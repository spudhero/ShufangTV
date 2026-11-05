package io.github.spudhero.shufangtv.reader;

import io.github.spudhero.shufangtv.net.RemoteFile;
import java.io.*;

public final class FileRemoteFile implements RemoteFile {
  private final RandomAccessFile file;
  private final long length;

  public FileRemoteFile(File source) throws IOException {
    file = new RandomAccessFile(source, "r");
    length = file.length();
  }

  @Override
  public long length() {
    return length;
  }

  @Override
  public synchronized int read(long p, byte[] b, int o, int n) throws IOException {
    file.seek(p);
    return file.read(b, o, n);
  }

  @Override
  public void close() throws IOException {
    file.close();
  }
}
