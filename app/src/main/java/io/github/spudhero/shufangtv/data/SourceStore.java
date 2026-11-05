package io.github.spudhero.shufangtv.data;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import io.github.spudhero.shufangtv.net.SourceConfig;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.*;
import javax.crypto.*;
import javax.crypto.spec.GCMParameterSpec;
import org.json.JSONArray;
import org.json.JSONObject;

/** Connection details stay on the device, encrypted using an Android Keystore key. */
public final class SourceStore {
  private static final String ALIAS = "io.github.spudhero.shufangtv.sources";
  private final SharedPreferences preferences;
  private final SecretKey keyOverride;
  public final List<SourceConfig> sources = new ArrayList<>();
  public String loadError;

  public SourceStore(Context context) {
    this(context, null);
  }

  SourceStore(Context context, SecretKey keyOverride) {
    this.keyOverride = keyOverride;
    preferences = context.getSharedPreferences("connections", Context.MODE_PRIVATE);
    String value = preferences.getString("encrypted", null);
    if (value == null) return;
    try {
      byte[] raw = Base64.decode(value, Base64.NO_WRAP);
      if (raw.length < 29) throw new IOException("加密记录不完整");
      Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
      c.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, raw, 0, 12));
      JSONArray a =
          new JSONArray(new String(c.doFinal(raw, 12, raw.length - 12), StandardCharsets.UTF_8));
      for (int i = 0; i < a.length(); i++) {
        JSONObject j = a.getJSONObject(i);
        SourceConfig s = new SourceConfig();
        s.id = j.getString("id");
        s.name = j.getString("name");
        s.type = SourceConfig.Type.valueOf(j.getString("type"));
        s.host = j.getString("host");
        s.port = j.optInt("port");
        s.share = j.optString("share");
        s.root = j.optString("root");
        s.username = j.optString("username");
        s.password = j.optString("password");
        s.domain = j.optString("domain");
        s.providerId = j.optString("providerId");
        s.accountId = j.optString("accountId");
        s.rootLabel = j.optString("rootLabel");
        s.https = j.optBoolean("https");
        sources.add(s);
      }
    } catch (Exception e) {
      loadError = "连接资料无法解密，请重新添加书库";
    }
  }

  public SourceConfig find(String id) {
    for (SourceConfig c : sources) if (c.id.equals(id)) return c;
    return null;
  }

  public void save() throws IOException {
    try {
      JSONArray a = new JSONArray();
      for (SourceConfig s : sources)
        a.put(
            new JSONObject()
                .put("id", s.id)
                .put("name", s.name)
                .put("type", s.type.name())
                .put("host", s.host)
                .put("port", s.port)
                .put("share", s.share)
                .put("root", s.root)
                .put("username", s.username)
                .put("password", s.password)
                .put("domain", s.domain)
                .put("providerId", s.providerId)
                .put("accountId", s.accountId)
                .put("rootLabel", s.rootLabel)
                .put("https", s.https));
      Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
      c.init(Cipher.ENCRYPT_MODE, key());
      byte[] encrypted = c.doFinal(a.toString().getBytes(StandardCharsets.UTF_8)),
          iv = c.getIV(),
          out = new byte[iv.length + encrypted.length];
      System.arraycopy(iv, 0, out, 0, iv.length);
      System.arraycopy(encrypted, 0, out, iv.length, encrypted.length);
      if (!preferences
          .edit()
          .putString("encrypted", Base64.encodeToString(out, Base64.NO_WRAP))
          .commit()) throw new IOException("无法保存连接");
    } catch (Exception e) {
      throw new IOException("无法加密保存连接资料", e);
    }
  }

  private SecretKey key() throws Exception {
    if (keyOverride != null) return keyOverride;
    KeyStore store = KeyStore.getInstance("AndroidKeyStore");
    store.load(null);
    if (store.containsAlias(ALIAS))
      return ((KeyStore.SecretKeyEntry) store.getEntry(ALIAS, null)).getSecretKey();
    KeyGenerator generator =
        KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
    generator.init(
        new KeyGenParameterSpec.Builder(
                ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .build());
    return generator.generateKey();
  }
}
