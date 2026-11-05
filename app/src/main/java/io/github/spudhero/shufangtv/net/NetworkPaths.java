package io.github.spudhero.shufangtv.net;

import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

final class NetworkPaths {
  static final int MAX_READ = 256 * 1024;
  static final long MAX_DOWNLOAD = 128L * 1024 * 1024;
  static final int MAX_ENTRIES = 20_000;

  private NetworkPaths() {}

  static SourceConfig snapshot(SourceConfig source) throws IOException {
    if (source == null || source.type == null) throw invalid("请选择书库连接方式。");
    SourceConfig config = new SourceConfig();
    config.id = value(source.id);
    config.name = value(source.name);
    config.host = value(source.host).trim();
    config.share = value(source.share).trim();
    config.root = root(source.root);
    config.username = value(source.username);
    config.password = value(source.password);
    config.domain = value(source.domain);
    config.port = source.port;
    config.https = source.https;
    config.type = source.type;
    if (config.host.isEmpty() || config.host.length() > 4096) throw invalid("请填写有效的服务器地址。");
    if (config.port < 0 || config.port > 65535) throw invalid("服务器端口必须在 1 至 65535 之间。");
    if (hasControl(config.host)
        || hasControl(config.username)
        || hasControl(config.password)
        || hasControl(config.domain)) {
      throw invalid("连接信息含有无效字符。");
    }
    if (config.type == SourceConfig.Type.SMB) {
      try {
        URI uri = new URI(config.host.contains("://") ? config.host : "smb://" + config.host);
        if (!"smb".equalsIgnoreCase(uri.getScheme())
            || uri.getHost() == null
            || uri.getRawUserInfo() != null
            || uri.getRawQuery() != null
            || uri.getRawFragment() != null
            || (uri.getRawPath() != null
                && !uri.getRawPath().isEmpty()
                && !"/".equals(uri.getRawPath()))) {
          throw invalid("SMB 地址只填写主机名或 IP，共享名称请单独填写。");
        }
        if (config.port == 0) config.port = uri.getPort() == -1 ? 445 : uri.getPort();
        config.host = uri.getHost();
        if (config.host.startsWith("[") && config.host.endsWith("]"))
          config.host = config.host.substring(1, config.host.length() - 1);
      } catch (URISyntaxException failure) {
        throw invalid("请填写有效的 SMB 服务器地址。");
      }
      if (config.port < 1 || config.share.isEmpty() || !segment(config.share))
        throw invalid("请填写有效的 SMB 共享名称。");
      if (config.username.isEmpty() && !config.password.isEmpty())
        throw invalid("使用 SMB 密码时，请同时填写用户名。");
    } else if (config.username.contains(":")) {
      throw invalid("WebDAV 用户名不能包含冒号。");
    }
    return config;
  }

  static String relative(String input) throws IOException {
    String path = value(input);
    if (path.isEmpty()) return "";
    if (path.startsWith("/") || path.endsWith("/") || path.length() > 8192)
      throw invalid("书籍路径不正确。");
    for (String part : path.split("/", -1)) if (!segment(part)) throw invalid("书籍路径不正确。");
    return path;
  }

  static String root(String input) throws IOException {
    String path = value(input).trim();
    while (path.startsWith("/")) path = path.substring(1);
    while (path.endsWith("/")) path = path.substring(0, path.length() - 1);
    return relative(path);
  }

  static boolean segment(String name) {
    if (name.isEmpty()
        || name.equals(".")
        || name.equals("..")
        || name.indexOf('/') >= 0
        || name.indexOf('\\') >= 0
        || hasControl(name)) return false;
    String lower = name.toLowerCase(Locale.ROOT);
    // Names are decoded path segments, never caller-supplied encoded paths.
    return !lower.contains("%2f")
        && !lower.contains("%5c")
        && !lower.equals("%2e")
        && !lower.equals("%2e%2e")
        && !lower.equals(".%2e")
        && !lower.equals("%2e.");
  }

  static String join(String parent, String child) {
    return parent.isEmpty() ? child : child.isEmpty() ? parent : parent + "/" + child;
  }

  static int readLength(long position, long total, byte[] buffer, int offset, int length)
      throws IOException {
    if (buffer == null) throw new NullPointerException("buffer");
    if (offset < 0 || length < 0 || offset > buffer.length - length)
      throw new IndexOutOfBoundsException();
    if (position < 0) throw invalid("读取位置不能为负数。");
    if (length == 0) return 0;
    if (position >= total) return -1;
    return (int) Math.min(Math.min((long) length, MAX_READ), total - position);
  }

  static void sort(List<RemoteEntry> entries) {
    entries.sort(
        Comparator.comparing((RemoteEntry e) -> !e.directory)
            .thenComparing(e -> e.name, String.CASE_INSENSITIVE_ORDER));
  }

  static NetworkFailure invalid(String message) {
    return new NetworkFailure(message, false);
  }

  static String value(String value) {
    return value == null ? "" : value;
  }

  static boolean hasControl(String value) {
    for (int i = 0; i < value.length(); i++)
      if (value.charAt(i) < 32 || value.charAt(i) == 127) return true;
    return false;
  }
}
