package io.github.spudhero.shufangtv.ui;

import static io.github.spudhero.shufangtv.ui.TvStyle.*;

import android.app.Dialog;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.InsetDrawable;
import android.graphics.drawable.LayerDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Native building blocks for the cloud-library flow, with a whole-control TV focus target. */
public final class SourceUi {
  public static final int BORDER = 0xff3b423c, RAISED = 0xff2c312d, QUIET = 0xff969f94;

  private SourceUi() {}

  public static TextView single(Context context, String value, int size, int color) {
    TextView label = text(context, value, size, color);
    label.setSingleLine(true);
    label.setEllipsize(TextUtils.TruncateAt.END);
    return label;
  }

  public static void target(View view, String description, Runnable action) {
    Context context = view.getContext();
    view.setFocusable(true);
    view.setFocusableInTouchMode(true);
    view.setClickable(true);
    view.setContentDescription(description);
    if (view instanceof ViewGroup)
      ((ViewGroup) view).setDescendantFocusability(ViewGroup.FOCUS_BLOCK_DESCENDANTS);
    view.setBackground(panel(context, SURFACE, BORDER));
    view.setOnFocusChangeListener(
        (v, focused) -> {
          v.setBackground(panel(context, focused ? RAISED : SURFACE, focused ? FOCUS : BORDER));
          v.setElevation(focused ? dp(context, 3) : 0);
        });
    view.setOnClickListener(v -> action.run());
  }

  public static TextView badge(Context context, String label) {
    TextView view = single(context, label, 11, ACCENT);
    view.setGravity(Gravity.CENTER);
    view.setPadding(dp(context, 8), dp(context, 4), dp(context, 8), dp(context, 4));
    view.setBackground(panel(context, 0xff343025, 0));
    return view;
  }

  public static LinearLayout row(
      Context context, String icon, String title, String detail, String trailing, Runnable action) {
    LinearLayout row = horizontal(context);
    row.setPadding(dp(context, 14), dp(context, 11), dp(context, 14), dp(context, 11));
    row.setMinimumHeight(dp(context, 58));
    add(row, new Mark(context, icon, ACCENT), 28, 28);
    gap(row, 12);
    LinearLayout labels = vertical(context);
    add(labels, single(context, title, 15, TEXT), -1, 23);
    if (!detail.isEmpty()) add(labels, single(context, detail, 11, MUTED), -1, 18);
    row.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
    if (!trailing.isEmpty()) {
      gap(row, 12);
      TextView tail = single(context, trailing, 12, MUTED);
      tail.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
      add(row, tail, -2, 30);
    }
    target(row, title + (detail.isEmpty() ? "" : "，" + detail), action);
    return row;
  }

  public static LinearLayout section(Context context, String title, String detail) {
    LinearLayout row = horizontal(context);
    TextView label = single(context, title, 15, TEXT);
    row.addView(label, new LinearLayout.LayoutParams(0, dp(context, 28), 1));
    if (!detail.isEmpty()) add(row, single(context, detail, 11, MUTED), -2, 24);
    return row;
  }

  public static LinearLayout listRow(
      Context context, String icon, String title, String detail, String trailing, Runnable action) {
    LinearLayout row = row(context, icon, title, detail, trailing, action);
    row.setBackground(listBackground(context));
    row.setOnFocusChangeListener(
        (view, focused) -> {
          view.setBackground(focused ? panel(context, RAISED, FOCUS) : listBackground(context));
          view.setElevation(focused ? dp(context, 3) : 0);
        });
    return row;
  }

  public static void primary(LinearLayout row) {
    tint(row, 0xff241e14);
    Context context = row.getContext();
    row.setBackground(panel(context, ACCENT, 0));
    row.setOnFocusChangeListener(
        (view, focused) -> {
          view.setBackground(panel(context, ACCENT, focused ? FOCUS : 0));
          view.setElevation(focused ? dp(context, 3) : 0);
        });
  }

  private static void tint(View view, int ink) {
    if (view instanceof TextView) ((TextView) view).setTextColor(ink);
    if (view instanceof Mark) ((Mark) view).paint.setColor(ink);
    if (view instanceof ViewGroup)
      for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++)
        tint(((ViewGroup) view).getChildAt(i), ink);
  }

  private static Drawable listBackground(Context context) {
    return new LayerDrawable(
        new Drawable[] {
          new ColorDrawable(BORDER),
          new InsetDrawable(new ColorDrawable(BG), 0, 0, 0, dp(context, 1))
        });
  }

  public static LinearLayout steps(Context context, int current) {
    LinearLayout row = horizontal(context);
    String[] names = {"扫码登录", "选择文件夹", "开始阅读"};
    for (int i = 0; i < names.length; i++) {
      if (i > 0) {
        TextView line = single(context, "—", 12, BORDER);
        line.setGravity(Gravity.CENTER);
        add(row, line, 32, 24);
      }
      TextView number =
          single(
              context,
              i < current ? "✓" : Integer.toString(i + 1),
              11,
              i <= current ? ACCENT : QUIET);
      number.setGravity(Gravity.CENTER);
      number.setBackground(panel(context, i <= current ? 0xff343025 : SURFACE, 0));
      add(row, number, 22, 22);
      gap(row, 7);
      add(row, single(context, names[i], 12, i == current ? TEXT : MUTED), -2, 24);
    }
    return row;
  }

  public static Dialog dialog(Context context, View content, int preferredWidth) {
    Dialog dialog = new Dialog(context);
    dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
    ScrollView viewport =
        new ScrollView(context) {
          @Override
          protected void onMeasure(int widthSpec, int heightSpec) {
            int available =
                dp(context, context.getResources().getConfiguration().screenHeightDp - 32);
            super.onMeasure(widthSpec, MeasureSpec.makeMeasureSpec(available, MeasureSpec.AT_MOST));
          }
        };
    viewport.setFillViewport(false);
    viewport.addView(content, new ScrollView.LayoutParams(-1, -2));
    dialog.setContentView(viewport);
    Window window = dialog.getWindow();
    if (window != null) {
      window.setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));
      window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
      WindowManager.LayoutParams params = window.getAttributes();
      params.dimAmount = .72f;
      params.gravity = Gravity.CENTER;
      params.width =
          dp(
              context,
              Math.min(
                  preferredWidth, context.getResources().getConfiguration().screenWidthDp - 64));
      params.height = WindowManager.LayoutParams.WRAP_CONTENT;
      window.setAttributes(params);
    }
    return dialog;
  }

  /** Small original line symbols; no downloaded images, icon font, or provider trademarks. */
  public static final class Mark extends View {
    private final String kind;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

    public Mark(Context context, String kind, int color) {
      super(context);
      this.kind = kind;
      paint.setColor(color);
      paint.setStrokeWidth(1.5f);
      paint.setStyle(Paint.Style.STROKE);
      paint.setStrokeCap(Paint.Cap.ROUND);
      paint.setStrokeJoin(Paint.Join.ROUND);
      setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    @Override
    protected void onDraw(Canvas canvas) {
      super.onDraw(canvas);
      canvas.save();
      canvas.scale(getWidth() / 24f, getHeight() / 24f);
      Path path = new Path();
      switch (kind) {
        case "folder":
          path.moveTo(3, 7);
          path.lineTo(3, 5);
          path.lineTo(9, 5);
          path.lineTo(12, 8);
          path.lineTo(21, 8);
          path.lineTo(21, 19);
          path.lineTo(3, 19);
          path.close();
          canvas.drawPath(path, paint);
          canvas.drawLine(3, 10, 21, 10, paint);
          break;
        case "book":
          canvas.drawRoundRect(5, 3, 20, 21, 1, 1, paint);
          canvas.drawLine(8, 3, 8, 21, paint);
          canvas.drawLine(11, 8, 17, 8, paint);
          canvas.drawLine(11, 12, 17, 12, paint);
          break;
        case "usb":
          canvas.drawLine(12, 3, 12, 19, paint);
          canvas.drawLine(9, 6, 12, 3, paint);
          canvas.drawLine(15, 6, 12, 3, paint);
          path.moveTo(6, 9);
          path.lineTo(6, 13);
          path.lineTo(12, 16);
          path.moveTo(18, 8);
          path.lineTo(18, 11);
          path.lineTo(12, 14);
          canvas.drawPath(path, paint);
          canvas.drawCircle(12, 20, 2, paint);
          break;
        case "server":
          canvas.drawRoundRect(4, 4, 20, 11, 1, 1, paint);
          canvas.drawRoundRect(4, 14, 20, 21, 1, 1, paint);
          canvas.drawCircle(16, 7.5f, .7f, paint);
          canvas.drawCircle(16, 17.5f, .7f, paint);
          break;
        default:
          path.moveTo(6, 18);
          path.cubicTo(0, 18, 1, 9, 7, 10);
          path.cubicTo(7, 1, 19, 2, 19, 11);
          path.cubicTo(25, 11, 24, 18, 19, 18);
          path.close();
          canvas.drawPath(path, paint);
          break;
      }
      canvas.restore();
    }
  }
}
