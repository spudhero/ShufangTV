package io.github.spudhero.shufangtv.reader;

import static org.junit.Assert.*;

import android.content.Context;
import android.view.View;
import io.github.spudhero.shufangtv.data.BookRecord;
import io.github.spudhero.shufangtv.data.LibraryStore;
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
