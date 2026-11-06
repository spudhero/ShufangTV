package io.github.spudhero.shufangtv.core;

import static org.junit.Assert.*;

import org.junit.Test;

public class ReadingResolutionTest {
  @Test
  public void legacyTvGainsPhysicalPixelsWithoutChangingItsLogicalViewport() {
    ReadingResolution full = ReadingResolution.choose(1920, 1080, 1920, 1080, 3840, 2160, true);
    assertEquals(3840, full.width);
    assertEquals(2160, full.height);
    assertTrue(full.needsSurface());
    ReadingResolution body = ReadingResolution.choose(1728, 680, 1920, 1080, 3840, 2160, true);
    assertEquals(3456, body.width);
    assertEquals(1360, body.height);
    assertEquals(2, body.scale, 0);
    assertEquals(
        .3f, new DocumentViewport(600, 900, full.width, full.height, 1, 0, .3f).y(), .0001f);
  }

  @Test
  public void native4kAndCompatibilityDoNotCreateAnExtraUpscalingSurface() {
    for (ReadingResolution r :
        new ReadingResolution[] {
          ReadingResolution.choose(3456, 1360, 3840, 2160, 3840, 2160, true),
          ReadingResolution.choose(1728, 680, 1920, 1080, 3840, 2160, false),
          ReadingResolution.choose(1728, 680, 1920, 1080, 1920, 1080, true)
        }) {
      assertFalse(r.needsSurface());
      assertEquals(1, r.scale, 0);
    }
  }

  @Test
  public void oversizedPanelsAspectMismatchAndUnmeasuredViewsStayBounded() {
    ReadingResolution eightK = ReadingResolution.choose(1920, 1080, 1920, 1080, 7680, 4320, true);
    assertEquals(3840, eightK.width);
    assertEquals(2160, eightK.height);
    ReadingResolution partial = ReadingResolution.choose(1280, 1000, 1280, 720, 3840, 2160, true);
    assertEquals(2160, partial.height);
    assertEquals((float) 1280 / 1000, (float) partial.width / partial.height, .001f);
    assertFalse(ReadingResolution.choose(0, 0, 0, 0, 3840, 2160, true).needsSurface());
  }
}
