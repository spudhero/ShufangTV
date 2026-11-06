package io.github.spudhero.shufangtv.reader;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import io.github.spudhero.shufangtv.core.Narration;
import io.github.spudhero.shufangtv.core.TextNavigator;
import io.github.spudhero.shufangtv.speech.SpeechEngine;
import io.github.spudhero.shufangtv.speech.SpeechSettings;

/**
 * Owns platform audio resources only while a reader is open. All callbacks run on the UI thread.
 */
public final class ReaderSpeech implements AutoCloseable {
  public interface Listener {
    void ready(boolean available);

    void range(TextNavigator.Range range, int spokenOffset);

    void state(boolean playing);

    void ended();

    void message(String message);
  }

  private final Handler main = new Handler(Looper.getMainLooper());
  private final AudioManager audio;
  private final AudioFocusRequest focus;
  private final MediaSession media;
  private final Narration narration;
  private final Listener listener;
  private final SpeechEngine engine;
  private boolean closed, ready, foreground = true, focused;
  private float rate;
  private String failureMessage = "朗读未完成，请在朗读与语音中检查配置";

  public ReaderSpeech(Context context, float rate, Listener listener) {
    this(context, rate, listener, SpeechSettings.engine(context));
  }

  public ReaderSpeech(Context context, float rate, Listener listener, SpeechEngine engine) {
    this.listener = listener;
    this.engine = engine;
    this.rate = rate;
    audio = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
    AudioAttributes attributes =
        new AudioAttributes.Builder()
            .setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
            .build();
    focus =
        new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(attributes)
            .setWillPauseWhenDucked(true)
            .setOnAudioFocusChangeListener(
                change -> {
                  if (change != AudioManager.AUDIOFOCUS_GAIN) pause();
                },
                main)
            .build();
    media = new MediaSession(context, "ShufangTV reading");
    narration =
        new Narration(
            new Narration.Engine() {
              public boolean speak(String text, long token) {
                return ready && !closed && engine.speak(text, token);
              }

              public void stop() {
                engine.stop();
              }
            },
            new Narration.Listener() {
              public void range(TextNavigator.Range range, int offset) {
                if (!closed) listener.range(range, offset);
              }

              public void state(boolean playing) {
                if (closed) return;
                if (!playing) abandonFocus();
                media.setPlaybackState(
                    new PlaybackState.Builder()
                        .setActions(
                            PlaybackState.ACTION_PLAY
                                | PlaybackState.ACTION_PAUSE
                                | PlaybackState.ACTION_PLAY_PAUSE
                                | PlaybackState.ACTION_STOP
                                | PlaybackState.ACTION_SKIP_TO_NEXT
                                | PlaybackState.ACTION_SKIP_TO_PREVIOUS)
                        .setState(
                            playing ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED,
                            PlaybackState.PLAYBACK_POSITION_UNKNOWN,
                            playing ? ReaderSpeech.this.rate : 0)
                        .build());
                listener.state(playing);
              }

              public void ended() {
                if (!closed) listener.ended();
              }

              public void failed() {
                if (!closed) listener.message(failureMessage);
              }
            });
    media.setCallback(
        new MediaSession.Callback() {
          @Override
          public void onPlay() {
            play();
          }

          @Override
          public void onPause() {
            pause();
          }

          @Override
          public void onStop() {
            pause();
          }

          @Override
          public void onSkipToNext() {
            move(1);
          }

          @Override
          public void onSkipToPrevious() {
            move(-1);
          }
        },
        main);
    engine.initialize(
        rate,
        new SpeechEngine.Listener() {
          public void ready(boolean available) {
            main.post(
                () -> {
                  if (!closed) {
                    ready = available;
                    listener.ready(available);
                  }
                });
          }

          public void done(long token) {
            main.post(
                () -> {
                  if (!closed) narration.done(token);
                });
          }

          public void progress(long token, int offset) {
            main.post(
                () -> {
                  if (!closed) narration.progress(token, offset);
                });
          }

          public void error(long token, String message) {
            main.post(
                () -> {
                  if (closed) return;
                  if (token == 0) {
                    listener.message(message);
                    return;
                  }
                  failureMessage = message == null ? "朗读未完成，请稍后重试" : message;
                  narration.error(token);
                });
          }
        });
  }

  public void load(String text, int offset) {
    if (!closed) narration.load(text, offset);
  }

  public boolean playing() {
    return !closed && narration.playing();
  }

  public void play() {
    if (closed || !foreground) return;
    if (!ready) {
      listener.message("语音尚未就绪，请在朗读与语音中检查语音包或配置");
      return;
    }
    if (!focused)
      focused = audio.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
    if (!focused) {
      listener.message("暂时无法获得音频焦点，请稍后重试");
      return;
    }
    media.setActive(true);
    narration.play();
  }

  public void pause() {
    if (!closed) narration.pause();
  }

  public void move(int direction) {
    boolean resume = playing();
    pause();
    narration.moveParagraph(direction);
    if (resume) play();
  }

  public void rate(float value) {
    rate = value;
    boolean resume = playing();
    pause();
    engine.rate(value);
    if (resume) play();
  }

  public void foreground(boolean value) {
    foreground = value;
    if (!value) {
      pause();
      media.setActive(false);
    }
  }

  private void abandonFocus() {
    if (focused) {
      focused = false;
      audio.abandonAudioFocusRequest(focus);
    }
  }

  @Override
  public void close() {
    if (closed) return;
    pause();
    closed = true;
    ready = false;
    main.removeCallbacksAndMessages(null);
    media.release();
    engine.close();
  }
}
