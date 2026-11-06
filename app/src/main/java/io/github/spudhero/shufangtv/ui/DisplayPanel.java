package io.github.spudhero.shufangtv.ui;

import static io.github.spudhero.shufangtv.ui.TvStyle.*;

import android.app.*;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableString;
import android.text.style.RelativeSizeSpan;
import android.view.*;
import android.widget.*;
import io.github.spudhero.shufangtv.data.DisplaySettings;
import io.github.spudhero.shufangtv.reader.DisplayProfile;
import java.util.function.Supplier;

/**
 * OpenDesign display panel: observed effect, selected policy, details, and an always-reachable
 * exit.
 */
public final class DisplayPanel {
  private DisplayPanel() {}

  public static AlertDialog show(
      Activity activity,
      SharedPreferences preferences,
      AlertDialog.Builder builder,
      Supplier<String> effect,
      Supplier<String> canvas,
      Runnable changed,
      Runnable dismissed) {
    boolean reading = effect != null;
    LinearLayout frame = vertical(activity);
    frame.setPadding(dp(activity, 20), dp(activity, 16), dp(activity, 20), dp(activity, 16));
    int heightDp = Math.max(280, activity.getResources().getConfiguration().screenHeightDp - 48);
    frame.setMinimumHeight(dp(activity, heightDp));
    add(frame, heading(activity, "显示与清晰度", 22), -1, -2);
    gap(frame, 6);
    add(frame, text(activity, "提高内容绘制精度，保留字号与阅读位置", 13, MUTED), -1, -2);
    gap(frame, 10);
    LinearLayout content = vertical(activity);
    ScrollView scroll = ReadingPanel.scroll(activity, content);
    frame.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
    LinearLayout status = vertical(activity);
    status.setPadding(dp(activity, 12), dp(activity, 10), dp(activity, 12), dp(activity, 10));
    status.setBackground(panel(activity, 0xff252d25, 0xff46513e));
    add(status, text(activity, "当前效果", 12, MUTED), -1, -2);
    TextView current = heading(activity, "", 18);
    current.setTag("display-effect");
    add(status, current, -1, -2);
    gap(status, 4);
    TextView explanation =
        text(
            activity,
            reading ? "仅表示应用阅读画布；最终显示精度仍取决于电视系统。" : "打开书籍后确认实际阅读画布，选择模式不会提前显示为已启用。",
            13,
            MUTED);
    explanation.setLineSpacing(dp(activity, 3), 1);
    add(status, explanation, -1, -2);
    add(content, status, -1, -2);
    ReadingPanel.section(content, "阅读显示方式");
    LinearLayout options = vertical(activity);
    add(content, options, -1, -2);
    Runnable[] renderOptions = new Runnable[1];
    renderOptions[0] =
        () -> {
          String anchor =
              options.findFocus() == null ? null : String.valueOf(options.findFocus().getTag());
          options.removeAllViews();
          boolean automatic = DisplaySettings.automatic(preferences);
          for (int i = 0; i < 2; i++) {
            boolean auto = i == 0;
            String label = auto ? "自动（推荐）" : "兼容模式";
            String detail = auto ? "按电视能力尝试高清绘制，失败时恢复系统画布。" : "使用系统画布，适合出现黑屏、闪烁或显示异常时选择。";
            TextView option =
                ReadingPanel.option(
                    activity,
                    label + "\n" + detail,
                    automatic == auto,
                    () -> {
                      if (DisplaySettings.automatic(preferences) != auto) {
                        DisplaySettings.save(preferences, auto);
                        if (changed != null) changed.run();
                      }
                      renderOptions[0].run();
                    });
            SpannableString text = new SpannableString(option.getText());
            text.setSpan(new RelativeSizeSpan(.82f), label.length() + 1, text.length(), 0);
            option.setText(text);
            option.setTextSize(16);
            option.setGravity(Gravity.CENTER_VERTICAL);
            option.setPadding(
                dp(activity, 12), dp(activity, 10), dp(activity, 12), dp(activity, 10));
            option.setMinHeight(dp(activity, 72));
            option.setTag(auto ? "display-mode-auto" : "display-mode-compatible");
            add(options, option, -1, -2);
            gap(options, 8);
          }
          if (anchor != null) options.findViewWithTag(anchor).requestFocus();
        };
    renderOptions[0].run();
    TextView retry =
        button(
            activity,
            "重新尝试高清绘制",
            false,
            () -> {
              if (changed != null) changed.run();
            });
    retry.setTag("display-retry");
    retry.setVisibility(View.GONE);
    add(content, retry, -1, 44);
    TextView details = text(activity, "", 14, TEXT);
    details.setTag("display-device-info");
    details.setPadding(dp(activity, 12), dp(activity, 8), dp(activity, 12), dp(activity, 8));
    details.setLineSpacing(dp(activity, 6), 1);
    details.setVisibility(View.GONE);
    TextView toggle = button(activity, "设备详情  ﹀", false, () -> {});
    toggle.setTag("display-details");
    toggle.setOnClickListener(
        v -> {
          boolean show = details.getVisibility() != View.VISIBLE;
          details.setVisibility(show ? View.VISIBLE : View.GONE);
          toggle.setText(show ? "收起设备详情  ﹀" : "设备详情  ﹀");
        });
    add(content, toggle, -1, 44);
    add(content, details, -1, -2);
    gap(content, 12);
    TextView help =
        text(
            activity,
            "4K 面板、系统界面与阅读画布可以不同。旧电视可能再次缩放高清内容；应用无法绕过系统的最终输出限制。"
                + "\n\nPDF 保持全屏撑满屏宽，上下滚动。100% 表示适合屏宽，放大后可左右平移。",
            13,
            MUTED);
    help.setLineSpacing(dp(activity, 4), 1);
    add(content, help, -1, -2);
    gap(frame, 12);
    line(frame);
    gap(frame, 8);
    TextView done = button(activity, reading ? "返回阅读" : "返回设置", true, () -> {});
    done.setTag("display-done");
    add(frame, done, -1, 44);
    Handler handler = new Handler(Looper.getMainLooper());
    AlertDialog[] dialog = new AlertDialog[1];
    Runnable[] update = new Runnable[1];
    update[0] =
        () -> {
          if (dialog[0] == null || !dialog[0].isShowing()) return;
          updateText(
              current,
              reading
                  ? effect.get()
                  : DisplaySettings.automatic(preferences) ? "自动 · 待读取书籍后确认" : "兼容模式 · 待读取书籍后确认");
          retry.setVisibility(
              reading && DisplaySettings.automatic(preferences) && effect.get().contains("已回退")
                  ? View.VISIBLE
                  : View.GONE);
          DisplayProfile profile = new DisplayProfile(activity);
          updateText(
              details,
              "电视面板："
                  + profile.panelSize()
                  + "\n系统界面："
                  + profile.systemSize()
                  + "\n阅读画布："
                  + (reading ? canvas.get() : "打开书籍后确认"));
          handler.postDelayed(update[0], 500);
        };
    builder.setOnDismissListener(
        d -> {
          handler.removeCallbacks(update[0]);
          if (dismissed != null && !activity.isFinishing()) dismissed.run();
        });
    dialog[0] = builder.create();
    dialog[0].setView(frame, 0, 0, 0, 0);
    done.setOnClickListener(v -> dialog[0].dismiss());
    dialog[0].setOnShowListener(
        d -> {
          Window window = dialog[0].getWindow();
          if (window != null) {
            window.setBackgroundDrawable(panel(activity, SURFACE, 0xff3b423c));
            window.setDimAmount(.4f);
            window.setGravity(Gravity.CENTER);
            window.setLayout(
                dp(
                    activity,
                    Math.min(720, activity.getResources().getConfiguration().screenWidthDp - 48)),
                dp(activity, heightDp));
          }
          update[0].run();
          options
              .findViewWithTag(
                  DisplaySettings.automatic(preferences)
                      ? "display-mode-auto"
                      : "display-mode-compatible")
              .requestFocus();
        });
    dialog[0].show();
    return dialog[0];
  }

  private static void updateText(TextView view, String value) {
    if (!android.text.TextUtils.equals(view.getText(), value)) view.setText(value);
  }
}
