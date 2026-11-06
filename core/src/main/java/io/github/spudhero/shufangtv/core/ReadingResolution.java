package io.github.spudhero.shufangtv.core;

/** A viewport-sized buffer: UI coordinates stay stable while content gains physical pixels. */
public final class ReadingResolution {
  public final int width, height;
  public final float scale;

  private ReadingResolution(int width, int height, float scale) {
    this.width = width;
    this.height = height;
    this.scale = scale;
  }

  public static ReadingResolution choose(
      int width,
      int height,
      int uiWidth,
      int uiHeight,
      int panelWidth,
      int panelHeight,
      boolean automatic) {
    width = Math.max(1, width);
    height = Math.max(1, height);
    float scale = 1;
    if (automatic && uiWidth > 0 && uiHeight > 0) {
      scale = Math.max(1, Math.min((float) panelWidth / uiWidth, (float) panelHeight / uiHeight));
      scale = Math.min(scale, Math.min(3840f / width, 2160f / height));
      scale = Math.max(1, scale);
    }
    return new ReadingResolution(Math.round(width * scale), Math.round(height * scale), scale);
  }

  public boolean needsSurface() {
    return scale > 1.01f;
  }
}
