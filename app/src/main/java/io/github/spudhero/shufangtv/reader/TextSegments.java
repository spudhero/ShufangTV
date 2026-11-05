package io.github.spudhero.shufangtv.reader;

import io.github.spudhero.shufangtv.net.RemoteFile;
import java.io.*;
import java.nio.*;
import java.nio.charset.*;

/** Online TXT uses bounded byte windows and stores the exact segment start. */
public final class TextSegments implements AutoCloseable {
  public static final int WINDOW = 48 * 1024;
  private final RemoteFile file;
  private final Charset charset;
  private final int bom;
  private final java.util.NavigableSet<Long> starts = new java.util.TreeSet<>();

  public TextSegments(RemoteFile file) throws IOException {
    this.file = file;
    byte[] first = new byte[(int) Math.min(4096, file.length())];
    readFully(0, first);
    if (first.length >= 3
        && first[0] == (byte) 0xef
        && first[1] == (byte) 0xbb
        && first[2] == (byte) 0xbf) {
      charset = StandardCharsets.UTF_8;
      bom = 3;
    } else if (first.length >= 2 && first[0] == (byte) 0xff && first[1] == (byte) 0xfe) {
      charset = StandardCharsets.UTF_16LE;
      bom = 2;
    } else if (first.length >= 2 && first[0] == (byte) 0xfe && first[1] == (byte) 0xff) {
      charset = StandardCharsets.UTF_16BE;
      bom = 2;
    } else {
      Charset chosen = StandardCharsets.UTF_8;
      CharsetDecoder d = chosen.newDecoder().onMalformedInput(CodingErrorAction.REPORT);
      CoderResult r =
          d.decode(
              ByteBuffer.wrap(first),
              CharBuffer.allocate(first.length + 4),
              file.length() <= first.length);
      if (r.isError()) chosen = Charset.forName("GB18030");
      charset = chosen;
      bom = 0;
    }
  }

  public Segment read(long start) throws IOException {
    start = Math.max(bom, Math.min(start, Math.max(bom, file.length() - 1)));
    if ((charset.equals(StandardCharsets.UTF_16LE) || charset.equals(StandardCharsets.UTF_16BE))
        && ((start - bom) & 1) != 0) start--;
    byte[] b = new byte[(int) Math.min(WINDOW, file.length() - start)];
    readFully(start, b);
    ByteBuffer input = ByteBuffer.wrap(b);
    CharBuffer out = CharBuffer.allocate(b.length + 2);
    CharsetDecoder decoder =
        charset
            .newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT);
    CoderResult result = decoder.decode(input, out, start + b.length >= file.length());
    if (result.isError()) throw new IOException("TXT 编码不完整或不受支持，请改用 UTF-8、UTF-16 或 GB18030 文件");
    out.flip();
    String text = out.toString();
    if (text.isEmpty() && b.length > 0) throw new IOException("无法读取这一段文字");
    starts.add(start);
    while (starts.size() > 64) starts.pollFirst();
    return new Segment(start, start + input.position(), text);
  }

  /** Backward navigation probes a few byte alignments, without reading the preceding file. */
  public Segment before(long current) throws IOException {
    Long known = starts.lower(current);
    if (known != null) return read(known);
    long candidate = Math.max(bom, current - WINDOW);
    IOException failure = null;
    for (int i = 0; i < 4 && candidate + i < current; i++)
      try {
        return read(candidate + i);
      } catch (IOException e) {
        failure = e;
      }
    throw failure == null ? new IOException("已经到开头了") : failure;
  }

  private void readFully(long start, byte[] b) throws IOException {
    int got = 0;
    while (got < b.length) {
      int n = file.read(start + got, b, got, b.length - got);
      if (n <= 0) throw new EOFException("TXT 读取不完整");
      got += n;
    }
  }

  public long length() {
    return file.length();
  }

  @Override
  public void close() throws IOException {
    file.close();
  }

  public static final class Segment {
    public final long start, end;
    public final String text;

    Segment(long start, long end, String text) {
      this.start = start;
      this.end = end;
      this.text = text;
    }
  }
}
