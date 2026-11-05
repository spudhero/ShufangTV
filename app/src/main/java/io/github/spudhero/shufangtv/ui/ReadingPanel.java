package io.github.spudhero.shufangtv.ui;

import static io.github.spudhero.shufangtv.ui.TvStyle.*;

import android.content.Context;
import android.view.Gravity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Native counterparts of the OpenDesign reading panels, sized for a 960 × 540 dp TV. */
public final class ReadingPanel {
  private ReadingPanel() {}

  public static ScrollView scroll(Context context, LinearLayout content) {
    ScrollView scroll = new ScrollView(context);
    scroll.setFillViewport(false);
    scroll.setSmoothScrollingEnabled(false);
    scroll.addView(content);
    return scroll;
  }

  public static void section(LinearLayout parent, String title) {
    gap(parent, 8);
    TextView label = text(parent.getContext(), title, 12, MUTED);
    if (android.os.Build.VERSION.SDK_INT >= 28) label.setAccessibilityHeading(true);
    add(parent, label, -1, 22);
  }

  public static TextView option(Context context, String label, boolean selected, Runnable action) {
    TextView view = button(context, label, false, action);
    view.setTextColor(selected ? ACCENT : TEXT);
    view.setSelected(selected);
    view.setMinHeight(dp(context, 44));
    view.setContentDescription(null);
    view.setBackground(panel(context, selected ? 0xff34372e : 0xff292e2a, selected ? ACCENT : 0));
    view.setOnFocusChangeListener(
        (v, focused) ->
            v.setBackground(
                panel(
                    context,
                    focused ? 0xff45483a : selected ? 0xff34372e : 0xff292e2a,
                    focused ? FOCUS : selected ? ACCENT : 0)));
    return view;
  }

  public static void row(LinearLayout parent, View... choices) {
    Context context = parent.getContext();
    LinearLayout row = horizontal(context);
    for (int i = 0; i < choices.length; i++) {
      if (i > 0) gap(row, 8);
      row.addView(choices[i], new LinearLayout.LayoutParams(0, dp(context, 44), 1));
    }
    add(parent, row, -1, -2);
    gap(parent, 6);
  }

  public static TextView tool(Context context, String label, String detail, Runnable action) {
    TextView view = option(context, label + "\n" + detail, false, action);
    android.text.SpannableString text = new android.text.SpannableString(view.getText());
    text.setSpan(
        new android.text.style.RelativeSizeSpan(.82f), label.length() + 1, text.length(), 0);
    text.setSpan(
        new android.text.style.ForegroundColorSpan(MUTED), label.length() + 1, text.length(), 0);
    view.setText(text);
    view.setTextSize(14);
    view.setGravity(Gravity.CENTER_VERTICAL);
    view.setPadding(dp(context, 12), dp(context, 5), dp(context, 8), dp(context, 5));
    view.setContentDescription(label + "，" + detail);
    view.setTag("tool:" + label);
    return view;
  }
}
