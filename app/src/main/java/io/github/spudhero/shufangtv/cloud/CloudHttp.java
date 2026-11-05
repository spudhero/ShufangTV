package io.github.spudhero.shufangtv.cloud;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import okhttp3.Cookie;
import okhttp3.CookieJar;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

public final class CloudHttp {
  public static final String USER_AGENT =
      "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 "
          + "(KHTML, like Gecko) Chrome/130.0.0.0 Safari/537.36";
  private static final OkHttpClient BASE =
      new OkHttpClient.Builder()
          .connectTimeout(20, TimeUnit.SECONDS)
          .readTimeout(30, TimeUnit.SECONDS)
          .callTimeout(45, TimeUnit.SECONDS)
          .followRedirects(false)
          .followSslRedirects(false)
          .build();

  private CloudHttp() {}

  public static OkHttpClient client(CloudAccount account) {
    return BASE.newBuilder()
        .cookieJar(new SessionCookies(account))
        .addInterceptor(
            chain ->
                chain.proceed(
                    chain.request().newBuilder().header("User-Agent", USER_AGENT).build()))
        .build();
  }

  public static JSONObject json(OkHttpClient client, Request request) throws IOException {
    requireSecure(request.url());
    Response received;
    try {
      received = client.newCall(request).execute();
    } catch (IOException e) {
      throw new IOException("网盘网络连接未完成，请检查网络后重试", e);
    }
    try (Response response = received) {
      if (response.code() == 401) throw new IOException("网盘登录已失效，请重新扫码");
      if (response.code() == 403) throw new IOException("网盘拒绝此请求，请在手机上确认权限或验证要求");
      if (!response.isSuccessful()) throw new IOException("网盘服务请求失败（" + response.code() + "）");
      ResponseBody body = response.body();
      if (body == null || body.contentLength() > 4 * 1024 * 1024)
        throw new IOException("网盘服务返回了无效数据");
      java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
      byte[] buffer = new byte[8192];
      int n;
      while ((n = body.byteStream().read(buffer)) != -1) {
        if (bytes.size() + n > 4 * 1024 * 1024) throw new IOException("网盘目录响应过大，请选择更小的文件夹");
        bytes.write(buffer, 0, n);
      }
      try {
        return new JSONObject(bytes.toString("UTF-8"));
      } catch (JSONException e) {
        throw new IOException("网盘会话暂不可用，请刷新或重新扫码");
      }
    }
  }

  static void requireSecure(HttpUrl url) throws IOException {
    if (!url.username().isEmpty() || !url.password().isEmpty())
      throw new IOException("网盘返回了不受支持的连接地址");
    if (!url.isHttps()
        && !url.host().equals("localhost")
        && !url.host().equals("127.0.0.1")
        && !url.host().equals("::1")) throw new IOException("网盘连接必须使用 HTTPS");
  }

  /** Cookie matching keeps account cookies away from signed file hosts and unrelated sites. */
  public static final class SessionCookies implements CookieJar {
    private final CloudAccount account;

    public SessionCookies(CloudAccount account) {
      this.account = account;
    }

    @Override
    public List<Cookie> loadForRequest(HttpUrl url) {
      synchronized (account) {
        List<Cookie> cookies = read();
        cookies.removeIf(c -> c.expiresAt() <= System.currentTimeMillis() || !c.matches(url));
        return cookies;
      }
    }

    @Override
    public void saveFromResponse(HttpUrl url, List<Cookie> incoming) {
      synchronized (account) {
        List<Cookie> cookies = read();
        for (Cookie cookie : incoming) {
          cookies.removeIf(
              c ->
                  c.name().equals(cookie.name())
                      && c.domain().equals(cookie.domain())
                      && c.path().equals(cookie.path()));
          if (cookie.expiresAt() > System.currentTimeMillis()) cookies.add(cookie);
        }
        JSONArray array = new JSONArray();
        for (Cookie c : cookies) {
          if (c.expiresAt() <= System.currentTimeMillis()) continue;
          try {
            array.put(
                new JSONObject()
                    .put("name", c.name())
                    .put("value", c.value())
                    .put("domain", c.domain())
                    .put("path", c.path())
                    .put("expires", c.expiresAt())
                    .put("secure", c.secure())
                    .put("httpOnly", c.httpOnly())
                    .put("hostOnly", c.hostOnly()));
          } catch (JSONException e) {
            throw new IllegalStateException(e);
          }
        }
        try {
          account.data.put("cookies", array);
        } catch (JSONException e) {
          throw new IllegalStateException(e);
        }
      }
    }

    private List<Cookie> read() {
      List<Cookie> result = new ArrayList<>();
      JSONArray array = account.data.optJSONArray("cookies");
      if (array == null) return result;
      for (int i = 0; i < array.length(); i++) {
        JSONObject j = array.optJSONObject(i);
        if (j == null) continue;
        try {
          Cookie.Builder b =
              new Cookie.Builder()
                  .name(j.getString("name"))
                  .value(j.getString("value"))
                  .path(j.getString("path"))
                  .expiresAt(j.getLong("expires"));
          if (j.optBoolean("hostOnly")) b.hostOnlyDomain(j.getString("domain"));
          else b.domain(j.getString("domain"));
          if (j.optBoolean("secure")) b.secure();
          if (j.optBoolean("httpOnly")) b.httpOnly();
          result.add(b.build());
        } catch (JSONException | IllegalArgumentException ignored) {
          /* Ignore an invalid saved cookie. */
        }
      }
      return result;
    }
  }
}
