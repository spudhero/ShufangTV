package io.github.spudhero.shufangtv.cloud;

import static org.junit.Assert.*;

import java.io.IOException;
import okhttp3.Request;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.shadows.ShadowLog;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 31, manifest = Config.NONE)
public class CloudDiagnosticsTest {
  @Test
  public void diagnosticsExposeOnlyWhitelistedCodesAndNeverCredentialsOrServerMessages()
      throws Exception {
    ShadowLog.clear();
    Request request =
        new Request.Builder()
            .url(
                "https://passport.baidu.com/channel/unicast?channel_id=private-channel&bduss=private-ticket")
            .header("Cookie", "BDUSS=private-cookie")
            .build();
    CloudDiagnostics.json(
        request,
        new JSONObject()
            .put("errno", 1)
            .put("message", "private-server-text")
            .put("channel_v", new JSONObject().put("status", 1).put("v", "private-ticket"))
            .put("data", new JSONObject().put("qrCodeStatus", "private-state")));
    CloudDiagnostics.failure(
        "baidu",
        "qr_poll",
        new IOException(
            "private-url https://secret/",
            new javax.net.ssl.SSLHandshakeException("private-certificate")));
    CloudDiagnostics.json(
        new Request.Builder()
            .url("https://unrelated.invalid/private-path?token=private-query")
            .build(),
        new JSONObject().put("code", "private-code").put("status", "private-status"));
    String logs = ShadowLog.getLogsForTag("CloudDiag").toString();
    assertTrue(logs.contains("provider=baidu op=qr_poll"));
    assertTrue(logs.contains("errno=1"));
    assertTrue(logs.contains("status=1"));
    assertTrue(logs.contains("IOException/SSLHandshakeException"));
    assertFalse(logs.contains("private-"));
    assertFalse(logs.contains("BDUSS"));
    assertFalse(logs.contains("https://"));
  }
}
