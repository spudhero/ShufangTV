package io.github.spudhero.shufangtv.core;

import static org.junit.Assert.*;

import java.util.ArrayList;
import java.util.List;
import org.junit.Test;

public class NarrationTest {
  @Test
  public void pauseResumesAtLatestEngineRangeWithSafeUnicodeBoundary() {
    Fake f = new Fake();
    Narration n = new Narration(f, f);
    n.load("开头😀继续朗读\n第二段", 0);
    n.play();
    long old = f.token;
    n.progress(old, 3); // The engine supplied the low half of the emoji.
    assertEquals(2, n.position());
    n.pause();
    n.progress(old, 8);
    n.done(old);
    n.play();
    assertEquals("😀继续朗读", f.spoken.get(1));
    assertEquals(2, n.position());
    n.progress(f.token, 2);
    n.pause();
    n.play();
    assertEquals("继续朗读", f.spoken.get(2));
  }

  private static final class Fake implements Narration.Engine, Narration.Listener {
    long token;
    int ends, errors;
    boolean playing;
    final List<String> spoken = new ArrayList<>();
    TextNavigator.Range range;

    public boolean speak(String text, long token) {
      this.token = token;
      spoken.add(text);
      return true;
    }

    public void stop() {}

    public void range(TextNavigator.Range range, int offset) {
      this.range = range;
    }

    public void state(boolean playing) {
      this.playing = playing;
    }

    public void ended() {
      ends++;
    }

    public void failed() {
      errors++;
    }
  }

  @Test
  public void resumesPausedPassageAndIgnoresLateCallbacksAfterChangingBook() {
    Fake f = new Fake();
    Narration n = new Narration(f, f);
    n.load("第一段😀\n\n第二段", 0);
    n.play();
    long old = f.token;
    n.pause();
    n.done(old);
    n.error(old);
    assertEquals(1, f.spoken.size());
    assertEquals(0, f.errors);
    n.play();
    assertEquals(f.spoken.get(0), f.spoken.get(1));
    n.done(f.token);
    assertEquals("第二段", f.spoken.get(2));
    long changed = f.token;
    n.load("新书", 0);
    n.play();
    n.done(changed);
    assertEquals("新书", f.spoken.get(3));
    assertTrue(n.playing());
    n.done(f.token);
    assertFalse(n.playing());
    assertEquals(1, f.ends);
  }

  @Test
  public void longParagraphsNeverDropTextOrSplitSurrogatesAndNavigationKeepsPause() {
    Fake f = new Fake();
    Narration n = new Narration(f, f);
    String text = "甲".repeat(1599) + "😀" + "乙".repeat(3500);
    n.load(text, 0);
    n.play();
    while (n.playing()) n.done(f.token);
    assertEquals(text, String.join("", f.spoken));
    for (String s : f.spoken) {
      assertTrue(s.length() <= 1600);
      assertFalse(Character.isHighSurrogate(s.charAt(s.length() - 1)));
    }
    n.load("一\n\n二\n三", 0);
    n.moveParagraph(1);
    assertEquals(3, n.position());
    assertFalse(n.playing());
    n.moveParagraph(-1);
    assertEquals(0, n.position());
    n.load("\n  \n", 0);
    n.play();
    assertFalse(n.playing());
    assertEquals(2, f.ends);
  }
}
