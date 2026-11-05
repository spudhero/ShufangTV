package io.github.spudhero.shufangtv.net;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import okhttp3.Credentials;
import okhttp3.HttpUrl;
import okhttp3.Request;

/** URL and credential confinement: returned DAV hrefs never become authority-bearing requests. */
final class WebDavEndpoint {
  final HttpUrl root;
  private final String authorization;

  WebDavEndpoint(SourceConfig config) throws IOException {
    String address =
        config.host.contains("://")
            ? config.host
            : (config.https ? "https://" : "http://") + config.host;
    try {
      URI raw = new URI(address);
      if (raw.getRawUserInfo() != null
          || raw.getRawQuery() != null
          || raw.getRawFragment() != null) {
        throw NetworkPaths.invalid("WebDAV 地址不能包含账号、查询参数或片段，请单独填写账号。");
      }
      checkRawPath(raw.getRawPath());
      HttpUrl url = HttpUrl.parse(address);
      if (url == null || (!url.scheme().equals("http") && !url.scheme().equals("https")))
        throw NetworkPaths.invalid("请填写有效的 HTTP 或 HTTPS WebDAV 地址。");
      HttpUrl.Builder builder = url.newBuilder();
      if (config.port > 0) builder.port(config.port);
      List<String> segments = new ArrayList<>(url.pathSegments());
      while (!segments.isEmpty() && segments.get(segments.size() - 1).isEmpty())
        segments.remove(segments.size() - 1);
      builder.encodedPath("/");
      for (String segment : segments) {
        if (!NetworkPaths.segment(segment)) throw NetworkPaths.invalid("WebDAV 根目录不正确。");
        builder.addPathSegment(segment);
      }
      if (!config.root.isEmpty())
        for (String segment : config.root.split("/")) builder.addPathSegment(segment);
      builder.addPathSegment("");
      root = builder.build();
      authorization =
          config.username.isEmpty() && config.password.isEmpty()
              ? null
              : Credentials.basic(config.username, config.password, StandardCharsets.UTF_8);
    } catch (URISyntaxException | IllegalArgumentException failure) {
      throw new NetworkFailure("请填写有效的 WebDAV 服务器地址。", false, failure);
    }
  }

  HttpUrl url(String path, boolean directory) throws IOException {
    NetworkPaths.relative(path);
    HttpUrl.Builder builder = root.newBuilder();
    if (!path.isEmpty()) for (String segment : path.split("/")) builder.addPathSegment(segment);
    if (directory && !path.isEmpty()) builder.addPathSegment("");
    return builder.build();
  }

  Request.Builder request(HttpUrl url) throws IOException {
    if (!sameOrigin(root, url)) throw NetworkPaths.invalid("连接地址超出了配置的服务器。");
    Request.Builder request =
        new Request.Builder()
            .url(url)
            .header("Accept-Encoding", "identity")
            .header("User-Agent", "ShufangTV/0.1");
    if (authorization != null) request.header("Authorization", authorization);
    return request;
  }

  /** Returns one direct child, or null for self, traversal, off-origin or deeper descendants. */
  String childPath(HttpUrl directory, String href) {
    try {
      if (href == null || href.length() > 8192) return null;
      URI reference = new URI(href.trim());
      if (reference.getRawUserInfo() != null
          || reference.getRawQuery() != null
          || reference.getRawFragment() != null) return null;
      checkRawPath(reference.getRawPath());
      HttpUrl resolved = directory.resolve(href.trim());
      if (resolved == null
          || !sameOrigin(root, resolved)
          || !resolved.username().isEmpty()
          || !resolved.password().isEmpty()) return null;
      List<String> parent = segments(directory), child = segments(resolved), base = segments(root);
      if (child.size() != parent.size() + 1 || !prefix(child, parent) || !prefix(child, base))
        return null;
      List<String> relative = child.subList(base.size(), child.size());
      for (String segment : relative) if (!NetworkPaths.segment(segment)) return null;
      return String.join("/", relative);
    } catch (IOException | URISyntaxException | IllegalArgumentException ignored) {
      return null;
    }
  }

  private static boolean prefix(List<String> path, List<String> prefix) {
    return path.size() >= prefix.size() && path.subList(0, prefix.size()).equals(prefix);
  }

  private static List<String> segments(HttpUrl url) {
    List<String> segments = new ArrayList<>(url.pathSegments());
    if (!segments.isEmpty() && segments.get(segments.size() - 1).isEmpty())
      segments.remove(segments.size() - 1);
    return segments;
  }

  static boolean sameOrigin(HttpUrl left, HttpUrl right) {
    return left.scheme().equals(right.scheme())
        && left.host().equals(right.host())
        && left.port() == right.port();
  }

  private static void checkRawPath(String path) throws IOException {
    if (path == null || path.isEmpty() || path.equals("/")) return;
    for (String part : path.split("/", -1)) {
      if (part.isEmpty()) continue;
      String decoded = decode(part);
      if (!NetworkPaths.segment(decoded)) throw NetworkPaths.invalid("服务器返回了无效的书籍路径。");
    }
  }

  private static String decode(String raw) throws IOException {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream();
    for (int i = 0; i < raw.length(); ) {
      if (raw.charAt(i) == '%') {
        if (i + 2 >= raw.length()) throw NetworkPaths.invalid("书籍路径编码不正确。");
        int hi = Character.digit(raw.charAt(i + 1), 16),
            lo = Character.digit(raw.charAt(i + 2), 16);
        if (hi < 0 || lo < 0) throw NetworkPaths.invalid("书籍路径编码不正确。");
        bytes.write((hi << 4) | lo);
        i += 3;
      } else {
        int codePoint = raw.codePointAt(i);
        byte[] encoded = new String(Character.toChars(codePoint)).getBytes(StandardCharsets.UTF_8);
        bytes.write(encoded, 0, encoded.length);
        i += Character.charCount(codePoint);
      }
    }
    try {
      return StandardCharsets.UTF_8
          .newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(ByteBuffer.wrap(bytes.toByteArray()))
          .toString();
    } catch (CharacterCodingException failure) {
      throw new NetworkFailure("书籍路径不是有效的 UTF-8 编码。", false, failure);
    }
  }
}
