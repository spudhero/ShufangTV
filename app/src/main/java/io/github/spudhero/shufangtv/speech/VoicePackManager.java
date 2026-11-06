package io.github.spudhero.shufangtv.speech;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import okhttp3.*;

/** Application-scoped downloads survive panel dismissal without retaining the Activity. */
public final class VoicePackManager {
  public enum State {
    MISSING,
    DOWNLOADING,
    VERIFYING,
    READY,
    ERROR
  }

  public interface Listener {
    void changed();
  }

  private static VoicePackManager instance;

  public static synchronized VoicePackManager get(Context context) {
    if (instance == null) instance = new VoicePackManager(context.getApplicationContext());
    return instance;
  }

  public final VoicePack pack;
  private final Handler main = new Handler(Looper.getMainLooper());
  private final ExecutorService worker = Executors.newSingleThreadExecutor();
  private final Set<Listener> listeners = new HashSet<>();
  private final OkHttpClient http =
      new OkHttpClient.Builder()
          .connectTimeout(20, TimeUnit.SECONDS)
          .readTimeout(45, TimeUnit.SECONDS)
          .callTimeout(8, TimeUnit.MINUTES)
          .build();
  private volatile State state;
  private volatile long downloaded;
  private volatile String error = "";
  private volatile boolean cancelled;
  private volatile Call call;
  private int readers;
  private long lastProgress;

  private VoicePackManager(Context context) {
    try (InputStream input = context.getAssets().open("speech/aishell3-zh.json")) {
      File parent = new File(context.getFilesDir(), "speech");
      if (!parent.isDirectory() && !parent.mkdirs()) throw new IOException("无法创建语音目录");
      ByteArrayOutputStream manifest = new ByteArrayOutputStream();
      byte[] buffer = new byte[4096];
      int count;
      while ((count = input.read(buffer)) != -1) {
        if (manifest.size() + count > 64 * 1024) throw new IOException("语音包清单过大");
        manifest.write(buffer, 0, count);
      }
      pack = new VoicePack(parent, new String(manifest.toByteArray(), StandardCharsets.UTF_8));
      state = pack.installed() ? State.READY : State.MISSING;
    } catch (IOException e) {
      throw new IllegalStateException("无法读取内置语音包信息", e);
    }
  }

  public State state() {
    return state;
  }

  public long downloaded() {
    return downloaded;
  }

  public String error() {
    return error;
  }

  public boolean busy() {
    return state == State.DOWNLOADING || state == State.VERIFYING;
  }

  public synchronized void listen(Listener listener) {
    listeners.add(listener);
  }

  public synchronized void unlisten(Listener listener) {
    listeners.remove(listener);
  }

  private void changed() {
    main.post(
        () -> {
          for (Listener listener : new ArrayList<>(listeners)) listener.changed();
        });
  }

  public synchronized AutoCloseable acquire() throws IOException {
    if (busy() || !pack.installed()) throw new IOException("请先下载离线中文语音包");
    readers++;
    return () -> {
      synchronized (VoicePackManager.this) {
        readers--;
      }
    };
  }

  public synchronized void download() {
    if (busy()) return;
    if (readers > 0) {
      error = "请先结束朗读或试听，再管理语音包";
      changed();
      return;
    }
    if (pack.directory.getParentFile().getUsableSpace()
        < pack.bytes + pack.downloadBytes + 40 * 1024 * 1024L) {
      error = "存储空间不足，请至少预留 120 MiB 后重试";
      state = State.ERROR;
      changed();
      return;
    }
    cancelled = false;
    downloaded = 0;
    error = "";
    state = State.DOWNLOADING;
    changed();
    worker.execute(
        () -> {
          try {
            pack.install(
                url -> {
                  VoicePack.check(() -> cancelled);
                  Call pending = http.newCall(new Request.Builder().url(url).build());
                  call = pending;
                  Response response = pending.execute();
                  if (!response.isSuccessful() || response.body() == null) {
                    response.close();
                    throw new IOException("语音包下载服务暂不可用，请重试");
                  }
                  return new FilterInputStream(response.body().byteStream()) {
                    @Override
                    public void close() throws IOException {
                      try {
                        super.close();
                      } finally {
                        response.close();
                      }
                    }
                  };
                },
                (done, total) -> {
                  downloaded = done;
                  if (done == total) {
                    state = State.VERIFYING;
                    changed();
                  } else if (SystemClock.elapsedRealtime() - lastProgress >= 250) {
                    lastProgress = SystemClock.elapsedRealtime();
                    changed();
                  }
                },
                () -> cancelled);
            state = State.READY;
          } catch (IOException | RuntimeException e) {
            error = cancelled ? "已取消，可重新下载" : "下载或校验未完成，请检查网络与空间后重试";
            state = pack.installed() ? State.READY : cancelled ? State.MISSING : State.ERROR;
          } finally {
            call = null;
            changed();
          }
        });
  }

  public void cancel() {
    cancelled = true;
    Call pending = call;
    if (pending != null) pending.cancel();
  }

  public synchronized boolean delete() {
    if (busy() || readers > 0) {
      error = "请先结束朗读或试听，再删除语音包";
      changed();
      return false;
    }
    try {
      pack.delete();
      state = State.MISSING;
      error = "";
      changed();
      return true;
    } catch (IOException e) {
      error = "语音包删除未完成，请重试";
      changed();
      return false;
    }
  }
}
