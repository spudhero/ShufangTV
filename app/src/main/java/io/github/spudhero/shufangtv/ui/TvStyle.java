package io.github.spudhero.shufangtv.ui;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.*;
import android.widget.*;

public final class TvStyle {
  public static final int BG = 0xff191c1b,
      SIDEBAR = 0xff141716,
      SURFACE = 0xff242825,
      TEXT = 0xfff0eee6,
      MUTED = 0xffb7bdb4,
      ACCENT = 0xffd8b377,
      FOCUS = 0xfff8e2b6;

  private TvStyle() {}

  public static int dp(Context c, float value) {
    return Math.round(c.getResources().getDisplayMetrics().density * value);
  }

  public static GradientDrawable panel(Context c, int color, int stroke) {
    GradientDrawable d = new GradientDrawable();
    d.setColor(color);
    d.setCornerRadius(dp(c, 5));
    if (stroke != 0) d.setStroke(dp(c, 1.5f), stroke);
    return d;
  }

  public static TextView text(Context c, String label, float size, int color) {
    TextView t = new TextView(c);
    t.setText(label);
    t.setTextSize(size);
    t.setTextColor(color);
    t.setFontFeatureSettings("kern");
    t.setIncludeFontPadding(false);
    return t;
  }

  public static TextView button(Context c, String label, boolean primary, Runnable action) {
    TextView t = text(c, label, 14, primary ? 0xff241e14 : TEXT);
    t.setGravity(Gravity.CENTER);
    t.setPadding(dp(c, 14), dp(c, 10), dp(c, 14), dp(c, 10));
    t.setMinHeight(dp(c, 38));
    t.setFocusable(true);
    t.setFocusableInTouchMode(true);
    t.setClickable(true);
    t.setBackground(panel(c, primary ? ACCENT : SURFACE, 0));
    t.setOnFocusChangeListener(
        (view, focus) -> {
          view.setBackground(panel(c, primary ? ACCENT : SURFACE, focus ? FOCUS : 0));
          view.setElevation(focus ? dp(c, 2) : 0);
        });
    t.setOnClickListener(v -> action.run());
    t.setContentDescription(label);
    return t;
  }

  public static LinearLayout vertical(Context c) {
    LinearLayout l = new LinearLayout(c);
    l.setOrientation(LinearLayout.VERTICAL);
    return l;
  }

  public static LinearLayout horizontal(Context c) {
    LinearLayout l = new LinearLayout(c);
    l.setOrientation(LinearLayout.HORIZONTAL);
    l.setGravity(Gravity.CENTER_VERTICAL);
    return l;
  }

  public static void add(LinearLayout parent, View child, int width, int height) {
    parent.addView(
        child,
        new LinearLayout.LayoutParams(
            width < 0 ? width : dp(parent.getContext(), width),
            height < 0 ? height : dp(parent.getContext(), height)));
  }

  public static void gap(LinearLayout parent, int n) {
    View v = new View(parent.getContext());
    add(
        parent,
        v,
        parent.getOrientation() == LinearLayout.VERTICAL ? 1 : n,
        parent.getOrientation() == LinearLayout.VERTICAL ? n : 1);
  }

  public static TextView heading(Context c, String s, float size) {
    TextView t = text(c, s, size, TEXT);
    t.setTypeface(Typeface.create("serif", Typeface.NORMAL));
    return t;
  }

  public static void line(LinearLayout parent) {
    View line = new View(parent.getContext());
    line.setBackgroundColor(0xff3b423c);
    add(parent, line, -1, 1);
  }
}
