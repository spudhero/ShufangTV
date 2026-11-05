package io.github.spudhero.shufangtv.core;

import java.io.IOException;
import java.io.InputStream;

/** An EPUB entry source backed by a local ZIP or a range-capable remote ZIP. */
public interface BookArchive extends AutoCloseable {
  /**
   * Opens one case-sensitive, archive-root-relative entry. The caller closes the stream. Missing
   * entries must throw {@link java.io.FileNotFoundException}; other I/O failures must remain
   * distinguishable so an unavailable network is not mistaken for missing metadata.
   */
  InputStream open(String entryName) throws IOException;

  @Override
  void close() throws IOException;
}
