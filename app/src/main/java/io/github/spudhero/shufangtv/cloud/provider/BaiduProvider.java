package io.github.spudhero.shufangtv.cloud.provider;

import io.github.spudhero.shufangtv.cloud.CloudAccount;
import io.github.spudhero.shufangtv.cloud.CloudHttp;
import io.github.spudhero.shufangtv.cloud.CloudProvider;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import okhttp3.Cookie;
import okhttp3.FormBody;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** First-party Baidu web QR and file protocol. No borrowed developer key or authorization relay. */
public final class BaiduProvider implements CloudProvider {
  private static final String PASSPORT = "https://passport.baidu.com";
  private static final String PAN = "https://pan.baidu.com";
  private static final String LANDING = PAN + "/disk/main";
  private static final String FIELDS =
      "[\"bdstoken\",\"uk\",\"username\",\"sign1\",\"sign2\",\"sign3\",\"timestamp\",\"is_vip\"]";
  private final OkHttpClient injectedClient;

  public BaiduProvider() {
    this(null);
  }

  /** Protocol tests inject a local response interceptor, without a real account. */
  public BaiduProvider(OkHttpClient client) {
    injectedClient = client;
  }

  @Override
  public String id() {
    return "baidu";
  }

  @Override
  public String label() {
    return "百度网盘";
  }

  @Override
  public String rootId(CloudAccount account) {
    return "/";
  }

  @Override
  public LoginChallenge beginLogin() throws IOException {
    CloudAccount account =
        new CloudAccount(id(), id() + ":pending:" + UUID.randomUUID(), label(), new JSONObject());
    // This first-party request establishes the anonymous BAIDUID/session cookies for the QR flow.
    visit(account, HttpUrl.get(PAN + "/"));
    String gid = UUID.randomUUID().toString().toUpperCase(java.util.Locale.ROOT);
    JSONObject reply =
        passport(
            account,
            "/v2/api/getqrcode",
            "lp",
            "pc",
            "qrloginfrom",
            "pc",
            "apiver",
            "v3",
            "tpl",
            "netdisk",
            "tt",
            now(),
            "gid",
            gid);
    checkError(reply);
    String sign = reply.optString("sign"), image = reply.optString("imgurl");
    if (image.startsWith("//")) image = "https:" + image;
    else if (!image.contains("://")) image = "https://" + image;
    HttpUrl url = HttpUrl.parse(image);
    if (sign.isEmpty() || url == null || !url.isHttps() || !isLoginHost(url.host()))
      throw new IOException("百度网盘没有返回有效二维码，请刷新二维码");
    JSONObject state = json("sign", sign, "gid", gid, "qrIsImage", true);
    return new LoginChallenge(
        id(), image, System.currentTimeMillis() + 590_000L, 2000, account, state);
  }

  @Override
  public LoginResult pollLogin(LoginChallenge challenge) throws IOException {
    requireChallenge(challenge);
    if (System.currentTimeMillis() >= challenge.expiresAt)
      return result(Status.EXPIRED, null, "二维码已过期，请刷新二维码");
    if (challenge.state.optBoolean("webSessionReady")) return confirmSession(challenge);
    JSONObject reply =
        passport(
            challenge.account,
            "/channel/unicast",
            true,
            "channel_id",
            challenge.state.optString("sign"),
            "gid",
            challenge.state.optString("gid"),
            "tpl",
            "netdisk",
            "_sdkFrom",
            "1",
            "apiver",
            "v3",
            "tt",
            now());
    int errno = reply.optInt("errno", -1);
    if (errno == 1) return result(Status.WAITING, null, "请用百度网盘 App 扫码");
    if (errno != 0) throw new IOException("百度网盘二维码状态暂不可用，请刷新二维码");
    JSONObject channel = reply.optJSONObject("channel_v");
    if (channel == null) {
      String value = reply.optString("channel_v");
      if (value.isEmpty()) return result(Status.WAITING, null, "请用百度网盘 App 扫码");
      try {
        channel = new JSONObject(value);
      } catch (JSONException e) {
        throw new IOException("百度网盘扫码响应无效，请刷新二维码", e);
      }
    }
    int status = channel.optInt("status", -1);
    if (status == 1) return result(Status.SCANNED, null, "已扫码，请在百度网盘 App 中确认登录");
    if (status == 2) return result(Status.DENIED, null, "已取消百度网盘登录，请刷新二维码");
    if (status != 0) return result(Status.WAITING, null, "请用百度网盘 App 扫码");
    String ticket = channel.optString("v");
    if (ticket.isEmpty()) throw new IOException("百度网盘登录凭证为空，请重新扫码");
    JSONObject exchange =
        passport(
            challenge.account,
            "/v3/login/main/qrbdusslogin",
            "v",
            now(),
            "bduss",
            ticket,
            "u",
            LANDING,
            "loginVersion",
            "v5",
            "qrcode",
            "1",
            "tpl",
            "netdisk",
            "apiver",
            "v3",
            "tt",
            now());
    JSONObject error = exchange.optJSONObject("errInfo");
    if (error == null || error.optInt("no", -1) != 0)
      return result(Status.DENIED, null, "百度网盘要求额外验证，请在官方 App 检查账号状态后重新扫码");
    JSONObject data = exchange.optJSONObject("data");
    String landing = data == null ? "" : data.optString("u");
    HttpUrl redirect = null;
    if (!landing.isEmpty()) {
      redirect = HttpUrl.parse(landing);
      if (redirect == null && landing.startsWith("https%3A")) {
        try {
          redirect = HttpUrl.parse(java.net.URLDecoder.decode(landing, "UTF-8"));
        } catch (IllegalArgumentException ignored) {
          /* Invalid redirect is rejected below. */
        }
      }
      if (redirect == null || !redirect.isHttps() || !isLoginHost(redirect.host()))
        throw new IOException("百度网盘返回了无效登录跳转，已停止登录");
    }
    installResponseCookies(challenge.account, exchange);
    boolean hasSession =
        new CloudHttp.SessionCookies(challenge.account)
            .loadForRequest(HttpUrl.get(PAN + "/")).stream()
                .anyMatch(
                    cookie -> cookie.name().equalsIgnoreCase("BDUSS") && !cookie.value().isEmpty());
    if (!hasSession) throw new IOException("百度扫码已确认，但响应缺少登录会话，请重新扫码");
    // A browser navigation URL can replace/delete cookies or require scripts. Once an explicit
    // BDUSS exists, validate it with the personal-drive API instead of navigating that page.
    put(challenge.state, "webSessionReady", true);
    return confirmSession(challenge);
  }

  private LoginResult confirmSession(LoginChallenge challenge) throws IOException {
    JSONObject template = template(challenge.account, true);
    String uk = template.optString("uk");
    if (!uk.matches("[1-9][0-9]{0,31}")) throw new IOException("百度网盘未返回账号标识，请重新扫码");
    String name = template.optString("username");
    put(challenge.account.data, "user_id", uk);
    CloudAccount account =
        new CloudAccount(
            id(), id() + ":" + uk, name.isEmpty() ? label() : name, challenge.account.data);
    challenge.state.remove("webSessionReady");
    return result(Status.AUTHORIZED, account, "百度网盘已连接");
  }

  @Override
  public ListPage list(
      CloudAccount account, String folderId, String pageToken, boolean directoriesOnly)
      throws IOException {
    synchronized (account) {
      requireAccount(account);
      requirePath(folderId);
      int page = page(pageToken);
      JSONObject template = template(account, false);
      JSONObject reply =
          pan(
              account,
              "/api/list",
              template,
              "dir",
              folderId,
              "order",
              "name",
              "desc",
              "0",
              "showempty",
              "0",
              "page",
              Integer.toString(page),
              "num",
              "100");
      // folder=1 selects a compact upstream schema without isdir/server_filename. Read the
      // full schema and filter locally, keeping names, file types and pagination consistent.
      checkError(reply);
      JSONArray list = reply.optJSONArray("list");
      if (list == null) throw new IOException("百度网盘目录响应缺少文件列表");
      List<Entry> entries = new ArrayList<>();
      for (int i = 0; i < list.length(); i++) {
        JSONObject item = list.optJSONObject(i);
        if (item == null) throw new IOException("百度网盘目录中存在无效文件信息");
        boolean directory = item.optInt("isdir") == 1;
        if (directoriesOnly && !directory) continue;
        String path = item.optString("path");
        requirePath(path);
        entries.add(
            new Entry(
                path,
                folderId,
                item.optString("server_filename"),
                directory,
                Math.max(0, item.optLong("size", 0))));
      }
      // A full page can have a successor even when directory filtering removes some entries.
      boolean more = reply.has("has_more") ? reply.optInt("has_more") != 0 : list.length() == 100;
      return new ListPage(entries, more ? Integer.toString(page + 1) : "");
    }
  }

  @Override
  public DownloadLink resolve(CloudAccount account, String fileId) throws IOException {
    synchronized (account) {
      requireAccount(account);
      requirePath(fileId);
      JSONObject template = template(account, false);
      String target = new JSONArray().put(fileId).toString();
      JSONObject metaReply =
          pan(account, "/api/filemetas", template, "target", target, "dlink", "1");
      checkError(metaReply);
      JSONArray infos = metaReply.optJSONArray("info");
      if (infos == null) infos = metaReply.optJSONArray("list");
      JSONObject metadata = infos == null ? null : infos.optJSONObject(0);
      if (metadata == null || metadata.optInt("isdir") == 1) throw new IOException("百度网盘找不到该书籍文件");
      String url = metadata.optString("dlink");
      if (url.isEmpty()) {
        // Baidu's web app asks for sign1/sign2/sign3 and uses RC4(sign3, sign1), then Base64.
        // We implement the algorithm, and never execute code returned in sign2.
        template = template(account, true);
        String sign1 = template.optString("sign1"), sign3 = template.optString("sign3");
        String algorithm = template.optString("sign2");
        if (sign1.isEmpty() || sign3.isEmpty() || !looksLikeRc4(algorithm))
          throw new IOException("百度网盘下载签名已更新，请在官方网页检查文件后重新扫码");
        String fsId = metadata.optString("fs_id");
        if (!fsId.matches("[0-9]+")) throw new IOException("百度网盘文件标识无效");
        String fidList = "[" + fsId + "]";
        JSONObject reply =
            pan(
                account,
                "/api/download",
                template,
                "type",
                "dlink",
                "fidlist",
                fidList,
                "sign",
                rc4Sign(sign3, sign1),
                "timestamp",
                template.optString("timestamp"),
                "vip",
                template.optInt("is_vip") == 1 ? "1" : "0");
        checkError(reply);
        JSONArray links = reply.optJSONArray("dlink");
        JSONObject link = links == null ? null : links.optJSONObject(0);
        url = link == null ? "" : link.optString("dlink");
      }
      HttpUrl parsed = HttpUrl.parse(url);
      if (parsed == null || !parsed.isHttps()) throw new IOException("百度网盘未返回可读取的文件直链");
      long size = metadata.optLong("size", -1);
      if (size < 0) throw new IOException("百度网盘未返回文件大小，无法在线分段阅读");
      Map<String, String> headers = new LinkedHashMap<>();
      headers.put("User-Agent", "pan.baidu.com");
      headers.put("Referer", PAN + "/");
      // Native file reads can require session cookies, confined to matching first-party PCS hosts.
      if (parsed.host().equals("pan.baidu.com")
          || parsed.host().equals("pcs.baidu.com")
          || parsed.host().endsWith(".pcs.baidu.com")) {
        StringBuilder cookieHeader = new StringBuilder();
        for (Cookie cookie : new CloudHttp.SessionCookies(account).loadForRequest(parsed)) {
          if (!cookie.name().equals("BDUSS")
              && !cookie.name().equals("STOKEN")
              && !cookie.name().equals("PTOKEN")) continue;
          if (cookieHeader.length() != 0) cookieHeader.append("; ");
          cookieHeader.append(cookie.name()).append('=').append(cookie.value());
        }
        if (cookieHeader.length() != 0) headers.put("Cookie", cookieHeader.toString());
      }
      String identity =
          fileId
              + ":"
              + size
              + ":"
              + metadata.optString("md5")
              + ":"
              + metadata.optString("server_mtime");
      return new DownloadLink(
          url, headers, size, identity, System.currentTimeMillis() + 10 * 60_000L);
    }
  }

  private JSONObject template(CloudAccount account, boolean refresh) throws IOException {
    JSONObject cached = account.data.optJSONObject("template");
    if (!refresh
        && cached != null
        && !cached.isNull("bdstoken")
        && !cached.optString("bdstoken").isEmpty()
        && account.data.optLong("template_fetched_at") > System.currentTimeMillis() - 10 * 60_000L)
      return cached;
    JSONObject reply = pan(account, "/api/gettemplatevariable", null, "fields", FIELDS);
    JSONObject value;
    if (reply.optInt("errno", -1) == -6) {
      // Current personal-drive sessions can read files while this legacy template endpoint
      // reports -6. Verify with the first-party loginStatus API; never infer login from cookies.
      reply = pan(account, "/api/loginStatus", null);
      checkError(reply);
      value = reply.optJSONObject("login_info");
      if (value != null && !value.isNull("uk_str") && !value.optString("uk_str", "").isEmpty())
        put(value, "uk", value.optString("uk_str"));
    } else {
      checkError(reply);
      value = reply.optJSONObject("result");
    }
    if (value == null || value.isNull("bdstoken") || value.optString("bdstoken").isEmpty())
      throw new IOException("百度网盘会话已失效，请重新扫码");
    put(account.data, "template", value);
    put(account.data, "template_fetched_at", System.currentTimeMillis());
    return value;
  }

  private JSONObject pan(CloudAccount account, String path, JSONObject template, String... pairs)
      throws IOException {
    HttpUrl.Builder url =
        HttpUrl.get(PAN + path)
            .newBuilder()
            .addQueryParameter("clienttype", "0")
            .addQueryParameter("app_id", "250528")
            .addQueryParameter("web", "1")
            .addQueryParameter("channel", "chunlei");
    if (template != null) url.addQueryParameter("bdstoken", template.optString("bdstoken"));
    query(url, pairs);
    return jsonResponse(
        account,
        new Request.Builder()
            .url(url.build())
            .header("Referer", LANDING)
            .header("User-Agent", CloudHttp.USER_AGENT)
            .header("X-Requested-With", "XMLHttpRequest")
            .build());
  }

  private JSONObject passport(CloudAccount account, String path, String... pairs)
      throws IOException {
    return passport(account, path, false, pairs);
  }

  private JSONObject passport(CloudAccount account, String path, boolean post, String... pairs)
      throws IOException {
    HttpUrl.Builder url =
        HttpUrl.get(PASSPORT + path)
            .newBuilder()
            .addQueryParameter("callback", "shufangQrCallback")
            .addQueryParameter("_", now());
    query(url, pairs);
    Request.Builder request =
        new Request.Builder()
            .url(url.build())
            .header("Referer", PAN + "/")
            .header("User-Agent", CloudHttp.USER_AGENT);
    if (post)
      request.post(
          new FormBody.Builder()
              .add("Referer", PAN + "/")
              .add("User-Agent", CloudHttp.USER_AGENT)
              .build());
    return jsonResponse(account, request.build());
  }

  private static void installResponseCookies(CloudAccount account, JSONObject response)
      throws IOException {
    List<Cookie> cookies = new ArrayList<>();
    for (String name : new String[] {"BDUSS", "PTOKEN", "STOKEN"}) {
      String value = findCookie(response, name, 0);
      if (value.isEmpty()) continue;
      if (value.length() > 16 * 1024) throw new IOException("百度网盘返回的登录会话无效");
      try {
        cookies.add(
            new Cookie.Builder()
                .name(name)
                .value(value)
                .domain("baidu.com")
                .path("/")
                .secure()
                .httpOnly()
                .expiresAt(System.currentTimeMillis() + 30 * 24 * 60 * 60_000L)
                .build());
      } catch (IllegalArgumentException e) {
        throw new IOException("百度网盘返回的登录会话无效", e);
      }
    }
    if (!cookies.isEmpty())
      new CloudHttp.SessionCookies(account).saveFromResponse(HttpUrl.get(PASSPORT + "/"), cookies);
  }

  private static String findCookie(JSONObject object, String name, int depth) {
    if (depth > 6) return "";
    java.util.Iterator<String> keys = object.keys();
    while (keys.hasNext()) {
      String key = keys.next();
      Object value = object.opt(key);
      if (key.equalsIgnoreCase(name) && value instanceof String && !((String) value).isEmpty())
        return (String) value;
      JSONObject nested = value instanceof JSONObject ? (JSONObject) value : null;
      if (nested == null
          && value instanceof String
          && ((String) value).length() < 64 * 1024
          && ((String) value).trim().startsWith("{")) {
        try {
          nested = new JSONObject((String) value);
        } catch (JSONException ignored) {
          /* Not a JSON session object. */
        }
      }
      if (nested != null) {
        String found = findCookie(nested, name, depth + 1);
        if (!found.isEmpty()) return found;
      }
    }
    return "";
  }

  private JSONObject jsonResponse(CloudAccount account, Request request) throws IOException {
    // The first-party API emits either JSON or JSONP depending on the endpoint. No script is run.
    String value = body(account, request, false).trim();
    if (!value.startsWith("{")) {
      int open = value.indexOf('('), close = value.lastIndexOf(')');
      if (open < 1
          || close <= open
          || !value.substring(0, open).trim().matches("[a-zA-Z_$][a-zA-Z0-9_.$]*")
          || !value.substring(close + 1).trim().matches(";?"))
        throw new IOException("百度网盘会话暂不可用，请重新扫码");
      value = value.substring(open + 1, close).trim();
    }
    try {
      JSONObject reply = new JSONObject(value);
      io.github.spudhero.shufangtv.cloud.CloudDiagnostics.json(request, reply);
      return reply;
    } catch (JSONException e) {
      throw new IOException("百度网盘返回数据无效，请刷新后重试", e);
    }
  }

  private void visit(CloudAccount account, HttpUrl url) throws IOException {
    body(
        account,
        new Request.Builder()
            .url(url)
            .header("User-Agent", CloudHttp.USER_AGENT)
            .header("Referer", PAN + "/")
            .build(),
        true);
  }

  private String body(CloudAccount account, Request first, boolean redirects) throws IOException {
    Request request = first;
    OkHttpClient client = client(account);
    if (first.url().host().equals("passport.baidu.com")
        && first.url().encodedPath().equals("/channel/unicast")) {
      // An unscanned QR normally returns errno=1 after about 30 seconds. Keep the existing
      // bounded call deadline, but allow the server's long poll to finish before read timeout.
      client = client.newBuilder().readTimeout(40, java.util.concurrent.TimeUnit.SECONDS).build();
    }
    for (int hop = 0; hop < 8; hop++) {
      if (!request.url().isHttps() || !isLoginHost(request.url().host()))
        throw new IOException("百度网盘连接地址无效");
      try (Response response = client.newCall(request).execute()) {
        if (response.isRedirect()) {
          if (!redirects) throw new IOException("百度网盘会话已过期，请重新扫码");
          String location = response.header("Location");
          HttpUrl next = location == null ? null : request.url().resolve(location);
          if (next == null || !next.isHttps() || !isLoginHost(next.host()))
            throw new IOException("百度网盘返回了无效登录跳转");
          request =
              new Request.Builder()
                  .url(next)
                  .header("User-Agent", CloudHttp.USER_AGENT)
                  .header("Referer", PAN + "/")
                  .build();
          continue;
        }
        if (!response.isSuccessful()) throw new IOException("百度网盘服务请求失败（" + response.code() + "）");
        ResponseBody body = response.body();
        if (body == null || body.contentLength() > 4 * 1024 * 1024)
          throw new IOException("百度网盘响应数据过大");
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        int count;
        while ((count = body.byteStream().read(buffer)) != -1) {
          if (bytes.size() + count > 4 * 1024 * 1024) throw new IOException("百度网盘响应数据过大");
          bytes.write(buffer, 0, count);
        }
        return bytes.toString("UTF-8");
      }
    }
    throw new IOException("百度网盘登录跳转过多，请重新扫码");
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

  /** Standard RC4 used solely for Baidu's legacy web request signature, not for secret storage. */
  static String rc4Sign(String key, String input) throws IOException {
    if (key.isEmpty() || key.length() > 4096 || input.length() > 65536)
      throw new IOException("百度网盘下载签名数据无效");
    int[] state = new int[256];
    for (int i = 0; i < state.length; i++) state[i] = i;
    int j = 0;
    for (int i = 0; i < state.length; i++) {
      j = (j + state[i] + key.charAt(i % key.length())) & 255;
      int swap = state[i];
      state[i] = state[j];
      state[j] = swap;
    }
    byte[] bytes = new byte[input.length()];
    int i = 0;
    j = 0;
    for (int offset = 0; offset < input.length(); offset++) {
      i = (i + 1) & 255;
      j = (j + state[i]) & 255;
      int swap = state[i];
      state[i] = state[j];
      state[j] = swap;
      bytes[offset] = (byte) (input.charAt(offset) ^ state[(state[i] + state[j]) & 255]);
    }
    return Base64.getEncoder().encodeToString(bytes);
  }

  private static boolean looksLikeRc4(String value) {
    return value.length() < 16 * 1024
        && value.contains("function")
        && value.contains("256")
        && value.contains("charCodeAt")
        && value.contains("fromCharCode");
  }

  private static boolean isLoginHost(String host) {
    return host.equals("pan.baidu.com")
        || host.equals("passport.baidu.com")
        || host.equals("wappass.baidu.com");
  }

  private void requireAccount(CloudAccount account) throws IOException {
    if (account == null || !id().equals(account.providerId)) throw new IOException("请选择百度网盘账号");
  }

  private void requireChallenge(LoginChallenge challenge) throws IOException {
    if (challenge == null
        || !id().equals(challenge.providerId)
        || challenge.account == null
        || challenge.state.optString("sign").isEmpty()) throw new IOException("百度网盘登录会话无效，请刷新二维码");
  }

  private static void requirePath(String value) throws IOException {
    if (value == null
        || !value.startsWith("/")
        || value.length() > 8192
        || value.indexOf('\0') >= 0) throw new IOException("百度网盘文件路径无效");
  }

  private static int page(String token) throws IOException {
    if (token == null || token.isEmpty()) return 1;
    try {
      int page = Integer.parseInt(token);
      if (page >= 1 && page <= 100_000) return page;
    } catch (NumberFormatException ignored) {
      /* Report a readable error below. */
    }
    throw new IOException("百度网盘翻页标识无效");
  }

  private static void checkError(JSONObject reply) throws IOException {
    int code = reply.optInt("errno", reply.optInt("error_code", -1));
    if (code == 0) return;
    if (code == -6 || code == 111 || code == 112 || code == 113 || code == 110 || code == 31045)
      throw new IOException("百度网盘会话已失效，请重新扫码");
    if (code == -9 || code == 31066) throw new IOException("百度网盘文件已移动或删除，请刷新目录");
    if (code == 1120 || code == 31326 || code == 31329 || code == 50000 || code == -20)
      throw new IOException("百度网盘要求额外验证，请在官方 App 完成验证后重试");
    if (code == 118 || code == 31024) throw new IOException("百度网盘没有该文件的读取权限");
    throw new IOException("百度网盘暂时无法完成请求（" + code + "）");
  }

  private static void query(HttpUrl.Builder url, String... pairs) {
    for (int i = 0; i < pairs.length; i += 2) url.addQueryParameter(pairs[i], pairs[i + 1]);
  }

  private static String now() {
    return Long.toString(System.currentTimeMillis());
  }

  private static JSONObject json(Object... pairs) throws IOException {
    JSONObject value = new JSONObject();
    for (int i = 0; i < pairs.length; i += 2) put(value, (String) pairs[i], pairs[i + 1]);
    return value;
  }

  private static void put(JSONObject object, String key, Object value) throws IOException {
    try {
      object.put(key, value);
    } catch (JSONException e) {
      throw new IOException("百度网盘会话数据无效", e);
    }
  }

  private static LoginResult result(Status status, CloudAccount account, String message) {
    return new LoginResult(status, account, message);
  }
}
