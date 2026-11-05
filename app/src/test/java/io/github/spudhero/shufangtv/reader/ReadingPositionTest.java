package io.github.spudhero.shufangtv.reader;

import static org.junit.Assert.*;

import android.content.Context;
import android.view.View;
import io.github.spudhero.shufangtv.data.BookRecord;
import io.github.spudhero.shufangtv.data.LibraryStore;
import io.github.spudhero.shufangtv.data.ReadingStyle;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 31)
public class ReadingPositionTest {
  @Test
  @org.robolectric.annotation.GraphicsMode(org.robolectric.annotation.GraphicsMode.Mode.NATIVE)
  public void narrowColumnReflowsAtSameUnicodeAnchorAndSurvivesResize() {
    PagedTextView page = new PagedTextView(RuntimeEnvironment.getApplication());
    page.layout(0, 0, 1280, 600);
    String text = ("读书与生活。🌱文字位置不依赖页码。".repeat(20) + "\n").repeat(100);
    page.setContent(text, text.indexOf("🌱") + 1);
    int anchor = page.offset();
    assertEquals(text.indexOf("🌱"), anchor);
    int wideEnd = page.nextOffset();
    page.setAppearance(22, 1.65f, true, 0xffeeeeee, 0);
    assertEquals(anchor, page.offset());
    assertTrue(page.nextOffset() < wideEnd);
    assertFalse(Character.isLowSurrogate(text.charAt(page.nextOffset())));
    assertTrue(page.next());
    assertTrue(page.previous());
    assertEquals(anchor, page.offset());
    page.layout(0, 0, 960, 500);
    assertEquals(anchor, page.offset());
    assertTrue(page.nextOffset() > anchor);
  }

  @Test
  public void perBookStyleSurvivesRestartWithoutChangingOtherBooksOrOldRecords() throws Exception {
    Context c = RuntimeEnvironment.getApplication();
    android.content.SharedPreferences prefs = c.getSharedPreferences("style-test", 0);
    prefs.edit().clear().putInt("font", 24).apply();
    LibraryStore library = new LibraryStore(c);
    BookRecord first = new BookRecord(), second = new BookRecord();
    first.readingStyle = ReadingStyle.defaults(prefs);
    first.readingStyle.font = 32;
    first.readingStyle.width = 0;
    first.offset = 127;
    library.books.add(first);
    library.books.add(second);
    library.save();
    LibraryStore restored = new LibraryStore(c);
    assertEquals(32, ReadingStyle.effective(restored.find(first.id), prefs).font);
    assertEquals(0, restored.find(first.id).readingStyle.width);
    assertEquals(127, restored.find(first.id).offset);
    prefs.edit().putInt("font", 28).apply();
    assertEquals(28, ReadingStyle.effective(restored.find(second.id), prefs).font);
    restored.find(first.id).readingStyle = null;
    assertEquals(28, ReadingStyle.effective(restored.find(first.id), prefs).font);
    org.json.JSONObject old = second.json();
    old.remove("readingStyle");
    assertNull(BookRecord.from(old).readingStyle);
    assertEquals(0, restored.booksDir.list().length);
  }

  @Test
  public void pageTurnsAndFontChangeKeepLogicalCharacterPosition() {
    PagedTextView page = new PagedTextView(RuntimeEnvironment.getApplication());
    page.measure(
        View.MeasureSpec.makeMeasureSpec(1280, View.MeasureSpec.EXACTLY),
        View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY));
    page.layout(0, 0, 1280, 600);
    page.setContent("一卷在手，片刻清宁。走过的路与读过的书，都留在心里。\n".repeat(500), 0);
    int second = page.nextOffset();
    assertTrue(second > 0);
    assertTrue(page.next());
    assertEquals(second, page.offset());
    assertTrue(page.previous());
    assertEquals(0, page.offset());
    assertTrue(page.next());
    page.setAppearance(30, 1.85f, true, 0xffeeeeee);
    assertEquals(second, page.offset());
    assertTrue(page.nextOffset() > page.offset());
  }

  @Test
  public void progressAndBookmarksSurviveRestartWithoutCreatingOfflineBook() throws Exception {
    Context context = RuntimeEnvironment.getApplication();
    LibraryStore library = new LibraryStore(context);
    BookRecord book = new BookRecord();
    book.title = "在线书籍";
    book.format = "epub";
    book.sourceId = "test-source";
    book.remotePath = "在线书籍.epub";
    book.chapter = 3;
    book.offset = 427;
    book.lastMode = "online";
    library.books.add(book);
    library.bookmark(book, "读到这里");
    LibraryStore reopened = new LibraryStore(context);
    BookRecord restored = reopened.find(book.id);
    assertNotNull(restored);
    assertTrue(restored.hasOnline());
    assertFalse(restored.hasOffline());
    assertEquals(3, restored.chapter);
    assertEquals(427, restored.offset);
    assertEquals(1, reopened.marks.size());
    assertEquals("online", reopened.marks.get(0).mode);
    assertEquals(0, reopened.booksDir.list().length);
  }

  @Test
  public void corruptMetadataIsPreservedInsteadOfOverwrittenByEmptyLibrary() throws Exception {
    Context context = RuntimeEnvironment.getApplication();
    File path = new File(context.getFilesDir(), "library.json");
    byte[] bad = "{broken original record".getBytes(StandardCharsets.UTF_8);
    Files.write(path.toPath(), bad);
    LibraryStore library = new LibraryStore(context);
    assertNotNull(library.loadError);
    assertTrue(library.books.isEmpty());
    try {
      library.save();
      fail("corrupt metadata overwritten");
    } catch (java.io.IOException expected) {
      assertArrayEquals(bad, Files.readAllBytes(path.toPath()));
    }
  }
}
