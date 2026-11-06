package io.github.spudhero.shufangtv.reader;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.graphics.*;
import android.os.Handler;
import android.os.Looper;
import android.view.PixelCopy;
import android.widget.FrameLayout;
import androidx.test.platform.app.InstrumentationRegistry;
import io.github.spudhero.shufangtv.MainActivity;
import java.util.concurrent.*;
import junit.framework.TestCase;

/**
 * Proves that the producer contains distinct one-pixel details, rather than a stretched 1080p
 * image.
 */
public class DisplayDeviceTest extends TestCase {
  public void testIndependent4kBufferRetainsOnePixelDetails() throws Exception {
    Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
    Activity activity =
        instrumentation.startActivitySync(
            new Intent(instrumentation.getTargetContext(), MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
    ReadingSurface[] surface = new ReadingSurface[1];
    CountDownLatch ready = new CountDownLatch(1);
    try {
      instrumentation.runOnMainSync(
          () -> {
            DisplayProfile profile = new DisplayProfile(activity);
            if (profile.panelWidth < 3840 || profile.uiWidth >= 3840) {
              ready.countDown();
              return;
            }
            FrameLayout frame = new FrameLayout(activity);
            surface[0] = new ReadingSurface(activity);
            frame.addView(surface[0], new FrameLayout.LayoutParams(-1, -1));
            surface[0].renderer =
                (canvas, width, height) -> {
                  canvas.drawColor(Color.WHITE);
                  Paint paint = new Paint();
                  paint.setColor(Color.BLACK);
                  for (int x = 0; x < width; x += 2) canvas.drawRect(x, 0, x + 1, height, paint);
                };
            surface[0].onStateChanged =
                () -> {
                  if (surface[0].confirmed()) ready.countDown();
                };
            activity.setContentView(frame);
            surface[0].configure(profile.reading(profile.uiWidth, profile.uiHeight, true));
          });
      assertTrue("4K surface did not become ready", ready.await(12, TimeUnit.SECONDS));
      if (surface[0] == null) return; // Already-native UI and non-4K targets use ordinary Views.
      assertEquals(3840, surface[0].pixelWidth());
      assertEquals(2160, surface[0].pixelHeight());
      Bitmap copy = Bitmap.createBitmap(3840, 2160, Bitmap.Config.ARGB_8888);
      CountDownLatch copied = new CountDownLatch(1);
      int[] result = {-1};
      PixelCopy.request(
          surface[0],
          copy,
          code -> {
            result[0] = code;
            copied.countDown();
          },
          new Handler(Looper.getMainLooper()));
      assertTrue(copied.await(5, TimeUnit.SECONDS));
      assertEquals(PixelCopy.SUCCESS, result[0]);
      for (int x = 0; x < 3840; x++)
        assertEquals(
            "Distinct pixel " + x, x % 2 == 0 ? Color.BLACK : Color.WHITE, copy.getPixel(x, 1000));
      java.io.File evidence = new java.io.File(activity.getCacheDir(), "display-4k-pixelcopy.png");
      try (java.io.OutputStream out = new java.io.FileOutputStream(evidence)) {
        copy.compress(Bitmap.CompressFormat.PNG, 100, out);
      }
      copy.recycle();
      android.util.Log.i("DisplayDeviceTest", "PixelCopy verified 3840x2160 distinct 1px details");
    } finally {
      instrumentation.runOnMainSync(activity::finish);
    }
  }
}
