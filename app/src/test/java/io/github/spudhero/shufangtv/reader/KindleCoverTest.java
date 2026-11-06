package io.github.spudhero.shufangtv.reader;

import static org.junit.Assert.*;

import io.github.spudhero.shufangtv.net.RemoteFile;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import org.junit.Test;

public class KindleCoverTest {
  @Test
  public void extractsOnlyCoverHeaderAndTableDespiteShortRangeReads() throws Exception {
    File file = fixture(201);
    byte[] cover = KindleCover.read(file);
    assertArrayEquals(new byte[] {1, 2, 3, 4}, cover);
    assertTrue(file.transferred < 4096);
    assertFalse(file.closed);
  }

  @Test
  public void acceptsThumbnailMetadataAndFirstResourceFallback() throws Exception {
    assertArrayEquals(new byte[] {1, 2, 3, 4}, KindleCover.read(fixture(202)));
    assertArrayEquals(new byte[] {1, 2, 3, 4}, KindleCover.read(fixture(999)));
  }

  @Test
  public void rejectsDrmAndInvalidRecordOrExthBoundsBeforeReadingPayload() throws Exception {
    for (int mode = 0; mode < 4; mode++) {
      File file = fixture(201);
      ByteBuffer data = ByteBuffer.wrap(file.bytes);
      if (mode == 0) data.putShort(104 + 12, (short) 2);
      if (mode == 1) data.putInt(78 + 16, Integer.MAX_VALUE);
      if (mode == 2) data.putInt(104 + 152, 4);
      if (mode == 3) data.putInt(104 + 148, Integer.MAX_VALUE);
      assertThrows(IOException.class, () -> KindleCover.read(file));
      assertTrue(file.transferred < 4096);
    }
  }

  private static File fixture(int tag) {
    int start = 104, text = 264, image = text + 4 * 1024 * 1024;
    byte[] bytes = new byte[image + 4];
    ByteBuffer out = ByteBuffer.wrap(bytes);
    out.position(60);
    out.put("BOOKMOBI".getBytes(StandardCharsets.US_ASCII));
    out.position(76);
    out.putShort((short) 3);
    out.putInt(start).putInt(1).putInt(text).putInt(2).putInt(image).putInt(3);
    out.position(start + 16);
    out.put("MOBI".getBytes(StandardCharsets.US_ASCII)).putInt(116);
    out.putInt(start + 108, 2);
    out.putInt(start + 128, 0x40);
    out.position(start + 132);
    out.put("EXTH".getBytes(StandardCharsets.US_ASCII));
    out.putInt(24).putInt(1).putInt(tag).putInt(12).putInt(0);
    out.position(image);
    out.put(new byte[] {1, 2, 3, 4});
    return new File(bytes, text, image);
  }

  private static final class File implements RemoteFile {
    final byte[] bytes;
    final int text, image;
    int transferred;
    boolean closed;

    File(byte[] bytes, int text, int image) {
      this.bytes = bytes;
      this.text = text;
      this.image = image;
    }

    public long length() {
      return bytes.length;
    }

    public int read(long at, byte[] out, int offset, int count) throws IOException {
      if (at >= text && at < image) throw new AssertionError("Cover read touched the book body");
      int n = (int) Math.min(Math.min(17, count), bytes.length - at);
      if (n <= 0) return -1;
      System.arraycopy(bytes, (int) at, out, offset, n);
      transferred += n;
      return n;
    }

    public void close() {
      closed = true;
    }
  }
}
