package io.github.spudhero.shufangtv.data;

import static org.junit.Assert.*;

import android.app.AlertDialog;
import android.graphics.Bitmap;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
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
      CloudLibrary clouds = new CloudLibrary(accounts, Collections.singletonList(provider));
      SecretKeySpec key = new SecretKeySpec(new byte[32], "AES");
      SourceStore sources = new SourceStore(activity, key);
      ReflectionHelpers.setField(activity, "clouds", clouds);
      ReflectionHelpers.setField(activity, "network", new NetworkLibrary(clouds));
      ReflectionHelpers.setField(activity, "sources", sources);
      ViewGroup decor = (ViewGroup) activity.getWindow().getDecorView();
      decor.findViewWithTag("nav-sources").performClick();
      decor.findViewWithTag("cloud-login-aliyun").performClick();
      settle(activity);
      assertTrue(ShadowAlertDialog.getLatestAlertDialog().isShowing());
      render(
          ShadowAlertDialog.getLatestAlertDialog().getWindow().getDecorView(),
          "cloud-login.png",
          1400,
          720);
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
      decor.findViewWithTag("cloud-folder-drive:nested").performClick();
      settle(activity);
      decor.findViewWithTag("cloud-folder-save").performClick();
      settle(activity);
      SourceStore restored = new SourceStore(activity, key);
      assertEquals(1, restored.sources.size());
      assertEquals("drive:nested", restored.sources.get(0).root);
      assertEquals("aliyun:fixture", restored.sources.get(0).accountId);
      assertEquals(0, provider.resolves.get());
    }
  }

  @Test
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
      AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
      dialog.cancel();
      Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMinutes(6));
      settle(activity);
      assertEquals(0, provider.polls.get());
      assertTrue(accounts.all().isEmpty());
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

  private static final class FakeProvider implements CloudProvider {
    final AtomicInteger polls = new AtomicInteger(), resolves = new AtomicInteger();
    boolean failNextList;
    final CloudAccount account =
        new CloudAccount("aliyun", "aliyun:fixture", "测试网盘", new JSONObject());

    public String id() {
      return "aliyun";
    }

    public String label() {
      return "阿里云盘";
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
      return new ListPage(
          folder.equals("drive:root")
              ? Collections.singletonList(new Entry("drive:nested", folder, "我的书籍", true, 0))
              : Collections.emptyList(),
          "");
    }

    public DownloadLink resolve(CloudAccount account, String file) {
      resolves.incrementAndGet();
      throw new AssertionError("Choosing a source must not start reading or downloading");
    }
  }
}
