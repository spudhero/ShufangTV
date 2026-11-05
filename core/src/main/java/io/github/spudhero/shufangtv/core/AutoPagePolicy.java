package io.github.spudhero.shufangtv.core;

/** Decides whether a timer may turn a page; user intent survives temporary interruptions. */
public final class AutoPagePolicy {
  private AutoPagePolicy() {}

  /**
   * enabled: feature is on; userPaused: explicit pause; foreground: reading screen active. dialogs:
   * number of reader dialogs; busy: chapter loading; speaking: TTS owns progression. Return true
   * only when automatic page turns may proceed.
   */
  public static boolean mayAdvance(
      boolean enabled,
      boolean userPaused,
      boolean foreground,
      int dialogs,
      boolean busy,
      boolean speaking) {
    if (!enabled || userPaused) return false;
    if (!foreground || dialogs > 0) return false;
    return !busy && !speaking;
  }
}
