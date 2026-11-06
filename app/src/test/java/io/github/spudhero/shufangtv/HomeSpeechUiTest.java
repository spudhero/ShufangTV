package io.github.spudhero.shufangtv;

import static org.junit.Assert.*;

import android.app.AlertDialog;
import android.content.Intent;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.speech.tts.TextToSpeech;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import io.github.spudhero.shufangtv.data.BookRecord;
import io.github.spudhero.shufangtv.data.LibraryStore;
import io.github.spudhero.shufangtv.ui.SpeechSetup;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.shadows.ShadowTextToSpeech;
import org.robolectric.util.ReflectionHelpers;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 31, qualifiers = "zh-rCN-w960dp-h540dp-land-television-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class HomeSpeechUiTest {
  @Test
  public void longHomeTitleFitsWithoutCoveringAuthorOrResumeAt1080p() throws Exception {
    assertHomeTitle("home-title-1080p.png");
  }

  @Test
  @Config(qualifiers = "zh-rCN-w640dp-h360dp-land-television-xhdpi")
  public void longHomeTitleFitsWithoutCoveringAuthorOrResumeAt720p() throws Exception {
    assertHomeTitle("home-title-720p.png");
  }

  private void assertHomeTitle(String screenshot) throws Exception {
    LibraryStore library = new LibraryStore(RuntimeEnvironment.getApplication());
    BookRecord book = new BookRecord();
    book.title = "合成长书名（全本全注全译，附完整目录、出版说明及多卷合集介绍）".repeat(8);
    book.author = "合成作者";
    book.lastRead = 1;
    library.books.add(book);
    library.save();
    try (ActivityController<MainActivity> controller =
        Robolectric.buildActivity(MainActivity.class).setup()) {
      View decor = controller.get().getWindow().getDecorView();
      ShadowLooper.idleMainLooper();
      ReadingUiTest.render(decor, screenshot);
      TextView title = text(decor, book.title), author = text(decor, book.author);
      assertNotNull(title);
      assertEquals(2, title.getLayout().getLineCount());
      assertTrue(title.getLayout().getEllipsisCount(1) > 0);
      assertTrue(title.getLayout().getHeight() <= title.getHeight());
      assertEquals(book.title, title.getContentDescription());
      int[] titleAt = new int[2], authorAt = new int[2], resumeAt = new int[2];
      title.getLocationOnScreen(titleAt);
      author.getLocationOnScreen(authorAt);
      text(decor, "继续阅读").getLocationOnScreen(resumeAt);
      assertTrue(titleAt[1] + title.getHeight() <= authorAt[1]);
      assertTrue(authorAt[1] + author.getHeight() <= resumeAt[1]);
      text(decor, "书名详情").requestFocus();
      text(decor, "书名详情").performClick();
      AlertDialog detail = ShadowAlertDialog.getLatestAlertDialog();
      assertNotNull(text(detail.getWindow().getDecorView(), book.title));
      ReadingUiTest.renderDialog(decor, screenshot.replace(".png", "-detail.png"));
      detail.dismiss();
      ShadowLooper.idleMainLooper();
      assertTrue(text(decor, "书名详情").hasFocus());
      assertEquals(book.title, library.books.get(0).title);
    }
  }

  @Test
  public void settingsRefreshEngineDetectionWithoutPretendingChineseIsReady() throws Exception {
    assertSpeechSettings("1080p");
  }

  @Test
  @Config(qualifiers = "zh-rCN-w640dp-h360dp-land-television-xhdpi")
  public void speechStatusAndSettingsStayReachableAt720p() throws Exception {
    assertSpeechSettings("720p");
  }

  private void assertSpeechSettings(String viewport) throws Exception {
    try (ActivityController<MainActivity> controller =
        Robolectric.buildActivity(MainActivity.class).setup()) {
      MainActivity activity = controller.get();
      View decor = activity.getWindow().getDecorView();
      text(decor, "设置").performClick();
      assertFalse(SpeechSetup.hasEngine(activity));
      assertNotNull(text(decor, "离线中文需下载语音包"));
      text(decor, "管理朗读语音").requestFocus();
      text(decor, "管理朗读语音").performClick();
      AlertDialog panel = ShadowAlertDialog.getLatestAlertDialog();
      View ui = panel.getWindow().getDecorView();
      ReadingUiTest.renderDialog(decor, "speech-offline-missing-" + viewport + ".png");
      assertNotNull(text(ui, "离线中文"));
      assertNotNull(text(ui, "下载语音包"));
      assertFullyVisible(text(ui, "下载语音包"));
      assertNull(ShadowTextToSpeech.getLastTextToSpeechInstance());
      text(ui, "系统语音").performClick();
      assertNotNull(text(ui, "未检测到系统语音引擎"));
      ResolveInfo engine = new ResolveInfo();
      engine.serviceInfo = new ServiceInfo();
      engine.serviceInfo.packageName = "test.speech";
      engine.serviceInfo.name = "test.speech.Engine";
      engine.serviceInfo.exported = true;
      Shadows.shadowOf(activity.getPackageManager())
          .addResolveInfoForIntent(
              new Intent(TextToSpeech.Engine.INTENT_ACTION_TTS_SERVICE), engine);
      controller.pause().resume();
      assertTrue(SpeechSetup.hasEngine(activity));
      assertNotNull(text(ui, "已检测到系统引擎，开启时检查中文支持"));
      assertNull(ShadowTextToSpeech.getLastTextToSpeechInstance());
      ShadowLooper.idleMainLooper();
      ReadingUiTest.renderDialog(decor, "speech-system-detected-" + viewport + ".png");
      text(ui, "网络语音").performClick();
      ReadingUiTest.renderDialog(decor, "speech-network-empty-" + viewport + ".png");
      assertNotNull(text(ui, "网络语音尚未配置"));
      text(ui, "保存网络配置").performClick();
      assertTrue(ui.findViewWithTag("speech-azure-key").hasFocus());
      assertNull(ShadowTextToSpeech.getLastTextToSpeechInstance());
      panel.dismiss();
      ShadowLooper.idleMainLooper();
      assertNotNull(text(activity.getWindow().getDecorView(), "离线中文需下载语音包"));
    }
  }

  @Test
  public void readerExplainsMissingEngineBeforeCreatingPlaybackControls() throws Exception {
    try (ActivityController<MainActivity> controller =
        Robolectric.buildActivity(MainActivity.class).setup()) {
      MainActivity activity = controller.get();
      View decor = activity.getWindow().getDecorView();
      text(decor, "先读一篇使用指南").performClick();
      ExecutorService io = ReflectionHelpers.getField(activity, "io");
      for (int i = 0; i < 4; i++) {
        io.submit(() -> {}).get(10, TimeUnit.SECONDS);
        ShadowLooper.idleMainLooper();
      }
      text(decor, "阅读菜单").performClick();
      AlertDialog menu = ShadowAlertDialog.getLatestAlertDialog();
      assertNotNull(text(menu.getWindow().getDecorView(), "离线中文需下载语音包"));
      ReadingUiTest.renderDialog(decor, "reader-speech-missing.png");
      text(menu.getWindow().getDecorView(), "朗读正文").performClick();
      ShadowLooper.idleMainLooper();
      AlertDialog setup = ShadowAlertDialog.getLatestAlertDialog();
      assertNotSame(menu, setup);
      assertNotNull(text(setup.getWindow().getDecorView(), "准备朗读语音"));
      assertEquals("管理朗读语音", setup.getButton(AlertDialog.BUTTON_POSITIVE).getText());
      assertNull(ShadowTextToSpeech.getLastTextToSpeechInstance());
      assertNull(text(decor, "准备语音…"));
    }
  }

  private static TextView text(View view, String value) {
    if (view.getVisibility() != View.VISIBLE) return null;
    if (view instanceof TextView) {
      String label = ((TextView) view).getText().toString();
      if (value.equals(label) || label.startsWith(value + "\n") || label.endsWith("\n" + value))
        return (TextView) view;
    }
    if (view instanceof ViewGroup)
      for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
        TextView found = text(((ViewGroup) view).getChildAt(i), value);
        if (found != null) return found;
      }
    return null;
  }

  private static void assertFullyVisible(View view) {
    android.graphics.Rect visible = new android.graphics.Rect();
    assertTrue(view.getGlobalVisibleRect(visible));
    assertEquals(view.getHeight(), visible.height());
    assertEquals(view.getWidth(), visible.width());
  }
}
