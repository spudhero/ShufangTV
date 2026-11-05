package io.github.spudhero.shufangtv.reader;

import io.github.spudhero.shufangtv.net.RemoteFile;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.util.*;

/** Seekable, read-only, memory-only cache. Never creates a local book file. */
public final class RemoteChannel implements SeekableByteChannel {
  public static final int BLOCK_BYTES = 64 * 1024, MAX_BLOCKS = 16;
  private final RemoteFile file;
  private final LinkedHashMap<Long, byte[]> blocks = new LinkedHashMap<>(MAX_BLOCKS, 0.75f, true);
  private long position;
  private boolean open = true;

  public RemoteChannel(RemoteFile file) {
    this.file = file;
  }

  @Override
  public synchronized int read(ByteBuffer target) throws IOException {
    ensureOpen();
    if (!target.hasRemaining()) return 0;
    if (position >= file.length()) return -1;
    int copied = 0;
    while (target.hasRemaining() && position < file.length()) {
      long block = position / BLOCK_BYTES;
      byte[] bytes = blocks.get(block);
      if (bytes == null) {
        int count = (int) Math.min(BLOCK_BYTES, file.length() - block * BLOCK_BYTES);
        bytes = new byte[count];
        int got = 0;
        while (got < count) {
          int n = file.read(block * BLOCK_BYTES + got, bytes, got, count - got);
          if (n <= 0) throw new IOException("远程文件读取不完整，请重试");
          got += n;
        }
        blocks.put(block, bytes);
        if (blocks.size() > MAX_BLOCKS) blocks.remove(blocks.keySet().iterator().next());
      }
      int at = (int) (position % BLOCK_BYTES), n = Math.min(target.remaining(), bytes.length - at);
      target.put(bytes, at, n);
      position += n;
      copied += n;
    }
    return copied;
  }

  @Override
  public synchronized long position() throws IOException {
    ensureOpen();
    return position;
  }

  @Override
  public synchronized SeekableByteChannel position(long value) throws IOException {
    ensureOpen();
    if (value < 0) throw new IllegalArgumentException("负数位置");
    position = value;
    return this;
  }

  @Override
  public long size() throws IOException {
    ensureOpen();
    return file.length();
  }

  @Override
  public int write(ByteBuffer ignored) throws IOException {
    throw new NonWritableChannelException();
  }

  @Override
  public SeekableByteChannel truncate(long ignored) throws IOException {
    throw new NonWritableChannelException();
  }

  @Override
  public boolean isOpen() {
    return open;
  }

  @Override
  public synchronized void close() throws IOException {
    if (open) {
      open = false;
      blocks.clear();
      file.close();
    }
  }

  private void ensureOpen() throws ClosedChannelException {
    if (!open) throw new ClosedChannelException();
  }

  public synchronized int cachedBytes() {
    int sum = 0;
    for (byte[] b : blocks.values()) sum += b.length;
    return sum;
  }
}
