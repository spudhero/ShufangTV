package io.github.spudhero.shufangtv.data;

import android.content.Context;
import android.util.AtomicFile;
import io.github.spudhero.shufangtv.core.TextNavigator;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.json.JSONArray;
import org.json.JSONObject;

/** Atomic private storage for metadata; complete offline files live in books/. */
public final class LibraryStore {
  private final AtomicFile metadata;
  public final File booksDir;
  public final List<BookRecord> books = new ArrayList<>();
  public final List<Mark> marks = new ArrayList<>();
  public String loadError;
  private boolean unreadableMetadata;

  public LibraryStore(Context context) {
    booksDir = new File(context.getFilesDir(), "books");
    if (!booksDir.isDirectory() && !booksDir.mkdirs()) loadError = "无法创建本地书库";
    metadata = new AtomicFile(new File(context.getFilesDir(), "library.json"));
    try (InputStream input = metadata.openRead()) {
      JSONObject j =
          new JSONObject(new String(readBounded(input, 4 * 1024 * 1024), StandardCharsets.UTF_8));
      JSONArray a = j.optJSONArray("books"), m = j.optJSONArray("marks");
      if (a != null)
        for (int i = 0; i < a.length(); i++) books.add(BookRecord.from(a.getJSONObject(i)));
      if (m != null) for (int i = 0; i < m.length(); i++) marks.add(Mark.from(m.getJSONObject(i)));
    } catch (FileNotFoundException ignored) {
      // A new installation has no metadata yet.
    } catch (Exception e) {
      books.clear();
      marks.clear();
      unreadableMetadata = true;
      loadError = "书库记录无法读取，原文件已保留；请先备份或清理应用数据后再使用。";
    }
  }

  public synchronized void save() throws IOException {
    if (unreadableMetadata) throw new IOException("书库记录已损坏，已停止覆盖原文件");
    FileOutputStream output = null;
    try {
      JSONArray a = new JSONArray(), m = new JSONArray();
      for (BookRecord b : books) a.put(b.json());
      for (Mark mark : marks) m.put(mark.json());
      byte[] bytes =
          new JSONObject()
              .put("version", 1)
              .put("books", a)
              .put("marks", m)
              .toString()
              .getBytes(StandardCharsets.UTF_8);
      if (bytes.length > 4 * 1024 * 1024) throw new IOException("书库记录已达容量上限，请先导出并清理部分笔记");
      output = metadata.startWrite();
      output.write(bytes);
      metadata.finishWrite(output);
    } catch (Exception e) {
      if (output != null) metadata.failWrite(output);
      throw new IOException("无法保存阅读记录", e);
    }
  }

  public BookRecord find(String id) {
    for (BookRecord b : books) if (b.id.equals(id)) return b;
    return null;
  }

  public BookRecord remote(String source, String path) {
    for (BookRecord b : books) if (b.sourceId.equals(source) && b.remotePath.equals(path)) return b;
    return null;
  }

  public File file(BookRecord b) {
    return new File(booksDir, b.id + "." + b.format);
  }

  public List<BookRecord> recent() {
    List<BookRecord> list = new ArrayList<>(books);
    list.sort((a, b) -> Long.compare(b.lastRead, a.lastRead));
    return list;
  }

  public List<BookRecord> shelf(boolean byTitle) {
    List<BookRecord> list = new ArrayList<>(books);
    list.sort(
        (a, b) -> {
          int pinned = Boolean.compare(b.pinned, a.pinned);
          if (pinned != 0) return pinned;
          int order =
              byTitle ? a.title.compareToIgnoreCase(b.title) : Long.compare(b.lastRead, a.lastRead);
          return order != 0 ? order : a.id.compareTo(b.id);
        });
    return list;
  }

  public List<String> groups() {
    java.util.Set<String> names = new java.util.TreeSet<>();
    for (BookRecord b : books) if (!b.group.isEmpty()) names.add(b.group);
    return new ArrayList<>(names);
  }

  public void bookmark(BookRecord b, String excerpt) throws IOException {
    for (Mark m : marks)
      if (m.bookId.equals(b.id)
          && m.chapter == b.chapter
          && m.offset == b.offset
          && m.pdfPage == b.pdfPage
          && Math.abs(m.pdfY - b.pdfY) < .0001f
          && Math.abs(m.imageY - b.imageY) < .0001f
          && m.txtByte == b.txtByte) return;
    Mark m = new Mark();
    m.bookId = b.id;
    m.chapter = b.chapter;
    m.offset = b.offset;
    m.pdfPage = b.pdfPage;
    m.pdfX = b.pdfX;
    m.pdfY = b.pdfY;
    m.pdfZoom = b.pdfZoom;
    m.imageY = b.imageY;
    m.txtByte = b.txtByte;
    m.excerpt = excerpt;
    m.mode = b.lastMode;
    marks.add(m);
    try {
      save();
    } catch (IOException e) {
      marks.remove(m);
      throw e;
    }
  }

  public Mark annotate(BookRecord b, String text, TextNavigator.Range range, String note)
      throws IOException {
    if (range.start < 0
        || range.end <= range.start
        || range.end > text.length()
        || range.end - range.start > 4000
        || TextNavigator.boundary(text, range.start) != range.start
        || TextNavigator.boundary(text, range.end) != range.end) throw new IOException("请选择有效正文段落");
    for (Mark mark : marks)
      if (mark.isNote()
          && mark.bookId.equals(b.id)
          && mark.chapter == b.chapter
          && mark.txtByte == b.txtByte
          && mark.offset == range.start
          && mark.end == range.end
          && mark.matches(text)) {
        updateNote(mark, note);
        return mark;
      }
    Mark mark = new Mark();
    mark.bookId = b.id;
    mark.chapter = b.chapter;
    mark.txtByte = b.txtByte;
    mark.offset = range.start;
    mark.end = range.end;
    mark.excerpt = text.substring(range.start, range.end);
    mark.note = boundedNote(note);
    mark.createdAt = System.currentTimeMillis();
    mark.mode = b.lastMode;
    marks.add(mark);
    try {
      save();
    } catch (IOException e) {
      marks.remove(mark);
      throw e;
    }
    return mark;
  }

  private static String boundedNote(String note) {
    String value = note == null ? "" : note.trim();
    return value.substring(0, TextNavigator.boundary(value, Math.min(value.length(), 2000)));
  }

  public void updateNote(Mark mark, String note) throws IOException {
    String before = mark.note;
    mark.note = boundedNote(note);
    try {
      save();
    } catch (IOException e) {
      mark.note = before;
      throw e;
    }
  }

  public void removeMark(Mark mark) throws IOException {
    int at = marks.indexOf(mark);
    if (at < 0) return;
    marks.remove(at);
    try {
      save();
    } catch (IOException e) {
      marks.add(at, mark);
      throw e;
    }
  }

  public static byte[] readBounded(InputStream input, int limit) throws IOException {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    byte[] buf = new byte[16384];
    int n;
    while ((n = input.read(buf)) != -1) {
      if (out.size() + n > limit) throw new IOException("文件超过读取上限");
      out.write(buf, 0, n);
    }
    return out.toByteArray();
  }

  public static final class Mark {
    public String id = UUID.randomUUID().toString(), bookId, excerpt = "", mode = "offline";
    public int chapter, offset, pdfPage;
    public float pdfX, pdfY, imageY, pdfZoom = 1;
    public long txtByte;
    public int end;
    public long createdAt;
    public String note = "";

    public boolean isNote() {
      return end > offset;
    }

    public boolean matches(String text) {
      return isNote()
          && offset >= 0
          && end <= text.length()
          && end - offset == excerpt.length()
          && TextNavigator.boundary(text, offset) == offset
          && TextNavigator.boundary(text, end) == end
          && text.regionMatches(offset, excerpt, 0, excerpt.length());
    }

    JSONObject json() throws Exception {
      return new JSONObject()
          .put("id", id)
          .put("bookId", bookId)
          .put("excerpt", excerpt)
          .put("mode", mode)
          .put("chapter", chapter)
          .put("offset", offset)
          .put("pdfPage", pdfPage)
          .put("pdfX", pdfX)
          .put("pdfY", pdfY)
          .put("pdfZoom", pdfZoom)
          .put("imageY", imageY)
          .put("txtByte", txtByte)
          .put("end", end)
          .put("note", note)
          .put("createdAt", createdAt);
    }

    static Mark from(JSONObject j) {
      Mark m = new Mark();
      m.id = j.optString("id", m.id);
      m.bookId = j.optString("bookId");
      m.excerpt = j.optString("excerpt");
      m.mode = j.optString("mode", "offline");
      m.chapter = Math.max(0, j.optInt("chapter"));
      m.offset = Math.max(0, j.optInt("offset"));
      m.pdfPage = Math.max(0, j.optInt("pdfPage"));
      m.pdfX = BookRecord.bounded(j.optDouble("pdfX", 0), 0, 1);
      m.pdfY = BookRecord.bounded(j.optDouble("pdfY", 0), 0, 1);
      m.pdfZoom = BookRecord.bounded(j.optDouble("pdfZoom", 1), 1, 3);
      m.imageY = BookRecord.bounded(j.optDouble("imageY", 0), 0, 1);
      m.txtByte = Math.max(0, j.optLong("txtByte"));
      m.end = Math.max(0, j.optInt("end"));
      m.note = boundedNote(j.optString("note"));
      m.createdAt = Math.max(0, j.optLong("createdAt"));
      return m;
    }
  }
}
