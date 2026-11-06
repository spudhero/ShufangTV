package io.github.spudhero.shufangtv.ui;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.provider.Settings;
import android.speech.tts.TextToSpeech;
import android.widget.Toast;

/** Service discovery is a setup hint; ReaderSpeech still verifies Chinese on initialization. */
public final class SpeechSetup {
  private SpeechSetup() {}

  public static boolean hasEngine(Context context) {
    return !context
        .getPackageManager()
        .queryIntentServices(new Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE), 0)
        .isEmpty();
  }

  public static void open(Activity activity) {
    try {
      activity.startActivity(new Intent("com.android.settings.TTS_SETTINGS"));
    } catch (ActivityNotFoundException | SecurityException e) {
      try {
        activity.startActivity(new Intent(Settings.ACTION_SETTINGS));
      } catch (ActivityNotFoundException | SecurityException unavailable) {
        Toast.makeText(activity, "请从电视系统设置中配置文字转语音", Toast.LENGTH_LONG).show();
      }
    }
  }
}
