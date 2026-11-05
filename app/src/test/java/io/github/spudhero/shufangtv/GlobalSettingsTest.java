package io.github.spudhero.shufangtv;

import static org.junit.Assert.*;

import android.app.AlertDialog;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import io.github.spudhero.shufangtv.data.BookRecord;
import io.github.spudhero.shufangtv.data.LibraryStore;
import io.github.spudhero.shufangtv.data.ReadingStyle;
import java.io.File;
import java.io.FileOutputStream;
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
public class GlobalSettingsTest {
  @Test
  public void defaultsKeepDpadFocusAndDoNotOverwriteBookOverrides() throws Exception {
    try (ActivityController<MainActivity> controller =
        Robolectric.buildActivity(MainActivity.class).setup()) {
      MainActivity activity = controller.get();
      activity.getSharedPreferences("reading", 0).edit().putInt("font", 38).apply();
      LibraryStore library = ReflectionHelpers.getField(activity, "library");
      BookRecord book = new BookRecord();
      book.readingStyle = new ReadingStyle();
      book.readingStyle.font = 20;
      library.books.add(book);
      library.save();
      View decor = activity.getWindow().getDecorView();
      decor.findViewWithTag("nav-settings").performClick();
      render(decor, "settings-defaults.png", 1920, 1080);
      decor.findViewWithTag("settings-font-more").performClick();
      ShadowLooper.idleMainLooper();
      assertEquals(40, ReadingStyle.defaults(activity.getSharedPreferences("reading", 0)).font);
      assertTrue(decor.findViewWithTag("settings-font-more").hasFocus());
      decor.findViewWithTag("settings-width-0").performClick();
      ShadowLooper.idleMainLooper();
      View narrow = decor.findViewWithTag("settings-width-0");
      assertTrue(narrow.hasFocus());
      assertTrue(narrow.isSelected());
      assertFalse(decor.findViewWithTag("settings-width-2").isSelected());
      assertEquals(0, ReadingStyle.defaults(activity.getSharedPreferences("reading", 0)).width);
      BookRecord reloaded = new LibraryStore(activity).find(book.id);
      assertEquals(20, reloaded.readingStyle.font);
      assertEquals(2, reloaded.readingStyle.width);
      decor.findViewWithTag("settings-about").performClick();
      AlertDialog about = ShadowAlertDialog.getLatestAlertDialog();
      String version =
          activity.getPackageManager().getPackageInfo(activity.getPackageName(), 0).versionName;
      assertTrue(contains(about.getWindow().getDecorView(), "书房 TV " + version));
    }
  }

  @Test
  @Config(qualifiers = "zh-rCN-w640dp-h360dp-land-television-xhdpi")
  public void smallTvKeepsChangedSettingAndFooterActionsVisible() throws Exception {
    try (ActivityController<MainActivity> controller =
        Robolectric.buildActivity(MainActivity.class).setup()) {
      View decor = controller.get().getWindow().getDecorView();
      decor.findViewWithTag("nav-settings").performClick();
      render(decor, "settings-720p.png", 1280, 720);
      decor.findViewWithTag("settings-width-1").performClick();
      render(decor, "settings-width-720p.png", 1280, 720);
      assertFullyVisible(decor.findViewWithTag("settings-width-1"));
      decor.findViewWithTag("settings-help").requestFocus();
      render(decor, "settings-footer-720p.png", 1280, 720);
      assertFullyVisible(decor.findViewWithTag("settings-help"));
      assertFullyVisible(decor.findViewWithTag("settings-about"));
    }
  }

  private static void assertFullyVisible(View view) {
    assertTrue(view.hasFocus() || view.getTag().equals("settings-about"));
    Rect rect = new Rect();
    assertTrue(view.getGlobalVisibleRect(rect));
    assertEquals(view.getHeight(), rect.height());
  }

  private static boolean contains(View view, String text) {
    if (view instanceof TextView && ((TextView) view).getText().toString().contains(text))
      return true;
    if (view instanceof ViewGroup) {
      ViewGroup group = (ViewGroup) view;
      for (int i = 0; i < group.getChildCount(); i++) {
        if (contains(group.getChildAt(i), text)) return true;
      }
    }
    return false;
  }

  private static void render(View view, String name, int width, int height) throws Exception {
    for (int i = 0; i < 3; i++) {
      view.measure(
          View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
          View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
      view.layout(0, 0, width, height);
      ShadowLooper.idleMainLooper();
    }
    Bitmap image = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
    view.draw(new Canvas(image));
    File directory = new File("build/qa/integration-ui");
    directory.mkdirs();
    try (FileOutputStream out = new FileOutputStream(new File(directory, name))) {
      assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, out));
    } finally {
      image.recycle();
    }
  }
}
