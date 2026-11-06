package io.github.spudhero.shufangtv.core;

import java.util.ArrayList;
import java.util.List;

/** Short, bounded synthesis requests while retaining original UTF-16 reading offsets. */
public final class SpeechSegments {
  private SpeechSegments() {}

  public static List<TextNavigator.Range> split(String text) {
    List<TextNavigator.Range> result = new ArrayList<>();
    int start = 0;
    while (start < text.length()) {
      int end = start, count = 0, limit = result.isEmpty() ? 16 : 36;
      while (end < text.length() && count < limit) {
        int c = text.codePointAt(end);
        end += Character.charCount(c);
        count++;
        if ("。！？!?；;\n".indexOf(c) >= 0 || (count >= 10 && "，,：:".indexOf(c) >= 0)) break;
      }
      if (!text.substring(start, end).trim().isEmpty())
        result.add(new TextNavigator.Range(start, end));
      start = end;
    }
    return result;
  }
}
