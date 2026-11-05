package io.github.spudhero.shufangtv.cloud;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import org.json.JSONObject;

/** First-party account authorization and file access, independent of any storage gateway. */
public interface CloudProvider {
  String id();

  String label();

  String rootId(CloudAccount account);

  LoginChallenge beginLogin() throws IOException;

  LoginResult pollLogin(LoginChallenge challenge) throws IOException;

  ListPage list(CloudAccount account, String folderId, String pageToken, boolean directoriesOnly)
      throws IOException;

  DownloadLink resolve(CloudAccount account, String fileId) throws IOException;

  enum Status {
    WAITING,
    SCANNED,
    AUTHORIZED,
    EXPIRED,
    DENIED
  }

  final class LoginChallenge {
    public final String providerId, qrUrl;
    public final long expiresAt, pollIntervalMs;
    public final CloudAccount account;
    public final JSONObject state;

    public LoginChallenge(
        String providerId,
        String qrUrl,
        long expiresAt,
        long pollIntervalMs,
        CloudAccount account,
        JSONObject state) {
      this.providerId = providerId;
      this.qrUrl = qrUrl;
      this.expiresAt = expiresAt;
      this.pollIntervalMs = pollIntervalMs;
      this.account = account;
      this.state = state;
    }
  }

  final class LoginResult {
    public final Status status;
    public final CloudAccount account;
    public final String message;

    public LoginResult(Status status, CloudAccount account, String message) {
      this.status = status;
      this.account = account;
      this.message = message;
    }
  }

  final class Entry {
    public final String id, parentId, name;
    public final boolean directory;
    public final long size;

    public Entry(String id, String parentId, String name, boolean directory, long size) {
      this.id = id;
      this.parentId = parentId;
      this.name = name;
      this.directory = directory;
      this.size = size;
    }
  }

  final class ListPage {
    public final List<Entry> entries;
    public final String nextPageToken;

    public ListPage(List<Entry> entries, String nextPageToken) {
      this.entries = java.util.Collections.unmodifiableList(new java.util.ArrayList<>(entries));
      this.nextPageToken = nextPageToken;
    }
  }

  final class DownloadLink {
    public final String url, identity;
    public final Map<String, String> headers;
    public final long size, expiresAt;

    public DownloadLink(
        String url, Map<String, String> headers, long size, String identity, long expiresAt) {
      this.url = url;
      this.headers = java.util.Collections.unmodifiableMap(new java.util.HashMap<>(headers));
      this.size = size;
      this.identity = identity;
      this.expiresAt = expiresAt;
    }
  }
}
