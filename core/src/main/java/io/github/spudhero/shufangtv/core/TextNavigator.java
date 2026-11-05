package io.github.spudhero.shufangtv.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Content coordinates are UTF-16, with an exclusive end; never display-page coordinates. */
public final class TextNavigator {
  private TextNavigator() {}

  public static final class Range {
    public final int start, end;

    public Range(int start, int end) {
      this.start = start;
      this.end = end;
    }
  }

  public static final class Matches {
    public final List<Range> ranges;
    public final boolean more;

    Matches(List<Range> ranges, boolean more) {
      this.ranges = Collections.unmodifiableList(ranges);
      this.more = more;
    }
  }

  public static Matches search(String text, String query, int limit) {
    List<Range> ranges = new ArrayList<>();
    query = query.trim();
    if (query.isEmpty() || limit <= 0) return new Matches(ranges, false);
    Matcher matcher =
        Pattern.compile(Pattern.quote(query), Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE)
            .matcher(text);
    while (matcher.find()) {
      if (Thread.currentThread().isInterrupted()) break;
      if (boundary(text, matcher.start()) != matcher.start()
          || boundary(text, matcher.end()) != matcher.end()) continue;
      if (ranges.size() == limit) return new Matches(ranges, true);
      ranges.add(new Range(matcher.start(), matcher.end()));
    }
    return new Matches(ranges, false);
  }

  public static int boundary(String text, int offset) {
    int at = Math.max(0, Math.min(text.length(), offset));
    return at > 0
            && at < text.length()
            && Character.isLowSurrogate(text.charAt(at))
            && Character.isHighSurrogate(text.charAt(at - 1))
        ? at - 1
        : at;
  }

  public static String snippet(String text, Range range) {
    int start = boundary(text, range.start - 24), end = boundary(text, range.end + 36);
    return (start > 0 ? "…" : "")
        + text.substring(start, end).replace('\n', ' ')
        + (end < text.length() ? "…" : "");
  }

  /** A bounded spoken passage, beginning at the requested UTF-16 position. */
  public static Range passage(String text, int position, int maxChars) {
    if (maxChars < 2) throw new IllegalArgumentException("maxChars must be at least 2");
    int start = boundary(text, position);
    while (start < text.length() && Character.isWhitespace(text.charAt(start))) start++;
    if (start == text.length()) return null;
    int newline = text.indexOf('\n', start);
    int end = Math.min(newline < 0 ? text.length() : newline, boundary(text, start + maxChars));
    if (end < text.length() && end - start == maxChars) {
      for (int i = end - 1; i > start + maxChars / 2; i--) {
        if ("。！？；.!?;".indexOf(text.charAt(i)) >= 0) {
          end = i + 1;
          break;
        }
      }
    }
    return new Range(start, end);
  }

  /** A paragraph containing position; very long paragraphs are split into bounded selections. */
  public static Range paragraph(String text, int position) {
    int at = boundary(text, position);
    while (at < text.length() && Character.isWhitespace(text.charAt(at))) at++;
    if (at >= text.length()) return null;
    int start = text.lastIndexOf('\n', Math.max(0, at - 1)) + 1;
    while (start < at && Character.isWhitespace(text.charAt(start))) start++;
    int newline = text.indexOf('\n', start);
    int limit = newline < 0 ? text.length() : newline;
    int end = Math.min(limit, boundary(text, start + 4000));
    while (at >= end && end < limit) {
      start = end;
      end = Math.min(limit, boundary(text, start + 4000));
    }
    return new Range(start, end);
  }

  public static Range previousParagraph(String text, int position) {
    int at = Math.min(text.length(), position) - 1;
    while (at >= 0 && Character.isWhitespace(text.charAt(at))) at--;
    return at < 0 ? null : paragraph(text, at);
  }
}
