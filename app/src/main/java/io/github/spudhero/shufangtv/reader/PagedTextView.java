package io.github.spudhero.shufangtv.reader;

import android.content.Context;
import android.graphics.*;
import android.text.*;
import android.text.style.BackgroundColorSpan;
import android.text.style.LeadingMarginSpan;
import android.view.View;
import io.github.spudhero.shufangtv.core.BookDocument;
import io.github.spudhero.shufangtv.core.DocumentViewport;
import java.util.*;
import java.util.Collections;
import java.util.List;
import java.util.function.Consumer;

/** Lays out at most 8192 characters; a large chapter does not allocate all pages. */
public final class PagedTextView extends View {
  private final TextPaint paint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
  private String text = "";
  private StaticLayout layout;
  private int offset, next;
  private float size = 22, line = 1.65f;
  private int ink = 0xffe9e7dc;
  private boolean serif = true;
  private int widthPreset = 2;
  private int highlightStart = -1, highlightEnd = -1;
  private final Map<Integer, Integer> previous = new HashMap<>();
  public Runnable onPageReady;
  public Consumer<BookDocument.Illustration> onIllustrationNeeded;
  private List<BookDocument.Illustration> illustrations = Collections.emptyList();
  private BookDocument.Illustration currentImage;
  private Bitmap illustration;
  private String imageError;
  private boolean imageRequested;
  private float imageY;
  private int imageFit;
  private boolean externalRendering;

  public void setExternalRendering(boolean external) {
    externalRendering = external;
    invalidate();
  }

  public void setImageFit(int mode) {
    if (imageFit == mode) return;
    imageFit = Math.max(0, Math.min(2, mode));
    imageY = 0;
    reflow();
  }

  public boolean fitsWholeImage() {
    return imageFit == 1 || (imageFit == 0 && illustrations.size() < 4);
  }

  public void setChapter(BookDocument.Chapter chapter, int position, float imageY) {
    releaseIllustration();
    illustrations = Collections.emptyList();
    setContent(chapter.text, position);
    illustrations = chapter.images;
    reflow();
    this.imageY = imageY;
    invalidate();
  }

  public boolean showingImage() {
    return currentImage != null;
  }

  public float imageY() {
    return showingImage() && !fitsWholeImage()
        ? illustration == null ? imageY : imageViewport().y()
        : 0;
  }

  public boolean imageFailed() {
    return imageError != null;
  }

  public BookDocument.Illustration currentImage() {
    return currentImage;
  }

  public void illustrationReady(BookDocument.Illustration ref, Bitmap value, String error) {
    if (ref != currentImage) {
      if (value != null) value.recycle();
      return;
    }
    if (illustration != null && illustration != value) illustration.recycle();
    illustration = value;
    imageError = error;
    reflow();
  }

  public void retryIllustration() {
    imageRequested = false;
    imageError = null;
    reflow();
  }

  public void releaseIllustration() {
    if (illustration != null) illustration.recycle();
    illustration = null;
    currentImage = null;
    imageError = null;
    imageRequested = false;
    imageY = 0;
  }

  public boolean scrollImage(int direction) {
    if (illustration == null || fitsWholeImage()) return false;
    DocumentViewport viewport = imageViewport();
    float nextY = viewport.movedY(direction * getHeight() * .8f);
    if (Math.abs(nextY - viewport.y()) < .00001f) return false;
    imageY = nextY;
    invalidate();
    if (onPageReady != null) onPageReady.run();
    return true;
  }

  private DocumentViewport imageViewport() {
    return new DocumentViewport(
        illustration.getWidth(),
        illustration.getHeight(),
        Math.max(1, getWidth()),
        Math.max(1, getHeight()),
        1,
        0,
        imageY);
  }

  public PagedTextView(Context c) {
    super(c);
    setFocusable(true);
    setFocusableInTouchMode(true);
    setContentDescription("阅读正文，左右翻页，确定打开阅读菜单");
  }

  public void setContent(String value, int position) {
    if (!text.equals(value)) {
      previous.clear();
      highlightStart = highlightEnd = -1;
      illustrations = Collections.emptyList();
    }
    text = value;
    offset = Math.max(0, Math.min(position, text.length()));
    if (offset > 0 && offset < text.length() && Character.isLowSurrogate(text.charAt(offset)))
      offset--;
    reflow();
  }

  public void setAppearance(float size, float line, boolean serif, int ink) {
    setAppearance(size, line, serif, ink, widthPreset);
  }

  public void setAppearance(float size, float line, boolean serif, int ink, int widthPreset) {
    this.size = size;
    this.line = line;
    this.serif = serif;
    this.ink = ink;
    this.widthPreset = Math.max(0, Math.min(2, widthPreset));
    previous.clear();
    reflow();
  }

  public int offset() {
    return offset;
  }

  public int nextOffset() {
    return next;
  }

  public int totalChars() {
    return text.length();
  }

  public String content() {
    return text;
  }

  public void highlight(int start, int end) {
    if (highlightStart == start && highlightEnd == end) return;
    highlightStart = start;
    highlightEnd = end;
    reflow();
  }

  public String excerpt() {
    return text.substring(offset, Math.min(text.length(), offset + 90)).replace('\n', ' ');
  }

  public boolean next() {
    if (showingImage() && scrollImage(1)) return true;
    if (next >= text.length()) return false;
    previous.put(next, offset);
    offset = next;
    reflow();
    return true;
  }

  public boolean previous() {
    if (showingImage() && scrollImage(-1)) return true;
    if (offset <= 0) return false;
    Integer known = previous.get(offset);
    offset = known != null ? known : previousOffset();
    reflow();
    if (showingImage()) imageY = 1;
    return true;
  }

  private int previousOffset() {
    int start = Math.max(0, offset - 8192);
    for (BookDocument.Illustration image : illustrations) {
      if (image.end > offset) break;
      if (blank(image.end, offset)) return image.start;
      start = Math.max(start, image.end);
    }
    int newline = text.indexOf('\n', start);
    if (start > 0 && newline > start && newline < offset - 128) start = newline + 1;
    StaticLayout l = build(text.substring(start, offset), start);
    int available = Math.max(1, getHeight() - padding() * 2), height = l.getHeight();
    int first = l.getLineForVertical(Math.max(0, height - available));
    return start + l.getLineStart(first);
  }

  private int padding() {
    return Math.round(getResources().getDisplayMetrics().density * 32);
  }

  // Layout's API-23 constant is the same value as LineBreaker's API-29 IntDef.
  @android.annotation.SuppressLint("WrongConstant")
  private StaticLayout build(String value, int start) {
    paint.setTextSize(size * getResources().getDisplayMetrics().scaledDensity);
    paint.setColor(ink);
    paint.setTypeface(Typeface.create(serif ? "serif" : "sans-serif", Typeface.NORMAL));
    SpannableString span = new SpannableString(value);
    int hStart = Math.max(0, highlightStart - start);
    int hEnd = Math.min(value.length(), highlightEnd - start);
    if (hStart < hEnd)
      span.setSpan(
          new BackgroundColorSpan(0x668c713e), hStart, hEnd, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    int pos = 0;
    while (pos < value.length()) {
      int end = value.indexOf('\n', pos);
      if (end < 0) end = value.length();
      boolean continues = pos == 0 && start > 0 && text.charAt(start - 1) != '\n';
      span.setSpan(
          new LeadingMarginSpan.Standard(continues ? 0 : (int) (paint.getTextSize() * 2), 0),
          pos,
          Math.max(pos + 1, end),
          Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
      pos = end + 1;
    }
    return StaticLayout.Builder.obtain(span, 0, span.length(), paint, columnWidth())
        .setIncludePad(false)
        .setLineSpacing(0, line)
        .setBreakStrategy(android.text.Layout.BREAK_STRATEGY_SIMPLE)
        .build();
  }

  private int columnWidth() {
    float ratio = widthPreset == 0 ? .62f : widthPreset == 1 ? .8f : 1f;
    return Math.max(1, Math.round(Math.max(1, getWidth() - padding() * 2) * ratio));
  }

  private void reflow() {
    if (getWidth() <= 0 || getHeight() <= 0) return;
    BookDocument.Illustration visible = null;
    int imageStart = text.length();
    for (BookDocument.Illustration candidate : illustrations) {
      if (candidate.end <= offset) continue;
      if (candidate.start <= offset || blank(offset, candidate.start)) visible = candidate;
      imageStart = candidate.start;
      break;
    }
    if (visible != currentImage) {
      releaseIllustration();
      currentImage = visible;
    }
    if (visible != null) {
      offset = visible.start;
      next = visible.end;
      while (next < text.length() && Character.isWhitespace(text.charAt(next))) next++;
      layout = null;
      setContentDescription(
          imageError == null
              ? illustration == null ? "正在读取插图" : "图片正文，左右翻阅、上下移动，确定打开阅读菜单"
              : "图片读取失败，按确定重试：" + imageError);
      if (!imageRequested && onIllustrationNeeded != null) {
        imageRequested = true;
        onIllustrationNeeded.accept(visible);
      }
      invalidate();
      if (onPageReady != null) onPageReady.run();
      return;
    }
    setContentDescription("阅读正文，左右翻页，确定打开阅读菜单");
    int end = Math.min(text.length(), offset + 8192);
    if (imageStart > offset) end = Math.min(end, imageStart);
    if (end < text.length() && end > offset && Character.isHighSurrogate(text.charAt(end - 1)))
      end--;
    String window = text.substring(offset, end);
    StaticLayout measured = build(window, offset);
    int available = Math.max(1, getHeight() - padding() * 2), lines = 0;
    while (lines < measured.getLineCount() && measured.getLineBottom(lines) <= available) lines++;
    lines = Math.max(1, lines);
    int count =
        window.isEmpty() ? 0 : measured.getLineEnd(Math.min(lines, measured.getLineCount()) - 1);
    next = offset + count;
    layout = build(window.substring(0, count), offset);
    invalidate();
    if (onPageReady != null) onPageReady.run();
  }

  @Override
  protected void onSizeChanged(int w, int h, int oldw, int oldh) {
    reflow();
  }

  @Override
  protected void onDraw(Canvas canvas) {
    super.onDraw(canvas);
    if (!externalRendering) renderContent(canvas);
  }

  /** Draw vectors and source illustrations directly into either the UI or a denser surface. */
  public void renderContent(Canvas canvas) {
    if (currentImage != null) {
      if (illustration != null) {
        DocumentViewport viewport = imageViewport();
        Paint bitmapPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
        RectF destination;
        if (fitsWholeImage()) {
          float scale =
              Math.min(
                  (float) getWidth() / illustration.getWidth(),
                  (float) getHeight() / illustration.getHeight());
          float width = illustration.getWidth() * scale, height = illustration.getHeight() * scale;
          destination =
              new RectF(
                  (getWidth() - width) / 2,
                  (getHeight() - height) / 2,
                  (getWidth() + width) / 2,
                  (getHeight() + height) / 2);
        } else {
          canvas.drawColor(Color.WHITE);
          destination =
              new RectF(
                  0,
                  -viewport.top(),
                  viewport.documentWidth,
                  viewport.documentHeight - viewport.top());
        }
        canvas.drawBitmap(illustration, null, destination, bitmapPaint);
      } else {
        paint.setTextSize(18 * getResources().getDisplayMetrics().scaledDensity);
        paint.setColor(ink);
        String label = imageError == null ? "正在读取插图…" : "图片读取失败，按确定重试";
        canvas.drawText(
            label,
            Math.max(12, (getWidth() - paint.measureText(label)) / 2),
            getHeight() / 2f,
            paint);
      }
      return;
    }
    if (layout != null) {
      canvas.save();
      canvas.translate((getWidth() - columnWidth()) / 2f, padding());
      layout.draw(canvas);
      canvas.restore();
    }
  }

  private boolean blank(int start, int end) {
    for (int i = start; i < end; i++) if (!Character.isWhitespace(text.charAt(i))) return false;
    return true;
  }
}
