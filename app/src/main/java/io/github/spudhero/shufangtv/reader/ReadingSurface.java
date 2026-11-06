package io.github.spudhero.shufangtv.reader;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.PixelFormat;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import io.github.spudhero.shufangtv.core.ReadingResolution;

/**
 * Optional independent content surface. All rendering and bitmap ownership stay on the UI thread.
 */
public final class ReadingSurface extends SurfaceView implements SurfaceHolder.Callback {
  public interface Renderer {
    void draw(Canvas canvas, int width, int height);
  }

  public Renderer renderer;
  public Runnable onStateChanged;
  private ReadingResolution target;
  private boolean created, queued, failed, confirmed;
  private int actualWidth, actualHeight;
  private final Runnable drawFrame =
      () -> {
        queued = false;
        render();
      };
  private final Runnable timeout =
      () -> {
        if (!confirmed && target != null) fallback();
      };

  public ReadingSurface(Context context) {
    super(context);
    setFocusable(false);
    setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    getHolder().setFormat(PixelFormat.OPAQUE);
    getHolder().addCallback(this);
    setVisibility(GONE);
  }

  public void configure(ReadingResolution resolution) {
    if (target != null && target.width == resolution.width && target.height == resolution.height)
      return;
    removeCallbacks(timeout);
    target = resolution.needsSurface() && !failed ? resolution : null;
    boolean wasConfirmed = confirmed;
    confirmed = false;
    actualWidth = actualHeight = 0;
    if (target == null) setVisibility(GONE);
    else {
      try {
        setVisibility(VISIBLE);
        getHolder().setFixedSize(target.width, target.height);
        postDelayed(timeout, 3000);
        requestRender();
      } catch (RuntimeException | OutOfMemoryError error) {
        fallback();
        return;
      }
    }
    if (wasConfirmed) changed();
  }

  public boolean confirmed() {
    return confirmed;
  }

  public boolean failed() {
    return failed;
  }

  public int pixelWidth() {
    return actualWidth;
  }

  public int pixelHeight() {
    return actualHeight;
  }

  public void retry() {
    failed = false;
    target = null;
  }

  public void requestRender() {
    if (!queued && created && target != null && !failed) {
      queued = true;
      postOnAnimation(drawFrame);
    }
  }

  private void render() {
    if (!created || target == null || renderer == null || failed || !isAttachedToWindow()) return;
    if (getHolder().getSurfaceFrame().width() != target.width
        || getHolder().getSurfaceFrame().height() != target.height) return;
    Canvas canvas = null;
    boolean success = false;
    try {
      canvas = getHolder().getSurface().lockHardwareCanvas();
      if (canvas.getWidth() != target.width || canvas.getHeight() != target.height)
        throw new IllegalStateException("Unexpected reading buffer size");
      renderer.draw(canvas, canvas.getWidth(), canvas.getHeight());
      success = true;
    } catch (RuntimeException | OutOfMemoryError error) {
      android.util.Log.w("ReadingSurface", "Using system reading canvas", error);
    } finally {
      if (canvas != null) {
        try {
          getHolder().getSurface().unlockCanvasAndPost(canvas);
        } catch (RuntimeException error) {
          success = false;
        }
      }
    }
    if (!success) {
      fallback();
      return;
    }
    boolean first = !confirmed;
    confirmed = true;
    actualWidth = target.width;
    actualHeight = target.height;
    removeCallbacks(timeout);
    if (first) changed();
  }

  public void fallback() {
    failed = true;
    confirmed = false;
    actualWidth = actualHeight = 0;
    target = null;
    removeCallbacks(drawFrame);
    removeCallbacks(timeout);
    queued = false;
    setVisibility(GONE);
    changed();
  }

  private void changed() {
    if (onStateChanged != null) onStateChanged.run();
  }

  @Override
  public void surfaceCreated(SurfaceHolder holder) {
    created = true;
    requestRender();
  }

  @Override
  public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
    requestRender();
  }

  @Override
  public void surfaceDestroyed(SurfaceHolder holder) {
    created = confirmed = false;
    actualWidth = actualHeight = 0;
    removeCallbacks(drawFrame);
    removeCallbacks(timeout);
    queued = false;
    changed();
  }
}
