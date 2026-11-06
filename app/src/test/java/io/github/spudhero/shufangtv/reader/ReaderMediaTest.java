package io.github.spudhero.shufangtv.reader;

import static org.junit.Assert.*;

import android.content.Context;
import android.graphics.*;
import io.github.spudhero.shufangtv.core.*;
import io.github.spudhero.shufangtv.data.*;
import java.io.*;
import java.util.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.*;
import org.robolectric.annotation.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 31)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class ReaderMediaTest {
  @Test
  public void mixedPagesRenderRealPixelsScrollAndRejectStaleImages() {
    PagedTextView page = new PagedTextView(RuntimeEnvironment.getApplication());
    page.layout(0, 0, 800, 400);
    String text = "前文\n［插图］\n后文";
    BookDocument.Illustration ref = new BookDocument.Illustration(3, 7, "page.png", "", "");
    List<BookDocument.Illustration> requested = new ArrayList<>();
    page.onIllustrationNeeded = requested::add;
    page.setChapter(new BookDocument.Chapter("混排", text, List.of(ref)), 0, 0);
    assertFalse(page.showingImage());
    assertTrue(page.next());
    assertTrue(page.showingImage());
    assertEquals(List.of(ref), requested);
    Bitmap source = Bitmap.createBitmap(400, 1200, Bitmap.Config.ARGB_8888);
    source.eraseColor(Color.RED);
    Canvas drawing = new Canvas(source);
    Paint ink = new Paint();
    ink.setColor(Color.BLUE);
    drawing.drawRect(0, 600, 400, 1200, ink);
    page.illustrationReady(ref, source, null);
    Bitmap screen = Bitmap.createBitmap(800, 400, Bitmap.Config.ARGB_8888);
    page.draw(new Canvas(screen));
    assertTrue(page.fitsWholeImage());
    assertEquals(Color.BLUE, screen.getPixel(400, 350));
    page.setImageFit(2);
    assertFalse(page.fitsWholeImage());
    page.draw(new Canvas(screen));
    assertEquals(Color.RED, screen.getPixel(400, 200));
    int anchor = page.offset();
    assertTrue(page.scrollImage(1));
    assertEquals(anchor, page.offset());
    assertTrue(page.imageY() > 0);
    while (page.scrollImage(1)) {}
    page.draw(new Canvas(screen));
    assertEquals(Color.BLUE, screen.getPixel(400, 200));
    assertTrue(page.next());
    assertFalse(page.showingImage());
    assertTrue(source.isRecycled());
    assertEquals("后文", text.substring(page.offset()).trim());
    Bitmap late = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888);
    page.illustrationReady(ref, late, null);
    assertTrue(late.isRecycled());
    page.setContent(text, text.length());
    assertTrue(page.previous());
    assertEquals("后文", text.substring(page.offset()).trim());
    assertTrue(page.previous());
    assertTrue(page.showingImage());
    assertEquals(3, page.offset());
    screen.recycle();
  }

  @Test
  public void imageAndPdfBookmarksRestoreTheirViewportWithOldRecordDefaults() throws Exception {
    Context c = RuntimeEnvironment.getApplication();
    LibraryStore library = new LibraryStore(c);
    BookRecord book = new BookRecord();
    book.format = "azw3";
    book.imageY = .3f;
    book.imageFit = 2;
    book.pdfY = .4f;
    book.pdfX = .2f;
    book.pdfZoom = 2;
    library.books.add(book);
    library.bookmark(book, "位置");
    LibraryStore restored = new LibraryStore(c);
    BookRecord record = restored.find(book.id);
    assertEquals(.3, record.imageY, .0001);
    assertEquals(2, record.imageFit);
    assertEquals(.4, record.pdfY, .0001);
    assertEquals(2, record.pdfZoom, 0);
    assertEquals(.4, restored.marks.get(0).pdfY, .0001);
    org.json.JSONObject old = book.json();
    old.remove("pdfZoom");
    old.remove("pdfY");
    old.remove("imageY");
    assertEquals(1, BookRecord.from(old).pdfZoom, 0);
    assertEquals(0, BookRecord.from(old).imageY, 0);
    assertTrue(BookRecord.supported("BOOK.MOBI"));
    assertTrue(BookRecord.supported("BOOK.AZW3"));
  }
}
