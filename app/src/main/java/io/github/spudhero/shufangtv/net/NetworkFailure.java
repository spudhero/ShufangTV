package io.github.spudhero.shufangtv.net;

import java.io.IOException;

/** Contains a safe, user-facing message rather than a server body, URL or credential. */
final class NetworkFailure extends IOException {
  final boolean retryable;

  NetworkFailure(String message, boolean retryable) {
    super(message);
    this.retryable = retryable;
  }

  NetworkFailure(String message, boolean retryable, Throwable cause) {
    super(message, cause);
    this.retryable = retryable;
  }
}
