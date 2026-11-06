package io.github.spudhero.shufangtv;

import static org.junit.Assert.*;

import android.app.AlertDialog;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;
import io.github.spudhero.shufangtv.data.BookRecord;
import io.github.spudhero.shufangtv.data.LibraryStore;
import io.github.spudhero.shufangtv.reader.BookSession;
import io.github.spudhero.shufangtv.reader.PagedTextView;
import io.github.spudhero.shufangtv.ui.ReaderScreen;
import java.io.File;
import java.io.FileOutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.util.ReflectionHelpers;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 31, qualifiers = "zh-rCN-w960dp-h540dp-land-television-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class ReaderFeaturesTest {
  @Test
  public void automaticPagingCrossesTxtSegmentsWithoutSavingOldOffsetsAndStopsAtEnd()
      throws Exception {
    android.content.Context context = org.robolectric.RuntimeEnvironment.getApplication();
    LibraryStore library = new LibraryStore(context);
    BookRecord book = new BookRecord();
    book.title = "跨段阅读";
    book.format = "txt";
    File file = library.file(book);
    book.offlineFile = file.getName();
    java.nio.file.Files.write(
        file.toPath(),
        ("跨段落😀。".repeat(100) + "\n").repeat(60).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    library.books.add(book);
    library.save();
    try (ActivityController<MainActivity> controller =
        Robolectric.buildActivity(MainActivity.class).setup()) {
      MainActivity activity = controller.get();
      View decor = activity.getWindow().getDecorView();
      findText(decor, "我的书库").performClick();
      decor.findViewWithTag("book-" + book.id).performClick();
      choose("离线打开");
      settle(activity);
      render(decor, "features-txt-segments.png");
      PagedTextView page = find(decor, PagedTextView.class);
      ReaderScreen reader = find(decor, ReaderScreen.class);
      BookSession session = ReflectionHelpers.getField(reader, "session");
      long boundary = session.segment.end;
      assertTrue(boundary < session.textLength());
      page.setContent(page.content(), page.totalChars() - 1);
      findText(decor, "阅读菜单").performClick();
      choose("自动翻页");
      choose("开始自动翻页");
      ShadowLooper.idleMainLooper();
      ExecutorService io = ReflectionHelpers.getField(activity, "io");
      java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
      io.submit(
          () -> {
            try {
              release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            }
          });
      try {
        ShadowLooper.idleMainLooper(30, TimeUnit.SECONDS);
        controller.pause();
        BookRecord paused = new LibraryStore(activity).find(book.id);
        assertEquals(boundary, paused.txtByte);
        assertEquals(0, paused.offset);
      } finally {
        release.countDown();
      }
      settle(activity);
      controller.resume();
      assertEquals(boundary, session.segment.start);
      assertEquals(0, page.offset());
      for (int i = 0; i < 5 && (boolean) ReflectionHelpers.getField(reader, "autoEnabled"); i++) {
        page.setContent(page.content(), page.totalChars() - 1);
        ShadowLooper.idleMainLooper(30, TimeUnit.SECONDS);
        settle(activity);
      }
      assertEquals(session.textLength(), session.segment.end);
      assertFalse((boolean) ReflectionHelpers.getField(reader, "autoEnabled"));
    }
  }

  @Test
  @Config(shadows = io.github.spudhero.shufangtv.reader.ReaderSpeechTest.ControlledTts.class)
  public void narrationTimerMediaKeyAndAutoPagingShareOneReadingPosition() throws Exception {
    org.robolectric.shadows.ShadowTextToSpeech.addLanguageAvailability(
        java.util.Locale.SIMPLIFIED_CHINESE);
    android.content.pm.ResolveInfo engine = new android.content.pm.ResolveInfo();
    engine.serviceInfo = new android.content.pm.ServiceInfo();
    engine.serviceInfo.packageName = "test.speech";
    engine.serviceInfo.name = "test.speech.Engine";
    engine.serviceInfo.exported = true;
    org.robolectric.Shadows.shadowOf(
            org.robolectric.RuntimeEnvironment.getApplication().getPackageManager())
        .addResolveInfoForIntent(
            new android.content.Intent(
                android.speech.tts.TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE),
            engine);
    try (ActivityController<MainActivity> controller =
        Robolectric.buildActivity(MainActivity.class).setup()) {
      MainActivity activity = controller.get();
      View decor = activity.getWindow().getDecorView();
      findText(decor, "先读一篇使用指南").performClick();
      settle(activity);
      render(decor, "features-speech-before.png");
      findText(decor, "阅读菜单").performClick();
      choose("朗读正文");
      org.robolectric.shadows.ShadowTextToSpeech tts =
          org.robolectric.Shadows.shadowOf(
              org.robolectric.shadows.ShadowTextToSpeech.getLastTextToSpeechInstance());
      tts.getOnInitListener().onInit(android.speech.tts.TextToSpeech.SUCCESS);
      ShadowLooper.idleMainLooper();
      ReaderScreen reader = find(decor, ReaderScreen.class);
      io.github.spudhero.shufangtv.reader.ReaderSpeech speech =
          ReflectionHelpers.getField(reader, "speech");
      assertTrue(speech.playing());
      render(decor, "features-speech-controls.png");
      assertNull("Narration must replace the ordinary page controls", findText(decor, "下一页 〉"));
      findText(decor, "朗读设置").performClick();
      choose("定时停止");
      choose("15 分钟后");
      ShadowLooper.idleMainLooper();
      ShadowLooper.idleMainLooper(15, TimeUnit.MINUTES);
      assertFalse(speech.playing());
      activity.dispatchKeyEvent(
          new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE));
      assertTrue(speech.playing());
      findText(decor, "朗读设置").performClick();
      choose("定时停止");
      choose("15 分钟后");
      ShadowLooper.idleMainLooper();
      controller.pause();
      org.robolectric.shadows.ShadowSystemClock.simulateDeepSleep(java.time.Duration.ofMinutes(20));
      controller.resume();
      assertFalse(speech.playing());
      assertEquals("定时停止", findText(decor, "定时停止").getText().toString());
      activity.dispatchKeyEvent(
          new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE));
      assertTrue(speech.playing());
      findText(decor, "阅读菜单").performClick();
      choose("自动翻页");
      choose("开始自动翻页");
      ShadowLooper.idleMainLooper();
      assertTrue(tts.isShutdown());
      assertFalse(speech.playing());
      assertNotNull(findText(decor, "暂停翻页"));
    }
  }

  @Test
  public void automaticPagingResumesAfterMenusButPreservesManualPauseAndStopsOnExit()
      throws Exception {
    try (ActivityController<MainActivity> controller =
        Robolectric.buildActivity(MainActivity.class).setup()) {
      MainActivity activity = controller.get();
      View decor = activity.getWindow().getDecorView();
      findText(decor, "先读一篇使用指南").performClick();
      settle(activity);
      render(decor, "features-auto-before.png");
      PagedTextView page = find(decor, PagedTextView.class);
      // A long loaded segment keeps this timer test independent of I/O scheduling at chapter edges.
      page.setContent(page.content().repeat(10), 0);
      findText(decor, "阅读菜单").performClick();
      choose("自动翻页");
      choose("开始自动翻页");
      ShadowLooper.idleMainLooper();
      ShadowLooper.idleMainLooper(30, TimeUnit.SECONDS);
      int first = page.offset();
      assertTrue(first > 0);
      findText(decor, "阅读菜单").performClick();
      ShadowLooper.idleMainLooper(60, TimeUnit.SECONDS);
      assertEquals(first, page.offset());
      ShadowAlertDialog.getLatestAlertDialog().dismiss();
      ShadowLooper.idleMainLooper();
      ShadowLooper.idleMainLooper(30, TimeUnit.SECONDS);
      assertTrue(page.offset() > first);
      findText(decor, "暂停翻页").performClick();
      int paused = page.offset();
      findText(decor, "阅读菜单").performClick();
      choose("字体与排版");
      ShadowAlertDialog.getLatestAlertDialog().dismiss();
      ShadowAlertDialog.getShownDialogs().stream()
          .filter(android.app.Dialog::isShowing)
          .forEach(android.app.Dialog::dismiss);
      ShadowLooper.idleMainLooper();
      ShadowLooper.idleMainLooper(60, TimeUnit.SECONDS);
      assertEquals(paused, page.offset());
      findText(decor, "继续翻页").performClick();
      controller.pause();
      ShadowLooper.idleMainLooper(60, TimeUnit.SECONDS);
      assertEquals(paused, page.offset());
      controller.resume();
      ShadowLooper.idleMainLooper(30, TimeUnit.SECONDS);
      assertTrue(page.offset() > paused);
      render(decor, "features-auto-controls.png");
      int leaving = page.offset();
      ReaderScreen reader = find(decor, ReaderScreen.class);
      reader.dispose();
      ShadowLooper.idleMainLooper(120, TimeUnit.SECONDS);
      assertEquals(leaving, page.offset());
    }
  }

  @Test
  public void remoteParagraphSelectionPersistsNoteAndBookShelfOrganization() throws Exception {
    try (ActivityController<MainActivity> controller =
        Robolectric.buildActivity(MainActivity.class).setup()) {
      MainActivity activity = controller.get();
      View decor = activity.getWindow().getDecorView();
      findText(decor, "先读一篇使用指南").performClick();
      settle(activity);
      render(decor, "features-note-before.png");
      findText(decor, "阅读菜单").performClick();
      choose("收藏正文段落");
      ShadowLooper.idleMainLooper();
      activity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_DOWN));
      render(decor, "features-paragraph-selection.png");
      assertNull("Selection must replace the ordinary page controls", findText(decor, "下一页 〉"));
      activity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER));
      ShadowLooper.idleMainLooper();
      AlertDialog note = ShadowAlertDialog.getLatestAlertDialog();
      find(note.getWindow().getDecorView(), EditText.class).setText("遥控器段落收藏测试");
      note.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
      ShadowLooper.idleMainLooper();
      LibraryStore saved = new LibraryStore(activity);
      assertEquals(1, saved.marks.size());
      assertTrue(saved.marks.get(0).isNote());
      assertEquals("遥控器段落收藏测试", saved.marks.get(0).note);
      activity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK));
      settle(activity);
      findText(decor, "我的书库").performClick();
      String id = saved.books.get(0).id;
      decor.findViewWithTag("book-" + id).performClick();
      choose("置顶");
      decor.findViewWithTag("book-" + id).performClick();
      choose("加入书单");
      choose("新建书单…");
      AlertDialog group = ShadowAlertDialog.getLatestAlertDialog();
      find(group.getWindow().getDecorView(), EditText.class).setText("睡前读");
      group.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
      ShadowLooper.idleMainLooper();
      decor.findViewWithTag("book-" + id).performClick();
      choose("阅读状态");
      choose("已读");
      saved = new LibraryStore(activity);
      assertTrue(saved.books.get(0).pinned);
      assertEquals("睡前读", saved.books.get(0).group);
      assertEquals("finished", saved.books.get(0).readState);
      render(decor, "features-library-organization.png");
      findText(decor, "我的书签").performClick();
      render(decor, "features-notes-library.png");
      findText(decor, "导出收藏").performClick();
      choose("Markdown");
      settle(activity);
      android.content.Intent export =
          org.robolectric.Shadows.shadowOf(activity).getNextStartedActivityForResult().intent;
      assertEquals(android.content.Intent.ACTION_CREATE_DOCUMENT, export.getAction());
      assertEquals("text/markdown", export.getType());
      File snapshot = new File(activity.getCacheDir(), "pending-notes-export.txt");
      assertTrue(
          new String(
                  java.nio.file.Files.readAllBytes(snapshot.toPath()),
                  java.nio.charset.StandardCharsets.UTF_8)
              .contains("遥控器段落收藏测试"));
      android.net.Uri destination = android.net.Uri.parse("content://documents.test/notes.md");
      java.io.ByteArrayOutputStream exported = new java.io.ByteArrayOutputStream();
      org.robolectric.Shadows.shadowOf(activity.getContentResolver())
          .registerOutputStream(destination, exported);
      activity.onActivityResult(
          32, android.app.Activity.RESULT_OK, new android.content.Intent().setData(destination));
      settle(activity);
      assertTrue(exported.toString("UTF-8").contains("遥控器段落收藏测试"));
      assertFalse(snapshot.exists());
      ShadowAlertDialog.getLatestAlertDialog().dismiss();
      ShadowLooper.idleMainLooper();
      LibraryStore.Mark mark = saved.marks.get(0);
      decor.findViewWithTag("mark-" + mark.id).performClick();
      choose("回到原文");
      settle(activity);
      assertEquals(mark.offset, find(decor, PagedTextView.class).offset());
      render(decor, "features-note-reopened.png");
    }
  }

  @Test
  public void searchFindsBodyTextAndReturnsToOriginalPosition() throws Exception {
    try (ActivityController<MainActivity> controller =
        Robolectric.buildActivity(MainActivity.class).setup()) {
      MainActivity activity = controller.get();
      View decor = activity.getWindow().getDecorView();
      findText(decor, "先读一篇使用指南").performClick();
      settle(activity);
      render(decor, "features-reader-wide.png");
      PagedTextView page = find(decor, PagedTextView.class);
      page.next();
      int origin = page.offset();
      findText(decor, "阅读菜单").performClick();
      choose("搜索当前正文");
      ShadowLooper.idleMainLooper();
      assertTrue(page.content().contains("书库"));
      AlertDialog search = ShadowAlertDialog.getLatestAlertDialog();
      find(search.getWindow().getDecorView(), EditText.class).setText("书库");
      search.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
      settle(activity);
      AlertDialog results = ShadowAlertDialog.getLatestAlertDialog();
      assertTrue(results.getListView().getCount() > 0);
      results.getListView().performItemClick(null, 0, 0);
      assertTrue(page.content().substring(page.offset()).startsWith("书库"));
      assertNotNull(findText(decor, "回到搜索前"));
      render(decor, "features-search-highlight.png");
      findText(decor, "回到搜索前").performClick();
      assertEquals(origin, page.offset());
      assertEquals("书库", new LibraryStore(activity).books.get(0).searchHistory.get(0));
      findText(decor, "阅读菜单").performClick();
      choose("字体与排版");
      choose("仅本书");
      choose("窄栏");
      assertEquals(origin, page.offset());
      assertEquals(0, new LibraryStore(activity).books.get(0).readingStyle.width);
      ShadowAlertDialog.getShownDialogs().stream()
          .filter(android.app.Dialog::isShowing)
          .forEach(android.app.Dialog::dismiss);
      render(decor, "features-reader-narrow.png");
    }
  }

  private static void choose(String label) {
    AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
    View custom = dialog.getWindow().getDecorView().findViewWithTag("tool:" + label);
    if (custom != null) {
      custom.performClick();
      return;
    }
    TextView choice = findText(dialog.getWindow().getDecorView(), label);
    if (choice != null && choice.isClickable()) {
      choice.performClick();
      return;
    }
    if (dialog.getListView() != null)
      for (int i = 0; i < dialog.getListView().getCount(); i++) {
        if (label.equals(dialog.getListView().getItemAtPosition(i).toString())) {
          dialog.getListView().performItemClick(null, i, i);
          return;
        }
      }
    fail("No menu item: " + label);
  }

  private static TextView findText(View view, String text) {
    if (view.getVisibility() != View.VISIBLE) return null;
    if (view instanceof TextView && text.equals(((TextView) view).getText().toString()))
      return (TextView) view;
    if (view instanceof ViewGroup)
      for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
        TextView found = findText(((ViewGroup) view).getChildAt(i), text);
        if (found != null) return found;
      }
    return null;
  }

  private static <T> T find(View view, Class<T> type) {
    if (type.isInstance(view)) return type.cast(view);
    if (view instanceof ViewGroup)
      for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
        T found = find(((ViewGroup) view).getChildAt(i), type);
        if (found != null) return found;
      }
    return null;
  }

  private static void settle(MainActivity activity) throws Exception {
    ExecutorService io = ReflectionHelpers.getField(activity, "io");
    for (int i = 0; i < 4; i++) {
      io.submit(() -> {}).get(10, TimeUnit.SECONDS);
      ShadowLooper.idleMainLooper();
    }
  }

  private static void render(View view, String name) throws Exception {
    view.measure(
        View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY),
        View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY));
    view.layout(0, 0, 1920, 1080);
    Bitmap bitmap = Bitmap.createBitmap(1920, 1080, Bitmap.Config.ARGB_8888);
    view.draw(new Canvas(bitmap));
    File dir = new File("build/qa/reading-features");
    dir.mkdirs();
    try (FileOutputStream out = new FileOutputStream(new File(dir, name))) {
      bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
    } finally {
      bitmap.recycle();
    }
  }
}
