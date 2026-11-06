package io.github.spudhero.shufangtv.reader;

import java.io.IOException;

/** Narrow JNI boundary; archive ownership and byte decoding remain in Java. */
final class NativeMobi {
  static {
    System.loadLibrary("mobi");
    System.loadLibrary("shufang_mobi");
  }

  static native long open(int fd, long length) throws IOException;

  static native String[] paths(long handle) throws IOException;

  static native byte[] read(long handle, String path) throws IOException;

  static native void close(long handle);

  private NativeMobi() {}
}
