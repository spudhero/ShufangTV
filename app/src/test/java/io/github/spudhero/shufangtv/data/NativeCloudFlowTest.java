package io.github.spudhero.shufangtv.data;

import static org.junit.Assert.*;

import android.app.AlertDialog;
import android.app.Dialog;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import com.google.zxing.*;
import com.google.zxing.common.HybridBinarizer;
import io.github.spudhero.shufangtv.MainActivity;
import io.github.spudhero.shufangtv.cloud.*;
import io.github.spudhero.shufangtv.cloud.CloudLibraryFlowTest.MemoryAccounts;
import io.github.spudhero.shufangtv.net.NetworkLibrary;
import io.github.spudhero.shufangtv.ui.CloudLoginDialog;
import java.time.Duration;
import java.util.Collections;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import javax.crypto.spec.SecretKeySpec;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowAlertDialog;
import org.robolectric.shadows.ShadowDialog;
import org.robolectric.shadows.ShadowLooper;
import org.robolectric.util.ReflectionHelpers;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 31, qualifiers = "zh-rCN-w960dp-h540dp-land-television-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class NativeCloudFlowTest {
  @Test
  public void scanChooseFolderAndBackNavigationSaveAnEncryptedNativeSource() throws Exception {
    try (ActivityController<MainActivity> controller =
        Robolectric.buildActivity(MainActivity.class).setup()) {
      MainActivity activity = controller.get();
      FakeProvider provider = new FakeProvider();
      MemoryAccounts accounts = new MemoryAccounts();
      CloudLibrary clouds =
          new CloudLibrary(
              accounts,
              java.util.Arrays.asList(
                  provider,
                  new FakeProvider("baidu", "百度网盘"),
                  new FakeProvider("quark", "夸克网盘"),
                  new FakeProvider("uc", "UC 网盘")));
      SecretKeySpec key = new SecretKeySpec(new byte[32], "AES");
      SourceStore sources = new SourceStore(activity, key);
      ReflectionHelpers.setField(activity, "clouds", clouds);
      ReflectionHelpers.setField(activity, "network", new NetworkLibrary(clouds));
      ReflectionHelpers.setField(activity, "sources", sources);
      ViewGroup decor = (ViewGroup) activity.getWindow().getDecorView();
      decor.findViewWithTag("nav-sources").performClick();
      decor.findViewWithTag("cloud-login-aliyun").performClick();
      settle(activity);
      Dialog login = ShadowDialog.getLatestDialog();
      assertTrue(login.isShowing());
      renderDialog(login, "cloud-login.png", 1920, 1080);
      assertNotNull(login.findViewById(android.R.id.content).findViewWithTag("cloud-qr-refresh"));
      Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2));
      settle(activity);
      assertEquals(1, accounts.all().size());
      render(decor, "cloud-folder.png", 1920, 1080);
      View folder = decor.findViewWithTag("cloud-folder-drive:nested");
      assertNotNull(folder);
      provider.failNextList = true;
      folder.performClick();
      settle(activity);
      assertTrue(
          ((java.util.Deque<?>) ReflectionHelpers.getField(activity, "cloudParents")).isEmpty());
      AlertDialog error = ShadowAlertDialog.getLatestAlertDialog();
      if (error != null && error.isShowing()) error.dismiss();
      decor.findViewWithTag("cloud-folder-drive:nested").performClick();
      settle(activity);
      activity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK));
      settle(activity);
      assertNotNull(decor.findViewWithTag("cloud-folder-drive:nested"));
      assertTrue(decor.findViewWithTag("cloud-folder-drive:nested").hasFocus());
      decor.findViewWithTag("cloud-folder-drive:nested").performClick();
      settle(activity);
      decor.findViewWithTag("cloud-folder-save").performClick();
      settle(activity);
      SourceStore restored = new SourceStore(activity, key);
      assertEquals(1, restored.sources.size());
      assertEquals("drive:nested", restored.sources.get(0).root);
      assertEquals("aliyun:fixture", restored.sources.get(0).accountId);
      assertEquals(0, provider.resolves.get());
      assertNotNull(
          "saving a folder must show its books immediately",
          decor.findViewWithTag("remote-file:pdf"));
      decor.findViewWithTag("nav-sources").performClick();
      render(decor, "cloud-sources.png", 1920, 1080);
      decor.findViewWithTag("source-browse-" + restored.sources.get(0).id).performClick();
      settle(activity);
      render(decor, "cloud-browser.png", 1920, 1080);
      View bookItem = decor.findViewWithTag("remote-file:sample");
      bookItem.requestFocus();
      bookItem.performClick();
      Dialog actions = ShadowDialog.getLatestDialog();
      renderDialog(actions, "cloud-actions.png", 1920, 1080);
      View online = actions.getWindow().getDecorView().findViewWithTag("remote-open-online");
      View offline = actions.getWindow().getDecorView().findViewWithTag("remote-save-offline");
      assertTrue(online.hasFocus());
      assertSame(offline, online.focusSearch(View.FOCUS_DOWN));
      actions.cancel();
      assertEquals(
          "opening an action panel must not fetch book content", 0, provider.resolves.get());
      decor.findViewWithTag("remote-refresh").performClick();
      settle(activity);
      assertTrue(
          "reloading the directory must restore the selected book",
          decor.findViewWithTag("remote-file:sample").hasFocus());
    }
  }

  @Test
  @Config(qualifiers = "zh-rCN-w640dp-h360dp-land-television-xhdpi")
  public void bookOnlyFolderShowsReadableFilesAndReusesItsSourceWithoutFetchingContent()
      throws Exception {
    try (ActivityController<MainActivity> controller =
        Robolectric.buildActivity(MainActivity.class).setup()) {
      MainActivity activity = controller.get();
      FakeProvider provider = new FakeProvider();
      provider.booksOnly = true;
      CloudLibrary clouds =
          new CloudLibrary(new MemoryAccounts(), Collections.singletonList(provider));
      clouds.save(provider.account);
      SourceStore sources = new SourceStore(activity, new SecretKeySpec(new byte[32], "AES"));
      ReflectionHelpers.setField(activity, "clouds", clouds);
      ReflectionHelpers.setField(activity, "network", new NetworkLibrary(clouds));
      ReflectionHelpers.setField(activity, "sources", sources);
      ViewGroup decor = (ViewGroup) activity.getWindow().getDecorView();
      decor.findViewWithTag("nav-sources").performClick();
      decor.findViewWithTag("cloud-folder-add").performClick();
      settle(activity);
      assertNotNull(decor.findViewWithTag("cloud-book-file:sample"));
      assertNotNull(decor.findViewWithTag("cloud-book-file:epub"));
      View pdf = decor.findViewWithTag("cloud-book-file:pdf");
      assertNotNull("a folder containing only books must not look empty", pdf);
      assertNull(decor.findViewWithTag("cloud-book-file:audio"));
      assertTrue(decor.findViewWithTag("cloud-book-file:epub").hasFocus());
      render(decor, "cloud-book-only-720p.png", 1280, 720);
      View save = decor.findViewWithTag("cloud-folder-save");
      Rect bounds = new Rect();
      assertTrue(save.getGlobalVisibleRect(bounds));
      assertEquals(save.getHeight(), bounds.height());
      assertTrue(bounds.bottom <= 720);
      pdf.performClick();
      settle(activity);
      assertEquals(1, sources.sources.size());
      String sourceId = sources.sources.get(0).id;
      assertEquals("drive:root", sources.sources.get(0).root);
      Dialog actions = ShadowDialog.getLatestDialog();
      assertTrue(actions.isShowing());
      assertNotNull(actions.getWindow().getDecorView().findViewWithTag("remote-open-online"));
      assertTrue(decor.findViewWithTag("remote-file:pdf").hasFocus());
      actions.cancel();
      decor.findViewWithTag("nav-sources").performClick();
      decor.findViewWithTag("cloud-folder-add").performClick();
      settle(activity);
      decor.findViewWithTag("cloud-book-file:pdf").performClick();
      settle(activity);
      assertEquals("an existing folder must be reused", 1, sources.sources.size());
      assertEquals(sourceId, sources.sources.get(0).id);
      assertEquals(
          "picker and action panel must not download book content", 0, provider.resolves.get());
      assertEquals(
          "the picker must request files, not just child folders",
          0,
          provider.directoryRequests.get());
    }
  }

  @Test
  @Config(qualifiers = "zh-rCN-w640dp-h360dp-land-television-xhdpi")
  public void dismissingQrStopsPollingAndNeverSavesAnUnconfirmedAccount() throws Exception {
    try (ActivityController<MainActivity> controller =
        Robolectric.buildActivity(MainActivity.class).setup()) {
      MainActivity activity = controller.get();
      FakeProvider provider = new FakeProvider();
      MemoryAccounts accounts = new MemoryAccounts();
      ReflectionHelpers.setField(
          activity, "clouds", new CloudLibrary(accounts, Collections.singletonList(provider)));
      ViewGroup decor = (ViewGroup) activity.getWindow().getDecorView();
      decor.findViewWithTag("nav-sources").performClick();
      decor.findViewWithTag("cloud-login-aliyun").performClick();
      settle(activity);
      Dialog dialog = ShadowDialog.getLatestDialog();
      View content = dialog.getWindow().getDecorView();
      renderDialog(dialog, "cloud-login-720p.png", 1280, 720);
      View refresh = content.findViewWithTag("cloud-qr-refresh");
      Rect visible = new Rect();
      assertTrue(refresh.getGlobalVisibleRect(visible));
      assertEquals(refresh.getHeight(), visible.height());
      assertTrue(content.getHeight() <= 720);
      dialog.cancel();
      Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMinutes(6));
      settle(activity);
      assertEquals(0, provider.polls.get());
      assertTrue(accounts.all().isEmpty());
    }
  }

  @Test
  @Config(qualifiers = "zh-rCN-w640dp-h360dp-land-television-xhdpi")
  public void longFolderListKeepsSelectionVisibleAt720pAndShowsNamesInsteadOfIds()
      throws Exception {
    try (ActivityController<MainActivity> controller =
        Robolectric.buildActivity(MainActivity.class).setup()) {
      MainActivity activity = controller.get();
      FakeProvider provider = new FakeProvider();
      provider.longList = true;
      MemoryAccounts accounts = new MemoryAccounts();
      CloudLibrary cloudLibrary = new CloudLibrary(accounts, Collections.singletonList(provider));
      cloudLibrary.save(provider.account);
      ReflectionHelpers.setField(activity, "clouds", cloudLibrary);
      ViewGroup decor = (ViewGroup) activity.getWindow().getDecorView();
      decor.findViewWithTag("nav-sources").performClick();
      decor.findViewWithTag("cloud-login-aliyun").performClick();
      Dialog accountMenu = ShadowDialog.getLatestDialog();
      renderDialog(accountMenu, "cloud-account-720p.png", 1280, 720);
      assertNotNull(
          accountMenu
              .getWindow()
              .getDecorView()
              .findViewWithTag("cloud-account-choose-" + provider.account.id));
      accountMenu.cancel();
      decor.findViewWithTag("cloud-folder-add").performClick();
      settle(activity);
      render(decor, "cloud-folder-720p.png", 1280, 720);
      View save = decor.findViewWithTag("cloud-folder-save");
      Rect bounds = new Rect();
      assertTrue(save.getGlobalVisibleRect(bounds));
      assertEquals(save.getHeight(), bounds.height());
      assertTrue(bounds.bottom <= 720);
      View folder = decor.findViewWithTag("cloud-folder-drive:nested");
      folder.performClick();
      settle(activity);
      TextView path = decor.findViewWithTag("cloud-folder-breadcrumb");
      assertTrue(path.getText().toString().contains("我的书籍"));
      assertFalse(path.getText().toString().contains("drive:"));
    }
  }

  @Test
  public void displayedQrDecodesToTheAuthorizationPayload() throws Exception {
    String payload = "https://example.com/authorize?ticket=synthetic-fixture";
    Bitmap bitmap = CloudLoginDialog.qr(payload);
    int[] pixels = new int[bitmap.getWidth() * bitmap.getHeight()];
    bitmap.getPixels(pixels, 0, bitmap.getWidth(), 0, 0, bitmap.getWidth(), bitmap.getHeight());
    BinaryBitmap binary =
        new BinaryBitmap(
            new HybridBinarizer(
                new RGBLuminanceSource(bitmap.getWidth(), bitmap.getHeight(), pixels)));
    assertEquals(payload, new MultiFormatReader().decode(binary).getText());
    bitmap.recycle();
  }

  private static void settle(MainActivity activity) throws Exception {
    ExecutorService io = ReflectionHelpers.getField(activity, "io");
    for (int i = 0; i < 6; i++) {
      io.submit(() -> {}).get(10, TimeUnit.SECONDS);
      ShadowLooper.idleMainLooper();
    }
  }

  private static void render(View view, String name, int width, int height) throws Exception {
    view.measure(
        View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
        View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
    view.layout(0, 0, width, height);
    Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
    view.draw(new android.graphics.Canvas(bitmap));
    java.io.File directory = new java.io.File("build/qa/native-renders");
    directory.mkdirs();
    try (java.io.FileOutputStream out =
        new java.io.FileOutputStream(new java.io.File(directory, name))) {
      assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, out));
    } finally {
      bitmap.recycle();
    }
  }

  private static void renderDialog(Dialog dialog, String name, int width, int height)
      throws Exception {
    View view = dialog.getWindow().getDecorView();
    int preferred = dialog.getWindow().getAttributes().width;
    view.measure(
        View.MeasureSpec.makeMeasureSpec(Math.min(width, preferred), View.MeasureSpec.EXACTLY),
        View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.UNSPECIFIED));
    int naturalHeight = view.getMeasuredHeight();
    assertTrue(
        "dialog must fit the TV before clipping", naturalHeight > 0 && naturalHeight <= height);
    render(view, name, view.getMeasuredWidth(), naturalHeight);
  }

  private static final class FakeProvider implements CloudProvider {
    final AtomicInteger polls = new AtomicInteger(),
        resolves = new AtomicInteger(),
        directoryRequests = new AtomicInteger();
    boolean failNextList, longList, booksOnly;
    final CloudAccount account;
    final String providerId, providerLabel;

    FakeProvider() {
      this("aliyun", "阿里云盘");
    }

    FakeProvider(String id, String label) {
      providerId = id;
      providerLabel = label;
      account = new CloudAccount(id, id + ":fixture", "测试网盘", new JSONObject());
    }

    public String id() {
      return providerId;
    }

    public String label() {
      return providerLabel;
    }

    public String rootId(CloudAccount account) {
      return "drive:root";
    }

    public LoginChallenge beginLogin() {
      return new LoginChallenge(
          id(),
          "https://example.com/login?ticket=fixture",
          System.currentTimeMillis() + 300000,
          1500,
          account,
          new JSONObject());
    }

    public LoginResult pollLogin(LoginChallenge challenge) {
      polls.incrementAndGet();
      return new LoginResult(Status.AUTHORIZED, account, "已连接");
    }

    public ListPage list(
        CloudAccount account, String folder, String cursor, boolean directoriesOnly)
        throws java.io.IOException {
      if (failNextList) {
        failNextList = false;
        throw new java.io.IOException("测试连接中断");
      }
      if (directoriesOnly) directoryRequests.incrementAndGet();
      java.util.List<Entry> entries = new java.util.ArrayList<>();
      if (folder.equals("drive:root") && !booksOnly) {
        entries.add(new Entry("drive:nested", folder, "我的书籍", true, 0));
        if (longList)
          for (int i = 0; i < 30; i++)
            entries.add(new Entry("drive:item" + i, folder, "中文长目录名称与分类 " + i, true, 0));
      }
      if (!directoriesOnly) {
        entries.add(new Entry("file:sample", folder, "阅读示例：长书名在电视上的清晰呈现.txt", false, 524288));
        entries.add(new Entry("file:epub", folder, "书房阅读指南.epub", false, 2097152));
        entries.add(new Entry("file:pdf", folder, "家庭书库手册.pdf", false, 4194304));
        entries.add(new Entry("file:audio", folder, "配套音频.mp3", false, 1048576));
      }
      return new ListPage(entries, "");
    }

    public DownloadLink resolve(CloudAccount account, String file) {
      resolves.incrementAndGet();
      throw new AssertionError("Choosing a source must not start reading or downloading");
    }
  }
}
