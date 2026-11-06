package io.github.spudhero.shufangtv.ui;

import static io.github.spudhero.shufangtv.ui.TvStyle.*;

import android.app.*;
import android.os.Bundle;
import android.text.InputType;
import android.view.*;
import android.widget.*;
import io.github.spudhero.shufangtv.core.TextNavigator;
import io.github.spudhero.shufangtv.reader.ReaderSpeech;
import io.github.spudhero.shufangtv.speech.*;
import java.io.IOException;
import java.util.Locale;

/** A bounded, scrollable TV panel shared by global settings and the reader. */
public final class SpeechPanel {
  private static final String SAMPLE = "夜幕降临，书房亮起一盏灯。愿每一页文字，都有温暖的声音。";
  private final Activity activity;
  private final VoicePackManager manager;
  private final LinearLayout content;
  private final ScrollView scroll;
  private final LinearLayout frame, navigation, main;
  private final Runnable dismissed;
  private final VoicePackManager.Listener changes = this::refresh;
  private AlertDialog dialog;
  private TextView status, primary, secondary, choose;
  private ProgressBar progress;
  private ReaderSpeech trial;
  private String feedback = "";
  private boolean closed;
  private SpeechSettings.Provider rendered;
  private Runnable azureSave;
  private final Application.ActivityLifecycleCallbacks lifecycle =
      new Application.ActivityLifecycleCallbacks() {
        public void onActivityCreated(Activity a, Bundle b) {}

        public void onActivityStarted(Activity a) {}

        public void onActivityResumed(Activity a) {
          if (a == activity) refresh();
        }

        public void onActivityPaused(Activity a) {
          if (a == activity) stopTrial();
        }

        public void onActivityStopped(Activity a) {}

        public void onActivitySaveInstanceState(Activity a, Bundle b) {}

        public void onActivityDestroyed(Activity a) {
          if (a == activity && dialog != null) dialog.dismiss();
        }
      };

  public static void show(Activity activity, Runnable dismissed) {
    new SpeechPanel(activity, dismissed).show();
  }

  private SpeechPanel(Activity activity, Runnable dismissed) {
    this.activity = activity;
    this.dismissed = dismissed;
    manager = VoicePackManager.get(activity);
    content = vertical(activity);
    scroll = ReadingPanel.scroll(activity, content);
    frame = vertical(activity);
    frame.setPadding(dp(activity, 20), dp(activity, 16), dp(activity, 20), dp(activity, 16));
    frame.setMinimumHeight(
        dp(
            activity,
            Math.max(280, activity.getResources().getConfiguration().screenHeightDp - 48)));
    TextView title = heading(activity, "朗读与语音", 22);
    add(frame, title, -1, -2);
    gap(frame, 12);
    LinearLayout columns = horizontal(activity);
    columns.setGravity(Gravity.TOP);
    frame.addView(columns, new LinearLayout.LayoutParams(-1, 0, 1));
    navigation = vertical(activity);
    add(
        columns,
        navigation,
        activity.getResources().getConfiguration().screenWidthDp < 800 ? 112 : 144,
        -1);
    gap(columns, 16);
    main = vertical(activity);
    columns.addView(main, new LinearLayout.LayoutParams(0, -1, 1));
  }

  private void show() {
    build();
    dialog = new AlertDialog.Builder(activity).create();
    dialog.setView(frame, 0, 0, 0, 0);
    dialog.setOnDismissListener(
        d -> {
          closed = true;
          stopTrial();
          manager.unlisten(changes);
          activity.getApplication().unregisterActivityLifecycleCallbacks(lifecycle);
          if (dismissed != null && !activity.isFinishing()) dismissed.run();
        });
    manager.listen(changes);
    activity.getApplication().registerActivityLifecycleCallbacks(lifecycle);
    dialog.show();
    Window window = dialog.getWindow();
    if (window != null) {
      window.setBackgroundDrawable(panel(activity, SURFACE, 0xff3b423c));
      window.setDimAmount(.4f);
      window.setLayout(
          dp(
              activity,
              Math.min(860, activity.getResources().getConfiguration().screenWidthDp - 48)),
          dp(
              activity,
              Math.max(280, activity.getResources().getConfiguration().screenHeightDp - 48)));
    }
    View selected = navigation.findViewWithTag("speech-provider-" + rendered.name());
    if (selected != null) selected.requestFocus();
  }

  private void build() {
    content.removeAllViews();
    navigation.removeAllViews();
    main.removeAllViews();
    azureSave = null;
    if (rendered == null) rendered = SpeechSettings.provider(activity);
    SpeechSettings.Provider[] providers = {
      SpeechSettings.Provider.OFFLINE, SpeechSettings.Provider.AZURE, SpeechSettings.Provider.SYSTEM
    };
    String[] labels = {"离线中文", "网络语音", "系统语音"};
    View[] tabs = new View[3];
    for (int i = 0; i < tabs.length; i++) {
      SpeechSettings.Provider provider = providers[i];
      boolean selected = SpeechSettings.provider(activity) == provider;
      tabs[i] =
          ReadingPanel.tool(
              activity,
              labels[i],
              selected ? "✓ 当前使用" : "查看设置",
              () -> {
                stopTrial();
                feedback = "";
                rendered = provider;
                build();
                navigation.findViewWithTag("speech-provider-" + provider.name()).requestFocus();
              });
      tabs[i].setTag("speech-provider-" + provider.name());
      tabs[i].setSelected(selected);
      int fill = rendered == provider ? 0xff343a31 : SURFACE;
      tabs[i].setBackground(panel(activity, fill, selected ? ACCENT : 0));
      tabs[i].setOnFocusChangeListener(
          (view, focused) ->
              view.setBackground(
                  panel(
                      activity,
                      focused ? 0xff45483a : fill,
                      focused ? FOCUS : selected ? ACCENT : 0)));
      add(
          navigation,
          tabs[i],
          -1,
          activity.getResources().getConfiguration().screenHeightDp <= 400 ? 52 : 64);
      gap(navigation, 8);
    }
    status = text(activity, "", 16, TEXT);
    status.setTag("speech-status");
    add(main, status, -1, -2);
    gap(main, 8);
    progress = new ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal);
    progress.setProgressTintList(android.content.res.ColorStateList.valueOf(ACCENT));
    add(main, progress, -1, 4);
    gap(main, 8);
    if (scroll.getParent() instanceof ViewGroup)
      ((ViewGroup) scroll.getParent()).removeView(scroll);
    main.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
    gap(main, 10);
    primary = button(activity, "", true, this::primaryAction);
    primary.setTag("speech-primary");
    secondary = ReadingPanel.option(activity, "", false, this::secondaryAction);
    secondary.setTag("speech-secondary");
    choose = ReadingPanel.option(activity, "选为当前", false, this::selectProvider);
    choose.setTag("speech-select");
    ReadingPanel.row(
        main, primary, choose, ReadingPanel.option(activity, "返回", false, () -> dialog.dismiss()));
    if (rendered == SpeechSettings.Provider.OFFLINE) {
      note("轻量中文普通话 · 下载 30.1 MiB / 安装约 31.2 MiB\n首次下载后可断网朗读；不需要系统语音。建议预留 120 MiB。此语音包仅支持中文。", 13);
      note("下载使用上游固定版本，每个文件均校验后安装。首次安装在电视上可能需要数分钟。离开面板会继续下载；取消或失败后可重新下载。", 12);
    } else if (rendered == SpeechSettings.Provider.AZURE) buildAzure();
    else note("系统引擎可选；检测到服务后，仍需在开启或试听时检查中文支持。语音数据由系统管理。", 13);
    note("朗读仅在前台播放，进入后台自动暂停。PDF 与纯图片暂不支持识字朗读。不会自动切换到网络语音。", 12);
    gap(content, 8);
    ReadingPanel.row(content, secondary);
    ReadingPanel.row(content, ReadingPanel.option(activity, "语音包与开源许可", false, this::licenses));
    refresh();
  }

  private void note(String value, int size) {
    gap(content, 8);
    TextView label = text(activity, value, size, MUTED);
    label.setLineSpacing(dp(activity, 4), 1);
    add(content, label, -1, -2);
  }

  private static void label(TextView view, String label) {
    view.setText(label);
    view.setContentDescription(label);
  }

  private void refresh() {
    if (closed || status == null) return;
    if (trial != null) {
      status.setText("正在准备并播放中文试听…");
      label(primary, "停止试听");
    } else {
      status.setText(feedback.isEmpty() ? SpeechSettings.status(activity, rendered) : feedback);
      if (rendered == SpeechSettings.Provider.OFFLINE) {
        if (manager.busy()) {
          status.setText(
              manager.state() == VoicePackManager.State.VERIFYING
                  ? "正在校验并安装…"
                  : "下载语音包 · "
                      + Math.min(100, manager.downloaded() * 100 / manager.pack.downloadBytes)
                      + "%");
          label(primary, "取消下载");
        } else if (manager.state() == VoicePackManager.State.READY) label(primary, "试听中文");
        else {
          label(primary, manager.state() == VoicePackManager.State.ERROR ? "重试下载" : "下载语音包");
          if (!manager.error().isEmpty()) status.setText(manager.error());
        }
      } else if (rendered == SpeechSettings.Provider.AZURE)
        label(primary, new AzureSettings(activity).configured() ? "试听网络语音" : "保存网络配置");
      else label(primary, "试听系统语音");
    }
    label(choose, SpeechSettings.provider(activity) == rendered ? "✓ 当前使用" : "选为当前");
    if (rendered == SpeechSettings.Provider.OFFLINE) {
      boolean busy = manager.busy();
      progress.setVisibility(busy ? View.VISIBLE : View.GONE);
      progress.setIndeterminate(manager.state() == VoicePackManager.State.VERIFYING);
      progress.setProgress(
          (int) Math.min(100, manager.downloaded() * 100 / manager.pack.downloadBytes));
      label(secondary, manager.state() == VoicePackManager.State.READY ? "删除语音包" : "下载说明");
    } else {
      progress.setVisibility(View.GONE);
      label(secondary, rendered == SpeechSettings.Provider.SYSTEM ? "系统语音设置" : "网络语音说明");
    }
  }

  private void primaryAction() {
    if (trial != null) {
      stopTrial();
      feedback = "试听已停止";
      refresh();
      return;
    }
    feedback = "";
    if (rendered == SpeechSettings.Provider.OFFLINE && manager.busy()) {
      manager.cancel();
      return;
    }
    if (rendered == SpeechSettings.Provider.OFFLINE
        && manager.state() != VoicePackManager.State.READY) {
      manager.download();
      refresh();
      return;
    }
    if (rendered == SpeechSettings.Provider.AZURE && !new AzureSettings(activity).configured()) {
      if (azureSave != null) azureSave.run();
      return;
    }
    if (rendered == SpeechSettings.Provider.AZURE) {
      new ReadingDialog(activity)
          .width(500)
          .setTitle("试听网络语音？")
          .setMessage("本次将发送以下试听文字至 Azure Speech，可能计费：\n" + SAMPLE + "\n试听不会改变当前朗读引擎。")
          .setPositiveButton("发送并试听", (d, w) -> startTrial())
          .setNegativeButton("取消", null)
          .show();
      return;
    }
    if (rendered == SpeechSettings.Provider.SYSTEM && !SpeechSetup.hasEngine(activity)) {
      feedback = "未检测到系统语音引擎，请先安装或启用";
      refresh();
      return;
    }
    startTrial();
  }

  private void startTrial() {
    if (closed) return;
    trial =
        new ReaderSpeech(
            activity,
            activity.getSharedPreferences("reading", 0).getFloat("speechRate", 1f),
            new ReaderSpeech.Listener() {
              public void ready(boolean available) {
                if (closed || trial == null) return;
                if (!available) {
                  stopTrial();
                  feedback = "语音未就绪，请检查语音包或当前引擎的中文配置";
                  refresh();
                  return;
                }
                trial.load(SAMPLE, 0);
                trial.play();
              }

              public void range(TextNavigator.Range range, int offset) {}

              public void state(boolean playing) {
                if (!closed) refresh();
              }

              public void ended() {
                stopTrial();
                feedback = "中文试听已播放完成";
                refresh();
              }

              public void message(String message) {
                stopTrial();
                feedback = message;
                refresh();
              }
            },
            SpeechSettings.engine(activity, rendered));
    refresh();
  }

  private void selectProvider() {
    if (SpeechSettings.provider(activity) == rendered) return;
    if (rendered == SpeechSettings.Provider.AZURE) {
      if (!new AzureSettings(activity).configured()) {
        feedback = "请先保存网络语音配置";
        refresh();
        View key = content.findViewWithTag("speech-azure-key");
        if (key != null) key.requestFocus();
        return;
      }
      new ReadingDialog(activity)
          .width(500)
          .setTitle("选用网络朗读？")
          .setMessage("开始朗读时，书籍段落将发送至 Azure Speech，可能按用量计费。仅在你主动开启朗读时发送；不会自动切换其他引擎。")
          .setPositiveButton("确认选用", (d, w) -> applyProvider())
          .setNegativeButton("取消", null)
          .show();
    } else applyProvider();
  }

  private void applyProvider() {
    stopTrial();
    SpeechSettings.select(activity, rendered);
    feedback = "";
    build();
    choose.requestFocus();
  }

  private void stopTrial() {
    ReaderSpeech previous = trial;
    trial = null;
    if (previous != null) previous.close();
  }

  private void secondaryAction() {
    if (rendered == SpeechSettings.Provider.SYSTEM) {
      stopTrial();
      SpeechSetup.open(activity);
      return;
    }
    if (rendered == SpeechSettings.Provider.AZURE) {
      info(
          "网络语音说明",
          "仅使用 Azure 官方 Speech REST。需要自行创建资源并填写该资源的地区与密钥；服务可能计费。\n"
              + "明确选用后，朗读段落会发送至该服务。不会自动切换网络。密钥在本机加密保存，音频仅暂存在内存。\n"
              + "尚未配置的设备可继续使用离线中文。");
      return;
    }
    if (manager.state() == VoicePackManager.State.READY) {
      stopTrial();
      new ReadingDialog(activity)
          .setTitle("删除离线语音包？")
          .setMessage("将释放约 31 MiB；书籍与阅读进度不受影响。再次使用离线中文需要重新下载。")
          .setPositiveButton(
              "删除语音包",
              (d, w) -> {
                manager.delete();
                feedback = manager.error();
                refresh();
              })
          .setNegativeButton("保留", null)
          .show();
    } else
      info(
          "离线语音包",
          "轻量中文普通话，下载 30.1 MiB，安装约 31.2 MiB。上游压缩包及每个安装文件均校验 SHA-256；不会保存未使用的大型词典。\n"
              + "建议预留 120 MiB。当前下载不支持断点续传；取消或断电后可重新下载。下载失败不会显示为已就绪。\n"
              + manager.error());
  }

  private void buildAzure() {
    note("选用后会把朗读段落发送至 Azure Speech，可能产生费用。密钥仅在本机加密保存，不会自动使用网络朗读。", 13);
    AzureSettings store = new AzureSettings(activity);
    AzureSettings.Config saved = null;
    try {
      saved = store.read();
    } catch (IOException e) {
      note(e.getMessage(), 12);
    }
    ReadingPanel.section(content, "资源地区");
    EditText region = input(saved == null ? "eastasia" : saved.region, "如 eastasia", false);
    region.setTag("speech-azure-region");
    add(content, region, -1, 48);
    ReadingPanel.section(content, "资源密钥");
    EditText secret = input("", saved == null ? "输入 Azure Speech 密钥" : "留空保留已保存密钥", true);
    secret.setTag("speech-azure-key");
    add(content, secret, -1, 48);
    ReadingPanel.section(content, "中文音色");
    String[] selected = {saved == null ? AzureSettings.VOICES[0] : saved.voice};
    LinearLayout voices = vertical(activity);
    add(content, voices, -1, -2);
    Runnable renderVoices =
        new Runnable() {
          public void run() {
            voices.removeAllViews();
            View[] options = new View[2];
            String[] labels = {"晓晓 · 女声", "云希 · 男声"};
            for (int i = 0; i < 2; i++) {
              int choice = i;
              options[i] =
                  ReadingPanel.option(
                      activity,
                      labels[i],
                      selected[0].equals(AzureSettings.VOICES[i]),
                      () -> {
                        selected[0] = AzureSettings.VOICES[choice];
                        run();
                        voices
                            .getChildAt(0)
                            .findViewWithTag("speech-voice-" + choice)
                            .requestFocus();
                      });
              options[i].setTag("speech-voice-" + i);
            }
            ReadingPanel.row(voices, options);
          }
        };
    renderVoices.run();
    azureSave =
        () -> {
          stopTrial();
          try {
            String value = secret.getText().toString().trim();
            AzureSettings.Config old = null;
            try {
              old = store.read();
            } catch (IOException ignored) {
            }
            if (value.isEmpty() && old != null) value = old.key;
            store.save(
                new AzureSettings.Config(
                    region.getText().toString().trim().toLowerCase(Locale.ROOT),
                    selected[0],
                    value));
            secret.setText("");
            secret.setHint("留空保留已保存密钥");
            feedback = "网络语音已配置，请试听检查连接";
            refresh();
            primary.requestFocus();
          } catch (IOException e) {
            feedback = e.getMessage();
            refresh();
            if (!region.getText().toString().trim().matches("[a-zA-Z][a-zA-Z0-9]{1,39}"))
              region.requestFocus();
            else secret.requestFocus();
            Toast.makeText(activity, feedback, Toast.LENGTH_LONG).show();
          }
        };
    ReadingPanel.row(
        content,
        ReadingPanel.option(activity, "保存网络配置", false, azureSave),
        ReadingPanel.option(
            activity,
            "清除网络配置",
            false,
            () ->
                new ReadingDialog(activity)
                    .setTitle("清除网络语音配置？")
                    .setMessage("将删除本机保存的地区、音色和密钥。离线语音包会保留。")
                    .setPositiveButton(
                        "清除配置",
                        (d, w) -> {
                          stopTrial();
                          try {
                            store.clear();
                            secret.setText("");
                            secret.setHint("输入 Azure Speech 密钥");
                            feedback = "网络语音配置已清除";
                            refresh();
                          } catch (IOException e) {
                            feedback = e.getMessage();
                            refresh();
                          }
                        })
                    .setNegativeButton("保留", null)
                    .show()));
  }

  private EditText input(String value, String hint, boolean secret) {
    EditText input = new EditText(activity);
    input.setText(value);
    input.setHint(hint);
    input.setTextColor(TEXT);
    input.setHintTextColor(MUTED);
    input.setTextSize(14);
    input.setSingleLine(true);
    input.setInputType(
        InputType.TYPE_CLASS_TEXT
            | (secret
                ? InputType.TYPE_TEXT_VARIATION_PASSWORD
                : InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD));
    input.setBackgroundTintList(android.content.res.ColorStateList.valueOf(ACCENT));
    return input;
  }

  private void licenses() {
    LinearLayout notices = vertical(activity);
    TextView attribution =
        text(
            activity,
            "离线引擎：sherpa-onnx 1.12.21 · Apache-2.0\n"
                + "推理运行库：Microsoft ONNX Runtime · MIT\n"
                + "中文模型：icefall AISHELL-3 · Apache-2.0\n"
                + "语料提供方：北京希尔贝壳科技。通用合成音色，未模仿特定人物。\n\n"
                + "完整许可已随应用提供，可离线查看：",
            14,
            TEXT);
    add(notices, attribution, -1, -2);
    String[] files = {"README.md", "LICENSE", "ONNXRUNTIME-LICENSE", "MODEL-LICENSE"};
    String[] labels = {"来源与说明", "sherpa-onnx 许可", "ONNX Runtime 许可", "中文模型许可"};
    for (int i = 0; i < files.length; i++) {
      String file = files[i], title = labels[i];
      ReadingPanel.row(
          notices,
          ReadingPanel.option(
              activity,
              title,
              false,
              () -> {
                try (java.io.InputStream input =
                    activity.getAssets().open("speech/licenses/" + file)) {
                  java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
                  byte[] buffer = new byte[4096];
                  int count;
                  while ((count = input.read(buffer)) != -1) bytes.write(buffer, 0, count);
                  info(title, bytes.toString(java.nio.charset.StandardCharsets.UTF_8.name()));
                } catch (IOException e) {
                  info(title, "许可文件暂时无法读取");
                }
              }));
    }
    new ReadingDialog(activity)
        .width(560)
        .setTitle("语音包与开源许可")
        .setView(ReadingPanel.scroll(activity, notices))
        .setPositiveButton("返回", null)
        .show();
  }

  private void info(String title, String message) {
    new ReadingDialog(activity)
        .width(500)
        .setTitle(title)
        .setMessage(message)
        .setPositiveButton("知道了", null)
        .show();
  }
}
