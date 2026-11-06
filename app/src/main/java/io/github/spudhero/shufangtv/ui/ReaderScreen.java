package io.github.spudhero.shufangtv.ui;

import static io.github.spudhero.shufangtv.ui.TvStyle.*;

import android.app.*;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Handler;
import android.os.Looper;
import android.view.*;
import android.widget.*;
import io.github.spudhero.shufangtv.core.AutoPagePolicy;
import io.github.spudhero.shufangtv.core.BookDocument;
import io.github.spudhero.shufangtv.core.DocumentViewport;
import io.github.spudhero.shufangtv.core.TextNavigator;
import io.github.spudhero.shufangtv.data.*;
import io.github.spudhero.shufangtv.reader.*;
import io.github.spudhero.shufangtv.speech.SpeechSettings;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** Native reader with logical text positions shared by online and offline copies. */
public final class ReaderScreen extends LinearLayout {
  private final Activity activity;
  private final ExecutorService io;
  private final LibraryStore library;
  private final SharedPreferences prefs;
  private final BookSession session;
  private final BookRecord book;
  private final Runnable exit;
  private final PagedTextView page;
  private final ImageView image;
  private final ReadingSurface highResolution;
  private DisplayProfile displayProfile;
  private int paperColor = 0xff1c201d;
  private float readingBrightness = 1;
  private boolean pdfReloadPending;
  private int illustrationGeneration;
  private final TextView chapter, progress, status, bookTitle, modeLabel;
  private final FrameLayout body;
  private final LinearLayout bottom;
  private final View previousButton;
  private boolean active = true, busy, textReady;
  private int generation;
  private Bitmap bitmap;
  private DocumentViewport pdfViewport;
  private int loadedChapter = -1;
  private final LinearLayout searchNavigation;
  private TextNavigator.Matches searchMatches;
  private int searchIndex, searchOrigin;
  private String searchText = "", searchQuery = "";
  private final Handler main = new Handler(Looper.getMainLooper());
  private final Set<AlertDialog> readerDialogs = new HashSet<>();
  private final LinearLayout speechNavigation;
  private ReaderSpeech speech;
  private boolean foreground = true,
      speechReady,
      speechRequested,
      resumeSpeechAfterLoad,
      stopAtChapter;
  private TextView speechToggle, sleepLabel;
  private long speechDeadline;
  private final LinearLayout selectionNavigation;
  private TextNavigator.Range selection;
  private LibraryStore.Mark pendingMark;
  private final LinearLayout autoNavigation;
  private boolean autoEnabled, autoPaused;
  private int autoSeconds;
  private TextView autoToggle, autoStatus;
  private final Runnable autoTick = this::advanceAutomatically;
  private final Runnable sleepStop = this::stopTimedSpeech;

  private void stopTimedSpeech() {
    speechDeadline = 0;
    speechRequested = resumeSpeechAfterLoad = false;
    if (speech != null) speech.pause();
    if (sleepLabel != null) sleepLabel.setText("定时停止");
    Toast.makeText(activity, "已按定时停止朗读", Toast.LENGTH_SHORT).show();
  }

  public ReaderScreen(
      Activity activity,
      ExecutorService io,
      LibraryStore library,
      SharedPreferences prefs,
      BookSession session,
      Runnable exit) {
    super(activity);
    this.activity = activity;
    this.io = io;
    this.library = library;
    this.prefs = prefs;
    this.session = session;
    displayProfile = new DisplayProfile(activity);
    book = session.record;
    this.exit = exit;
    setOrientation(VERTICAL);
    setPadding(dp(activity, 48), dp(activity, 28), dp(activity, 48), dp(activity, 22));
    LinearLayout header = horizontal(activity);
    bookTitle = heading(activity, book.title, 18);
    bookTitle.setSingleLine(true);
    bookTitle.setEllipsize(android.text.TextUtils.TruncateAt.END);
    bookTitle.setContentDescription(book.title);
    add(header, bookTitle, 0, 28);
    ((LayoutParams) header.getChildAt(0).getLayoutParams()).weight = 1;
    modeLabel = text(activity, session.online ? "在线阅读" : "离线阅读", 12, ACCENT);
    add(header, modeLabel, -2, 28);
    add(this, header, -1, 34);
    line(this);
    chapter = text(activity, "", 14, MUTED);
    chapter.setSingleLine(true);
    chapter.setEllipsize(android.text.TextUtils.TruncateAt.END);
    gap(this, 16);
    add(this, chapter, -1, 24);
    body = new FrameLayout(activity);
    addView(body, new LayoutParams(-1, 0, 1));
    page = new PagedTextView(activity);
    image =
        new ImageView(activity) {
          @Override
          protected void onDraw(Canvas canvas) {
            if (!highResolution.confirmed()) super.onDraw(canvas);
          }
        };
    image.setScaleType(ImageView.ScaleType.FIT_CENTER);
    image.setFocusable(true);
    image.setFocusableInTouchMode(true);
    highResolution = new ReadingSurface(activity);
    highResolution.renderer = this::drawHighResolution;
    highResolution.onStateChanged = this::resolutionChanged;
    body.addView(highResolution, new FrameLayout.LayoutParams(-1, -1));
    body.addView(page, new FrameLayout.LayoutParams(-1, -1));
    body.addView(image, new FrameLayout.LayoutParams(-1, -1));
    status = text(activity, "", 14, MUTED);
    status.setGravity(Gravity.CENTER);
    body.addView(status, new FrameLayout.LayoutParams(-1, -1));
    status.setVisibility(GONE);
    page.onPageReady =
        () -> {
          updateProgress();
          highResolution.requestRender();
        };
    page.onIllustrationNeeded = this::loadIllustration;
    body.addOnLayoutChangeListener(
        (v, l, t, r, b, ol, ot, or, ob) -> {
          if (active && r > l && b > t) refreshResolution();
        });
    searchNavigation = horizontal(activity);
    searchNavigation.setVisibility(GONE);
    mountControls(searchNavigation);
    speechNavigation = horizontal(activity);
    speechNavigation.setVisibility(GONE);
    mountControls(speechNavigation);
    selectionNavigation = horizontal(activity);
    selectionNavigation.setVisibility(GONE);
    mountControls(selectionNavigation);
    autoNavigation = horizontal(activity);
    autoNavigation.setVisibility(GONE);
    mountControls(autoNavigation);
    autoSeconds = Math.max(5, Math.min(120, prefs.getInt("autoSeconds", 30)));
    bottom = horizontal(activity);
    previousButton = button(activity, "〈 上一页", false, () -> turn(-1));
    add(bottom, previousButton, 100, 44);
    progress = text(activity, "", 12, MUTED);
    progress.setGravity(Gravity.CENTER);
    bottom.addView(progress, new LayoutParams(0, dp(activity, 44), 1));
    add(bottom, button(activity, "阅读菜单", false, this::menu), 115, 44);
    gap(bottom, 12);
    add(bottom, button(activity, "下一页 〉", false, () -> turn(1)), 100, 44);
    add(this, bottom, -1, 48);
    applyAppearance();
    if (book.format.equals("pdf")) {
      setPadding(0, 0, 0, 0);
      for (int i = 0; i < getChildCount(); i++)
        if (getChildAt(i) != body) getChildAt(i).setVisibility(GONE);
      page.setVisibility(GONE);
      image.setVisibility(VISIBLE);
      image.setScaleType(ImageView.ScaleType.FIT_XY);
      book.pdfPage = Math.min(book.pdfPage, session.pdfPages() - 1);
      image.post(this::loadPdf);
    } else {
      image.setVisibility(GONE);
      book.chapter = Math.min(book.chapter, session.chapterCount() - 1);
      loadChapter(false, false);
    }
  }

  private void applyAppearance() {
    ReadingStyle style = ReadingStyle.effective(book, prefs);
    int theme = style.paper;
    paperColor = theme == 1 ? 0xffe2dac4 : theme == 2 ? 0xff0d110f : 0xff1c201d;
    setBackgroundColor(paperColor);
    page.setAppearance(
        style.font, style.line, style.serif, theme == 1 ? 0xff33382f : 0xffe9e7dc, style.width);
    page.setImageFit(book.imageFit);
    readingBrightness = Math.max(.1f, Math.min(1, prefs.getInt("brightness", 100) / 100f));
    applyContentAlpha();
    highResolution.requestRender();
    chapter.setTextColor(theme == 1 ? 0xff50594c : MUTED);
    progress.setTextColor(theme == 1 ? 0xff50594c : MUTED);
    bookTitle.setTextColor(theme == 1 ? 0xff33382f : TEXT);
    modeLabel.setTextColor(theme == 1 ? 0xff50594c : ACCENT);
    status.setTextColor(theme == 1 ? 0xff50594c : MUTED);
  }

  public void refreshResolution() {
    displayProfile = new DisplayProfile(activity);
    highResolution.configure(
        displayProfile.reading(
            body.getWidth(), body.getHeight(), DisplaySettings.automatic(prefs)));
    highResolution.requestRender();
  }

  public void applyResolutionSetting() {
    highResolution.retry();
    refreshResolution();
    if (page.showingImage()) page.retryIllustration();
  }

  private void applyContentAlpha() {
    page.setExternalRendering(highResolution.confirmed());
    page.setAlpha(readingBrightness);
    image.setAlpha(readingBrightness);
    image.invalidate();
  }

  private void resolutionChanged() {
    if (!active) return;
    applyContentAlpha();
    if (foreground && book.format.equals("pdf") && body.getWidth() > 0) loadPdf();
  }

  private void drawHighResolution(Canvas canvas, int width, int height) {
    canvas.drawColor(paperColor);
    if (book.format.equals("pdf")) {
      if (bitmap != null && !bitmap.isRecycled())
        canvas.drawBitmap(
            bitmap, null, new RectF(0, 0, width, height), new Paint(Paint.FILTER_BITMAP_FLAG));
    } else if (page.getWidth() > 0 && page.getHeight() > 0) {
      canvas.save();
      canvas.scale((float) width / page.getWidth(), (float) height / page.getHeight());
      page.renderContent(canvas);
      canvas.restore();
    }
    // Equivalent to the normal View alpha over the same reading-paper background.
    int shade = Math.round((1 - readingBrightness) * 255);
    if (shade > 0) canvas.drawColor((shade << 24) | (paperColor & 0xffffff));
  }

  public String resolutionStatus() {
    if (highResolution.failed()) return "系统清晰度 · 已回退";
    if (highResolution.confirmed())
      return displayProfile.panelWidth >= 3840 && displayProfile.panelHeight >= 2160
          ? "4K 内容绘制"
          : "高清内容绘制";
    if (displayProfile.uiWidth >= 3840 && displayProfile.uiHeight >= 2160) return "系统原生 4K";
    return "系统清晰度";
  }

  public String readingCanvasSize() {
    return (highResolution.confirmed() ? highResolution.pixelWidth() : body.getWidth())
        + " × "
        + (highResolution.confirmed() ? highResolution.pixelHeight() : body.getHeight());
  }

  private void mountControls(LinearLayout controls) {
    HorizontalScrollView rail = new HorizontalScrollView(activity);
    rail.setFillViewport(true);
    rail.setHorizontalScrollBarEnabled(false);
    rail.setBackground(panel(activity, SIDEBAR, 0xff3b423c));
    rail.setPadding(dp(activity, 12), dp(activity, 8), dp(activity, 12), dp(activity, 8));
    rail.addView(controls, new FrameLayout.LayoutParams(-2, -1));
    rail.setVisibility(GONE);
    add(this, rail, -1, 62);
  }

  private void updateControls() {
    if (bottom == null) return;
    boolean mode = false;
    for (LinearLayout controls :
        new LinearLayout[] {
          searchNavigation, speechNavigation, selectionNavigation, autoNavigation
        }) {
      boolean visible = controls.getVisibility() == VISIBLE;
      ((View) controls.getParent()).setVisibility(visible ? VISIBLE : GONE);
      if (!visible) continue;
      mode = true;
      if (controls.findViewWithTag("mode-menu") == null) {
        TextView tools = button(activity, "阅读菜单", false, this::menu);
        tools.setTag("mode-menu");
        add(controls, tools, 105, 44);
      }
      for (int i = 0; i < controls.getChildCount(); i++) {
        View child = controls.getChildAt(i);
        if (!(child instanceof TextView)) continue;
        LayoutParams params = (LayoutParams) child.getLayoutParams();
        params.height = dp(activity, 44);
        if (child.isFocusable()) {
          params.rightMargin = dp(activity, 6);
          child.setContentDescription(
              null); // TextView exposes its live label, including pause/resume.
        }
        child.setLayoutParams(params);
      }
    }
    bottom.setVisibility(mode ? GONE : VISIBLE);
  }

  private <T> void task(Callable<T> work, Consumer<T> done, Runnable retry) {
    busy = true;
    scheduleAuto();
    int id = ++generation;
    status.setText("正在读取…");
    status.setVisibility(VISIBLE);
    io.submit(
        () -> {
          try {
            T value = work.call();
            activity.runOnUiThread(
                () -> {
                  if (!active || id != generation) {
                    if (value instanceof Bitmap) ((Bitmap) value).recycle();
                    if (value instanceof BookSession.PdfFrame)
                      ((BookSession.PdfFrame) value).bitmap.recycle();
                    return;
                  }
                  busy = false;
                  status.setVisibility(GONE);
                  done.accept(value);
                  scheduleAuto();
                });
          } catch (Exception e) {
            activity.runOnUiThread(
                () -> {
                  if (!active || id != generation) return;
                  busy = false;
                  autoPaused = true;
                  updateAutoLabel();
                  status.setVisibility(GONE);
                  new ReaderDialog(activity)
                      .setTitle("读取未完成")
                      .setMessage(safeError(e))
                      .setPositiveButton("重试", (d, w) -> retry.run())
                      .setNegativeButton("返回书库", (d, w) -> exit.run())
                      .setOnCancelListener(d -> exit.run())
                      .show();
                });
          }
        });
  }

  private String safeError(Exception e) {
    return e.getMessage() == null ? "请检查书库连接后重试。在线模式不会自动保存离线副本。" : e.getMessage();
  }

  private void loadChapter(boolean end, boolean precedingSegment) {
    if (loadedChapter >= 0 && loadedChapter != book.chapter) book.imageY = 0;
    loadedChapter = book.chapter;
    textReady = false;
    final int savedOffset = book.offset;
    task(
        () ->
            precedingSegment
                ? session.previousSegment()
                : session.chapter(book.chapter, book.txtByte),
        c -> {
          clearSearch(false);
          chapter.setText(c.title);
          if (session.segment != null) book.txtByte = session.segment.start;
          textReady = true;
          page.setChapter(c, end ? Math.max(0, c.text.length() - 1) : savedOffset, book.imageY);
          if (end) {
            page.setContent(c.text, c.text.length());
            page.previous();
          }
          updateProgress();
          surface().requestFocus();
          save();
          applyPendingMark();
          if (resumeSpeechAfterLoad && speech != null) {
            resumeSpeechAfterLoad = false;
            speech.load(c.text, page.offset());
            if (speechRequested && foreground) speech.play();
          }
        },
        () -> loadChapter(end, precedingSegment));
  }

  private void loadPdf() {
    if (!active) return;
    if (busy) {
      pdfReloadPending = true;
      return;
    }
    pdfReloadPending = false;
    int uiWidth =
        body.getWidth() > 0 ? body.getWidth() : getResources().getDisplayMetrics().widthPixels;
    int uiHeight =
        body.getHeight() > 0 ? body.getHeight() : getResources().getDisplayMetrics().heightPixels;
    int width = highResolution.confirmed() ? highResolution.pixelWidth() : uiWidth;
    int height = highResolution.confirmed() ? highResolution.pixelHeight() : uiHeight;
    int pdfPage = book.pdfPage;
    float zoom = book.pdfZoom, x = book.pdfX, y = book.pdfY;
    task(
        () -> {
          try {
            return session.renderPdf(pdfPage, width, height, zoom, x, y);
          } catch (OutOfMemoryError error) {
            if (width <= uiWidth && height <= uiHeight) throw new IOException("内存不足，请稍后重试", error);
            try {
              return session.renderPdf(pdfPage, uiWidth, uiHeight, zoom, x, y);
            } catch (OutOfMemoryError lowMemory) {
              throw new IOException("内存不足，请稍后重试", lowMemory);
            }
          }
        },
        frame -> {
          Bitmap old = bitmap;
          bitmap = frame.bitmap;
          pdfViewport = frame.viewport;
          book.pdfX = pdfViewport.x();
          book.pdfY = pdfViewport.y();
          image.setImageBitmap(bitmap);
          if (old != null && old != bitmap) old.recycle();
          highResolution.requestRender();
          if (frame.bitmap.getWidth() != width || frame.bitmap.getHeight() != height)
            highResolution.fallback();
          chapter.setText("PDF · 第 " + (book.pdfPage + 1) + " 页");
          updateProgress();
          surface().requestFocus();
          save();
          if (pdfReloadPending) image.post(this::loadPdf);
        },
        this::loadPdf);
  }

  private void loadIllustration(BookDocument.Illustration ref) {
    int request = ++illustrationGeneration;
    int width =
        displayProfile.reading(
                Math.max(1, body.getWidth()),
                Math.max(1, body.getHeight()),
                DisplaySettings.automatic(prefs) && !highResolution.failed())
            .width;
    int decodeWidth = Math.max(1920, width);
    int decodeHeight = Math.max(2160, Math.min(8192, decodeWidth * 2));
    io.submit(
        () -> {
          Bitmap value = null;
          String error = null;
          boolean memoryFallback = false;
          try {
            try {
              value = session.illustration(ref, decodeWidth, decodeHeight);
            } catch (OutOfMemoryError tooLarge) {
              if (decodeWidth <= 1920) throw tooLarge;
              memoryFallback = true;
              value = session.illustration(ref, 1920, 2160);
            }
          } catch (Exception e) {
            error = safeError(e);
          } catch (OutOfMemoryError e) {
            error = "图片内存不足，请稍后重试";
          }
          final Bitmap result = value;
          final String failure = error;
          final boolean fellBack = memoryFallback;
          activity.runOnUiThread(
              () -> {
                if (!active || request != illustrationGeneration) {
                  if (result != null) result.recycle();
                  return;
                }
                if (fellBack) highResolution.fallback();
                page.illustrationReady(ref, result, failure);
              });
        });
  }

  private void scrollPdf(int direction) {
    if (busy || pdfViewport == null) return;
    pauseAutoForMovement();
    float y = pdfViewport.movedY(direction * pdfViewport.height * .8f);
    if (Math.abs(y - book.pdfY) > .00001f) {
      book.pdfY = y;
      loadPdf();
    } else {
      int next = book.pdfPage + direction;
      if (next >= 0 && next < session.pdfPages()) {
        book.pdfPage = next;
        book.pdfY = direction > 0 ? 0 : 1;
        loadPdf();
      }
    }
  }

  private void panPdf(int direction) {
    if (busy || pdfViewport == null) return;
    pauseAutoForMovement();
    book.pdfX = pdfViewport.movedX(direction * pdfViewport.width * .6f);
    loadPdf();
  }

  private void pauseAutoForMovement() {
    if (autoEnabled) {
      autoPaused = true;
      scheduleAuto();
      updateAutoLabel();
    }
  }

  public View surface() {
    return book.format.equals("pdf") ? image : page;
  }

  public boolean handle(KeyEvent event) {
    if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
    int key = event.getKeyCode();
    if (autoEnabled && key == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) {
      if (event.getRepeatCount() == 0) toggleAuto();
      return true;
    }
    if (speech != null && event.getRepeatCount() == 0) {
      if (key == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
          || key == KeyEvent.KEYCODE_MEDIA_PLAY
          || key == KeyEvent.KEYCODE_MEDIA_PAUSE) {
        if ((key == KeyEvent.KEYCODE_MEDIA_PLAY && !speech.playing())
            || (key == KeyEvent.KEYCODE_MEDIA_PAUSE && speech.playing())
            || key == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) toggleSpeech();
        return true;
      }
      if (key == KeyEvent.KEYCODE_MEDIA_STOP) {
        stopSpeech();
        return true;
      }
      if (key == KeyEvent.KEYCODE_MEDIA_NEXT || key == KeyEvent.KEYCODE_MEDIA_PREVIOUS) {
        speech.move(key == KeyEvent.KEYCODE_MEDIA_NEXT ? 1 : -1);
        return true;
      }
    }
    if (key == KeyEvent.KEYCODE_ESCAPE) {
      exit.run();
      return true;
    }
    if (busy)
      return key == KeyEvent.KEYCODE_DPAD_CENTER
          || key == KeyEvent.KEYCODE_ENTER
          || key == KeyEvent.KEYCODE_DPAD_LEFT
          || key == KeyEvent.KEYCODE_DPAD_RIGHT
          || key == KeyEvent.KEYCODE_DPAD_UP
          || key == KeyEvent.KEYCODE_DPAD_DOWN;
    if (surface().hasFocus()) {
      if (book.format.equals("pdf")) {
        if (key == KeyEvent.KEYCODE_DPAD_DOWN || key == KeyEvent.KEYCODE_DPAD_UP) {
          scrollPdf(key == KeyEvent.KEYCODE_DPAD_DOWN ? 1 : -1);
          return true;
        }
        if (book.pdfZoom > 1
            && (key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_DPAD_RIGHT)) {
          panPdf(key == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1);
          return true;
        }
      } else if (page.showingImage()
          && (key == KeyEvent.KEYCODE_DPAD_DOWN || key == KeyEvent.KEYCODE_DPAD_UP)) {
        turn(key == KeyEvent.KEYCODE_DPAD_DOWN ? 1 : -1);
        return true;
      }
      if (selection != null) {
        if (key == KeyEvent.KEYCODE_DPAD_UP || key == KeyEvent.KEYCODE_DPAD_DOWN) {
          selectParagraph(key == KeyEvent.KEYCODE_DPAD_UP ? -1 : 1);
          return true;
        }
        if (key == KeyEvent.KEYCODE_DPAD_CENTER || key == KeyEvent.KEYCODE_ENTER) {
          if (event.getRepeatCount() == 0) saveSelection();
          return true;
        }
        if (key == KeyEvent.KEYCODE_DPAD_LEFT || key == KeyEvent.KEYCODE_DPAD_RIGHT) {
          selectionNavigation.getChildAt(0).requestFocus();
          return true;
        }
      }
      if (key == KeyEvent.KEYCODE_DPAD_RIGHT) {
        turn(1);
        return true;
      }
      if (key == KeyEvent.KEYCODE_DPAD_LEFT) {
        turn(-1);
        return true;
      }
      if (key == KeyEvent.KEYCODE_DPAD_CENTER || key == KeyEvent.KEYCODE_ENTER) {
        if (event.getRepeatCount() == 0) {
          if (autoEnabled) toggleAuto();
          else if (!book.format.equals("pdf") && page.imageFailed()) page.retryIllustration();
          else menu();
        }
        return true;
      }
      if (key == KeyEvent.KEYCODE_DPAD_DOWN) {
        if (autoNavigation.getVisibility() == VISIBLE) autoNavigation.getChildAt(0).requestFocus();
        else if (speechNavigation.getVisibility() == VISIBLE)
          speechNavigation.getChildAt(0).requestFocus();
        else if (searchNavigation.getVisibility() == VISIBLE)
          searchNavigation.getChildAt(0).requestFocus();
        else previousButton.requestFocus();
        return true;
      }
    } else if ((bottom.hasFocus()
            || searchNavigation.hasFocus()
            || speechNavigation.hasFocus()
            || selectionNavigation.hasFocus()
            || autoNavigation.hasFocus())
        && key == KeyEvent.KEYCODE_DPAD_UP) {
      surface().requestFocus();
      return true;
    }
    return false;
  }

  private void turn(int delta) {
    turn(delta, false);
  }

  private void turn(int delta, boolean automatic) {
    if (busy || (!book.format.equals("pdf") && !textReady)) return;
    if (!automatic && autoEnabled) {
      autoPaused = true;
      scheduleAuto();
      updateAutoLabel();
    }
    stopSpeech();
    stopSelection();
    if (book.format.equals("pdf")) {
      if (automatic && pdfViewport != null) {
        float y = pdfViewport.movedY(pdfViewport.height * .8f);
        if (y > book.pdfY + .00001f) {
          book.pdfY = y;
          loadPdf();
          return;
        }
      }
      int next = Math.max(0, Math.min(session.pdfPages() - 1, book.pdfPage + delta));
      if (next != book.pdfPage) {
        book.pdfPage = next;
        book.pdfX = book.pdfY = 0;
        loadPdf();
      } else {
        if (automatic) stopAuto();
        Toast.makeText(activity, delta > 0 ? "已经到末页" : "已经到开头", Toast.LENGTH_SHORT).show();
      }
      return;
    }
    if (delta > 0 ? page.next() : page.previous()) {
      book.offset = page.offset();
      updateProgress();
      save();
      return;
    }
    if (session.epub != null) {
      int next = book.chapter + delta;
      if (next >= 0 && next < session.chapterCount()) {
        book.chapter = next;
        book.offset = 0;
        loadChapter(delta < 0, false);
        return;
      }
    } else if (session.segment != null) {
      if (delta > 0 && session.segment.end < session.textLength()) {
        book.txtByte = session.segment.end;
        book.offset = 0;
        loadChapter(false, false);
        return;
      }
      if (delta < 0 && session.segment.start > 0) {
        book.offset = 0;
        loadChapter(true, true);
        return;
      }
    }
    if (automatic) stopAuto();
    Toast.makeText(activity, delta > 0 ? "已经到末尾" : "已经到开头", Toast.LENGTH_SHORT).show();
  }

  private void updateProgress() {
    if (book.format.equals("pdf")) {
      String position =
          (book.pdfPage + 1)
              + " / "
              + session.pdfPages()
              + " · "
              + Math.round(book.pdfZoom * 100)
              + "%";
      progress.setText(position);
      image.setContentDescription(
          "PDF 正文 " + position + "，上下滚动，左右" + (book.pdfZoom > 1 ? "平移" : "翻页") + "，确定打开阅读菜单");
      return;
    }
    if (!textReady) return;
    book.offset = page.offset();
    book.imageY = page.imageY();
    if (session.epub != null)
      progress.setText(
          "第 "
              + (book.chapter + 1)
              + " / "
              + session.chapterCount()
              + " 章  ·  "
              + Math.round(100.0 * page.nextOffset() / Math.max(1, page.totalChars()))
              + "%");
    else if (session.segment != null)
      progress.setText(
          Math.min(
                  100,
                  Math.round(
                      100.0
                          * (session.segment.start
                              + (session.segment.end - session.segment.start)
                                  * (double) page.nextOffset()
                                  / Math.max(1, page.totalChars()))
                          / Math.max(1, session.textLength())))
              + "%  ·  左右翻页");
  }

  private void menu() {
    if (busy) return;
    LinearLayout content = vertical(activity);
    AlertDialog dialog =
        new ReaderDialog(activity)
            .setTitle("阅读菜单")
            .setView(ReadingPanel.scroll(activity, content))
            .setNegativeButton("继续阅读", null)
            .setNeutralButton("返回书库", (d, i) -> exit.run())
            .create();
    ReadingPanel.section(content, "阅读");
    ReadingPanel.row(
        content,
        tool(dialog, "目录 / 跳转", book.format.equals("pdf") ? "前往指定页" : "选择章节", this::contents),
        tool(
            dialog,
            "自动翻页",
            autoEnabled ? (autoPaused ? "已暂停" : "每页 " + autoSeconds + " 秒") : "按自己的节奏",
            this::autoMenu));
    if (!book.format.equals("pdf"))
      ReadingPanel.row(
          content,
          tool(dialog, "搜索当前正文", session.epub != null ? "只搜索本章" : "只搜索当前片段", this::searchDialog),
          tool(
              dialog,
              "朗读正文",
              speech != null ? "返回朗读控制" : SpeechSettings.status(activity),
              this::startSpeech));
    ReadingPanel.section(content, "记录");
    View bookmark = tool(dialog, "添加书签", "记住当前阅读位置", this::addBookmark);
    if (!book.format.equals("pdf"))
      ReadingPanel.row(content, bookmark, tool(dialog, "收藏正文段落", "摘录与自己的想法", this::startSelection));
    else ReadingPanel.row(content, bookmark);
    ReadingPanel.section(content, "显示");
    ReadingPanel.row(
        content,
        tool(
            dialog,
            "显示与清晰度",
            resolutionStatus(),
            () ->
                DisplayPanel.show(
                    activity,
                    prefs,
                    new ReaderDialog(activity),
                    this::resolutionStatus,
                    this::readingCanvasSize,
                    this::applyResolutionSetting,
                    null)));
    ReadingStyle style = ReadingStyle.effective(book, prefs);
    View paper =
        tool(
            dialog,
            book.format.equals("pdf") ? "阅读亮度" : "亮度与纸色",
            book.format.equals("pdf")
                ? prefs.getInt("brightness", 100) + "% · 全局设置"
                : new String[] {"暗色书房", "暖纸色", "墨黑"}[style.paper],
            this::paperMenu);
    if (book.format.equals("pdf")) {
      ReadingPanel.row(
          content,
          tool(dialog, "缩放与翻页", Math.round(book.pdfZoom * 100) + "% · 上下滚动查看", this::pdfTools),
          paper);
    } else
      ReadingPanel.row(
          content,
          tool(
              dialog,
              "字体与排版",
              style.font + " 号 · " + (book.readingStyle == null ? "全局默认" : "仅本书"),
              this::fontMenu),
          paper);
    if (!book.format.equals("pdf") && page.showingImage())
      ReadingPanel.row(
          content,
          tool(
              dialog,
              "图片显示",
              new String[] {"自动适配", "适合整图", "100% · 适合屏宽"}[book.imageFit],
              this::imageTools));
    dialog.show();
    content.getChildAt(2).requestFocus();
  }

  private void pdfTools() {
    String[] labels = {"100% · 适合屏宽", "125%", "150%", "200%", "300%", "上一页", "下一页"};
    float[] zooms = {1, 1.25f, 1.5f, 2, 3};
    new ReaderDialog(activity)
        .setTitle("PDF 缩放与翻页")
        .setItems(
            labels,
            (d, which) -> {
              if (which >= 5) {
                turn(which == 5 ? -1 : 1);
                return;
              }
              book.pdfZoom = zooms[which];
              book.pdfX = 0;
              loadPdf();
            })
        .setNegativeButton("返回阅读", null)
        .show();
  }

  private void imageTools() {
    new ReaderDialog(activity)
        .setTitle("图片显示")
        .setItems(
            new String[] {"自动适配", "适合整图", "100% · 适合屏宽"},
            (d, which) -> {
              book.imageFit = which;
              page.setImageFit(which);
              save();
            })
        .setNegativeButton("返回阅读", null)
        .show();
  }

  private View tool(AlertDialog dialog, String label, String detail, Runnable action) {
    return ReadingPanel.tool(
        activity,
        label,
        detail,
        () -> {
          if (!label.equals("字体与排版") && !label.equals("亮度与纸色") && !label.equals("阅读亮度"))
            dialog.dismiss();
          action.run();
        });
  }

  private void addBookmark() {
    try {
      library.bookmark(
          book, book.format.equals("pdf") ? "第 " + (book.pdfPage + 1) + " 页" : page.excerpt());
      Toast.makeText(activity, "书签已保存", Toast.LENGTH_SHORT).show();
    } catch (IOException e) {
      Toast.makeText(activity, e.getMessage(), Toast.LENGTH_LONG).show();
    }
  }

  private void searchDialog() {
    stopAuto();
    stopSpeech();
    stopSelection();
    EditText input = new EditText(activity);
    input.setSingleLine(true);
    input.setHint("输入正文关键词");
    input.setFilters(
        new android.text.InputFilter[] {new android.text.InputFilter.LengthFilter(128)});
    input.setText(searchQuery);
    AlertDialog dialog =
        new ReaderDialog(activity)
            .setTitle(session.epub != null ? "搜索本章" : "搜索当前 TXT 片段")
            .setMessage("只搜索当前已加载正文，不读取整本书。")
            .setView(input)
            .setPositiveButton("搜索", null)
            .setNeutralButton("搜索历史", (d, w) -> searchHistory())
            .setNegativeButton("返回", null)
            .create();
    dialog.show();
    dialog
        .getButton(AlertDialog.BUTTON_POSITIVE)
        .setOnClickListener(
            v -> {
              String query = input.getText().toString().trim();
              if (query.isEmpty()) {
                input.setError("请输入关键词");
                return;
              }
              dialog.dismiss();
              searchCurrentText(query);
            });
  }

  private void searchHistory() {
    if (book.searchHistory.isEmpty()) {
      Toast.makeText(activity, "本书还没有搜索记录", Toast.LENGTH_SHORT).show();
      return;
    }
    new ReaderDialog(activity)
        .setTitle("本书搜索历史")
        .setItems(
            book.searchHistory.toArray(new String[0]),
            (d, which) -> searchCurrentText(book.searchHistory.get(which)))
        .setNeutralButton(
            "清空记录",
            (d, w) -> {
              book.searchHistory.clear();
              save();
            })
        .setNegativeButton("返回", null)
        .show();
  }

  private void searchCurrentText(String query) {
    final String text = page.content();
    final int origin = page.offset();
    task(
        () -> TextNavigator.search(text, query, 200),
        matches -> {
          clearSearch(false);
          searchText = text;
          searchQuery = query;
          searchOrigin = origin;
          searchMatches = matches;
          book.rememberSearch(query);
          save();
          showSearchResults();
        },
        () -> searchCurrentText(query));
  }

  private void showSearchResults() {
    if (searchMatches == null) return;
    if (searchMatches.ranges.isEmpty()) {
      new ReaderDialog(activity)
          .setTitle("当前正文没有找到")
          .setMessage("检索范围：" + (session.epub != null ? "当前章节" : "当前 TXT 片段") + "。未搜索其他正文。")
          .setPositiveButton("重新搜索", (d, w) -> searchDialog())
          .setNegativeButton("返回", null)
          .show();
      return;
    }
    String[] labels = new String[searchMatches.ranges.size()];
    for (int i = 0; i < labels.length; i++)
      labels[i] = (i + 1) + ". " + TextNavigator.snippet(searchText, searchMatches.ranges.get(i));
    new ReaderDialog(activity)
        .setTitle("搜索结果 · " + labels.length + (searchMatches.more ? " 条以上，仅显示前 200 条" : " 条"))
        .setItems(labels, (d, which) -> jumpSearch(which))
        .setNeutralButton("结束搜索", (d, w) -> clearSearch(false))
        .setNegativeButton("返回阅读", null)
        .setOnDismissListener(d -> surface().requestFocus())
        .show();
  }

  private void jumpSearch(int index) {
    if (searchMatches == null || searchMatches.ranges.isEmpty()) return;
    searchIndex = Math.max(0, Math.min(searchMatches.ranges.size() - 1, index));
    TextNavigator.Range range = searchMatches.ranges.get(searchIndex);
    page.setContent(searchText, range.start);
    page.highlight(range.start, range.end);
    searchNavigation.removeAllViews();
    add(
        searchNavigation,
        button(activity, "上一条", false, () -> jumpSearch(searchIndex - 1)),
        90,
        38);
    add(
        searchNavigation,
        text(activity, (searchIndex + 1) + " / " + searchMatches.ranges.size(), 13, ACCENT),
        65,
        38);
    add(
        searchNavigation,
        button(activity, "下一条", false, () -> jumpSearch(searchIndex + 1)),
        90,
        38);
    gap(searchNavigation, 12);
    add(searchNavigation, button(activity, "结果列表", false, this::showSearchResults), 105, 38);
    add(searchNavigation, button(activity, "回到搜索前", false, () -> clearSearch(true)), 120, 38);
    add(searchNavigation, button(activity, "结束搜索", false, () -> clearSearch(false)), 105, 38);
    searchNavigation.setVisibility(VISIBLE);
    updateControls();
    page.requestFocus();
    save();
  }

  public boolean back() {
    if (selection != null) {
      stopSelection();
      return true;
    }
    if (searchNavigation.getVisibility() != VISIBLE) return false;
    showSearchResults();
    return true;
  }

  private void clearSearch(boolean restore) {
    if (restore && searchMatches != null) page.setContent(searchText, searchOrigin);
    searchMatches = null;
    searchNavigation.setVisibility(GONE);
    updateControls();
    page.highlight(-1, -1);
    if (restore) save();
    page.requestFocus();
  }

  private void startSpeech() {
    if (!textReady || busy) return;
    stopAuto();
    clearSearch(false);
    stopSelection();
    if (speech != null) {
      speechNavigation.setVisibility(VISIBLE);
      updateControls();
      return;
    }
    if (!SpeechSettings.available(activity)) {
      showSpeechSetup();
      return;
    }
    speechRequested = true;
    speechReady = false;
    stopAtChapter = false;
    speechNavigation.removeAllViews();
    speechToggle = button(activity, "准备语音…", false, this::toggleSpeech);
    add(speechNavigation, speechToggle, 110, 38);
    add(
        speechNavigation,
        button(
            activity,
            "上一段",
            false,
            () -> {
              if (speech != null) speech.move(-1);
            }),
        88,
        38);
    add(
        speechNavigation,
        button(
            activity,
            "下一段",
            false,
            () -> {
              if (speech != null) speech.move(1);
            }),
        88,
        38);
    sleepLabel = text(activity, "朗读 · 不定时", 12, MUTED);
    add(speechNavigation, sleepLabel, 135, 38);
    add(speechNavigation, button(activity, "朗读设置", false, this::speechOptions), 105, 38);
    speechNavigation.setVisibility(VISIBLE);
    updateControls();
    speech =
        new ReaderSpeech(
            activity,
            prefs.getFloat("speechRate", 1f),
            new ReaderSpeech.Listener() {
              public void ready(boolean available) {
                if (!active || speech == null) return;
                speechReady = available;
                if (!available) {
                  speechRequested = false;
                  showSpeechSetup();
                  stopSpeech();
                  return;
                }
                speech.load(page.content(), page.offset());
                if (speechRequested && foreground) speech.play();
              }

              public void range(TextNavigator.Range range, int spokenOffset) {
                if (!active || speech == null) return;
                if (spokenOffset < page.offset() || spokenOffset >= page.nextOffset())
                  page.setContent(page.content(), spokenOffset);
                page.highlight(range.start, range.end);
                if (spokenOffset == range.start) save();
              }

              public void state(boolean playing) {
                if (speechToggle != null) speechToggle.setText(playing ? "暂停朗读" : "继续朗读");
              }

              public void ended() {
                nextSpokenChapter();
              }

              public void message(String message) {
                speechRequested = resumeSpeechAfterLoad = false;
                Toast.makeText(activity, message, Toast.LENGTH_LONG).show();
              }
            });
    speech.foreground(foreground);
    page.requestFocus();
  }

  private void startSelection() {
    stopAuto();
    stopSpeech();
    clearSearch(false);
    selection = TextNavigator.paragraph(page.content(), page.offset());
    if (selection == null) {
      Toast.makeText(activity, "这里没有可收藏的正文", Toast.LENGTH_SHORT).show();
      return;
    }
    selectionNavigation.removeAllViews();
    add(selectionNavigation, button(activity, "上一段", false, () -> selectParagraph(-1)), 85, 38);
    add(selectionNavigation, button(activity, "下一段", false, () -> selectParagraph(1)), 85, 38);
    add(selectionNavigation, button(activity, "收藏并备注", false, this::saveSelection), 120, 38);
    add(selectionNavigation, button(activity, "结束选段", false, this::stopSelection), 105, 38);
    add(selectionNavigation, text(activity, "↑↓ 选段 · 确定收藏 · ←→ 选操作", 12, MUTED), -2, 38);
    selectionNavigation.setVisibility(VISIBLE);
    updateControls();
    showSelection();
  }

  private void selectParagraph(int direction) {
    if (selection == null) return;
    TextNavigator.Range next =
        direction < 0
            ? TextNavigator.previousParagraph(page.content(), selection.start)
            : TextNavigator.paragraph(page.content(), selection.end);
    if (next == null) {
      Toast.makeText(activity, "已到当前正文的边界", Toast.LENGTH_SHORT).show();
      return;
    }
    selection = next;
    showSelection();
  }

  private void showSelection() {
    page.setContent(page.content(), selection.start);
    page.highlight(selection.start, selection.end);
    page.requestFocus();
  }

  private void stopSelection() {
    if (selection == null) return;
    selection = null;
    selectionNavigation.setVisibility(GONE);
    updateControls();
    page.highlight(-1, -1);
    page.requestFocus();
  }

  private void saveSelection() {
    if (selection == null) return;
    final TextNavigator.Range selected = selection;
    final String text = page.content();
    EditText input = new EditText(activity);
    input.setHint("写下自己的想法（可留空）");
    input.setMaxLines(5);
    input.setFilters(
        new android.text.InputFilter[] {new android.text.InputFilter.LengthFilter(2000)});
    new ReaderDialog(activity)
        .setTitle("收藏段落")
        .setMessage(
            TextNavigator.snippet(
                text,
                new TextNavigator.Range(
                    selected.start, Math.min(selected.end, selected.start + 120))))
        .setView(input)
        .setPositiveButton(
            "保存收藏",
            (d, w) -> {
              try {
                library.annotate(book, text, selected, input.getText().toString());
                Toast.makeText(activity, "段落已保存，可在我的书签中查看和导出", Toast.LENGTH_SHORT).show();
                stopSelection();
              } catch (IOException e) {
                Toast.makeText(activity, e.getMessage(), Toast.LENGTH_LONG).show();
              }
            })
        .setNegativeButton("取消", null)
        .show();
  }

  public void showMark(LibraryStore.Mark mark) {
    pendingMark = mark;
    if (textReady && !busy) applyPendingMark();
  }

  private void applyPendingMark() {
    LibraryStore.Mark mark = pendingMark;
    pendingMark = null;
    if (mark == null || !mark.isNote()) return;
    if (mark.matches(page.content())) {
      page.setContent(page.content(), mark.offset);
      page.highlight(mark.offset, mark.end);
    } else {
      new ReaderDialog(activity)
          .setTitle("原文位置需要核对")
          .setMessage(
              "当前文件在收藏位置的文字已变化，已保留原摘录。\n\n"
                  + mark.excerpt.substring(0, TextNavigator.boundary(mark.excerpt, 180)))
          .setPositiveButton("知道了", null)
          .show();
    }
  }

  private void toggleSpeech() {
    if (speech == null || !speechReady || busy) return;
    speechRequested = !speech.playing();
    resumeSpeechAfterLoad = false;
    if (speechRequested) speech.play();
    else speech.pause();
  }

  private void stopSpeech() {
    speechRequested = resumeSpeechAfterLoad = false;
    main.removeCallbacks(sleepStop);
    speechDeadline = 0;
    if (speech != null) {
      speech.close();
      speech = null;
      page.highlight(-1, -1);
    }
    if (speechNavigation != null) speechNavigation.setVisibility(GONE);
    updateControls();
  }

  private void nextSpokenChapter() {
    if (!active || !foreground || !speechRequested || speech == null) return;
    if (!stopAtChapter) {
      if (session.epub != null && book.chapter + 1 < session.chapterCount()) {
        book.chapter++;
      } else if (session.segment != null && session.segment.end < session.textLength()) {
        book.txtByte = session.segment.end;
      } else {
        speechRequested = false;
        clearSpeechTimer();
        return;
      }
      book.offset = 0;
      resumeSpeechAfterLoad = true;
      loadChapter(false, false);
    } else {
      speechRequested = false;
      clearSpeechTimer();
      Toast.makeText(activity, "已在当前正文结束处停止朗读", Toast.LENGTH_SHORT).show();
    }
  }

  private void speechRate() {
    float[] rates = {.75f, 1f, 1.25f, 1.5f, 2f};
    int selected = 1;
    for (int i = 0; i < rates.length; i++)
      if (Math.abs(rates[i] - prefs.getFloat("speechRate", 1f)) < .01) selected = i;
    new ReaderDialog(activity)
        .setTitle("朗读语速")
        .setSingleChoiceItems(
            new String[] {"0.75 倍", "1 倍", "1.25 倍", "1.5 倍", "2 倍"},
            selected,
            (d, which) -> {
              prefs.edit().putFloat("speechRate", rates[which]).apply();
              if (speech != null) speech.rate(rates[which]);
              d.dismiss();
            })
        .setNegativeButton("返回", null)
        .show();
  }

  private void speechOptions() {
    new ReaderDialog(activity)
        .setTitle("朗读设置")
        .setItems(
            new String[] {"语速", "定时停止", "语音管理 · " + SpeechSettings.name(activity), "结束朗读"},
            (d, i) -> {
              if (i == 0) speechRate();
              else if (i == 1) speechTimer();
              else if (i == 2) {
                stopSpeech();
                SpeechPanel.show(activity, null);
              } else stopSpeech();
            })
        .setNegativeButton("返回", null)
        .show();
  }

  private void speechTimer() {
    new ReaderDialog(activity)
        .setTitle("朗读定时停止")
        .setItems(
            new String[] {
              "不定时", "15 分钟后", "30 分钟后", "60 分钟后", session.epub != null ? "本章结束" : "当前 TXT 片段结束"
            },
            (d, which) -> {
              clearSpeechTimer();
              stopAtChapter = which == 4;
              int minutes = which == 1 ? 15 : which == 2 ? 30 : 60;
              if (which > 0 && which < 4) {
                speechDeadline = android.os.SystemClock.elapsedRealtime() + minutes * 60_000L;
                main.postDelayed(sleepStop, minutes * 60_000L);
              }
              sleepLabel.setText(which == 0 ? "定时停止" : which == 4 ? "正文末尾停" : minutes + " 分钟后停");
            })
        .setNegativeButton("返回", null)
        .show();
  }

  private void clearSpeechTimer() {
    main.removeCallbacks(sleepStop);
    speechDeadline = 0;
    if (sleepLabel != null) sleepLabel.setText("定时停止");
  }

  private void showSpeechSetup() {
    new ReaderDialog(activity)
        .setTitle("准备朗读语音")
        .setMessage(
            SpeechSettings.status(activity) + "。可下载应用内离线中文语音包，无需安装系统语音。配置完成后返回阅读菜单开启朗读，阅读位置会保留。")
        .setPositiveButton("管理朗读语音", (d, w) -> SpeechPanel.show(activity, null))
        .setNegativeButton("返回", null)
        .show();
  }

  private boolean mayAutoAdvance() {
    return active
        && AutoPagePolicy.mayAdvance(
            autoEnabled, autoPaused, foreground, readerDialogs.size(), busy, speech != null);
  }

  private void scheduleAuto() {
    main.removeCallbacks(autoTick);
    updateAutoLabel();
    if (mayAutoAdvance()) main.postDelayed(autoTick, autoSeconds * 1000L);
  }

  private void advanceAutomatically() {
    if (!mayAutoAdvance()) return;
    turn(1, true);
    scheduleAuto();
  }

  private void autoMenu() {
    new ReaderDialog(activity)
        .setTitle("自动翻页")
        .setItems(
            new String[] {
              autoEnabled ? (autoPaused ? "继续自动翻页" : "暂停自动翻页") : "开始自动翻页",
              "每页停留时间（" + autoSeconds + " 秒）",
              "关闭自动翻页"
            },
            (d, which) -> {
              if (which == 1) autoInterval();
              else if (which == 2) stopAuto();
              else if (autoEnabled) toggleAuto();
              else {
                stopSpeech();
                stopSelection();
                clearSearch(false);
                autoEnabled = true;
                autoPaused = false;
                showAutoNavigation();
                scheduleAuto();
              }
            })
        .setNegativeButton("返回", null)
        .show();
  }

  private void autoInterval() {
    int[] seconds = {5, 10, 20, 30, 45, 60, 90, 120};
    String[] labels = new String[seconds.length];
    for (int i = 0; i < seconds.length; i++) labels[i] = seconds[i] + " 秒";
    new ReaderDialog(activity)
        .setTitle("每页停留时间")
        .setSingleChoiceItems(
            labels,
            java.util.Arrays.binarySearch(seconds, autoSeconds),
            (d, which) -> {
              autoSeconds = seconds[which];
              prefs.edit().putInt("autoSeconds", autoSeconds).apply();
              if (autoEnabled) showAutoNavigation();
              d.dismiss();
              scheduleAuto();
            })
        .setNegativeButton("返回", null)
        .show();
  }

  private void showAutoNavigation() {
    autoNavigation.removeAllViews();
    autoStatus = text(activity, "自动翻页", 12, MUTED);
    add(autoNavigation, autoStatus, 135, 44);
    autoToggle = button(activity, "", false, this::toggleAuto);
    add(autoNavigation, autoToggle, 115, 38);
    add(
        autoNavigation,
        button(activity, "每页 " + autoSeconds + " 秒", false, this::autoInterval),
        135,
        38);
    add(autoNavigation, button(activity, "关闭自动翻页", false, this::stopAuto), 135, 38);
    autoNavigation.setVisibility(VISIBLE);
    updateControls();
    updateAutoLabel();
    surface().requestFocus();
  }

  private void toggleAuto() {
    if (!autoEnabled) return;
    autoPaused = !autoPaused;
    updateAutoLabel();
    scheduleAuto();
  }

  private void updateAutoLabel() {
    if (autoToggle != null) autoToggle.setText(autoPaused ? "继续翻页" : "暂停翻页");
    if (autoStatus != null)
      autoStatus.setText(
          "自动翻页\n"
              + (autoPaused
                  ? "已暂停 · 请主动继续"
                  : !foreground
                      ? "暂挂 · 应用不可见"
                      : !readerDialogs.isEmpty() ? "菜单暂挂" : busy ? "等待正文" : "运行中"));
  }

  private void stopAuto() {
    autoEnabled = false;
    autoPaused = false;
    main.removeCallbacks(autoTick);
    if (autoNavigation != null) autoNavigation.setVisibility(GONE);
    updateControls();
    surface().requestFocus();
  }

  public void foreground(boolean value) {
    foreground = value;
    if (value) highResolution.requestRender();
    if (value && speechDeadline > 0) {
      main.removeCallbacks(sleepStop);
      long remaining = speechDeadline - android.os.SystemClock.elapsedRealtime();
      if (remaining <= 0) stopTimedSpeech();
      else main.postDelayed(sleepStop, remaining);
    }
    if (!value) speechRequested = resumeSpeechAfterLoad = false;
    if (speech != null) speech.foreground(value);
    if (!value) save();
    scheduleAuto();
  }

  private final class ReaderDialog extends ReadingDialog {
    private android.content.DialogInterface.OnDismissListener dismissed;

    ReaderDialog(Activity context) {
      super(context);
    }

    @Override
    public AlertDialog.Builder setOnDismissListener(
        android.content.DialogInterface.OnDismissListener listener) {
      dismissed = listener;
      return this;
    }

    @Override
    public AlertDialog create() {
      AlertDialog dialog = super.create();
      readerDialogs.add(dialog);
      scheduleAuto();
      dialog.setOnDismissListener(
          d -> {
            readerDialogs.remove(dialog);
            if (active) {
              if (dismissed != null) dismissed.onDismiss(d);
              if (readerDialogs.isEmpty()) surface().requestFocus();
              scheduleAuto();
            }
          });
      return dialog;
    }
  }

  private void contents() {
    stopSpeech();
    stopSelection();
    if (book.format.equals("pdf")) {
      EditText input = new EditText(activity);
      input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
      input.setText(Integer.toString(book.pdfPage + 1));
      new ReaderDialog(activity)
          .setTitle("跳转页码（1–" + session.pdfPages() + "）")
          .setView(input)
          .setPositiveButton(
              "跳转",
              (d, w) -> {
                try {
                  int p = Integer.parseInt(input.getText().toString());
                  if (p < 1 || p > session.pdfPages()) throw new NumberFormatException();
                  book.pdfPage = p - 1;
                  book.pdfX = book.pdfY = 0;
                  loadPdf();
                } catch (NumberFormatException e) {
                  Toast.makeText(activity, "请输入有效页码", Toast.LENGTH_SHORT).show();
                }
              })
          .setNegativeButton("取消", null)
          .show();
      return;
    }
    if (session.epub == null) {
      Toast.makeText(activity, "TXT 按正文分段，使用左右键翻页", Toast.LENGTH_SHORT).show();
      return;
    }
    List<String> titles = session.chapterTitles();
    new ReaderDialog(activity)
        .setTitle("目录")
        .setSingleChoiceItems(
            titles.toArray(new String[0]),
            book.chapter,
            (d, which) -> {
              book.chapter = which;
              book.offset = 0;
              d.dismiss();
              loadChapter(false, false);
            })
        .setNegativeButton("返回", null)
        .show();
  }

  private void fontMenu() {
    LinearLayout content = vertical(activity);
    AlertDialog dialog =
        new ReaderDialog(activity)
            .setTitle("字体与排版")
            .setView(ReadingPanel.scroll(activity, content))
            .setNegativeButton("完成", null)
            .create();
    Runnable[] render = new Runnable[1];
    render[0] =
        () -> {
          String focused =
              content.findFocus() == null
                  ? "scope-book"
                  : String.valueOf(content.findFocus().getTag());
          content.removeAllViews();
          ReadingStyle style = ReadingStyle.effective(book, prefs);
          boolean own = book.readingStyle != null;
          ReadingPanel.section(content, "应用范围 · " + (own ? "仅本书" : "全局默认"));
          ReadingPanel.row(
              content,
              setting(
                  "全局默认",
                  "scope-global",
                  !own,
                  () -> {
                    book.readingStyle = null;
                    applyAppearance();
                    save();
                    render[0].run();
                  }),
              setting(
                  "仅本书",
                  "scope-book",
                  own,
                  () -> {
                    if (book.readingStyle == null) book.readingStyle = ReadingStyle.defaults(prefs);
                    save();
                    render[0].run();
                  }));
          ReadingPanel.section(content, "字号 · " + style.font + " 号");
          ReadingPanel.row(
              content,
              setting(
                  "减小字号",
                  "font-less",
                  false,
                  () -> {
                    style.font = Math.max(16, style.font - 2);
                    saveStyle(style);
                    render[0].run();
                  }),
              setting(
                  "增大字号",
                  "font-more",
                  false,
                  () -> {
                    style.font = Math.min(40, style.font + 2);
                    saveStyle(style);
                    render[0].run();
                  }));
          ReadingPanel.section(content, "字体 · " + (style.serif ? "宋体" : "黑体"));
          ReadingPanel.row(
              content,
              setting(
                  "宋体",
                  "font-serif",
                  style.serif,
                  () -> {
                    style.serif = true;
                    saveStyle(style);
                    render[0].run();
                  }),
              setting(
                  "黑体",
                  "font-sans",
                  !style.serif,
                  () -> {
                    style.serif = false;
                    saveStyle(style);
                    render[0].run();
                  }));
          ReadingPanel.section(content, "行距");
          String[] lines = {"紧凑 1.45", "舒展 1.65", "宽松 1.85"};
          float[] values = {1.45f, 1.65f, 1.85f};
          View[] spacing = new View[3];
          for (int i = 0; i < 3; i++) {
            final int choice = i;
            spacing[i] =
                setting(
                    lines[i],
                    "line-" + i,
                    Math.abs(style.line - values[i]) < .01,
                    () -> {
                      style.line = values[choice];
                      saveStyle(style);
                      render[0].run();
                    });
          }
          ReadingPanel.row(content, spacing);
          ReadingPanel.section(content, "阅读栏宽");
          View[] widths = new View[3];
          String[] names = {"窄栏", "中栏", "宽栏"};
          for (int i = 0; i < 3; i++) {
            final int choice = i;
            widths[i] =
                setting(
                    names[i],
                    "width-" + i,
                    style.width == i,
                    () -> {
                      style.width = choice;
                      saveStyle(style);
                      render[0].run();
                    });
          }
          ReadingPanel.row(content, widths);
          View restore = content.findViewWithTag(focused);
          if (restore != null) restore.requestFocus();
        };
    render[0].run();
    dialog.show();
    content
        .findViewWithTag(book.readingStyle == null ? "scope-global" : "scope-book")
        .requestFocus();
  }

  private TextView setting(String label, String key, boolean selected, Runnable action) {
    TextView view = ReadingPanel.option(activity, label, selected, action);
    view.setTag(key);
    view.setOnClickListener(
        v -> {
          v.requestFocus();
          action.run();
        });
    return view;
  }

  private void saveStyle(ReadingStyle style) {
    if (book.readingStyle == null) style.saveDefaults(prefs);
    else book.readingStyle = style;
    applyAppearance();
    save();
  }

  private void paperMenu() {
    boolean pdf = book.format.equals("pdf");
    String initial =
        pdf
            ? "brightness-" + prefs.getInt("brightness", 100)
            : "paper-" + ReadingStyle.effective(book, prefs).paper;
    LinearLayout content = vertical(activity);
    AlertDialog dialog =
        new ReaderDialog(activity)
            .setTitle(pdf ? "阅读亮度" : "亮度与纸色")
            .setView(ReadingPanel.scroll(activity, content))
            .setNegativeButton("完成", null)
            .create();
    Runnable[] render = new Runnable[1];
    render[0] =
        () -> {
          String focused =
              content.findFocus() == null ? initial : String.valueOf(content.findFocus().getTag());
          content.removeAllViews();
          ReadingStyle style = ReadingStyle.effective(book, prefs);
          if (!pdf) {
            ReadingPanel.section(content, "纸色 · " + (book.readingStyle == null ? "全局默认" : "仅本书"));
            String[] names = {"暗色书房", "暖纸色", "墨黑"};
            View[] papers = new View[3];
            for (int i = 0; i < 3; i++) {
              final int choice = i;
              papers[i] =
                  setting(
                      names[i],
                      "paper-" + i,
                      style.paper == i,
                      () -> {
                        style.paper = choice;
                        saveStyle(style);
                        render[0].run();
                      });
            }
            ReadingPanel.row(content, papers);
          }
          ReadingPanel.section(content, "阅读亮度 · 全局设置");
          View[] levels = new View[3];
          int[] brightness = {60, 80, 100};
          for (int i = 0; i < 3; i++) {
            final int value = brightness[i];
            levels[i] =
                setting(
                    value + "%",
                    "brightness-" + i,
                    prefs.getInt("brightness", 100) == value,
                    () -> {
                      prefs.edit().putInt("brightness", value).apply();
                      applyAppearance();
                      render[0].run();
                    });
          }
          ReadingPanel.row(content, levels);
          View restore = content.findViewWithTag(focused);
          if (restore != null) restore.requestFocus();
        };
    render[0].run();
    dialog.show();
    View selected = content.findViewWithTag(initial);
    if (selected != null) selected.requestFocus();
  }

  public void save() {
    if ((textReady || bitmap != null) && book.readState.equals("unread"))
      book.readState = "reading";
    book.lastRead = System.currentTimeMillis();
    book.lastMode = session.online ? "online" : "offline";
    if (textReady && !book.format.equals("pdf")) {
      book.offset = page.offset();
      book.imageY = page.imageY();
    }
    try {
      library.save();
    } catch (IOException e) {
      Toast.makeText(activity, "阅读进度未能保存", Toast.LENGTH_LONG).show();
    }
  }

  public void dispose() {
    if (!active) return;
    save();
    active = false;
    highResolution.onStateChanged = null;
    highResolution.renderer = null;
    highResolution.setVisibility(GONE);
    stopAuto();
    stopSpeech();
    main.removeCallbacksAndMessages(null);
    for (AlertDialog dialog : new ArrayList<>(readerDialogs)) dialog.dismiss();
    generation++;
    io.submit(
        () -> {
          try {
            session.close();
          } catch (IOException ignored) {
          }
        });
    image.setImageDrawable(null);
    page.releaseIllustration();
    if (bitmap != null) {
      bitmap.recycle();
      bitmap = null;
    }
  }
}
