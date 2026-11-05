package io.github.spudhero.shufangtv.reader;

import android.content.Context;
import android.graphics.*;
import android.text.*;
import android.text.style.LeadingMarginSpan;
import android.view.View;
import java.util.*;

/** Lays out at most 8192 characters; a large chapter does not allocate all pages. */
public final class PagedTextView extends View {
  private final TextPaint paint = new TextPaint(Paint.ANTI_ALIAS_FLAG);
  private String text = "";
  private StaticLayout layout;
  private int offset, next;
  private float size = 22, line = 1.65f;
  private int ink = 0xffe9e7dc;
  private boolean serif = true;
  private final Map<Integer, Integer> previous = new HashMap<>();
  public Runnable onPageReady;

  public PagedTextView(Context c) {
    super(c);
    setFocusable(true);
    setFocusableInTouchMode(true);
    setContentDescription("阅读正文，左右翻页，确定打开阅读菜单");
  }

  public void setContent(String value, int position) {
    if (!text.equals(value)) previous.clear();
    text = value;
    offset = Math.max(0, Math.min(position, text.length()));
    if (offset > 0 && offset < text.length() && Character.isLowSurrogate(text.charAt(offset)))
      offset--;
    reflow();
  }

  public void setAppearance(float size, float line, boolean serif, int ink) {
    this.size = size;
    this.line = line;
    this.serif = serif;
    this.ink = ink;
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

  public String excerpt() {
    return text.substring(offset, Math.min(text.length(), offset + 90)).replace('\n', ' ');
  }

  public boolean next() {
    if (next >= text.length()) return false;
    previous.put(next, offset);
    offset = next;
    reflow();
    return true;
  }

  public boolean previous() {
    if (offset <= 0) return false;
    Integer known = previous.get(offset);
    offset = known != null ? known : previousOffset();
    reflow();
    return true;
  }

  private int previousOffset() {
    int start = Math.max(0, offset - 8192);
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

  private StaticLayout build(String value, int start) {
    paint.setTextSize(size * getResources().getDisplayMetrics().scaledDensity);
    paint.setColor(ink);
    paint.setTypeface(Typeface.create(serif ? "serif" : "sans-serif", Typeface.NORMAL));
    SpannableString span = new SpannableString(value);
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
    return StaticLayout.Builder.obtain(
            span, 0, span.length(), paint, Math.max(1, getWidth() - padding() * 2))
        .setIncludePad(false)
        .setLineSpacing(0, line)
        .setBreakStrategy(android.graphics.text.LineBreaker.BREAK_STRATEGY_SIMPLE)
        .build();
  }

  private void reflow() {
    if (getWidth() <= 0 || getHeight() <= 0) return;
    int end = Math.min(text.length(), offset + 8192);
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
    if (layout != null) {
      canvas.save();
      canvas.translate(padding(), padding());
      layout.draw(canvas);
      canvas.restore();
    }
  }
}
