package io.github.spudhero.shufangtv.cloud.provider;

import static org.junit.Assert.*;

import io.github.spudhero.shufangtv.cloud.CloudAccount;
import io.github.spudhero.shufangtv.cloud.CloudHttp;
import io.github.spudhero.shufangtv.cloud.CloudProvider;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.List;
import okhttp3.Cookie;
import okhttp3.FormBody;
import okhttp3.HttpUrl;
import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 31, manifest = Config.NONE)
public class QuarkUcProviderTest {
  private static final String TOKEN = "0123456789abcdef0123456789abcdef";
  private static final String BEGIN =
      "{\"status\":2000000,\"data\":{\"members\":{\"token\":\"" + TOKEN + "\"}}}";
  private static final String TICKET =
      "{\"status\":2000000,\"data\":{\"members\":{\"service_ticket\":\"fixture-ticket\"}}}";
  private static final String ACCOUNT =
      "{\"success\":true,\"data\":{\"uid\":\"user_42\",\"nickname\":\"书房读者\"}}";
  private static final String CONFIG = "{\"status\":200,\"code\":0,\"data\":{}}";

  @Test
  public void quarkQrUsesFirstPartyGetAndPublicWebClient() throws Exception {
    Script script = new Script(BEGIN);
    QuarkUcProvider provider = script.provider(false);
    CloudProvider.LoginChallenge challenge = provider.beginLogin();
    Request request = script.requests.get(0);
    assertEquals("GET", request.method());
    assertEquals("uop.quark.cn", request.url().host());
    assertEquals("/cas/ajax/getTokenForQrcodeLogin", request.url().encodedPath());
    assertEquals("532", request.url().queryParameter("client_id"));
    assertEquals("1.2", request.url().queryParameter("v"));
    assertNotNull(request.url().queryParameter("request_id"));
    HttpUrl qr = HttpUrl.get(challenge.qrUrl);
    assertEquals("su.quark.cn", qr.host());
    assertEquals("/4_eMHBJ", qr.encodedPath());
    assertEquals(TOKEN, qr.queryParameter("token"));
    assertEquals(2_000L, challenge.pollIntervalMs);
    assertTrue(challenge.account.id.startsWith("pending-"));
  }

  @Test
  public void ucQrUsesFirstPartyPostForm() throws Exception {
    Script script = new Script(BEGIN);
    CloudProvider.LoginChallenge challenge = script.provider(true).beginLogin();
    Request request = script.requests.get(0);
    assertEquals("POST", request.method());
    assertEquals("api.open.uc.cn", request.url().host());
    assertEquals("https://broccoli.uc.cn/", request.header("Origin"));
    assertEquals("https://broccoli.uc.cn/", request.header("Referer"));
    FormBody body = (FormBody) request.body();
    assertEquals("381", form(body, "client_id"));
    assertEquals("1.2", form(body, "v"));
    assertEquals("su.uc.cn", HttpUrl.get(challenge.qrUrl).host());
    assertEquals("/1_n0ZCv", HttpUrl.get(challenge.qrUrl).encodedPath());
    assertEquals("381", HttpUrl.get(challenge.qrUrl).queryParameter("client_id"));
    assertEquals(1_000L, challenge.pollIntervalMs);
  }

  @Test
  public void waitingAndServerExpiryDoNotPretendTheAccountIsAuthorized() throws Exception {
    Script script = new Script(BEGIN, "{\"status\":50004001}", "{\"status\":50004002}");
    QuarkUcProvider provider = script.provider(false);
    CloudProvider.LoginChallenge challenge = provider.beginLogin();
    CloudProvider.LoginResult waiting = provider.pollLogin(challenge);
    assertEquals(CloudProvider.Status.WAITING, waiting.status);
    assertNull(waiting.account);
    assertEquals(TOKEN, script.requests.get(1).url().queryParameter("token"));
    assertEquals(CloudProvider.Status.EXPIRED, provider.pollLogin(challenge).status);
    assertEquals(3, script.requests.size());
  }

  @Test
  public void authorizationRequiresTicketExchangeOnTheMatchingFirstPartySite() throws Exception {
    for (boolean uc : new boolean[] {false, true}) {
      Script script = new Script(BEGIN, TICKET, ACCOUNT, CONFIG);
      QuarkUcProvider provider = script.provider(uc);
      CloudProvider.LoginChallenge challenge = provider.beginLogin();
      sessionCookie(challenge.account, uc, false);
      CloudProvider.LoginResult result = provider.pollLogin(challenge);
      assertEquals(CloudProvider.Status.AUTHORIZED, result.status);
      assertEquals(uc ? "uc" : "quark", result.account.providerId);
      assertEquals((uc ? "uc" : "quark") + ":user_42", result.account.id);
      assertEquals("user_42", result.account.data.optString("uid"));
      assertEquals("书房读者", result.account.label);
      Request exchange = script.requests.get(2);
      assertEquals(uc ? "drive.uc.cn" : "pan.quark.cn", exchange.url().host());
      assertEquals("/account/info", exchange.url().encodedPath());
      assertEquals("fixture-ticket", exchange.url().queryParameter("st"));
      Request config = script.requests.get(3);
      assertEquals(uc ? "pc-api.uc.cn" : "drive.quark.cn", config.url().host());
      assertEquals("/1/clouddrive/config", config.url().encodedPath());
      assertEquals("GET", config.method());
      assertEquals(uc ? "UCBrowser" : "ucpro", config.url().queryParameter("pr"));
      assertEquals("pc", config.url().queryParameter("fr"));
      assertEquals(4, script.requests.size());
      if (uc) {
        assertEquals("https://broccoli.uc.cn/", script.requests.get(1).header("Origin"));
        assertEquals("https://broccoli.uc.cn/", script.requests.get(1).header("Referer"));
      }
      assertFalse(challenge.state.has("service_ticket"));
      assertFalse(challenge.state.has("token"));
    }
  }

  @Test
  public void personalDriveMustAcceptTheSessionBeforeAuthorization() throws Exception {
    for (boolean uc : new boolean[] {false, true}) {
      for (long code : new long[] {31001, 50000}) {
        Script script =
            new Script(
                BEGIN,
                TICKET,
                ACCOUNT,
                "{\"status\":401,\"code\":" + code + ",\"message\":\"private account detail\"}");
        QuarkUcProvider provider = script.provider(uc);
        CloudProvider.LoginChallenge challenge = provider.beginLogin();
        sessionCookie(challenge.account, uc, false);
        IOException failure = expectIo(() -> provider.pollLogin(challenge));
        assertFalse(failure.getMessage().contains("private account detail"));
        assertFalse(challenge.account.data.has("uid"));
        assertTrue(challenge.state.has("service_ticket"));
        assertEquals(4, script.requests.size());
      }
    }
  }

  @Test
  public void configCookieUpdatesAreIncludedInThePersistableAuthorizedAccount() throws Exception {
    for (boolean uc : new boolean[] {false, true}) {
      Script script = new Script(BEGIN, TICKET, ACCOUNT, CONFIG);
      QuarkUcProvider provider = script.provider(uc);
      CloudProvider.LoginChallenge challenge = provider.beginLogin();
      sessionCookie(challenge.account, uc, false);
      script.cookieAccount = challenge.account;
      script.configCookie =
          "__puus=fixture-refreshed; Domain=" + (uc ? "uc.cn" : "quark.cn") + "; Path=/; Secure";
      CloudProvider.LoginResult result = provider.pollLogin(challenge);
      assertEquals(CloudProvider.Status.AUTHORIZED, result.status);
      CloudAccount restored = CloudAccount.fromJson(result.account.toJson());
      List<Cookie> cookies =
          CloudHttp.client(restored)
              .cookieJar()
              .loadForRequest(
                  HttpUrl.get(
                      (uc ? "https://pc-api.uc.cn" : "https://drive.quark.cn")
                          + "/1/clouddrive/config"));
      assertEquals(1, cookies.size());
      assertEquals("fixture-refreshed", cookies.get(0).value());
    }
  }

  @Test
  public void configThatClearsTheSessionDoesNotAuthorize() throws Exception {
    for (boolean uc : new boolean[] {false, true}) {
      Script script = new Script(BEGIN, TICKET, ACCOUNT, CONFIG);
      QuarkUcProvider provider = script.provider(uc);
      CloudProvider.LoginChallenge challenge = provider.beginLogin();
      sessionCookie(challenge.account, uc, false);
      script.cookieAccount = challenge.account;
      script.configCookie =
          "__puus=; Max-Age=0; Domain=" + (uc ? "uc.cn" : "quark.cn") + "; Path=/; Secure";
      IOException failure = expectIo(() -> provider.pollLogin(challenge));
      assertTrue(failure.getMessage().contains("重新扫码"));
      assertFalse(challenge.account.data.has("uid"));
      assertEquals(4, script.requests.size());
    }
  }

  @Test
  public void successfulAccountJsonWithoutAnApiSessionCookieDoesNotAuthorize() throws Exception {
    for (boolean hostOnly : new boolean[] {false, true}) {
      Script script =
          new Script(
              BEGIN,
              "{\"status\":2000000,\"data\":{\"members\":{\"service_ticket\":\"fixture-ticket\"}}}",
              "{\"success\":true,\"data\":{\"uid\":\"user_42\",\"nickname\":\"测试账号\"}}");
      QuarkUcProvider provider = script.provider(false);
      CloudProvider.LoginChallenge challenge = provider.beginLogin();
      if (hostOnly) sessionCookie(challenge.account, false, true);
      IOException failure = expectIo(() -> provider.pollLogin(challenge));
      assertTrue(failure.getMessage().contains("重新扫码"));
      assertFalse(challenge.account.data.has("uid"));
    }
  }

  @Test
  public void missingTicketOrFailedExchangeNeverAuthorizes() throws Exception {
    Script missing = new Script(BEGIN, "{\"status\":2000000,\"data\":{\"members\":{}}}");
    QuarkUcProvider provider = missing.provider(false);
    CloudProvider.LoginChallenge challenge = provider.beginLogin();
    expectIo(() -> provider.pollLogin(challenge));
    assertEquals(2, missing.requests.size());
    Script denied =
        new Script(
            BEGIN,
            "{\"status\":2000000,\"data\":{\"members\":{\"service_ticket\":\"fixture-ticket\"}}}",
            "{\"success\":false,\"code\":50001,\"msg\":\"private account detail\"}");
    QuarkUcProvider deniedProvider = denied.provider(true);
    CloudProvider.LoginChallenge deniedChallenge = deniedProvider.beginLogin();
    IOException failure = expectIo(() -> deniedProvider.pollLogin(deniedChallenge));
    assertFalse(failure.getMessage().contains("private account detail"));
  }

  @Test
  public void localExpiryAndWrongProviderDoNotPerformMoreNetworkRequests() throws Exception {
    Script script = new Script(BEGIN);
    QuarkUcProvider provider = script.provider(false);
    CloudProvider.LoginChallenge original = provider.beginLogin();
    CloudProvider.LoginChallenge expired =
        new CloudProvider.LoginChallenge(
            "quark",
            original.qrUrl,
            System.currentTimeMillis() - 1,
            original.pollIntervalMs,
            original.account,
            original.state);
    assertEquals(CloudProvider.Status.EXPIRED, provider.pollLogin(expired).status);
    expectIo(() -> script.provider(true).pollLogin(original));
    assertEquals(1, script.requests.size());
  }

  @Test
  public void directoryPaginationUsesTheUnfilteredServerCount() throws Exception {
    Script script =
        new Script(
            "{\"status\":200,\"code\":0,\"data\":{\"list\":[{\"fid\":\"folder_1\",\"file_name\":\"中文书库\",\"file_type\":0},{\"fid\":\"book_1\",\"file_name\":\"书籍.epub\",\"file_type\":1,\"size\":123}]},\"metadata\":{\"_total\":101}}",
            "{\"status\":200,\"code\":0,\"data\":{\"list\":[{\"fid\":\"book_2\",\"file_name\":\"第二本.txt\",\"file_type\":1,\"size\":456}]},\"metadata\":{\"_total\":101}}");
    QuarkUcProvider provider = script.provider(true);
    CloudAccount account = account(true);
    CloudProvider.ListPage first = provider.list(account, "0", null, true);
    assertEquals(1, first.entries.size());
    assertTrue(first.entries.get(0).directory);
    assertEquals("中文书库", first.entries.get(0).name);
    assertEquals("2", first.nextPageToken);
    CloudProvider.ListPage second = provider.list(account, "0", first.nextPageToken, true);
    assertTrue(second.entries.isEmpty());
    assertNull(second.nextPageToken);
    assertEquals("2", script.requests.get(1).url().queryParameter("_page"));
    assertEquals("100", script.requests.get(0).url().queryParameter("_size"));
  }

  @Test
  public void fileListUsesTheCorrectHostAndReturnsFilesWithoutDownloading() throws Exception {
    for (boolean uc : new boolean[] {false, true}) {
      Script script =
          new Script(
              "{\"status\":200,\"code\":0,\"data\":{\"list\":[{\"fid\":\"book_1\",\"file_name\":\"中文.pdf\",\"file_type\":1,\"size\":4096}]},\"metadata\":{\"_total\":1}}");
      QuarkUcProvider provider = script.provider(uc);
      CloudProvider.ListPage page = provider.list(account(uc), "folder_1", "1", false);
      Request request = script.requests.get(0);
      assertEquals(uc ? "pc-api.uc.cn" : "drive-pc.quark.cn", request.url().host());
      assertEquals(uc ? "UCBrowser" : "ucpro", request.url().queryParameter("pr"));
      assertEquals("pc", request.url().queryParameter("fr"));
      assertEquals("folder_1", request.url().queryParameter("pdir_fid"));
      assertEquals("file_type:asc,file_name:asc", request.url().queryParameter("_sort"));
      assertFalse(page.entries.get(0).directory);
      assertEquals(4096, page.entries.get(0).size);
      assertEquals(1, script.requests.size());
    }
  }

  @Test
  public void invalidIdentifiersPagesAndAccountsFailBeforeNetworking() throws Exception {
    Script script = new Script();
    QuarkUcProvider provider = script.provider(false);
    expectIo(() -> provider.list(account(false), "../outside", null, false));
    expectIo(() -> provider.list(account(false), "0", "0", false));
    expectIo(() -> provider.list(account(false), "0", "100001", false));
    expectIo(() -> provider.list(account(true), "0", null, false));
    expectIo(() -> provider.resolve(account(false), "book&secret=1"));
    assertTrue(script.requests.isEmpty());
  }

  @Test
  public void resolveOnlyQueriesTheLinkAndScopesCookiesToItsHost() throws Exception {
    Script script = new Script(link("https://files.quark.cn/book.epub?signature=fixture"));
    CloudAccount account = account(false);
    CloudHttp.client(account)
        .cookieJar()
        .saveFromResponse(
            HttpUrl.get("https://pan.quark.cn/"),
            Arrays.asList(
                new Cookie.Builder()
                    .name("__puus")
                    .value("fixture-cookie")
                    .domain("quark.cn")
                    .path("/")
                    .secure()
                    .build(),
                new Cookie.Builder()
                    .name("unrelated")
                    .value("never-send")
                    .domain("uc.cn")
                    .path("/")
                    .secure()
                    .build()));
    CloudProvider.DownloadLink link = script.provider(false).resolve(account, "book_1");
    assertEquals(1024, link.size);
    assertEquals("quark:user_42:book_1", link.identity);
    assertEquals("__puus=fixture-cookie", link.headers.get("Cookie"));
    assertEquals("https://pan.quark.cn/", link.headers.get("Referer"));
    Request request = script.requests.get(0);
    assertEquals("POST", request.method());
    assertEquals("drive.quark.cn", request.url().host());
    assertEquals("/1/clouddrive/file/download", request.url().encodedPath());
    Buffer body = new Buffer();
    request.body().writeTo(body);
    assertEquals("book_1", new JSONObject(body.readUtf8()).getJSONArray("fids").getString(0));
    assertEquals(1, script.requests.size());
  }

  @Test
  public void signedCdnUrlsDoNotReceiveAccountCookies() throws Exception {
    Script script =
        new Script(link("https://bucket.oss-cn-hangzhou.aliyuncs.com/book.epub?signature=fixture"));
    CloudAccount account = account(true);
    CloudHttp.client(account)
        .cookieJar()
        .saveFromResponse(
            HttpUrl.get("https://drive.uc.cn/"),
            Arrays.asList(
                new Cookie.Builder()
                    .name("__puus")
                    .value("fixture-cookie")
                    .domain("uc.cn")
                    .path("/")
                    .secure()
                    .build()));
    CloudProvider.DownloadLink link = script.provider(true).resolve(account, "book_1");
    assertFalse(link.headers.containsKey("Cookie"));
    assertEquals("pc-api.uc.cn", script.requests.get(0).url().host());
  }

  @Test
  public void insecureSpoofedAndCredentialBearingDownloadUrlsAreRejected() throws Exception {
    for (String url :
        new String[] {
          "http://files.quark.cn/book.epub",
          "https://quark.cn.evil.test/book.epub",
          "https://name:password@files.quark.cn/book.epub",
          "https://files.quark.cn/book.epub#fragment",
          "https://127.0.0.1/book.epub"
        }) {
      Script script = new Script(link(url));
      expectIo(() -> script.provider(false).resolve(account(false), "book_1"));
      assertEquals(1, script.requests.size());
    }
  }

  @Test
  public void expiredCredentialsAndMalformedResponsesFailTruthfully() throws Exception {
    Script expired =
        new Script("{\"status\":401,\"code\":31001,\"message\":\"secret must not leak\"}");
    IOException error =
        expectIo(() -> expired.provider(false).list(account(false), "0", null, false));
    assertTrue(error.getMessage().contains("重新扫码"));
    assertFalse(error.getMessage().contains("secret"));
    Script malformed = new Script("{\"status\":200,\"code\":0,\"data\":{}}");
    expectIo(() -> malformed.provider(true).list(account(true), "0", null, false));
    Script wrongFile =
        new Script(
            "{\"status\":200,\"code\":0,\"data\":[{\"fid\":\"another\",\"download_url\":\"https://files.quark.cn/book.epub\"}]}");
    expectIo(() -> wrongFile.provider(false).resolve(account(false), "book_1"));
  }

  private static CloudAccount account(boolean uc) {
    return new CloudAccount(
        uc ? "uc" : "quark", (uc ? "uc" : "quark") + ":user_42", "测试账号", new JSONObject());
  }

  private static void sessionCookie(CloudAccount account, boolean uc, boolean hostOnly) {
    Cookie.Builder cookie =
        new Cookie.Builder().name("__puus").value("fixture-session").path("/").secure();
    if (hostOnly) cookie.hostOnlyDomain(uc ? "drive.uc.cn" : "pan.quark.cn");
    else cookie.domain(uc ? "uc.cn" : "quark.cn");
    CloudHttp.client(account)
        .cookieJar()
        .saveFromResponse(
            HttpUrl.get(uc ? "https://drive.uc.cn/" : "https://pan.quark.cn/"),
            Arrays.asList(cookie.build()));
  }

  private static String link(String url) throws Exception {
    return new JSONObject()
        .put("status", 200)
        .put("code", 0)
        .put(
            "data",
            new JSONArray()
                .put(
                    new JSONObject()
                        .put("fid", "book_1")
                        .put("download_url", url)
                        .put("size", 1024)))
        .toString();
  }

  private static String form(FormBody body, String name) {
    for (int i = 0; i < body.size(); i++) if (body.name(i).equals(name)) return body.value(i);
    return null;
  }

  private interface Work {
    void run() throws Exception;
  }

  private static IOException expectIo(Work work) throws Exception {
    try {
      work.run();
      fail("Expected IOException");
    } catch (IOException expected) {
      return expected;
    }
    throw new AssertionError();
  }

  private static final class Script implements Interceptor {
    final Deque<String> responses = new ArrayDeque<>();
    final List<Request> requests = new ArrayList<>();
    CloudAccount cookieAccount;
    String configCookie;

    Script(String... responses) {
      this.responses.addAll(Arrays.asList(responses));
    }

    QuarkUcProvider provider(boolean uc) {
      return new QuarkUcProvider(uc, new OkHttpClient.Builder().addInterceptor(this).build());
    }

    @Override
    public Response intercept(Chain chain) throws IOException {
      requests.add(chain.request());
      if (responses.isEmpty()) throw new IOException("Unexpected fixture request");
      Response.Builder response =
          new Response.Builder()
              .request(chain.request())
              .protocol(Protocol.HTTP_1_1)
              .code(200)
              .message("OK")
              .body(
                  ResponseBody.create(responses.removeFirst(), MediaType.get("application/json")));
      if (configCookie != null
          && "/1/clouddrive/config".equals(chain.request().url().encodedPath())) {
        response.addHeader("Set-Cookie", configCookie);
      }
      Response result = response.build();
      if (cookieAccount != null) {
        // This scripted application interceptor bypasses OkHttp's BridgeInterceptor. Deliver its
        // synthetic Set-Cookie headers to the real account jar before the provider sees the JSON.
        CloudHttp.client(cookieAccount)
            .cookieJar()
            .saveFromResponse(
                chain.request().url(), Cookie.parseAll(chain.request().url(), result.headers()));
      }
      return result;
    }
  }
}
