package io.github.spudhero.shufangtv.speech;

import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioTrack;
import io.github.spudhero.shufangtv.core.SpeechSegments;
import io.github.spudhero.shufangtv.core.TextNavigator;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.concurrent.*;

/** Bounded synthesis runs ahead of playback; cancellation also invalidates queued PCM. */
public final class PcmSpeechEngine implements SpeechEngine {
  public static final class Clip {
    public final short[] samples;
    public final int sampleRate;

    public Clip(short[] samples, int sampleRate) {
      this.samples = samples;
      this.sampleRate = sampleRate;
    }
  }

  public interface Source extends AutoCloseable {
    void initialize(VoicePack.Cancelled cancelled) throws IOException;

    Clip synthesize(String text, float rate, VoicePack.Cancelled cancelled) throws IOException;

    default void cancel() {}

    @Override
    void close();
  }

  public interface Output extends AutoCloseable {
    void start(int sampleRate) throws IOException;

    int write(short[] samples, int offset, int count) throws IOException;

    long played();

    void stop();

    @Override
    void close();
  }

  public interface OutputFactory {
    Output create();
  }

  private static final class Item {
    final Clip clip;
    final int offset;
    final String error;

    Item(Clip clip, int offset, String error) {
      this.clip = clip;
      this.offset = offset;
      this.error = error;
    }
  }

  private static final Item END = new Item(null, 0, null);

  private static final class Session {
    final long token;
    final float rate;
    final ArrayBlockingQueue<Item> queue = new ArrayBlockingQueue<>(2);
    volatile boolean cancelled;
    volatile Output output;

    Session(long token, float rate) {
      this.token = token;
      this.rate = rate;
    }
  }

  private final Source source;
  private final OutputFactory outputs;
  private final ExecutorService synthesis = Executors.newSingleThreadExecutor();
  private final ExecutorService playback = Executors.newSingleThreadExecutor();
  private Listener listener;
  private volatile boolean ready, closed;
  private volatile float rate;
  private volatile Session current;

  public PcmSpeechEngine(Source source) {
    this(source, TrackOutput::new);
  }

  public PcmSpeechEngine(Source source, OutputFactory outputs) {
    this.source = source;
    this.outputs = outputs;
  }

  public static AudioAttributes attributes() {
    return new AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_MEDIA)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build();
  }

  @Override
  public void initialize(float rate, Listener listener) {
    this.rate = rate;
    this.listener = listener;
    synthesis.execute(
        () -> {
          try {
            source.initialize(() -> closed);
            if (!closed) {
              ready = true;
              listener.ready(true);
            }
          } catch (IOException | RuntimeException | LinkageError e) {
            if (!closed) {
              listener.error(0, "语音初始化失败，请在朗读与语音中检查配置或重新下载语音包");
              listener.ready(false);
            }
          }
        });
  }

  @Override
  public synchronized boolean speak(String text, long token) {
    if (closed || !ready) return false;
    stop();
    Session session = new Session(token, rate);
    current = session;
    synthesis.execute(() -> produce(session, text));
    playback.execute(() -> consume(session));
    return true;
  }

  private boolean active(Session session) {
    return !closed && !session.cancelled && current == session;
  }

  private void put(Session session, Item item) throws InterruptedException {
    while (active(session) && !session.queue.offer(item, 100, TimeUnit.MILLISECONDS)) {
      /* bounded prefetch */
    }
  }

  private void produce(Session session, String text) {
    try {
      for (TextNavigator.Range range : SpeechSegments.split(text)) {
        if (!active(session)) return;
        Clip clip =
            source.synthesize(
                text.substring(range.start, range.end), session.rate, () -> !active(session));
        if (clip == null
            || clip.samples == null
            || clip.samples.length == 0
            || clip.sampleRate < 8000
            || clip.sampleRate > 48000
            || clip.samples.length > clip.sampleRate * 90) throw new IOException("语音服务没有返回有效音频");
        put(session, new Item(clip, range.start, null));
      }
      put(session, END);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    } catch (IOException | RuntimeException | LinkageError e) {
      try {
        put(session, new Item(null, 0, e instanceof IOException ? e.getMessage() : "语音合成失败，请稍后重试"));
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
      }
    }
  }

  private void consume(Session session) {
    Output output = outputs.create();
    session.output = output;
    long written = 0;
    int sampleRate = 0;
    ArrayDeque<long[]> markers = new ArrayDeque<>();
    try {
      while (active(session)) {
        Item item = session.queue.poll(100, TimeUnit.MILLISECONDS);
        if (item == null) continue;
        if (item == END) break;
        if (item.error != null) throw new IOException(item.error);
        if (!active(session)) return;
        if (sampleRate == 0) {
          sampleRate = item.clip.sampleRate;
          output.start(sampleRate);
        } else if (sampleRate != item.clip.sampleRate) throw new IOException("语音采样率发生变化");
        markers.add(new long[] {written, item.offset});
        short[] samples = item.clip.samples;
        for (int offset = 0; offset < samples.length && active(session); ) {
          int n = output.write(samples, offset, Math.min(2048, samples.length - offset));
          if (n < 0) throw new IOException("音频输出中断，请重试");
          if (n == 0) {
            Thread.sleep(5);
            continue;
          }
          offset += n;
          written += n;
          progress(session, output, markers);
        }
      }
      while (active(session) && output.played() < written) {
        progress(session, output, markers);
        Thread.sleep(20);
      }
      progress(session, output, markers);
      if (active(session)) listener.done(session.token);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    } catch (IOException | RuntimeException e) {
      if (active(session)) {
        session.cancelled = true;
        source.cancel();
        listener.error(session.token, e instanceof IOException ? e.getMessage() : "音频播放失败，请重试");
      }
    } finally {
      output.close();
      session.output = null;
      session.queue.clear();
    }
  }

  private void progress(Session session, Output output, ArrayDeque<long[]> markers) {
    while (active(session) && !markers.isEmpty() && output.played() > markers.peek()[0]) {
      listener.progress(session.token, (int) markers.remove()[1]);
    }
  }

  @Override
  public synchronized void stop() {
    Session session = current;
    if (session != null) {
      session.cancelled = true;
      current = null;
      source.cancel();
      session.queue.clear();
      Output output = session.output;
      if (output != null) output.stop();
    }
  }

  @Override
  public void rate(float value) {
    rate = Math.max(.5f, Math.min(2f, value));
  }

  @Override
  public synchronized void close() {
    if (closed) return;
    stop();
    closed = true;
    ready = false;
    synthesis.execute(source::close);
    synthesis.shutdown();
    playback.shutdownNow();
  }

  private static final class TrackOutput implements Output {
    private volatile AudioTrack track;

    @Override
    public void start(int sampleRate) throws IOException {
      int minimum =
          AudioTrack.getMinBufferSize(
              sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
      if (minimum <= 0) throw new IOException("电视不支持此音频输出");
      AudioTrack created =
          new AudioTrack.Builder()
              .setAudioAttributes(attributes())
              .setAudioFormat(
                  new AudioFormat.Builder()
                      .setSampleRate(sampleRate)
                      .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                      .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                      .build())
              .setBufferSizeInBytes(Math.max(minimum, sampleRate / 5 * 2))
              .setTransferMode(AudioTrack.MODE_STREAM)
              .build();
      track = created;
      if (created.getState() != AudioTrack.STATE_INITIALIZED) throw new IOException("音频输出未就绪");
      created.play();
    }

    @Override
    public int write(short[] samples, int offset, int count) {
      return track.write(samples, offset, count, AudioTrack.WRITE_BLOCKING);
    }

    @Override
    public long played() {
      AudioTrack active = track;
      return active == null ? 0 : Integer.toUnsignedLong(active.getPlaybackHeadPosition());
    }

    @Override
    public void stop() {
      AudioTrack active = track;
      if (active != null) {
        try {
          active.pause();
          active.flush();
        } catch (IllegalStateException ignored) {
        }
      }
    }

    @Override
    public void close() {
      AudioTrack active = track;
      track = null;
      if (active != null) {
        try {
          active.stop();
        } catch (IllegalStateException ignored) {
        }
        active.release();
      }
    }
  }
}
