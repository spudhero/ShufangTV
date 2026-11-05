package io.github.spudhero.shufangtv.net;

import java.io.IOException;

/**
 * A read-only remote file. Reads request bounded ranges and never download the whole file to disk.
 */
public interface RemoteFile extends AutoCloseable {
  long length();

  /**
   * Returns bytes read, or -1 at EOF. Implementations may return less than the requested length.
   */
  int read(long position, byte[] buffer, int offset, int length) throws IOException;

  @Override
  void close() throws IOException;
}
