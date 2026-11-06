package io.github.spudhero.shufangtv.reader;

import android.content.Context;
import android.graphics.Point;
import android.os.Build;
import android.view.Display;
import android.view.WindowManager;
import io.github.spudhero.shufangtv.core.ReadingResolution;

/** Public display APIs plus Sony's declared QFHD panel feature on older BRAVIA firmware. */
public final class DisplayProfile {
  public final int uiWidth, uiHeight, panelWidth, panelHeight;
  public final boolean sonyPanel;

  @SuppressWarnings("deprecation")
  public DisplayProfile(Context context) {
    Display display = context.getSystemService(WindowManager.class).getDefaultDisplay();
    Point size = new Point();
    display.getRealSize(size);
    uiWidth = Math.max(1, size.x);
    uiHeight = Math.max(1, size.y);
    Display.Mode mode = display.getMode();
    sonyPanel =
        "Sony".equalsIgnoreCase(Build.MANUFACTURER)
            && context.getPackageManager().hasSystemFeature("com.sony.dtv.hardware.panel.qfhd");
    panelWidth = sonyPanel ? 3840 : mode.getPhysicalWidth();
    panelHeight = sonyPanel ? 2160 : mode.getPhysicalHeight();
  }

  public ReadingResolution reading(int width, int height, boolean automatic) {
    return ReadingResolution.choose(
        width, height, uiWidth, uiHeight, panelWidth, panelHeight, automatic);
  }

  public String systemSize() {
    return uiWidth + " × " + uiHeight;
  }

  public String panelSize() {
    return panelWidth + " × " + panelHeight;
  }
}
