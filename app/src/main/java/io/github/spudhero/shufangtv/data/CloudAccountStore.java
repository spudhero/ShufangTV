package io.github.spudhero.shufangtv.data;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import io.github.spudhero.shufangtv.cloud.CloudAccount;
import io.github.spudhero.shufangtv.cloud.CloudLibrary;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.ArrayList;
import java.util.List;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import org.json.JSONArray;

/** Account sessions never enter library metadata, backups, logs, or plain preferences. */
public final class CloudAccountStore implements CloudLibrary.AccountRepository {
  private static final String ALIAS = "io.github.spudhero.shufangtv.cloud-accounts";
  private final SharedPreferences preferences;
  private final SecretKey keyOverride;
  private final List<CloudAccount> accounts = new ArrayList<>();
  public String loadError;

  public CloudAccountStore(Context context) {
    this(context, null);
  }

  CloudAccountStore(Context context, SecretKey keyOverride) {
    this.keyOverride = keyOverride;
    preferences = context.getSharedPreferences("cloud-accounts", Context.MODE_PRIVATE);
    String encoded = preferences.getString("encrypted", null);
    if (encoded == null) return;
    try {
      byte[] bytes = Base64.decode(encoded, Base64.NO_WRAP);
      if (bytes.length < 29 || bytes.length > 2 * 1024 * 1024) throw new IOException();
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, bytes, 0, 12));
      JSONArray saved =
          new JSONArray(
              new String(cipher.doFinal(bytes, 12, bytes.length - 12), StandardCharsets.UTF_8));
      for (int i = 0; i < saved.length(); i++)
        accounts.add(CloudAccount.fromJson(saved.getJSONObject(i)));
    } catch (Exception e) {
      accounts.clear();
      loadError = "网盘账号资料无法解密，原记录已保留。请在设置中清除本应用数据后重新扫码。";
    }
  }

  @Override
  public synchronized List<CloudAccount> all() {
    return new ArrayList<>(accounts);
  }

  @Override
  public synchronized CloudAccount find(String id) {
    for (CloudAccount account : accounts) if (account.id.equals(id)) return account;
    return null;
  }

  @Override
  public synchronized void put(CloudAccount account) throws IOException {
    if (loadError != null) throw new IOException(loadError);
    CloudAccount previous = find(account.id);
    if (previous == null) accounts.add(account);
    else if (previous != account) accounts.set(accounts.indexOf(previous), account);
    try {
      save();
    } catch (IOException e) {
      if (previous == null) accounts.remove(account);
      else if (previous != account) accounts.set(accounts.indexOf(account), previous);
      throw e;
    }
  }

  @Override
  public synchronized void remove(String id) throws IOException {
    if (loadError != null) throw new IOException(loadError);
    List<CloudAccount> old = new ArrayList<>(accounts);
    accounts.removeIf(a -> a.id.equals(id));
    try {
      save();
    } catch (IOException e) {
      accounts.clear();
      accounts.addAll(old);
      throw e;
    }
  }

  private void save() throws IOException {
    try {
      JSONArray json = new JSONArray();
      for (CloudAccount account : accounts) json.put(account.toJson());
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.ENCRYPT_MODE, key());
      byte[] plain = json.toString().getBytes(StandardCharsets.UTF_8);
      if (plain.length > 1024 * 1024) throw new IOException("网盘账号资料过大");
      byte[] encrypted = cipher.doFinal(plain), iv = cipher.getIV();
      byte[] bytes = new byte[iv.length + encrypted.length];
      System.arraycopy(iv, 0, bytes, 0, iv.length);
      System.arraycopy(encrypted, 0, bytes, iv.length, encrypted.length);
      if (!preferences
          .edit()
          .putString("encrypted", Base64.encodeToString(bytes, Base64.NO_WRAP))
          .commit()) throw new IOException("无法保存网盘账号");
    } catch (Exception e) {
      throw new IOException("无法加密保存网盘账号", e);
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
