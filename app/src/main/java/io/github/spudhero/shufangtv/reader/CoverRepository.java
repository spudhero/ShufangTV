package io.github.spudhero.shufangtv.reader;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.AtomicFile;
import android.util.LruCache;
import io.github.spudhero.shufangtv.data.*;
import io.github.spudhero.shufangtv.net.*;
import io.github.spudhero.shufangtv.ui.CoverView;
import java.io.*;
import java.lang.ref.WeakReference;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;

/** Activity-scoped thumbnails, with I/O isolated from the UI and reader queues. */
public final class CoverRepository implements AutoCloseable {
  static final int WIDTH = 384, HEIGHT = 576;
  private final int width, height;
  private static final long RETRY_MS = 30_000, MAX_DISK_BYTES = 64L * 1024 * 1024;
  private final File directory;
  private final LibraryStore library;
  private final Extractor extractor;
  private final ExecutorService worker = Executors.newSingleThreadExecutor();
  private final Handler main = new Handler(Looper.getMainLooper());
  private final LruCache<String, Bitmap> memory =
      new LruCache<>(8 * 1024 * 1024) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
          return value.getAllocationByteCount();
        }
      };
  // Only the UI thread accesses requests/retryAfter and completes view updates.
  private final Map<String, List<WeakReference<CoverView>>> requests = new HashMap<>();
  private final Map<String, Long> retryAfter = new HashMap<>();
  private volatile boolean closed;

  interface Extractor {
    Bitmap read(BookRecord book) throws IOException;
  }

  public CoverRepository(Context context, LibraryStore library, SourceStore sources) {
    this(
        context.getCacheDir(),
        library,
        book -> extract(context.getApplicationContext(), library, sources, book),
        coverWidth(context));
  }

  CoverRepository(File cache, LibraryStore library, Extractor extractor) {
    this(cache, library, extractor, WIDTH);
  }

  private CoverRepository(File cache, LibraryStore library, Extractor extractor, int width) {
    this.width = width;
    this.height = width * 3 / 2;
    this.directory = new File(cache, "covers-v1");
    this.library = library;
    this.extractor = extractor;
  }

  private static int coverWidth(Context context) {
    return context.getResources().getDisplayMetrics().density > 2.5f ? 512 : WIDTH;
  }

  public void bind(CoverView view, BookRecord book) {
    if (closed || book.format.equals("txt")) return;
    BookRecord copy = copy(book);
    String key = key(copy);
    Bitmap bitmap = memory.get(key);
    if (bitmap != null) view.setImage(bitmap);
    else view.loadWhenVisible(() -> request(view, copy, key));
  }

  private void request(CoverView view, BookRecord book, String key) {
    if (closed || SystemClock.uptimeMillis() < retryAfter.getOrDefault(key, 0L)) return;
    Bitmap cached = memory.get(key);
    if (cached != null) {
      view.setImage(cached);
      return;
    }
    List<WeakReference<CoverView>> listeners = requests.get(key);
    if (listeners != null) {
      listeners.add(new WeakReference<>(view));
      return;
    }
    listeners = new ArrayList<>();
    listeners.add(new WeakReference<>(view));
    requests.put(key, listeners);
    worker.execute(
        () -> {
          Bitmap result = disk(key);
          if (result == null) {
            try {
              result = extractor.read(book);
              if (result != null) store(key, result);
            } catch (IOException | RuntimeException ignored) {
              // Source failures never become a permanent "no cover" entry on disk.
            }
          }
          Bitmap ready = result;
          if (ready != null && !closed) memory.put(key, ready);
          main.post(
              () -> {
                List<WeakReference<CoverView>> pending = requests.remove(key);
                if (closed || pending == null) return;
                if (ready == null) retryAfter.put(key, SystemClock.uptimeMillis() + RETRY_MS);
                else {
                  retryAfter.remove(key);
                  for (WeakReference<CoverView> ref : pending) {
                    CoverView target = ref.get();
                    if (target != null && target.isAttachedToWindow()) target.setImage(ready);
                  }
                }
              });
        });
  }

  private static Bitmap extract(
      Context context, LibraryStore library, SourceStore sources, BookRecord book)
      throws IOException {
    boolean online = !book.hasOffline() && book.hasOnline();
    try {
      return extract(context, library, sources, book, online);
    } catch (IOException e) {
      if (!online && book.hasOnline()) return extract(context, library, sources, book, true);
      throw e;
    }
  }

  private static Bitmap extract(
      Context context, LibraryStore library, SourceStore sources, BookRecord book, boolean online)
      throws IOException {
    if (BookSession.isKindle(book.format)) {
      SourceConfig config = online ? sources.find(book.sourceId) : null;
      if (online && config == null) throw new IOException("书库连接已移除");
      try (RemoteFile file =
          online
              ? new NetworkLibrary(context).open(config, book.remotePath)
              : !book.documentUri.isEmpty()
                  ? new DocumentRemoteFile(context, book.documentUri)
                  : new FileRemoteFile(library.file(book))) {
        byte[] image = KindleCover.read(file);
        int width = coverWidth(context);
        return image == null ? null : IllustrationDecoder.thumbnail(image, width, width * 3 / 2);
      }
    }
    try (BookSession session = BookSession.open(context, book, online, library, sources)) {
      int width = coverWidth(context);
      return session.coverThumbnail(width, width * 3 / 2);
    }
  }

  private Bitmap disk(String key) {
    File file = new File(directory, key + ".png");
    try (InputStream input = new AtomicFile(file).openRead()) {
      if (file.length() > 4 * 1024 * 1024) throw new IOException("封面缓存无效");
      ByteArrayOutputStream out = new ByteArrayOutputStream();
      byte[] buffer = new byte[8192];
      int count;
      while ((count = input.read(buffer)) != -1) {
        if (out.size() + count > 4 * 1024 * 1024) throw new IOException("封面缓存过大");
        out.write(buffer, 0, count);
      }
      byte[] bytes = out.toByteArray();
      Bitmap bitmap = IllustrationDecoder.thumbnail(bytes, width, height);
      file.setLastModified(System.currentTimeMillis());
      return bitmap;
    } catch (IOException | RuntimeException ignored) {
      new AtomicFile(file).delete();
      return null;
    }
  }

  private void store(String key, Bitmap image) throws IOException {
    if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("无法创建封面缓存");
    AtomicFile file = new AtomicFile(new File(directory, key + ".png"));
    FileOutputStream out = null;
    try {
      out = file.startWrite();
      if (!image.compress(Bitmap.CompressFormat.PNG, 100, out)) throw new IOException("封面缓存写入失败");
      file.finishWrite(out);
    } catch (IOException | RuntimeException e) {
      file.failWrite(out);
      throw e;
    }
    File[] files = directory.listFiles((dir, name) -> name.endsWith(".png"));
    if (files == null) return;
    Arrays.sort(files, Comparator.comparingLong(File::lastModified));
    long bytes = 0;
    for (File entry : files) bytes += entry.length();
    for (int i = 0; i < files.length && (bytes > MAX_DISK_BYTES || files.length - i > 256); i++) {
      long length = files[i].length();
      if (files[i].delete()) bytes -= length;
    }
  }

  private String key(BookRecord book) {
    File local = library.file(book);
    String identity =
        book.id
            + "\n"
            + book.format
            + "\n"
            + book.sourceId
            + "\n"
            + book.remotePath
            + "\n"
            + book.documentUri
            + "\n"
            + book.offlineFile
            + "\n"
            + book.size
            + (book.offlineFile.isEmpty()
                ? ""
                : "\n" + local.length() + ":" + local.lastModified());
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256").digest(identity.getBytes(StandardCharsets.UTF_8));
      StringBuilder out = new StringBuilder();
      for (byte b : digest) out.append(String.format(Locale.ROOT, "%02x", b & 255));
      return (width > WIDTH ? "4k-" : "") + out;
    } catch (java.security.NoSuchAlgorithmException e) {
      throw new AssertionError(e);
    }
  }

  private static BookRecord copy(BookRecord book) {
    BookRecord copy = new BookRecord();
    copy.id = book.id;
    copy.title = book.title;
    copy.author = book.author;
    copy.format = book.format;
    copy.sourceId = book.sourceId;
    copy.remotePath = book.remotePath;
    copy.documentUri = book.documentUri;
    copy.offlineFile = book.offlineFile;
    copy.size = book.size;
    return copy;
  }

  @Override
  public void close() {
    closed = true;
    requests.clear();
    retryAfter.clear();
    worker.shutdownNow();
    memory.evictAll();
  }
}
