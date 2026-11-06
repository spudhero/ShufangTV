package io.github.spudhero.shufangtv.core;

/** Fit-width document coordinates, independent of display size and bitmap allocation. */
public final class DocumentViewport {
  public final int width, height;
  public final float scale, zoom, left, top, documentWidth, documentHeight;

  public DocumentViewport(
      int pageWidth, int pageHeight, int width, int height, float zoom, float x, float y) {
    if (pageWidth <= 0 || pageHeight <= 0 || width <= 0 || height <= 0)
      throw new IllegalArgumentException("Invalid document dimensions");
    this.width = Math.min(3840, width);
    this.height = Math.min(2160, height);
    this.zoom = finiteClamp(zoom, 1, 3);
    scale = (float) this.width / pageWidth * this.zoom;
    documentWidth = pageWidth * scale;
    documentHeight = pageHeight * scale;
    left = finiteClamp(x, 0, 1) * documentWidth;
    top = finiteClamp(y, 0, 1) * documentHeight;
  }

  public float left() {
    return Math.min(left, Math.max(0, documentWidth - width));
  }

  public float top() {
    return Math.min(top, Math.max(0, documentHeight - height));
  }

  public float x() {
    return left() / documentWidth;
  }

  public float y() {
    return top() / documentHeight;
  }

  public float movedX(float pixels) {
    return Math.max(0, Math.min(Math.max(0, documentWidth - width), left() + pixels))
        / documentWidth;
  }

  public float movedY(float pixels) {
    return Math.max(0, Math.min(Math.max(0, documentHeight - height), top() + pixels))
        / documentHeight;
  }

  private static float finiteClamp(float value, float min, float max) {
    return Float.isFinite(value) ? Math.max(min, Math.min(max, value)) : min;
  }
}
