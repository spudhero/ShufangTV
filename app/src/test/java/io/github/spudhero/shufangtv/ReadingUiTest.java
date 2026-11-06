package io.github.spudhero.shufangtv;

import static org.junit.Assert.*;

import android.app.AlertDialog;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import io.github.spudhero.shufangtv.data.BookRecord;
import io.github.spudhero.shufangtv.data.LibraryStore;
import java.io.File;
import java.io.FileOutputStream;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.util.ReflectionHelpers;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 31, qualifiers = "zh-rCN-w960dp-h540dp-land-television-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class ReadingUiTest {
  @Test
  public void bundledGuideRefreshesOnOpenWithoutChangingSameNameUserBook() throws Exception {
    LibraryStore library = new LibraryStore(RuntimeEnvironment.getApplication());
    BookRecord guide = new BookRecord(), userBook = new BookRecord();
    for (BookRecord book : new BookRecord[] {guide, userBook}) {
      book.title = "书房使用指南";
      book.format = "txt";
      book.offlineFile = book.id + ".txt";
      library.books.add(book);
    }
    guide.author = "书房 TV";
    userBook.author = "用户";
    java.nio.file.Files.write(
        library.file(guide).toPath(),
        "旧指南：图片不支持。".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    String userText = "用户保存的同名书籍正文。";
    java.nio.file.Files.write(
        library.file(userBook).toPath(),
        userText.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    library.save();
    try (ActivityController<MainActivity> controller =
        Robolectric.buildActivity(MainActivity.class).setup()) {
      MainActivity activity = controller.get();
      View decor = activity.getWindow().getDecorView();
      findText(decor, "我的书库").performClick();
      java.util.concurrent.ExecutorService io = ReflectionHelpers.getField(activity, "io");
      for (BookRecord book : new BookRecord[] {guide, userBook}) {
        decor.findViewWithTag("book-" + book.id).performClick();
        choose("离线打开");
        for (int i = 0; i < 4; i++) {
          io.submit(() -> {}).get(10, java.util.concurrent.TimeUnit.SECONDS);
          ShadowLooper.idleMainLooper();
        }
        String text =
            find(decor, io.github.spudhero.shufangtv.reader.PagedTextView.class).content();
        if (book == guide) {
          assertTrue(text.contains("AZW3"));
          assertTrue(text.contains("默认全屏撑满屏宽"));
          assertTrue(text.contains("下载离线中文语音包"));
          assertFalse(text.contains("朗读使用电视的中文语音引擎"));
          assertFalse(text.contains("旧指南"));
        } else assertEquals(userText, text.trim());
        activity.dispatchKeyEvent(
            new android.view.KeyEvent(
                android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_BACK));
      }
      assertEquals(
          userText,
          new String(
              java.nio.file.Files.readAllBytes(library.file(userBook).toPath()),
              java.nio.charset.StandardCharsets.UTF_8));
      assertTrue(
          new String(
                  java.nio.file.Files.readAllBytes(library.file(guide).toPath()),
                  java.nio.charset.StandardCharsets.UTF_8)
              .contains("AZW3"));
    }
  }

  @Test
  public void imageAndMixedChaptersRenderIllustrationsAndKeepTextAnchors() throws Exception {
    LibraryStore library = new LibraryStore(RuntimeEnvironment.getApplication());
    BookRecord book = new BookRecord();
    book.title = "合成图片与文字章节";
    File file = library.file(book);
    book.offlineFile = file.getName();
    java.util.Map<String, String> entries =
        java.util.Map.of(
            "META-INF/container.xml",
            "<container><rootfiles><rootfile full-path='book.opf'/></rootfiles></container>",
            "book.opf",
            "<package><metadata><title>合成图片与文字章节</title></metadata><manifest><item id='a'"
                + " href='a.xhtml' media-type='application/xhtml+xml'/><item id='b' href='b.xhtml'"
                + " media-type='application/xhtml+xml'/><item id='c' href='c.xhtml'"
                + " media-type='application/xhtml+xml'/></manifest><spine><itemref"
                + " idref='a'/><itemref idref='b'/><itemref idref='c'/></spine></package>",
            "a.xhtml",
            "<html><body><h1>图片章节</h1><img src='page.jpg' alt='扫描页'/></body></html>",
            "b.xhtml",
            "<html><body><h1>图文混排</h1><img src='page.jpg'/><p>这是可以阅读的合成正文。</p></body></html>",
            "c.xhtml",
            "<html><body><h1>正文</h1><p>不含图片的合成正文。</p></body></html>");
    try (java.util.zip.ZipOutputStream zip =
        new java.util.zip.ZipOutputStream(new FileOutputStream(file))) {
      for (java.util.Map.Entry<String, String> entry : entries.entrySet()) {
        zip.putNextEntry(new java.util.zip.ZipEntry(entry.getKey()));
        zip.write(entry.getValue().getBytes(java.nio.charset.StandardCharsets.UTF_8));
        zip.closeEntry();
      }
      zip.putNextEntry(new java.util.zip.ZipEntry("page.jpg"));
      Bitmap fixture = Bitmap.createBitmap(640, 120, Bitmap.Config.ARGB_8888);
      fixture.eraseColor(0xffb03020);
      fixture.compress(Bitmap.CompressFormat.PNG, 100, zip);
      fixture.recycle();
      zip.closeEntry();
    }
    library.books.add(book);
    library.save();
    try (ActivityController<MainActivity> controller =
        Robolectric.buildActivity(MainActivity.class).setup()) {
      MainActivity activity = controller.get();
      View decor = activity.getWindow().getDecorView();
      findText(decor, "我的书库").performClick();
      decor.findViewWithTag("book-" + book.id).performClick();
      choose("离线打开");
      java.util.concurrent.ExecutorService io = ReflectionHelpers.getField(activity, "io");
      for (int i = 0; i < 4; i++) {
        io.submit(() -> {}).get(10, java.util.concurrent.TimeUnit.SECONDS);
        ShadowLooper.idleMainLooper();
      }
      render(decor, "reader-image-rendered.png");
      io.submit(() -> {}).get(10, java.util.concurrent.TimeUnit.SECONDS);
      ShadowLooper.idleMainLooper();
      assertNotNull(findText(decor, "图片章节"));
      io.github.spudhero.shufangtv.reader.PagedTextView page =
          find(decor, io.github.spudhero.shufangtv.reader.PagedTextView.class);
      assertEquals("［图片：扫描页］", page.content().trim());
      assertEquals(0, page.offset());
      assertTrue(page.showingImage());
      assertFalse(page.imageFailed());
      Bitmap drawn =
          Bitmap.createBitmap(page.getWidth(), page.getHeight(), Bitmap.Config.ARGB_8888);
      page.draw(new Canvas(drawn));
      assertEquals(0xffb03020, drawn.getPixel(page.getWidth() / 2, page.getHeight() / 2));
      drawn.recycle();
      findText(decor, "下一页 〉").performClick();
      for (int i = 0; i < 4; i++) {
        io.submit(() -> {}).get(10, java.util.concurrent.TimeUnit.SECONDS);
        ShadowLooper.idleMainLooper();
      }
      render(decor, "reader-mixed-image-rendered.png");
      assertNotNull(findText(decor, "图文混排"));
      assertTrue(page.showingImage());
      assertTrue(page.content().contains("这是可以阅读的合成正文。"));
      findText(decor, "下一页 〉").performClick();
      for (int i = 0; i < 4; i++) {
        io.submit(() -> {}).get(10, java.util.concurrent.TimeUnit.SECONDS);
        ShadowLooper.idleMainLooper();
      }
      assertFalse(page.showingImage());
      assertTrue(page.offset() > 0);
      findText(decor, "下一页 〉").performClick();
      for (int i = 0; i < 4; i++) {
        io.submit(() -> {}).get(10, java.util.concurrent.TimeUnit.SECONDS);
        ShadowLooper.idleMainLooper();
      }
      render(decor, "reader-after-images.png");
      assertNull(findText(decor, "本节含图片，暂不支持显示 · 图文混排"));
      assertNotNull(findText(decor, "正文"));
      assertTrue(page.content().contains("不含图片的合成正文。"));
      assertTrue(page.hasFocus());
    }
  }

  @Test
  public void longReaderTitleFitsOneLineAndLeavesTheReadingModeVisible() throws Exception {
    android.content.Context context = RuntimeEnvironment.getApplication();
    LibraryStore library = new LibraryStore(context);
    BookRecord book = new BookRecord();
    book.title = "测试合集（全本全注全译）及多位作者的详细介绍与出版说明".repeat(8);
    book.format = "txt";
    File file = library.file(book);
    book.offlineFile = file.getName();
    java.nio.file.Files.write(
        file.toPath(),
        "第一段正文。\n第二段正文。\n".repeat(50).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    library.books.add(book);
    library.save();
    try (ActivityController<MainActivity> controller =
        Robolectric.buildActivity(MainActivity.class).setup()) {
      MainActivity activity = controller.get();
      View decor = activity.getWindow().getDecorView();
      findText(decor, "我的书库").performClick();
      decor.findViewWithTag("book-" + book.id).performClick();
      choose("离线打开");
      java.util.concurrent.ExecutorService io = ReflectionHelpers.getField(activity, "io");
      for (int i = 0; i < 4; i++) {
        io.submit(() -> {}).get(10, java.util.concurrent.TimeUnit.SECONDS);
        ShadowLooper.idleMainLooper();
      }
      render(decor, "reader-long-title.png");
      TextView title = findText(decor, book.title);
      TextView mode = findText(decor, "离线阅读");
      assertEquals(1, title.getLayout().getLineCount());
      assertTrue(title.getLayout().getEllipsisCount(0) > 0);
      assertTrue(title.getLayout().getHeight() <= title.getHeight());
      int[] titleAt = new int[2], modeAt = new int[2];
      title.getLocationOnScreen(titleAt);
      mode.getLocationOnScreen(modeAt);
      assertTrue(titleAt[0] + title.getWidth() <= modeAt[0]);
      assertEquals(book.title, title.getContentDescription());
    }
  }

  @Test
  public void readingPanelsKeepTextAndDismissToReader() throws Exception {
    try (ActivityController<MainActivity> controller =
        Robolectric.buildActivity(MainActivity.class).setup()) {
      MainActivity activity = controller.get();
      View decor = activity.getWindow().getDecorView();
      findText(decor, "先读一篇使用指南").performClick();
      java.util.concurrent.ExecutorService io = ReflectionHelpers.getField(activity, "io");
      for (int i = 0; i < 4; i++) {
        io.submit(() -> {}).get(10, java.util.concurrent.TimeUnit.SECONDS);
        ShadowLooper.idleMainLooper();
      }
      render(decor, "reader-baseline.png");
      findText(decor, "阅读菜单").performClick();
      ShadowLooper.idleMainLooper();
      renderDialog(decor, "reader-tools.png");
      AlertDialog tools = ShadowAlertDialog.getLatestAlertDialog();
      assertTrue(tools.isShowing());
      choose("字体与排版");
      ShadowLooper.idleMainLooper();
      renderDialog(decor, "reader-typography.png");
      ShadowAlertDialog.getLatestAlertDialog().dismiss();
      ShadowLooper.idleMainLooper();
      assertTrue("Closing settings must return to its reading tool panel", tools.isShowing());
      tools.dismiss();
      ShadowLooper.idleMainLooper();
      assertTrue(find(decor, io.github.spudhero.shufangtv.reader.PagedTextView.class).hasFocus());
      findText(decor, "阅读菜单").performClick();
      choose("亮度与纸色");
      choose("暖纸色");
      ShadowAlertDialog.getShownDialogs().stream()
          .filter(android.app.Dialog::isShowing)
          .forEach(android.app.Dialog::dismiss);
      render(decor, "reader-warm-paper.png");
      int foreground = findText(decor, "离线阅读").getCurrentTextColor();
      int background =
          ((android.graphics.drawable.ColorDrawable)
                  find(decor, io.github.spudhero.shufangtv.ui.ReaderScreen.class).getBackground())
              .getColor();
      assertTrue(
          "Small reading-mode label must remain legible on warm paper",
          contrast(foreground, background) >= 4.5);
    }
  }

  @Test
  @Config(qualifiers = "zh-rCN-w854dp-h480dp-land-television-mdpi")
  public void smallTvSettingsPreserveSelectionFocusAndAllActionsRemainReachable() throws Exception {
    try (ActivityController<MainActivity> controller =
        Robolectric.buildActivity(MainActivity.class).setup()) {
      MainActivity activity = controller.get();
      View decor = activity.getWindow().getDecorView();
      findText(decor, "先读一篇使用指南").performClick();
      java.util.concurrent.ExecutorService io = ReflectionHelpers.getField(activity, "io");
      for (int i = 0; i < 4; i++) {
        io.submit(() -> {}).get(10, java.util.concurrent.TimeUnit.SECONDS);
        ShadowLooper.idleMainLooper();
      }
      render(decor, "reader-480p.png");
      findText(decor, "阅读菜单").performClick();
      AlertDialog tools = ShadowAlertDialog.getLatestAlertDialog();
      renderDialog(decor, "tools-480p.png");
      choose("字体与排版");
      choose("仅本书");
      choose("窄栏");
      AlertDialog settings = ShadowAlertDialog.getLatestAlertDialog();
      renderDialog(decor, "typography-480p.png");
      View selected = settings.getWindow().getDecorView().findViewWithTag("width-0");
      assertTrue(selected.isSelected());
      assertTrue(
          "Rebuilding settings must preserve the edited control's focus", selected.hasFocus());
      android.graphics.Rect visible = new android.graphics.Rect();
      assertTrue(selected.getGlobalVisibleRect(visible));
      assertEquals(
          "Focused action must be fully scrolled into view",
          selected.getHeight(),
          visible.height());
      assertTrue(settings.getButton(AlertDialog.BUTTON_NEGATIVE).isShown());
      settings.dismiss();
      assertTrue(tools.isShowing());
      tools.dismiss();
      chooseNoHiddenMode(decor);
    }
  }

  static void chooseNoHiddenMode(View decor) {
    // Hidden mode controls must never stand in for a reachable reader action.
    assertNotNull(findText(decor, "阅读菜单"));
    assertTrue(find(decor, io.github.spudhero.shufangtv.reader.PagedTextView.class).hasFocus());
  }

  static double contrast(int first, int second) {
    double[] luminance = new double[2];
    int[] colors = {first, second};
    for (int i = 0; i < colors.length; i++) {
      double[] channels = {
        android.graphics.Color.red(colors[i]) / 255.0,
        android.graphics.Color.green(colors[i]) / 255.0,
        android.graphics.Color.blue(colors[i]) / 255.0
      };
      for (int j = 0; j < 3; j++)
        channels[j] =
            channels[j] <= .04045
                ? channels[j] / 12.92
                : Math.pow((channels[j] + .055) / 1.055, 2.4);
      luminance[i] = .2126 * channels[0] + .7152 * channels[1] + .0722 * channels[2];
    }
    return (Math.max(luminance[0], luminance[1]) + .05)
        / (Math.min(luminance[0], luminance[1]) + .05);
  }

  @Test
  public void longNotePreviewKeepsItsNativeActionListVisible() throws Exception {
    try (ActivityController<MainActivity> controller =
        Robolectric.buildActivity(MainActivity.class).setup()) {
      MainActivity activity = controller.get();
      AlertDialog dialog =
          new io.github.spudhero.shufangtv.ui.ReadingDialog(activity)
              .width(480)
              .setTitle("段落收藏")
              .setMessage("原文与备注。".repeat(150))
              .setItems(new String[] {"回到原文", "编辑备注", "移除"}, (d, i) -> {})
              .setNegativeButton("返回", null)
              .show();
      renderDialog(activity.getWindow().getDecorView(), "note-detail.png");
      assertTrue(
          "Android hides a list when a message occupies its content panel",
          dialog.getListView().isShown());
      assertNotNull(dialog.getListView().getChildAt(0));
      assertTrue(dialog.getButton(AlertDialog.BUTTON_NEGATIVE).isShown());
    }
  }

  static void choose(String label) {
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
    fail("Missing option: " + label);
  }

  @Test
  public void longExcerptCannotPushTheReadersNoteOutOfItsCard() throws Exception {
    LibraryStore library = new LibraryStore(RuntimeEnvironment.getApplication());
    BookRecord book = new BookRecord();
    book.title = "慢读与生活";
    book.format = "txt";
    library.books.add(book);
    LibraryStore.Mark mark = new LibraryStore.Mark();
    mark.bookId = book.id;
    mark.excerpt = "长摘录不应该挤掉自己的想法。".repeat(100);
    mark.end = mark.excerpt.length();
    mark.note = "自己的想法仍然可见";
    library.marks.add(mark);
    library.save();
    try (ActivityController<MainActivity> controller =
        Robolectric.buildActivity(MainActivity.class).setup()) {
      View decor = controller.get().getWindow().getDecorView();
      findText(decor, "我的书签").performClick();
      render(decor, "notes-long-excerpt.png");
      TextView note = findText(decor, "备注：" + mark.note);
      assertNotNull(note);
      android.graphics.Rect visible = new android.graphics.Rect();
      assertTrue(note.getGlobalVisibleRect(visible));
      assertEquals(note.getHeight(), visible.height());
      decor.findViewWithTag("mark-" + mark.id).performClick();
      renderDialog(decor, "notes-long-detail.png");
      assertTrue(ShadowAlertDialog.getLatestAlertDialog().getListView().isShown());
    }
  }

  static <T> T find(View view, Class<T> type) {
    if (type.isInstance(view)) return type.cast(view);
    if (view instanceof ViewGroup)
      for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
        T found = find(((ViewGroup) view).getChildAt(i), type);
        if (found != null) return found;
      }
    return null;
  }

  @Test
  public void shelfKeepsReadingStatusVisibleBelowLongBookTitle() throws Exception {
    LibraryStore library = new LibraryStore(RuntimeEnvironment.getApplication());
    BookRecord book = new BookRecord();
    book.title = "在漫长旅途中读一本关于时间与生活的书";
    book.author = "测试作者";
    book.format = "txt";
    book.pinned = true;
    book.readState = "finished";
    book.group = "睡前慢读";
    library.books.add(book);
    library.save();
    try (ActivityController<MainActivity> controller =
        Robolectric.buildActivity(MainActivity.class).setup()) {
      MainActivity activity = controller.get();
      View decor = activity.getWindow().getDecorView();
      findText(decor, "我的书库").performClick();
      render(decor, "shelf-long-title.png");
      ViewGroup card = decor.findViewWithTag("book-" + book.id);
      assertNotNull(card);
      TextView status = null;
      for (int i = 0; i < card.getChildCount(); i++) {
        View child = card.getChildAt(i);
        if (child instanceof TextView && ((TextView) child).getText().toString().contains("已读"))
          status = (TextView) child;
      }
      assertNotNull("Reading state must appear on the shelf", status);
      assertTrue(
          "State clipped by card: " + status.getBottom() + " > " + card.getHeight(),
          status.getBottom() <= card.getHeight());
      View row = (View) card.getParent();
      assertTrue(
          "Card clipped by row: " + card.getBottom() + " > " + row.getHeight(),
          card.getBottom() <= row.getHeight());
    }
  }

  static TextView findText(View view, String text) {
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

  public static void render(View view, String name) throws Exception {
    int width =
        Math.round(
            view.getResources().getConfiguration().screenWidthDp
                * view.getResources().getDisplayMetrics().density);
    int height =
        Math.round(
            view.getResources().getConfiguration().screenHeightDp
                * view.getResources().getDisplayMetrics().density);
    view.measure(
        View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
        View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
    view.layout(0, 0, width, height);
    Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
    view.draw(new Canvas(bitmap));
    File dir = new File("build/qa/reading-ui");
    dir.mkdirs();
    try (FileOutputStream out = new FileOutputStream(new File(dir, name))) {
      bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
    } finally {
      bitmap.recycle();
    }
  }

  static void renderDialog(View screen, String name) throws Exception {
    ShadowLooper.idleMainLooper();
    int screenWidth =
        Math.round(
            screen.getResources().getConfiguration().screenWidthDp
                * screen.getResources().getDisplayMetrics().density);
    int screenHeight =
        Math.round(
            screen.getResources().getConfiguration().screenHeightDp
                * screen.getResources().getDisplayMetrics().density);
    screen.measure(
        View.MeasureSpec.makeMeasureSpec(screenWidth, View.MeasureSpec.EXACTLY),
        View.MeasureSpec.makeMeasureSpec(screenHeight, View.MeasureSpec.EXACTLY));
    screen.layout(0, 0, screenWidth, screenHeight);
    AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
    View decor = dialog.getWindow().getDecorView();
    int width = dialog.getWindow().getAttributes().width;
    int height = dialog.getWindow().getAttributes().height;
    decor.measure(
        View.MeasureSpec.makeMeasureSpec(
            width > 0 ? width : screenWidth - 48,
            width > 0 ? View.MeasureSpec.EXACTLY : View.MeasureSpec.AT_MOST),
        View.MeasureSpec.makeMeasureSpec(
            height > 0 ? height : screenHeight - 48,
            height > 0 ? View.MeasureSpec.EXACTLY : View.MeasureSpec.AT_MOST));
    decor.layout(0, 0, decor.getMeasuredWidth(), decor.getMeasuredHeight());
    assertTrue(decor.getMeasuredWidth() <= screenWidth);
    assertTrue(decor.getMeasuredHeight() <= screenHeight);
    Bitmap bitmap = Bitmap.createBitmap(screenWidth, screenHeight, Bitmap.Config.ARGB_8888);
    Canvas canvas = new Canvas(bitmap);
    screen.draw(canvas);
    canvas.drawColor(0x77000000);
    int x =
        (dialog.getWindow().getAttributes().gravity & android.view.Gravity.RIGHT)
                == android.view.Gravity.RIGHT
            ? screenWidth - decor.getWidth() - dialog.getWindow().getAttributes().x
            : (screenWidth - decor.getWidth()) / 2;
    canvas.translate(x, (screenHeight - decor.getHeight()) / 2f);
    decor.draw(canvas);
    File dir = new File("build/qa/reading-ui");
    dir.mkdirs();
    try (FileOutputStream out = new FileOutputStream(new File(dir, name))) {
      bitmap.compress(Bitmap.CompressFormat.PNG, 100, out);
    } finally {
      bitmap.recycle();
    }
  }
}
