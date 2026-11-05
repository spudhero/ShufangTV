package io.github.spudhero.shufangtv.net;

import java.io.File;
import java.io.IOException;
import java.util.List;

/** Read-only access to SMB and standard WebDAV sources (including CD2's WebDAV endpoint). */
public final class NetworkLibrary {
  /** Throw IOException from the callback to cancel; no partial target is installed. */
  public interface Progress {
    void onProgress(long received, long total) throws IOException;
  }

  public RemoteFile open(SourceConfig config, String relativePath) throws IOException {
    SourceConfig snapshot = NetworkPaths.snapshot(config);
    String path = NetworkPaths.relative(relativePath);
    if (path.isEmpty()) throw new NetworkFailure("请选择要打开的书籍。", false);
    return NetworkRetry.run(() -> connector(snapshot).open(snapshot, path));
  }

  public List<RemoteEntry> list(SourceConfig config, String relativePath) throws IOException {
    SourceConfig snapshot = NetworkPaths.snapshot(config);
    String path = NetworkPaths.relative(relativePath);
    return NetworkRetry.run(() -> connector(snapshot).list(snapshot, path));
  }

  public void download(SourceConfig config, String relativePath, File target, Progress progress)
      throws IOException {
    SourceConfig snapshot = NetworkPaths.snapshot(config);
    String path = NetworkPaths.relative(relativePath);
    if (path.isEmpty()) throw new NetworkFailure("请选择要导入的书籍。", false);
    if (target == null) throw new NetworkFailure("未指定书籍保存位置。", false);
    NetworkRetry.run(
        () -> {
          connector(snapshot).download(snapshot, path, target, progress);
          return null;
        });
  }

  private static LibraryConnector connector(SourceConfig config) {
    return config.type == SourceConfig.Type.SMB ? new SmbConnector() : new WebDavConnector();
  }
}
