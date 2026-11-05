package io.github.spudhero.shufangtv.cloud.provider;

import static org.junit.Assert.*;

import io.github.spudhero.shufangtv.cloud.CloudAccount;
import io.github.spudhero.shufangtv.cloud.CloudProvider;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import okhttp3.Cookie;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import okio.Buffer;
import org.bouncycastle.asn1.sec.SECNamedCurves;
import org.bouncycastle.asn1.x9.X9ECParameters;
import org.bouncycastle.crypto.params.ECDomainParameters;
import org.bouncycastle.crypto.params.ECPublicKeyParameters;
import org.bouncycastle.crypto.signers.ECDSASigner;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** Entirely synthetic first-party protocol fixtures. No real account, token, or network is used. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 31, manifest = Config.NONE)
public class CloudProviderProtocolTest {
  private interface Handler {
    Object reply(Request request) throws Exception;
  }

  private static final class Reply {
    final int code;
    final String body;

    Reply(int code, String body) {
      this.code = code;
      this.body = body;
    }
  }

  private static final class Fixture {
    final List<Request> requests = new ArrayList<>();
    final OkHttpClient client;

    Fixture(Handler handler) {
      client =
          new OkHttpClient.Builder()
              .addInterceptor(
                  chain -> {
                    Request request = chain.request();
                    requests.add(request);
                    try {
                      Object value = handler.reply(request);
                      if (value == null)
                        throw new AssertionError("Unexpected request: " + request.url());
                      Reply reply =
                          value instanceof Reply ? (Reply) value : new Reply(200, (String) value);
                      return new Response.Builder()
                          .request(request)
                          .protocol(Protocol.HTTP_1_1)
                          .code(reply.code)
                          .message("fixture")
                          .body(ResponseBody.create(reply.body, MediaType.get("application/json")))
                          .build();
                    } catch (IOException e) {
                      throw e;
                    } catch (Exception e) {
                      throw new IOException("fixture", e);
                    }
                  })
              .build();
    }
  }

  private static CloudAccount aliAccount() throws Exception {
    JSONObject data =
        new JSONObject()
            .put("user_id", "123")
            .put("access_token", "synthetic-access")
            .put("refresh_token", "synthetic-refresh")
            .put("token_expires_at", System.currentTimeMillis() + 3600_000)
            .put("device_id", "synthetic-device")
            .put("private_key", "1");
    return new CloudAccount("aliyun", "aliyun:123", "测试账号", data);
  }

  private static CloudAccount baiduAccount() throws Exception {
    JSONObject template =
        new JSONObject()
            .put("bdstoken", "synthetic-bdstoken")
            .put("uk", "123")
            .put("username", "测试读者")
            .put("sign1", "Plaintext")
            .put("sign3", "Key")
            .put("sign2", "function(){/* synthetic RC4 markers: 256 charCodeAt fromCharCode */}")
            .put("timestamp", 100000);
    return new CloudAccount(
        "baidu",
        "baidu:123",
        "测试账号",
        new JSONObject()
            .put("template", template)
            .put("template_fetched_at", System.currentTimeMillis()));
  }

  private static String body(Request request) throws Exception {
    Buffer buffer = new Buffer();
    request.body().writeTo(buffer);
    return buffer.readUtf8();
  }

  private static String tokenResult() throws Exception {
    return new JSONObject()
        .put(
            "pds_login_result",
            new JSONObject()
                .put("userId", "123")
                .put("accessToken", "synthetic-access")
                .put("refreshToken", "synthetic-refresh")
                .put("defaultDriveId", "drive1")
                .put("nickName", "中文读者")
                .put("expiresIn", 7200))
        .toString();
  }

  private static CloudProvider.LoginChallenge challenge(String provider, CloudAccount account)
      throws Exception {
    return new CloudProvider.LoginChallenge(
        provider,
        "synthetic-qr",
        System.currentTimeMillis() + 60_000,
        2000,
        account,
        new JSONObject().put("sign", "synthetic-sign").put("ck", "ck1").put("t", 1000));
  }

  @Test
  public void aliyunInitializesFirstPartyFormAndReturnsLocalQrText() throws Exception {
    Fixture fixture =
        new Fixture(
            request -> {
              if (request.url().encodedPath().equals("/mini_login.htm"))
                return "<script>window.viewData = "
                           + "{\"loginFormData\":{\"appName\":\"aliyun_drive\",\"fromSite\":52,\"_csrf_token\":\"fixture-csrf\"}};</script>";
              assertEquals("fixture-csrf", request.url().queryParameter("_csrf_token"));
              return "{\"content\":{\"success\":true,\"data\":{\"ck\":\"ck1\",\"t\":1000,\"codeContent\":\"https://passport.alipan.com/qrcodeCheck.htm?fixture\"}}}";
            });
    CloudProvider.LoginChallenge result = new AliyunProvider(fixture.client).beginLogin();
    assertEquals("aliyun", result.providerId);
    assertTrue(result.qrUrl.contains("qrcodeCheck"));
    assertEquals("ck1", result.state.getString("ck"));
    assertEquals(2, fixture.requests.size());
    assertTrue(
        fixture.requests.stream().allMatch(r -> r.url().host().equals("passport.alipan.com")));
  }

  @Test
  public void aliyunScannedAndExpiredStatesDoNotCreateAccountOrFetchFiles() throws Exception {
    for (String status : new String[] {"SCANED", "EXPIRED", "CANCELED", "NEW"}) {
      Fixture fixture =
          new Fixture(
              r -> {
                assertEquals("POST", r.method());
                assertTrue(body(r).contains("ck=ck1"));
                return "{\"content\":{\"data\":{\"qrCodeStatus\":\"" + status + "\"}}}";
              });
      CloudProvider.LoginResult result =
          new AliyunProvider(fixture.client).pollLogin(challenge("aliyun", aliAccount()));
      CloudProvider.Status expected =
          status.equals("SCANED")
              ? CloudProvider.Status.SCANNED
              : status.equals("EXPIRED")
                  ? CloudProvider.Status.EXPIRED
                  : status.equals("CANCELED")
                      ? CloudProvider.Status.DENIED
                      : CloudProvider.Status.WAITING;
      assertEquals(expected, result.status);
      assertNull(result.account);
      assertEquals(1, fixture.requests.size());
    }
  }

  @Test
  public void aliyunConfirmedMapsCamelCaseTokenAndNamespacedAccountId() throws Exception {
    String encoded =
        Base64.getEncoder().encodeToString(tokenResult().getBytes(StandardCharsets.UTF_8));
    Fixture fixture =
        new Fixture(
            r ->
                new JSONObject()
                    .put(
                        "content",
                        new JSONObject()
                            .put(
                                "data",
                                new JSONObject()
                                    .put("qrCodeStatus", "CONFIRMED")
                                    .put("bizExt", encoded)))
                    .toString());
    CloudProvider.LoginResult result =
        new AliyunProvider(fixture.client).pollLogin(challenge("aliyun", aliAccount()));
    assertEquals(CloudProvider.Status.AUTHORIZED, result.status);
    assertEquals("aliyun:123", result.account.id);
    assertEquals("123", result.account.data.getString("user_id"));
    assertEquals("中文读者", result.account.label);
    assertEquals("synthetic-refresh", result.account.data.getString("refresh_token"));
    assertEquals(64, result.account.data.getString("private_key").length());
  }

  @Test
  public void aliyunSupportsGb18030AndPlainJsonLoginResults() throws Exception {
    String token = tokenResult();
    String encoded = Base64.getEncoder().encodeToString(token.getBytes(Charset.forName("GB18030")));
    assertEquals(
        "中文读者",
        AliyunProvider.decodeLogin(encoded)
            .getJSONObject("pds_login_result")
            .getString("nickName"));
    assertEquals(
        "123",
        AliyunProvider.decodeLogin(token).getJSONObject("pds_login_result").getString("userId"));
    assertThrows(IOException.class, () -> AliyunProvider.decodeLogin("not-a-base64-session"));
  }

  @Test
  public void aliyunDeviceSignatureUsesCanonicalRecoverableSecp256k1() throws Exception {
    String message = "public-app:device:user:0";
    String compact = AliyunProvider.deviceSignature("1", message);
    assertEquals(130, compact.length());
    assertEquals(compact, AliyunProvider.deviceSignature("1", message));
    X9ECParameters curve = SECNamedCurves.getByName("secp256k1");
    ECDomainParameters domain =
        new ECDomainParameters(curve.getCurve(), curve.getG(), curve.getN(), curve.getH());
    BigInteger r = new BigInteger(compact.substring(0, 64), 16),
        s = new BigInteger(compact.substring(64, 128), 16);
    assertTrue(s.compareTo(curve.getN().shiftRight(1)) <= 0);
    assertTrue(Integer.parseInt(compact.substring(128), 16) <= 3);
    ECDSASigner verifier = new ECDSASigner();
    verifier.init(false, new ECPublicKeyParameters(curve.getG(), domain));
    assertTrue(
        verifier.verifySignature(
            MessageDigest.getInstance("SHA-256").digest(message.getBytes(StandardCharsets.UTF_8)),
            r,
            s));
    assertThrows(IOException.class, () -> AliyunProvider.deviceSignature("0", message));
  }

  @Test
  public void aliyunCreatesOwnDeviceAndListsBothRealDrives() throws Exception {
    Fixture fixture =
        new Fixture(
            request -> {
              assertEquals("Bearer synthetic-access", request.header("Authorization"));
              assertEquals("synthetic-device", request.header("x-device-id"));
              assertEquals(130, request.header("x-signature").length());
              if (request.url().encodedPath().endsWith("create_session")) {
                JSONObject payload = new JSONObject(body(request));
                assertEquals("书房 TV", payload.getString("deviceName"));
                assertEquals(130, payload.getString("pubKey").length());
                assertFalse(payload.has("private_key"));
                return "{\"result\":true}";
              }
              assertEquals("user.aliyundrive.com", request.url().host());
              return "{\"resource_drive_id\":\"resource\",\"backup_drive_id\":\"backup\"}";
            });
    CloudProvider.ListPage page =
        new AliyunProvider(fixture.client).list(aliAccount(), "@drives", "", true);
    assertEquals(2, page.entries.size());
    assertEquals("resource:root", page.entries.get(0).id);
    assertEquals("backup:root", page.entries.get(1).id);
    assertEquals(2, fixture.requests.size());
  }

  @Test
  public void aliyunDirectoryPaginationKeepsOpaqueDriveId() throws Exception {
    CloudAccount account = aliAccount();
    account
        .data
        .put("signature", "synthetic-signature")
        .put("device_expires_at", System.currentTimeMillis() + 3600_000);
    Fixture fixture =
        new Fixture(
            request -> {
              JSONObject payload = new JSONObject(body(request));
              assertEquals("driveA", payload.getString("drive_id"));
              assertEquals("folderB", payload.getString("parent_file_id"));
              assertEquals("cursor1", payload.getString("marker"));
              assertEquals("folder", payload.getString("type"));
              return "{\"items\":[{\"file_id\":\"folderC\",\"name\":\"小说\",\"type\":\"folder\"},{\"file_id\":\"book\",\"name\":\"书.epub\",\"type\":\"file\"}],\"next_marker\":\"cursor2\"}";
            });
    CloudProvider.ListPage page =
        new AliyunProvider(fixture.client).list(account, "driveA:folderB", "cursor1", true);
    assertEquals("cursor2", page.nextPageToken);
    assertEquals(1, page.entries.size());
    assertEquals("driveA:folderC", page.entries.get(0).id);
  }

  @Test
  public void aliyunRefreshRotatesTokenBeforeFileOperations() throws Exception {
    CloudAccount account = aliAccount();
    account
        .data
        .put("token_expires_at", 0)
        .put("signature", "fixture")
        .put("device_expires_at", System.currentTimeMillis() + 3600_000);
    Fixture fixture =
        new Fixture(
            request -> {
              if (request.url().host().equals("auth.alipan.com")) {
                assertEquals("/v2/account/token", request.url().encodedPath());
                assertNull(request.header("Authorization"));
                assertEquals(
                    "synthetic-refresh", new JSONObject(body(request)).getString("refresh_token"));
                assertEquals(
                    "refresh_token", new JSONObject(body(request)).getString("grant_type"));
                return "{\"access_token\":\"rotated-access\",\"refresh_token\":\"rotated-refresh\",\"expires_in\":7200}";
              }
              assertEquals("Bearer rotated-access", request.header("Authorization"));
              return "{\"items\":[],\"next_marker\":\"\"}";
            });
    new AliyunProvider(fixture.client).list(account, "drive:root", "", false);
    assertEquals("rotated-refresh", account.data.getString("refresh_token"));
  }

  @Test
  public void aliyunDownloadExportsOnlyFileHeadersAndNeverDownloadsBody() throws Exception {
    CloudAccount account = aliAccount();
    account
        .data
        .put("signature", "fixture")
        .put("device_expires_at", System.currentTimeMillis() + 3600_000);
    Fixture fixture =
        new Fixture(
            request ->
                request.url().encodedPath().endsWith("get_download_url")
                    ? "{\"url\":\"https://signed.example.test/file?signature=synthetic\"}"
                    : "{\"type\":\"file\",\"size\":123456,\"content_hash\":\"fixture-hash\"}");
    CloudProvider.DownloadLink link =
        new AliyunProvider(fixture.client).resolve(account, "drive:book");
    assertEquals(123456, link.size);
    assertFalse(link.headers.containsKey("Authorization"));
    assertFalse(link.headers.containsKey("Cookie"));
    assertTrue(link.identity.contains("fixture-hash"));
    assertEquals(2, fixture.requests.size());
    assertEquals("api.aliyundrive.com", fixture.requests.get(0).url().host());
    assertEquals("api.alipan.com", fixture.requests.get(1).url().host());
  }

  @Test
  public void baiduBeginsOnlyOnFirstPartyAndReturnsQrImageFlag() throws Exception {
    Fixture fixture =
        new Fixture(
            request ->
                request.url().host().equals("pan.baidu.com")
                    ? "<html></html>"
                    : "{\"errno\":0,\"sign\":\"fixture-sign\",\"imgurl\":\"passport.baidu.com/v2/api/qrcode?sign=fixture\"}");
    CloudProvider.LoginChallenge challenge = new BaiduProvider(fixture.client).beginLogin();
    assertTrue(challenge.state.getBoolean("qrIsImage"));
    assertTrue(challenge.qrUrl.startsWith("https://passport.baidu.com/"));
    Request request = fixture.requests.get(1);
    assertEquals("netdisk", request.url().queryParameter("tpl"));
    assertEquals("v3", request.url().queryParameter("apiver"));
    assertFalse(request.url().queryParameterNames().contains("client_secret"));
  }

  @Test
  public void baiduHandlesWaitingScannedAndDeniedWithoutCookieExchange() throws Exception {
    for (int status : new int[] {-1, 1, 2}) {
      Fixture fixture =
          new Fixture(
              request -> {
                assertEquals("POST", request.method());
                assertTrue(body(request).contains("Referer="));
                return status == -1
                    ? "{\"errno\":1}"
                    : new JSONObject()
                        .put("errno", 0)
                        .put("channel_v", new JSONObject().put("status", status).toString())
                        .toString();
              });
      CloudProvider.LoginResult result =
          new BaiduProvider(fixture.client).pollLogin(challenge("baidu", baiduAccount()));
      assertEquals(
          status == -1
              ? CloudProvider.Status.WAITING
              : status == 1 ? CloudProvider.Status.SCANNED : CloudProvider.Status.DENIED,
          result.status);
      assertNull(result.account);
      assertEquals(1, fixture.requests.size());
    }
  }

  @Test
  public void baiduConfirmedExchangesTicketAndValidatesOwnWebSession() throws Exception {
    CloudAccount account = baiduAccount();
    Fixture fixture =
        new Fixture(
            request -> {
              String path = request.url().encodedPath();
              if (path.equals("/channel/unicast"))
                return "{\"errno\":0,\"channel_v\":\"{\\\"status\\\":0,\\\"v\\\":\\\"synthetic-ticket\\\"}\"}";
              if (path.endsWith("qrbdusslogin")) {
                assertEquals("synthetic-ticket", request.url().queryParameter("bduss"));
                assertEquals("netdisk", request.url().queryParameter("tpl"));
                assertEquals("v5", request.url().queryParameter("loginVersion"));
                return "fixtureCallback({\"errInfo\":{\"no\":\"0\"},\"data\":{\"u\":\"https://pan.baidu.com/disk/main\","
                           + "\"session\":{\"bduss\":\"synthetic-body-bduss\",\"stoken\":\"synthetic-body-stoken\"}}});";
              }
              if (path.equals("/disk/main")) return "<html>synthetic session</html>";
              return new JSONObject()
                  .put("errno", 0)
                  .put("result", account.data.getJSONObject("template"))
                  .toString();
            });
    CloudProvider.LoginResult result =
        new BaiduProvider(fixture.client).pollLogin(challenge("baidu", account));
    assertEquals(CloudProvider.Status.AUTHORIZED, result.status);
    assertEquals("baidu:123", result.account.id);
    assertEquals("123", result.account.data.getString("user_id"));
    assertEquals("测试读者", result.account.label);
    List<Cookie> cookies =
        new io.github.spudhero.shufangtv.cloud.CloudHttp.SessionCookies(result.account)
            .loadForRequest(HttpUrl.get("https://pan.baidu.com/"));
    assertTrue(
        cookies.stream()
            .anyMatch(c -> c.name().equals("BDUSS") && c.value().equals("synthetic-body-bduss")));
    assertEquals(4, fixture.requests.size());
  }

  @Test
  public void baiduNeverFollowsLoginRedirectToAnotherOrigin() throws Exception {
    Fixture fixture =
        new Fixture(
            request ->
                request.url().encodedPath().equals("/channel/unicast")
                    ? "{\"errno\":0,\"channel_v\":{\"status\":0,\"v\":\"fixture\"}}"
                    : "{\"errInfo\":{\"no\":0},\"data\":{\"u\":\"https://attacker.example/collect\"}}");
    IOException failure =
        assertThrows(
            IOException.class,
            () -> new BaiduProvider(fixture.client).pollLogin(challenge("baidu", baiduAccount())));
    assertTrue(failure.getMessage().contains("无效登录跳转"));
    assertEquals(2, fixture.requests.size());
  }

  @Test
  public void baiduDirectoryPaginationUsesPathsAndFiltersDirectories() throws Exception {
    Fixture fixture =
        new Fixture(
            request -> {
              assertEquals("/书籍", request.url().queryParameter("dir"));
              assertEquals("2", request.url().queryParameter("page"));
              assertEquals("synthetic-bdstoken", request.url().queryParameter("bdstoken"));
              return "{\"errno\":0,\"has_more\":1,\"list\":[{\"path\":\"/书籍/历史\",\"server_filename\":\"历史\",\"isdir\":1},{\"path\":\"/书籍/书.epub\",\"server_filename\":\"书.epub\",\"isdir\":0}]}";
            });
    CloudProvider.ListPage page =
        new BaiduProvider(fixture.client).list(baiduAccount(), "/书籍", "2", true);
    assertEquals("3", page.nextPageToken);
    assertEquals(1, page.entries.size());
    assertEquals("/书籍/历史", page.entries.get(0).id);
    assertEquals(1, fixture.requests.size());
  }

  @Test
  public void baiduMetadataDlinkAvoidsLegacySignAndNeverFetchesTheFile() throws Exception {
    Fixture fixture =
        new Fixture(
            request -> {
              assertEquals("/api/filemetas", request.url().encodedPath());
              assertEquals(
                  "/书籍/小说.epub",
                  new JSONArray(request.url().queryParameter("target")).getString(0));
              return "{\"errno\":0,\"info\":[{\"fs_id\":123,\"size\":900000,\"md5\":\"fixture\",\"dlink\":\"https://d.pcs.baidu.com/file?sign=synthetic\"}]}";
            });
    CloudProvider.DownloadLink link =
        new BaiduProvider(fixture.client).resolve(baiduAccount(), "/书籍/小说.epub");
    assertEquals(900000, link.size);
    assertEquals("pan.baidu.com", link.headers.get("User-Agent"));
    assertFalse(link.headers.containsKey("Cookie"));
    assertFalse(link.headers.containsKey("Authorization"));
    assertEquals(1, fixture.requests.size());
  }

  @Test
  public void baiduLegacySignatureIsStandardRc4AndNeverExecutesReturnedJavascript()
      throws Exception {
    // Public RC4 test vector: Key / Plaintext -> BBF316E8D940AF0AD3, encoded as Base64.
    assertEquals("u/MW6NlArwrT", BaiduProvider.rc4Sign("Key", "Plaintext"));
    CloudAccount account = baiduAccount();
    Fixture fixture =
        new Fixture(
            request -> {
              String path = request.url().encodedPath();
              if (path.equals("/api/filemetas"))
                return "{\"errno\":0,\"info\":[{\"fs_id\":9007199254740993,\"size\":100}]}";
              if (path.equals("/api/gettemplatevariable"))
                return new JSONObject()
                    .put("errno", 0)
                    .put("result", account.data.getJSONObject("template"))
                    .toString();
              assertEquals("/api/download", path);
              assertEquals("[9007199254740993]", request.url().queryParameter("fidlist"));
              assertEquals("u/MW6NlArwrT", request.url().queryParameter("sign"));
              return "{\"errno\":0,\"dlink\":[{\"dlink\":\"https://d.pcs.baidu.com/file?signature=fixture\"}]}";
            });
    new BaiduProvider(fixture.client).resolve(account, "/书.epub");
    assertEquals(3, fixture.requests.size());
  }

  @Test
  public void baiduChangedSignatureAndExpiredSessionAreReadableErrors() throws Exception {
    CloudAccount account = baiduAccount();
    account.data.getJSONObject("template").put("sign2", "unrecognized protocol");
    Fixture changed =
        new Fixture(
            request ->
                request.url().encodedPath().equals("/api/filemetas")
                    ? "{\"errno\":0,\"info\":[{\"fs_id\":1,\"size\":10}]}"
                    : new JSONObject()
                        .put("errno", 0)
                        .put("result", account.data.getJSONObject("template"))
                        .toString());
    assertTrue(
        assertThrows(
                IOException.class,
                () -> new BaiduProvider(changed.client).resolve(account, "/书.epub"))
            .getMessage()
            .contains("签名已更新"));
    Fixture expired = new Fixture(request -> "{\"errno\":-6}");
    assertTrue(
        assertThrows(
                IOException.class,
                () -> new BaiduProvider(expired.client).list(baiduAccount(), "/", "", false))
            .getMessage()
            .contains("重新扫码"));
  }

  @Test
  public void neitherProviderSendsRequestsForExpiredQrOrInvalidFolder() throws Exception {
    Fixture fixture =
        new Fixture(
            request -> {
              throw new AssertionError("no network expected");
            });
    CloudProvider.LoginChallenge expired =
        new CloudProvider.LoginChallenge(
            "aliyun", "fixture", 0, 2000, aliAccount(), new JSONObject());
    assertEquals(
        CloudProvider.Status.EXPIRED, new AliyunProvider(fixture.client).pollLogin(expired).status);
    assertThrows(
        IOException.class,
        () -> new AliyunProvider(fixture.client).list(aliAccount(), "bad-id", "", false));
    assertThrows(
        IOException.class,
        () -> new BaiduProvider(fixture.client).list(baiduAccount(), "relative", "", false));
    assertThrows(
        IOException.class,
        () -> new BaiduProvider(fixture.client).list(baiduAccount(), "/", "not-a-page", false));
    assertEquals(0, fixture.requests.size());
  }

  @Test
  public void aliyun401RefreshesOnceAndPreservesOmittedRefreshToken() throws Exception {
    CloudAccount account = aliAccount();
    account
        .data
        .put("signature", "fixture")
        .put("device_expires_at", System.currentTimeMillis() + 3600_000);
    AtomicInteger files = new AtomicInteger(),
        refreshes = new AtomicInteger(),
        devices = new AtomicInteger();
    Fixture fixture =
        new Fixture(
            request -> {
              String path = request.url().encodedPath();
              if (request.url().host().equals("auth.alipan.com")) {
                refreshes.incrementAndGet();
                assertEquals("/v2/account/token", path);
                return "{\"access_token\":\"rotated-access\",\"expires_in\":7200}";
              }
              if (path.endsWith("create_session")) {
                devices.incrementAndGet();
                assertEquals("Bearer rotated-access", request.header("Authorization"));
                return "{\"result\":true}";
              }
              if (files.incrementAndGet() == 1)
                return new Reply(401, "{\"code\":\"AccessTokenInvalid\"}");
              assertEquals("Bearer rotated-access", request.header("Authorization"));
              return "{\"items\":[],\"next_marker\":\"\"}";
            });
    new AliyunProvider(fixture.client).list(account, "drive:root", "", false);
    assertEquals(2, files.get());
    assertEquals(1, refreshes.get());
    assertEquals(1, devices.get());
    assertEquals("synthetic-refresh", account.data.getString("refresh_token"));
  }

  @Test
  public void aliyunPersistent401StopsAfterOneRefresh() throws Exception {
    CloudAccount account = aliAccount();
    account
        .data
        .put("signature", "fixture")
        .put("device_expires_at", System.currentTimeMillis() + 3600_000);
    AtomicInteger files = new AtomicInteger(), refreshes = new AtomicInteger();
    Fixture fixture =
        new Fixture(
            request -> {
              if (request.url().host().equals("auth.alipan.com")) {
                refreshes.incrementAndGet();
                return "{\"access_token\":\"rotated\",\"expires_in\":7200}";
              }
              if (request.url().encodedPath().endsWith("create_session"))
                return "{\"result\":true}";
              files.incrementAndGet();
              return new Reply(401, "{\"code\":\"AccessTokenInvalid\"}");
            });
    assertTrue(
        assertThrows(
                IOException.class,
                () -> new AliyunProvider(fixture.client).list(account, "drive:root", "", false))
            .getMessage()
            .contains("重新扫码"));
    assertEquals(2, files.get());
    assertEquals(1, refreshes.get());
    assertEquals(4, fixture.requests.size());
  }

  @Test
  public void aliyunPermissionFailureDoesNotRefreshOrRepeatRequest() throws Exception {
    CloudAccount account = aliAccount();
    account
        .data
        .put("signature", "fixture")
        .put("device_expires_at", System.currentTimeMillis() + 3600_000);
    Fixture fixture = new Fixture(request -> new Reply(403, "{\"code\":\"Forbidden\"}"));
    assertThrows(
        IOException.class,
        () -> new AliyunProvider(fixture.client).list(account, "drive:root", "", false));
    assertEquals(1, fixture.requests.size());
  }

  @Test
  public void aliyunAcceptsWrappedDirectoryButRejectsRepeatedCursor() throws Exception {
    CloudAccount account = aliAccount();
    account
        .data
        .put("signature", "fixture")
        .put("device_expires_at", System.currentTimeMillis() + 3600_000);
    Fixture fixture =
        new Fixture(
            request ->
                "{\"data\":{\"file_list\":[{\"file_id\":\"book\",\"name\":\"书.epub\",\"type\":\"file\"}],\"nextMarker\":\"same\"}}");
    AliyunProvider provider = new AliyunProvider(fixture.client);
    assertEquals("drive:book", provider.list(account, "drive:root", "", false).entries.get(0).id);
    assertThrows(IOException.class, () -> provider.list(account, "drive:root", "same", false));
  }

  @Test
  public void baiduFileCookiesStayOnMatchingFirstPartyPcsHost() throws Exception {
    CloudAccount account = baiduAccount();
    new io.github.spudhero.shufangtv.cloud.CloudHttp.SessionCookies(account)
        .saveFromResponse(
            HttpUrl.get("https://passport.baidu.com/"),
            java.util.Arrays.asList(
                new Cookie.Builder()
                    .name("BDUSS")
                    .value("synthetic-bduss")
                    .domain("baidu.com")
                    .path("/")
                    .secure()
                    .build(),
                new Cookie.Builder()
                    .name("BAIDUID")
                    .value("unneeded-tracking")
                    .domain("baidu.com")
                    .path("/")
                    .secure()
                    .build(),
                new Cookie.Builder()
                    .name("STOKEN")
                    .value("passport-only")
                    .hostOnlyDomain("passport.baidu.com")
                    .path("/")
                    .secure()
                    .build()));
    for (String host :
        new String[] {"d.pcs.baidu.com", "signed.example.test", "attacker.baidu.com"}) {
      Fixture fixture =
          new Fixture(
              request ->
                  "{\"errno\":0,\"info\":[{\"fs_id\":1,\"size\":100,\"dlink\":\"https://"
                      + host
                      + "/file?sign=fixture\"}]}");
      CloudProvider.DownloadLink link =
          new BaiduProvider(fixture.client).resolve(account, "/书+文字.epub");
      if (host.equals("d.pcs.baidu.com"))
        assertEquals("BDUSS=synthetic-bduss", link.headers.get("Cookie"));
      else assertFalse(link.headers.containsKey("Cookie"));
      JSONArray target = new JSONArray(fixture.requests.get(0).url().queryParameter("target"));
      assertEquals("/书+文字.epub", target.getString(0));
      assertTrue(fixture.requests.get(0).url().encodedQuery().contains("%2B"));
    }
  }

  @Test
  public void baiduConfirmationWithoutRealSessionCookieCannotAuthorize() throws Exception {
    Fixture fixture =
        new Fixture(
            request ->
                request.url().encodedPath().equals("/channel/unicast")
                    ? "{\"errno\":0,\"channel_v\":{\"status\":0,\"v\":\"synthetic-ticket\"}}"
                    : "{\"errInfo\":{\"no\":0},\"data\":{}}");
    IOException failure =
        assertThrows(
            IOException.class,
            () -> new BaiduProvider(fixture.client).pollLogin(challenge("baidu", baiduAccount())));
    assertTrue(failure.getMessage().contains("缺少登录会话"));
    assertEquals(2, fixture.requests.size());
  }
}
