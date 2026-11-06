package io.github.spudhero.shufangtv.speech;

/** Backends report progress only for their accepted request token. */
public interface SpeechEngine extends AutoCloseable {
  interface Listener {
    void ready(boolean available);

    void progress(long token, int offset);

    void done(long token);

    void error(long token, String message);
  }

  void initialize(float rate, Listener listener);

  boolean speak(String text, long token);

  void stop();

  void rate(float value);

  @Override
  void close();
}
