package io.github.spudhero.shufangtv.core;

/** Sequential narration with generation tokens: stopped engines may still send late callbacks. */
public final class Narration {
  public interface Engine {
    boolean speak(String text, long token);

    void stop();
  }

  public interface Listener {
    void range(TextNavigator.Range range, int spokenOffset);

    void state(boolean playing);

    void ended();

    void failed();
  }

  private final Engine engine;
  private final Listener listener;
  private String text = "";
  private int cursor;
  private long token;
  private boolean playing;
  private TextNavigator.Range current;

  public Narration(Engine engine, Listener listener) {
    this.engine = engine;
    this.listener = listener;
  }

  public boolean playing() {
    return playing;
  }

  public int position() {
    return cursor;
  }

  public void load(String text, int offset) {
    pause();
    this.text = text;
    cursor = TextNavigator.boundary(text, offset);
    current = null;
  }

  public void play() {
    if (playing) return;
    playing = true;
    listener.state(true);
    speakNext();
  }

  public void pause() {
    token++;
    playing = false;
    current = null;
    engine.stop();
    listener.state(false);
  }

  private void speakNext() {
    current = TextNavigator.passage(text, cursor, 1600);
    if (current == null) {
      playing = false;
      listener.state(false);
      listener.ended();
      return;
    }
    cursor = current.start;
    long request = ++token;
    if (!engine.speak(text.substring(current.start, current.end), request)) {
      error(request);
      return;
    }
    listener.range(current, current.start);
  }

  public void done(long request) {
    if (!playing || request != token || current == null) return;
    cursor = current.end;
    current = null;
    speakNext();
  }

  public void progress(long request, int relativeStart) {
    if (!playing || request != token || current == null) return;
    int at =
        TextNavigator.boundary(
            text, Math.max(current.start, Math.min(current.end, current.start + relativeStart)));
    cursor = at;
    listener.range(current, at);
  }

  public void error(long request) {
    if (!playing || request != token) return;
    pause();
    listener.failed();
  }

  public void moveParagraph(int direction) {
    boolean resume = playing;
    int position = position();
    TextNavigator.Range at = TextNavigator.paragraph(text, position);
    TextNavigator.Range next =
        direction < 0
            ? TextNavigator.previousParagraph(text, at == null ? position : at.start)
            : at == null ? null : TextNavigator.paragraph(text, at.end);
    if (next == null) return;
    pause();
    current = null;
    cursor = next.start;
    listener.range(next, next.start);
    if (resume) play();
  }
}
