package io.github.spudhero.shufangtv.ui;

import android.content.Context;
import android.graphics.*;
import android.view.View;

/** Original typographic cover used when a book has no embedded cover. */
public final class CoverView extends View {
  private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
  private final String title, author;
  private final int color;

  public CoverView(Context context, String title, String author) {
    super(context);
    this.title = title;
    this.author = author;
    int[] colors = {0xffc9bc98, 0xff9dae9e, 0xffa98570, 0xff34545b, 0xffa6aa7c, 0xffa45742};
    color = colors[Math.floorMod(title.hashCode(), colors.length)];
    setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
  }

  @Override
  protected void onDraw(Canvas canvas) {
    super.onDraw(canvas);
    float w = getWidth(), h = getHeight();
    paint.setColor(color);
    canvas.drawRoundRect(0, 0, w, h, 3, 3, paint);
    paint.setColor(0x55414b3c);
    paint.setStyle(Paint.Style.STROKE);
    paint.setStrokeWidth(1);
    canvas.drawRect(w * .09f, h * .045f, w * .91f, h * .95f, paint);
    Path p = new Path();
    p.moveTo(w * .12f, h * .74f);
    p.lineTo(w * .32f, h * .49f);
    p.lineTo(w * .56f, h * .74f);
    p.moveTo(w * .28f, h * .74f);
    p.lineTo(w * .52f, h * .4f);
    p.lineTo(w * .78f, h * .74f);
    canvas.drawPath(p, paint);
    paint.setStyle(Paint.Style.FILL);
    paint.setColor(0xff283c32);
    paint.setTypeface(Typeface.create("serif", Typeface.NORMAL));
    String clean = title.replaceAll("[（(].*", "");
    int count = Math.min(7, clean.codePointCount(0, clean.length()));
    float size = Math.min(w * .22f, h * .7f / Math.max(3, count));
    paint.setTextSize(size);
    int at = 0;
    for (int i = 0; i < count; i++) {
      int cp = clean.codePointAt(at);
      String ch = new String(Character.toChars(cp));
      canvas.drawText(ch, w * .66f - paint.measureText(ch) / 2, h * .12f + size * (i + 1), paint);
      at += Character.charCount(cp);
    }
    paint.setTextSize(w * .073f);
    paint.setTypeface(Typeface.DEFAULT);
    String shortAuthor = author.length() > 8 ? author.substring(0, 8) : author;
    canvas.drawText(shortAuthor, w * .16f, h * .14f, paint);
  }
}
