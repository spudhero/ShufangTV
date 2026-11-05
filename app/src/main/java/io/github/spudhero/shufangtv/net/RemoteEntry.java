package io.github.spudhero.shufangtv.net;

/** A child path relative to the configured source root; directory sizes may be unknown (-1). */
public final class RemoteEntry {
  public final String name;
  public final String path;
  public final boolean directory;
  public final long size;

  public RemoteEntry(String name, String path, boolean directory, long size) {
    this.name = name;
    this.path = path;
    this.directory = directory;
    this.size = size;
  }
}
