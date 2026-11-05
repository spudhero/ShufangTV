package io.github.spudhero.shufangtv.data;

import static org.junit.Assert.*;

import android.content.Context;
import io.github.spudhero.shufangtv.core.TextNavigator;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 31)
public class ReadingNotesTest {
  @Test
  public void fullMetadataRejectsNewNoteAndPreservesReadablePreviousLibrary() throws Exception {
    Context c = RuntimeEnvironment.getApplication();
    LibraryStore library = new LibraryStore(c);
    BookRecord book = new BookRecord();
    book.title = "已保存的书";
    library.books.add(book);
    library.save();
    book.title = "x".repeat(4 * 1024 * 1024);
    try {
      library.annotate(book, "一段正文", new TextNavigator.Range(0, 4), "想法");
      fail("Oversized metadata was saved");
    } catch (java.io.IOException expected) {
      assertTrue(library.marks.isEmpty());
    }
    LibraryStore restored = new LibraryStore(c);
    assertNull(restored.loadError);
    assertEquals("已保存的书", restored.find(book.id).title);
    assertTrue(restored.marks.isEmpty());
  }

  @Test
  public void notesPersistWithAnchorsAndExportBothFormatsWithoutChangingSources() throws Exception {
    Context c = RuntimeEnvironment.getApplication();
    LibraryStore library = new LibraryStore(c);
    BookRecord b = new BookRecord();
    b.title = "一本 [书]";
    b.format = "txt";
    b.txtByte = 4096;
    b.remotePath = "/books/original.txt";
    library.books.add(b);
    String text = "前段\n第二段😀\n末段";
    LibraryStore.Mark m = library.annotate(b, text, TextNavigator.paragraph(text, 4), "自己的想法\n第二行");
    library.annotate(b, text, TextNavigator.paragraph(text, 4), "更新的备注");
    assertEquals(1, library.marks.size());
    LibraryStore loaded = new LibraryStore(c);
    LibraryStore.Mark note = loaded.marks.get(0);
    assertTrue(note.matches(text));
    assertFalse(note.matches("插入" + text));
    assertEquals(4096, note.txtByte);
    assertEquals("第二段😀", note.excerpt);
    assertEquals("更新的备注", note.note);
    String md = NoteExport.render(loaded, true), plain = NoteExport.render(loaded, false);
    assertTrue(md.contains("## 一本 \\[书\\]"));
    assertTrue(md.contains("> 第二段😀"));
    assertTrue(plain.contains("《一本 [书]》"));
    assertTrue(plain.contains("更新的备注"));
    assertFalse(md.contains("/books/original.txt"));
    assertEquals(0, library.booksDir.list().length);
    loaded.removeMark(note);
    assertTrue(new LibraryStore(c).marks.isEmpty());
    assertEquals("/books/original.txt", new LibraryStore(c).books.get(0).remotePath);
  }

  @Test
  public void oldPositionBookmarksStayValidAndInvalidRangesNeverSave() throws Exception {
    LibraryStore.Mark old =
        LibraryStore.Mark.from(
            new JSONObject().put("bookId", "old").put("offset", 12).put("excerpt", "原书签"));
    assertFalse(old.isNote());
    assertEquals("", old.note);
    Context c = RuntimeEnvironment.getApplication();
    LibraryStore library = new LibraryStore(c);
    BookRecord b = new BookRecord();
    library.books.add(b);
    try {
      library.annotate(b, "甲😀乙", new TextNavigator.Range(0, 2), "");
      fail();
    } catch (java.io.IOException expected) {
      assertTrue(library.marks.isEmpty());
    }
    String huge = "甲".repeat(3999) + "😀" + "乙".repeat(4100);
    TextNavigator.Range first = TextNavigator.paragraph(huge, 0);
    TextNavigator.Range second = TextNavigator.paragraph(huge, first.end);
    assertEquals(first.end, second.start);
    assertTrue(second.end > first.end);
    assertEquals(first.start, TextNavigator.previousParagraph(huge, second.start).start);
    LibraryStore.Mark mark = library.annotate(b, huge, second, "甲".repeat(1999) + "😀");
    assertTrue(mark.matches(huge));
    assertEquals(1999, mark.note.length());
  }
}
