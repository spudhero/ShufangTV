package io.github.spudhero.shufangtv.cloud;

import io.github.spudhero.shufangtv.net.RemoteFile;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import okhttp3.Call;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/** Strict, bounded HTTP ranges. A full response is never treated as an online range. */
public final class CloudRangeFile implements RemoteFile {
  public interface Resolver {
    CloudProvider.DownloadLink resolve() throws IOException;
  }

  public interface AccessGuard {
    void check() throws IOException;
  }

  private static final Pattern RANGE = Pattern.compile("bytes (\\d+)-(\\d+)/(\\d+)");
  private static final OkHttpClient HTTP =
      new OkHttpClient.Builder()
          .followRedirects(false)
          .followSslRedirects(false)
          .connectTimeout(20, TimeUnit.SECONDS)
          .readTimeout(30, TimeUnit.SECONDS)
          .callTimeout(45, TimeUnit.SECONDS)
          .build();
  private final Resolver resolver;
  private final AccessGuard guard;
  private CloudProvider.DownloadLink link;
  private long size = -1;
  private String etag, modified;
  private volatile boolean closed;
  private volatile Call activeCall;

  public CloudRangeFile(Resolver resolver) throws IOException {
    this(resolver, () -> {});
  }

  public CloudRangeFile(Resolver resolver, AccessGuard guard) throws IOException {
    this.resolver = resolver;
    this.guard = guard;
    guard.check();
    link = resolver.resolve();
    read(0, new byte[1], 0, 1);
  }

  @Override
  public long length() {
    return size;
  }

  @Override
  public synchronized int read(long position, byte[] buffer, int offset, int length)
      throws IOException {
    if (closed) throw new IOException("书籍已关闭");
    guard.check();
    if (position < 0 || offset < 0 || length < 0 || offset > buffer.length - length)
      throw new IndexOutOfBoundsException();
    if (length == 0) return 0;
    if (size >= 0 && position >= size) return -1;
    int count = (int) Math.min(Math.min(length, 256 * 1024), size < 0 ? length : size - position);
    if (position > Long.MAX_VALUE - count) throw new IOException("无效的书籍位置");
    for (int attempt = 0; attempt < 2; attempt++) {
      if (Thread.currentThread().isInterrupted()) throw new IOException("已取消读取");
      if (link.expiresAt > 0 && link.expiresAt <= System.currentTimeMillis()) refresh();
      try (Transfer transfer =
          request(
              link,
              "bytes=" + position + "-" + (position + count - 1),
              call -> {
                activeCall = call;
                if (closed) call.cancel();
              })) {
        Response response = transfer.response;
        if ((response.code() == 401 || response.code() == 403) && attempt == 0) {
          refresh();
          continue;
        }
        if (response.code() != 206) throw new IOException("此网盘暂不支持这本书的按需读取。可单独选择“保存到本地”，或更换来源。");
        Matcher range = RANGE.matcher(response.header("Content-Range", ""));
        if (!range.matches()) throw new IOException("网盘未返回有效的读取范围");
        long start, end, total;
        try {
          start = Long.parseLong(range.group(1));
          end = Long.parseLong(range.group(2));
          total = Long.parseLong(range.group(3));
        } catch (NumberFormatException e) {
          throw new IOException("网盘返回了无效的书籍长度");
        }
        if (start != position
            || end != position + count - 1
            || total <= end
            || (size >= 0 && total != size)
            || (link.size > 0 && total != link.size)) throw new IOException("书籍长度或读取范围已变化，请重新打开");
        if (!"identity".equalsIgnoreCase(response.header("Content-Encoding", "identity")))
          throw new IOException("网盘未按原始字节返回书籍");
        String currentEtag = response.header("ETag"),
            currentModified = response.header("Last-Modified");
        if (size >= 0
            && ((etag != null && !etag.equals(currentEtag))
                || (modified != null && !modified.equals(currentModified))))
          throw new IOException("网盘中的书籍已经更新，请重新打开");
        if (response.body() == null) throw new EOFException("书籍内容不完整");
        if (response.body().contentLength() >= 0 && response.body().contentLength() != count)
          throw new IOException("网盘返回的内容长度与范围不一致");
        InputStream input = response.body().byteStream();
        int got = 0;
        while (got < count) {
          if (Thread.currentThread().isInterrupted()) throw new IOException("已取消读取");
          int n = input.read(buffer, offset + got, count - got);
          if (n < 0) throw new EOFException("在线读取中断，请重试");
          got += n;
        }
        if (input.read() != -1) throw new IOException("网盘返回了超出请求范围的内容");
        size = total;
        etag = currentEtag;
        modified = currentModified;
        return count;
      } finally {
        activeCall = null;
      }
    }
    throw new IOException("网盘读取地址失效，请重新扫码");
  }

  private void refresh() throws IOException {
    CloudProvider.DownloadLink fresh = resolver.resolve();
    if (size >= 0
        && ((fresh.size > 0 && fresh.size != size)
            || (link.identity != null
                && !link.identity.isEmpty()
                && fresh.identity != null
                && !fresh.identity.isEmpty()
                && !link.identity.equals(fresh.identity))))
      throw new IOException("网盘中的书籍已经更新，请重新打开");
    link = fresh;
  }

  /** Follow file redirects explicitly, dropping credentials when the origin changes. */
  public static final class Transfer implements AutoCloseable {
    public final Response response;
    private final Call call;

    Transfer(Response response, Call call) {
      this.response = response;
      this.call = call;
    }

    @Override
    public void close() {
      // OkHttp otherwise drains a rejected body for connection reuse, possibly consuming a whole
      // book.
      call.cancel();
      response.close();
    }
  }

  public static Transfer request(CloudProvider.DownloadLink link, String range) throws IOException {
    return request(link, range, call -> {});
  }

  private static Transfer request(
      CloudProvider.DownloadLink link, String range, java.util.function.Consumer<Call> currentCall)
      throws IOException {
    HttpUrl original = HttpUrl.parse(link.url);
    if (original == null) throw new IOException("网盘返回了无效的读取地址");
    HttpUrl current = original;
    for (int redirects = 0; redirects <= 5; redirects++) {
      CloudHttp.requireSecure(current);
      if (original.isHttps() && !current.isHttps()) throw new IOException("网盘读取地址不能降级为 HTTP");
      Request.Builder builder =
          new Request.Builder()
              .url(current)
              .header("Accept-Encoding", "identity")
              .header("User-Agent", CloudHttp.USER_AGENT);
      boolean sameOrigin =
          original.scheme().equals(current.scheme())
              && original.host().equals(current.host())
              && original.port() == current.port();
      for (Map.Entry<String, String> header : link.headers.entrySet()) {
        String name = header.getKey();
        if (name.equalsIgnoreCase("Range")
            || name.equalsIgnoreCase("Accept-Encoding")
            || name.equalsIgnoreCase("Host")) continue;
        if (sameOrigin || name.equalsIgnoreCase("User-Agent") || name.equalsIgnoreCase("Referer"))
          builder.header(name, header.getValue());
      }
      if (range != null) builder.header("Range", range);
      Call call = HTTP.newCall(builder.build());
      currentCall.accept(call);
      Response response;
      try {
        response = call.execute();
      } catch (IOException e) {
        throw new IOException("网盘文件连接中断，请检查网络后重试", e);
      }
      int code = response.code();
      if (code != 301 && code != 302 && code != 303 && code != 307 && code != 308)
        return new Transfer(response, call);
      String location = response.header("Location");
      HttpUrl next = location == null ? null : current.resolve(location);
      call.cancel();
      response.close();
      if (next == null || redirects == 5) throw new IOException("网盘读取地址跳转无效");
      current = next;
    }
    throw new IOException("网盘读取地址跳转过多");
  }

  @Override
  public void close() {
    closed = true;
    Call call = activeCall;
    if (call != null) call.cancel();
  }
}
