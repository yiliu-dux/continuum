package ml.docilealligator.infinityforreddit.adapters;

import android.graphics.PorterDuff;
import android.graphics.Typeface;
import android.graphics.drawable.Animatable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.text.TextUtils;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.bumptech.glide.RequestBuilder;
import com.bumptech.glide.RequestManager;
import com.bumptech.glide.load.DataSource;
import com.bumptech.glide.load.MultiTransformation;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.load.engine.GlideException;
import com.bumptech.glide.load.resource.bitmap.CenterCrop;
import com.bumptech.glide.request.RequestListener;
import com.bumptech.glide.request.RequestOptions;
import com.bumptech.glide.request.target.Target;
import io.noties.markwon.Markwon;
import java.util.ArrayList;
import java.util.function.Consumer;
import jp.wasabeef.glide.transformations.BlurTransformation;
import jp.wasabeef.glide.transformations.RoundedCornersTransformation;
import ml.docilealligator.infinityforreddit.SaveMemoryCenterInisdeDownsampleStrategy;
import ml.docilealligator.infinityforreddit.databinding.ItemGalleryImageInPostFeedBinding;
import ml.docilealligator.infinityforreddit.post.Post;

@SuppressWarnings("NullAway.Init")
public class PostGalleryTypeImageRecyclerViewAdapter extends RecyclerView.Adapter<PostGalleryTypeImageRecyclerViewAdapter.ImageViewHolder> {
    private final RequestManager glide;
    @Nullable
    private final Typeface typeface;
    private Markwon mPostDetailMarkwon;
    private final SaveMemoryCenterInisdeDownsampleStrategy saveMemoryCenterInisdeDownsampleStrategy;
    private final int mColorAccent;
    private final int mPrimaryTextColor;
    private int mCardViewColor;
    private int mCommentColor;
    private ArrayList<Post.Gallery> galleryImages;
    private boolean blurImage;
    private float ratio;
    private int maxPreviewHeight;
    private final boolean showCaption;
    private boolean isGridLayout;
    // Whether this post is eligible to animate its gifs at all: Video Autoplay is on (and, on the
    // "On Wi-Fi" setting, we are on Wi-Fi), and the post is not one autoplay skips (NSFW with
    // "Autoplay NSFW videos" off, or a spoiler). Set by the host adapter on every bind.
    private boolean autoplayGif;
    // Whether the host RecyclerView's autoplay coordinator has currently selected this post to
    // play, honouring Settings -> Video -> "Simultaneous autoplay limit" across the whole feed.
    private boolean playing;
    // The gallery page the pager is settled on. Only that tile animates -- the neighbouring pages
    // RecyclerView keeps bound for a smooth swipe are off screen, and animating them would spend
    // the autoplay budget on frames nobody sees.
    private int currentPosition;
    @Nullable
    private RecyclerView attachedRecyclerView;

    public PostGalleryTypeImageRecyclerViewAdapter(RequestManager glide, @Nullable Typeface typeface,
                                                   SaveMemoryCenterInisdeDownsampleStrategy saveMemoryCenterInisdeDownsampleStrategy,
                                                   int mColorAccent, int mPrimaryTextColor) {
        this.glide = glide;
        this.typeface = typeface;
        this.saveMemoryCenterInisdeDownsampleStrategy = saveMemoryCenterInisdeDownsampleStrategy;
        this.mColorAccent = mColorAccent;
        this.mPrimaryTextColor = mPrimaryTextColor;
        showCaption = false;
    }

    public PostGalleryTypeImageRecyclerViewAdapter(RequestManager glide, @Nullable Typeface typeface, Markwon postDetailMarkwon,
                                                   SaveMemoryCenterInisdeDownsampleStrategy saveMemoryCenterInisdeDownsampleStrategy,
                                                   int mColorAccent, int mPrimaryTextColor, int mCardViewColor,
                                                   int mCommentColor) {
        this.glide = glide;
        this.typeface = typeface;
        this.mPostDetailMarkwon = postDetailMarkwon;
        this.saveMemoryCenterInisdeDownsampleStrategy = saveMemoryCenterInisdeDownsampleStrategy;
        this.mColorAccent = mColorAccent;
        this.mPrimaryTextColor = mPrimaryTextColor;
        this.mCardViewColor = mCardViewColor;
        this.mCommentColor = mCommentColor;
        showCaption = true;
    }

    @NonNull
    @Override
    public ImageViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new ImageViewHolder(ItemGalleryImageInPostFeedBinding.inflate(LayoutInflater.from(parent.getContext()), parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull ImageViewHolder holder, int position) {
        if (isGridLayout) {
            // Grid tiles are square by construction, so the post's preview shape does not apply.
            holder.binding.imageViewItemGalleryImageInPostFeed.setScaleType(ImageView.ScaleType.CENTER_CROP);
            holder.binding.imageViewItemGalleryImageInPostFeed.setRatioMaxHeight(0);
            holder.binding.imageViewItemGalleryImageInPostFeed.setRatio(1);
        } else {
            // Every tile in a gallery is given the same shape, taken from the post's first preview,
            // so the other images in it need not match. Only the square preview is a shape none of
            // them was measured against, and it is the one that has to crop to fill; sizing from
            // the post's own ratio letterboxes instead, which is what this has always done.
            // maxPreviewHeight is set alongside the square ratio and left at 0 otherwise, so it
            // distinguishes the two.
            holder.binding.imageViewItemGalleryImageInPostFeed.setScaleType(
                    maxPreviewHeight > 0 ? ImageView.ScaleType.CENTER_CROP : ImageView.ScaleType.FIT_CENTER);
            holder.binding.imageViewItemGalleryImageInPostFeed.setRatioMaxHeight(maxPreviewHeight);
            holder.binding.imageViewItemGalleryImageInPostFeed.setRatio(ratio);
        }
        holder.binding.errorTextViewItemGalleryImageInPostFeed.setVisibility(View.GONE);
        holder.binding.progressBarItemGalleryImageInPostFeed.setVisibility(View.VISIBLE);

        ImageView imageView = holder.binding.imageViewItemGalleryImageInPostFeed;

        // Nothing has been decoded for what this holder is about to show.
        holder.loadedWidth = 0;
        holder.loadedHeight = 0;

        // The tile watches its own layout for as long as it is bound, and every pass asks for the
        // image again at the size the tile should be -- see requestBox(), which is what decides
        // that, deliberately not trusting the view's own measurements. Loading once at the first
        // plausible moment is what this used to do, and there is no such moment: the sizes a tile
        // is measured at before it settles range from a hundred pixels to several thousand.
        if (holder.layoutListener == null) {
            holder.layoutListener = (v, l, t, r, b, oldL, oldT, oldR, oldB) -> loadImageIfNeeded(holder);
            imageView.addOnLayoutChangeListener(holder.layoutListener);
        }

        loadImageIfNeeded(holder);

        if (showCaption) {
            loadCaptionPreview(holder);
        }
    }

    @Override
    public int getItemCount() {
        return galleryImages == null ? 0 : galleryImages.size();
    }

    @Override
    public void onAttachedToRecyclerView(@NonNull RecyclerView recyclerView) {
        super.onAttachedToRecyclerView(recyclerView);
        attachedRecyclerView = recyclerView;
        recyclerView.addOnScrollListener(settleListener);
    }

    @Override
    public void onDetachedFromRecyclerView(@NonNull RecyclerView recyclerView) {
        super.onDetachedFromRecyclerView(recyclerView);
        recyclerView.removeOnScrollListener(settleListener);
        attachedRecyclerView = null;
    }

    @Override
    public void onViewRecycled(@NonNull ImageViewHolder holder) {
        super.onViewRecycled(holder);
        if (holder.layoutListener != null) {
            holder.binding.imageViewItemGalleryImageInPostFeed.removeOnLayoutChangeListener(holder.layoutListener);
            holder.layoutListener = null;
        }
        holder.loadedWidth = 0;
        holder.loadedHeight = 0;
        holder.binding.captionConstraintLayoutItemGalleryImageInPostFeed.setVisibility(View.GONE);
        holder.binding.captionTextViewItemGalleryImageInPostFeed.setText("");
        holder.binding.captionUrlTextViewItemGalleryImageInPostFeed.setText("");
        holder.binding.progressBarItemGalleryImageInPostFeed.setVisibility(View.GONE);
        holder.binding.errorImageViewItemGalleryImageInPostFeed.setVisibility(View.GONE);
        glide.clear(holder.binding.imageViewItemGalleryImageInPostFeed);
    }

    /**
     * Issues the load unless the image already on screen was decoded for a box at least this big.
     *
     * <p>The size asked for is {@link #requestBox}'s, not the view's, so this runs on every layout
     * without a bad measurement ever reaching Glide.
     */
    private void loadImageIfNeeded(ImageViewHolder holder) {
        int[] box = requestBox(holder);
        if (box[0] <= holder.loadedWidth && box[1] <= holder.loadedHeight) {
            return;
        }
        // Not while the pager is moving. Glide's into() clears the ImageView before it starts, and
        // setImageDrawable(null) calls requestLayout(); a layout pass in the middle of a drag
        // re-anchors the pager and throws away the scroll that had accumulated. A tile that has
        // nothing on it yet is exempt -- that is the image first appearing, not a reload -- and
        // anything deferred here is picked up by settleListener once the pager stops.
        if (holder.loadedWidth > 0 && attachedRecyclerView != null
                && attachedRecyclerView.getScrollState() != RecyclerView.SCROLL_STATE_IDLE) {
            return;
        }
        loadImage(holder, box);
    }

    /**
     * Re-checks every attached tile once the pager settles, so a load {@link #loadImageIfNeeded}
     * declined to issue mid-scroll is not lost.
     */
    private final RecyclerView.OnScrollListener settleListener = new RecyclerView.OnScrollListener() {
        @Override
        public void onScrollStateChanged(@NonNull RecyclerView recyclerView, int newState) {
            if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                forEachAttachedHolder(PostGalleryTypeImageRecyclerViewAdapter.this::loadImageIfNeeded);
            }
        }
    };

    /**
     * The box to decode for, which is the view's own measurements only when they are credible.
     *
     * <p>The view's size cannot simply be trusted. A horizontal RecyclerView whose own width is
     * unconstrained -- which is what a weighted split layout hands it before it has distributed
     * widths -- sizes itself from its children, and with {@code match_parent} tiles that feeds
     * back: the row takes its children's width, the children are measured against the wider row,
     * and each pass multiplies it again. Measured on a 1968px display, one tile was handed 119px
     * and 4888px within the same second. Decoding for either is wrong, and the 119px one is
     * catastrophic -- the bitmap it yields is then stretched across the real tile.
     *
     * <p>So a measurement is used only when it is between half of and all of the width the row
     * will settle at; anything else falls back to that settled width, which is derived from the
     * display rather than from a measurement in flight. The load therefore always happens -- there
     * is no state in which a tile waits forever for a size it likes -- and always at a size the
     * tile can actually be.
     */
    private int[] requestBox(ImageViewHolder holder) {
        ImageView imageView = holder.binding.imageViewItemGalleryImageInPostFeed;
        int settledWidth = settledTileWidth(imageView);
        int width = imageView.getWidth();
        int height = imageView.getHeight();
        if (width < (settledWidth + 1) / 2 || width > settledWidth || height <= 0) {
            width = settledWidth;
            height = ratio > 0 ? (int) (width * ratio) : width;
            if (maxPreviewHeight > 0 && height > maxPreviewHeight) {
                height = maxPreviewHeight;
            }
        }
        return new int[]{Math.max(1, width), Math.max(1, height)};
    }

    /**
     * The width one tile settles at: the row's width divided between its columns, with the row
     * itself capped to the display. The cap is what keeps a runaway measurement out -- a row can
     * report itself wider than the screen, but a tile is never actually drawn wider than that.
     */
    private int settledTileWidth(ImageView imageView) {
        int displayWidth = imageView.getResources().getDisplayMetrics().widthPixels;
        int rowWidth = attachedRecyclerView == null ? 0 : attachedRecyclerView.getWidth();
        if (rowWidth <= 0 || rowWidth > displayWidth) {
            rowWidth = displayWidth;
        }
        int spanCount = 1;
        if (attachedRecyclerView != null
                && attachedRecyclerView.getLayoutManager() instanceof GridLayoutManager) {
            spanCount = Math.max(1, ((GridLayoutManager) attachedRecyclerView.getLayoutManager()).getSpanCount());
        }
        return Math.max(1, rowWidth / spanCount);
    }

    private void loadImage(ImageViewHolder holder) {
        loadImage(holder, requestBox(holder));
    }

    private void loadImage(ImageViewHolder holder, int[] box) {
        if (galleryImages == null || galleryImages.isEmpty()) {
            return;
        }
        int index = holder.getBindingAdapterPosition();
        if (index < 0 || index >= galleryImages.size()) {
            return;
        }

        ImageView imageView = holder.binding.imageViewItemGalleryImageInPostFeed;
        // The box this request is sized for. Recorded before the request goes out so a later
        // layout can tell whether the tile has outgrown what is on screen.
        holder.loadedWidth = box[0];
        holder.loadedHeight = box[1];

        Post.Gallery galleryImage = galleryImages.get(index);
        // Prefer the resolution-bounded feed preview, which for a gif is a static still. The source
        // -- the animated gif itself, often tens of MB -- is loaded only for the one tile that is
        // playing (issue #382), or when there is no usable preview to fall back on. The full-screen
        // media view is unaffected — it loads `url` directly.
        boolean loadSource = shouldAnimate(index) || galleryImage.feedPreviewUrl == null;
        String loadUrl = loadSource ? galleryImage.url : galleryImage.feedPreviewUrl;

        // A still is worth caching decoded (ALL); an animated gif must not be. Its decoded resource
        // is the animation library's GifDecoder, which has no Glide result encoder, so ALL fails
        // the load outright with NoResultEncoderAvailableException — which also left a gif with no
        // still to fall back on showing the error tile. DATA caches the downloaded bytes instead,
        // which is the expensive half anyway.
        boolean animatedResource = loadSource && galleryImage.mediaType == Post.Gallery.TYPE_GIF;

        RequestBuilder<Drawable> imageRequestBuilder = glide.load(loadUrl)
                // Sized explicitly rather than from the view, which Glide would otherwise read at
                // whatever an in-flight layout pass left behind. See requestBox().
                .override(box[0], box[1])
                .diskCacheStrategy(animatedResource ? DiskCacheStrategy.DATA : DiskCacheStrategy.ALL)
                .listener(new RequestListener<>() {
            @Override
            public boolean onLoadFailed(@Nullable GlideException e, Object model, Target<Drawable> target, boolean isFirstResource) {
                holder.binding.progressBarItemGalleryImageInPostFeed.setVisibility(View.GONE);
                if (isGridLayout) {
                    holder.binding.errorImageViewItemGalleryImageInPostFeed.setVisibility(View.VISIBLE);
                } else {
                    holder.binding.errorTextViewItemGalleryImageInPostFeed.setVisibility(View.VISIBLE);
                }
                return false;
            }

            @Override
            public boolean onResourceReady(Drawable resource, Object model, Target<Drawable> target, DataSource dataSource, boolean isFirstResource) {
                holder.binding.errorImageViewItemGalleryImageInPostFeed.setVisibility(View.GONE);
                holder.binding.errorTextViewItemGalleryImageInPostFeed.setVisibility(View.GONE);
                holder.binding.progressBarItemGalleryImageInPostFeed.setVisibility(View.GONE);
                if (resource instanceof Animatable) {
                    // A gif with no usable still to fall back on lands here even when this tile is
                    // not the one playing. Glide starts the animation immediately after this
                    // callback returns, so undo it on the next loop rather than from here.
                    holder.binding.imageViewItemGalleryImageInPostFeed.post(() -> {
                        if (!shouldAnimate(holder.getBindingAdapterPosition())) {
                            stopAnimation(holder);
                        }
                    });
                }
                return false;
            }
        });
        if (blurImage) {
            if (isGridLayout) {
                imageRequestBuilder
                        .apply(RequestOptions.bitmapTransform(new MultiTransformation<>(new CenterCrop(), new RoundedCornersTransformation(32, 0), new BlurTransformation(50, 2))))
                        .into(imageView);
            } else {
                imageRequestBuilder.apply(RequestOptions.bitmapTransform(new BlurTransformation(50, 10)))
                        .into(imageView);
            }
        } else {
            if (isGridLayout) {
                imageRequestBuilder
                        .apply(RequestOptions.bitmapTransform(new MultiTransformation<>(new CenterCrop(), new RoundedCornersTransformation(32, 0))))
                        .downsample(saveMemoryCenterInisdeDownsampleStrategy).into(imageView);
            } else if (imageView.getScaleType() == ImageView.ScaleType.CENTER_CROP) {
                // Decode to fill the tile, because the tile crops to fill. centerInside() would
                // decode to fit *inside* the box and the view would then scale that up to cover,
                // by the image's entire aspect mismatch with the box -- several times over for a
                // tall or panoramic image in the square tile, and it looks it.
                imageRequestBuilder.centerCrop().downsample(saveMemoryCenterInisdeDownsampleStrategy).into(imageView);
            } else {
                imageRequestBuilder.centerInside().downsample(saveMemoryCenterInisdeDownsampleStrategy).into(imageView);
            }
        }
    }

    /**
     * Whether the tile at {@code index} should be showing a running gif right now: this post is
     * eligible, the autoplay coordinator has picked it, and {@code index} is the page the pager is
     * settled on. Grid layout is excluded — every tile is on screen at once there, so animating
     * them would mean fetching every gif in the gallery at full size.
     */
    private boolean shouldAnimate(int index) {
        return playing && index == currentPosition && canAnimateCurrentTile();
    }

    /**
     * Whether the settled page is a gif this post is allowed to animate. Used by the host
     * ViewHolder to decide whether to ask for one of the autoplay slots at all, so a gallery
     * sitting on a still image never takes a slot from a video.
     */
    public boolean canAnimateCurrentTile() {
        return autoplayGif && !isGridLayout && !blurImage
                && galleryImages != null && currentPosition >= 0 && currentPosition < galleryImages.size()
                && galleryImages.get(currentPosition).mediaType == Post.Gallery.TYPE_GIF;
    }

    public void setAutoplayGif(boolean autoplayGif) {
        this.autoplayGif = autoplayGif;
    }

    /** Called by the host ViewHolder when the autoplay coordinator starts or stops this post. */
    public void setPlaying(boolean playing) {
        if (this.playing == playing) {
            return;
        }
        this.playing = playing;
        if (playing) {
            startCurrentTile();
        } else {
            forEachAttachedHolder(this::stopAnimation);
        }
    }

    /**
     * Called by the host ViewHolder when the pager settles. Returns whether the settled page
     * actually changed, i.e. whether which tile may animate has to be reconsidered.
     */
    public boolean setCurrentPosition(int currentPosition) {
        if (this.currentPosition == currentPosition) {
            return false;
        }
        this.currentPosition = currentPosition;
        if (!playing) {
            return true;
        }
        forEachAttachedHolder(holder -> {
            if (holder.getBindingAdapterPosition() != this.currentPosition) {
                stopAnimation(holder);
            }
        });
        startCurrentTile();
        return true;
    }

    // The settled tile may already hold the animated gif (paused, or scrolled back to), in which
    // case it only needs restarting; otherwise it is showing the still and has to load the source.
    private void startCurrentTile() {
        if (!shouldAnimate(currentPosition)) {
            return;
        }
        ImageViewHolder holder = findAttachedHolder(currentPosition);
        if (holder == null) {
            // Not bound yet — loadImage() consults shouldAnimate() when it is.
            return;
        }
        Drawable drawable = holder.binding.imageViewItemGalleryImageInPostFeed.getDrawable();
        if (drawable instanceof Animatable) {
            ((Animatable) drawable).start();
        } else {
            loadImage(holder);
        }
    }

    private void stopAnimation(ImageViewHolder holder) {
        Drawable drawable = holder.binding.imageViewItemGalleryImageInPostFeed.getDrawable();
        if (drawable instanceof Animatable && ((Animatable) drawable).isRunning()) {
            // Leave the frame it stopped on: it is a frame of the gif itself, so freezing beats
            // reloading the still and flashing a different image in its place.
            ((Animatable) drawable).stop();
        }
    }

    @Nullable
    private ImageViewHolder findAttachedHolder(int position) {
        if (attachedRecyclerView == null) {
            return null;
        }
        RecyclerView.ViewHolder holder = attachedRecyclerView.findViewHolderForAdapterPosition(position);
        return holder instanceof ImageViewHolder ? (ImageViewHolder) holder : null;
    }

    private void forEachAttachedHolder(Consumer<ImageViewHolder> action) {
        if (attachedRecyclerView == null) {
            return;
        }
        for (int i = 0; i < attachedRecyclerView.getChildCount(); i++) {
            RecyclerView.ViewHolder holder =
                    attachedRecyclerView.getChildViewHolder(attachedRecyclerView.getChildAt(i));
            if (holder instanceof ImageViewHolder) {
                action.accept((ImageViewHolder) holder);
            }
        }
    }

    private void loadCaptionPreview(ImageViewHolder holder) {
        if (galleryImages == null || galleryImages.isEmpty()) {
            return;
        }

        int index = holder.getBindingAdapterPosition();
        if (index < 0 || index >= galleryImages.size()) {
            return;
        }

        String previewCaption = galleryImages.get(index).caption;
        String previewCaptionUrl = galleryImages.get(index).captionUrl;
        boolean previewCaptionIsEmpty = TextUtils.isEmpty(previewCaption);
        boolean previewCaptionUrlIsEmpty = TextUtils.isEmpty(previewCaptionUrl);
        if (!previewCaptionIsEmpty || !previewCaptionUrlIsEmpty) {
            holder.binding.captionConstraintLayoutItemGalleryImageInPostFeed.setBackgroundColor(mCardViewColor & 0x0D000000); // Make 10% darker than CardViewColor
            holder.binding.captionConstraintLayoutItemGalleryImageInPostFeed.setVisibility(View.VISIBLE);
        }
        if (!previewCaptionIsEmpty) {
            holder.binding.captionTextViewItemGalleryImageInPostFeed.setTextColor(mCommentColor);
            holder.binding.captionTextViewItemGalleryImageInPostFeed.setText(previewCaption);
            holder.binding.captionTextViewItemGalleryImageInPostFeed.setSelected(true);
        }
        if (!previewCaptionUrlIsEmpty) {
            String host = Uri.parse(previewCaptionUrl).getHost();
            // A URL with no authority has no host; label the link with the URL rather than "null".
            String domain = host == null ? previewCaptionUrl : (host.startsWith("www.") ? host.substring(4) : host);
            mPostDetailMarkwon.setMarkdown(holder.binding.captionUrlTextViewItemGalleryImageInPostFeed, String.format("[%s](%s)", domain, previewCaptionUrl));
        }
    }

    public void setGalleryImages(@Nullable ArrayList<Post.Gallery> galleryImages) {
        this.galleryImages = galleryImages != null ? galleryImages : new java.util.ArrayList<>();
        // A recycled holder is showing a different post now, so its pager starts back at page one.
        currentPosition = 0;
        notifyDataSetChanged();
    }

    public void setBlurImage(boolean blurImage) {
        this.blurImage = blurImage;
    }

    /**
     * @param ratio height-to-width ratio for every tile, always positive: the gallery images' own
     *              ratio, or {@code 1} for the square "Fixed Height in Card" preview.
     */
    public void setRatio(float ratio) {
        this.ratio = ratio;
    }

    /**
     * @param maxPreviewHeight ceiling in pixels for a tile's height, or {@code 0} for none. Only
     *                         the square preview needs one -- a tile sized from the image's own
     *                         ratio is already the shape the caller asked for.
     */
    public void setMaxPreviewHeight(int maxPreviewHeight) {
        this.maxPreviewHeight = maxPreviewHeight;
    }

    public void setIsGridLayout(boolean isGridLayout) {
        this.isGridLayout = isGridLayout;
    }

    class ImageViewHolder extends RecyclerView.ViewHolder {

        ItemGalleryImageInPostFeedBinding binding;
        // Watches the tile's layout for as long as it is bound, so a box that grows after the
        // image was decoded gets a load at the new size. Tracked so it can be removed on recycle.
        @Nullable
        View.OnLayoutChangeListener layoutListener;
        // The box the request currently on screen was sized for, or 0 when nothing has loaded for
        // this holder's contents yet.
        int loadedWidth;
        int loadedHeight;

        public ImageViewHolder(ItemGalleryImageInPostFeedBinding binding) {
            super(binding.getRoot());

            this.binding = binding;

            if (typeface != null) {
                binding.errorTextViewItemGalleryImageInPostFeed.setTypeface(typeface);
            }
            binding.progressBarItemGalleryImageInPostFeed.setIndicatorColor(mColorAccent);
            binding.errorTextViewItemGalleryImageInPostFeed.setTextColor(mPrimaryTextColor);
            binding.errorImageViewItemGalleryImageInPostFeed.setColorFilter(
                    // mPrimaryTextColor is the correct color here.
                    mPrimaryTextColor,
                    PorterDuff.Mode.SRC_IN
            );

            binding.errorTextViewItemGalleryImageInPostFeed.setOnClickListener(view -> {
                binding.progressBarItemGalleryImageInPostFeed.setVisibility(View.VISIBLE);
                binding.errorTextViewItemGalleryImageInPostFeed.setVisibility(View.GONE);
                loadImage(this);
            });

            binding.errorImageViewItemGalleryImageInPostFeed.setOnClickListener(view -> {
                binding.progressBarItemGalleryImageInPostFeed.setVisibility(View.VISIBLE);
                binding.errorImageViewItemGalleryImageInPostFeed.setVisibility(View.GONE);
                loadImage(this);
            });
        }
    }
}
