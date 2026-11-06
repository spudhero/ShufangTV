package io.github.spudhero.shufangtv.reader;

import io.github.spudhero.shufangtv.net.RemoteFile;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;

/** Reads PalmDB/MOBI cover records by range; never decompresses or scans the book text. */
public final class KindleCover {
  private static final int MAX_HEADER = 2 * 1024 * 1024;
  private static final int MAX_IMAGE = 16 * 1024 * 1024;

  public static byte[] read(RemoteFile file) throws IOException {
    byte[] pdb = read(file, 0, 78);
    String type = new String(pdb, 60, 8, StandardCharsets.US_ASCII);
    if (!type.equals("BOOKMOBI")) return null;
    int count = Short.toUnsignedInt(ByteBuffer.wrap(pdb).getShort(76));
    if (count < 2) throw new IOException("Kindle 记录表无效");
    byte[] table = read(file, 78, count * 8);
    long[] offsets = new long[count + 1];
    for (int i = 0; i < count; i++) {
      offsets[i] = u32(table, i * 8);
      if (offsets[i] < 78L + count * 8
          || offsets[i] >= file.length()
          || (i > 0 && offsets[i] <= offsets[i - 1])) throw new IOException("Kindle 记录偏移无效");
    }
    offsets[count] = file.length();
    Header header = header(file, offsets, 0);
    if (header == null) return null;
    long imageBase = header.images;
    // In hybrid KF7/KF8 books, resources are shared from the first MOBI header.
    if (header.cover < 0 && header.boundary >= 0 && header.boundary + 1 < count) {
      Header kf8 = header(file, offsets, (int) header.boundary + 1);
      if (kf8 != null) {
        if (imageBase == 0xffffffffL) imageBase = kf8.images;
        header = kf8;
      }
    }
    if (imageBase == 0xffffffffL) return null;
    long index = imageBase + (header.cover >= 0 ? header.cover : 0);
    if (index < 0 || index >= count) throw new IOException("Kindle 封面记录无效");
    long size = offsets[(int) index + 1] - offsets[(int) index];
    if (size <= 0 || size > MAX_IMAGE) throw new IOException("Kindle 封面过大或无效");
    return read(file, offsets[(int) index], (int) size);
  }

  private static Header header(RemoteFile file, long[] offsets, int index) throws IOException {
    long length = offsets[index + 1] - offsets[index];
    if (length < 132 || length > MAX_HEADER) return null;
    byte[] data = read(file, offsets[index], (int) length);
    if (!new String(data, 16, 4, StandardCharsets.US_ASCII).equals("MOBI")) return null;
    if (ByteBuffer.wrap(data).getShort(12) != 0) throw new IOException("Kindle 书籍受加密保护");
    long headerLength = u32(data, 20);
    if (headerLength < 116 || headerLength > length - 16) throw new IOException("Kindle 头无效");
    Header result = new Header();
    result.images = u32(data, 108);
    int at = (int) headerLength + 16;
    if ((u32(data, 128) & 0x40) == 0 || at > data.length - 12) return result;
    if (!new String(data, at, 4, StandardCharsets.US_ASCII).equals("EXTH")) return result;
    long exthLength = u32(data, at + 4), count = u32(data, at + 8);
    if (exthLength < 12 || exthLength > data.length - at || count > (exthLength - 12) / 8)
      throw new IOException("Kindle 封面元数据无效");
    int end = at + (int) exthLength;
    at += 12;
    long thumbnail = -1;
    for (int i = 0; i < count; i++) {
      if (at > end - 8) throw new IOException("Kindle 封面元数据不完整");
      long tag = u32(data, at), size = u32(data, at + 4);
      if (size < 8 || size > end - at) throw new IOException("Kindle 封面元数据长度无效");
      if (size == 12) {
        long value = u32(data, at + 8);
        if (value != 0xffffffffL) {
          if (tag == 201) result.cover = value;
          if (tag == 202) thumbnail = value;
          if (tag == 121) result.boundary = value;
        }
      }
      at += (int) size;
    }
    if (result.cover < 0) result.cover = thumbnail;
    return result;
  }

  private static byte[] read(RemoteFile file, long offset, int count) throws IOException {
    if (offset < 0 || count < 0 || offset > file.length() - count)
      throw new IOException("Kindle 文件不完整");
    byte[] result = new byte[count];
    int got = 0;
    while (got < count) {
      if (Thread.currentThread().isInterrupted()) throw new IOException("封面读取已取消");
      int n = file.read(offset + got, result, got, Math.min(count - got, 256 * 1024));
      if (n <= 0) throw new IOException("Kindle 文件读取中断");
      got += n;
    }
    return result;
  }

  private static long u32(byte[] data, int at) {
    return Integer.toUnsignedLong(ByteBuffer.wrap(data).getInt(at));
  }

  private static final class Header {
    long images, cover = -1, boundary = -1;
  }

  private KindleCover() {}
}
