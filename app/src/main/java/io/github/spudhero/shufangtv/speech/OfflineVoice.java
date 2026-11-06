package io.github.spudhero.shufangtv.speech;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/** Owned by one synthesis worker; never destroy a handle while generation is running. */
public final class OfflineVoice implements AutoCloseable {
  static {
    System.loadLibrary("shufang_speech");
  }

  private long handle;
  public final int sampleRate;

  public OfflineVoice(File directory) throws IOException {
    handle = create(directory.getAbsolutePath());
    sampleRate = sampleRate(handle);
    if (handle == 0 || sampleRate < 8000 || sampleRate > 48000) {
      close();
      throw new IOException("离线语音初始化失败");
    }
  }

  public short[] synthesize(String text, float rate) throws IOException {
    return generate(handle, text.getBytes(StandardCharsets.UTF_8), rate);
  }

  @Override
  public void close() {
    if (handle != 0) {
      destroy(handle);
      handle = 0;
    }
  }

  private static native long create(String directory) throws IOException;

  private static native int sampleRate(long handle);

  private static native short[] generate(long handle, byte[] text, float rate) throws IOException;

  private static native void destroy(long handle);

  public static native String runtimeVersion();
}
