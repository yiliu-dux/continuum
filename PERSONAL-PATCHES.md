# Personal patches

Fork-local behaviour changes that are not upstream, kept here so they can be recognised and
re-applied after a rebase onto `cygnusx-1-org/continuum`. Each section says what the symptom was,
what actually caused it, and what the fix touches.

Base at the time of writing: upstream `1a5cac082` ("Preloaded compact thumbnails so they appear
without pop-in").

An earlier round of these patches lives on the `personal-patches` branch as commits `95c2e5625`,
`e5c7494ff` and `ea90ad426`. They are superseded by what is described here — upstream has since
fixed part of what they covered, and the rest needed different fixes. Keep the branch for reference
only; do not replay those commits.

## Merge map

Every file this fork changes, and why. Line numbers throughout this document are approximate and
will drift; the named methods are the reliable anchors.

| File | Issue | What is fork-local |
| --- | --- | --- |
| `SaveMemoryCenterInisdeDownsampleStrategy.java` | 4a | `getSampleSizeRounding` returns `QUALITY` |
| `adapters/PostGalleryTypeImageRecyclerViewAdapter.java` | 4b, 4c | `requestBox`, `settledTileWidth`, `loadImageIfNeeded`, `override()`, the `CENTER_CROP` branch |
| `adapters/PostRecyclerViewAdapter.java` | 1 | `DISABLE_FORCED_COMPACT_LAYOUT`, `hasNothingToPreview` and its two bind sites |
| `adapters/CommentsRecyclerViewAdapterNew.java` | 2a, 2c, 2d | `markdownRenderKey`, `mMarkdownRenderGeneration`, `hasNoTextSelection`, the flair `rendered` guard |
| `markdown/CustomMarkwonAdapter.java` | 2d | `forwardLongClickToBlock` and its two call sites |
| `utils/Utils.java` | 2a | `setHTMLWithImageToTextView` identity guard and boolean return |
| `utils/GlideImageGetter.java` | 2b | `IMAGE_CACHE`, `sizeDrawable` |
| `res/values/ids.xml` | 2a | `html_image_content_tag` |
| `activities/ViewVideoActivity.java` | 3 | `clampPosition`, focal anchoring in `onScale`, `ACTION_POINTER_UP` |

Issue 4a is a one-line change and carries most of the value in this document — if a merge goes badly
and things have to be re-applied one at a time, start there.

Two changes alter shared behaviour beyond the feature they were made for, so re-check them if
something unrelated looks different after a merge: the `QUALITY` rounding (4a) affects every image
in the app, and `setHTMLWithImageToTextView` (2a) gained a return value its callers must honour.

---

## 1. Link posts with no preview drop out of the feed's layout

**Symptom.** A link post with no preview image renders as a compact row in the middle of a card
feed, so it reads as a different kind of post.

**Cause.** `PostRecyclerViewAdapter.shouldUseCompactLayout()` returns true for any post with no
preview and no usable thumbnail, and `viewTypeFor()` then swaps the row's view type for
`VIEW_TYPE_POST_COMPACT` (or `VIEW_TYPE_POST_CARD_2_COMPACT_LINK` in the Card 2 layout).

Simply disabling that swap is not enough on its own. The card then falls through to the
`preview == null` branch of the bind, which makes `imageViewNoPreviewGallery` visible: a 150dp band
of flat colour with a chain-link glyph in the middle, which looks worse than the compact row did.

**Fix.** `app/src/main/java/ml/docilealligator/infinityforreddit/adapters/PostRecyclerViewAdapter.java`

- `DISABLE_FORCED_COMPACT_LAYOUT` (line ~1888) — a guard clause at the top of
  `shouldUseCompactLayout()`, so the post keeps whatever layout the feed is set to. Written as a
  guard rather than by editing the expression below it, so upstream changes to the original rule
  keep merging cleanly.
- `hasNothingToPreview()` (line ~1907) — true for a **link** post with neither preview nor
  thumbnail. Where it holds, the bind hides the preview box entirely (line ~1225) instead of drawing
  the placeholder, so the card is title + domain + toolbar.

Only link posts lose the placeholder. An image, gif, video or gallery post with a missing preview
still shows its glyph — that is the same placeholder data-saving mode draws for a preview it is
deliberately suppressing, and it tells you there is something to open.

The bind hides `imageWrapperFrameLayout`, `imageView` and `videoOrGifIndicator` explicitly rather
than relying on `onViewRecycled`, which only runs when a holder actually returns to the pool. A
holder rebound in place would otherwise keep the previous post's preview box.

---

## 2. Comment images refresh on collapse, and comments full of images will not collapse

Four separate causes behind one reported symptom. Upstream had already fixed a fifth: the
bounds reservation in `GlideImageGetter.BitmapDrawablePlaceholder` landed upstream independently,
which is why flair icons no longer collapse to zero height and drag the layout with them. What
remained was the blink.

### 2a. Author flair re-rendered on every bind

**Cause.** `Utils.setHTMLWithImageToTextView()` builds a fresh spannable each call. Its image spans
start out empty and are filled in by an asynchronous Glide load, so every rebind blinks the icons
out and back — and rows rebind constantly, since collapsing one comment rebinds it and the rows
around it.

**Fix.** `utils/Utils.java` (line ~378) — skip the render when the view already shows exactly this
content, tracked with a tag on `R.id.html_image_content_tag` (declared in `res/values/ids.xml`).

The method now returns a boolean: true when it rendered, false when it left the view alone. Callers
that post-process the text **must** honour it, or they stack their change on every rebind. The only
such caller is the Recovered badge in `CommentsRecyclerViewAdapterNew` (line ~470), which also
passes `"recovered"` as the `variantKey` so the badge is part of the identity being compared — a
holder reused for a comment with the same flair but a different recovered state must re-render.

### 2b. Icons blink on a genuinely fresh render

**Cause.** Even on a Glide cache hit, the result arrives through an asynchronous callback, so a
recycled holder showing a *different* comment with the *same* flair emoji still blinks.

**Fix.** `utils/GlideImageGetter.java` (line ~37) — a static `LruCache` of decoded bitmaps, bounded
by bytes. `getDrawable()` applies a cached bitmap synchronously via the new `sizeDrawable()`
(line ~132), which sets the image and its bounds without touching the host TextView;
`setDrawable()` keeps the `setText()` re-measure for drawables that arrive after the text was set.

`onResourceReady` caches a **copy**, not the delivered bitmap — Glide may return that one to its
pool when the target is cleared, which would leave a recycled bitmap in the cache.

### 2c. Comment body images reloaded on every bind

The largest of the four, and not covered by the old patches at all.

**Cause.** `CommentsRecyclerViewAdapterNew.onBindViewHolder` called `setMarkdown()` +
`notifyDataSetChanged()` on the nested Markwon adapter unconditionally, which re-parses the body and
restarts every image and gif load in it.

**Fix.** `adapters/CommentsRecyclerViewAdapterNew.java` — `markdownRenderKey()` (line ~907) and the
guard around the re-render (line ~605). The key is comment id + markdown + the identity of the media
metadata map + `mMarkdownRenderGeneration`.

Two details that matter if this is ever touched again:

- The media metadata map is compared **by identity**. The view model carries it by reference through
  the `Comment` copies it makes on expand and collapse, so identity is stable for an unchanged
  comment and differs once the comment is fetched afresh.
- `mMarkdownRenderGeneration` (line ~143) covers settings the renderer reads but the comment does
  not carry: image blur, data saving, comment-gif autoplay. `applyImageBlur()` bumps it **only on an
  actual change** — it runs on every data emission, and bumping unconditionally would re-render
  every body each time and undo the whole fix.

The `mImageAndGifEntry` / `mVideoEntry` `setCurrent*` calls are deliberately left outside the guard
and still run on every bind.

### 2d. A comment made mostly of images would not collapse

**Cause.** Two independent faults, both of which had to be fixed:

1. The image inside an `ImageAndGifBlock` has its own click listener (it opens the media), which
   makes the view consume touches. The long press never reached the item view that carries the
   collapse listener. Same for the frame inside a `VideoBlock`.
2. Even when it did reach the adapter, the listener was written as
   `if (view instanceof TextView) { ...check selection...; collapse(); }` — so anything that was not
   a TextView was silently dropped.

Upstream commit `d165d1187` (11 Sep 2026, "Rendered hard-break-terminated image and GIF lines as
blocks in comments") is what made this visible: more of a comment's area became block nodes, so more
of it became dead to the gesture.

**Fix.**

- `markdown/CustomMarkwonAdapter.java` — `forwardLongClickToBlock()` (line ~219), applied to the
  image (line ~193) and to the video frame (line ~175). It clears a stale forwarder when there is no
  listener, because holders come from a shared pool.
- `adapters/CommentsRecyclerViewAdapterNew.java` — `hasNoTextSelection()` (line ~1587) replaces the
  `instanceof TextView` gate in both long-press listeners. Only a TextView can have a selection
  worth protecting; everything else should act on the comment.

---

## 3. Video view jumps when releasing a pinch

**Symptom.** The video leaps sideways when you lift your fingers after zooming.

**Cause.** Three things in `ViewVideoActivity`, all of which land on release:

1. **`ACTION_POINTER_UP` was not handled.** `lastTouchX/Y` still held a coordinate from before the
   pinch, and after the lift `ev.getX()` reports whichever pointer remains — possibly the *other*
   finger. The first `ACTION_MOVE` after the release measured its delta against that stale anchor,
   so the frame jumped by roughly the distance between the two fingers. This is the big one.
2. **No translation clamp during scaling.** Zooming out left a pan translation the new scale no
   longer permits. Nothing corrected it until something else clamped — the first pan, or
   `resetPosition()` — at which point it snapped.
3. **`onScaleEnd` recentred the pan** when the scale landed within 0.05 of the fit scale. Harmless
   unrotated, where the fit scale is 1 and the clamp leaves no slack anyway, but a rotated video's
   fit scale is above 1 and still has room to pan, so this threw the view across the screen.

**Fix.** `app/src/main/java/ml/docilealligator/infinityforreddit/activities/ViewVideoActivity.java`

- `ACTION_POINTER_UP` case (line ~1237) — re-anchor `lastTouchX/Y` to the pointer that is staying
  down. Indices are compacted after a pointer goes up, so the coordinate read here is the one
  `ev.getX()` will report next.
- `clampPosition()` (line ~1107) — extracted from the pan path and now also called from `onScale`
  (line ~1161) and from the `onScaleEnd` snap (line ~1184, clamping instead of recentring).
- Focal-point anchoring in `onScale` (line ~1137). The frame scales about its own centre pivot, so
  each scale step is paired with the pan translation that cancels the slide the focal point would
  otherwise undergo. The scale is isotropic, so this is independent of the frame's rotation — it
  holds at 90° and 270° as well.

The focal anchoring is a during-pinch fix rather than a release fix, but without it the content
slides out from under your fingers and the release reads as part of the same jump.

---

## 4. Gallery images render pixelated in post detail

**Symptom.** Open a gallery post and swipe: the first image is sharp, most of the rest are visibly
soft, and large images are the worst. Sometimes the first one degrades from sharp to soft a moment
after opening.

Diagnosed from on-device logs rather than by reading, after two rounds of plausible-looking fixes
that changed nothing. The method that worked, and is worth repeating if this resurfaces: log the
three sizes that decide sharpness — what the server delivered, what was decoded, and the box it is
drawn in — and compare an image that looks right against one that does not. `FIT_CENTER` with a
correctly sized decode cannot produce a soft image, so whenever one appears, either the view crops
instead of fitting, or the decode was the wrong size. There is no third option, and that narrows it
quickly.

### 4a. Every downscaled decode came back at half resolution

The cause of the reported symptom, and by far the largest effect.

**Cause.** `SaveMemoryCenterInisdeDownsampleStrategy.getSampleSizeRounding()` returned
`SampleSizeRounding.MEMORY`. That decides how Glide rounds a scale factor to a power-of-two
`inSampleSize`, and under `MEMORY` Glide doubles the sample size whenever
`powerOfTwoSampleSize < 1 / exactScaleFactor`. **Any** scale factor below 1 satisfies that, so every
image being scaled down at all was decoded at half the resolution asked for and stretched back up by
the view. It is not a gradual loss — 954x636 requested from a 1080x720 source decoded 540x360.

The measured split was absolute, with nothing in between:

```
scale=1.000 -> bitmap 1080x1438, box 1080x1438, draw 1.00x   (sharp)
scale=0.883 -> bitmap  540x360,  box  954x636,  draw 1.77x   (soft)
```

This is also why the *first* gallery image looked right: the tile box is shaped from the first
image's preview, so that one image lands on scale exactly 1.0. Every other image in the gallery is
drawn in a box that is not its shape, so its scale falls below 1 and it gets halved.

**Fix.** Return `SampleSizeRounding.QUALITY`
(`SaveMemoryCenterInisdeDownsampleStrategy.java`, line ~52). Memory is still bounded by the pixel
threshold in `getScaleFactor` — that is this class's real memory control, and it is untouched.

The strategy is shared, so this sharpens post previews and comment images too, not only galleries.

### 4b. Tiles were decoded for measurements that were never real

**Cause.** The gallery tile's size was read off the view at load time. Those measurements cannot be
trusted. A horizontal RecyclerView whose own width is unconstrained sizes itself from its children,
and with `match_parent` tiles that feeds back — the row takes its children's width, the children are
measured against the wider row, and each pass multiplies it again. On a 1968px display one tile was
handed **119px and 4888px within the same second**. A load issued during the 119px pass produced a
119px bitmap that was then stretched across the real tile: `draw=16.54x`, and up to `41x`.

**Fix.** `requestBox()` and `settledTileWidth()`
(`PostGalleryTypeImageRecyclerViewAdapter.java`, lines ~209 and ~229). A measurement is used only
when it falls between half of and all of the width the row settles at — derived from the display,
not from a measurement in flight — and anything else falls back to that settled width. The size is
then handed to Glide with `override()` (line ~280) instead of being read off the view, so a bad
measurement cannot reach the decoder. The load still always happens, so no tile waits forever for a
size it likes.

The tile re-checks on every layout (`loadImageIfNeeded`, line ~184) and reloads only when the box it
needs is bigger than the one already decoded, which is what makes it converge.

### 4c. Cropping tiles decoded to fit instead of to fill

Reasoned, not observed — every gallery in the captured logs used `FIT_CENTER`. Drop it first if it
ever conflicts on a merge.

`onBindViewHolder` sets `CENTER_CROP` when the tile box is the square one ("Fixed Height in Card",
or a preview with no usable dimensions), but `loadImage` always used `centerInside()`. The decode
then fits *inside* the box and the view scales it up to cover, by the image's whole aspect mismatch.
`loadImage` now picks `centerCrop()` for cropping tiles (line ~326), which is what the grid path
already did.

### Testing this area

Glide's `DiskCacheStrategy.ALL` caches the **transformed** bitmap, and the cache key knows nothing
about rounding mode or decode size. Every image viewed under a broken build keeps coming back broken
from `RESOURCE_DISK_CACHE`. **Clear the app's storage before judging a fix here**, or half the
results are stale.

### Known remaining limitation

Reddit's gallery previews (`media_metadata` `p` entries, picked in `ParsePost` as
`Post.Gallery.feedPreviewUrl`) cap at 1080px wide. On a display wider than that the post-detail tile
is wider than the preview, leaving an inherent upscale — 1.82x on the 1968px device this was
measured on. No decoding change can add pixels the server never sent.

Fixing it means loading `Post.Gallery.url` (the full-size source) when the tile is wider than the
largest preview. That is a bandwidth decision, not a bug fix, so it has deliberately been left
alone. Items whose *source* is small (a 505x423 image yielding a 320px preview) are upscaled for the
same reason and cannot be fixed at all.

---

## Building

`assembleDebug` runs `verifyShippedSubredditLists`, which needs a sibling checkout that the asset
symlinks point into:

```bash
git clone https://github.com/cygnusx-1-org/subreddit-lists.git ../subreddit-lists
```

Run `./gradlew spotlessApply` before committing. It needs network access the first time, to fetch
`google-java-format`.
