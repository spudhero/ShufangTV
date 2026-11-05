package io.github.spudhero.shufangtv.core;

import static org.junit.Assert.*;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class BookParserTest {
  @Rule public final TemporaryFolder temporary = new TemporaryFolder();
  private final BookParser parser = new BookParser();

  @Test
  public void advertisesOnlyHandledTextFormats() {
    assertTrue(BookParser.supported("中文.EPUB"));
    assertTrue(BookParser.supported("notes.txt"));
    assertFalse(BookParser.supported("notes.pdf"));
    assertFalse(BookParser.supported(null));
  }

  @Test
  public void inspectIsLazyAndReturnsSpineOrderAndCoverPath() throws Exception {
    Map<String, byte[]> entries =
        book(
            item("second", "Text/second.xhtml")
                + item("first", "Text/first.xhtml")
                + "<item id='cover' href='Images/封面.jpg' media-type='image/jpeg'"
                + " properties='cover-image'/>",
            "<itemref idref='first'/><itemref idref='second'/>");
    // No chapter or cover payload is present: metadata inspection must still succeed.
    MemoryArchive archive = new MemoryArchive(entries);
    EpubBook result = parser.inspect(archive);
    assertEquals("测试书籍", result.title);
    assertEquals("作者甲、作者乙", result.author);
    assertEquals(2, result.spine.size());
    assertEquals("OPS/Text/first.xhtml", result.spine.get(0).path);
    assertEquals("OPS/Text/second.xhtml", result.spine.get(1).path);
    assertEquals("OPS/Images/封面.jpg", result.coverPath);
    assertFalse(archive.opened.contains(result.spine.get(0).path));
    assertFalse(archive.opened.contains(result.spine.get(1).path));
    assertFalse(archive.opened.contains(result.coverPath));
    assertEquals(0, archive.activeStreams);
    assertFalse(archive.closed);
  }

  @Test
  public void readChapterLoadsOnlyThatEntryAndKeepsChineseParagraphs() throws Exception {
    MemoryArchive archive = new MemoryArchive(new LinkedHashMap<>());
    archive.entries.put(
        "OPS/Text/current.xhtml",
        bytes(
            "<!DOCTYPE html><html><head><title>备用标题</title></head>"
                + "<body><h1>知行合一</h1><p>知<span>行</span>合一。</p><p>第二段。<br/>下一行。</p>"
                + "<script>不可显示()</script><style>body {color:red}</style>"
                + "<p>水<ruby>善<rt>shàn</rt><rp>(</rp></ruby>利万物。</p></body></html>"));
    BookDocument.Chapter chapter =
        parser.readChapter(archive, new EpubBook.SpineItem("知行合一", "OPS/Text/current.xhtml"));
    assertEquals("知行合一", chapter.title);
    assertEquals("知行合一。\n\n第二段。\n下一行。\n\n水善利万物。", chapter.text);
    assertEquals(Arrays.asList("OPS/Text/current.xhtml"), archive.opened);
    assertEquals(0, archive.activeStreams);
    assertFalse(archive.closed);
  }

  @Test
  public void readCoverIsAnExplicitSeparateRequest() throws Exception {
    MemoryArchive archive = new MemoryArchive(new LinkedHashMap<>());
    byte[] cover = {1, 2, 3, 4};
    archive.entries.put("cover.png", cover);
    EpubBook book = new EpubBook("书", "人", new ArrayList<>(), "cover.png");
    assertArrayEquals(cover, parser.readCover(archive, book));
    assertEquals(Arrays.asList("cover.png"), archive.opened);
    assertNull(parser.readCover(archive, new EpubBook("书", "人", new ArrayList<>(), null)));
  }

  @Test
  public void brokenCoverReferenceDoesNotPreventReadingTheBook() throws Exception {
    Map<String, byte[]> entries =
        book(
            item("a", "a.xhtml")
                + "<item id='cover' href='missing-cover.jpg' media-type='image/jpeg'"
                + " properties='cover-image'/>",
            "<itemref idref='a'/>");
    entries.put("OPS/a.xhtml", html("第一章", "封面缺失，正文仍可阅读。"));
    BookDocument result = parser.parse(zip("missing-cover.epub", entries));
    assertEquals("封面缺失，正文仍可阅读。", result.chapters.get(0).text);
    assertNull(result.coverBytes);
  }

  @Test
  public void declaredChineseMarkupEncodingIsRespected() throws Exception {
    Map<String, byte[]> entries = singleBook();
    entries.put(
        "OPS/a.xhtml",
        ("<?xml version='1.0' encoding='GB18030'?>"
                + "<html><head><title>旧编码章节</title></head><body><p>山川与河流。</p></body></html>")
            .getBytes(Charset.forName("GB18030")));
    BookDocument result = parser.parse(zip("legacy-markup.epub", entries));
    assertEquals("旧编码章节", result.chapters.get(0).title);
    assertEquals("山川与河流。", result.chapters.get(0).text);
  }

  @Test
  public void offlineImportFollowsSpineNotZipOrder() throws Exception {
    Map<String, byte[]> entries =
        book(
            item("a", "a.xhtml") + item("b", "b.xhtml"),
            "<itemref idref='b'/><itemref idref='a'/>");
    entries.put("OPS/a.xhtml", html("甲章", "甲正文。"));
    entries.put("OPS/b.xhtml", html("乙章", "乙正文。"));
    BookDocument result = parser.parse(zip("book.epub", entries));
    assertEquals("乙章", result.chapters.get(0).title);
    assertEquals("乙正文。", result.chapters.get(0).text);
    assertEquals("甲章", result.chapters.get(1).title);
    assertEquals(2, result.chapters.size());
  }

  @Test
  public void epubThreeNavigationResolvesChinesePercentPathsAndWinsOverNcx() throws Exception {
    Map<String, byte[]> entries =
        book(
            item("a", "Text/第%20一+章.xhtml")
                + "<item id='nav' href='nav.xhtml' media-type='application/xhtml+xml'"
                + " properties='nav'/><item id='toc' href='old.ncx'"
                + " media-type='application/x-dtbncx+xml'/>",
            "<itemref idref='a'/>");
    entries.put(
        "OPS/nav.xhtml",
        bytes(
            "<html><body><nav epub:type='landmarks'><a href='wrong.xhtml'>封面</a></nav><nav"
                + " epub:type='toc'><ol><li><a"
                + " href='Text/第%20一+章.xhtml#start'>新的中文章名</a></li></ol></nav></body></html>"));
    entries.put(
        "OPS/old.ncx",
        bytes(
            "<ncx><navMap><navPoint><navLabel><text>旧标题</text></navLabel>"
                + "<content src='Text/第%20一+章.xhtml'/></navPoint></navMap></ncx>"));
    MemoryArchive archive = new MemoryArchive(entries);
    EpubBook result = parser.inspect(archive);
    assertEquals("OPS/Text/第 一+章.xhtml", result.spine.get(0).path);
    assertEquals("新的中文章名", result.spine.get(0).title);
    assertFalse(archive.opened.contains("OPS/Text/第 一+章.xhtml"));
  }

  @Test
  public void ncxNestedLabelsRemainAttachedToTheirOwnSpineItems() throws Exception {
    Map<String, byte[]> entries =
        book(
            item("a", "Text/a.xhtml")
                + item("b", "Text/b.xhtml")
                + "<item id='ncx' href='Nav/toc.ncx' media-type='application/x-dtbncx+xml'/>",
            "<itemref idref='a'/><itemref idref='b'/>");
    entries.put(
        "OPS/Nav/toc.ncx",
        bytes(
            "<?xml version='1.0'?><ncx"
                + " xmlns='http://www.daisy.org/z3986/2005/ncx/'><navMap><navPoint><navLabel><text>上卷</text></navLabel><content"
                + " src='../Text/a.xhtml#x'/><navPoint><navLabel><text>下卷</text></navLabel><content"
                + " src='../Text/b.xhtml'/></navPoint></navPoint></navMap></ncx>"));
    EpubBook result = parser.inspect(new MemoryArchive(entries));
    assertEquals("上卷", result.spine.get(0).title);
    assertEquals("下卷", result.spine.get(1).title);
  }

  @Test
  public void relativeParentInsideArchiveIsAllowed() throws Exception {
    Map<String, byte[]> entries = book(item("a", "Text/../a.xhtml"), "<itemref idref='a'/>");
    entries.put("OPS/a.xhtml", html("正文", "根目录内的相对路径。"));
    assertEquals("根目录内的相对路径。", parser.parse(zip("relative.epub", entries)).chapters.get(0).text);
  }

  @Test
  public void pathCannotEscapeArchiveEvenWhenPercentEncoded() throws Exception {
    Map<String, byte[]> entries =
        book(item("a", "%2e%2e/%2e%2e/secret.xhtml"), "<itemref idref='a'/>");
    IOException error =
        assertThrows(IOException.class, () -> parser.inspect(new MemoryArchive(entries)));
    assertTrue(error.getMessage().contains("根目录"));
  }

  @Test
  public void maliciousArchiveEntryAndNormalizedDuplicatesAreRejected() throws Exception {
    Map<String, byte[]> traversal = singleBook();
    traversal.put("../outside.txt", bytes("不可读取"));
    assertThrows(IOException.class, () -> parser.parse(zip("traversal.epub", traversal)));
    Map<String, byte[]> duplicate = singleBook();
    duplicate.put("OPS/./a.xhtml", html("另一个", "重复条目"));
    IOException error =
        assertThrows(IOException.class, () -> parser.parse(zip("duplicate.epub", duplicate)));
    assertTrue(error.getMessage().contains("重复"));
  }

  @Test
  public void externalSpineIsRejectedWithoutNetworkAccess() throws Exception {
    MemoryArchive archive =
        new MemoryArchive(
            book(item("a", "https://example.invalid/a.xhtml"), "<itemref idref='a'/>"));
    assertThrows(IOException.class, () -> parser.inspect(archive));
    assertFalse(archive.opened.stream().anyMatch(path -> path.startsWith("https:")));
  }

  @Test
  public void invalidEncodedAndWindowsStylePathsAreRejected() throws Exception {
    for (String ref :
        Arrays.asList(
            "bad%xx.xhtml", "Text\\..\\a.xhtml", "/absolute.xhtml", "%2fabsolute.xhtml")) {
      assertThrows(
          ref,
          IOException.class,
          () -> parser.inspect(new MemoryArchive(book(item("a", ref), "<itemref idref='a'/>"))));
    }
  }

  @Test
  public void missingChapterFailsWholeImportRatherThanReturningHalfABook() throws Exception {
    Map<String, byte[]> entries =
        book(
            item("a", "a.xhtml") + item("b", "missing.xhtml"),
            "<itemref idref='a'/><itemref idref='b'/>");
    entries.put("OPS/a.xhtml", html("第一章", "完整正文"));
    IOException error =
        assertThrows(IOException.class, () -> parser.parse(zip("missing.epub", entries)));
    assertTrue(error.getMessage().contains("缺少文件"));
  }

  @Test
  public void missingSpineItemAndEmptySpineAreExplained() throws Exception {
    IOException missing =
        assertThrows(
            IOException.class,
            () ->
                parser.inspect(
                    new MemoryArchive(book(item("a", "a.xhtml"), "<itemref idref='absent'/>"))));
    assertTrue(missing.getMessage().contains("不存在的章节"));
    IOException empty =
        assertThrows(
            IOException.class,
            () -> parser.inspect(new MemoryArchive(book(item("a", "a.xhtml"), ""))));
    assertTrue(empty.getMessage().contains("阅读顺序为空"));
  }

  @Test
  public void duplicateManifestIdsAndFallbackCyclesAreRejected() throws Exception {
    assertThrows(
        IOException.class,
        () ->
            parser.inspect(
                new MemoryArchive(
                    book(item("a", "a.xhtml") + item("a", "b.xhtml"), "<itemref idref='a'/>"))));
    Map<String, byte[]> cycle =
        book(
            "<item id='a' href='a.svg' media-type='image/svg+xml' fallback='b'/>"
                + "<item id='b' href='b.svg' media-type='image/svg+xml' fallback='a'/>",
            "<itemref idref='a'/>");
    IOException error =
        assertThrows(IOException.class, () -> parser.inspect(new MemoryArchive(cycle)));
    assertTrue(error.getMessage().contains("循环"));
  }

  @Test
  public void textFallbackForUnsupportedMediaIsUsed() throws Exception {
    Map<String, byte[]> entries =
        book(
            "<item id='graphic' href='chapter.svg' media-type='image/svg+xml' fallback='text'/>"
                + item("text", "chapter.xhtml"),
            "<itemref idref='graphic'/>");
    assertEquals("OPS/chapter.xhtml", parser.inspect(new MemoryArchive(entries)).spine.get(0).path);
  }

  @Test
  public void drmIsRejectedButKnownFontObfuscationIsAllowed() throws Exception {
    Map<String, byte[]> entries = singleBook();
    entries.put(
        "META-INF/encryption.xml",
        bytes(encryption("http://www.idpf.org/2008/embedding", "OPS/font.otf")));
    assertEquals(1, parser.inspect(new MemoryArchive(entries)).spine.size());
    entries.put(
        "META-INF/encryption.xml",
        bytes(encryption("http://www.w3.org/2001/04/xmlenc#aes128-cbc", "OPS/a.xhtml")));
    IOException drm =
        assertThrows(IOException.class, () -> parser.inspect(new MemoryArchive(entries)));
    assertTrue(drm.getMessage().contains("DRM"));
    entries.put(
        "META-INF/encryption.xml",
        bytes(encryption("http://www.idpf.org/2008/embedding", "OPS/a.xhtml")));
    assertThrows(IOException.class, () -> parser.inspect(new MemoryArchive(entries)));
  }

  @Test
  public void rightsManifestAndCustomEntitiesAreRejected() throws Exception {
    Map<String, byte[]> entries = singleBook();
    entries.put("META-INF/rights.xml", bytes("<rights/>"));
    assertThrows(IOException.class, () -> parser.inspect(new MemoryArchive(entries)));
    entries.remove("META-INF/rights.xml");
    entries.put(
        "META-INF/container.xml",
        bytes(
            "<!DOCTYPE container [<!ENTITY secret SYSTEM"
                + " 'file:///not-readable'>]><container><rootfiles><rootfile"
                + " full-path='&secret;'/></rootfiles></container>"));
    IOException error =
        assertThrows(IOException.class, () -> parser.inspect(new MemoryArchive(entries)));
    assertTrue(error.getMessage().contains("实体声明"));
  }

  @Test
  public void optionalMetadataDoesNotHideTransportFailures() throws Exception {
    Map<String, byte[]> entries = singleBook();
    MemoryArchive broken =
        new MemoryArchive(entries) {
          @Override
          public InputStream open(String name) throws IOException {
            if ("META-INF/encryption.xml".equals(name)) throw new IOException("网络连接已断开");
            return super.open(name);
          }
        };
    IOException error = assertThrows(IOException.class, () -> parser.inspect(broken));
    assertEquals("网络连接已断开", error.getMessage());
    assertEquals(0, broken.activeStreams);
  }

  @Test
  public void metadataChapterCoverAndAggregateLimitsRejectWholeOperation() throws Exception {
    Map<String, byte[]> entries = singleBook();
    BookParser tinyMetadata = new BookParser(40, 1024, 4096, 1024);
    assertThrows(IOException.class, () -> tinyMetadata.inspect(new MemoryArchive(entries)));

    BookParser tinyChapter = new BookParser(2048, 30, 4096, 1024);
    MemoryArchive archive = new MemoryArchive(entries);
    assertThrows(
        IOException.class,
        () -> tinyChapter.readChapter(archive, new EpubBook.SpineItem("甲", "OPS/a.xhtml")));
    assertEquals(0, archive.activeStreams);

    BookParser tinyCover = new BookParser(2048, 2048, 4096, 3);
    archive.entries.put("cover.jpg", new byte[4]);
    EpubBook book = new EpubBook("书", "人", new ArrayList<>(), "cover.jpg");
    assertThrows(IOException.class, () -> tinyCover.readCover(archive, book));

    Map<String, byte[]> two =
        book(
            item("a", "a.xhtml") + item("b", "b.xhtml"),
            "<itemref idref='a'/><itemref idref='b'/>");
    two.put("OPS/a.xhtml", html("第一章", repeat("甲", 100)));
    two.put("OPS/b.xhtml", html("第二章", repeat("乙", 100)));
    BookParser tinyAggregate = new BookParser(2048, 1024, 500, 1024);
    assertThrows(IOException.class, () -> tinyAggregate.parse(zip("large-body.epub", two)));
  }

  @Test
  public void missingMetadataHasUsableFallbacks() throws Exception {
    Map<String, byte[]> entries = singleBook();
    entries.put(
        "OPS/book.opf",
        bytes(
            "<package><manifest>"
                + item("a", "a.xhtml")
                + "</manifest><spine><itemref idref='a'/></spine></package>"));
    BookDocument result = parser.parse(zip("没有元数据.epub", entries));
    assertEquals("没有元数据", result.title);
    assertEquals("作者未知", result.author);
  }

  @Test
  public void imageOnlyEpubExplainsItsLimitation() throws Exception {
    Map<String, byte[]> entries = singleBook();
    entries.put("OPS/a.xhtml", bytes("<html><body><img src='scan.jpg'/></body></html>"));
    IOException error =
        assertThrows(IOException.class, () -> parser.parse(zip("scanned.epub", entries)));
    assertTrue(error.getMessage().contains("纯图片"));
  }

  @Test
  public void inlinePicturesRetainTheirPositionAndAltDescription() throws Exception {
    MemoryArchive archive = new MemoryArchive(new LinkedHashMap<>());
    archive.entries.put(
        "a.xhtml",
        bytes("<html><body><p>上文</p><img src='a.png' alt='山川示意图'/>" + "<p>下文</p></body></html>"));
    String text = parser.readChapter(archive, new EpubBook.SpineItem("一章", "a.xhtml")).text;
    assertTrue(text.indexOf("上文") < text.indexOf("图片：山川示意图"));
    assertTrue(text.indexOf("图片：山川示意图") < text.indexOf("下文"));
  }

  @Test
  public void corruptZipAndNonFilesAreRejected() throws Exception {
    File file = write("broken.epub", bytes("这不是 ZIP"));
    assertThrows(IOException.class, () -> parser.parse(file));
    assertThrows(IOException.class, () -> parser.parse(temporary.getRoot()));
    assertThrows(
        IOException.class, () -> parser.parse(new File(temporary.getRoot(), "absent.epub")));
  }

  @Test
  public void oversizeLocalArchiveIsRejectedBeforeOpeningZip() throws Exception {
    File file = temporary.newFile("oversize.epub");
    try (RandomAccessFile sparse = new RandomAccessFile(file, "rw")) {
      sparse.setLength(256L * 1024 * 1024 + 1);
    }
    IOException error = assertThrows(IOException.class, () -> parser.parse(file));
    assertTrue(error.getMessage().contains("256 MiB"));
  }

  @Test
  public void utf8BomAndChineseTxtChaptersKeepAllText() throws Exception {
    byte[] data =
        concat(
            new byte[] {(byte) 0xef, (byte) 0xbb, (byte) 0xbf},
            bytes("卷首说明。\r\n\r\n第一章 初见\r\n　　中文正文一。\r\n\r\n第二章 重逢\r\n正文二。"));
    BookDocument result = parser.parse(write("中文书.txt", data));
    assertEquals("中文书", result.title);
    assertEquals(3, result.chapters.size());
    assertEquals("卷首说明。", result.chapters.get(0).text);
    assertEquals("第一章 初见", result.chapters.get(1).title);
    assertEquals("中文正文一。", result.chapters.get(1).text);
    assertEquals("正文二。", result.chapters.get(2).text);
  }

  @Test
  public void utf16BomSupportsLittleAndBigEndian() throws Exception {
    for (boolean little : new boolean[] {true, false}) {
      byte[] bom =
          little ? new byte[] {(byte) 0xff, (byte) 0xfe} : new byte[] {(byte) 0xfe, (byte) 0xff};
      Charset charset = little ? StandardCharsets.UTF_16LE : StandardCharsets.UTF_16BE;
      BookDocument result =
          parser.parse(
              write(
                  little ? "little.txt" : "big.txt",
                  concat(bom, "上善若水。\n\n水善利万物而不争。".getBytes(charset))));
      assertEquals("上善若水。\n\n水善利万物而不争。", result.chapters.get(0).text);
    }
  }

  @Test
  public void gb18030AndSupplementaryChineseCharactersSurvive() throws Exception {
    String text = "中文旧编码。𠀀字也应保留。";
    BookDocument result = parser.parse(write("旧书.txt", text.getBytes(Charset.forName("GB18030"))));
    assertEquals(text, result.chapters.get(0).text);
  }

  @Test
  public void invalidEncodingEmptyTxtAndBinaryContentAreRejected() throws Exception {
    assertThrows(
        IOException.class,
        () -> parser.parse(write("bad.txt", new byte[] {(byte) 0xff, 0, (byte) 0x80})));
    assertThrows(IOException.class, () -> parser.parse(write("empty.txt", bytes(" \n \t"))));
    assertThrows(
        IOException.class, () -> parser.parse(write("binary.txt", new byte[] {'x', 0, 'y'})));
    assertThrows(
        IOException.class,
        () -> new BookParser(100, 100, 5, 100).parse(write("large.txt", bytes("正文超过五字节"))));
  }

  @Test
  public void documentAndMetadataListsAreDefensiveCopies() {
    List<BookDocument.Chapter> chapters = new ArrayList<>();
    chapters.add(new BookDocument.Chapter("一", "正文"));
    byte[] image = {1};
    BookDocument result = new BookDocument("书", "人", chapters, image);
    chapters.clear();
    image[0] = 2;
    assertEquals(1, result.chapters.size());
    assertEquals(1, result.coverBytes[0]);
    assertThrows(UnsupportedOperationException.class, () -> result.chapters.clear());
    List<EpubBook.SpineItem> spine = new ArrayList<>();
    spine.add(new EpubBook.SpineItem("一", "a.xhtml"));
    EpubBook metadata = new EpubBook("书", "人", spine, null);
    spine.clear();
    assertEquals(1, metadata.spine.size());
    assertThrows(UnsupportedOperationException.class, () -> metadata.spine.clear());
  }

  private static Map<String, byte[]> singleBook() {
    Map<String, byte[]> entries = book(item("a", "a.xhtml"), "<itemref idref='a'/>");
    entries.put("OPS/a.xhtml", html("第一章", "这是独立编写的测试正文。"));
    return entries;
  }

  private static Map<String, byte[]> book(String manifest, String spine) {
    Map<String, byte[]> entries = new LinkedHashMap<>();
    entries.put("mimetype", bytes("application/epub+zip"));
    entries.put(
        "META-INF/container.xml",
        bytes(
            "<?xml version='1.0' encoding='UTF-8'?><container"
                + " xmlns='urn:oasis:names:tc:opendocument:xmlns:container'><rootfiles><rootfile"
                + " full-path='OPS/book.opf'"
                + " media-type='application/oebps-package+xml'/></rootfiles></container>"));
    entries.put(
        "OPS/book.opf",
        bytes(
            "<?xml version='1.0' encoding='UTF-8'?><package xmlns='http://www.idpf.org/2007/opf'"
                + " version='3.0'><metadata xmlns:dc='http://purl.org/dc/elements/1.1/'>"
                + "<dc:title>测试书籍</dc:title><dc:creator>作者甲</dc:creator><dc:creator>作者乙</dc:creator></metadata>"
                + "<manifest>"
                + manifest
                + "</manifest><spine>"
                + spine
                + "</spine></package>"));
    return entries;
  }

  private static String item(String id, String path) {
    return "<item id='" + id + "' href='" + path + "' media-type='application/xhtml+xml'/>";
  }

  private static byte[] html(String title, String body) {
    return bytes(
        "<?xml version='1.0' encoding='UTF-8'?><html xmlns='http://www.w3.org/1999/xhtml'>"
            + "<head><title>"
            + title
            + "</title></head><body><h1>"
            + title
            + "</h1><p>"
            + body
            + "</p></body></html>");
  }

  private static String encryption(String algorithm, String uri) {
    return "<encryption xmlns='urn:oasis:names:tc:opendocument:xmlns:container'>"
        + "<enc:EncryptedData xmlns:enc='http://www.w3.org/2001/04/xmlenc#'>"
        + "<enc:EncryptionMethod Algorithm='"
        + algorithm
        + "'/><enc:CipherData>"
        + "<enc:CipherReference URI='"
        + uri
        + "'/></enc:CipherData></enc:EncryptedData></encryption>";
  }

  private static byte[] bytes(String text) {
    return text.getBytes(StandardCharsets.UTF_8);
  }

  private static String repeat(String text, int count) {
    StringBuilder output = new StringBuilder();
    for (int i = 0; i < count; i++) output.append(text);
    return output.toString();
  }

  private static byte[] concat(byte[] a, byte[] b) {
    byte[] result = Arrays.copyOf(a, a.length + b.length);
    System.arraycopy(b, 0, result, a.length, b.length);
    return result;
  }

  private File write(String name, byte[] data) throws IOException {
    File file = temporary.newFile(name);
    Files.write(file.toPath(), data);
    return file;
  }

  private File zip(String name, Map<String, byte[]> entries) throws IOException {
    File result = temporary.newFile(name);
    try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(result.toPath()))) {
      for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
        zip.putNextEntry(new ZipEntry(entry.getKey()));
        zip.write(entry.getValue());
        zip.closeEntry();
      }
    }
    return result;
  }

  private static class MemoryArchive implements BookArchive {
    final Map<String, byte[]> entries;
    final List<String> opened = new ArrayList<>();
    int activeStreams;
    boolean closed;

    MemoryArchive(Map<String, byte[]> entries) {
      this.entries = entries;
    }

    @Override
    public InputStream open(String path) throws IOException {
      opened.add(path);
      byte[] bytes = entries.get(path);
      if (bytes == null) throw new FileNotFoundException(path);
      activeStreams++;
      return new FilterInputStream(new ByteArrayInputStream(bytes)) {
        boolean done;

        @Override
        public void close() throws IOException {
          if (!done) {
            done = true;
            activeStreams--;
          }
          super.close();
        }
      };
    }

    @Override
    public void close() {
      closed = true;
    }
  }
}
