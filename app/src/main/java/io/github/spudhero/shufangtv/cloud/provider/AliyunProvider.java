package io.github.spudhero.shufangtv.cloud.provider;

import io.github.spudhero.shufangtv.cloud.CloudAccount;
import io.github.spudhero.shufangtv.cloud.CloudHttp;
import io.github.spudhero.shufangtv.cloud.CloudProvider;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import okhttp3.FormBody;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.bouncycastle.asn1.sec.SECNamedCurves;
import org.bouncycastle.asn1.x9.X9ECParameters;
import org.bouncycastle.crypto.digests.SHA256Digest;
import org.bouncycastle.crypto.params.ECDomainParameters;
import org.bouncycastle.crypto.params.ECPrivateKeyParameters;
import org.bouncycastle.crypto.signers.ECDSASigner;
import org.bouncycastle.crypto.signers.HMacDSAKCalculator;
import org.bouncycastle.math.ec.ECAlgorithms;
import org.bouncycastle.math.ec.ECPoint;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Independently implemented first-party Alipan web session protocol; no OpenAPI secret. */
public final class AliyunProvider implements CloudProvider {
  // Public identifier shipped by Alipan's own sign-in page; this is not a private AppSecret.
  private static final String WEB_APP_ID = "25dzX3vbYqktVxyX";
  private static final String PASSPORT = "https://passport.alipan.com";
  private static final String API = "https://api.aliyundrive.com";
  private static final String AUTH_API = "https://auth.alipan.com";
  private static final String DOWNLOAD_API = "https://api.alipan.com";
  private static final String USER_API = "https://user.aliyundrive.com";
  private static final String REFERER = "https://www.alipan.com/";
  private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
  private static final Pattern VIEW_DATA =
      Pattern.compile("window\\.viewData\\s*=\\s*(\\{.*?\\});", Pattern.DOTALL);
  private static final X9ECParameters CURVE = SECNamedCurves.getByName("secp256k1");
  private static final ECDomainParameters DOMAIN =
      new ECDomainParameters(CURVE.getCurve(), CURVE.getG(), CURVE.getN(), CURVE.getH());
  private final OkHttpClient injectedClient;

  public AliyunProvider() {
    this(null);
  }

  /** Allows a deterministic, local HTTP interceptor in protocol tests. */
  public AliyunProvider(OkHttpClient client) {
    injectedClient = client;
  }

  @Override
  public String id() {
    return "aliyun";
  }

  @Override
  public String label() {
    return "阿里云盘";
  }

  @Override
  public String rootId(CloudAccount account) {
    return "@drives";
  }

  @Override
  public LoginChallenge beginLogin() throws IOException {
    CloudAccount account =
        new CloudAccount(id(), id() + ":pending:" + UUID.randomUUID(), label(), new JSONObject());
    OkHttpClient client = client(account);
    String mini =
        text(
            client,
            new Request.Builder()
                .url(
                    PASSPORT
                        + "/mini_login.htm?appName=aliyun_drive&appEntrance=web&isMobile=false&lang=zh_CN&styleType=auto&rnd="
                        + UUID.randomUUID())
                .header("Cache-Control", "no-cache")
                .header("Referer", REFERER)
                .build());
    Matcher matcher = VIEW_DATA.matcher(mini);
    if (!matcher.find()) throw new IOException("阿里云盘登录页暂不可用，请稍后重新扫码");
    try {
      JSONObject form = new JSONObject(matcher.group(1)).getJSONObject("loginFormData");
      HttpUrl.Builder url = HttpUrl.get(PASSPORT + "/newlogin/qrcode/generate.do").newBuilder();
      appendForm(url, form);
      url.addQueryParameter("_", Long.toString(System.currentTimeMillis()));
      JSONObject reply =
          CloudHttp.json(
              client,
              new Request.Builder()
                  .url(url.build())
                  .header("Referer", PASSPORT + "/mini_login.htm")
                  .build());
      JSONObject data = qrData(reply);
      String content = data.getString("codeContent");
      put(form, "ck", data.getString("ck"));
      put(form, "t", data.get("t"));
      return new LoginChallenge(
          id(), content, System.currentTimeMillis() + 5 * 60_000L, 2000, account, form);
    } catch (JSONException e) {
      throw new IOException("阿里云盘未返回有效二维码，请重新扫码", e);
    }
  }

  @Override
  public LoginResult pollLogin(LoginChallenge challenge) throws IOException {
    requireChallenge(challenge);
    if (System.currentTimeMillis() >= challenge.expiresAt)
      return result(Status.EXPIRED, null, "二维码已过期，请刷新二维码");
    FormBody.Builder form = new FormBody.Builder();
    java.util.Iterator<String> keys = challenge.state.keys();
    while (keys.hasNext()) {
      String key = keys.next();
      form.add(key, challenge.state.optString(key));
    }
    if (!challenge.state.has("appName")) form.add("appName", "aliyun_drive");
    if (!challenge.state.has("fromSite")) form.add("fromSite", "52");
    JSONObject reply =
        CloudHttp.json(
            client(challenge.account),
            new Request.Builder()
                .url(PASSPORT + "/newlogin/qrcode/query.do?appName=aliyun_drive&fromSite=52")
                .post(form.build())
                .header("Referer", PASSPORT + "/mini_login.htm")
                .build());
    JSONObject content = reply.optJSONObject("content");
    JSONObject data = content == null ? null : content.optJSONObject("data");
    if (data == null) throw new IOException("阿里云盘登录状态无效，请刷新二维码");
    String status = data.optString("qrCodeStatus");
    if ("EXPIRED".equals(status)) return result(Status.EXPIRED, null, "二维码已过期，请刷新二维码");
    if ("CANCELED".equals(status)) return result(Status.DENIED, null, "已取消阿里云盘登录");
    if ("ERROR".equals(status)) return result(Status.DENIED, null, "阿里云盘要求重新验证，请重新扫码");
    if ("SCANED".equals(status) || "SCANNED".equals(status))
      return result(Status.SCANNED, null, "已扫码，请在阿里云盘 App 中确认登录");
    if (!"CONFIRMED".equals(status)) return result(Status.WAITING, null, "请用阿里云盘 App 扫码");
    JSONObject login = decodeLogin(data.optString("bizExt"));
    if (login.has("pds_login_error")) return result(Status.DENIED, null, "阿里云盘登录受限，请在官方 App 完成验证");
    JSONObject token = login.optJSONObject("pds_login_result");
    if (token == null || "not_ready".equals(token.optString("status")))
      throw new IOException("阿里云盘账号尚未准备就绪，请先在官方 App 完成开通");
    updateToken(challenge.account, token);
    String userId = challenge.account.data.optString("user_id");
    if (userId.isEmpty()) throw new IOException("阿里云盘未返回用户标识，请重新扫码");
    CloudAccount account =
        new CloudAccount(
            id(),
            id() + ":" + userId,
            first(token, "nickName", "nick_name", "userName", "user_name"),
            challenge.account.data);
    if (account.label.isEmpty()) account.label = label();
    put(account.data, "device_id", UUID.randomUUID().toString());
    put(account.data, "private_key", randomPrivateKey());
    return result(Status.AUTHORIZED, account, "阿里云盘已连接");
  }

  @Override
  public ListPage list(
      CloudAccount account, String folderId, String pageToken, boolean directoriesOnly)
      throws IOException {
    synchronized (account) {
      requireAccount(account);
      if ("@drives".equals(folderId)) return drives(account);
      String[] ids = splitId(folderId);
      JSONObject body =
          json(
              "drive_id",
              ids[0],
              "parent_file_id",
              ids[1],
              "limit",
              100,
              "marker",
              pageToken == null ? "" : pageToken,
              "order_by",
              "name",
              "order_direction",
              "ASC");
      if (directoriesOnly) put(body, "type", "folder");
      JSONObject reply = data(signed(account, API + "/adrive/v3/file/list", body));
      JSONArray items = reply.optJSONArray("items");
      if (items == null) items = reply.optJSONArray("file_list");
      if (items == null) throw new IOException("阿里云盘目录响应缺少文件列表");
      List<Entry> entries = new ArrayList<>();
      for (int i = 0; i < items.length(); i++) {
        JSONObject item = items.optJSONObject(i);
        if (item == null) throw new IOException("阿里云盘目录中存在无效文件信息");
        boolean directory = "folder".equals(item.optString("type"));
        if (directoriesOnly && !directory) continue;
        String fileId = item.optString("file_id");
        if (fileId.isEmpty()) throw new IOException("阿里云盘文件缺少标识");
        entries.add(
            new Entry(
                ids[0] + ":" + fileId,
                folderId,
                item.optString("name"),
                directory,
                Math.max(0, item.optLong("size", 0))));
      }
      String next = first(reply, "next_marker", "nextMarker");
      if (!next.isEmpty() && (next.equals(pageToken) || items.length() == 0))
        throw new IOException("阿里云盘返回了重复的目录游标，请刷新目录");
      return new ListPage(entries, next);
    }
  }

  @Override
  public DownloadLink resolve(CloudAccount account, String fileId) throws IOException {
    synchronized (account) {
      requireAccount(account);
      String[] ids = splitId(fileId);
      JSONObject key = json("drive_id", ids[0], "file_id", ids[1]);
      JSONObject metadata = data(signed(account, API + "/v2/file/get", key));
      if ("folder".equals(metadata.optString("type"))) throw new IOException("请选择书籍文件");
      JSONObject reply =
          data(
              signed(
                  account,
                  DOWNLOAD_API + "/v2/file/get_download_url",
                  json("drive_id", ids[0], "file_id", ids[1], "expire_sec", 3600)));
      String url = first(reply, "url", "download_url", "downloadUrl", "cdn_url", "cdnUrl");
      HttpUrl parsed = HttpUrl.parse(url);
      if (parsed == null || !parsed.isHttps())
        throw new IOException("阿里云盘未返回可读取的直链，请在官方 App 检查文件状态后重试");
      Map<String, String> headers = new LinkedHashMap<>();
      headers.put("User-Agent", CloudHttp.USER_AGENT);
      headers.put("Referer", REFERER);
      long size = metadata.optLong("size", -1);
      if (size < 0) throw new IOException("阿里云盘未返回文件大小，无法在线分段阅读");
      String identity = fileId + ":" + size + ":" + first(metadata, "content_hash", "updated_at");
      long expiry =
          parseTime(reply.optString("expiration"), System.currentTimeMillis() + 50 * 60_000L);
      return new DownloadLink(url, headers, size, identity, expiry);
    }
  }

  private ListPage drives(CloudAccount account) throws IOException {
    JSONObject info = data(signed(account, USER_API + "/v2/user/get", new JSONObject()));
    List<Entry> entries = new ArrayList<>();
    String resource = info.optString("resource_drive_id"),
        backup = info.optString("backup_drive_id");
    if (!resource.isEmpty()) entries.add(new Entry(resource + ":root", "@drives", "资源库", true, 0));
    if (!backup.isEmpty() && !backup.equals(resource))
      entries.add(new Entry(backup + ":root", "@drives", "备份盘", true, 0));
    if (entries.isEmpty()) {
      String drive = first(info, "default_drive_id");
      if (drive.isEmpty()) drive = account.data.optString("default_drive_id");
      if (drive.isEmpty()) throw new IOException("阿里云盘账号没有可访问的网盘，请先在官方 App 完成开通");
      entries.add(new Entry(drive + ":root", "@drives", "我的网盘", true, 0));
    }
    return new ListPage(entries, "");
  }

  private JSONObject signed(CloudAccount account, String url, JSONObject body) throws IOException {
    ensureToken(account);
    for (int attempt = 0; attempt < 2; attempt++) {
      try {
        ensureDevice(account);
        return api(account, url, body, account.data.optString("signature"));
      } catch (SessionFailure failure) {
        if (attempt != 0) throw failure;
        if (!failure.device) refreshToken(account);
        put(account.data, "device_expires_at", 0L);
      }
    }
    throw new IOException("阿里云盘会话无法恢复，请重新扫码");
  }

  private void ensureToken(CloudAccount account) throws IOException {
    if (account.data.optString("access_token").isEmpty()) throw new IOException("阿里云盘会话已失效，请重新扫码");
    if (account.data.optLong("token_expires_at", 0) > System.currentTimeMillis() + 120_000L) return;
    refreshToken(account);
  }

  private void refreshToken(CloudAccount account) throws IOException {
    String refresh = account.data.optString("refresh_token");
    if (refresh.isEmpty()) throw new IOException("阿里云盘会话已过期，请重新扫码");
    JSONObject reply =
        apiJson(
            client(account),
            post(
                AUTH_API + "/v2/account/token",
                json("grant_type", "refresh_token", "refresh_token", refresh)));
    updateToken(account, data(reply));
  }

  private void ensureDevice(CloudAccount account) throws IOException {
    if (account.data.optLong("device_expires_at") > System.currentTimeMillis() + 60_000L
        && !account.data.optString("signature").isEmpty()) return;
    if (account.data.optString("private_key").isEmpty())
      put(account.data, "private_key", randomPrivateKey());
    if (account.data.optString("device_id").isEmpty())
      put(account.data, "device_id", UUID.randomUUID().toString());
    String signature =
        deviceSignature(
            account.data.optString("private_key"),
            WEB_APP_ID
                + ":"
                + account.data.optString("device_id")
                + ":"
                + account.data.optString("user_id")
                + ":0");
    BigInteger secret = new BigInteger(account.data.optString("private_key"), 16);
    String pubKey = hex(CURVE.getG().multiply(secret).normalize().getEncoded(false));
    JSONObject reply =
        api(
            account,
            API + "/users/v1/users/device/create_session",
            json("deviceName", "书房 TV", "modelName", "Android TV", "pubKey", pubKey),
            signature);
    if (!reply.optBoolean("result", false))
      throw new IOException("阿里云盘无法建立设备会话，请在官方 App 检查登录设备数量后重试");
    put(account.data, "signature", signature);
    put(account.data, "device_expires_at", System.currentTimeMillis() + 23 * 60 * 60_000L);
  }

  private JSONObject api(CloudAccount account, String url, JSONObject body, String signature)
      throws IOException {
    Request request =
        post(url, body)
            .newBuilder()
            .header("Authorization", "Bearer " + account.data.optString("access_token"))
            .header("x-device-id", account.data.optString("device_id"))
            .header("x-signature", signature)
            .header("Origin", "https://www.alipan.com")
            .header("x-request-id", UUID.randomUUID().toString())
            .header("X-Canary", "client=web,app=adrive,version=v6.8.12")
            .header("Referer", REFERER)
            .build();
    return apiJson(client(account), request);
  }

  private OkHttpClient client(CloudAccount account) {
    return injectedClient == null
        ? CloudHttp.client(account)
        : injectedClient
            .newBuilder()
            .cookieJar(new CloudHttp.SessionCookies(account))
            .followRedirects(false)
            .followSslRedirects(false)
            .build();
  }

  static JSONObject decodeLogin(String value) throws IOException {
    if (value.isEmpty() || value.length() > 256 * 1024) throw new IOException("阿里云盘登录结果无效，请重新扫码");
    try {
      if (value.trim().startsWith("{")) return new JSONObject(value);
      byte[] bytes = Base64.getDecoder().decode(value);
      try {
        String utf8 =
            StandardCharsets.UTF_8
                .newDecoder()
                .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(bytes))
                .toString();
        return new JSONObject(utf8);
      } catch (java.nio.charset.CharacterCodingException | JSONException invalidUtf8) {
        return new JSONObject(new String(bytes, java.nio.charset.Charset.forName("GB18030")));
      }
    } catch (JSONException | IllegalArgumentException e) {
      throw new IOException("阿里云盘登录结果无法解析，请重新扫码", e);
    }
  }

  private static void updateToken(CloudAccount account, JSONObject token) throws IOException {
    String access = first(token, "access_token", "accessToken");
    String refresh = first(token, "refresh_token", "refreshToken");
    if (refresh.isEmpty()) refresh = account.data.optString("refresh_token");
    if (access.isEmpty() || refresh.isEmpty()) throw new IOException("阿里云盘没有返回完整会话，请重新扫码");
    put(account.data, "access_token", access);
    put(account.data, "refresh_token", refresh);
    String user = first(token, "user_id", "userId");
    if (!user.isEmpty()) put(account.data, "user_id", user);
    String drive = first(token, "default_drive_id", "defaultDriveId");
    if (!drive.isEmpty()) put(account.data, "default_drive_id", drive);
    long seconds = token.optLong("expires_in", token.optLong("expiresIn", 7200));
    put(
        account.data,
        "token_expires_at",
        parseTime(
            first(token, "expire_time", "expireTime"),
            System.currentTimeMillis() + Math.max(60, seconds) * 1000L));
  }

  /** Compact recoverable ECDSA signature, matching the public web protocol's 65-byte format. */
  static String deviceSignature(String privateHex, String message) throws IOException {
    try {
      BigInteger secret = new BigInteger(privateHex, 16), n = CURVE.getN();
      if (secret.signum() <= 0 || secret.compareTo(n) >= 0)
        throw new IllegalArgumentException("private key");
      byte[] digest =
          MessageDigest.getInstance("SHA-256").digest(message.getBytes(StandardCharsets.UTF_8));
      ECDSASigner signer = new ECDSASigner(new HMacDSAKCalculator(new SHA256Digest()));
      signer.init(true, new ECPrivateKeyParameters(secret, DOMAIN));
      BigInteger[] signature = signer.generateSignature(digest);
      BigInteger r = signature[0], s = signature[1];
      if (s.compareTo(n.shiftRight(1)) > 0) s = n.subtract(s);
      ECPoint expected = CURVE.getG().multiply(secret).normalize();
      BigInteger e = new BigInteger(1, digest);
      for (int recovery = 0; recovery < 4; recovery++) {
        BigInteger x = r.add(n.multiply(BigInteger.valueOf(recovery / 2)));
        if (x.compareTo(CURVE.getCurve().getField().getCharacteristic()) >= 0) continue;
        byte[] compressed = new byte[33];
        compressed[0] = (byte) (2 + (recovery & 1));
        System.arraycopy(unsigned32(x), 0, compressed, 1, 32);
        ECPoint point = CURVE.getCurve().decodePoint(compressed);
        if (!point.multiply(n).isInfinity()) continue;
        BigInteger inv = r.modInverse(n);
        ECPoint recovered =
            ECAlgorithms.sumOfTwoMultiplies(
                    CURVE.getG(),
                    e.negate().mod(n).multiply(inv).mod(n),
                    point,
                    s.multiply(inv).mod(n))
                .normalize();
        if (recovered.equals(expected))
          return hex(unsigned32(r)) + hex(unsigned32(s)) + "0" + recovery;
      }
      throw new IllegalStateException("recovery");
    } catch (Exception e) {
      throw new IOException("阿里云盘设备签名失败，请重新扫码", e);
    }
  }

  private static byte[] unsigned32(BigInteger value) {
    byte[] raw = value.toByteArray(), result = new byte[32];
    int count = Math.min(32, raw.length);
    System.arraycopy(raw, raw.length - count, result, 32 - count, count);
    return result;
  }

  private static String randomPrivateKey() {
    byte[] bytes = new byte[32];
    BigInteger value;
    SecureRandom random = new SecureRandom();
    do {
      random.nextBytes(bytes);
      value = new BigInteger(1, bytes);
    } while (value.signum() == 0 || value.compareTo(CURVE.getN()) >= 0);
    return hex(bytes);
  }

  private static String hex(byte[] bytes) {
    StringBuilder result = new StringBuilder(bytes.length * 2);
    for (byte b : bytes) {
      result.append(Character.forDigit((b >>> 4) & 15, 16));
      result.append(Character.forDigit(b & 15, 16));
    }
    return result.toString();
  }

  private static String[] splitId(String id) throws IOException {
    if (id == null || id.length() > 1024) throw new IOException("阿里云盘文件标识无效");
    int colon = id.indexOf(':');
    if (colon <= 0 || colon == id.length() - 1) throw new IOException("阿里云盘文件标识无效");
    return new String[] {id.substring(0, colon), id.substring(colon + 1)};
  }

  private void requireAccount(CloudAccount account) throws IOException {
    if (account == null || !id().equals(account.providerId)) throw new IOException("请选择阿里云盘账号");
  }

  private void requireChallenge(LoginChallenge challenge) throws IOException {
    if (challenge == null || !id().equals(challenge.providerId) || challenge.account == null)
      throw new IOException("阿里云盘登录会话无效，请刷新二维码");
  }

  private static JSONObject qrData(JSONObject reply) throws IOException {
    JSONObject content = reply.optJSONObject("content"),
        data = content == null ? null : content.optJSONObject("data");
    if (data == null || !content.optBoolean("success")) throw new IOException("阿里云盘二维码生成失败，请稍后重试");
    return data;
  }

  private static void appendForm(HttpUrl.Builder url, JSONObject form) {
    java.util.Iterator<String> keys = form.keys();
    while (keys.hasNext()) {
      String key = keys.next();
      url.addQueryParameter(key, form.optString(key));
    }
    if (!form.has("appName")) url.addQueryParameter("appName", "aliyun_drive");
    if (!form.has("fromSite")) url.addQueryParameter("fromSite", "52");
  }

  private static Request post(String url, JSONObject body) {
    return new Request.Builder()
        .url(url)
        .header("User-Agent", CloudHttp.USER_AGENT)
        .post(RequestBody.create(body.toString(), JSON))
        .build();
  }

  private static void checkError(JSONObject reply) throws IOException {
    String code = reply.optString("code");
    if (code.isEmpty() || "200".equals(code) || "OK".equalsIgnoreCase(code)) return;
    if (code.contains("Token") || code.contains("Login")) throw new SessionFailure(false);
    if (code.contains("Signature") || code.contains("Device")) throw new SessionFailure(true);
    if (code.contains("Forbidden") || code.contains("AccessDenied"))
      throw new IOException("阿里云盘拒绝访问，请在官方 App 检查文件权限");
    throw new IOException("阿里云盘暂时无法完成请求（" + code.replaceAll("[^a-zA-Z0-9_.-]", "") + "）");
  }

  private static JSONObject data(JSONObject object) {
    JSONObject value = object.optJSONObject("data");
    return value == null ? object : value;
  }

  private static JSONObject apiJson(OkHttpClient client, Request request) throws IOException {
    try (Response response = client.newCall(request).execute()) {
      if (response.code() == 401) throw new SessionFailure(false);
      ResponseBody body = response.body();
      if (body == null || body.contentLength() > 4 * 1024 * 1024)
        throw new IOException("阿里云盘服务返回了无效数据");
      java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
      byte[] buffer = new byte[8192];
      int count;
      while ((count = body.byteStream().read(buffer)) != -1) {
        if (bytes.size() + count > 4 * 1024 * 1024) throw new IOException("阿里云盘服务响应过大");
        bytes.write(buffer, 0, count);
      }
      JSONObject reply;
      try {
        reply = new JSONObject(bytes.toString("UTF-8"));
      } catch (JSONException e) {
        throw new IOException("阿里云盘会话暂不可用，请重新扫码", e);
      }
      checkError(reply);
      if (!response.isSuccessful()) throw new IOException("阿里云盘服务请求失败（" + response.code() + "）");
      return reply;
    }
  }

  private static final class SessionFailure extends IOException {
    final boolean device;

    SessionFailure(boolean device) {
      super(device ? "阿里云盘设备验证已失效，请重新扫码" : "阿里云盘会话已失效，请重新扫码");
      this.device = device;
    }
  }

  private static String text(OkHttpClient client, Request request) throws IOException {
    try (Response response = client.newCall(request).execute()) {
      if (!response.isSuccessful()) throw new IOException("阿里云盘登录页暂不可用（" + response.code() + "）");
      ResponseBody body = response.body();
      if (body == null || body.contentLength() > 1024 * 1024) throw new IOException("阿里云盘登录页数据无效");
      java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
      byte[] buffer = new byte[8192];
      int count;
      while ((count = body.byteStream().read(buffer)) != -1) {
        if (bytes.size() + count > 1024 * 1024) throw new IOException("阿里云盘登录页数据过大");
        bytes.write(buffer, 0, count);
      }
      return bytes.toString("UTF-8");
    }
  }

  private static long parseTime(String value, long fallback) {
    try {
      return Instant.parse(value).toEpochMilli();
    } catch (RuntimeException ignored) {
      return fallback;
    }
  }

  private static String first(JSONObject object, String... names) {
    for (String name : names) {
      String value = object.optString(name, "");
      if (!value.isEmpty()) return value;
    }
    return "";
  }

  private static JSONObject json(Object... pairs) throws IOException {
    JSONObject object = new JSONObject();
    for (int i = 0; i < pairs.length; i += 2) put(object, (String) pairs[i], pairs[i + 1]);
    return object;
  }

  private static void put(JSONObject object, String key, Object value) throws IOException {
    try {
      object.put(key, value);
    } catch (JSONException e) {
      throw new IOException("阿里云盘会话数据无效", e);
    }
  }

  private static LoginResult result(Status status, CloudAccount account, String message) {
    return new LoginResult(status, account, message);
  }
}
