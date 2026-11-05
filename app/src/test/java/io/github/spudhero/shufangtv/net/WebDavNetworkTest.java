package io.github.spudhero.shufangtv.net;

import static org.junit.Assert.*;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public class WebDavNetworkTest {
  @Rule public TemporaryFolder temporary = new TemporaryFolder();
  private final NetworkLibrary library = new NetworkLibrary();

  private static SourceConfig source(LocalHttpFixture server) {
    SourceConfig config = new SourceConfig();
    config.type = SourceConfig.Type.WEBDAV;
    config.host = server.origin() + "/dav";
    config.root = "书籍";
    return config;
  }

  private static LocalHttpFixture.Reply fileReply(LocalHttpFixture.Incoming request, byte[] book) {
    if (request.method.equals("HEAD"))
      return new LocalHttpFixture.Reply(200, new byte[0])
          .header("Content-Length", Integer.toString(book.length))
          .header("ETag", "\"book-v1\"");
    String range = request.headers.get("range");
    if (range == null) return new LocalHttpFixture.Reply(200, book);
    String[] parts = range.substring("bytes=".length()).split("-");
    int start = Integer.parseInt(parts[0]),
        end = Math.min(book.length - 1, Integer.parseInt(parts[1]));
    return new LocalHttpFixture.Reply(206, Arrays.copyOfRange(book, start, end + 1))
        .header("Content-Range", "bytes " + start + "-" + end + "/" + book.length)
        .header("ETag", "\"book-v1\"");
  }

  @Test
  public void onlineReadsOnlyRequestedRangesNeverWholeBookOrDisk() throws Exception {
    byte[] book = new byte[4 * 1024 * 1024];
    for (int n = 0; n < book.length; n++) book[n] = (byte) (n * 31);
    AtomicInteger served = new AtomicInteger();
    try (LocalHttpFixture server =
        new LocalHttpFixture(
            request -> {
              LocalHttpFixture.Reply reply = fileReply(request, book);
              if (!request.method.equals("HEAD")) served.addAndGet(reply.bytes.length);
              return reply;
            })) {
      try (RemoteFile remote = library.open(source(server), "传习录.epub")) {
        assertEquals(book.length, remote.length());
        assertEquals(1, served.get());
        byte[] bytes = new byte[512 * 1024];
        assertEquals(200, remote.read(book.length - 200, bytes, 3, 300));
        assertArrayEquals(
            Arrays.copyOfRange(book, book.length - 200, book.length),
            Arrays.copyOfRange(bytes, 3, 203));
        assertEquals(NetworkPaths.MAX_READ, remote.read(10, bytes, 0, bytes.length));
        assertEquals(-1, remote.read(book.length, bytes, 0, 4));
        assertEquals(0, remote.read(book.length, bytes, 0, 0));
        assertTrue(served.get() < 300_000);
      }
      assertEquals(0, temporary.getRoot().list().length);
      for (LocalHttpFixture.Incoming request : server.requests) {
        if (request.method.equals("GET")) assertNotNull(request.headers.get("range"));
        assertTrue(request.path.contains("%E4%BC%A0%E4%B9%A0%E5%BD%95.epub"));
      }
    }
  }

  @Test
  public void serverIgnoringRangeCannotSilentlyDownload() throws Exception {
    try (LocalHttpFixture server =
        new LocalHttpFixture(
            request ->
                request.method.equals("HEAD")
                    ? new LocalHttpFixture.Reply(200, new byte[0])
                        .header("Content-Length", "9000000")
                    : new LocalHttpFixture.Reply(200, new byte[100]))) {
      IOException failure =
          assertThrows(IOException.class, () -> library.open(source(server), "book.epub"));
      assertTrue(failure.getMessage().contains("不支持按需读取"));
      assertEquals(2, server.requests.size());
    }
  }

  @Test
  public void missingHeadSupportUsesOnlyOneByteProbe() throws Exception {
    byte[] book = "中文内容 abc".getBytes(StandardCharsets.UTF_8);
    try (LocalHttpFixture server =
        new LocalHttpFixture(
            request ->
                request.method.equals("HEAD")
                    ? LocalHttpFixture.Reply.text(405, "")
                    : fileReply(request, book))) {
      try (RemoteFile remote = library.open(source(server), "book.txt")) {
        assertEquals(book.length, remote.length());
      }
      assertEquals(2, server.requests.size());
      assertEquals("bytes=0-0", server.requests.get(1).headers.get("range"));
    }
  }

  @Test
  public void rejectsWrongContentRangeAndChangedFile() throws Exception {
    for (String badRange : new String[] {"bytes 1-1/10", "bytes 0-0/*", "bytes 0-0/11"}) {
      try (LocalHttpFixture server =
          new LocalHttpFixture(
              request ->
                  request.method.equals("HEAD")
                      ? new LocalHttpFixture.Reply(200, new byte[0]).header("Content-Length", "10")
                      : new LocalHttpFixture.Reply(206, new byte[] {1})
                          .header("Content-Range", badRange))) {
        assertThrows(IOException.class, () -> library.open(source(server), "book.epub"));
        assertEquals(2, server.requests.size());
      }
    }
  }

  @Test
  public void rejectsChangedEtagBeforeReturningBytes() throws Exception {
    try (LocalHttpFixture server =
        new LocalHttpFixture(
            request ->
                request.method.equals("HEAD")
                    ? new LocalHttpFixture.Reply(200, new byte[0])
                        .header("Content-Length", "10")
                        .header("ETag", "\"one\"")
                    : new LocalHttpFixture.Reply(206, new byte[] {1})
                        .header("Content-Range", "bytes 0-0/10")
                        .header("ETag", "\"two\""))) {
      assertThrows(IOException.class, () -> library.open(source(server), "book.epub"));
      assertEquals("\"one\"", server.requests.get(1).headers.get("if-match"));
    }
  }

  @Test
  public void laterReadAlsoRefusesFullResponseWithoutFallback() throws Exception {
    AtomicInteger gets = new AtomicInteger();
    byte[] book = new byte[10_000];
    try (LocalHttpFixture server =
        new LocalHttpFixture(
            request -> {
              if (request.method.equals("GET") && gets.incrementAndGet() > 1)
                return new LocalHttpFixture.Reply(200, book);
              return fileReply(request, book);
            })) {
      try (RemoteFile remote = library.open(source(server), "book.epub")) {
        assertThrows(IOException.class, () -> remote.read(100, new byte[100], 0, 100));
      }
      assertEquals(2, gets.get());
      assertEquals(3, server.requests.size());
      assertEquals("bytes=100-199", server.requests.get(2).headers.get("range"));
    }
  }

  @Test
  public void transientRangeReadRetriesOnlyTheSameBoundedSlice() throws Exception {
    AtomicInteger reads = new AtomicInteger();
    byte[] book = new byte[1_000_000];
    try (LocalHttpFixture server =
        new LocalHttpFixture(
            request -> {
              if ("bytes=100-199".equals(request.headers.get("range"))
                  && reads.incrementAndGet() == 1) return LocalHttpFixture.Reply.text(503, "");
              return fileReply(request, book);
            })) {
      try (RemoteFile remote = library.open(source(server), "book.epub")) {
        assertEquals(100, remote.read(100, new byte[100], 0, 100));
      }
      assertEquals(2, reads.get());
      assertEquals(4, server.requests.size());
      assertEquals("bytes=100-199", server.requests.get(2).headers.get("range"));
      assertEquals("bytes=100-199", server.requests.get(3).headers.get("range"));
    }
  }

  @Test
  public void directoryCanBeBrowsedAfterPreviousRequestFailed() throws Exception {
    AtomicInteger attempts = new AtomicInteger();
    try (LocalHttpFixture server =
        new LocalHttpFixture(
            request ->
                attempts.incrementAndGet() <= 2
                    ? LocalHttpFixture.Reply.text(503, "")
                    : LocalHttpFixture.Reply.text(207, "<d:multistatus xmlns:d=\"DAV:\"/>"))) {
      assertThrows(IOException.class, () -> library.list(source(server), ""));
      assertTrue(library.list(source(server), "").isEmpty());
      assertEquals(3, attempts.get());
    }
  }

  @Test
  public void directoryPreservesUnicodeAndRejectsOffOriginTraversalAndNestedHrefs()
      throws Exception {
    String base = "/dav/%E4%B9%A6%E7%B1%8D/";
    String xml =
        "<?xml version=\"1.0\"?><d:multistatus xmlns:d=\"DAV:\">"
            + entry(base, true, 0)
            + entry(base + "%E5%8F%A4%E7%B1%8D/", true, 0)
            + entry(base + "%E4%BC%A0%E4%B9%A0%E5%BD%95%20%2B%20%E6%B3%A8.epub", false, 123)
            + entry(base + "A%26B.txt", false, 5)
            + entry("http://other.invalid/book.epub", false, 10)
            + entry(base + "%2e%2e/secret.txt", false, 10)
            + entry(base + "bad%2Fname.epub", false, 10)
            + entry(base + "nested/deeper.epub", false, 10)
            + "</d:multistatus>";
    try (LocalHttpFixture server =
        new LocalHttpFixture(request -> LocalHttpFixture.Reply.text(207, xml))) {
      List<RemoteEntry> entries = library.list(source(server), "");
      assertEquals(3, entries.size());
      assertEquals("古籍", entries.get(0).name);
      assertTrue(entries.get(0).directory);
      assertEquals("传习录 + 注.epub", entries.get(2).name);
      assertEquals(123, entries.get(2).size);
      assertEquals("1", server.requests.get(0).headers.get("depth"));
      assertEquals(base, server.requests.get(0).path);
    }
  }

  @Test
  public void basicAuthenticationAndPermanentFailureAreNotRetried() throws Exception {
    try (LocalHttpFixture server =
        new LocalHttpFixture(request -> LocalHttpFixture.Reply.text(401, "server private error"))) {
      SourceConfig config = source(server);
      config.username = "读者";
      config.password = "example-secret";
      IOException failure = assertThrows(IOException.class, () -> library.list(config, ""));
      assertEquals(1, server.requests.size());
      assertFalse(failure.getMessage().contains("secret"));
      String expected =
          "Basic "
              + Base64.getEncoder()
                  .encodeToString("读者:example-secret".getBytes(StandardCharsets.UTF_8));
      assertEquals(expected, server.requests.get(0).headers.get("authorization"));
    }
  }

  @Test
  public void redirectsAreNeverFollowedWithCredentials() throws Exception {
    AtomicInteger destination = new AtomicInteger();
    try (LocalHttpFixture attacker =
            new LocalHttpFixture(
                request -> {
                  destination.incrementAndGet();
                  return LocalHttpFixture.Reply.text(200, "");
                });
        LocalHttpFixture server =
            new LocalHttpFixture(
                request ->
                    LocalHttpFixture.Reply.text(302, "")
                        .header("Location", attacker.origin() + "/capture"))) {
      SourceConfig config = source(server);
      config.username = "reader";
      config.password = "example";
      assertThrows(IOException.class, () -> library.list(config, ""));
      assertEquals(1, server.requests.size());
      assertEquals(0, destination.get());
    }
  }

  @Test
  public void transientReadOnlyRequestRetriesOnceWithFreshRequest() throws Exception {
    AtomicInteger attempts = new AtomicInteger();
    try (LocalHttpFixture server =
        new LocalHttpFixture(
            request ->
                attempts.incrementAndGet() == 1
                    ? LocalHttpFixture.Reply.text(503, "")
                    : LocalHttpFixture.Reply.text(207, "<d:multistatus xmlns:d=\"DAV:\"/>"))) {
      assertTrue(library.list(source(server), "").isEmpty());
      assertEquals(2, attempts.get());
    }
  }

  @Test
  public void explicitOfflineDownloadIsAtomicAndCancellationKeepsPriorTarget() throws Exception {
    byte[] book = new byte[300_000];
    Arrays.fill(book, (byte) 42);
    try (LocalHttpFixture server = new LocalHttpFixture(request -> fileReply(request, book))) {
      File target = temporary.newFile("book.epub");
      Files.write(target.toPath(), new byte[] {7});
      assertThrows(
          IOException.class,
          () ->
              library.download(
                  source(server),
                  "book.epub",
                  target,
                  (received, total) -> {
                    if (received > 0) throw new IOException("cancel");
                  }));
      assertArrayEquals(new byte[] {7}, Files.readAllBytes(target.toPath()));
      assertEquals(1, server.requests.size());
      assertEquals(1, temporary.getRoot().list().length);
      library.download(source(server), "book.epub", target, null);
      assertArrayEquals(book, Files.readAllBytes(target.toPath()));
      assertEquals(1, temporary.getRoot().list().length);
      assertNull(server.requests.get(1).headers.get("range"));
    }
  }

  @Test
  public void oversizedOfflineFileIsRejectedWithoutInstallingPartial() throws Exception {
    try (LocalHttpFixture server =
        new LocalHttpFixture(
            request ->
                new LocalHttpFixture.Reply(200, new byte[0])
                    .header("Content-Length", Long.toString(NetworkPaths.MAX_DOWNLOAD + 1)))) {
      File target = new File(temporary.getRoot(), "book.epub");
      assertThrows(
          IOException.class, () -> library.download(source(server), "book.epub", target, null));
      assertFalse(target.exists());
      assertEquals(0, temporary.getRoot().list().length);
    }
  }

  @Test
  public void xmlEntityDeclarationsAreRejected() throws Exception {
    String xml =
        "<!DOCTYPE d:multistatus [<!ENTITY local SYSTEM \"file:///etc/passwd\">]>"
            + "<d:multistatus xmlns:d=\"DAV:\">&local;</d:multistatus>";
    try (LocalHttpFixture server =
        new LocalHttpFixture(request -> LocalHttpFixture.Reply.text(207, xml))) {
      assertThrows(IOException.class, () -> library.list(source(server), ""));
      assertEquals(1, server.requests.size());
    }
  }

  @Test
  public void closedRemoteFileDoesNotMakeAnotherRequest() throws Exception {
    try (LocalHttpFixture server =
        new LocalHttpFixture(request -> fileReply(request, new byte[100]))) {
      RemoteFile remote = library.open(source(server), "book.epub");
      remote.close();
      assertThrows(IOException.class, () -> remote.read(0, new byte[5], 0, 5));
      assertEquals(2, server.requests.size());
    }
  }

  private static String entry(String href, boolean directory, long size) {
    return "<d:response><d:href>"
        + href.replace("&", "&amp;")
        + "</d:href><d:propstat><d:prop><d:resourcetype>"
        + (directory ? "<d:collection/>" : "")
        + "</d:resourcetype><d:getcontentlength>"
        + size
        + "</d:getcontentlength></d:prop><d:status>HTTP/1.1 200"
        + " OK</d:status></d:propstat></d:response>";
  }
}
