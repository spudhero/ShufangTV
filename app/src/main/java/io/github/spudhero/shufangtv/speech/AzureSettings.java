package io.github.spudhero.shufangtv.speech;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import javax.crypto.*;
import javax.crypto.spec.GCMParameterSpec;
import org.json.JSONObject;

/**
 * Cloud credentials are kept separate from reading preferences and encrypted with Android Keystore.
 */
public final class AzureSettings {
  public static final String[] VOICES = {"zh-CN-XiaoxiaoNeural", "zh-CN-YunxiNeural"};

  public static final class Config {
    public final String region, voice, key;

    public Config(String region, String voice, String key) throws IOException {
      if (region == null || !region.matches("[a-z][a-z0-9]{1,39}"))
        throw new IOException("请输入 Azure Speech 资源所在地区，如 eastasia");
      if (!java.util.Arrays.asList(VOICES).contains(voice)) throw new IOException("请选择支持的中文音色");
      if (key == null || !key.matches("[A-Za-z0-9+/=_-]{8,256}"))
        throw new IOException("请输入有效的 Azure Speech 密钥");
      this.region = region;
      this.voice = voice;
      this.key = key;
    }

    public String endpoint() {
      return "https://" + region + ".tts.speech.microsoft.com/cognitiveservices/v1";
    }
  }

  private static final String ALIAS = "io.github.spudhero.shufangtv.speech-credentials";
  private final SharedPreferences preferences;
  private final SecretKey keyOverride;

  public AzureSettings(Context context) {
    this(context, null);
  }

  AzureSettings(Context context, SecretKey keyOverride) {
    preferences = context.getSharedPreferences("speech-credentials", Context.MODE_PRIVATE);
    this.keyOverride = keyOverride;
  }

  public Config read() throws IOException {
    String saved = preferences.getString("encrypted", null);
    if (saved == null) return null;
    try {
      byte[] encrypted = Base64.decode(saved, Base64.NO_WRAP);
      if (encrypted.length < 29 || encrypted.length > 4096) throw new IOException();
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, encrypted, 0, 12));
      JSONObject json =
          new JSONObject(
              new String(
                  cipher.doFinal(encrypted, 12, encrypted.length - 12), StandardCharsets.UTF_8));
      return new Config(json.getString("region"), json.getString("voice"), json.getString("key"));
    } catch (Exception e) {
      throw new IOException("网络语音配置无法读取，请重新保存配置", e);
    }
  }

  public boolean configured() {
    try {
      return read() != null;
    } catch (IOException e) {
      return false;
    }
  }

  public void save(Config config) throws IOException {
    try {
      JSONObject json =
          new JSONObject()
              .put("region", config.region)
              .put("voice", config.voice)
              .put("key", config.key);
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.ENCRYPT_MODE, key());
      byte[] payload = cipher.doFinal(json.toString().getBytes(StandardCharsets.UTF_8)),
          iv = cipher.getIV();
      byte[] stored = new byte[iv.length + payload.length];
      System.arraycopy(iv, 0, stored, 0, iv.length);
      System.arraycopy(payload, 0, stored, iv.length, payload.length);
      if (!preferences
          .edit()
          .putString("encrypted", Base64.encodeToString(stored, Base64.NO_WRAP))
          .commit()) throw new IOException();
    } catch (Exception e) {
      throw new IOException("网络语音配置保存失败", e);
    }
  }

  public void clear() throws IOException {
    if (!preferences.edit().clear().commit()) throw new IOException("网络语音配置清除失败");
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
