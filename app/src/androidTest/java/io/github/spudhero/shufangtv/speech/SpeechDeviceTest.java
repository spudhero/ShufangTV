package io.github.spudhero.shufangtv.speech;

import android.content.Context;
import android.os.SystemClock;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.*;
import java.nio.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import junit.framework.TestCase;
import org.json.JSONObject;

/** Requires the voice pack installed in the target app; exercises real ARM/JNI and AudioTrack. */
public class SpeechDeviceTest extends TestCase {
  public void testOfflineChineseSynthesisProducesAudioAndHonorsSpeed() throws Exception {
    Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    VoicePackManager manager = VoicePackManager.get(context);
    assertTrue("Install the offline Chinese voice first", manager.pack.installed());
    manager.pack.verify(() -> false);
    File evidence = new File(context.getFilesDir(), "speech-qa");
    assertTrue(evidence.isDirectory() || evidence.mkdirs());
    JSONObject metrics = new JSONObject();
    long started = SystemClock.elapsedRealtime();
    try (AutoCloseable lease = manager.acquire();
        OfflineVoice voice = new OfflineVoice(manager.pack.directory)) {
      metrics
          .put("runtime", OfflineVoice.runtimeVersion())
          .put("sampleRate", voice.sampleRate)
          .put("initializationMs", SystemClock.elapsedRealtime() - started);
      String text = "夜幕降临，书房亮起一盏灯。";
      started = SystemClock.elapsedRealtime();
      short[] normal = voice.synthesize(text, 1f);
      long elapsed = SystemClock.elapsedRealtime() - started;
      assertTrue(normal.length > voice.sampleRate);
      double energy = 0;
      int peak = 0;
      for (short sample : normal) {
        energy += (double) sample * sample;
        peak = Math.max(peak, Math.abs((int) sample));
      }
      double rms = Math.sqrt(energy / normal.length);
      assertTrue("Generated PCM must not be silence", rms > 20);
      writeWave(new File(evidence, "offline-chinese.wav"), normal, voice.sampleRate);
      metrics
          .put("text", text)
          .put("synthesisMs", elapsed)
          .put("audioSeconds", (double) normal.length / voice.sampleRate)
          .put("rtf", (double) elapsed * voice.sampleRate / (1000 * normal.length))
          .put("rms", rms)
          .put("peak", peak);
      short[] fast = voice.synthesize(text, 1.5f);
      assertTrue("Faster speech must have fewer samples", fast.length < normal.length * .9);
      metrics.put("fastAudioSeconds", (double) fast.length / voice.sampleRate);
      short[] dates = voice.synthesize("今天是2026年11月6日，欢迎使用书房。", 1f);
      assertTrue(dates.length > voice.sampleRate);
      writeWave(new File(evidence, "offline-numbers.wav"), dates, voice.sampleRate);
    }
    Files.write(
        new File(evidence, "metrics.json").toPath(),
        metrics.toString(2).getBytes(StandardCharsets.UTF_8));
  }

  public void testRealAudioPlaybackCancellationDoesNotCompleteOldRequest() throws Exception {
    Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    PcmSpeechEngine engine =
        new PcmSpeechEngine(new OfflineSpeechSource(VoicePackManager.get(context)));
    CountDownLatch ready = new CountDownLatch(1), audible = new CountDownLatch(1);
    AtomicBoolean available = new AtomicBoolean();
    AtomicInteger done = new AtomicInteger(), errors = new AtomicInteger();
    engine.initialize(
        1f,
        new SpeechEngine.Listener() {
          public void ready(boolean value) {
            available.set(value);
            ready.countDown();
          }

          public void progress(long token, int offset) {
            audible.countDown();
          }

          public void done(long token) {
            done.incrementAndGet();
          }

          public void error(long token, String message) {
            errors.incrementAndGet();
          }
        });
    try {
      assertTrue(ready.await(90, TimeUnit.SECONDS));
      assertTrue(available.get());
      assertTrue(engine.speak("夜幕降临，书房亮起一盏灯。愿每一页文字，都有温暖的声音。", 41));
      assertTrue("Playback head must advance", audible.await(90, TimeUnit.SECONDS));
      engine.stop();
      Thread.sleep(500);
      assertEquals(0, done.get());
      assertEquals(0, errors.get());
    } finally {
      engine.close();
    }
  }

  private static void writeWave(File file, short[] samples, int sampleRate) throws IOException {
    ByteBuffer bytes = ByteBuffer.allocate(44 + samples.length * 2).order(ByteOrder.LITTLE_ENDIAN);
    bytes
        .put("RIFF".getBytes(StandardCharsets.US_ASCII))
        .putInt(36 + samples.length * 2)
        .put("WAVEfmt ".getBytes(StandardCharsets.US_ASCII))
        .putInt(16)
        .putShort((short) 1)
        .putShort((short) 1)
        .putInt(sampleRate)
        .putInt(sampleRate * 2)
        .putShort((short) 2)
        .putShort((short) 16)
        .put("data".getBytes(StandardCharsets.US_ASCII))
        .putInt(samples.length * 2);
    for (short sample : samples) bytes.putShort(sample);
    Files.write(file.toPath(), bytes.array());
  }
}
