package io.github.spudhero.shufangtv.reader;

import static org.junit.Assert.*;

import io.github.spudhero.shufangtv.core.BookParser;
import io.github.spudhero.shufangtv.core.EpubBook;
import io.github.spudhero.shufangtv.net.RemoteFile;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Random;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.Test;

/** Exercises storage and seek invariants using synthetic books, without a device or a real NAS. */
public class OnlineReadingTest {
  private static final class MemoryFile implements RemoteFile {
    final byte[] bytes;
    long transferred;
    int reads;
    boolean closed;

    MemoryFile(byte[] bytes) {
      this.bytes = bytes;
    }

    @Override
    public long length() {
      return bytes.length;
    }

    @Override
    public int read(long p, byte[] b, int off, int count) throws IOException {
      if (closed) throw new IOException("closed");
      if (p >= bytes.length) return -1;
      int n = Math.min(count, bytes.length - (int) p);
      System.arraycopy(bytes, (int) p, b, off, n);
      reads++;
      transferred += n;
      return n;
    }

    @Override
    public void close() {
      closed = true;
    }
  }

  @Test
  public void seekingAcrossBlocksReusesCacheAndKeepsMemoryBounded() throws Exception {
    byte[] bytes = new byte[4 * 1024 * 1024];
    new Random(42).nextBytes(bytes);
    MemoryFile file = new MemoryFile(bytes);
    try (RemoteChannel channel = new RemoteChannel(file)) {
      channel.position(65530);
      ByteBuffer result = ByteBuffer.allocate(100);
      assertEquals(100, channel.read(result));
      assertArrayEquals(Arrays.copyOfRange(bytes, 65530, 65630), result.array());
      long first = file.transferred;
      channel.position(65530);
      channel.read(ByteBuffer.allocate(100));
      assertEquals(first, file.transferred);
      for (int i = 0; i < 40; i++) {
        channel.position(i * 65536L);
        channel.read(ByteBuffer.allocate(1));
      }
      assertTrue(channel.cachedBytes() <= 1024 * 1024);
      channel.position(bytes.length);
      assertEquals(-1, channel.read(ByteBuffer.allocate(1)));
    }
    assertTrue(file.closed);
  }

  @Test(expected = ClosedChannelException.class)
  public void closedChannelCannotRead() throws Exception {
    RemoteChannel channel = new RemoteChannel(new MemoryFile(new byte[16]));
    channel.close();
    channel.read(ByteBuffer.allocate(1));
  }

  @Test
  public void epubMetadataAndOneChapterDoNotTransferUnreadBookBody() throws Exception {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    try (ZipOutputStream zip = new ZipOutputStream(out)) {
      entry(zip, "mimetype", "application/epub+zip".getBytes(StandardCharsets.UTF_8));
      entry(
          zip,
          "META-INF/container.xml",
          ("<container"
               + " xmlns=\"urn:oasis:names:tc:opendocument:xmlns:container\"><rootfiles><rootfile"
               + " full-path=\"book.opf\"/></rootfiles></container>")
              .getBytes(StandardCharsets.UTF_8));
      entry(
          zip,
          "book.opf",
          ("<package xmlns=\"http://www.idpf.org/2007/opf\" version=\"3.0\"><metadata"
               + " xmlns:dc=\"http://purl.org/dc/elements/1.1/\"><dc:title>按需阅读</dc:title></metadata><manifest><item"
               + " id=\"one\" href=\"one.xhtml\" media-type=\"application/xhtml+xml\"/><item"
               + " id=\"later\" href=\"later.xhtml\""
               + " media-type=\"application/xhtml+xml\"/></manifest><spine><itemref"
               + " idref=\"one\"/><itemref idref=\"later\"/></spine></package>")
              .getBytes(StandardCharsets.UTF_8));
      entry(
          zip,
          "one.xhtml",
          "<html xmlns=\"http://www.w3.org/1999/xhtml\"><body><h1>第一章</h1><p>只读取当前章节。</p></body></html>"
              .getBytes(StandardCharsets.UTF_8));
      byte[] unread = new byte[8 * 1024 * 1024];
      new Random(17).nextBytes(unread);
      entry(zip, "later.xhtml", unread);
    }
    MemoryFile file = new MemoryFile(out.toByteArray());
    try (RemoteZipArchive archive = new RemoteZipArchive(file)) {
      BookParser parser = new BookParser();
      EpubBook book = parser.inspect(archive);
      assertEquals("按需阅读", book.title);
      assertEquals(2, book.spine.size());
      assertTrue(parser.readChapter(archive, book.spine.get(0)).text.contains("只读取当前章节"));
      assertTrue(
          "opening one chapter must not fetch the unread 8 MiB spine",
          file.transferred < 512 * 1024);
    }
    assertTrue(file.closed);
  }

  @Test
  public void truncatedZipIsRejectedAndRemoteHandleCloses() throws Exception {
    MemoryFile file = new MemoryFile(new byte[64]);
    try {
      new RemoteZipArchive(file);
      fail("invalid ZIP accepted");
    } catch (IOException expected) {
      assertTrue(file.closed);
    }
  }

  @Test
  public void utf8ChunkBoundariesPreserveEveryCharacterAndBackwardPosition() throws Exception {
    textRoundTrip(StandardCharsets.UTF_8, false);
  }

  @Test
  public void gb18030ChunkBoundariesPreserveEveryCharacterAndBackwardPosition() throws Exception {
    textRoundTrip(Charset.forName("GB18030"), false);
  }

  @Test
  public void utf16ChunkBoundariesPreserveEveryCharacterAndBackwardPosition() throws Exception {
    textRoundTrip(StandardCharsets.UTF_16LE, true);
  }

  private void textRoundTrip(Charset charset, boolean bom) throws Exception {
    String source = "天地玄黄，宇宙洪荒。日月盈昃，辰宿列张。\nabc123书房😀\n".repeat(4000);
    byte[] encoded = source.getBytes(charset);
    if (bom) {
      byte[] copy = new byte[encoded.length + 2];
      copy[0] = (byte) 0xff;
      copy[1] = (byte) 0xfe;
      System.arraycopy(encoded, 0, copy, 2, encoded.length);
      encoded = copy;
    }
    MemoryFile file = new MemoryFile(encoded);
    try (TextSegments text = new TextSegments(file)) {
      StringBuilder decoded = new StringBuilder();
      long position = 0, prior = -1;
      while (position < file.length()) {
        TextSegments.Segment current = text.read(position);
        decoded.append(current.text);
        if (prior >= 0) {
          TextSegments.Segment previous = text.before(current.start);
          assertEquals(prior, previous.start);
        }
        prior = current.start;
        assertTrue(current.end > position);
        position = current.end;
      }
      assertEquals(source, decoded.toString());
      assertTrue(file.reads > 1);
    }
    assertTrue(file.closed);
  }

  private static void entry(ZipOutputStream zip, String name, byte[] data) throws IOException {
    CRC32 crc = new CRC32();
    crc.update(data);
    ZipEntry entry = new ZipEntry(name);
    entry.setMethod(ZipEntry.STORED);
    entry.setSize(data.length);
    entry.setCompressedSize(data.length);
    entry.setCrc(crc.getValue());
    zip.putNextEntry(entry);
    zip.write(data);
    zip.closeEntry();
  }
}
