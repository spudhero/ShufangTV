package io.github.spudhero.shufangtv;

import static org.junit.Assert.*;

import android.app.AlertDialog;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import io.github.spudhero.shufangtv.data.LibraryStore;
import io.github.spudhero.shufangtv.reader.PagedTextView;
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
public class NativeInterfaceTest {
  @Test
  public void originalGuideSupportsDpadTurnsBookmarksAndResume() throws Exception {
    try (ActivityController<MainActivity> controller =
        Robolectric.buildActivity(MainActivity.class).setup()) {
      MainActivity activity = controller.get();
      ViewGroup decor = (ViewGroup) activity.getWindow().getDecorView();
      findText(decor, "先读一篇使用指南").performClick();
      settle(activity);
      render(decor, "reader.png");
      ShadowLooper.idleMainLooper();
      PagedTextView page = findPage(decor);
      assertNotNull(page);
      assertTrue(page.totalChars() > 400);
      assertEquals(0, page.offset());
      page.requestFocus();
      activity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT));
      int position = page.offset();
      assertTrue("D-pad should advance actual Chinese text", position > 0);
      activity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER));
      AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
      assertTrue(dialog.isShowing());
      dialog.getListView().performItemClick(null, 1, 1);
      LibraryStore saved = new LibraryStore(activity);
      assertEquals(1, saved.marks.size());
      assertEquals(position, saved.marks.get(0).offset);
      activity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK));
      settle(activity);
      render(decor, "home-guide.png");
      findText(decor, "继续阅读").performClick();
      settle(activity);
      render(decor, "reader-resume.png");
      assertEquals(position, findPage(decor).offset());
    }
  }

  @Test
  public void chineseNavigationAndBackReturnToHome() throws Exception {
    try (ActivityController<MainActivity> controller =
        Robolectric.buildActivity(MainActivity.class).setup()) {
      MainActivity activity = controller.get();
      ViewGroup decor = (ViewGroup) activity.getWindow().getDecorView();
      assertNotNull(findText(decor, "添加家庭书库"));
      render(decor, "home-empty.png");
      decor.findViewWithTag("nav-library").performClick();
      assertNotNull(findText(decor, "在线书籍"));
      assertNotNull(findText(decor, "本地 / 离线"));
      render(decor, "library-empty.png");
      decor.findViewWithTag("nav-sources").performClick();
      assertNotNull(decor.findViewWithTag("cloud-login-aliyun"));
      assertNotNull(decor.findViewWithTag("cloud-login-baidu"));
      assertNotNull(decor.findViewWithTag("cloud-login-quark"));
      assertNotNull(decor.findViewWithTag("cloud-login-uc"));
      render(decor, "sources.png");
      decor.findViewWithTag("nav-bookmarks").performClick();
      assertNotNull(findText(decor, "留住让你停下来的文字"));
      decor.findViewWithTag("nav-settings").performClick();
      assertNotNull(findText(decor, "暗色书房"));
      render(decor, "settings.png");
      activity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK));
      activity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK));
      assertNotNull(findText(decor, "添加家庭书库"));
      assertFalse(activity.isFinishing());
    }
  }

  private static TextView findText(View v, String text) {
    if (v instanceof TextView && ((TextView) v).getText().toString().equals(text))
      return (TextView) v;
    if (v instanceof ViewGroup)
      for (int i = 0; i < ((ViewGroup) v).getChildCount(); i++) {
        TextView found = findText(((ViewGroup) v).getChildAt(i), text);
        if (found != null) return found;
      }
    return null;
  }

  private static PagedTextView findPage(View v) {
    if (v instanceof PagedTextView) return (PagedTextView) v;
    if (v instanceof ViewGroup)
      for (int i = 0; i < ((ViewGroup) v).getChildCount(); i++) {
        PagedTextView result = findPage(((ViewGroup) v).getChildAt(i));
        if (result != null) return result;
      }
    return null;
  }

  private static void settle(MainActivity activity) throws Exception {
    ExecutorService io = ReflectionHelpers.getField(activity, "io");
    for (int i = 0; i < 5; i++) {
      io.submit(() -> {}).get(10, TimeUnit.SECONDS);
      ShadowLooper.idleMainLooper();
    }
  }

  private static void render(View view, String name) throws Exception {
    view.measure(
        View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY),
        View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY));
    view.layout(0, 0, 1920, 1080);
    Bitmap image = Bitmap.createBitmap(1920, 1080, Bitmap.Config.ARGB_8888);
    view.draw(new Canvas(image));
    File directory = new File("build/qa/native-renders");
    directory.mkdirs();
    try (FileOutputStream out = new FileOutputStream(new File(directory, name))) {
      assertTrue(image.compress(Bitmap.CompressFormat.PNG, 100, out));
    } finally {
      image.recycle();
    }
  }
}
