package io.github.spudhero.shufangtv.cloud.provider;

import io.github.spudhero.shufangtv.cloud.CloudAccount;
import io.github.spudhero.shufangtv.cloud.CloudHttp;
import io.github.spudhero.shufangtv.cloud.CloudProvider;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import okhttp3.Cookie;
import okhttp3.FormBody;
import okhttp3.HttpUrl;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Independent implementation of the first-party Quark/UC web QR and read-only file protocols. */
public final class QuarkUcProvider implements CloudProvider {
  private static final MediaType JSON = MediaType.get("application/json; charset=utf-8");
  private static final int PAGE_SIZE = 100;
  // A local lifecycle bound; the server's explicit expiry response remains authoritative.
  private static final long LOGIN_WINDOW_MS = 5 * 60_000L;
  private final boolean uc;
  private final OkHttpClient injectedClient;

  public QuarkUcProvider() {
    this(false);
  }

  public QuarkUcProvider(boolean uc) {
    this(uc, null);
  }

  /** Inject a transport for controlled protocol tests; all production URLs stay first-party. */
  public QuarkUcProvider(boolean uc, OkHttpClient client) {
    this.uc = uc;
    this.injectedClient = client;
  }

  @Override
  public String id() {
    return uc ? "uc" : "quark";
  }

  @Override
  public String label() {
    return uc ? "UC 网盘" : "夸克网盘";
  }

  @Override
  public String rootId(CloudAccount account) {
    return "0";
  }

  @Override
  public LoginChallenge beginLogin() throws IOException {
    CloudAccount pending =
        new CloudAccount(id(), "pending-" + UUID.randomUUID(), label(), new JSONObject());
    JSONObject response = cas(pending, "getTokenForQrcodeLogin", null);
    if (response.optLong("status", -1) != 2_000_000L) throw failure("无法生成登录二维码", response);
    JSONObject members = members(response);
    String token = identifier(members.optString("token", ""), "二维码令牌");
    HttpUrl.Builder qr =
        HttpUrl.get(uc ? "https://su.uc.cn/1_n0ZCv" : "https://su.quark.cn/4_eMHBJ")
            .newBuilder()
            .addQueryParameter("token", token)
            .addQueryParameter("client_id", clientId());
    if (uc) {
      qr.addQueryParameter("uc_param_str", "dsdnfrpfbivesscpgimibtbmnijblauputogpintnwktprchmt")
          .addQueryParameter("uc_biz_str", "S:custom|C:titlebar_fix");
    } else {
      qr.addQueryParameter("ssb", "weblogin")
          .addQueryParameter("uc_param_str", "")
          .addQueryParameter(
              "uc_biz_str", "S:custom|OPT:SAREA@0|OPT:IMMERSIVE@1|OPT:BACK_BTN_STYLE@0");
    }
    JSONObject state = new JSONObject();
    put(state, "token", token);
    return new LoginChallenge(
        id(),
        qr.build().toString(),
        System.currentTimeMillis() + LOGIN_WINDOW_MS,
        uc ? 1_000L : 2_000L,
        pending,
        state);
  }

  @Override
  public LoginResult pollLogin(LoginChallenge challenge) throws IOException {
    if (challenge == null
        || !id().equals(challenge.providerId)
        || challenge.account == null
        || challenge.state == null
        || !id().equals(challenge.account.providerId)) {
      throw new IOException("扫码会话不正确，请重新生成二维码。");
    }
    if (challenge.expiresAt > 0 && System.currentTimeMillis() >= challenge.expiresAt) {
      return new LoginResult(Status.EXPIRED, null, "二维码已过期，请刷新后重新扫码。");
    }
    String ticket = challenge.state.optString("service_ticket", "");
    if (ticket.isEmpty()) {
      JSONObject response =
          cas(
              challenge.account,
              "getServiceTicketByQrcodeToken",
              identifier(challenge.state.optString("token", ""), "二维码令牌"));
      long status = response.optLong("status", -1);
      if (status == 50_004_001L)
        return new LoginResult(Status.WAITING, null, "请使用" + label() + "手机应用扫码并确认。");
      if (status == 50_004_002L) return new LoginResult(Status.EXPIRED, null, "二维码已过期，请刷新后重新扫码。");
      if (status != 2_000_000L) throw failure("扫码登录暂时不可用", response);
      ticket = bounded(members(response).optString("service_ticket", ""), 4_096, "登录确认票据");
      put(challenge.state, "service_ticket", ticket);
    }
    HttpUrl accountUrl =
        HttpUrl.get(webOrigin() + "/account/info")
            .newBuilder()
            .addQueryParameter("st", ticket)
            .build();
    JSONObject response = request(challenge.account, headers(accountUrl).get().build());
    if (!response.optBoolean("success", false)) throw failure("官方账号验证未完成，请重新扫码", response);
    JSONObject data = response.optJSONObject("data");
    if (data == null) throw new IOException(label() + "没有返回账号信息，请重新扫码。");
    String uid = accountUid(challenge.account, data);
    if (client(challenge.account)
        .cookieJar()
        .loadForRequest(api("/1/clouddrive/file/sort").build())
        .isEmpty()) {
      throw new IOException(label() + "没有返回可用于书库的登录会话，请重新扫码。");
    }
    // An account-site response and matching cookies do not prove that the personal drive accepts
    // this session. Config validates it and may rotate the cookies used by subsequent file reads.
    JSONObject config =
        request(challenge.account, headers(api("/1/clouddrive/config").build()).get().build());
    checkApi(config);
    if (client(challenge.account)
        .cookieJar()
        .loadForRequest(api("/1/clouddrive/file/sort").build())
        .isEmpty()) {
      throw new IOException(label() + "个人盘会话未完成，请重新扫码。");
    }
    String name = data.optString("nickname", "").trim();
    if (name.isEmpty()) name = label();
    if (name.length() > 80) name = name.substring(0, 80);
    name = name.replaceAll("[\\p{Cntrl}]", "");
    put(challenge.account.data, "uid", uid);
    challenge.state.remove("service_ticket");
    challenge.state.remove("token");
    return new LoginResult(
        Status.AUTHORIZED,
        new CloudAccount(id(), id() + ":" + uid, name, challenge.account.data),
        "已连接" + label() + "。");
  }

  @Override
  public ListPage list(
      CloudAccount account, String folderId, String pageToken, boolean directoriesOnly)
      throws IOException {
    requireAccount(account);
    String parent = folderId == null || folderId.isEmpty() ? "0" : identifier(folderId, "目录编号");
    int page = page(pageToken);
    HttpUrl url =
        api("/1/clouddrive/file/sort")
            .addQueryParameter("pdir_fid", parent)
            .addQueryParameter("_page", Integer.toString(page))
            .addQueryParameter("_size", Integer.toString(PAGE_SIZE))
            .addQueryParameter("_fetch_total", "1")
            .addQueryParameter("_fetch_sub_dirs", "1")
            .addQueryParameter("_sort", "file_type:asc,file_name:asc")
            .build();
    JSONObject response = request(account, headers(url).get().build());
    checkApi(response);
    JSONObject data = response.optJSONObject("data");
    JSONArray items = data == null ? null : data.optJSONArray("list");
    if (items == null || items.length() > 1_000) throw new IOException(label() + "返回的目录格式不正确。");
    List<Entry> entries = new ArrayList<>();
    for (int i = 0; i < items.length(); i++) {
      JSONObject item = items.optJSONObject(i);
      if (item == null || !item.has("file_type")) throw new IOException(label() + "返回的文件信息不完整。");
      int type = item.optInt("file_type", -1);
      if (type != 0 && type != 1) throw new IOException(label() + "返回了无法识别的文件类型。");
      boolean directory = type == 0;
      if (directoriesOnly && !directory) continue;
      String fid = identifier(item.optString("fid", ""), "文件编号");
      String name = bounded(item.optString("file_name", ""), 4_096, "文件名");
      entries.add(
          new Entry(fid, parent, name, directory, directory ? -1 : item.optLong("size", -1)));
    }
    JSONObject metadata = response.optJSONObject("metadata");
    long total = metadata == null ? -1 : metadata.optLong("_total", -1);
    boolean more = total >= 0 ? (long) page * PAGE_SIZE < total : items.length() >= PAGE_SIZE;
    if (more && (items.length() == 0 || page >= 100_000))
      throw new IOException(label() + "返回了不一致的目录分页信息。");
    return new ListPage(entries, more ? Integer.toString(page + 1) : null);
  }

  @Override
  public DownloadLink resolve(CloudAccount account, String fileId) throws IOException {
    requireAccount(account);
    String fid = identifier(fileId, "文件编号");
    JSONObject payload = new JSONObject();
    put(payload, "fids", new JSONArray().put(fid));
    HttpUrl url = api("/1/clouddrive/file/download").build();
    JSONObject response =
        request(account, headers(url).post(RequestBody.create(payload.toString(), JSON)).build());
    checkApi(response);
    JSONArray items = response.optJSONArray("data");
    JSONObject item = items == null || items.length() != 1 ? null : items.optJSONObject(0);
    if (item == null) throw new IOException(label() + "没有返回此书籍的读取链接。");
    if (item.has("fid") && !fid.equals(item.optString("fid", "")))
      throw new IOException(label() + "返回了其他文件的链接，已停止读取。");
    String rawUrl = bounded(item.optString("download_url", ""), 32_768, "读取链接");
    HttpUrl download;
    try {
      download = HttpUrl.get(rawUrl);
    } catch (IllegalArgumentException invalid) {
      throw new IOException(label() + "返回的读取链接不正确。");
    }
    if (!download.isHttps()
        || !download.username().isEmpty()
        || !download.password().isEmpty()
        || download.fragment() != null
        || !allowedDownloadHost(download.host())) {
      throw new IOException(label() + "返回了不受支持的读取地址，已停止连接。");
    }
    Map<String, String> linkHeaders = new LinkedHashMap<>();
    linkHeaders.put("User-Agent", CloudHttp.USER_AGENT);
    linkHeaders.put("Referer", webOrigin() + "/");
    // A signed CDN link may be on another domain. Only the cookie jar decides which cookies match
    // it.
    List<Cookie> cookies = client(account).cookieJar().loadForRequest(download);
    StringBuilder cookie = new StringBuilder();
    for (Cookie value : cookies) {
      if (!value.matches(download)) continue;
      if (cookie.length() != 0) cookie.append("; ");
      cookie.append(value.name()).append('=').append(value.value());
    }
    if (cookie.length() != 0) linkHeaders.put("Cookie", cookie.toString());
    return new DownloadLink(
        download.toString(),
        linkHeaders,
        item.optLong("size", -1),
        account.id + ":" + fid,
        System.currentTimeMillis() + 5 * 60_000L);
  }

  private JSONObject cas(CloudAccount account, String operation, String token) throws IOException {
    HttpUrl url =
        HttpUrl.get(
            (uc ? "https://api.open.uc.cn" : "https://uop.quark.cn") + "/cas/ajax/" + operation);
    Request.Builder request;
    if (uc) {
      FormBody.Builder body =
          new FormBody.Builder()
              .add("client_id", clientId())
              .add("v", "1.2")
              .add("request_id", Long.toString(System.currentTimeMillis()));
      if (token != null) body.add("token", token);
      request =
          headers(url)
              .post(body.build())
              .header("Origin", "https://broccoli.uc.cn/")
              .header("Referer", "https://broccoli.uc.cn/");
    } else {
      HttpUrl.Builder query =
          url.newBuilder()
              .addQueryParameter("client_id", clientId())
              .addQueryParameter("v", "1.2")
              .addQueryParameter("request_id", UUID.randomUUID().toString());
      if (token != null) query.addQueryParameter("token", token);
      request = headers(query.build()).get();
    }
    return request(account, request.build());
  }

  private HttpUrl.Builder api(String path) {
    String origin =
        uc
            ? "https://pc-api.uc.cn"
            : "/1/clouddrive/file/sort".equals(path)
                ? "https://drive-pc.quark.cn"
                : "https://drive.quark.cn";
    return HttpUrl.get(origin + path)
        .newBuilder()
        .addQueryParameter("pr", uc ? "UCBrowser" : "ucpro")
        .addQueryParameter("fr", "pc");
  }

  private Request.Builder headers(HttpUrl url) {
    return new Request.Builder()
        .url(url)
        .header("User-Agent", CloudHttp.USER_AGENT)
        .header("Accept", "application/json")
        .header("Origin", webOrigin())
        .header("Referer", webOrigin() + "/");
  }

  private JSONObject request(CloudAccount account, Request request) throws IOException {
    return CloudHttp.json(client(account), request);
  }

  private OkHttpClient client(CloudAccount account) {
    OkHttpClient bound = CloudHttp.client(account);
    OkHttpClient transport = injectedClient == null ? bound : injectedClient;
    return transport
        .newBuilder()
        .cookieJar(bound.cookieJar())
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false)
        .build();
  }

  private String clientId() {
    return uc ? "381" : "532";
  }

  private String webOrigin() {
    return uc ? "https://drive.uc.cn" : "https://pan.quark.cn";
  }

  private void requireAccount(CloudAccount account) throws IOException {
    if (account == null
        || !id().equals(account.providerId)
        || account.data == null
        || account.id == null
        || account.id.startsWith("pending-")) {
      throw new IOException("请先扫码连接" + label() + "。");
    }
  }

  private String accountUid(CloudAccount account, JSONObject data) throws IOException {
    String uid = data.isNull("uid") ? "" : data.optString("uid", "");
    if (!uid.isEmpty()) return identifier(uid, "账号编号");
    // Current Quark account/info can omit uid. Its first-party web client reads __uid from
    // site cookies; never substitute __kps or another session credential as an account key.
    HttpUrl origin = HttpUrl.get(webOrigin() + "/");
    for (Cookie cookie : client(account).cookieJar().loadForRequest(origin)) {
      if (cookie.name().equals("__uid") && cookie.matches(origin))
        return identifier(cookie.value(), "账号编号");
    }
    throw new IOException(label() + "没有返回账号编号，请重新扫码。");
  }

  private void checkApi(JSONObject response) throws IOException {
    long code = response.optLong("code", Long.MIN_VALUE);
    if (code == 31_001L) throw new IOException(label() + "登录已失效，请重新扫码。");
    if (code != 0 || (response.has("status") && response.optInt("status", -1) != 200)) {
      throw failure("读取请求失败", response);
    }
  }

  private IOException failure(String action, JSONObject response) {
    // Do not surface raw server bodies: they may contain account details, tickets or signed URLs.
    String code =
        response.has("code")
            ? response.optString("code", "未知")
            : response.optString("status", "未知");
    if (!code.matches("[A-Za-z0-9_-]{1,32}")) code = "未知";
    return new IOException(label() + action + "（" + code + "）。");
  }

  private static JSONObject members(JSONObject response) throws IOException {
    JSONObject data = response.optJSONObject("data");
    JSONObject members = data == null ? null : data.optJSONObject("members");
    if (members == null) throw new IOException("官方扫码服务返回的数据不完整，请稍后重试。");
    return members;
  }

  private static String identifier(String value, String field) throws IOException {
    if (value == null || !value.matches("[A-Za-z0-9_-]{1,256}"))
      throw new IOException(field + "不正确，请重新选择或扫码。");
    return value;
  }

  private static String bounded(String value, int limit, String field) throws IOException {
    if (value == null
        || value.isEmpty()
        || value.length() > limit
        || value.matches("(?s).*[\\p{Cntrl}].*")) {
      throw new IOException("服务器返回的" + field + "不正确。");
    }
    return value;
  }

  private static int page(String value) throws IOException {
    if (value == null || value.isEmpty()) return 1;
    if (!value.matches("[1-9][0-9]{0,5}")) throw new IOException("目录页码不正确，请重新打开目录。");
    int page = Integer.parseInt(value);
    if (page > 100_000) throw new IOException("目录页码超出支持范围。");
    return page;
  }

  private static boolean allowedDownloadHost(String host) {
    String[] domains = {
      "quark.cn", "uc.cn", "ucweb.com", "alicdn.com", "aliyuncs.com", "alicloudcs.com"
    };
    for (String domain : domains)
      if (host.equals(domain) || host.endsWith("." + domain)) return true;
    return false;
  }

  private static void put(JSONObject object, String key, Object value) throws IOException {
    try {
      object.put(key, value);
    } catch (JSONException invalid) {
      throw new IOException("无法保存网盘会话状态。", invalid);
    }
  }
}
