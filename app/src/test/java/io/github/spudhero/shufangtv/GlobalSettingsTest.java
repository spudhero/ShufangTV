package io.github.spudhero.shufangtv;

import static org.junit.Assert.*;

import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
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
import org.robolectric.Shadows;
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

  @Test
  public void longSourceFormUsesDarkBoundedWindowWithoutLosingValidation() throws Exception {
    try (ActivityController<MainActivity> controller =
        Robolectric.buildActivity(MainActivity.class).setup()) {
      MainActivity activity = controller.get();
      View decor = activity.getWindow().getDecorView();
      findText(decor, "添加家庭书库").performClick();
      ShadowLooper.idleMainLooper();
      AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
      assertTrue(dialog.getWindow().getDecorView().getBackground() instanceof GradientDrawable);
      int maxHeight = Math.round(460 * activity.getResources().getDisplayMetrics().density);
      assertTrue(dialog.getWindow().getAttributes().height > 0);
      assertTrue(dialog.getWindow().getAttributes().height <= maxHeight);
      dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
      assertTrue("Invalid source must keep its form open", dialog.isShowing());
      assertNotNull(findError(dialog.getWindow().getDecorView()));
    }
  }

  @Test
  public void tvDocumentStubOffersRecoveryAndPreservesSavedNotes() throws Exception {
    try (ActivityController<MainActivity> controller =
        Robolectric.buildActivity(MainActivity.class).setup()) {
      MainActivity activity = controller.get();
      ResolveInfo stub = new ResolveInfo();
      stub.activityInfo = new ActivityInfo();
      stub.activityInfo.packageName = "com.google.android.tv.frameworkpackagestubs";
      stub.activityInfo.name = stub.activityInfo.packageName + ".Stubs$DocumentsStub";
      Intent open =
          new Intent(Intent.ACTION_OPEN_DOCUMENT)
              .setType("*/*")
              .addCategory(Intent.CATEGORY_OPENABLE);
      Intent create =
          new Intent(Intent.ACTION_CREATE_DOCUMENT)
              .setType("text/plain")
              .addCategory(Intent.CATEGORY_OPENABLE);
      Shadows.shadowOf(activity.getPackageManager()).addResolveInfoForIntent(open, stub);
      Shadows.shadowOf(activity.getPackageManager()).addResolveInfoForIntent(create, stub);
      LibraryStore library = ReflectionHelpers.getField(activity, "library");
      BookRecord book = new BookRecord();
      book.title = "电视收藏";
      library.books.add(book);
      LibraryStore.Mark mark = new LibraryStore.Mark();
      mark.bookId = book.id;
      mark.excerpt = "系统文件界面缺失时也不能丢失收藏";
      mark.end = mark.excerpt.length();
      mark.note = "保留备注";
      library.marks.add(mark);
      library.save();
      ReflectionHelpers.callInstanceMethod(activity, "chooseLocal");
      ShadowLooper.idleMainLooper();
      AlertDialog explanation = ShadowAlertDialog.getLatestAlertDialog();
      assertTrue(contains(explanation.getWindow().getDecorView(), "网络书库"));
      explanation.dismiss();
      ReflectionHelpers.callInstanceMethod(
          activity, "exportNotes", ReflectionHelpers.ClassParameter.from(boolean.class, false));
      ShadowLooper.idleMainLooper();
      explanation = ShadowAlertDialog.getLatestAlertDialog();
      assertTrue(contains(explanation.getWindow().getDecorView(), "收藏仍保存在书房 TV"));
      assertNull(Shadows.shadowOf(activity).getNextStartedActivityForResult());
      assertFalse(new File(activity.getCacheDir(), "pending-notes-export.txt").exists());
      LibraryStore reloaded = new LibraryStore(activity);
      assertEquals(1, reloaded.marks.size());
      assertEquals("保留备注", reloaded.marks.get(0).note);
    }
  }

  private static TextView findText(View view, String text) {
    if (view instanceof TextView && ((TextView) view).getText().toString().equals(text))
      return (TextView) view;
    if (view instanceof ViewGroup) {
      ViewGroup group = (ViewGroup) view;
      for (int i = 0; i < group.getChildCount(); i++) {
        TextView found = findText(group.getChildAt(i), text);
        if (found != null) return found;
      }
    }
    return null;
  }

  private static TextView findError(View view) {
    if (view instanceof TextView && ((TextView) view).getError() != null) return (TextView) view;
    if (view instanceof ViewGroup) {
      ViewGroup group = (ViewGroup) view;
      for (int i = 0; i < group.getChildCount(); i++) {
        TextView found = findError(group.getChildAt(i));
        if (found != null) return found;
      }
    }
    return null;
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
