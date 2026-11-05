package io.github.spudhero.shufangtv.core;

import static org.junit.Assert.*;

import org.junit.Test;

public class TextNavigatorTest {
  @Test
  public void literalSearchKeepsOriginalUnicodeCoordinates() {
    String text = "开头🌱 A.b 不是 axb，后面 a.B。\n🌱";
    TextNavigator.Matches matches = TextNavigator.search(text, "a.b", 200);
    assertEquals(2, matches.ranges.size());
    assertEquals(text.indexOf("A.b"), matches.ranges.get(0).start);
    assertEquals("a.B", text.substring(matches.ranges.get(1).start, matches.ranges.get(1).end));
    assertEquals(2, TextNavigator.search(text, "🌱", 200).ranges.size());
    assertTrue(TextNavigator.search(text, "\udf31", 200).ranges.isEmpty());
  }

  @Test
  public void resultLimitReportsPartialSearchAndEmptyInputDoesNotMatchEverything() {
    TextNavigator.Matches matches = TextNavigator.search("读书\n读书\n读书", "书", 2);
    assertEquals(2, matches.ranges.size());
    assertTrue(matches.more);
    assertFalse(TextNavigator.search("书书", "书", 2).more);
    assertTrue(TextNavigator.search("正文", "  ", 200).ranges.isEmpty());
    assertTrue(TextNavigator.search("正文", "别处", 200).ranges.isEmpty());
  }
}
