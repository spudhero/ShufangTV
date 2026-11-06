package io.github.spudhero.shufangtv.speech;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import java.util.Locale;

public final class SystemSpeechEngine implements SpeechEngine {
  private final Context context;
  private final Handler main = new Handler(Looper.getMainLooper());
  private TextToSpeech tts;
  private boolean closed, ready;
  private float rate;

  public SystemSpeechEngine(Context context) {
    this.context = context.getApplicationContext();
  }

  @Override
  public void initialize(float rate, Listener listener) {
    this.rate = rate;
    tts =
        new TextToSpeech(
            context,
            status ->
                main.post(
                    () -> {
                      if (closed) return;
                      ready =
                          status == TextToSpeech.SUCCESS
                              && tts.setLanguage(Locale.SIMPLIFIED_CHINESE)
                                  >= TextToSpeech.LANG_AVAILABLE;
                      if (ready) {
                        tts.setAudioAttributes(PcmSpeechEngine.attributes());
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
            event(id, 0, 0);
          }

          @Override
          public void onError(String id) {
            event(id, 1, 0);
          }

          @Override
          public void onRangeStart(String id, int start, int end, int frame) {
            event(id, 2, start);
          }

          private void event(String id, int type, int offset) {
            try {
              long token = Long.parseLong(id);
              if (closed) return;
              if (type == 0) listener.done(token);
              else if (type == 1) listener.error(token, "系统语音播放失败，请检查中文语音数据");
              else listener.progress(token, offset);
            } catch (RuntimeException ignored) {
              /* An unrelated engine callback is not our request. */
            }
          }
        });
  }

  @Override
  public boolean speak(String text, long token) {
    return ready
        && !closed
        && tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, Long.toString(token))
            == TextToSpeech.SUCCESS;
  }

  @Override
  public void stop() {
    if (tts != null) tts.stop();
  }

  @Override
  public void rate(float value) {
    rate = value;
    if (tts != null) tts.setSpeechRate(value);
  }

  @Override
  public void close() {
    if (closed) return;
    closed = true;
    ready = false;
    main.removeCallbacksAndMessages(null);
    if (tts != null) {
      tts.stop();
      tts.shutdown();
    }
  }
}
