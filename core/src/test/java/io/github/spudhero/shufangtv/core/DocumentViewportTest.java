package io.github.spudhero.shufangtv.core;

import static org.junit.Assert.*;

import org.junit.Test;

public class DocumentViewportTest {
  @Test
  public void portraitDocumentFillsWidthAndScrollsWithoutAnEnormousBitmap() {
    DocumentViewport v = new DocumentViewport(600, 900, 1920, 1080, 1, 0, 0);
    assertEquals(1920, v.documentWidth, .01);
    assertEquals(2880, v.documentHeight, .01);
    assertEquals(.3, v.movedY(864), .0001);
    DocumentViewport bottom = new DocumentViewport(600, 900, 1920, 1080, 1, 0, 1);
    assertEquals(1800, bottom.top(), .01);
    assertEquals(1920 * 1080, bottom.width * bottom.height);
  }

  @Test
  public void zoomPanResetAndResizeRetainDocumentCoordinates() {
    DocumentViewport zoom = new DocumentViewport(600, 900, 1920, 1080, 2, .5f, .3f);
    assertEquals(3840, zoom.documentWidth, .01);
    assertEquals(1920, zoom.left(), .01);
    DocumentViewport reset = new DocumentViewport(600, 900, 1920, 1080, 1, zoom.x(), zoom.y());
    assertEquals(0, reset.left(), .01);
    assertEquals(.3, reset.y(), .0001);
    DocumentViewport smaller = new DocumentViewport(600, 900, 1280, 720, 1, reset.x(), reset.y());
    assertEquals(reset.y(), smaller.y(), .0001);
  }

  @Test
  public void invalidAndExtremeValuesCannotAllocateAnUnboundedViewport() {
    DocumentViewport v =
        new DocumentViewport(1, 1, 100000, 100000, Float.NaN, Float.POSITIVE_INFINITY, -10);
    assertEquals(3840, v.width);
    assertEquals(2160, v.height);
    assertEquals(1, v.zoom, 0);
    assertEquals(0, v.top(), 0);
    assertThrows(IllegalArgumentException.class, () -> new DocumentViewport(0, 1, 1, 1, 1, 0, 0));
  }
}
