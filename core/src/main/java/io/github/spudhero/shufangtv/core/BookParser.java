package io.github.spudhero.shufangtv.core;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.net.URLDecoder;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Attribute;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;
import org.jsoup.nodes.Node;
import org.jsoup.nodes.TextNode;
import org.jsoup.parser.Parser;

/** Independent EPUB/TXT reader. This class neither opens URLs nor extracts ZIP files. */
public final class BookParser {
  public static final int MAX_METADATA_BYTES = 2 * 1024 * 1024;
  public static final int MAX_CHAPTER_BYTES = 8 * 1024 * 1024;
  public static final int MAX_TEXT_BYTES = 32 * 1024 * 1024;
  public static final int MAX_COVER_BYTES = 4 * 1024 * 1024;
  public static final int MAX_SPINE_ITEMS = 5000;
  private static final int MAX_ZIP_ENTRIES = 20000;
  private static final long MAX_LOCAL_EPUB_BYTES = 256L * 1024 * 1024;
  private static final Set<String> BLOCKS =
      new HashSet<>(
          Arrays.asList(
              "address",
              "article",
              "aside",
              "blockquote",
              "caption",
              "dd",
              "div",
              "dl",
              "dt",
              "figcaption",
              "figure",
              "footer",
              "h1",
              "h2",
              "h3",
              "h4",
              "h5",
              "h6",
              "header",
              "hr",
              "li",
              "main",
              "ol",
              "p",
              "pre",
              "section",
              "table",
              "tr",
              "ul"));
  private static final Set<String> OMIT =
      new HashSet<>(
          Arrays.asList(
              "script",
              "style",
              "noscript",
              "iframe",
              "object",
              "video",
              "audio",
              "canvas",
              "form",
              "input",
              "button",
              "select",
              "textarea",
              "rt",
              "rp"));
  private static final Pattern TXT_HEADING =
      Pattern.compile(
          "(?m)^[\\t \\u3000]*((?:第[零〇一二两三四五六七八九十百千万0-9]+[章节卷回部篇集][^\\n]{0,65})"
              + "|(?:(?:序言|序章|前言|后记|楔子|尾声|附录|序)(?:[\\t ：:][^\\n]{0,50})?))[\\t \\u3000]*$");
  private static final Pattern SCHEME = Pattern.compile("^[A-Za-z][A-Za-z0-9+.-]*:");
  private final int metadataLimit;
  private final int chapterLimit;
  private final int textLimit;
  private final int coverLimit;

  public BookParser() {
    this(MAX_METADATA_BYTES, MAX_CHAPTER_BYTES, MAX_TEXT_BYTES, MAX_COVER_BYTES);
  }

  // Smaller limits let tests exercise exact boundaries without allocating large books.
  BookParser(int metadataLimit, int chapterLimit, int textLimit, int coverLimit) {
    if (metadataLimit <= 0 || chapterLimit <= 0 || textLimit <= 0 || coverLimit <= 0) {
      throw new IllegalArgumentException("limits must be positive");
    }
    this.metadataLimit = metadataLimit;
    this.chapterLimit = chapterLimit;
    this.textLimit = textLimit;
    this.coverLimit = coverLimit;
  }

  public static boolean supported(String name) {
    String lower = name == null ? "" : name.toLowerCase(Locale.ROOT);
    return lower.endsWith(".epub") || lower.endsWith(".txt");
  }

  /** Imports a local EPUB/TXT completely. Online reading uses inspect/readChapter instead. */
  public BookDocument parse(File file) throws IOException {
    if (file == null || !file.isFile()) throw new IOException("书籍文件不存在或无法读取。");
    String lower = file.getName().toLowerCase(Locale.ROOT);
    if (lower.endsWith(".txt")) return parseText(file);
    if (!lower.endsWith(".epub")) throw new IOException("文本解析器目前支持 EPUB 和 TXT 文件。");
    if (file.length() > MAX_LOCAL_EPUB_BYTES) throw new IOException("EPUB 文件超过 256 MiB，无法完整导入。");
    try (BookArchive archive = new LocalArchive(file)) {
      EpubBook book = inspect(archive);
      List<BookDocument.Chapter> chapters = new ArrayList<>();
      long bodyBytes = 0;
      MetadataBudget inflatedBodyBudget =
          new MetadataBudget(textLimit, "EPUB 解压后的正文超过完整导入上限，未导入不完整内容。");
      boolean hasText = false;
      for (EpubBook.SpineItem item : book.spine) {
        BookDocument.Chapter chapter = readChapter(archive, item, inflatedBodyBudget);
        bodyBytes += chapter.text.getBytes(StandardCharsets.UTF_8).length;
        if (bodyBytes > textLimit) throw new IOException("书籍正文超过完整导入上限，未导入不完整内容。");
        if (!chapter.text.isEmpty()) chapters.add(chapter);
        hasText |= hasReadableText(chapter.text) || !chapter.images.isEmpty();
      }
      if (chapters.isEmpty() || !hasText) throw new IOException("这本 EPUB 没有可读取的文字，可能是纯图片书籍。");
      String title = "未命名书籍".equals(book.title) ? fileTitle(file) : book.title;
      return new BookDocument(title, book.author, chapters, readCover(archive, book));
    } catch (ZipException e) {
      throw new IOException("EPUB 压缩包损坏或使用了不支持的加密格式。", e);
    }
  }

  /** Reads only package metadata and navigation. It never opens a spine chapter or cover image. */
  public EpubBook inspect(BookArchive archive) throws IOException {
    MetadataBudget budget = new MetadataBudget((long) metadataLimit * 4);
    Document container = xml(required(archive, "META-INF/container.xml", metadataLimit, budget));
    Element rootFile = null;
    for (Element candidate : descendants(container, "rootfile")) {
      if (rootFile == null) rootFile = candidate;
      if ("application/oebps-package+xml".equals(candidate.attr("media-type"))) {
        rootFile = candidate;
        break;
      }
    }
    if (rootFile == null || rootFile.attr("full-path").isEmpty()) {
      throw new IOException("EPUB 缺少有效的 container.xml 书籍入口。");
    }
    String packagePath = resolve("", rootFile.attr("full-path"));
    Document opf = xml(required(archive, packagePath, metadataLimit, budget));
    if (first(opf, "package") == null) throw new IOException("EPUB 的 OPF 打包文件无效。");
    checkEncryption(archive, budget);

    Element metadata = first(opf, "metadata");
    String title = metadata == null ? "" : firstText(metadata, "title");
    String author = metadata == null ? "" : authors(metadata);
    if (title.isEmpty()) title = "未命名书籍";
    if (author.isEmpty()) author = "作者未知";
    Element manifest = first(opf, "manifest");
    Element spine = first(opf, "spine");
    if (manifest == null || spine == null) throw new IOException("EPUB 缺少资源清单或阅读顺序。");

    Map<String, ManifestItem> items = new LinkedHashMap<>();
    String coverId = "";
    if (metadata != null) {
      for (Element meta : descendants(metadata, "meta")) {
        if ("cover".equalsIgnoreCase(meta.attr("name"))) coverId = meta.attr("content");
      }
    }
    ManifestItem nav = null;
    ManifestItem ncx = null;
    String coverPath = null, legacyCover = null, namedCover = null;
    for (Element element : manifest.children()) {
      if (!"item".equals(local(element.tagName()))) continue;
      String id = element.attr("id");
      String href = element.attr("href");
      if (id.isEmpty() || href.isEmpty() || items.containsKey(id)) {
        throw new IOException("EPUB 资源清单含空白或重复的资源标识。");
      }
      String itemPath = external(href) ? null : resolve(packagePath, href);
      ManifestItem item =
          new ManifestItem(
              id,
              itemPath,
              element.attr("media-type"),
              element.attr("properties"),
              element.attr("fallback"));
      items.put(id, item);
      if (token(item.properties, "nav")) nav = item;
      if ("application/x-dtbncx+xml".equals(item.mediaType)) ncx = item;
      if (token(item.properties, "cover-image")) coverPath = item.path;
      if (item.id.equals(coverId)) legacyCover = item.path;
      if (item.mediaType.startsWith("image/")
          && (id.equalsIgnoreCase("cover") || href.matches("(?i).*(?:^|/)cover\\.[^/]+$")))
        namedCover = item.path;
    }
    if (coverPath == null) coverPath = legacyCover;
    if (coverPath == null) {
      Element guide = first(opf, "guide");
      if (guide != null)
        for (Element ref : descendants(guide, "reference"))
          if (token(ref.attr("type"), "cover")
              && !ref.attr("href").isEmpty()
              && !external(ref.attr("href"))) {
            try {
              coverPath = resolve(packagePath, ref.attr("href"));
            } catch (IOException ignored) {
              // Optional cover metadata must not prevent reading a valid spine.
            }
            break;
          }
    }
    if (coverPath == null) coverPath = namedCover;
    String tocId = spine.attr("toc");
    if (!tocId.isEmpty() && items.containsKey(tocId)) ncx = items.get(tocId);
    Map<String, String> titles = new HashMap<>();
    if (nav != null && nav.path != null) readNavigation(archive, nav.path, titles, budget);
    if (ncx != null && ncx.path != null) readNcx(archive, ncx.path, titles, budget);

    List<EpubBook.SpineItem> order = new ArrayList<>();
    for (Element ref : spine.children()) {
      if (!"itemref".equals(local(ref.tagName()))) continue;
      ManifestItem item = textItem(items, ref.attr("idref"));
      if (item.path == null) throw new IOException("EPUB 正文引用了外部资源，无法作为书籍章节读取。");
      if (order.size() >= MAX_SPINE_ITEMS) throw new IOException("EPUB 章节数超过 5000，无法安全读取。");
      String label = titles.get(item.path);
      boolean fromNav = label != null && !label.isEmpty();
      order.add(
          new EpubBook.SpineItem(
              fromNav ? label : "第 " + (order.size() + 1) + " 章", item.path, fromNav));
    }
    if (order.isEmpty()) throw new IOException("EPUB 的阅读顺序为空。");
    return new EpubBook(title, author, order, coverPath);
  }

  /** Loads exactly the requested XHTML/HTML entry, with a limit on inflated bytes. */
  public BookDocument.Chapter readChapter(BookArchive archive, EpubBook.SpineItem chapter)
      throws IOException {
    return readChapter(archive, chapter, null);
  }

  private BookDocument.Chapter readChapter(
      BookArchive archive, EpubBook.SpineItem chapter, MetadataBudget budget) throws IOException {
    String path = canonicalEntry(chapter.path);
    byte[] data = required(archive, path, chapterLimit, budget);
    Document document = html(data);
    Element body = document.body();
    if (body == null) throw new IOException("EPUB 章节没有可读取的正文。");
    body.select("script,style,noscript,iframe,object,video,audio,canvas,form").remove();
    Element heading = body.selectFirst("h1,h2,h3");
    String title = chapter.title;
    if (!chapter.navigationTitle) {
      String embeddedTitle =
          heading != null ? inlineText(heading.text()) : inlineText(document.title());
      if (!embeddedTitle.isEmpty()) title = embeddedTitle;
    }
    if (heading != null && inlineText(heading.text()).equals(inlineText(title))) heading.remove();
    StringBuilder output = new StringBuilder();
    List<Element> imageElements = new ArrayList<>();
    appendNodes(body, output, imageElements);
    String text = normalizeLines(output.toString());
    List<BookDocument.Illustration> images = new ArrayList<>();
    StringBuilder anchored = new StringBuilder();
    int cursor = 0;
    for (int i = 0; i < imageElements.size(); i++) {
      String marker = "\ue000" + i + "\ue001";
      int at = text.indexOf(marker, cursor);
      if (at < 0) throw new IOException("图片位置解析失败");
      anchored.append(text, cursor, at);
      Element element = imageElements.get(i);
      String alt = inlineText(element.attr("alt"));
      String label = "［" + (alt.isEmpty() ? "插图" : "图片：" + alt) + "］";
      int start = anchored.length();
      anchored.append(label);
      String src = element.attr("src"), svg = "";
      if ("svg".equals(local(element.normalName()))) {
        Element embedded = element.selectFirst("image");
        if (embedded != null && element.select("image").size() == 1) {
          src = embedded.hasAttr("href") ? embedded.attr("href") : embedded.attr("xlink:href");
        } else svg = element.outerHtml();
      }
      // Resource access stays inside the selected book. Data images are decoded with a byte cap.
      String source =
          !svg.isEmpty()
              ? path
              : src.startsWith("data:image/") ? src : src.isEmpty() ? "" : resolve(path, src);
      images.add(new BookDocument.Illustration(start, anchored.length(), source, alt, svg));
      cursor = at + marker.length();
    }
    anchored.append(text, cursor, text.length());
    text = anchored.toString();
    if (text.getBytes(StandardCharsets.UTF_8).length > chapterLimit) {
      throw new IOException("该章节正文超过单章读取上限。");
    }
    return new BookDocument.Chapter(title, text, images);
  }

  /**
   * Separate from inspection; a missing cover is harmless, while transport failures remain errors.
   */
  public byte[] readCover(BookArchive archive, EpubBook book) throws IOException {
    return book.coverPath == null
        ? null
        : optional(archive, canonicalEntry(book.coverPath), coverLimit, null);
  }

  /** Resolves a cover page without scanning the rest of the book or fetching its images. */
  public BookDocument.Illustration coverIllustration(BookArchive archive, EpubBook book)
      throws IOException {
    String path = book.coverPath;
    if (path == null) {
      if (book.spine.isEmpty()) return null;
      path = book.spine.get(0).path;
    }
    String lower = path.toLowerCase(Locale.ROOT);
    if (lower.matches(".*\\.(?:jpe?g|png|gif|webp|bmp|svg|avif)$"))
      return new BookDocument.Illustration(0, 0, path, "封面", "");
    byte[] page = optional(archive, canonicalEntry(path), coverLimit, null);
    if (page == null) return null;
    Document document = html(page);
    Element image = document.selectFirst("img[src],svg");
    if (image == null) return null;
    if ("svg".equals(local(image.normalName())))
      return new BookDocument.Illustration(0, 0, path, "封面", image.outerHtml());
    String src = image.attr("src");
    if (src.isEmpty()) return null;
    return new BookDocument.Illustration(
        0, 0, src.startsWith("data:image/") ? src : resolve(path, src), "封面", "");
  }

  private BookDocument parseText(File file) throws IOException {
    byte[] data;
    try (InputStream input = new FileInputStream(file)) {
      data = bounded(input, textLimit, "TXT 正文", null);
    }
    String text = normalizeLines(decodeText(data));
    if (!hasReadableText(text)) throw new IOException("TXT 文件没有可读取的正文。");
    if (text.indexOf('\0') >= 0) throw new IOException("TXT 含有二进制内容或未知编码，无法读取。");
    List<BookDocument.Chapter> chapters = splitText(text, fileTitle(file));
    return new BookDocument(fileTitle(file), "作者未知", chapters, null);
  }

  private List<BookDocument.Chapter> splitText(String text, String fallbackTitle)
      throws IOException {
    List<BookDocument.Chapter> chapters = new ArrayList<>();
    Matcher matcher = TXT_HEADING.matcher(text);
    String currentTitle = fallbackTitle;
    int start = 0;
    while (matcher.find()) {
      String preceding = trimSpace(text.substring(start, matcher.start()));
      if (!preceding.isEmpty()) chapters.add(new BookDocument.Chapter(currentTitle, preceding));
      if (chapters.size() >= MAX_SPINE_ITEMS) throw new IOException("TXT 章节数过多，无法安全读取。");
      currentTitle = inlineText(matcher.group(1));
      start = matcher.end();
    }
    String remaining = trimSpace(text.substring(start));
    if (!remaining.isEmpty()) chapters.add(new BookDocument.Chapter(currentTitle, remaining));
    if (chapters.isEmpty()) chapters.add(new BookDocument.Chapter(fallbackTitle, text));
    return chapters;
  }

  private void checkEncryption(BookArchive archive, MetadataBudget budget) throws IOException {
    byte[] rights = optional(archive, "META-INF/rights.xml", metadataLimit, budget);
    if (rights != null) throw new IOException("这本 EPUB 受 DRM 保护，当前无法读取。");
    byte[] encryption = optional(archive, "META-INF/encryption.xml", metadataLimit, budget);
    if (encryption == null) return;
    Document document = xml(encryption);
    List<Element> encrypted = descendants(document, "EncryptedData");
    if (encrypted.isEmpty()) throw new IOException("EPUB 的加密说明无法识别。");
    for (Element item : encrypted) {
      Element method = first(item, "EncryptionMethod");
      String algorithm = method == null ? "" : method.attr("Algorithm");
      // EPUB font obfuscation does not encrypt the text; system fonts are used instead.
      if (!"http://www.idpf.org/2008/embedding".equals(algorithm)
          && !"http://ns.adobe.com/pdf/enc#RC".equals(algorithm)) {
        throw new IOException("这本 EPUB 受 DRM 或未知加密保护，当前无法读取。");
      }
      for (Element reference : descendants(item, "CipherReference")) {
        String path = resolve("", reference.attr("URI"));
        String lower = path.toLowerCase(Locale.ROOT);
        if (!lower.endsWith(".otf")
            && !lower.endsWith(".ttf")
            && !lower.endsWith(".woff")
            && !lower.endsWith(".woff2")) {
          throw new IOException("EPUB 正文使用了不支持的加密方式。");
        }
      }
    }
  }

  private void readNavigation(
      BookArchive archive, String path, Map<String, String> titles, MetadataBudget budget)
      throws IOException {
    byte[] data = optional(archive, path, metadataLimit, budget);
    if (data == null) return;
    Document document = html(data);
    Element toc = null;
    for (Element nav : descendants(document, "nav")) {
      if (toc == null) toc = nav;
      if (token(attribute(nav, "type"), "toc")) {
        toc = nav;
        break;
      }
    }
    if (toc == null) return;
    for (Element link : toc.select("a[href]")) {
      String href = link.attr("href");
      String label = inlineText(link.text());
      if (!label.isEmpty() && !external(href)) titles.putIfAbsent(resolve(path, href), label);
    }
  }

  private void readNcx(
      BookArchive archive, String path, Map<String, String> titles, MetadataBudget budget)
      throws IOException {
    byte[] data = optional(archive, path, metadataLimit, budget);
    if (data == null) return;
    Document document = xml(data);
    for (Element point : descendants(document, "navPoint")) {
      // Only the current navPoint's own children are considered; nested labels belong to later
      // entries.
      Element label = child(point, "navLabel");
      Element content = child(point, "content");
      if (label == null || content == null || content.attr("src").isEmpty()) continue;
      String text = inlineText(label.text());
      String href = content.attr("src");
      if (!text.isEmpty() && !external(href)) titles.putIfAbsent(resolve(path, href), text);
    }
  }

  private static ManifestItem textItem(Map<String, ManifestItem> items, String id)
      throws IOException {
    Set<String> seen = new HashSet<>();
    while (true) {
      ManifestItem item = items.get(id);
      if (item == null) throw new IOException("EPUB 阅读顺序引用了不存在的章节。");
      if (!seen.add(id)) throw new IOException("EPUB 的章节替代资源形成循环。");
      if ("application/xhtml+xml".equals(item.mediaType)
          || "text/html".equals(item.mediaType)
          || (item.mediaType.isEmpty()
              && item.path != null
              && item.path.toLowerCase(Locale.ROOT).matches(".*\\.(xhtml|html|htm)$"))) return item;
      if (item.fallback.isEmpty()) throw new IOException("EPUB 含有暂不支持的正文类型：" + item.mediaType);
      id = item.fallback;
    }
  }

  private static byte[] required(BookArchive archive, String path, int limit, MetadataBudget budget)
      throws IOException {
    try (InputStream input = archive.open(path)) {
      if (input == null) throw new IOException("书籍数据源未返回可读取的内容。");
      return bounded(input, limit, path, budget);
    } catch (FileNotFoundException e) {
      throw new IOException("EPUB 缺少文件：" + path, e);
    }
  }

  private static byte[] optional(BookArchive archive, String path, int limit, MetadataBudget budget)
      throws IOException {
    InputStream stream;
    try {
      stream = archive.open(path);
    } catch (FileNotFoundException e) {
      return null;
    }
    if (stream == null) throw new IOException("书籍数据源未返回可读取的内容。");
    try (InputStream input = stream) {
      return bounded(input, limit, path, budget);
    }
  }

  private static byte[] bounded(InputStream input, int limit, String name, MetadataBudget budget)
      throws IOException {
    ByteArrayOutputStream output = new ByteArrayOutputStream(Math.min(8192, limit));
    byte[] buffer = new byte[8192];
    int total = 0;
    int count;
    while ((count = input.read(buffer)) != -1) {
      if (count == 0) continue;
      if (count > limit - total) throw new IOException("书籍文件超过读取上限：" + name);
      total += count;
      if (budget != null) budget.add(count);
      output.write(buffer, 0, count);
    }
    return output.toByteArray();
  }

  private static Document xml(byte[] bytes) throws IOException {
    rejectEntities(bytes);
    // Jsoup parses local bytes only and never resolves external DTDs or network entities.
    return Jsoup.parse(new ByteArrayInputStream(bytes), null, "", Parser.xmlParser());
  }

  private static Document html(byte[] bytes) throws IOException {
    rejectEntities(bytes);
    return Jsoup.parse(new ByteArrayInputStream(bytes), null, "");
  }

  private static void rejectEntities(byte[] bytes) throws IOException {
    // XML entity declarations are unnecessary for EPUB text and may hide unreadable content.
    String ascii = new String(bytes, StandardCharsets.ISO_8859_1);
    if (ascii.toUpperCase(Locale.ROOT).contains("<!ENTITY")) {
      throw new IOException("EPUB 含有不支持的自定义实体声明。");
    }
  }

  private static String decodeText(byte[] bytes) throws IOException {
    int offset = 0;
    Charset charset = null;
    if (bytes.length >= 3
        && bytes[0] == (byte) 0xef
        && bytes[1] == (byte) 0xbb
        && bytes[2] == (byte) 0xbf) {
      charset = StandardCharsets.UTF_8;
      offset = 3;
    } else if (bytes.length >= 2 && bytes[0] == (byte) 0xff && bytes[1] == (byte) 0xfe) {
      charset = StandardCharsets.UTF_16LE;
      offset = 2;
    } else if (bytes.length >= 2 && bytes[0] == (byte) 0xfe && bytes[1] == (byte) 0xff) {
      charset = StandardCharsets.UTF_16BE;
      offset = 2;
    }
    if (charset != null) return strictDecode(bytes, offset, charset);
    try {
      return strictDecode(bytes, 0, StandardCharsets.UTF_8);
    } catch (IOException invalidUtf8) {
      return strictDecode(bytes, 0, Charset.forName("GB18030"));
    }
  }

  private static String strictDecode(byte[] bytes, int offset, Charset charset) throws IOException {
    try {
      return charset
          .newDecoder()
          .onMalformedInput(CodingErrorAction.REPORT)
          .onUnmappableCharacter(CodingErrorAction.REPORT)
          .decode(ByteBuffer.wrap(bytes, offset, bytes.length - offset))
          .toString();
    } catch (CharacterCodingException e) {
      throw new IOException("无法识别 TXT 编码，请使用 UTF-8、UTF-16 或 GB18030。", e);
    }
  }

  private static void appendNodes(Node root, StringBuilder output) {
    appendNodes(root, output, null);
  }

  private static void appendNodes(Node root, StringBuilder output, List<Element> images) {
    // Iterative traversal prevents deeply nested but small XHTML from exhausting the Java stack.
    Deque<WalkNode> pending = new ArrayDeque<>();
    pending.push(new WalkNode(root, false));
    while (!pending.isEmpty()) {
      WalkNode current = pending.pop();
      Node node = current.node;
      if (node instanceof TextNode) {
        output.append(
            ((TextNode) node)
                .getWholeText()
                .replace("\ue000", "")
                .replace("\ue001", "")
                .replaceAll("[\\t\\r\\n\\f ]+", " "));
        continue;
      }
      if (!(node instanceof Element)) continue;
      Element element = (Element) node;
      String tag = local(element.normalName());
      if (OMIT.contains(tag)) continue;
      if (current.exit) {
        if (BLOCKS.contains(tag)) output.append("\n\n");
        if ("td".equals(tag) || "th".equals(tag)) output.append("  ");
        continue;
      }
      if ("br".equals(tag)) {
        output.append('\n');
        continue;
      }
      if (images != null && ("img".equals(tag) || "svg".equals(tag))) {
        output.append('\n').append('\ue000').append(images.size()).append('\ue001').append('\n');
        images.add(element);
        continue;
      }
      if ("img".equals(tag)) {
        String alt = inlineText(element.attr("alt"));
        output.append("\n［").append(alt.isEmpty() ? "插图" : "图片：" + alt).append("］\n");
        continue;
      }
      if ("svg".equals(tag) && element.select("text").isEmpty()) {
        output.append("\n［插图］\n");
        continue;
      }
      if (BLOCKS.contains(tag)) output.append("\n\n");
      pending.push(new WalkNode(node, true));
      List<Node> children = node.childNodes();
      for (int i = children.size() - 1; i >= 0; i--)
        pending.push(new WalkNode(children.get(i), false));
    }
  }

  private static String normalizeLines(String input) {
    return trimSpace(
        input
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .replace('\f', '\n')
            .replace("\ufeff", "")
            .replace('\u00a0', ' ')
            .replaceAll("[\\t\\x0B\\p{Zs}]+", " ")
            .replaceAll(" *\\n *", "\n")
            .replaceAll("\\n{3,}", "\n\n"));
  }

  private static String inlineText(String input) {
    return trimSpace(input.replace('\u00a0', ' ').replaceAll("[\\s\\p{Zs}]+", " "));
  }

  private static String trimSpace(String text) {
    int start = 0, end = text.length();
    while (start < end
        && (Character.isWhitespace(text.charAt(start))
            || Character.isSpaceChar(text.charAt(start)))) start++;
    while (end > start
        && (Character.isWhitespace(text.charAt(end - 1))
            || Character.isSpaceChar(text.charAt(end - 1)))) end--;
    return text.substring(start, end);
  }

  private static boolean hasReadableText(String text) {
    String withoutPlaceholders = text.replace("［插图］", "");
    for (int offset = 0; offset < withoutPlaceholders.length(); ) {
      int codePoint = withoutPlaceholders.codePointAt(offset);
      if (Character.isLetterOrDigit(codePoint)) return true;
      offset += Character.charCount(codePoint);
    }
    return false;
  }

  private static String fileTitle(File file) {
    String name = file.getName();
    int dot = name.lastIndexOf('.');
    return dot > 0 ? name.substring(0, dot) : name;
  }

  private static String local(String name) {
    int colon = name.indexOf(':');
    return colon < 0 ? name : name.substring(colon + 1);
  }

  private static Element first(Element root, String name) {
    for (Element element : root.getAllElements())
      if (name.equals(local(element.tagName()))) return element;
    return null;
  }

  private static Element child(Element root, String name) {
    for (Element element : root.children())
      if (name.equals(local(element.tagName()))) return element;
    return null;
  }

  private static List<Element> descendants(Element root, String name) {
    List<Element> result = new ArrayList<>();
    for (Element element : root.getAllElements())
      if (name.equals(local(element.tagName()))) result.add(element);
    return result;
  }

  private static String firstText(Element root, String name) {
    Element element = first(root, name);
    return element == null ? "" : inlineText(element.text());
  }

  private static String authors(Element metadata) {
    List<String> authors = new ArrayList<>();
    for (Element creator : descendants(metadata, "creator")) {
      String value = inlineText(creator.text());
      if (!value.isEmpty() && !authors.contains(value)) authors.add(value);
    }
    return String.join("、", authors);
  }

  private static String attribute(Element element, String name) {
    for (Attribute attribute : element.attributes())
      if (name.equals(local(attribute.getKey()))) return attribute.getValue();
    return "";
  }

  private static boolean token(String input, String token) {
    for (String part : input.split("\\s+")) if (part.equals(token)) return true;
    return false;
  }

  private static boolean external(String href) {
    String value = trimSpace(href);
    return value.startsWith("//") || SCHEME.matcher(value).find();
  }

  /** Resolves an EPUB URL against a ZIP entry; it never resolves to the filesystem or network. */
  public static String resolve(String baseFile, String href) throws IOException {
    String ref = trimSpace(href);
    if (external(ref)) throw new IOException("EPUB 资源必须位于书籍压缩包内。");
    int hash = ref.indexOf('#');
    if (hash >= 0) ref = ref.substring(0, hash);
    int query = ref.indexOf('?');
    if (query >= 0) ref = ref.substring(0, query);
    try {
      // URLDecoder normally changes '+' to a space, which is incorrect in a URI path.
      ref = URLDecoder.decode(ref.replace("+", "%2B"), "UTF-8");
    } catch (IllegalArgumentException e) {
      throw new IOException("EPUB 资源路径的百分号编码无效。", e);
    }
    if (ref.isEmpty()) {
      if (baseFile.isEmpty()) throw new IOException("EPUB 资源路径为空。");
      return canonicalEntry(baseFile);
    }
    if (ref.startsWith("/") || ref.indexOf('\\') >= 0 || external(ref)) {
      throw new IOException("EPUB 含有越界或外部资源路径。");
    }
    String base =
        baseFile.contains("/") ? baseFile.substring(0, baseFile.lastIndexOf('/') + 1) : "";
    return canonicalEntry(base + ref);
  }

  private static String canonicalEntry(String path) throws IOException {
    if (path == null
        || path.isEmpty()
        || path.startsWith("/")
        || path.indexOf('\\') >= 0
        || path.indexOf('\0') >= 0
        || SCHEME.matcher(path).find()) {
      throw new IOException("EPUB 含有无效的资源路径。");
    }
    Deque<String> parts = new ArrayDeque<>();
    for (String part : path.split("/")) {
      if (part.isEmpty() || ".".equals(part)) continue;
      if ("..".equals(part)) {
        if (parts.isEmpty()) throw new IOException("EPUB 资源路径越过压缩包根目录。");
        parts.removeLast();
      } else parts.addLast(part);
    }
    if (parts.isEmpty()) throw new IOException("EPUB 资源路径为空。");
    return String.join("/", parts);
  }

  private static final class MetadataBudget {
    final long limit;
    final String message;
    long read;

    MetadataBudget(long limit) {
      this(limit, "EPUB 元数据总量过大，无法安全读取。");
    }

    MetadataBudget(long limit, String message) {
      this.limit = limit;
      this.message = message;
    }

    void add(int bytes) throws IOException {
      read += bytes;
      if (read > limit) throw new IOException(message);
    }
  }

  private static final class ManifestItem {
    final String id, path, mediaType, properties, fallback;

    ManifestItem(String id, String path, String mediaType, String properties, String fallback) {
      this.id = id;
      this.path = path;
      this.mediaType = mediaType;
      this.properties = properties;
      this.fallback = fallback;
    }
  }

  private static final class WalkNode {
    final Node node;
    final boolean exit;

    WalkNode(Node node, boolean exit) {
      this.node = node;
      this.exit = exit;
    }
  }

  private static final class LocalArchive implements BookArchive {
    final ZipFile zip;
    final Map<String, ZipEntry> entries = new HashMap<>();

    LocalArchive(File file) throws IOException {
      zip = new ZipFile(file, StandardCharsets.UTF_8);
      boolean valid = false;
      try {
        Enumeration<? extends ZipEntry> inventory = zip.entries();
        int count = 0;
        while (inventory.hasMoreElements()) {
          ZipEntry entry = inventory.nextElement();
          if (++count > MAX_ZIP_ENTRIES) throw new IOException("EPUB 资源文件过多，无法安全读取。");
          String name = canonicalEntry(entry.getName());
          if (entry.isDirectory()) continue;
          if (entries.put(name, entry) != null) throw new IOException("EPUB 存在冲突的重复文件路径。");
        }
        valid = true;
      } finally {
        if (!valid) zip.close();
      }
    }

    @Override
    public InputStream open(String entryName) throws IOException {
      ZipEntry entry = entries.get(canonicalEntry(entryName));
      if (entry == null) throw new FileNotFoundException(entryName);
      return zip.getInputStream(entry);
    }

    @Override
    public void close() throws IOException {
      zip.close();
    }
  }
}
