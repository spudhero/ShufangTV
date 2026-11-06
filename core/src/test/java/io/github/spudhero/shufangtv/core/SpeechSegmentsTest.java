package io.github.spudhero.shufangtv.core;

import static org.junit.Assert.*;

import java.util.List;
import org.junit.Test;

public class SpeechSegmentsTest {
  @Test
  public void chunksKeepEveryOriginalCharacterAndNeverSplitSurrogates() {
    String text = "第一句里有🌙和扩展汉字𠮷。" + "长段落没有标点𠮷".repeat(40) + "\n最后一段！";
    List<TextNavigator.Range> chunks = SpeechSegments.split(text);
    StringBuilder joined = new StringBuilder();
    int previous = 0;
    for (TextNavigator.Range chunk : chunks) {
      assertEquals(previous, chunk.start);
      String part = text.substring(chunk.start, chunk.end);
      assertFalse(Character.isLowSurrogate(part.charAt(0)));
      assertFalse(Character.isHighSurrogate(part.charAt(part.length() - 1)));
      assertTrue(part.codePointCount(0, part.length()) <= 36);
      joined.append(part);
      previous = chunk.end;
    }
    assertEquals(text, joined.toString());
    assertTrue(text.codePointCount(0, chunks.get(0).end) <= 16);
  }

  @Test
  public void blankPassagesDoNotRequestSynthesisButOffsetsStillReferToOriginalText() {
    assertTrue(SpeechSegments.split(" \n\n ").isEmpty());
    List<TextNavigator.Range> chunks = SpeechSegments.split("\n\n正文，后半句。\n末句");
    assertEquals(2, chunks.get(0).start);
    assertEquals("正文，后半句。", "\n\n正文，后半句。\n末句".substring(chunks.get(0).start, chunks.get(0).end));
  }
}
