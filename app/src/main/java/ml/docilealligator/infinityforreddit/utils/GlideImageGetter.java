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

    // Inline HTML images (notably flair emojis) are tiny but re-rendered constantly as recycled
    // views rebind. Glide's own cache still routes every request through an async callback, leaving
    // a blank frame where the icon flickers out and back. Keeping the decoded bitmaps here lets a
    // repeat render apply the image synchronously, so the icon never blanks. Capped by bytes so the
    // cache stays small regardless of how many distinct images are seen.
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

        // Reserve the final line height before the bitmap loads. Without this the placeholder has
        // zero bounds, so the image span takes no vertical space and the surrounding text (e.g. a
        // flair) sits at plain text height until the async load finishes, then jumps taller. That
        // height change shifts layout and yanks the scroll position whenever the view is (re)bound
        // — which recycled views like comment flairs do constantly. A square reserve matches the
        // typical aspect of flair emojis; sizeDrawable() corrects the width once the bitmap arrives,
        // keeping the height fixed so no vertical reflow occurs.
        int reservedHeight = (int) (enlargeImage ? textSize * 1.5 : textSize);
        drawable.setBounds(0, 0, reservedHeight, reservedHeight);

        // If we have already decoded this image, apply it synchronously so the icon shows on the
        // first frame (the caller sets the spannable right after this returns). This skips the async
        // Glide hop that would otherwise leave the icon blank for a frame and flicker on rebind.
        TextView host = container.get();
        if (host == null) {
            return drawable;
        }

        Bitmap cached = IMAGE_CACHE.get(source);
        if (cached != null && !cached.isRecycled()) {
            drawable.sizeDrawable(new BitmapDrawable(host.getResources(), cached));
            return drawable;
        }

        host.post(() -> {
            TextView textView = container.get();
            if (textView != null) {
                Context context = textView.getContext();
                if (!(context instanceof Activity && (((Activity) context).isFinishing() || ((Activity) context).isDestroyed()))) {
                    Glide.with(context)
                            .asBitmap()
                            .load(source)
                            .into(drawable);
                }
            });
        }

        return drawable;
    }

    private class BitmapDrawablePlaceholder extends BitmapDrawable implements Target<Bitmap> {

        @Nullable
        protected Drawable drawable;
        private final String source;

        BitmapDrawablePlaceholder(String source) {
            super(container.get().getResources(),
                    Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888));
            this.source = source;
        }

        @Override
        public void draw(final Canvas canvas) {
            if (drawable != null) {
                drawable.draw(canvas);
            }
        }

        /** Applies the image and its final bounds. Does not touch the host TextView. */
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
            // Re-set the text so the span is re-measured against the now-sized drawable.
            container.get().setText(container.get().getText());
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
                        // Cache an independent copy: Glide may recycle the delivered bitmap back to
                        // its pool once this target is cleared, which would leave a recycled bitmap
                        // in IMAGE_CACHE. The copy is small (inline icons) and outlives the request.
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
