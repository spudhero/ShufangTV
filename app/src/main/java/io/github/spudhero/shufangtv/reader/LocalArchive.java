package io.github.spudhero.shufangtv.reader;

import io.github.spudhero.shufangtv.core.BookArchive;
import java.io.*;
import java.util.zip.*;

public final class LocalArchive implements BookArchive {
  private final ZipFile zip;

  public LocalArchive(File file) throws IOException {
    zip = new ZipFile(file);
  }

  @Override
  public InputStream open(String name) throws IOException {
    ZipEntry e = zip.getEntry(name);
    if (e == null) throw new FileNotFoundException(name);
    if (e.getSize() > 8 * 1024 * 1024) throw new IOException("单个 EPUB 章节或资源超过 8 MiB");
    return zip.getInputStream(e);
  }

  @Override
  public void close() throws IOException {
    zip.close();
  }
}
