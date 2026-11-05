package io.github.spudhero.shufangtv.net;

import static org.junit.Assert.*;

import java.io.EOFException;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.SocketException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.Test;

public class NetworkPathsTest {
  @Test
  public void smbConfigUsesIndependentValidatedSnapshot() throws Exception {
    SourceConfig original = new SourceConfig();
    original.host = "smb://192.0.2.1:1445";
    original.share = "书库";
    original.root = "/中文/古籍/";
    SourceConfig snapshot = NetworkPaths.snapshot(original);
    original.host = "changed.invalid";
    assertEquals("192.0.2.1", snapshot.host);
    assertEquals(1445, snapshot.port);
    assertEquals("中文/古籍", snapshot.root);
  }

  @Test
  public void refusesTraversalAndEncodedSeparators() {
    for (String path :
        new String[] {
          "../secret",
          "folder/../secret",
          "/root",
          "folder//book",
          "a\\b",
          "a/%2e%2e/b",
          "a/b%2fc",
          "a\u0000b"
        }) {
      assertThrows(path, IOException.class, () -> NetworkPaths.relative(path));
    }
  }

  @Test
  public void davRootDoesNotAcceptUserInfoOrParentPath() {
    for (String host :
        new String[] {
          "http://user:password@example.invalid/",
          "http://example.invalid/a/../b",
          "http://example.invalid/a/%2e%2e/b",
          "http://example.invalid/a%2fb",
          "https://example.invalid/?secret=x"
        }) {
      SourceConfig config = new SourceConfig();
      config.type = SourceConfig.Type.WEBDAV;
      config.host = host;
      assertThrows(host, IOException.class, () -> new WebDavEndpoint(config));
    }
  }

  @Test
  public void literalPlusAndPercentAreEncodedOnce() throws Exception {
    SourceConfig config = new SourceConfig();
    config.type = SourceConfig.Type.WEBDAV;
    config.host = "https://example.invalid/dav/";
    config.root = "中文";
    WebDavEndpoint endpoint = new WebDavEndpoint(NetworkPaths.snapshot(config));
    assertTrue(endpoint.url("A+B 100%.epub", false).encodedPath().endsWith("A+B%20100%25.epub"));
  }

  @Test
  public void retryIsBoundedAndHonorsCancellation() throws Exception {
    AtomicInteger attempts = new AtomicInteger();
    assertThrows(
        IOException.class,
        () ->
            NetworkRetry.run(
                () -> {
                  attempts.incrementAndGet();
                  throw new SocketException();
                }));
    assertEquals(2, attempts.get());
    attempts.set(0);
    assertThrows(
        IOException.class,
        () ->
            NetworkRetry.run(
                () -> {
                  attempts.incrementAndGet();
                  throw new NetworkFailure("账号错误", false);
                }));
    assertEquals(1, attempts.get());
    assertFalse(NetworkRetry.retryable(new InterruptedIOException()));
    assertTrue(NetworkRetry.retryable(new EOFException()));
  }

  @Test
  public void rangeReadBoundsAndEOFDoNotOverflow() throws Exception {
    byte[] bytes = new byte[512 * 1024];
    assertEquals(
        NetworkPaths.MAX_READ, NetworkPaths.readLength(1, Long.MAX_VALUE, bytes, 0, bytes.length));
    assertEquals(1, NetworkPaths.readLength(Long.MAX_VALUE - 1, Long.MAX_VALUE, bytes, 0, 100));
    assertEquals(-1, NetworkPaths.readLength(100, 100, bytes, 0, 1));
    assertThrows(
        IndexOutOfBoundsException.class,
        () -> NetworkPaths.readLength(0, 100, bytes, Integer.MAX_VALUE, 3));
    assertThrows(IOException.class, () -> NetworkPaths.readLength(-1, 100, bytes, 0, 3));
  }
}
