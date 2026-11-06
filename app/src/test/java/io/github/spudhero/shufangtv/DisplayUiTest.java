package io.github.spudhero.shufangtv;

import static org.junit.Assert.*;

import android.app.*;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.*;
import android.widget.TextView;
import io.github.spudhero.shufangtv.data.BookRecord;
import io.github.spudhero.shufangtv.data.DisplaySettings;
import io.github.spudhero.shufangtv.data.LibraryStore;
import io.github.spudhero.shufangtv.reader.DisplayProfile;
import io.github.spudhero.shufangtv.reader.PagedTextView;
import io.github.spudhero.shufangtv.ui.DisplayPanel;
import java.io.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.*;
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.util.ReflectionHelpers;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 31, qualifiers = "zh-rCN-w960dp-h540dp-land-television-xxxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class DisplayUiTest {
  @Test
  public void libraryAndReaderRenderAt3840WithReadableTypeAndStablePosition() throws Exception {
    LibraryStore library = new LibraryStore(RuntimeEnvironment.getApplication());
    BookRecord book = new BookRecord();
    book.title = "像素与书页（合成示例）";
    book.author = "书房 TV";
    book.format = "txt";
    book.offlineFile = book.id + ".txt";
    java.nio.file.Files.write(
        library.file(book).toPath(),
        ("夜幕降临，书房亮起一盏灯。高清画布增加文字的绘制精度，字号与阅读位置保持一致。" + "每一次翻页，都沿用同一套阅读坐标。\n")
            .repeat(80)
            .getBytes(java.nio.charset.StandardCharsets.UTF_8));
    library.books.add(book);
    library.save();
    try (ActivityController<MainActivity> controller =
        Robolectric.buildActivity(MainActivity.class).setup()) {
      MainActivity activity = controller.get();
      View decor = activity.getWindow().getDecorView();
      ReflectionHelpers.callInstanceMethod(activity, "showLibrary");
      decor.findViewWithTag("book-" + book.id).performClick();
      AlertDialog actions = ShadowAlertDialog.getLatestAlertDialog();
      android.widget.ListView list = actions.getListView();
      list.performItemClick(list.getAdapter().getView(0, null, list), 0, 0);
      java.util.concurrent.ExecutorService executor = ReflectionHelpers.getField(activity, "io");
      for (int i = 0; i < 4; i++) {
        executor.submit(() -> {}).get(10, java.util.concurrent.TimeUnit.SECONDS);
        ShadowLooper.idleMainLooper();
      }
      io.github.spudhero.shufangtv.ui.ReaderScreen reader =
          ReflectionHelpers.getField(activity, "reader");
      assertNotNull(reader);
      PagedTextView page = (PagedTextView) reader.surface();
      assertEquals("系统原生 4K", reader.resolutionStatus());
      assertEquals(3840, decor.getWidth());
      assertEquals(2160, decor.getHeight());
      assertTrue(page.getContentDescription().toString().contains("左右翻页"));
      assertTrue(page.nextOffset() > 100);
      TextView footer = ReflectionHelpers.getField(reader, "progress");
      assertTrue(footer.getHeight() >= footer.getPaint().getFontSpacing());
      File directory = new File("build/display-qa");
      directory.mkdirs();
      Bitmap shot = Bitmap.createBitmap(3840, 2160, Bitmap.Config.ARGB_8888);
      decor.draw(new Canvas(shot));
      try (OutputStream out = new FileOutputStream(new File(directory, "reader-native-4k.png"))) {
        shot.compress(Bitmap.CompressFormat.PNG, 100, out);
      }
      shot.recycle();
      int position = page.offset();
      activity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER));
      assertEquals(position, page.offset());
      assertTrue(ShadowAlertDialog.getLatestAlertDialog().isShowing());
    }
  }

  @Test
  public void native4kPanelKeepsDpSizingSelectionAndExit() throws Exception {
    panel(4, "4k");
  }

  @Test
  @Config(qualifiers = "zh-rCN-w960dp-h540dp-land-television-xhdpi")
  public void fullHdHasTheSameLogicalGeometry() throws Exception {
    panel(2, "1080p");
  }

  @Test
  @Config(qualifiers = "zh-rCN-w640dp-h360dp-land-television-xhdpi")
  public void smallTvScrollsToDetailsAndRetainsAnExit() throws Exception {
    panel(2, "720p");
  }

  private void panel(int density, String name) throws Exception {
    try (ActivityController<Activity> controller =
        Robolectric.buildActivity(Activity.class).setup()) {
      Activity activity = controller.get();
      activity.setTheme(R.style.AppTheme);
      SharedPreferences prefs = activity.getSharedPreferences("display-qa", 0);
      prefs.edit().clear().commit();
      int[] changes = {0};
      AlertDialog dialog =
          DisplayPanel.show(
              activity,
              prefs,
              new AlertDialog.Builder(activity),
              null,
              null,
              () -> changes[0]++,
              null);
      ShadowLooper.idleMainLooper();
      View decor = dialog.getWindow().getDecorView();
      TextView auto = decor.findViewWithTag("display-mode-auto");
      TextView compatible = decor.findViewWithTag("display-mode-compatible");
      assertTrue(auto.isSelected());
      assertTrue(auto.hasFocus());
      assertEquals(16 * density, auto.getTextSize(), .01);
      auto.requestFocus();
      compatible.requestFocus();
      compatible.performClick();
      assertFalse(DisplaySettings.automatic(prefs));
      assertEquals(1, changes[0]);
      assertTrue(decor.findViewWithTag("display-mode-compatible").isSelected());
      assertTrue(decor.findViewWithTag("display-mode-compatible").hasFocus());
      decor.findViewWithTag("display-details").performClick();
      TextView details = decor.findViewWithTag("display-device-info");
      assertEquals(View.VISIBLE, details.getVisibility());
      assertTrue(details.getText().toString().contains("打开书籍后确认"));
      assertFalse(
          ((TextView) decor.findViewWithTag("display-effect"))
              .getText()
              .toString()
              .contains("已启用"));
      View done = decor.findViewWithTag("display-done");
      done.requestFocus();
      ShadowLooper.idleMainLooper();
      assertTrue(done.hasFocus());
      assertTrue(done.getHeight() >= 44 * density);
      assertTrue(done.getBottom() <= ((View) done.getParent()).getHeight());
      DisplayProfile profile = new DisplayProfile(activity);
      if (name.equals("4k")) assertEquals(3840, profile.uiWidth);
      // Public evidence contains synthetic panel state only, never user books or device
      // credentials.
      File directory = new File(System.getProperty("shufang.qaOutput", "build/display-qa"));
      directory.mkdirs();
      Bitmap shot =
          Bitmap.createBitmap(decor.getWidth(), decor.getHeight(), Bitmap.Config.ARGB_8888);
      decor.draw(new Canvas(shot));
      try (OutputStream out =
          new FileOutputStream(new File(directory, "display-" + name + ".png"))) {
        shot.compress(Bitmap.CompressFormat.PNG, 100, out);
      }
      shot.recycle();
      done.performClick();
      assertFalse(dialog.isShowing());
    }
  }
}
