package io.github.spudhero.shufangtv.cloud;

import static org.junit.Assert.*;

import io.github.spudhero.shufangtv.net.*;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONObject;
import org.junit.*;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 31)
public class CloudLibraryFlowTest {
  @Rule public TemporaryFolder temp = new TemporaryFolder();
  private final byte[] book = ("按需读取这本书。".repeat(18000)).getBytes(StandardCharsets.UTF_8);
  private LocalHttpFixture http;
  private MemoryAccounts accounts;
  private FakeProvider provider;
  private CloudLibrary clouds;
  private NetworkLibrary network;
  private SourceConfig source;
  private int fullRequests, rangeRequests;

  @Before
  public void prepare() throws Exception {
    http =
        new LocalHttpFixture(
            request -> {
              String range = request.headers.get("range");
              if (range == null) {
                fullRequests++;
                return new LocalHttpFixture.Reply(200, book);
              }
              rangeRequests++;
              String[] bounds = range.substring(6).split("-");
              int start = Integer.parseInt(bounds[0]), end = Integer.parseInt(bounds[1]);
              return new LocalHttpFixture.Reply(206, Arrays.copyOfRange(book, start, end + 1))
                  .header("Content-Range", "bytes " + start + "-" + end + "/" + book.length)
                  .header("ETag", "v1");
            });
    accounts = new MemoryAccounts();
    accounts.put(new CloudAccount("fixture", "fixture:user", "测试账号", new JSONObject()));
    provider = new FakeProvider();
    clouds = new CloudLibrary(accounts, Collections.singletonList(provider));
    network = new NetworkLibrary(clouds);
    source = new SourceConfig();
    source.type = SourceConfig.Type.CLOUD;
    source.accountId = "fixture:user";
    source.providerId = "fixture";
    source.root = "drive:folder/+opaque?";
  }

  @After
  public void close() throws Exception {
    http.close();
  }

  @Test
  public void opaqueIdsAndProviderPaginationReachTheReaderUnchanged() throws Exception {
    List<RemoteEntry> entries = network.list(source, "");
    assertEquals(source.root, provider.lastParent);
    assertEquals(2, entries.size());
    assertEquals(2, provider.pages.get());
    assertEquals("drive:book/+opaque?", entries.get(1).path);
    try (RemoteFile remote = network.open(source, entries.get(1).path)) {
      byte[] first = new byte[128];
      assertEquals(128, remote.read(0, first, 0, first.length));
      assertArrayEquals(Arrays.copyOf(book, 128), first);
    }
    assertEquals("drive:book/+opaque?", provider.lastFile);
    assertEquals(0, fullRequests);
    assertTrue(rangeRequests > 0);
  }

  @Test
  public void onlyExplicitOfflineActionCreatesAFullLocalCopy() throws Exception {
    File target = new File(temp.getRoot(), "book.txt");
    try (RemoteFile remote = network.open(source, "book")) {
      remote.read(5, new byte[10], 0, 10);
      assertFalse(target.exists());
      assertEquals(0, fullRequests);
    }
    network.download(source, "book", target, null);
    assertArrayEquals(book, Files.readAllBytes(target.toPath()));
    assertEquals(1, fullRequests);
    clouds.logout(source.accountId);
    try {
      network.open(source, "book");
      fail();
    } catch (IOException expected) {
      assertTrue(expected.getMessage().contains("重新扫码"));
    }
    assertArrayEquals(book, Files.readAllBytes(target.toPath()));
  }

  @Test
  public void cancellingOfflineSavePreservesAnExistingLocalCopy() throws Exception {
    File target = temp.newFile("book.txt");
    Files.write(target.toPath(), new byte[] {1, 2, 3});
    try {
      network.download(
          source,
          "book",
          target,
          (received, total) -> {
            if (received >= 64 * 1024) throw new IOException("cancel");
          });
      fail();
    } catch (IOException expected) {
      assertTrue(expected.getMessage().contains("取消"));
    }
    assertArrayEquals(new byte[] {1, 2, 3}, Files.readAllBytes(target.toPath()));
    assertEquals(1, Objects.requireNonNull(temp.getRoot().list()).length);
  }

  @Test
  public void repeatedPageTokensFailWithoutLoopingOrReturningAnIncompleteLibrary()
      throws Exception {
    provider.repeatCursor = true;
    try {
      network.list(source, "");
      fail();
    } catch (IOException expected) {
      assertTrue(expected.getMessage().contains("文件夹"));
    }
    assertEquals(2, provider.pages.get());
  }

  @Test
  public void aSourceCannotUseAnotherProvidersAccount() throws Exception {
    source.providerId = "other";
    try {
      network.open(source, "book");
      fail();
    } catch (IOException expected) {
      assertTrue(expected.getMessage().contains("不匹配"));
    }
    assertEquals(0, fullRequests + rangeRequests);
  }

  @Test
  public void lateDirectoryResponseCannotRecreateALoggedOutAccount() throws Exception {
    provider.duringList = () -> clouds.logout(source.accountId);
    try {
      network.list(source, "");
      fail();
    } catch (IOException expected) {
      assertTrue(expected.getMessage().contains("退出"));
    }
    assertTrue(clouds.accounts().isEmpty());
  }

  @Test
  public void lateDirectoryResponseCannotOverwriteANewerLogin() throws Exception {
    CloudAccount replacement =
        new CloudAccount("fixture", source.accountId, "新会话", new JSONObject());
    provider.duringList = () -> clouds.save(replacement);
    try {
      network.list(source, "");
      fail();
    } catch (IOException expected) {
      assertTrue(expected.getMessage().contains("重新授权"));
    }
    assertSame(replacement, clouds.account(source.accountId));
  }

  @Test
  public void loggingOutAlsoStopsReadersHoldingAnUnexpiredLink() throws Exception {
    try (RemoteFile file = network.open(source, "book")) {
      int before = rangeRequests;
      clouds.logout(source.accountId);
      try {
        file.read(20, new byte[10], 0, 10);
        fail();
      } catch (IOException expected) {
        assertTrue(expected.getMessage().contains("退出"));
      }
      assertEquals(before, rangeRequests);
    }
  }

  private final class FakeProvider implements CloudProvider {
    String lastParent, lastFile;
    boolean repeatCursor;
    AtomicInteger pages = new AtomicInteger();
    Checked duringList;

    public String id() {
      return "fixture";
    }

    public String label() {
      return "测试网盘";
    }

    public String rootId(CloudAccount a) {
      return "root";
    }

    public LoginChallenge beginLogin() {
      throw new UnsupportedOperationException();
    }

    public LoginResult pollLogin(LoginChallenge c) {
      throw new UnsupportedOperationException();
    }

    public ListPage list(CloudAccount a, String parent, String cursor, boolean directoriesOnly)
        throws IOException {
      lastParent = parent;
      pages.incrementAndGet();
      if (duringList != null) duringList.run();
      return new ListPage(
          Collections.singletonList(
              new Entry(
                  cursor.isEmpty() ? "folder" : "drive:book/+opaque?",
                  parent,
                  cursor.isEmpty() ? "书籍" : "长篇.txt",
                  cursor.isEmpty(),
                  book.length)),
          repeatCursor || cursor.isEmpty() ? "next" : "");
    }

    public DownloadLink resolve(CloudAccount a, String file) {
      lastFile = file;
      return new DownloadLink(
          http.origin() + "/book", Collections.emptyMap(), book.length, "book-v1", 0);
    }
  }

  private interface Checked {
    void run() throws IOException;
  }

  public static final class MemoryAccounts implements CloudLibrary.AccountRepository {
    final Map<String, CloudAccount> values = new LinkedHashMap<>();

    public List<CloudAccount> all() {
      return new ArrayList<>(values.values());
    }

    public CloudAccount find(String id) {
      return values.get(id);
    }

    public void put(CloudAccount a) {
      values.put(a.id, a);
    }

    public void remove(String id) {
      values.remove(id);
    }
  }
}
