package ml.docilealligator.infinityforreddit.utils;

import android.app.Activity;
import android.content.Context;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.text.Html;
import android.util.LruCache;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import com.bumptech.glide.Glide;
import com.bumptech.glide.request.Request;
import com.bumptech.glide.request.target.SizeReadyCallback;
import com.bumptech.glide.request.target.Target;
import com.bumptech.glide.request.transition.Transition;
import java.lang.ref.WeakReference;
import java.util.Objects;

public class GlideImageGetter implements Html.ImageGetter {

    /**
     * Decoded inline images, kept so a repeat render can apply one synchronously.
     *
     * <p>These are flair emojis and the like: a handful of tiny images that recur on row after row.
     * Glide caches them too, but every Glide request hands its result back through an asynchronous
     * callback, so the span is empty for at least one frame each time a view binds and the icon
     * blinks. Holding the decoded bitmaps here lets {@link #getDrawable} fill the span before the
     * caller sets the text, so a bitmap that has been seen once never blinks again.
     *
     * <p>Bounded by total bytes rather than entry count, so the cache stays small no matter how
     * many distinct images a session sees.
     */
    private static final LruCache<String, Bitmap> IMAGE_CACHE = new LruCache<>(4 * 1024 * 1024) {
        @Override
        protected int sizeOf(@NonNull String key, @NonNull Bitmap value) {
            return value.getByteCount();
        }
    };

    private final WeakReference<TextView> container;
    private boolean enlargeImage;
    @Nullable
    private final HtmlImagesHandler imagesHandler;
    private float density = 1.0f;
    private final float textSize;

    public GlideImageGetter(TextView textView, boolean enlargeImage) {
        this(textView, false, null);
        this.enlargeImage = enlargeImage;
    }

    public GlideImageGetter(TextView textView, boolean densityAware,
                            @Nullable HtmlImagesHandler imagesHandler) {
        this.container = new WeakReference<>(textView);
        this.imagesHandler = imagesHandler;
        if (densityAware) {
            density = textView.getResources().getDisplayMetrics().density;
        }
        textSize = textView.getTextSize();
    }

    @Override
    public Drawable getDrawable(String source) {
        if (imagesHandler != null) {
            imagesHandler.addImage(source);
        }

        BitmapDrawablePlaceholder drawable = new BitmapDrawablePlaceholder(source);

        TextView containerView = container.get();
        if (containerView == null) {
            return drawable;
        }

        // Already decoded: apply it now, before the caller sets the text, so the icon is there on
        // the very first frame instead of appearing one asynchronous hop later.
        Bitmap cached = IMAGE_CACHE.get(source);
        if (cached != null && !cached.isRecycled()) {
            drawable.sizeDrawable(new BitmapDrawable(containerView.getResources(), cached));
            return drawable;
        }

        containerView.post(() -> {
            TextView textView = container.get();
            if (textView != null) {
                Context context = textView.getContext();
                if (!(context instanceof Activity && (((Activity) context).isFinishing() || ((Activity) context).isDestroyed()))) {
                    Glide.with(context)
                            .asBitmap()
                            .load(source)
                            .into(drawable);
                }
            }
        });

        return drawable;
    }

    private class BitmapDrawablePlaceholder extends BitmapDrawable implements Target<Bitmap> {

        @Nullable
        protected Drawable drawable;
        private final String source;

        BitmapDrawablePlaceholder(String source) {
            super(Objects.requireNonNull(container.get()).getResources(),
                    Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888));
            this.source = source;
            // Reserve the line box before the bitmap arrives. Until setBounds runs the drawable is
            // empty, and ImageSpan takes the span's width and line ascent straight from its bounds,
            // so a flair icon occupies nothing until it loads and then snaps to full size, growing
            // the line, the header and the row, and shifting everything below it. The height
            // setDrawable() settles on comes from the text size and never from the bitmap, so it is
            // already known here and reserving it is exact. Only the width depends on the bitmap's
            // aspect ratio, and correcting that moves text along the line instead of moving rows.
            int reservedSize = (int) (enlargeImage ? textSize * 1.5 : textSize);
            setBounds(0, 0, reservedSize, reservedSize);
        }

        @Override
        public void draw(final Canvas canvas) {
            if (drawable != null) {
                drawable.draw(canvas);
            }
        }

        /** Applies the image and its final bounds without touching the host TextView. */
        private void sizeDrawable(Drawable drawable) {
            this.drawable = drawable;
            int drawableWidth = (int) (drawable.getIntrinsicWidth() * density);
            int drawableHeight = (int) (drawable.getIntrinsicHeight() * density);
            float ratio = (float) drawableWidth / (float) drawableHeight;
            drawableHeight = enlargeImage ? (int) (textSize * 1.5) : (int) textSize;
            drawableWidth = (int) (drawableHeight * ratio);
            drawable.setBounds(0, 0, drawableWidth, drawableHeight);
            setBounds(0, 0, drawableWidth, drawableHeight);
        }

        private void setDrawable(Drawable drawable) {
            sizeDrawable(drawable);

            // Re-set the text so the span is measured again against the now-sized drawable. Only
            // needed for a drawable that arrives after the text was set, which is why the
            // synchronous cache path in getDrawable() calls sizeDrawable() directly.
            TextView textView = container.get();
            if (textView != null) {
                textView.setText(textView.getText());
            }
        }

        @Override
        public void onLoadStarted(@Nullable Drawable placeholderDrawable) {
            if (placeholderDrawable != null) {
                setDrawable(placeholderDrawable);
            }
        }

        @Override
        public void onLoadFailed(@Nullable Drawable errorDrawable) {
            if (errorDrawable != null) {
                setDrawable(errorDrawable);
            }
        }

        @Override
        public void onResourceReady(@NonNull Bitmap bitmap, @Nullable Transition<? super Bitmap> transition) {
            if (container != null) {
                TextView textView = container.get();
                if (textView != null) {
                    Resources resources = textView.getResources();
                    if (resources != null) {
                        // Cache a copy, not the delivered bitmap: Glide may return that one to its
                        // pool when this target is cleared, which would leave a recycled bitmap in
                        // IMAGE_CACHE. Inline images are small, so the copy is cheap.
                        if (source != null && !bitmap.isRecycled()) {
                            Bitmap.Config config = bitmap.getConfig() != null
                                    ? bitmap.getConfig() : Bitmap.Config.ARGB_8888;
                            Bitmap copy = bitmap.copy(config, false);
                            if (copy != null) {
                                IMAGE_CACHE.put(source, copy);
                            }
                        }
                        setDrawable(new BitmapDrawable(resources, bitmap));
                    }
                }
            }
        }

        @Override
        public void onLoadCleared(@Nullable Drawable placeholderDrawable) {
            if (placeholderDrawable != null) {
                setDrawable(placeholderDrawable);
            }
        }

        @Override
        public void getSize(@NonNull SizeReadyCallback cb) {
            cb.onSizeReady(Target.SIZE_ORIGINAL, Target.SIZE_ORIGINAL);
        }

        @Override
        public void removeCallback(@NonNull SizeReadyCallback cb) {}

        @Override
        public void setRequest(@Nullable Request request) {}

        @Nullable
        @Override
        public Request getRequest() {
            return null;
        }

        @Override
        public void onStart() {}

        @Override
        public void onStop() {}

        @Override
        public void onDestroy() {}

    }

    public interface HtmlImagesHandler {
        void addImage(String uri);
    }
}
