package io.github.spudhero.shufangtv.cloud;

import android.content.Context;
import androidx.test.platform.app.InstrumentationRegistry;
import io.github.spudhero.shufangtv.cloud.provider.*;
import io.github.spudhero.shufangtv.data.CloudAccountStore;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import junit.framework.TestCase;
import org.json.JSONArray;
import org.json.JSONObject;

/** Explicit opt-in, read-only network probe. Never runs account requests in normal device tests. */
public class CloudDiagnosticDeviceTest extends TestCase {
  public void testReadOnlyProviderProbe() throws Exception {
    if (!"true".equals(InstrumentationRegistry.getArguments().getString("cloudProbe"))) return;
    Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    CloudAccountStore accounts = new CloudAccountStore(context);
    JSONObject report = new JSONObject().put("accountStoreReadable", accounts.loadError == null);
    JSONArray results = new JSONArray();
    report.put("providers", results);
    for (String id : new String[] {"aliyun", "baidu", "quark", "uc"}) {
      String selected = InstrumentationRegistry.getArguments().getString("cloudProvider");
      if (selected != null && !selected.equals(id)) continue;
      JSONObject result = new JSONObject().put("provider", id);
      results.put(result);
      CloudProvider provider =
          id.equals("aliyun")
              ? new AliyunProvider()
              : id.equals("baidu") ? new BaiduProvider() : new QuarkUcProvider(id.equals("uc"));
      CloudAccount account = null;
      for (CloudAccount value : accounts.all())
        if (value.providerId.equals(id)) {
          account = value;
          break;
        }
      result.put("savedAccount", account != null);
      try {
        if (account != null) {
          result.put("stage", "root_list");
          CloudProvider.ListPage page = provider.list(account, provider.rootId(account), "", true);
          result.put("rootEntries", page.entries.size());
          result.put("hasMore", page.nextPageToken != null && !page.nextPageToken.isEmpty());
          if (id.equals("aliyun") && !page.entries.isEmpty()) {
            result.put("stage", "drive_list");
            page = provider.list(account, page.entries.get(0).id, "", true);
            result.put("driveEntries", page.entries.size());
          }
        } else {
          result.put("stage", "qr_begin");
          CloudProvider.LoginChallenge qr = provider.beginLogin();
          result.put("qrCreated", true);
          result.put("stage", "qr_poll");
          result.put("pollStatus", provider.pollLogin(qr).status.name());
        }
        result.put("success", true);
      } catch (Exception error) {
        result.put("success", false).put("exception", error.getClass().getSimpleName());
        // Local private report only. The provider's top-level message helps identify the failed
        // check.
        result.put("message", error.getMessage());
      } finally {
        // Token refresh / cookie rotation must be retained exactly as in normal app reads.
        if (account != null) accounts.put(account);
      }
    }
    File destination = new File(context.getCacheDir(), "cloud-diagnostic-probe.json");
    Files.write(destination.toPath(), report.toString(2).getBytes(StandardCharsets.UTF_8));
    android.util.Log.i("CloudDiag", "Read-only probe complete; private report saved");
  }
}
