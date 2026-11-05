package io.github.spudhero.shufangtv.net;

import java.io.EOFException;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;

final class AtomicDownload {
  private AtomicDownload() {}

  static void copy(InputStream input, long total, File target, NetworkLibrary.Progress listener)
      throws IOException {
    if (total > NetworkPaths.MAX_DOWNLOAD) throw NetworkPaths.invalid("书籍超过 128 MiB，当前版本暂不支持离线下载。");
    File parent = target.getAbsoluteFile().getParentFile();
    if (parent == null || (!parent.isDirectory() && !parent.mkdirs()))
      throw NetworkPaths.invalid("无法创建书籍保存目录。");
    File partial = File.createTempFile("shufang-", ".part", parent);
    try {
      long received = 0;
      notifyProgress(listener, 0, total);
      try (FileOutputStream output = new FileOutputStream(partial)) {
        byte[] buffer = new byte[64 * 1024];
        for (int count; (count = input.read(buffer)) != -1; ) {
          NetworkRetry.checkInterrupted();
          if (count == 0) continue;
          received += count;
          if (received > NetworkPaths.MAX_DOWNLOAD)
            throw NetworkPaths.invalid("书籍超过 128 MiB，已停止离线下载。");
          if (total >= 0 && received > total) throw NetworkPaths.invalid("服务器返回的书籍长度与声明不符。");
          output.write(buffer, 0, count);
          notifyProgress(listener, received, total);
        }
        if (total >= 0 && received != total) throw new EOFException("书籍尚未下载完整。");
        output.getFD().sync();
      }
      try {
        Files.move(
            partial.toPath(),
            target.toPath(),
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING);
      } catch (AtomicMoveNotSupportedException ignored) {
        Files.move(partial.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
      }
    } finally {
      Files.deleteIfExists(partial.toPath());
    }
  }

  private static void notifyProgress(NetworkLibrary.Progress listener, long received, long total)
      throws IOException {
    if (listener == null) return;
    try {
      listener.onProgress(received, total);
    } catch (IOException cancelled) {
      throw new NetworkFailure("已取消离线下载。", false, cancelled);
    }
  }
}
