package io.github.spudhero.shufangtv.reader;

import android.content.Context;
import android.graphics.*;
import android.graphics.pdf.PdfRenderer;
import android.os.*;
import android.os.storage.StorageManager;
import android.system.*;
import io.github.spudhero.shufangtv.core.*;
import io.github.spudhero.shufangtv.data.*;
import io.github.spudhero.shufangtv.net.*;
import java.io.*;
import java.util.*;

/** All open/chapter/render/close calls run on the reader I/O executor. */
public final class BookSession implements AutoCloseable {
  public final BookRecord record;
  public final boolean online;
  public EpubBook epub;
  public String title, author;
  public TextSegments.Segment segment;
  private BookArchive archive;
  private TextSegments text;
  private PdfRenderer pdf;
  private HandlerThread proxyThread;
  private final BookParser parser = new BookParser();
  private final LinkedHashMap<Integer, BookDocument.Chapter> chapters =
      new LinkedHashMap<>(3, 0.75f, true);

  private BookSession(BookRecord record, boolean online) {
    this.record = record;
    this.online = online;
    title = record.title;
    author = record.author;
  }

  public static BookSession open(
      Context context, BookRecord record, boolean online, LibraryStore library, SourceStore sources)
      throws IOException {
    BookSession session = new BookSession(record, online);
    RemoteFile remote = null;
    try {
      if (online) {
        SourceConfig config = sources.find(record.sourceId);
        if (config == null) throw new IOException("书库连接已移除，请重新添加");
        remote = new NetworkLibrary(context).open(config, record.remotePath);
        record.size = remote.length();
      }
      if (record.format.equals("epub")) {
        session.archive =
            online
                ? new RemoteZipArchive(remote)
                : !record.documentUri.isEmpty()
                    ? new RemoteZipArchive(new DocumentRemoteFile(context, record.documentUri))
                    : new LocalArchive(library.file(record));
        remote = null;
        session.readMetadata();
      } else if (record.format.equals("txt")) {
        session.text =
            new TextSegments(
                online
                    ? remote
                    : !record.documentUri.isEmpty()
                        ? new DocumentRemoteFile(context, record.documentUri)
                        : new FileRemoteFile(library.file(record)));
        remote = null;
      } else if (record.format.equals("pdf") || isKindle(record.format)) {
        ParcelFileDescriptor descriptor;
        if (online) {
          RemoteFile file = remote;
          session.proxyThread = new HandlerThread("document-network");
          session.proxyThread.start();
          StorageManager manager = context.getSystemService(StorageManager.class);
          descriptor =
              manager.openProxyFileDescriptor(
                  ParcelFileDescriptor.MODE_READ_ONLY,
                  new ProxyFileDescriptorCallback() {
                    @Override
                    public long onGetSize() {
                      return file.length();
                    }

                    @Override
                    public int onRead(long offset, int size, byte[] data) throws ErrnoException {
                      try {
                        int got = 0;
                        while (got < size && offset + got < file.length()) {
                          int n =
                              file.read(offset + got, data, got, Math.min(size - got, 256 * 1024));
                          if (n <= 0) break;
                          got += n;
                        }
                        return got;
                      } catch (IOException e) {
                        throw new ErrnoException("在线书籍读取中断", OsConstants.EIO, e);
                      }
                    }

                    @Override
                    public void onRelease() {
                      try {
                        file.close();
                      } catch (IOException ignored) {
                      }
                    }
                  },
                  new Handler(session.proxyThread.getLooper()));
          remote = null;
        } else
          descriptor =
              !record.documentUri.isEmpty()
                  ? context
                      .getContentResolver()
                      .openFileDescriptor(android.net.Uri.parse(record.documentUri), "r")
                  : ParcelFileDescriptor.open(
                      library.file(record), ParcelFileDescriptor.MODE_READ_ONLY);
        if (descriptor == null) throw new IOException("无法打开书籍文件，请重新选择文件");
        try {
          if (record.format.equals("pdf")) session.pdf = new PdfRenderer(descriptor);
          else {
            session.archive = new MobiArchive(descriptor.getFd(), descriptor.getStatSize());
            descriptor.close();
            session.readMetadata();
          }
        } catch (Exception e) {
          descriptor.close();
          throw e;
        }
      } else throw new IOException("暂不支持这种书籍格式");
      return session;
    } catch (Exception e) {
      if (remote != null)
        try {
          remote.close();
        } catch (IOException ignored) {
        }
      session.close();
      throw e instanceof IOException
          ? (IOException) e
          : new IOException("无法打开书籍：" + e.getMessage(), e);
    }
  }

  public static boolean isKindle(String format) {
    return format.equals("mobi")
        || format.equals("azw3")
        || format.equals("azw")
        || format.equals("prc");
  }

  private void readMetadata() throws IOException {
    epub =
        archive instanceof MobiArchive
            ? ((MobiArchive) archive).inspect(parser)
            : parser.inspect(archive);
    title = epub.title;
    author = epub.author;
  }

  public int chapterCount() {
    return epub == null ? 1 : epub.spine.size();
  }

  public List<String> chapterTitles() {
    List<String> list = new ArrayList<>();
    if (epub != null) for (EpubBook.SpineItem item : epub.spine) list.add(item.title);
    else list.add(record.format.equals("pdf") ? "PDF 页面" : "正文");
    return list;
  }

  public BookDocument.Chapter chapter(int index, long byteStart) throws IOException {
    if (epub != null) {
      BookDocument.Chapter chapter = chapters.get(index);
      if (chapter == null) {
        chapter = parser.readChapter(archive, epub.spine.get(index));
        chapters.put(index, chapter);
        while (chapters.size() > 2) chapters.remove(chapters.keySet().iterator().next());
      }
      return chapter;
    }
    segment = text.read(byteStart);
    return new BookDocument.Chapter("正文", segment.text);
  }

  public BookDocument.Chapter previousSegment() throws IOException {
    segment = text.before(segment.start);
    return new BookDocument.Chapter("正文", segment.text);
  }

  public long textLength() {
    return text == null ? 0 : text.length();
  }

  public int pdfPages() {
    return pdf == null ? 0 : pdf.getPageCount();
  }

  public Bitmap illustration(BookDocument.Illustration ref) throws IOException {
    return IllustrationDecoder.read(archive, ref);
  }

  public Bitmap illustration(BookDocument.Illustration ref, int width, int height)
      throws IOException {
    return IllustrationDecoder.read(archive, ref, width, height);
  }

  /** Full first-page thumbnail; independent of the reader's current page, scroll and zoom. */
  public Bitmap coverThumbnail(int width, int height) throws IOException {
    if (epub != null) {
      BookDocument.Illustration image = parser.coverIllustration(archive, epub);
      return image == null ? null : IllustrationDecoder.read(archive, image, width, height);
    }
    if (pdf == null || pdf.getPageCount() == 0) return null;
    try (PdfRenderer.Page page = pdf.openPage(0)) {
      float scale = Math.min((float) width / page.getWidth(), (float) height / page.getHeight());
      Bitmap bitmap =
          Bitmap.createBitmap(
              Math.max(1, Math.round(page.getWidth() * scale)),
              Math.max(1, Math.round(page.getHeight() * scale)),
              Bitmap.Config.ARGB_8888);
      bitmap.eraseColor(Color.WHITE);
      Matrix matrix = new Matrix();
      matrix.setScale(scale, scale);
      try {
        page.render(bitmap, null, matrix, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
        return bitmap;
      } catch (RuntimeException e) {
        bitmap.recycle();
        throw new IOException("PDF 封面读取失败", e);
      }
    }
  }

  public static final class PdfFrame {
    public final Bitmap bitmap;
    public final DocumentViewport viewport;

    PdfFrame(Bitmap bitmap, DocumentViewport viewport) {
      this.bitmap = bitmap;
      this.viewport = viewport;
    }
  }

  public PdfFrame renderPdf(int page, int width, int height, float zoom, float x, float y)
      throws IOException {
    try (PdfRenderer.Page p = pdf.openPage(page)) {
      DocumentViewport viewport =
          new DocumentViewport(p.getWidth(), p.getHeight(), width, height, zoom, x, y);
      Bitmap bitmap = Bitmap.createBitmap(viewport.width, viewport.height, Bitmap.Config.ARGB_8888);
      bitmap.eraseColor(Color.WHITE);
      Matrix matrix = new Matrix();
      matrix.setScale(viewport.scale, viewport.scale);
      matrix.postTranslate(-viewport.left(), -viewport.top());
      try {
        p.render(bitmap, null, matrix, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
        return new PdfFrame(bitmap, viewport);
      } catch (Exception e) {
        bitmap.recycle();
        throw e;
      }
    } catch (Exception e) {
      throw new IOException("PDF 页面读取失败，请检查连接后重试", e);
    }
  }

  @Override
  public void close() throws IOException {
    IOException failure = null;
    try {
      if (archive != null) archive.close();
    } catch (IOException e) {
      failure = e;
    }
    try {
      if (text != null) text.close();
    } catch (IOException e) {
      failure = e;
    }
    if (pdf != null) {
      pdf.close();
      pdf = null;
    }
    if (proxyThread != null) proxyThread.quitSafely();
    chapters.clear();
    if (failure != null) throw failure;
  }
}
