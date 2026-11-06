package io.github.spudhero.shufangtv.speech;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.*;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream;
import org.json.JSONObject;

/** A fixed, hashed file manifest; an interrupted installation never becomes usable. */
public final class VoicePack {
  public interface Cancelled {
    boolean get();
  }

  public interface Download {
    InputStream open(String url) throws IOException;
  }

  public interface Progress {
    void update(long done, long total);
  }

  public final String id, name;
  public final long bytes;
  public final long downloadBytes;
  public final File directory;
  private final File staging, previous;
  private final String archiveUrl, archiveRoot, archiveHash;
  private final LinkedHashMap<String, Entry> files = new LinkedHashMap<>();

  private static final class Entry {
    final long size;
    final String hash;

    Entry(long size, String hash) {
      this.size = size;
      this.hash = hash;
    }
  }

  public VoicePack(File parent, String manifest) throws IOException {
    try {
      JSONObject json = new JSONObject(manifest);
      id = json.getString("id");
      name = json.getString("name");
      archiveUrl = json.optString("archiveUrl");
      archiveRoot = json.optString("archiveRoot");
      archiveHash = json.optString("archiveSha256");
      if (!id.matches("[a-z0-9-]+")
          || !archiveUrl.equals(
              "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/vits-icefall-zh-aishell3.tar.bz2")
          || !archiveRoot.equals("vits-icefall-zh-aishell3/")
          || !archiveHash.matches("[0-9a-f]{64}")) throw new IOException("无效的语音包来源");
      long total = 0;
      JSONObject list = json.getJSONObject("files");
      for (Iterator<String> keys = list.keys(); keys.hasNext(); ) {
        String path = keys.next();
        JSONObject f = list.getJSONObject(path);
        long size = f.getLong("bytes");
        String hash = f.getString("sha256");
        if (path.startsWith("/")
            || path.contains("..")
            || !path.matches("[A-Za-z0-9_./-]+")
            || size < 1
            || size > 200 * 1024 * 1024L
            || !hash.matches("[0-9a-f]{64}")) throw new IOException("无效的语音包清单");
        files.put(path, new Entry(size, hash));
        total += size;
      }
      if (files.isEmpty()
          || files.size() > 64
          || total > 300 * 1024 * 1024L
          || total != json.getLong("installedBytes")) throw new IOException("语音包清单大小不符");
      bytes = total;
      downloadBytes = json.getLong("archiveBytes");
      if (downloadBytes <= 0 || downloadBytes > 300 * 1024 * 1024L)
        throw new IOException("无效的下载大小");
      directory = new File(parent, id);
      staging = new File(parent, id + ".staging");
      previous = new File(parent, id + ".previous");
      if (!directory.exists() && previous.isDirectory() && !previous.renameTo(directory))
        throw new IOException("语音包恢复失败");
    } catch (IOException e) {
      throw e;
    } catch (Exception e) {
      throw new IOException("无法读取语音包清单", e);
    }
  }

  public boolean installed() {
    try {
      File marker = new File(directory, ".complete");
      if (marker.length() != id.length()
          || !new String(Files.readAllBytes(marker.toPath()), StandardCharsets.UTF_8).equals(id))
        return false;
      for (Map.Entry<String, Entry> f : files.entrySet())
        if (!new File(directory, f.getKey()).isFile()
            || new File(directory, f.getKey()).length() != f.getValue().size) return false;
      return true;
    } catch (IOException e) {
      return false;
    }
  }

  public void verify(Cancelled cancelled) throws IOException {
    if (!installed()) throw new IOException("请先下载完整的离线语音包");
    for (Map.Entry<String, Entry> f : files.entrySet()) {
      check(cancelled);
      if (!hash(new File(directory, f.getKey()), cancelled).equals(f.getValue().hash))
        throw new IOException("语音包校验失败，请删除后重新下载");
    }
  }

  public void install(Download source, Progress progress, Cancelled cancelled) throws IOException {
    remove(staging);
    if (!staging.mkdirs()) throw new IOException("无法创建语音包目录");
    try {
      {
        File archive = new File(staging, ".download.tar.bz2");
        try (InputStream input = source.open(archiveUrl)) {
          copy(
              input,
              archive,
              new Entry(downloadBytes, archiveHash),
              cancelled,
              progress,
              0,
              downloadBytes);
        }
        Set<String> extracted = new HashSet<>();
        long expanded = 0;
        int entries = 0;
        try (TarArchiveInputStream tar =
            new TarArchiveInputStream(
                new BZip2CompressorInputStream(
                    new BufferedInputStream(
                        new FilterInputStream(new FileInputStream(archive)) {
                          @Override
                          public int read(byte[] buffer, int offset, int length)
                              throws IOException {
                            check(cancelled);
                            return super.read(buffer, offset, length);
                          }

                          @Override
                          public int read() throws IOException {
                            check(cancelled);
                            return super.read();
                          }
                        })))) {
          TarArchiveEntry entry;
          while ((entry = tar.getNextEntry()) != null) {
            check(cancelled);
            if (++entries > 64
                || entry.getName().contains("..")
                || entry.isSymbolicLink()
                || entry.isLink()) throw new IOException("无效的语音包条目");
            if (entry.isDirectory()
                && (entry.getName().equals(archiveRoot.substring(0, archiveRoot.length() - 1))
                    || entry.getName().startsWith(archiveRoot))) continue;
            if (!entry.getName().startsWith(archiveRoot)) throw new IOException("无效的语音包目录");
            expanded += entry.getSize();
            if (!entry.isFile() || entry.getSize() < 0 || expanded > 240 * 1024 * 1024L)
              throw new IOException("语音包解压大小超限");
            String path = entry.getName().substring(archiveRoot.length());
            Entry expected = files.get(path);
            // This upstream archive also contains an unused 172 MiB rule.far. Never store it.
            if (expected == null) {
              if (!path.equals("rule.far") || entry.getSize() != 180717014)
                throw new IOException("语音包包含未知条目");
              continue;
            }
            if (!extracted.add(path) || entry.getSize() != expected.size)
              throw new IOException("语音包条目与清单不符");
            try (InputStream input =
                new FilterInputStream(tar) {
                  @Override
                  public void close() {}
                }) {
              copy(input, new File(staging, path), expected, cancelled, (d, t) -> {}, 0, bytes);
            }
          }
        }
        if (extracted.size() != files.size()) throw new IOException("语音包文件不完整");
        if (!archive.delete()) throw new IOException("无法清理下载文件");
      }
      check(cancelled);
      try (FileOutputStream marker = new FileOutputStream(new File(staging, ".complete"))) {
        marker.write(id.getBytes(StandardCharsets.UTF_8));
        marker.getFD().sync();
      }
      remove(previous);
      if (directory.exists() && !directory.renameTo(previous)) throw new IOException("无法替换旧语音包");
      if (!staging.renameTo(directory)) {
        if (previous.exists() && !previous.renameTo(directory)) throw new IOException("语音包恢复失败");
        throw new IOException("无法完成语音包安装");
      }
      remove(previous);
    } finally {
      remove(staging);
    }
  }

  public void delete() throws IOException {
    remove(directory);
    remove(staging);
    remove(previous);
  }

  private static void copy(
      InputStream input,
      File dest,
      Entry expected,
      Cancelled cancelled,
      Progress progress,
      long before,
      long total)
      throws IOException {
    File parent = dest.getParentFile();
    if (!parent.isDirectory() && !parent.mkdirs()) throw new IOException("无法创建语音包目录");
    MessageDigest digest = digest();
    long size = 0;
    try (FileOutputStream output = new FileOutputStream(dest)) {
      byte[] buffer = new byte[64 * 1024];
      int n;
      while ((n = input.read(buffer)) != -1) {
        check(cancelled);
        size += n;
        if (size > expected.size) throw new IOException("语音文件大小超出清单");
        output.write(buffer, 0, n);
        digest.update(buffer, 0, n);
        progress.update(before + size, total);
      }
      output.getFD().sync();
    }
    if (size != expected.size || !hex(digest.digest()).equals(expected.hash))
      throw new IOException("语音文件校验失败，请重试下载");
  }

  static void remove(File file) throws IOException {
    if (!file.exists() && !Files.isSymbolicLink(file.toPath())) return;
    if (file.isDirectory() && !Files.isSymbolicLink(file.toPath())) {
      File[] children = file.listFiles();
      if (children == null) throw new IOException("无法读取语音包目录");
      for (File child : children) remove(child);
    }
    if (!file.delete()) throw new IOException("无法删除语音包文件");
  }

  static void check(Cancelled cancelled) throws IOException {
    if (cancelled.get()) throw new InterruptedIOException("已取消下载");
  }

  private static MessageDigest digest() throws IOException {
    try {
      return MessageDigest.getInstance("SHA-256");
    } catch (Exception e) {
      throw new IOException("无法校验语音包", e);
    }
  }

  private static String hash(File file, Cancelled cancelled) throws IOException {
    MessageDigest digest = digest();
    try (InputStream input = new FileInputStream(file)) {
      byte[] buffer = new byte[64 * 1024];
      int n;
      while ((n = input.read(buffer)) != -1) {
        check(cancelled);
        digest.update(buffer, 0, n);
      }
    }
    return hex(digest.digest());
  }

  private static String hex(byte[] bytes) {
    StringBuilder out = new StringBuilder();
    for (byte b : bytes) out.append(String.format(Locale.ROOT, "%02x", b & 255));
    return out.toString();
  }
}
