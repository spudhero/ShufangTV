package io.github.spudhero.shufangtv.data;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Metadata and a logical reading location, never the contents of a remote book. */
public final class BookRecord {
  public String id = UUID.randomUUID().toString();
  public String title = "",
      author = "",
      format = "epub",
      sourceId = "",
      remotePath = "",
      offlineFile = "";
  public String lastMode = "offline", documentUri = "";
  public int chapter, offset, pdfPage;
  public int imageFit;
  public float pdfX, pdfY, imageY, pdfZoom = 1;
  public long txtByte, size, lastRead;
  public ReadingStyle readingStyle;
  public boolean pinned;
  public String group = "", readState = "unread";
  public final List<String> searchHistory = new ArrayList<>();

  public void rememberSearch(String query) {
    query = query.trim();
    if (query.isEmpty() || query.length() > 128) return;
    searchHistory.remove(query);
    searchHistory.add(0, query);
    while (searchHistory.size() > 8) searchHistory.remove(searchHistory.size() - 1);
  }

  public boolean hasOnline() {
    return !sourceId.isEmpty();
  }

  public boolean hasOffline() {
    return !offlineFile.isEmpty() || !documentUri.isEmpty();
  }

  public static boolean supported(String name) {
    String ext = extension(name);
    return ext.equals("epub")
        || ext.equals("txt")
        || ext.equals("pdf")
        || ext.equals("azw3")
        || ext.equals("mobi")
        || ext.equals("azw")
        || ext.equals("prc");
  }

  public static String extension(String name) {
    int i = name.lastIndexOf('.');
    return i < 0 ? "" : name.substring(i + 1).toLowerCase(Locale.ROOT);
  }

  public JSONObject json() throws JSONException {
    return new JSONObject()
        .put("id", id)
        .put("title", title)
        .put("author", author)
        .put("format", format)
        .put("sourceId", sourceId)
        .put("remotePath", remotePath)
        .put("offlineFile", offlineFile)
        .put("lastMode", lastMode)
        .put("chapter", chapter)
        .put("offset", offset)
        .put("pdfPage", pdfPage)
        .put("pdfX", pdfX)
        .put("pdfY", pdfY)
        .put("pdfZoom", pdfZoom)
        .put("imageY", imageY)
        .put("imageFit", imageFit)
        .put("txtByte", txtByte)
        .put("size", size)
        .put("lastRead", lastRead)
        .put("readingStyle", readingStyle == null ? JSONObject.NULL : readingStyle.json())
        .put("searchHistory", new JSONArray(searchHistory))
        .put("pinned", pinned)
        .put("group", group)
        .put("readState", readState)
        .put("documentUri", documentUri);
  }

  public static BookRecord from(JSONObject j) throws JSONException {
    BookRecord b = new BookRecord();
    b.id = j.getString("id");
    if (!b.id.matches("[a-f0-9-]{36}")) throw new JSONException("书籍标识损坏");
    b.title = j.optString("title", "未命名");
    b.author = j.optString("author");
    b.format = j.optString("format", "epub");
    if (!supported("book." + b.format)) throw new JSONException("书籍格式无效");
    b.sourceId = j.optString("sourceId");
    b.remotePath = j.optString("remotePath");
    b.offlineFile = j.optString("offlineFile");
    b.documentUri = j.optString("documentUri");
    if (!b.documentUri.isEmpty() && !b.documentUri.startsWith("content://"))
      throw new JSONException("本地文件授权无效");
    if (!b.offlineFile.isEmpty() && !b.offlineFile.equals(b.id + "." + b.format))
      throw new JSONException("本地路径无效");
    b.lastMode = j.optString("lastMode", "offline");
    b.chapter = Math.max(0, j.optInt("chapter"));
    b.offset = Math.max(0, j.optInt("offset"));
    b.pdfPage = Math.max(0, j.optInt("pdfPage"));
    b.pdfX = bounded(j.optDouble("pdfX", 0), 0, 1);
    b.pdfY = bounded(j.optDouble("pdfY", 0), 0, 1);
    b.pdfZoom = bounded(j.optDouble("pdfZoom", 1), 1, 3);
    b.imageY = bounded(j.optDouble("imageY", 0), 0, 1);
    b.imageFit = Math.max(0, Math.min(2, j.optInt("imageFit", 0)));
    b.txtByte = Math.max(0, j.optLong("txtByte"));
    b.size = Math.max(0, j.optLong("size"));
    b.lastRead = j.optLong("lastRead");
    b.pinned = j.optBoolean("pinned", false);
    b.group = j.optString("group", "");
    b.readState = j.optString("readState", b.lastRead > 0 ? "reading" : "unread");
    if (!b.readState.equals("reading") && !b.readState.equals("finished")) b.readState = "unread";
    JSONObject style = j.optJSONObject("readingStyle");
    if (style != null) b.readingStyle = ReadingStyle.from(style);
    JSONArray history = j.optJSONArray("searchHistory");
    if (history != null)
      for (int i = Math.min(history.length(), 8) - 1; i >= 0; i--)
        b.rememberSearch(history.optString(i, ""));
    return b;
  }

  static float bounded(double value, float min, float max) {
    return Double.isFinite(value) ? (float) Math.max(min, Math.min(max, value)) : min;
  }
}
