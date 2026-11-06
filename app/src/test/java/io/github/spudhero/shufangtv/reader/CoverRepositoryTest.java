package io.github.spudhero.shufangtv.reader;

import static org.junit.Assert.*;

import android.app.Activity;
import android.graphics.*;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import io.github.spudhero.shufangtv.MainActivity;
import io.github.spudhero.shufangtv.ReadingUiTest;
import io.github.spudhero.shufangtv.core.*;
import io.github.spudhero.shufangtv.data.*;
import io.github.spudhero.shufangtv.ui.CoverView;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.*;
import org.robolectric.shadows.*;
import org.robolectric.util.ReflectionHelpers;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 31, qualifiers = "w960dp-h540dp-land-television-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class CoverRepositoryTest {
  @Test
  public void actualEpubCoverAppearsOnHomeAndShelfAt1080p() throws Exception {
    assertProductionBinding("1080p");
  }

  @Test
  @Config(qualifiers = "w640dp-h360dp-land-television-xhdpi")
  public void actualEpubCoverAppearsOnHomeAndShelfAt720p() throws Exception {
    assertProductionBinding("720p");
  }

  private void assertProductionBinding(String size) throws Exception {
    android.content.Context context = RuntimeEnvironment.getApplication();
    LibraryStore library = new LibraryStore(context);
    BookRecord book = new BookRecord();
    book.title = "山海之间";
    book.author = "书房测试样书";
    book.lastRead = 1;
    book.offlineFile = book.id + ".epub";
    Bitmap cover = Bitmap.createBitmap(400, 600, Bitmap.Config.ARGB_8888);
    Canvas canvas = new Canvas(cover);
    Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    canvas.drawColor(0xff314d4b);
    paint.setColor(0xffd8b377);
    paint.setStrokeWidth(6);
    canvas.drawLine(50, 80, 350, 80, paint);
    canvas.drawLine(50, 520, 350, 520, paint);
    paint.setTextSize(42);
    canvas.drawText("山海之间", 104, 250, paint);
    paint.setTextSize(22);
    canvas.drawText("书房原创测试样书", 110, 310, paint);
    ByteArrayOutputStream png = new ByteArrayOutputStream();
    cover.compress(Bitmap.CompressFormat.PNG, 100, png);
    try (ZipOutputStream zip = new ZipOutputStream(new FileOutputStream(library.file(book)))) {
      entry(
          zip,
          "META-INF/container.xml",
          "<container><rootfiles><rootfile full-path='book.opf'/></rootfiles></container>"
              .getBytes(StandardCharsets.UTF_8));
      entry(
          zip,
          "book.opf",
          ("<package><metadata><title>山海之间</title><creator>书房测试样书</creator></metadata><manifest><item"
               + " id='cover' href='cover.png' media-type='image/png'"
               + " properties='cover-image'/><item id='body' href='body.xhtml'"
               + " media-type='application/xhtml+xml'/></manifest><spine><itemref"
               + " idref='body'/></spine></package>")
              .getBytes(StandardCharsets.UTF_8));
      entry(zip, "cover.png", png.toByteArray());
      entry(
          zip,
          "body.xhtml",
          "<html><body><p>原创测试正文。</p></body></html>".getBytes(StandardCharsets.UTF_8));
    }
    library.books.add(book);
    library.save();
    try (ActivityController<MainActivity> controller =
        Robolectric.buildActivity(MainActivity.class).setup()) {
      MainActivity activity = controller.get();
      View decor = activity.getWindow().getDecorView();
      CoverRepository repository = ReflectionHelpers.getField(activity, "covers");
      ReadingUiTest.render(decor, "covers-home-" + size + ".png");
      settle(repository, decor);
      // At 720p the recent card starts below the viewport and should remain lazy.
      assertEquals(size.equals("720p") ? 1 : 2, assertImages(decor));
      assertTrue(text(decor, "继续阅读").hasFocus());
      ReadingUiTest.render(decor, "covers-home-" + size + ".png");
      text(decor, "我的书库").performClick();
      ReadingUiTest.render(decor, "covers-shelf-" + size + ".png");
      settle(repository, decor);
      assertEquals(1, assertImages(decor));
      assertTrue(decor.findViewWithTag("book-" + book.id).hasFocus());
      ReadingUiTest.render(decor, "covers-shelf-" + size + ".png");
    }
  }

  private static int assertImages(View view) {
    int count = 0;
    if (view instanceof CoverView) {
      if (!view.getGlobalVisibleRect(new Rect())) return 0;
      Bitmap bitmap = ReflectionHelpers.getField(view, "image");
      assertNotNull(bitmap);
      assertTrue(bitmap.getWidth() <= 384 && bitmap.getHeight() <= 576);
      assertEquals(0xff314d4b, bitmap.getPixel(20, 20));
      count++;
    }
    if (view instanceof ViewGroup)
      for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++)
        count += assertImages(((ViewGroup) view).getChildAt(i));
    return count;
  }

  private static TextView text(View view, String value) {
    if (view instanceof TextView && value.equals(((TextView) view).getText().toString()))
      return (TextView) view;
    if (view instanceof ViewGroup)
      for (int i = 0; i < ((ViewGroup) view).getChildCount(); i++) {
        TextView result = text(((ViewGroup) view).getChildAt(i), value);
        if (result != null) return result;
      }
    return null;
  }

  private static void entry(ZipOutputStream zip, String name, byte[] data) throws IOException {
    zip.putNextEntry(new ZipEntry(name));
    zip.write(data);
    zip.closeEntry();
  }

  @Test
  public void deduplicatesVisibleViewsWithoutStealingFocusAndSurvivesColdRestart()
      throws Exception {
    ActivityController<Activity> controller = Robolectric.buildActivity(Activity.class).setup();
    Activity activity = controller.get();
    LibraryStore library = new LibraryStore(activity);
    AtomicInteger reads = new AtomicInteger();
    BookRecord book = book();
    try (CoverRepository covers =
        new CoverRepository(
            activity.getCacheDir(),
            library,
            b -> {
              reads.incrementAndGet();
              return image(Color.RED);
            })) {
      LinearLayout row = row(activity);
      CoverView first = view(activity, row), second = view(activity, row);
      first.setFocusable(true);
      first.setFocusableInTouchMode(true);
      first.requestFocus();
      covers.bind(first, book);
      covers.bind(second, book);
      settle(covers, row);
      assertEquals(1, reads.get());
      assertTrue(first.hasFocus());
      assertEquals(Color.RED, rendered(first).getPixel(100, 150));
      assertEquals(Color.RED, rendered(second).getPixel(100, 150));
    }
    try (CoverRepository cold =
        new CoverRepository(
            activity.getCacheDir(),
            library,
            b -> {
              fail("Cached cover should work with the source offline");
              return null;
            })) {
      LinearLayout row = row(activity);
      CoverView cover = view(activity, row);
      cold.bind(cover, book);
      settle(cold, row);
      assertEquals(Color.RED, rendered(cover).getPixel(100, 150));
    } finally {
      controller.close();
    }
  }

  @Test
  public void failedSourceRetriesInsteadOfPersistingAnEmptyCover() throws Exception {
    try (ActivityController<Activity> controller =
        Robolectric.buildActivity(Activity.class).setup()) {
      Activity activity = controller.get();
      AtomicInteger reads = new AtomicInteger();
      BookRecord book = book();
      try (CoverRepository covers =
          new CoverRepository(
              activity.getCacheDir(),
              new LibraryStore(activity),
              b -> {
                if (reads.incrementAndGet() == 1) throw new IOException("Disconnected");
                return image(Color.BLUE);
              })) {
        LinearLayout row = row(activity);
        CoverView cover = view(activity, row);
        covers.bind(cover, book);
        settle(covers, row);
        assertEquals(1, reads.get());
        assertNull(ReflectionHelpers.getField(cover, "image"));
        ShadowSystemClock.advanceBy(Duration.ofSeconds(31));
        row = row(activity);
        cover = view(activity, row);
        covers.bind(cover, book);
        settle(covers, row);
        assertEquals(2, reads.get());
        assertEquals(Color.BLUE, rendered(cover).getPixel(100, 150));
      }
    }
  }

  @Test
  public void detachedOrHiddenCardsDoNotReceiveImagesOrStartEagerReads() throws Exception {
    try (ActivityController<Activity> controller =
        Robolectric.buildActivity(Activity.class).setup()) {
      Activity activity = controller.get();
      CountDownLatch started = new CountDownLatch(1), finish = new CountDownLatch(1);
      AtomicInteger reads = new AtomicInteger();
      try (CoverRepository covers =
          new CoverRepository(
              activity.getCacheDir(),
              new LibraryStore(activity),
              b -> {
                reads.incrementAndGet();
                started.countDown();
                try {
                  if (!finish.await(5, TimeUnit.SECONDS)) throw new IOException("timed out");
                } catch (InterruptedException e) {
                  throw new IOException(e);
                }
                return image(Color.GREEN);
              })) {
        LinearLayout row = row(activity);
        CoverView hidden = view(activity, row);
        hidden.setVisibility(View.GONE);
        covers.bind(hidden, book());
        layout(row);
        ShadowLooper.idleMainLooper();
        assertEquals(0, reads.get());
        hidden.setVisibility(View.VISIBLE);
        layout(row);
        ShadowLooper.idleMainLooper();
        assertTrue(started.await(5, TimeUnit.SECONDS));
        row.removeView(hidden);
        finish.countDown();
        settle(covers, row);
        assertNull(ReflectionHelpers.getField(hidden, "image"));
      } finally {
        finish.countDown();
      }
    }
  }

  @Test
  public void changedSourcePathCannotReuseAnotherBooksThumbnail() throws Exception {
    try (ActivityController<Activity> controller =
        Robolectric.buildActivity(Activity.class).setup()) {
      Activity activity = controller.get();
      BookRecord book = book();
      AtomicInteger reads = new AtomicInteger();
      try (CoverRepository covers =
          new CoverRepository(
              activity.getCacheDir(),
              new LibraryStore(activity),
              b -> {
                reads.incrementAndGet();
                return image(b.remotePath.equals("first.epub") ? Color.RED : Color.BLUE);
              })) {
        LinearLayout row = row(activity);
        CoverView first = view(activity, row);
        covers.bind(first, book);
        settle(covers, row);
        book.remotePath = "second.epub";
        row = row(activity);
        CoverView second = view(activity, row);
        covers.bind(second, book);
        settle(covers, row);
        assertEquals(2, reads.get());
        assertEquals(Color.BLUE, rendered(second).getPixel(100, 150));
      }
    }
  }

  @Test
  public void landscapeCoverKeepsBothEdgesWithLetterboxingInsteadOfCropping() {
    try (ActivityController<Activity> controller =
        Robolectric.buildActivity(Activity.class).setup()) {
      Activity activity = controller.get();
      CoverView cover = new CoverView(activity, "封面", "作者");
      Bitmap wide = Bitmap.createBitmap(400, 200, Bitmap.Config.ARGB_8888);
      Canvas canvas = new Canvas(wide);
      Paint paint = new Paint();
      paint.setColor(Color.RED);
      canvas.drawRect(0, 0, 200, 200, paint);
      paint.setColor(Color.BLUE);
      canvas.drawRect(200, 0, 400, 200, paint);
      cover.setImage(wide);
      cover.layout(0, 0, 200, 300);
      Bitmap screen = rendered(cover);
      assertEquals(Color.RED, screen.getPixel(1, 150));
      assertEquals(Color.BLUE, screen.getPixel(198, 150));
      assertEquals(0xff20231e, screen.getPixel(100, 20));
    }
  }

  @Test
  public void svgCoverPageResolvesItsEmbeddedImageAndThumbnailStaysBounded() throws Exception {
    Bitmap source = Bitmap.createBitmap(1200, 1800, Bitmap.Config.ARGB_8888);
    source.eraseColor(Color.MAGENTA);
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    source.compress(Bitmap.CompressFormat.PNG, 100, out);
    byte[] bytes = out.toByteArray();
    byte[] page =
        ("<html><body><svg xmlns='http://www.w3.org/2000/svg'"
             + " xmlns:xlink='http://www.w3.org/1999/xlink' viewBox='0 0 1200 1800'><image"
             + " width='1200' height='1800' xlink:href='../Images/封面.png'/></svg></body></html>")
            .getBytes(StandardCharsets.UTF_8);
    try (BookArchive archive =
        new BookArchive() {
          public InputStream open(String path) throws IOException {
            if (path.equals("OPS/Text/cover.xhtml")) return new ByteArrayInputStream(page);
            assertEquals("OPS/Images/封面.png", path);
            return new ByteArrayInputStream(bytes);
          }

          public void close() {}
        }) {
      BookDocument.Illustration ref =
          new BookParser()
              .coverIllustration(
                  archive,
                  new EpubBook(
                      "书", "作者", java.util.Collections.emptyList(), "OPS/Text/cover.xhtml"));
      Bitmap cover = IllustrationDecoder.read(archive, ref, 384, 576);
      assertEquals(384, cover.getWidth());
      assertEquals(576, cover.getHeight());
      assertEquals(Color.MAGENTA, cover.getPixel(192, 288));
    }
  }

  @Test
  public void slowCoverNetworkReadDoesNotLockOutAnotherBooksRasterIllustration() throws Exception {
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    image(Color.GREEN).compress(Bitmap.CompressFormat.PNG, 100, out);
    byte[] bytes = out.toByteArray();
    CountDownLatch started = new CountDownLatch(1), finish = new CountDownLatch(1);
    ExecutorService workers = Executors.newFixedThreadPool(2);
    BookArchive archive =
        new BookArchive() {
          public InputStream open(String path) throws IOException {
            if (path.equals("slow.png")) {
              started.countDown();
              try {
                if (!finish.await(5, TimeUnit.SECONDS)) throw new IOException("Timed out");
              } catch (InterruptedException e) {
                throw new IOException(e);
              }
            }
            return new ByteArrayInputStream(bytes);
          }

          public void close() {}
        };
    try {
      Future<Bitmap> slow =
          workers.submit(
              () ->
                  IllustrationDecoder.read(
                      archive, new BookDocument.Illustration(0, 0, "slow.png", "", ""), 384, 576));
      assertTrue(started.await(5, TimeUnit.SECONDS));
      Future<Bitmap> reader =
          workers.submit(
              () ->
                  IllustrationDecoder.read(
                      archive, new BookDocument.Illustration(0, 0, "reader.png", "", "")));
      assertEquals(Color.GREEN, reader.get(2, TimeUnit.SECONDS).getPixel(10, 10));
      finish.countDown();
      assertNotNull(slow.get(5, TimeUnit.SECONDS));
    } finally {
      finish.countDown();
      workers.shutdownNow();
    }
  }

  private static BookRecord book() {
    BookRecord book = new BookRecord();
    book.title = "合成封面测试";
    book.sourceId = "test-source";
    book.remotePath = "first.epub";
    return book;
  }

  private static Bitmap image(int color) {
    Bitmap bitmap = Bitmap.createBitmap(100, 150, Bitmap.Config.ARGB_8888);
    bitmap.eraseColor(color);
    return bitmap;
  }

  private static LinearLayout row(Activity activity) {
    LinearLayout row = new LinearLayout(activity);
    activity.setContentView(row);
    return row;
  }

  private static CoverView view(Activity activity, LinearLayout row) {
    CoverView view = new CoverView(activity, "测试书", "合成作者");
    row.addView(view, new LinearLayout.LayoutParams(200, 300));
    return view;
  }

  private static void layout(View view) {
    view.measure(
        View.MeasureSpec.makeMeasureSpec(1200, View.MeasureSpec.EXACTLY),
        View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY));
    view.layout(0, 0, 1200, 800);
    view.getViewTreeObserver().dispatchOnGlobalLayout();
  }

  private static void settle(CoverRepository covers, View root) throws Exception {
    if (root instanceof LinearLayout) layout(root);
    else root.getViewTreeObserver().dispatchOnGlobalLayout();
    ShadowLooper.idleMainLooper();
    ExecutorService worker = ReflectionHelpers.getField(covers, "worker");
    worker.submit(() -> {}).get(10, TimeUnit.SECONDS);
    ShadowLooper.idleMainLooper();
  }

  private static Bitmap rendered(CoverView view) {
    Bitmap bitmap = Bitmap.createBitmap(200, 300, Bitmap.Config.ARGB_8888);
    view.draw(new Canvas(bitmap));
    return bitmap;
  }
}
