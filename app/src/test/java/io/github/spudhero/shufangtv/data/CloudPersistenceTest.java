package io.github.spudhero.shufangtv.data;

import static org.junit.Assert.*;

import android.content.Context;
import android.util.Base64;
import io.github.spudhero.shufangtv.cloud.*;
import io.github.spudhero.shufangtv.net.SourceConfig;
import java.io.IOException;
import java.util.Collections;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import okhttp3.Cookie;
import okhttp3.HttpUrl;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 31)
public class CloudPersistenceTest {
  private final Context context = RuntimeEnvironment.getApplication();
  // Controlled AES key only for JVM storage tests; production uses AndroidKeyStore.
  private final SecretKey key = new SecretKeySpec(new byte[32], "AES");

  @Test
  public void sessionsRoundTripEncryptedWithCookieScopeIntact() throws Exception {
    CloudAccount account = new CloudAccount("quark", "quark:123", "测试账号", new JSONObject());
    Cookie cookie =
        new Cookie.Builder()
            .name("session")
            .value("fixture-session-secret")
            .hostOnlyDomain("drive-pc.quark.cn")
            .path("/books")
            .secure()
            .httpOnly()
            .expiresAt(System.currentTimeMillis() + 60000)
            .build();
    new CloudHttp.SessionCookies(account)
        .saveFromResponse(
            HttpUrl.get("https://drive-pc.quark.cn/books"), Collections.singletonList(cookie));
    CloudAccountStore store = new CloudAccountStore(context, key);
    store.put(account);
    String persisted = context.getSharedPreferences("cloud-accounts", 0).getString("encrypted", "");
    assertFalse(persisted.contains("fixture-session-secret"));
    assertFalse(persisted.contains("测试账号"));
    CloudAccount restored = new CloudAccountStore(context, key).find(account.id);
    assertNotNull(restored);
    CloudHttp.SessionCookies jar = new CloudHttp.SessionCookies(restored);
    assertEquals(1, jar.loadForRequest(HttpUrl.get("https://drive-pc.quark.cn/books/1")).size());
    assertTrue(jar.loadForRequest(HttpUrl.get("https://pan.quark.cn/books/1")).isEmpty());
    assertTrue(jar.loadForRequest(HttpUrl.get("https://drive-pc.quark.cn/other")).isEmpty());
    assertTrue(jar.loadForRequest(HttpUrl.get("http://drive-pc.quark.cn/books/1")).isEmpty());
  }

  @Test
  public void tamperedCiphertextIsPreservedAndCannotBeOverwrittenByLogin() throws Exception {
    CloudAccountStore store = new CloudAccountStore(context, key);
    CloudAccount account = new CloudAccount("quark", "quark:123", "账号", new JSONObject());
    store.put(account);
    String encoded = context.getSharedPreferences("cloud-accounts", 0).getString("encrypted", "");
    byte[] bytes = Base64.decode(encoded, Base64.NO_WRAP);
    bytes[bytes.length - 1] ^= 1;
    String tampered = Base64.encodeToString(bytes, Base64.NO_WRAP);
    context
        .getSharedPreferences("cloud-accounts", 0)
        .edit()
        .putString("encrypted", tampered)
        .commit();
    CloudAccountStore damaged = new CloudAccountStore(context, key);
    assertNotNull(damaged.loadError);
    assertTrue(damaged.all().isEmpty());
    try {
      damaged.put(account);
      fail();
    } catch (IOException expected) {
      assertTrue(expected.getMessage().contains("原记录"));
    }
    assertEquals(
        tampered, context.getSharedPreferences("cloud-accounts", 0).getString("encrypted", ""));
  }

  @Test
  public void equalUserIdsFromDifferentProvidersDoNotOverwriteAccounts() throws Exception {
    CloudAccountStore store = new CloudAccountStore(context, key);
    store.put(new CloudAccount("quark", "quark:123", "夸克", new JSONObject()));
    store.put(new CloudAccount("uc", "uc:123", "UC", new JSONObject()));
    CloudAccountStore restored = new CloudAccountStore(context, key);
    assertEquals(2, restored.all().size());
    restored.remove("quark:123");
    assertNotNull(new CloudAccountStore(context, key).find("uc:123"));
  }

  @Test
  public void cloudFolderIdsAndExistingNetworkSourcesSurviveRoundTrip() throws Exception {
    SourceStore store = new SourceStore(context, key);
    SourceConfig cloud = new SourceConfig();
    cloud.id = "cloud-source";
    cloud.type = SourceConfig.Type.CLOUD;
    cloud.name = "书籍";
    cloud.providerId = "aliyun";
    cloud.accountId = "aliyun:123";
    cloud.root = "drive-id:opaque/file+id?";
    cloud.rootLabel = "我的藏书";
    SourceConfig smb = new SourceConfig();
    smb.id = "smb-source";
    smb.host = "192.168.1.2";
    smb.password = "fixture-password";
    smb.share = "Books";
    store.sources.add(cloud);
    store.sources.add(smb);
    store.save();
    SourceStore restored = new SourceStore(context, key);
    assertNull(restored.loadError);
    assertEquals(2, restored.sources.size());
    assertEquals(cloud.root, restored.find(cloud.id).root);
    assertEquals(cloud.accountId, restored.find(cloud.id).accountId);
    assertEquals(cloud.rootLabel, restored.find(cloud.id).rootLabel);
    assertEquals("fixture-password", restored.find(smb.id).password);
  }
}
