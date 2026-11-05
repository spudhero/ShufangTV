package io.github.spudhero.shufangtv.net;

import static org.junit.Assert.*;

import android.app.AlertDialog;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import io.github.spudhero.shufangtv.MainActivity;
import io.github.spudhero.shufangtv.data.BookRecord;
import io.github.spudhero.shufangtv.data.LibraryStore;
import io.github.spudhero.shufangtv.data.SourceStore;
import io.github.spudhero.shufangtv.reader.PagedTextView;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
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
public class OnlineOfflineFlowTest {
  @Test
  public void onlineDoesNotSaveBookAndExplicitOfflineCopyWorksWithoutServer() throws Exception {
    runOnlineOfflineFlow(false);
  }

  @Test
  public void sourceActionPanelKeepsOnlineAndOfflineOperationsSeparate() throws Exception {
    runOnlineOfflineFlow(true);
  }

  private void runOnlineOfflineFlow(boolean sourcePanel) throws Exception {
    byte[] bytes =
        ("在线阅读验证\n\n只在需要时，取一页文字。\n\n在线模式通过家庭书库按需读取；主动保存后，才能在电视上离线打开。\n\n".repeat(7000))
            .getBytes(StandardCharsets.UTF_8);
    AtomicLong transferred = new AtomicLong();
    try (LocalHttpFixture server =
            new LocalHttpFixture(
                request -> {
                  if (request.method.equals("HEAD"))
                    return LocalHttpFixture.Reply.text(200, "")
                        .header("Content-Length", Integer.toString(bytes.length))
                        .header("ETag", "\"fixture-v1\"");
                  String range = request.headers.get("range");
                  if (range == null) {
                    transferred.addAndGet(bytes.length);
                    return new LocalHttpFixture.Reply(200, bytes).header("ETag", "\"fixture-v1\"");
                  }
                  String[] limits = range.substring("bytes=".length()).split("-");
                  int start = Integer.parseInt(limits[0]),
                      end = Math.min(bytes.length - 1, Integer.parseInt(limits[1]));
                  transferred.addAndGet(end - start + 1);
                  return new LocalHttpFixture.Reply(206, Arrays.copyOfRange(bytes, start, end + 1))
                      .header("Content-Range", "bytes " + start + "-" + end + "/" + bytes.length)
                      .header("ETag", "\"fixture-v1\"");
                });
        ActivityController<MainActivity> controller =
            Robolectric.buildActivity(MainActivity.class).setup()) {
      MainActivity activity = controller.get();
      ViewGroup decor = (ViewGroup) activity.getWindow().getDecorView();
      LibraryStore library = ReflectionHelpers.getField(activity, "library");
      SourceStore sources = ReflectionHelpers.getField(activity, "sources");
      // Inject only a test connection; credential encryption is a separate device validation item.
      SourceConfig source = new SourceConfig();
      source.id = "controlled-fixture";
      source.type = SourceConfig.Type.WEBDAV;
      source.host = server.origin();
      source.name = "本机测试服务";
      sources.sources.add(source);
      BookRecord book = new BookRecord();
      book.title = "在线阅读验证";
      book.format = "txt";
      book.sourceId = source.id;
      book.remotePath = "book.txt";
      book.size = bytes.length;
      library.books.add(book);
      library.save();
      if (sourcePanel) sourceAction(activity, decor, source, book, "remote-open-online");
      else {
        decor.findViewWithTag("nav-library").performClick();
        decor.findViewWithTag("book-" + book.id).performClick();
        choose(0);
      }
      settle(activity);
      render(decor, "online-reader.png");
      assertNotNull(findText(decor, "在线阅读"));
      assertNotNull(findPage(decor));
      assertEquals("online must not create an offline book", 0, library.booksDir.list().length);
      assertFalse(book.hasOffline());
      assertTrue(transferred.get() < bytes.length / 4);
      assertTrue(
          server.requests.stream()
              .filter(r -> r.method.equals("GET"))
              .allMatch(r -> r.headers.containsKey("range")));
      findPage(decor).requestFocus();
      activity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT));
      int position = findPage(decor).offset();
      assertTrue(position > 0);
      activity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK));
      settle(activity);
      if (sourcePanel) sourceAction(activity, decor, source, book, "remote-save-offline");
      else {
        decor.findViewWithTag("book-" + book.id).performClick();
        choose(1);
      }
      ShadowAlertDialog.getLatestAlertDialog()
          .getButton(AlertDialog.BUTTON_POSITIVE)
          .performClick();
      settle(activity);
      assertTrue(book.hasOffline());
      assertEquals(bytes.length, library.file(book).length());
      assertEquals(1, library.booksDir.list().length);
      assertTrue(
          server.requests.stream()
              .anyMatch(r -> r.method.equals("GET") && !r.headers.containsKey("range")));
      int requests = server.requests.size();
      server.close();
      ShadowAlertDialog.getLatestAlertDialog()
          .getButton(AlertDialog.BUTTON_POSITIVE)
          .performClick();
      settle(activity);
      render(decor, "offline-reader.png");
      assertNotNull(findText(decor, "离线阅读"));
      assertEquals(position, findPage(decor).offset());
      assertEquals(requests, server.requests.size());
    }
  }

  private static void sourceAction(
      MainActivity activity, ViewGroup decor, SourceConfig source, BookRecord book, String action) {
    ReflectionHelpers.callInstanceMethod(
        activity,
        "showRemote",
        ReflectionHelpers.ClassParameter.from(SourceConfig.class, source),
        ReflectionHelpers.ClassParameter.from(String.class, ""),
        ReflectionHelpers.ClassParameter.from(
            java.util.List.class,
            java.util.Collections.singletonList(
                new RemoteEntry("在线阅读验证.txt", book.remotePath, false, book.size))));
    decor.findViewWithTag("remote-" + book.remotePath).performClick();
    org.robolectric.shadows.ShadowDialog.getLatestDialog()
        .getWindow()
        .getDecorView()
        .findViewWithTag(action)
        .performClick();
  }

  private static void choose(int index) {
    AlertDialog dialog = ShadowAlertDialog.getLatestAlertDialog();
    assertTrue(dialog.isShowing());
    dialog.getListView().performItemClick(null, index, index);
  }

  private static void settle(MainActivity activity) throws Exception {
    ExecutorService io = ReflectionHelpers.getField(activity, "io");
    for (int i = 0; i < 5; i++) {
      io.submit(() -> {}).get(10, TimeUnit.SECONDS);
      ShadowLooper.idleMainLooper();
    }
  }

  private static TextView findText(View view, String text) {
    if (view instanceof TextView && ((TextView) view).getText().toString().equals(text))
      return (TextView) view;
    if (view instanceof ViewGroup)
      for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
        TextView result = findText(((ViewGroup) view).getChildAt(i), text);
        if (result != null) return result;
      }
    return null;
  }

  private static PagedTextView findPage(View view) {
    if (view instanceof PagedTextView) return (PagedTextView) view;
    if (view instanceof ViewGroup)
      for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
        PagedTextView result = findPage(((ViewGroup) view).getChildAt(i));
        if (result != null) return result;
      }
    return null;
  }

  private static void render(View view, String name) throws Exception {
    view.measure(
        View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY),
        View.MeasureSpec.makeMeasureSpec(1080, View.MeasureSpec.EXACTLY));
    view.layout(0, 0, 1920, 1080);
    Bitmap bitmap = Bitmap.createBitmap(1920, 1080, Bitmap.Config.ARGB_8888);
    view.draw(new Canvas(bitmap));
    File directory = new File("build/qa/native-renders");
    directory.mkdirs();
    try (FileOutputStream output = new FileOutputStream(new File(directory, name))) {
      assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output));
    } finally {
      bitmap.recycle();
    }
  }
}
