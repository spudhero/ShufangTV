package io.github.spudhero.shufangtv.data;

import android.content.SharedPreferences;
import org.json.JSONException;
import org.json.JSONObject;

/** Optional per-book overrides; absent overrides continue to follow global defaults. */
public final class ReadingStyle {
  public int font = 22, width = 2, paper;
  public float line = 1.65f;
  public boolean serif = true;

  public static ReadingStyle defaults(SharedPreferences prefs) {
    ReadingStyle style = new ReadingStyle();
    style.font = prefs.getInt("font", 22);
    style.width = prefs.getInt("readingWidth", 2);
    style.paper = prefs.getInt("paper", 0);
    style.line = prefs.getFloat("line", 1.65f);
    style.serif = prefs.getBoolean("serif", true);
    style.normalize();
    return style;
  }

  public static ReadingStyle effective(BookRecord book, SharedPreferences prefs) {
    return book.readingStyle == null ? defaults(prefs) : book.readingStyle;
  }

  public void saveDefaults(SharedPreferences prefs) {
    normalize();
    prefs
        .edit()
        .putInt("font", font)
        .putInt("readingWidth", width)
        .putInt("paper", paper)
        .putFloat("line", line)
        .putBoolean("serif", serif)
        .apply();
  }

  public JSONObject json() throws JSONException {
    normalize();
    return new JSONObject()
        .put("font", font)
        .put("width", width)
        .put("paper", paper)
        .put("line", line)
        .put("serif", serif);
  }

  public static ReadingStyle from(JSONObject json) {
    ReadingStyle style = new ReadingStyle();
    style.font = json.optInt("font", 22);
    style.width = json.optInt("width", 2);
    style.paper = json.optInt("paper", 0);
    style.line = (float) json.optDouble("line", 1.65);
    style.serif = json.optBoolean("serif", true);
    style.normalize();
    return style;
  }

  private void normalize() {
    font = Math.max(16, Math.min(40, font));
    width = Math.max(0, Math.min(2, width));
    paper = Math.max(0, Math.min(2, paper));
    if (!Float.isFinite(line) || line < 1.2f || line > 2.2f) line = 1.65f;
  }
}
