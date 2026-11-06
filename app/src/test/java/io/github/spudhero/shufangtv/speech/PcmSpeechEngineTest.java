package io.github.spudhero.shufangtv.speech;

import static org.junit.Assert.*;

import java.io.IOException;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.Test;

public class PcmSpeechEngineTest {
  static class Source implements PcmSpeechEngine.Source {
    CountDownLatch entered = new CountDownLatch(1),
        allow = new CountDownLatch(1),
        closed = new CountDownLatch(1);

    public void initialize(VoicePack.Cancelled cancelled) {}

    public PcmSpeechEngine.Clip synthesize(String text, float rate, VoicePack.Cancelled cancelled)
        throws IOException {
      entered.countDown();
      try {
        if (!allow.await(5, TimeUnit.SECONDS)) throw new IOException("test timeout");
      } catch (InterruptedException e) {
        throw new IOException(e);
      }
      return new PcmSpeechEngine.Clip(new short[1600], 16_000);
    }

    public void close() {
      closed.countDown();
    }
  }

  static class Output implements PcmSpeechEngine.Output {
    AtomicInteger writes = new AtomicInteger();
    AtomicLong submitted = new AtomicLong(), head = new AtomicLong();
    CountDownLatch written = new CountDownLatch(1);

    public void start(int sampleRate) {}

    public int write(short[] samples, int offset, int count) {
      writes.incrementAndGet();
      submitted.addAndGet(count);
      written.countDown();
      return count;
    }

    public long played() {
      return head.get();
    }

    public void stop() {}

    public void close() {}
  }

  static class Events implements SpeechEngine.Listener {
    CountDownLatch ready = new CountDownLatch(1), done = new CountDownLatch(1);
    AtomicInteger errors = new AtomicInteger(), progresses = new AtomicInteger();

    public void ready(boolean value) {
      if (value) ready.countDown();
    }

    public void progress(long token, int offset) {
      progresses.incrementAndGet();
    }

    public void done(long token) {
      done.countDown();
    }

    public void error(long token, String message) {
      errors.incrementAndGet();
    }
  }

  @Test
  public void completionWaitsForPlaybackHeadInsteadOfOnlyFinishingSynthesis() throws Exception {
    Source source = new Source();
    Output output = new Output();
    Events events = new Events();
    PcmSpeechEngine engine = new PcmSpeechEngine(source, () -> output);
    engine.initialize(1f, events);
    assertTrue(events.ready.await(3, TimeUnit.SECONDS));
    source.allow.countDown();
    assertTrue(engine.speak("正文。", 3));
    assertTrue(output.written.await(3, TimeUnit.SECONDS));
    assertFalse(events.done.await(100, TimeUnit.MILLISECONDS));
    assertEquals("Highlight must wait for actual playback", 0, events.progresses.get());
    output.head.set(1600);
    assertTrue(events.done.await(3, TimeUnit.SECONDS));
    assertEquals(1, events.progresses.get());
    engine.close();
    assertTrue(source.closed.await(3, TimeUnit.SECONDS));
  }

  @Test
  public void cancelledNativeResultCannotStartOutputOrCompleteAnOldRequest() throws Exception {
    Source source = new Source();
    Output output = new Output();
    Events events = new Events();
    PcmSpeechEngine engine = new PcmSpeechEngine(source, () -> output);
    engine.initialize(1f, events);
    assertTrue(events.ready.await(3, TimeUnit.SECONDS));
    engine.speak("正在合成的旧片段。", 7);
    assertTrue(source.entered.await(3, TimeUnit.SECONDS));
    engine.stop();
    engine.close();
    source.allow.countDown();
    assertTrue(source.closed.await(3, TimeUnit.SECONDS));
    assertEquals(0, output.writes.get());
    assertEquals(0, events.errors.get());
    assertEquals(1, events.done.getCount());
    assertEquals(0, events.progresses.get());
  }
}
