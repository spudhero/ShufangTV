package io.github.spudhero.shufangtv.cloud;

import android.util.Log;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import okhttp3.Interceptor;
import okhttp3.Request;
import okhttp3.Response;
import org.json.JSONObject;

/** Only fixed operation names, numeric status codes and exception classes may enter logcat. */
public final class CloudDiagnostics {
  private static final Map<String, Long> RECENT = new LinkedHashMap<>();
  private static final Set<String> STATES =
      Set.of(
          "NEW",
          "SCANED",
          "SCANNED",
          "CONFIRMED",
          "EXPIRED",
          "CANCELED",
          "ERROR",
          "WAITING",
          "AUTHORIZED",
          "DENIED");
  private static final Set<String> ALI_CODES =
      Set.of(
          "AccessTokenInvalid",
          "AccessTokenExpired",
          "InvalidParameter.AccessToken",
          "DeviceSessionSignatureInvalid",
          "DeviceSessionSignatureExpired",
          "DeviceSessionNotFound",
          "InvalidParameter",
          "Forbidden",
          "InvalidDriveId",
          "NotFound",
          "TooManyRequests");

  private CloudDiagnostics() {}

  public static Interceptor network(String provider) {
    return chain -> {
      Request request = chain.request();
      String operation = operation(request);
      try {
        Response response = chain.proceed(request);
        record("provider=" + provider(provider) + " op=" + operation + " http=" + response.code());
        return response;
      } catch (IOException error) {
        failure(provider, operation, error);
        throw error;
      }
    };
  }

  public static void json(Request request, JSONObject reply) {
    StringBuilder fields = new StringBuilder();
    numeric(fields, reply, "code");
    numeric(fields, reply, "errno");
    numeric(fields, reply, "status");
    if (reply.has("code") && reply.isNull("code")) fields.append(" code=null");
    JSONObject error = reply.optJSONObject("errInfo");
    if (error != null) numeric(fields, error, "no");
    JSONObject content = reply.optJSONObject("content");
    JSONObject data = content == null ? reply.optJSONObject("data") : content.optJSONObject("data");
    if (data != null) state(fields, data.optString("qrCodeStatus"));
    if (ALI_CODES.contains(reply.optString("code")))
      fields.append(" code=").append(reply.optString("code"));
    Object success = reply.opt("success");
    if (success instanceof Boolean) fields.append(" success=").append(success);
    Object channel = reply.opt("channel_v");
    try {
      JSONObject value =
          channel instanceof JSONObject
              ? (JSONObject) channel
              : channel instanceof String ? new JSONObject((String) channel) : null;
      if (value != null) numeric(fields, value, "status");
    } catch (org.json.JSONException ignored) {
      /* Never log unparsed response content. */
    }
    if (fields.length() > 0)
      record("provider=" + providerFor(request) + " op=" + operation(request) + fields);
  }

  public static void failure(String provider, String operation, Throwable error) {
    // Never log Throwable.toString(), its message or stack: they may contain authenticated URLs.
    StringBuilder types = new StringBuilder();
    for (int i = 0; error != null && i < 4; i++, error = error.getCause()) {
      if (i != 0) types.append('/');
      types.append(error.getClass().getSimpleName().replaceAll("[^A-Za-z0-9_]", ""));
    }
    String phase =
        Set.of(
                    "qr_begin",
                    "qr_poll",
                    "qr_exchange",
                    "account",
                    "account_legacy",
                    "config",
                    "list",
                    "download_link",
                    "token",
                    "device",
                    "login",
                    "other")
                .contains(operation)
            ? operation
            : "other";
    record("provider=" + provider(provider) + " op=" + phase + " failure=" + types);
  }

  private static void numeric(StringBuilder fields, JSONObject object, String key) {
    Object value = object.opt(key);
    String text = String.valueOf(value);
    if (text.matches("-?[0-9]{1,10}")) fields.append(' ').append(key).append('=').append(text);
  }

  private static void state(StringBuilder fields, String value) {
    if (STATES.contains(value)) fields.append(" state=").append(value);
  }

  private static String provider(String id) {
    return Set.of("aliyun", "baidu", "quark", "uc").contains(id) ? id : "unknown";
  }

  private static String providerFor(Request request) {
    String host = request.url().host();
    if (host.endsWith(".baidu.com") || host.equals("baidu.com")) return "baidu";
    if (host.endsWith(".alipan.com") || host.endsWith(".aliyundrive.com")) return "aliyun";
    if (host.endsWith(".quark.cn")) return "quark";
    if (host.endsWith(".uc.cn")) return "uc";
    return "unknown";
  }

  private static String operation(Request request) {
    switch (request.url().encodedPath()) {
      case "/mini_login.htm":
      case "/newlogin/qrcode/generate.do":
      case "/v2/api/getqrcode":
      case "/cas/ajax/getTokenForQrcodeLogin":
        return "qr_begin";
      case "/newlogin/qrcode/query.do":
      case "/channel/unicast":
      case "/cas/ajax/getServiceTicketByQrcodeToken":
        return "qr_poll";
      case "/v3/login/main/qrbdusslogin":
        return "qr_exchange";
      case "/account/info":
      case "/v2/user/get":
      case "/api/loginStatus":
        return "account";
      case "/api/gettemplatevariable":
        return "account_legacy";
      case "/1/clouddrive/config":
        return "config";
      case "/adrive/v3/file/list":
      case "/api/list":
      case "/1/clouddrive/file/sort":
        return "list";
      case "/v2/file/get_download_url":
      case "/api/download":
      case "/api/filemetas":
      case "/1/clouddrive/file/download":
        return "download_link";
      case "/v2/account/token":
        return "token";
      case "/users/v1/users/device/create_session":
        return "device";
      default:
        return "other";
    }
  }

  private static synchronized void record(String value) {
    long now = android.os.SystemClock.elapsedRealtime();
    Long previous = RECENT.get(value);
    if (previous != null && now - previous < 30_000) return;
    RECENT.put(value, now);
    while (RECENT.size() > 64) RECENT.remove(RECENT.keySet().iterator().next());
    Log.i("CloudDiag", value);
  }
}
