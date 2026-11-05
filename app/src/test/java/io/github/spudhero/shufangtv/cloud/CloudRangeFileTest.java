package io.github.spudhero.shufangtv.cloud;

import static org.junit.Assert.*;

import io.github.spudhero.shufangtv.net.LocalHttpFixture;
import io.github.spudhero.shufangtv.net.LocalHttpFixture.Reply;
import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class CloudRangeFileTest {
  private LocalHttpFixture server;
  private String url;
  private final byte[] data = new byte[600 * 1024];
  private final AtomicReference<String> mode = new AtomicReference<>("normal");
  private final AtomicReference<String> version = new AtomicReference<>("\"v1\"");
  private final AtomicInteger requests = new AtomicInteger();

  @Before
  public void start() throws Exception {
    for (int i = 0; i < data.length; i++) data[i] = (byte) (i * 31);
    server =
        new LocalHttpFixture(
            request ->
                request.path.equals("/expired") ? new Reply(403, new byte[0]) : serve(request));
    url = server.origin() + "/book";
  }

  @After
  public void stop() throws Exception {
    server.close();
  }

  private CloudProvider.DownloadLink link(String path, long size, String identity) {
    return new CloudProvider.DownloadLink(
        path, Collections.emptyMap(), size, identity, System.currentTimeMillis() + 300000);
  }

  private CloudRangeFile open() throws IOException {
    return new CloudRangeFile(() -> link(url, data.length, "file-v1"));
  }

  @Test
  public void onlineReadsArePositionedAndBoundedTo256KiB() throws Exception {
    try (CloudRangeFile file = open()) {
      assertEquals(data.length, file.length());
      byte[] result = new byte[400 * 1024];
      assertEquals(256 * 1024, file.read(123, result, 0, result.length));
      assertArrayEquals(
          Arrays.copyOfRange(data, 123, 123 + 256 * 1024), Arrays.copyOf(result, 256 * 1024));
      assertEquals(-1, file.read(data.length, result, 0, 1));
      assertEquals(2, requests.get());
    }
  }

  @Test
  public void fullResponseCannotSilentlyBecomeOnlineDownload() throws Exception {
    mode.set("ignore");
    expectFailure(this::open, "按需读取");
    assertEquals(1, requests.get());
  }

  @Test
  public void contentRangeMustMatchRequestedOffset() throws Exception {
    try (CloudRangeFile file = open()) {
      mode.set("offset");
      expectFailure(() -> file.read(200, new byte[10], 0, 10), "读取范围");
    }
  }

  @Test
  public void changingTotalCannotMixVersions() throws Exception {
    try (CloudRangeFile file = open()) {
      mode.set("total");
      expectFailure(() -> file.read(200, new byte[10], 0, 10), "书籍长度");
    }
  }

  @Test
  public void changingEtagCannotMixVersions() throws Exception {
    try (CloudRangeFile file = open()) {
      version.set("\"v2\"");
      expectFailure(() -> file.read(10, new byte[10], 0, 10), "已经更新");
    }
  }

  @Test
  public void oversizedRangeBodyIsRejected() throws Exception {
    try (CloudRangeFile file = open()) {
      mode.set("extra");
      expectFailure(() -> file.read(10, new byte[10], 0, 10), "内容长度");
    }
  }

  @Test
  public void expiredSignedAddressIsResolvedAgainOnce() throws Exception {
    AtomicInteger resolved = new AtomicInteger();
    try (CloudRangeFile file =
        new CloudRangeFile(
            () ->
                link(
                    resolved.getAndIncrement() == 0 ? url.replace("/book", "/expired") : url,
                    data.length,
                    "file-v1"))) {
      assertEquals(data.length, file.length());
      assertEquals(2, resolved.get());
    }
  }

  @Test
  public void refreshCannotSwitchToAnotherFile() throws Exception {
    AtomicInteger resolved = new AtomicInteger();
    try (CloudRangeFile file =
        new CloudRangeFile(
            () -> link(url, data.length, resolved.getAndIncrement() == 0 ? "v1" : "v2"))) {
      mode.set("forbidden");
      expectFailure(() -> file.read(20, new byte[10], 0, 10), "已经更新");
      assertEquals(2, resolved.get());
    }
  }

  @Test
  public void crossOriginRedirectDropsCredentialsAndKeepsRange() throws Exception {
    AtomicReference<String> cookie = new AtomicReference<>(),
        auth = new AtomicReference<>(),
        range = new AtomicReference<>();
    try (LocalHttpFixture destination =
        new LocalHttpFixture(
            e -> {
              cookie.set(e.headers.get("cookie"));
              auth.set(e.headers.get("authorization"));
              range.set(e.headers.get("range"));
              return new Reply(206, new byte[] {data[0]})
                  .header("Content-Range", "bytes 0-0/" + data.length);
            })) {
      server.handler =
          e -> new Reply(302, new byte[0]).header("Location", destination.origin() + "/target");
      Map<String, String> headers = new java.util.HashMap<>();
      headers.put("Cookie", "session=fixture");
      headers.put("Authorization", "Bearer fixture");
      try (CloudRangeFile ignored =
          new CloudRangeFile(
              () -> new CloudProvider.DownloadLink(url, headers, data.length, "v1", 0))) {
        assertNull(cookie.get());
        assertNull(auth.get());
        assertEquals("bytes=0-0", range.get());
      }
    }
  }

  @Test
  public void closedReaderDoesNotMakeFurtherRequests() throws Exception {
    CloudRangeFile file = open();
    file.close();
    expectFailure(() -> file.read(10, new byte[1], 0, 1), "已关闭");
    assertEquals(1, requests.get());
  }

  @Test
  public void embeddedUrlCredentialsAreRejectedBeforeNetwork() throws Exception {
    expectFailure(
        () ->
            new CloudRangeFile(
                () -> link(url.replace("http://", "http://user:pass@"), data.length, "v1")),
        "连接地址");
    assertEquals(0, requests.get());
  }

  @Test
  public void closingDuringANetworkReadCancelsWithoutWaitingForTheReadLock() throws Exception {
    CloudRangeFile file = open();
    java.util.concurrent.CountDownLatch arrived = new java.util.concurrent.CountDownLatch(1);
    java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
    java.util.concurrent.ExecutorService worker =
        java.util.concurrent.Executors.newSingleThreadExecutor();
    server.handler =
        e -> {
          arrived.countDown();
          try {
            release.await(5, java.util.concurrent.TimeUnit.SECONDS);
          } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
          }
          return serve(e);
        };
    try {
      java.util.concurrent.Future<?> read =
          worker.submit(
              () -> {
                try {
                  file.read(10, new byte[10], 0, 10);
                  fail("Closed transfer should be cancelled");
                } catch (IOException expected) {
                  /* Cancellation is observable by the reader. */
                }
              });
      assertTrue(arrived.await(2, java.util.concurrent.TimeUnit.SECONDS));
      long started = System.nanoTime();
      file.close();
      assertTrue(
          "Closing must not wait for the synchronized read",
          System.nanoTime() - started < 500_000_000L);
      read.get(2, java.util.concurrent.TimeUnit.SECONDS);
    } finally {
      release.countDown();
      worker.shutdownNow();
      file.close();
    }
  }

  private Reply serve(LocalHttpFixture.Incoming e) throws IOException {
    requests.incrementAndGet();
    if (mode.get().equals("forbidden")) return new Reply(403, new byte[0]);
    if (mode.get().equals("ignore")) return new Reply(200, data);
    String range = e.headers.get("range");
    if (range == null) return new Reply(400, new byte[0]);
    String[] parts = range.substring(6).split("-");
    int start = Integer.parseInt(parts[0]), end = Integer.parseInt(parts[1]);
    int declaredStart = mode.get().equals("offset") ? start + 1 : start;
    int total = mode.get().equals("total") ? data.length + 1 : data.length;
    byte[] body = Arrays.copyOfRange(data, start, end + 1);
    if (mode.get().equals("extra")) body = Arrays.copyOf(body, body.length + 1);
    return new Reply(206, body)
        .header("Content-Range", "bytes " + declaredStart + "-" + end + "/" + total)
        .header("ETag", version.get());
  }

  private interface Checked {
    void run() throws Exception;
  }

  private static void expectFailure(Checked action, String message) throws Exception {
    try {
      action.run();
      fail("Expected IOException");
    } catch (IOException expected) {
      assertTrue(expected.getMessage(), expected.getMessage().contains(message));
    }
  }
}
