package io.github.spudhero.shufangtv.reader;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

import android.content.Context;
import android.media.AudioManager;
import android.os.Bundle;
import android.speech.tts.TextToSpeech;
import io.github.spudhero.shufangtv.core.TextNavigator;
import io.github.spudhero.shufangtv.speech.SystemSpeechEngine;
import java.util.Locale;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadows.ShadowAudioManager;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.shadows.ShadowTextToSpeech;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 31, shadows = ReaderSpeechTest.ControlledTts.class)
public class ReaderSpeechTest {
  @Implements(TextToSpeech.class)
  public static class ControlledTts extends ShadowTextToSpeech {
    String token;
    int calls;

    @Implementation
    protected int speak(CharSequence text, int mode, Bundle params, String id) {
      token = id;
      calls++;
      return TextToSpeech.SUCCESS;
    }
  }

  private static final class Events implements ReaderSpeech.Listener {
    boolean ready;
    int messages, ended, ranges, initializations;

    public void ready(boolean available) {
      ready = available;
      initializations++;
    }

    public void range(TextNavigator.Range range, int offset) {
      ranges++;
    }

    public void state(boolean playing) {}

    public void ended() {
      ended++;
    }

    public void message(String message) {
      messages++;
    }
  }

  @Test
  public void missingChineseDataReportsUnavailableWithoutRequestingFocus() {
    Context context = RuntimeEnvironment.getApplication();
    Events events = new Events();
    ReaderSpeech speech = new ReaderSpeech(context, 1f, events, new SystemSpeechEngine(context));
    ControlledTts tts = (ControlledTts) shadowOf(ShadowTextToSpeech.getLastTextToSpeechInstance());
    tts.getOnInitListener().onInit(TextToSpeech.SUCCESS);
    ShadowLooper.idleMainLooper();
    assertEquals(1, events.initializations);
    assertFalse(events.ready);
    speech.load("正文", 0);
    speech.play();
    assertFalse(speech.playing());
    assertEquals(0, tts.calls);
    assertEquals(1, events.messages);
    ShadowAudioManager audio =
        shadowOf((AudioManager) context.getSystemService(Context.AUDIO_SERVICE));
    assertNull(audio.getLastAudioFocusRequest());
    speech.close();
    assertTrue(tts.isShutdown());
  }

  @Test
  public void focusLossAndBackgroundPauseRequireExplicitResumeAndReleaseResources() {
    Context context = RuntimeEnvironment.getApplication();
    ShadowTextToSpeech.addLanguageAvailability(Locale.SIMPLIFIED_CHINESE);
    Events events = new Events();
    ReaderSpeech speech = new ReaderSpeech(context, 1f, events, new SystemSpeechEngine(context));
    ControlledTts tts = (ControlledTts) shadowOf(ShadowTextToSpeech.getLastTextToSpeechInstance());
    tts.getOnInitListener().onInit(TextToSpeech.SUCCESS);
    ShadowLooper.idleMainLooper();
    assertTrue(events.ready);
    speech.load("第一段\n第二段", 0);
    ShadowAudioManager audio =
        shadowOf((AudioManager) context.getSystemService(Context.AUDIO_SERVICE));
    audio.setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_FAILED);
    speech.play();
    assertFalse(speech.playing());
    assertEquals(1, events.messages);
    assertEquals(0, tts.calls);
    audio.setNextFocusRequestResponse(AudioManager.AUDIOFOCUS_REQUEST_GRANTED);
    speech.play();
    assertTrue(speech.playing());
    String late = tts.token;
    audio
        .getLastAudioFocusRequest()
        .listener
        .onAudioFocusChange(AudioManager.AUDIOFOCUS_LOSS_TRANSIENT_CAN_DUCK);
    assertFalse(speech.playing());
    assertNotNull(audio.getLastAbandonedAudioFocusRequest());
    tts.getUtteranceProgressListener().onDone(late);
    ShadowLooper.idleMainLooper();
    assertEquals(1, tts.calls);
    speech.play();
    speech.foreground(false);
    speech.foreground(true);
    assertFalse(speech.playing());
    assertEquals(2, tts.calls);
    speech.play();
    assertEquals(3, tts.calls);
    speech.close();
    assertTrue(tts.isShutdown());
    tts.getUtteranceProgressListener().onDone(tts.token);
    ShadowLooper.idleMainLooper();
    assertEquals(0, events.ended);
    assertFalse(speech.playing());
  }

  @Test
  public void delayedInitializationAfterCloseCannotRestartAudio() {
    Context context = RuntimeEnvironment.getApplication();
    ShadowTextToSpeech.addLanguageAvailability(Locale.SIMPLIFIED_CHINESE);
    Events events = new Events();
    ReaderSpeech speech = new ReaderSpeech(context, 1f, events, new SystemSpeechEngine(context));
    ControlledTts tts = (ControlledTts) shadowOf(ShadowTextToSpeech.getLastTextToSpeechInstance());
    speech.close();
    tts.getOnInitListener().onInit(TextToSpeech.SUCCESS);
    ShadowLooper.idleMainLooper();
    speech.play();
    assertFalse(events.ready);
    assertEquals(0, tts.calls);
    assertTrue(tts.isShutdown());
  }
}
