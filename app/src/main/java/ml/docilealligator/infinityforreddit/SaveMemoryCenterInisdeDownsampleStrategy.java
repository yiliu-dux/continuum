package ml.docilealligator.infinityforreddit;

import com.bumptech.glide.load.resource.bitmap.DownsampleStrategy;

public class SaveMemoryCenterInisdeDownsampleStrategy extends DownsampleStrategy {

    private int threshold;

    public SaveMemoryCenterInisdeDownsampleStrategy(int threshold) {
        this.threshold = threshold;
    }

    @Override
    public float getScaleFactor(int sourceWidth, int sourceHeight, int requestedWidth, int requestedHeight) {
        int originalSourceWidth = sourceWidth;
        int originalSourceHeight = sourceHeight;
        if (sourceWidth * sourceHeight > threshold) {
            int divisor = 2;
            do {
                sourceWidth /= divisor;
                sourceHeight /= divisor;
            } while (sourceWidth * sourceHeight > threshold);
        }

        float widthPercentage = (float) requestedWidth / (float) sourceWidth;
        float heightPercentage = (float) requestedHeight / (float) sourceHeight;

        return Math.min((float) sourceWidth / (float) originalSourceWidth, (float) sourceHeight / (float) originalSourceHeight) * Math.min(1.f, Math.min(widthPercentage, heightPercentage));
    }

    @Override
    public SampleSizeRounding getSampleSizeRounding(int sourceWidth, int sourceHeight, int requestedWidth, int requestedHeight) {
        // QUALITY, not MEMORY. This decides how Glide rounds the scale factor above to a power-of-two
        // inSampleSize, and MEMORY rounds it the wrong way for anything that is being scaled down
        // even slightly.
        //
        // Glide computes an integer scaleFactor from the exact one and then, under MEMORY, doubles
        // the sample size whenever `powerOfTwoSampleSize < 1 / exactScaleFactor`. A scale factor
        // anywhere below 1 gives a sample size of 1 and a reciprocal above 1, so the test passes and
        // the decode is halved -- the image comes back at half the resolution it was asked for and
        // the view stretches it back to 2x. It is not a gradual loss either: a request for 954x636
        // from a 1080x720 source (scale 0.883) decodes 540x360.
        //
        // A tile therefore renders sharp only when the box happens to match the image exactly
        // (scale 1.0) and soft for every other size, which is why a gallery's first image is crisp
        // -- the tile is shaped from its preview -- and the rest of the images, sized to a box that
        // is not their shape, are not.
        //
        // Memory is still bounded, by the pixel threshold applied in getScaleFactor above. That cap
        // is this class's actual memory control; halving every downscaled decode on top of it was
        // costing 4x the pixels in quality to save memory that was already accounted for.
        return SampleSizeRounding.QUALITY;
    }

    public void setThreshold(int threshold) {
        this.threshold = threshold;
    }
}
