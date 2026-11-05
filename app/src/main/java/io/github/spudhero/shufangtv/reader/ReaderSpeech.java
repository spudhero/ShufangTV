package io.github.spudhero.shufangtv.reader;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import io.github.spudhero.shufangtv.core.Narration;
import io.github.spudhero.shufangtv.core.TextNavigator;
import java.util.Locale;

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
  private TextToSpeech tts;
  private boolean closed, ready, foreground = true, focused;
  private float rate;

  public ReaderSpeech(Context context, float rate, Listener listener) {
    this.listener = listener;
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
                return ready
                    && !closed
                    && tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, Long.toString(token))
                        == TextToSpeech.SUCCESS;
              }

              public void stop() {
                if (tts != null) tts.stop();
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
                if (!closed) listener.message("朗读未完成，请检查系统语音引擎后重试");
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
    tts =
        new TextToSpeech(
            context.getApplicationContext(),
            status ->
                main.post(
                    () -> {
                      if (closed) return;
                      int language =
                          status == TextToSpeech.SUCCESS
                              ? tts.setLanguage(Locale.SIMPLIFIED_CHINESE)
                              : TextToSpeech.ERROR;
                      ready = language >= TextToSpeech.LANG_AVAILABLE;
                      if (ready) {
                        tts.setAudioAttributes(attributes);
                        tts.setSpeechRate(this.rate);
                      }
                      listener.ready(ready);
                    }));
    tts.setOnUtteranceProgressListener(
        new UtteranceProgressListener() {
          @Override
          public void onStart(String id) {}

          @Override
          public void onDone(String id) {
            callback(id, 0, 0);
          }

          @Override
          public void onError(String id) {
            callback(id, 1, 0);
          }

          @Override
          public void onRangeStart(String id, int start, int end, int frame) {
            callback(id, 2, start);
          }
        });
  }

  private void callback(String id, int type, int start) {
    long token;
    try {
      token = Long.parseLong(id);
    } catch (RuntimeException e) {
      return;
    }
    main.post(
        () -> {
          if (closed) return;
          if (type == 0) narration.done(token);
          else if (type == 1) narration.error(token);
          else narration.progress(token, start);
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
      listener.message("语音引擎尚未就绪；请安装或启用中文语音数据");
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
    if (tts != null) tts.setSpeechRate(value);
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
    if (tts != null) {
      tts.shutdown();
      tts = null;
    }
  }
}
