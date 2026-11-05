package io.github.spudhero.shufangtv.net;

/**
 * User-owned connection settings. Passwords must be persisted only by the platform secret store.
 */
public final class SourceConfig {
  public enum Type {
    SMB,
    WEBDAV,
    CLOUD
  }

  public String id = "";
  public String name = "";
  public String host = "";
  public String share = "";
  public String root = "";
  public String username = "";
  public String password = "";
  public String domain = "";
  public String providerId = "";
  public String accountId = "";
  public String rootLabel = "";
  public int port;
  public boolean https;
  public Type type = Type.SMB;

  public SourceConfig() {}
}
