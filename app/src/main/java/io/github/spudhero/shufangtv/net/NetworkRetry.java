package io.github.spudhero.shufangtv.net;

import java.io.EOFException;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import javax.net.ssl.SSLException;

final class NetworkRetry {
  interface Work<T> {
    T run() throws IOException;
  }

  private NetworkRetry() {}

  /** Only read-only, idempotent requests use this path; at most one fresh retry. */
  static <T> T run(Work<T> work) throws IOException {
    for (int attempt = 0; ; attempt++) {
      checkInterrupted();
      try {
        return work.run();
      } catch (IOException failure) {
        checkInterrupted();
        if (attempt == 0 && retryable(failure)) continue;
        if (failure instanceof NetworkFailure || failure instanceof InterruptedIOException)
          throw failure;
        throw new NetworkFailure("连接中断或服务器没有响应，请稍后重试。", false, failure);
      }
    }
  }

  static boolean retryable(IOException failure) {
    if (failure instanceof NetworkFailure) return ((NetworkFailure) failure).retryable;
    if (failure instanceof SSLException) return false;
    if (failure instanceof SocketTimeoutException) return true;
    if (failure instanceof InterruptedIOException) return false;
    return failure instanceof SocketException || failure instanceof EOFException;
  }

  static void checkInterrupted() throws InterruptedIOException {
    if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("已取消网络操作。");
  }
}
