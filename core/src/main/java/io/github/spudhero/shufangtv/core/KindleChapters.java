package io.github.spudhero.shufangtv.core;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.*;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Element;
import org.jsoup.parser.Parser;

/** Legacy MOBI stores the entire book in one HTML part; NCX anchors recover its real chapters. */
public final class KindleChapters {
  public static final class Section {
    public final String title, path;
    public final byte[] html;

    Section(String title, String path, String html) {
      this.title = title;
      this.path = path;
      this.html = html.getBytes(StandardCharsets.UTF_8);
    }
  }

  public static List<Section> split(BookArchive archive, EpubBook book, Collection<String> entries)
      throws IOException {
    if (book.spine.size() != 1) return Collections.emptyList();
    String part = book.spine.get(0).path;
    List<String[]> links = new ArrayList<>();
    for (String entry : entries)
      if (entry.endsWith(".ncx")) {
        String xml = read(archive, entry, BookParser.MAX_METADATA_BYTES);
        for (Element point : Jsoup.parse(xml, "", Parser.xmlParser()).select("navPoint")) {
          Element content = null, label = null;
          for (Element child : point.children()) {
            if (child.tagName().equals("content")) content = child;
            if (child.tagName().equals("navLabel")) label = child;
          }
          if (content == null || label == null) continue;
          String href = content.attr("src");
          int hash = href.indexOf('#');
          if (hash < 0 || !BookParser.resolve(entry, href).equals(part)) continue;
          links.add(new String[] {href.substring(hash + 1), label.text()});
        }
      }
    if (links.size() < 2) return Collections.emptyList();
    String html = Jsoup.parse(read(archive, part, BookParser.MAX_TEXT_BYTES)).body().html();
    TreeMap<Integer, String> starts = new TreeMap<>();
    for (String[] link : links) {
      Pattern anchor = Pattern.compile("\\bid\\s*=\\s*(['\"])" + Pattern.quote(link[0]) + "\\1");
      Matcher match = anchor.matcher(html);
      if (match.find()) starts.putIfAbsent(html.lastIndexOf('<', match.start()), link[1]);
    }
    if (starts.size() < 2) return Collections.emptyList();
    starts.putIfAbsent(0, "扉页与前言");
    if (starts.size() > BookParser.MAX_SPINE_ITEMS) throw new IOException("Kindle 章节数超过读取上限");
    List<Integer> positions = new ArrayList<>(starts.keySet());
    List<Section> sections = new ArrayList<>();
    for (int i = 0; i < positions.size(); i++) {
      int start = positions.get(i),
          end = i + 1 < positions.size() ? positions.get(i + 1) : html.length();
      sections.add(
          new Section(
              starts.get(start),
              String.format(Locale.ROOT, "chapter%05d.xhtml", i),
              "<html><head><meta charset='utf-8'/></head><body>"
                  + html.substring(start, end)
                  + "</body></html>"));
    }
    return sections;
  }

  private static String read(BookArchive archive, String name, int limit) throws IOException {
    try (InputStream input = archive.open(name);
        ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      byte[] buffer = new byte[16384];
      int n;
      while ((n = input.read(buffer)) != -1) {
        if (out.size() + (long) n > limit) throw new IOException("Kindle 正文或目录超过读取上限");
        out.write(buffer, 0, n);
      }
      return out.toString(StandardCharsets.UTF_8.name());
    }
  }

  private KindleChapters() {}
}
