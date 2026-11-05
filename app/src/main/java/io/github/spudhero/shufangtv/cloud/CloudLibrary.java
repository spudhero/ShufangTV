package io.github.spudhero.shufangtv.cloud;

import android.content.Context;
import io.github.spudhero.shufangtv.cloud.provider.AliyunProvider;
import io.github.spudhero.shufangtv.cloud.provider.BaiduProvider;
import io.github.spudhero.shufangtv.cloud.provider.QuarkUcProvider;
import io.github.spudhero.shufangtv.data.CloudAccountStore;
import io.github.spudhero.shufangtv.net.AtomicDownload;
import io.github.spudhero.shufangtv.net.NetworkLibrary;
import io.github.spudhero.shufangtv.net.RemoteEntry;
import io.github.spudhero.shufangtv.net.RemoteFile;
import io.github.spudhero.shufangtv.net.SourceConfig;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import okhttp3.Response;

/** Native provider registry and the bridge into the existing online/offline reader. */
public final class CloudLibrary {
  public interface AccountRepository {
    List<CloudAccount> all();

    CloudAccount find(String id);

    void put(CloudAccount account) throws IOException;

    void remove(String id) throws IOException;
  }

  private static CloudLibrary instance;

  public static synchronized CloudLibrary get(Context context) {
    if (instance == null)
      instance =
          new CloudLibrary(
              new CloudAccountStore(context.getApplicationContext()),
              Arrays.asList(
                  new AliyunProvider(),
                  new BaiduProvider(),
                  new QuarkUcProvider(false),
                  new QuarkUcProvider(true)));
    return instance;
  }

  private final AccountRepository accounts;
  private final Map<String, CloudProvider> providers = new LinkedHashMap<>();

  public CloudLibrary(AccountRepository accounts, List<CloudProvider> providers) {
    this.accounts = accounts;
    for (CloudProvider provider : providers) this.providers.put(provider.id(), provider);
  }

  public List<CloudProvider> providers() {
    return new ArrayList<>(providers.values());
  }

  public synchronized List<CloudAccount> accounts() {
    return accounts.all();
  }

  public synchronized CloudAccount account(String id) throws IOException {
    CloudAccount account = accounts.find(id);
    if (account == null) throw new IOException("网盘账号已退出，请重新扫码登录");
    return account;
  }

  public CloudProvider provider(String id) throws IOException {
    CloudProvider provider = providers.get(id);
    if (provider == null) throw new IOException("此网盘连接暂不可用");
    return provider;
  }

  public synchronized void save(CloudAccount account) throws IOException {
    accounts.put(account);
  }

  public synchronized void logout(String id) throws IOException {
    accounts.remove(id);
  }

  private synchronized void requireCurrent(CloudAccount account) throws IOException {
    if (accounts.find(account.id) != account) throw new IOException("网盘账号已退出或已重新授权，请重新进入书籍来源");
  }

  private synchronized void saveCurrent(CloudAccount account) throws IOException {
    // A late response must never recreate a logged-out account or overwrite a newer login.
    if (accounts.find(account.id) == account) accounts.put(account);
  }

  public CloudProvider.ListPage page(
      CloudAccount account, String folderId, String pageToken, boolean directoriesOnly)
      throws IOException {
    requireCurrent(account);
    try {
      CloudProvider.ListPage result =
          provider(account.providerId).list(account, folderId, pageToken, directoriesOnly);
      requireCurrent(account);
      return result;
    } finally {
      saveCurrent(account);
    }
  }

  public List<RemoteEntry> list(SourceConfig source, String folderId) throws IOException {
    CloudAccount account = checkedAccount(source);
    List<RemoteEntry> entries = new ArrayList<>();
    Set<String> cursors = new HashSet<>();
    String cursor = "";
    do {
      if (!cursors.add(cursor) || cursors.size() > 100 || entries.size() > 10000)
        throw new IOException("此文件夹条目过多，请选择更小的书籍文件夹");
      if (Thread.currentThread().isInterrupted()) throw new IOException("已取消加载");
      CloudProvider.ListPage page =
          page(account, folderId.isEmpty() ? source.root : folderId, cursor, false);
      for (CloudProvider.Entry entry : page.entries)
        entries.add(new RemoteEntry(entry.name, entry.id, entry.directory, entry.size));
      cursor = page.nextPageToken == null ? "" : page.nextPageToken;
    } while (!cursor.isEmpty());
    return entries;
  }

  public RemoteFile open(SourceConfig source, String fileId) throws IOException {
    CloudAccount account = checkedAccount(source);
    return new CloudRangeFile(() -> resolve(account, fileId), () -> requireCurrent(account));
  }

  public void download(
      SourceConfig source, String fileId, File target, NetworkLibrary.Progress progress)
      throws IOException {
    CloudAccount account = checkedAccount(source);
    for (int attempt = 0; attempt < 2; attempt++) {
      CloudProvider.DownloadLink link = resolve(account, fileId);
      try (CloudRangeFile.Transfer transfer = CloudRangeFile.request(link, null)) {
        Response response = transfer.response;
        if ((response.code() == 401 || response.code() == 403) && attempt == 0) continue;
        if (response.code() != 200 || response.body() == null)
          throw new IOException("网盘暂不允许下载此文件（" + response.code() + "），请在网盘端确认权限");
        long total = response.body().contentLength();
        if (link.size > 0 && total >= 0 && link.size != total)
          throw new IOException("书籍已发生变化，请刷新目录后重试");
        AtomicDownload.copy(
            response.body().byteStream(), total >= 0 ? total : link.size, target, progress);
        return;
      }
    }
    throw new IOException("网盘读取地址已失效，请重新扫码");
  }

  private CloudProvider.DownloadLink resolve(CloudAccount account, String fileId)
      throws IOException {
    requireCurrent(account);
    try {
      CloudProvider.DownloadLink result = provider(account.providerId).resolve(account, fileId);
      requireCurrent(account);
      return result;
    } finally {
      saveCurrent(account);
    }
  }

  private CloudAccount checkedAccount(SourceConfig source) throws IOException {
    CloudAccount account = account(source.accountId);
    if (!account.providerId.equals(source.providerId)) throw new IOException("网盘来源与账号不匹配");
    return account;
  }
}
