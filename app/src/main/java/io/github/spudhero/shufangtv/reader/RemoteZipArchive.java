package io.github.spudhero.shufangtv.reader;

import io.github.spudhero.shufangtv.core.BookArchive;
import io.github.spudhero.shufangtv.net.RemoteFile;
import java.io.*;
import java.nio.ByteBuffer;
import org.apache.commons.compress.archivers.zip.*;

/** Reads the ZIP directory and requested entries over ranges, not the full EPUB. */
public final class RemoteZipArchive implements BookArchive {
  private final ZipFile zip;

  public RemoteZipArchive(RemoteFile remote) throws IOException {
    RemoteChannel channel = new RemoteChannel(remote);
    try {
      checkDirectory(channel);
      // Deferring local headers is essential: inspecting a spine must not fetch every chapter.
      zip = ZipFile.builder().setSeekableByteChannel(channel).setIgnoreLocalFileHeader(true).get();
    } catch (Exception e) {
      channel.close();
      throw e instanceof IOException ? (IOException) e : new IOException("无法打开远程 EPUB", e);
    }
  }

  private static void checkDirectory(RemoteChannel channel) throws IOException {
    long size = channel.size();
    if (size < 22) throw new IOException("EPUB 文件不完整");
    int n = (int) Math.min(65557, size);
    ByteBuffer buffer = ByteBuffer.allocate(n);
    channel.position(size - n);
    while (buffer.hasRemaining()) {
      if (channel.read(buffer) < 0) throw new EOFException("EPUB 目录不完整");
    }
    byte[] b = buffer.array();
    for (int i = n - 22; i >= 0; i--)
      if (u32(b, i) == 0x06054b50L && i + 22 + u16(b, i + 20) == n) {
        long length = u32(b, i + 12), start = u32(b, i + 16);
        int entries = u16(b, i + 10);
        if (entries == 65535 || length == 0xffffffffL || start == 0xffffffffL)
          throw new IOException("此版在线阅读暂不支持 ZIP64 EPUB");
        if (length > 2 * 1024 * 1024 || entries > 16000 || start + length > size)
          throw new IOException("EPUB 目录过大或已损坏");
        return;
      }
    throw new IOException("找不到 EPUB 目录，请检查文件是否完整");
  }

  private static int u16(byte[] b, int i) {
    return (b[i] & 255) | ((b[i + 1] & 255) << 8);
  }

  private static long u32(byte[] b, int i) {
    return (u16(b, i) & 65535L) | ((long) u16(b, i + 2) << 16);
  }

  @Override
  public InputStream open(String name) throws IOException {
    ZipArchiveEntry entry = zip.getEntry(name);
    if (entry == null) throw new FileNotFoundException(name);
    if (entry.getSize() > 16 * 1024 * 1024) throw new IOException("单个 EPUB 资源超过 16 MiB");
    if (!zip.canReadEntryData(entry)) throw new IOException("此 EPUB 使用了不支持的压缩或加密方式");
    return zip.getInputStream(entry);
  }

  @Override
  public void close() throws IOException {
    zip.close();
  }
}
