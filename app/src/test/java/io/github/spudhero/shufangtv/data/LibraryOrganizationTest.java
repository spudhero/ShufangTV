package io.github.spudhero.shufangtv.data;

import static org.junit.Assert.*;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 31)
public class LibraryOrganizationTest {
  @Test
  public void pinnedShelfOrderDoesNotChangeContinueReadingOrSourceIdentity() throws Exception {
    LibraryStore library = new LibraryStore(RuntimeEnvironment.getApplication());
    BookRecord old = new BookRecord(), recent = new BookRecord();
    old.title = "乙";
    old.pinned = true;
    old.lastRead = 5;
    old.sourceId = "cloud-test";
    old.remotePath = "opaque-file-id";
    old.group = "睡前读";
    old.readState = "finished";
    old.offset = 102;
    recent.title = "甲";
    recent.lastRead = 10;
    recent.group = "睡前读";
    library.books.add(old);
    library.books.add(recent);
    library.save();
    LibraryStore reopened = new LibraryStore(RuntimeEnvironment.getApplication());
    assertEquals(old.id, reopened.shelf(false).get(0).id);
    assertEquals(old.id, reopened.shelf(true).get(0).id);
    assertEquals(recent.id, reopened.recent().get(0).id);
    assertEquals(1, reopened.groups().size());
    BookRecord restored = reopened.find(old.id);
    assertEquals("finished", restored.readState);
    assertEquals(102, restored.offset);
    assertEquals("opaque-file-id", restored.remotePath);
    assertEquals("cloud-test", restored.sourceId);
    assertEquals(0, reopened.booksDir.list().length);
  }

  @Test
  public void legacyReadingStateMigratesAndSearchHistoryRemainsBounded() throws Exception {
    BookRecord book = new BookRecord();
    book.lastRead = 100;
    org.json.JSONObject legacy = book.json();
    legacy.remove("readState");
    assertEquals("reading", BookRecord.from(legacy).readState);
    for (int i = 0; i < 20; i++) book.rememberSearch("关键词" + i);
    book.rememberSearch("关键词18");
    BookRecord restored = BookRecord.from(book.json());
    assertEquals(8, restored.searchHistory.size());
    assertEquals("关键词18", restored.searchHistory.get(0));
    assertFalse(restored.pinned);
    assertEquals("", restored.group);
  }
}
