package io.github.spudhero.shufangtv.reader;

import io.github.spudhero.shufangtv.core.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Virtual EPUB entries reconstructed from the selected MOBI/KF8; no conversion file is saved. */
public final class MobiArchive implements BookArchive {
  private long handle;
  private final Set<String> entries;
  private final Map<String, byte[]> sections = new HashMap<>();

  public MobiArchive(int fd, long length) throws IOException {
    handle = NativeMobi.open(fd, length);
    try {
      entries = new LinkedHashSet<>(Arrays.asList(NativeMobi.paths(handle)));
    } catch (Exception e) {
      close();
      throw e;
    }
  }

  public Set<String> entries() {
    return Collections.unmodifiableSet(entries);
  }

  public EpubBook inspect(BookParser parser) throws IOException {
    EpubBook book = parser.inspect(this);
    List<KindleChapters.Section> split = KindleChapters.split(this, book, entries);
    if (split.isEmpty()) return book;
    List<EpubBook.SpineItem> spine = new ArrayList<>();
    for (KindleChapters.Section section : split) {
      sections.put(section.path, section.html);
      spine.add(new EpubBook.SpineItem(section.title, section.path));
    }
    return new EpubBook(book.title, book.author, spine, book.coverPath);
  }

  @Override
  public InputStream open(String name) throws IOException {
    if (handle == 0) throw new IOException("Kindle 阅读已关闭");
    if (sections.containsKey(name)) return new ByteArrayInputStream(sections.get(name));
    if (name.equals("META-INF/container.xml"))
      return new ByteArrayInputStream(
          "<container><rootfiles><rootfile full-path='content.opf' media-type='application/oebps-package+xml'/></rootfiles></container>"
              .getBytes(StandardCharsets.UTF_8));
    if (!entries.contains(name)) throw new FileNotFoundException(name);
    byte[] data = NativeMobi.read(handle, name);
    if (data == null) throw new FileNotFoundException(name);
    return new ByteArrayInputStream(data);
  }

  @Override
  public void close() {
    if (handle != 0) {
      NativeMobi.close(handle);
      handle = 0;
    }
    sections.clear();
  }
}
