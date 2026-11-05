package io.github.spudhero.shufangtv.data;

import android.content.Context;
import android.util.AtomicFile;
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
      output = metadata.startWrite();
      output.write(
          new JSONObject()
              .put("version", 1)
              .put("books", a)
              .put("marks", m)
              .toString()
              .getBytes(StandardCharsets.UTF_8));
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

  public void bookmark(BookRecord b, String excerpt) throws IOException {
    for (Mark m : marks)
      if (m.bookId.equals(b.id)
          && m.chapter == b.chapter
          && m.offset == b.offset
          && m.pdfPage == b.pdfPage
          && m.txtByte == b.txtByte) return;
    Mark m = new Mark();
    m.bookId = b.id;
    m.chapter = b.chapter;
    m.offset = b.offset;
    m.pdfPage = b.pdfPage;
    m.txtByte = b.txtByte;
    m.excerpt = excerpt;
    m.mode = b.lastMode;
    marks.add(m);
    save();
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
    public long txtByte;

    JSONObject json() throws Exception {
      return new JSONObject()
          .put("id", id)
          .put("bookId", bookId)
          .put("excerpt", excerpt)
          .put("mode", mode)
          .put("chapter", chapter)
          .put("offset", offset)
          .put("pdfPage", pdfPage)
          .put("txtByte", txtByte);
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
      m.txtByte = Math.max(0, j.optLong("txtByte"));
      return m;
    }
  }
}
