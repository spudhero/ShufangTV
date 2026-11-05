package io.github.spudhero.shufangtv.net;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import okhttp3.Call;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;

final class WebDavConnector implements LibraryConnector {
  private static final OkHttpClient HTTP =
      new OkHttpClient.Builder()
          .connectTimeout(10, TimeUnit.SECONDS)
          .readTimeout(20, TimeUnit.SECONDS)
          .writeTimeout(15, TimeUnit.SECONDS)
          .callTimeout(30, TimeUnit.SECONDS)
          .followRedirects(false)
          .followSslRedirects(false)
          .retryOnConnectionFailure(false)
          .build();
  private static final int MAX_LIST_BYTES = 4 * 1024 * 1024;
  private static final MediaType XML = MediaType.get("application/xml; charset=utf-8");
  private static final Pattern CONTENT_RANGE = Pattern.compile("bytes (\\d+)-(\\d+)/(\\d+)");
  private static final String PROPFIND =
      "<?xml version=\"1.0\" encoding=\"utf-8\"?><d:propfind"
          + " xmlns:d=\"DAV:\"><d:prop><d:resourcetype/><d:getcontentlength/></d:prop></d:propfind>";

  @Override
  public List<RemoteEntry> list(SourceConfig config, String path) throws IOException {
    WebDavEndpoint endpoint = new WebDavEndpoint(config);
    HttpUrl url = endpoint.url(path, true);
    Request request =
        endpoint
            .request(url)
            .header("Depth", "1")
            .header("Accept", "application/xml,text/xml")
            .method("PROPFIND", RequestBody.create(PROPFIND, XML))
            .build();
    Call call = HTTP.newCall(request);
    try (Response response = call.execute()) {
      if (response.code() != 207) {
        call.cancel();
        throw status(response.code());
      }
      return DavListing.parse(bounded(response.body(), MAX_LIST_BYTES), endpoint, url);
    }
  }

  @Override
  public RemoteFile open(SourceConfig config, String path) throws IOException {
    WebDavEndpoint endpoint = new WebDavEndpoint(config);
    HttpUrl url = endpoint.url(path, false);
    long length = -1;
    String etag = null, modified = null;
    Call head = HTTP.newCall(endpoint.request(url).head().build());
    try (Response response = head.execute()) {
      if (response.isSuccessful()) {
        length = longHeader(response.header("Content-Length"));
        etag = strongEtag(response.header("ETag"));
        modified = response.header("Last-Modified");
      } else if (response.code() != 405 && response.code() != 501) {
        head.cancel();
        throw status(response.code());
      }
    }
    if (length == 0) return new DavRemoteFile(endpoint, url, 0, etag, modified);
    // A one-byte range proves random access; a server returning 200 is cancelled before body
    // consumption.
    Range probe = range(endpoint, url, 0, 1, length, etag, modified, null);
    return new DavRemoteFile(
        endpoint,
        url,
        probe.total,
        probe.etag == null ? etag : probe.etag,
        probe.modified == null ? modified : probe.modified);
  }

  @Override
  public void download(
      SourceConfig config, String path, File target, NetworkLibrary.Progress listener)
      throws IOException {
    WebDavEndpoint endpoint = new WebDavEndpoint(config);
    Call call = HTTP.newCall(endpoint.request(endpoint.url(path, false)).get().build());
    try (Response response = call.execute()) {
      if (response.code() != 200) {
        call.cancel();
        throw status(response.code());
      }
      ResponseBody body = response.body();
      if (body == null) throw NetworkPaths.invalid("服务器没有返回书籍内容。");
      if (body.contentLength() > NetworkPaths.MAX_DOWNLOAD) {
        call.cancel();
        throw NetworkPaths.invalid("书籍超过 128 MiB，当前版本暂不支持离线下载。");
      }
      try (InputStream stream = body.byteStream()) {
        AtomicDownload.copy(stream, body.contentLength(), target, listener);
      } catch (IOException failure) {
        call.cancel();
        throw failure;
      }
    }
  }

  private static Range range(
      WebDavEndpoint endpoint,
      HttpUrl url,
      long position,
      int count,
      long expectedTotal,
      String etag,
      String modified,
      DavRemoteFile owner)
      throws IOException {
    long end = position + count - 1;
    Request.Builder request =
        endpoint.request(url).get().header("Range", "bytes=" + position + "-" + end);
    if (etag != null) request.header("If-Match", etag);
    else if (modified != null) request.header("If-Unmodified-Since", modified);
    Call call = HTTP.newCall(request.build());
    if (owner != null) owner.started(call);
    try (Response response = call.execute()) {
      if (response.code() != 206) {
        call.cancel();
        if (response.code() == 200 || response.code() == 416) {
          throw NetworkPaths.invalid("此 WebDAV 服务不支持按需读取，无法在线打开；可选择单独离线下载。");
        }
        throw status(response.code());
      }
      try {
        String encoding = response.header("Content-Encoding");
        if (encoding != null && !encoding.equalsIgnoreCase("identity"))
          throw NetworkPaths.invalid("服务器压缩了分段响应，无法可靠在线读取。");
        String header = response.header("Content-Range");
        Matcher matcher = CONTENT_RANGE.matcher(header == null ? "" : header);
        if (!matcher.matches()) throw NetworkPaths.invalid("服务器未返回有效的 Content-Range，无法在线读取。");
        long start = Long.parseLong(matcher.group(1)),
            actualEnd = Long.parseLong(matcher.group(2)),
            total = Long.parseLong(matcher.group(3));
        if (total <= 0
            || start != position
            || actualEnd < start
            || actualEnd >= total
            || actualEnd != Math.min(end, total - 1)
            || (expectedTotal >= 0 && total != expectedTotal)) {
          throw NetworkPaths.invalid("远程书籍已改变或服务器返回了错误的分段位置，请重新打开。");
        }
        String returnedEtag = strongEtag(response.header("ETag")),
            returnedModified = response.header("Last-Modified");
        if (etag != null && returnedEtag != null && !etag.equals(returnedEtag))
          throw NetworkPaths.invalid("远程书籍已改变，请重新打开。");
        ResponseBody body = response.body();
        int wanted = (int) (actualEnd - start + 1);
        if (body == null || (body.contentLength() >= 0 && body.contentLength() != wanted))
          throw NetworkPaths.invalid("服务器返回的分段长度不正确。");
        byte[] bytes = new byte[wanted];
        try (InputStream stream = body.byteStream()) {
          int done = 0;
          while (done < wanted) {
            NetworkRetry.checkInterrupted();
            int received = stream.read(bytes, done, wanted - done);
            if (received == -1) throw new EOFException("在线书籍分段尚未读取完整。");
            if (received > 0) done += received;
          }
          if (stream.read() != -1) throw NetworkPaths.invalid("服务器返回了超出请求范围的书籍内容。");
        }
        return new Range(total, bytes, returnedEtag, returnedModified);
      } catch (IOException | NumberFormatException failure) {
        call.cancel();
        if (failure instanceof IOException) throw (IOException) failure;
        throw new NetworkFailure("服务器返回的分段长度不正确。", false, failure);
      }
    } finally {
      if (owner != null) owner.finished(call);
    }
  }

  private static byte[] bounded(ResponseBody body, int limit) throws IOException {
    if (body == null) throw NetworkPaths.invalid("服务器未返回目录。");
    if (body.contentLength() > limit) throw NetworkPaths.invalid("目录过大，请缩小书库根目录后重试。");
    try (InputStream input = body.byteStream();
        ByteArrayOutputStream output = new ByteArrayOutputStream()) {
      byte[] bytes = new byte[8192];
      for (int count; (count = input.read(bytes)) != -1; ) {
        NetworkRetry.checkInterrupted();
        if (output.size() + count > limit) throw NetworkPaths.invalid("目录过大，请缩小书库根目录后重试。");
        output.write(bytes, 0, count);
      }
      return output.toByteArray();
    }
  }

  private static long longHeader(String value) {
    try {
      long number = Long.parseLong(value == null ? "" : value);
      return number >= 0 ? number : -1;
    } catch (NumberFormatException ignored) {
      return -1;
    }
  }

  private static String strongEtag(String value) {
    return value != null && value.startsWith("\"") && value.endsWith("\"") ? value : null;
  }

  private static NetworkFailure status(int code) {
    if (code == 401 || code == 403) return new NetworkFailure("账号、密码或访问权限不正确，请检查书库连接。", false);
    if (code >= 300 && code < 400) return new NetworkFailure("WebDAV 服务要求跳转，请填写最终服务地址后重试。", false);
    if (code == 404 || code == 410) return new NetworkFailure("服务器上找不到此目录或书籍。", false);
    if (code == 412) return new NetworkFailure("远程书籍已改变，请重新打开。", false);
    if (code == 408 || code == 429 || code == 502 || code == 503 || code == 504)
      return new NetworkFailure("WebDAV 服务暂时不可用，请稍后重试。", true);
    return new NetworkFailure("WebDAV 服务返回了不支持的响应（" + code + "）。", false);
  }

  private static final class Range {
    final long total;
    final byte[] bytes;
    final String etag, modified;

    Range(long total, byte[] bytes, String etag, String modified) {
      this.total = total;
      this.bytes = bytes;
      this.etag = etag;
      this.modified = modified;
    }
  }

  private static final class DavRemoteFile implements RemoteFile {
    final WebDavEndpoint endpoint;
    final HttpUrl url;
    final long size;
    final String etag, modified;
    private volatile boolean closed;
    private volatile Call active;

    DavRemoteFile(WebDavEndpoint endpoint, HttpUrl url, long size, String etag, String modified) {
      this.endpoint = endpoint;
      this.url = url;
      this.size = size;
      this.etag = etag;
      this.modified = modified;
    }

    @Override
    public long length() {
      return size;
    }

    @Override
    public synchronized int read(long position, byte[] buffer, int offset, int length)
        throws IOException {
      if (closed) throw NetworkPaths.invalid("在线书籍已经关闭。");
      int wanted = NetworkPaths.readLength(position, size, buffer, offset, length);
      if (wanted <= 0) return wanted;
      Range chunk =
          NetworkRetry.run(
              () -> {
                if (closed) throw NetworkPaths.invalid("在线书籍已经关闭。");
                return range(endpoint, url, position, wanted, size, etag, modified, this);
              });
      System.arraycopy(chunk.bytes, 0, buffer, offset, chunk.bytes.length);
      return chunk.bytes.length;
    }

    void started(Call call) throws IOException {
      active = call;
      if (closed) {
        call.cancel();
        throw NetworkPaths.invalid("在线书籍已经关闭。");
      }
    }

    void finished(Call call) {
      if (active == call) active = null;
    }

    @Override
    public void close() {
      closed = true;
      Call call = active;
      if (call != null) call.cancel();
    }
  }
}
