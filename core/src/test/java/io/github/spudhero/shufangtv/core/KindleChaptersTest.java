package io.github.spudhero.shufangtv.core;

import static org.junit.Assert.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.junit.Test;

public class KindleChaptersTest {
  @Test
  public void legacyNcxRecoversChaptersAndRetainsAllTextAndImageResources() throws Exception {
    Map<String, String> data =
        Map.of(
            "part00000.html",
                "<html><body><p>前言😀</p><a id='0000000123'></a><h1>第一章</h1><p>甲</p><img"
                    + " src='resource00001.jpg'/><a"
                    + " id='0000000456'></a><h1>第二章</h1><p>乙</p></body></html>",
            "resource00002.ncx",
                "<ncx><navMap><navPoint><navLabel><text>第一章</text></navLabel><content"
                    + " src='part00000.html#0000000123'/></navPoint><navPoint><navLabel><text>第二章</text></navLabel><content"
                    + " src='part00000.html#0000000456'/></navPoint></navMap></ncx>");
    BookArchive archive = archive(data);
    EpubBook original =
        new EpubBook("书", "作者", List.of(new EpubBook.SpineItem("正文", "part00000.html")), null);
    List<KindleChapters.Section> sections = KindleChapters.split(archive, original, data.keySet());
    assertEquals(3, sections.size());
    assertEquals("第一章", sections.get(1).title);
    assertEquals("第二章", sections.get(2).title);
    Map<String, String> split = new HashMap<>();
    for (KindleChapters.Section section : sections)
      split.put(section.path, new String(section.html, StandardCharsets.UTF_8));
    BookParser parser = new BookParser();
    BookDocument.Chapter first =
        parser.readChapter(archive(split), new EpubBook.SpineItem("第一章", sections.get(1).path));
    assertTrue(first.text.contains("甲"));
    assertFalse(first.text.contains("乙"));
    assertEquals("resource00001.jpg", first.images.get(0).source);
    assertTrue(new String(sections.get(0).html, StandardCharsets.UTF_8).contains("前言😀"));
  }

  @Test
  public void missingAnchorsKeepTheOriginalCompleteSpine() throws Exception {
    EpubBook book =
        new EpubBook("书", "作者", List.of(new EpubBook.SpineItem("正文", "part.html")), null);
    assertTrue(KindleChapters.split(archive(Map.of()), book, List.of()).isEmpty());
  }

  private static BookArchive archive(Map<String, String> data) {
    return new BookArchive() {
      public InputStream open(String name) throws IOException {
        if (!data.containsKey(name)) throw new FileNotFoundException(name);
        return new ByteArrayInputStream(data.get(name).getBytes(StandardCharsets.UTF_8));
      }

      public void close() {}
    };
  }
}
