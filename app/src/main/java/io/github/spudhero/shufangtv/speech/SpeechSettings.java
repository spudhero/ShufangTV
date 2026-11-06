package io.github.spudhero.shufangtv.speech;

import android.content.Context;
import android.content.SharedPreferences;
import io.github.spudhero.shufangtv.ui.SpeechSetup;

public final class SpeechSettings {
  public enum Provider {
    OFFLINE,
    AZURE,
    SYSTEM
  }

  private SpeechSettings() {}

  private static SharedPreferences preferences(Context context) {
    return context.getSharedPreferences("reading", Context.MODE_PRIVATE);
  }

  public static Provider provider(Context context) {
    try {
      return Provider.valueOf(preferences(context).getString("speechProvider", "OFFLINE"));
    } catch (RuntimeException e) {
      return Provider.OFFLINE;
    }
  }

  public static void select(Context context, Provider provider) {
    preferences(context).edit().putString("speechProvider", provider.name()).apply();
  }

  public static String name(Context context) {
    switch (provider(context)) {
      case SYSTEM:
        return "系统语音";
      case AZURE:
        return "Azure 网络语音";
      default:
        return "离线中文";
    }
  }

  public static boolean available(Context context) {
    switch (provider(context)) {
      case SYSTEM:
        return SpeechSetup.hasEngine(context);
      case AZURE:
        return new AzureSettings(context).configured();
      default:
        return VoicePackManager.get(context).state() == VoicePackManager.State.READY;
    }
  }

  public static String status(Context context) {
    return status(context, provider(context));
  }

  public static String status(Context context, Provider provider) {
    switch (provider) {
      case SYSTEM:
        return SpeechSetup.hasEngine(context) ? "已检测到系统引擎，开启时检查中文支持" : "未检测到系统语音引擎";
      case AZURE:
        return new AzureSettings(context).configured() ? "网络语音已配置，试听检查连接" : "网络语音尚未配置";
      default:
        VoicePackManager manager = VoicePackManager.get(context);
        if (manager.busy()) return "语音包准备中，可在管理页查看进度";
        return manager.state() == VoicePackManager.State.READY ? "离线中文已就绪 · 无需系统语音" : "离线中文需下载语音包";
    }
  }

  public static SpeechEngine engine(Context context) {
    return engine(context, provider(context));
  }

  public static SpeechEngine engine(Context context, Provider provider) {
    switch (provider) {
      case SYSTEM:
        return new SystemSpeechEngine(context);
      case AZURE:
        return new PcmSpeechEngine(new AzureSpeechSource(new AzureSettings(context)));
      default:
        return new PcmSpeechEngine(new OfflineSpeechSource(VoicePackManager.get(context)));
    }
  }
}
