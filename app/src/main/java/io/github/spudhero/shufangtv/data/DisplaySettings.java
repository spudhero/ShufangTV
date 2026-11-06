package io.github.spudhero.shufangtv.data;

import android.content.SharedPreferences;

public final class DisplaySettings {
  private DisplaySettings() {}

  public static boolean automatic(SharedPreferences preferences) {
    return !"compatible".equals(preferences.getString("readingResolution", "auto"));
  }

  public static void save(SharedPreferences preferences, boolean automatic) {
    preferences.edit().putString("readingResolution", automatic ? "auto" : "compatible").apply();
  }
}
