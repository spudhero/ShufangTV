package io.github.spudhero.shufangtv.reader;

import android.content.Context;
import android.graphics.*;
import android.graphics.pdf.PdfDocument;
import androidx.test.platform.app.InstrumentationRegistry;
import io.github.spudhero.shufangtv.core.*;
import io.github.spudhero.shufangtv.data.*;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import junit.framework.TestCase;

/** Runs against Android PDFium on the actual device, which Robolectric does not implement. */
public class ReaderDeviceTest extends TestCase {
  public void testPdfRendersOnlyTheViewportAndShowsDifferentContentAfterScrolling()
      throws Exception {
    Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    LibraryStore library = new LibraryStore(context);
    BookRecord book = new BookRecord();
    book.format = "pdf";
    book.offlineFile = book.id + ".pdf";
    PdfDocument doc = new PdfDocument();
    try {
      PdfDocument.Page page = doc.startPage(new PdfDocument.PageInfo.Builder(600, 900, 1).create());
      Paint paint = new Paint();
      paint.setColor(Color.RED);
      page.getCanvas().drawRect(0, 0, 600, 450, paint);
      paint.setColor(Color.BLUE);
      page.getCanvas().drawRect(0, 450, 600, 900, paint);
      doc.finishPage(page);
      try (OutputStream out = new FileOutputStream(library.file(book))) {
        doc.writeTo(out);
      }
    } finally {
      doc.close();
    }
    try (BookSession session =
        BookSession.open(context, book, false, library, new SourceStore(context))) {
      BookSession.PdfFrame top = session.renderPdf(0, 800, 400, 1, 0, 0);
      BookSession.PdfFrame bottom = session.renderPdf(0, 800, 400, 1, 0, 1);
      assertEquals(800, top.bitmap.getWidth());
      assertEquals(400, top.bitmap.getHeight());
      assertEquals(Color.RED, top.bitmap.getPixel(400, 200));
      assertEquals(Color.BLUE, bottom.bitmap.getPixel(400, 200));
      assertEquals(1200, top.viewport.documentHeight, .01);
      BookSession.PdfFrame zoom = session.renderPdf(0, 800, 400, 2, 1, .3f);
      assertEquals(800, zoom.bitmap.getWidth());
      assertEquals(1600, zoom.viewport.documentWidth, .01);
      top.bitmap.recycle();
      bottom.bitmap.recycle();
      zoom.bitmap.recycle();
    } finally {
      library.file(book).delete();
    }
  }

  public void testNativeMobiParsesPalmDocCompressionAndRejectsEncryptedAndBrokenInput()
      throws Exception {
    Context context = InstrumentationRegistry.getInstrumentation().getTargetContext();
    File file = File.createTempFile("reader-native-fixture", ".mobi", context.getCacheDir());
    try {
      for (int compression : new int[] {1, 2}) {
        java.nio.file.Files.write(file.toPath(), fixture(compression, 0));
        try (RandomAccessFile fd = new RandomAccessFile(file, "r");
            android.os.ParcelFileDescriptor pfd = android.os.ParcelFileDescriptor.dup(fd.getFD());
            MobiArchive archive = new MobiArchive(pfd.getFd(), file.length())) {
          BookParser parser = new BookParser();
          EpubBook book = archive.inspect(parser);
          assertEquals(1, book.spine.size());
          assertTrue(
              parser.readChapter(archive, book.spine.get(0)).text.contains("Visible content."));
        }
      }
      for (byte[] invalid : new byte[][] {new byte[78], fixture(1, 2)}) {
        java.nio.file.Files.write(file.toPath(), invalid);
        try (android.os.ParcelFileDescriptor fd =
            android.os.ParcelFileDescriptor.open(
                file, android.os.ParcelFileDescriptor.MODE_READ_ONLY)) {
          try (MobiArchive ignored = new MobiArchive(fd.getFd(), file.length())) {
            fail("Invalid book accepted");
          } catch (IOException expected) {
            assertNotNull(expected.getMessage());
          }
        }
      }
    } finally {
      file.delete();
    }
  }

  private static byte[] fixture(int compression, int encryption) throws IOException {
    byte[] html =
        "<html><body><h1>Reader fixture</h1><p>Visible content.</p></body></html>"
            .getBytes(StandardCharsets.US_ASCII);
    ByteArrayOutputStream encoded = new ByteArrayOutputStream();
    if (compression == 1) encoded.write(html);
    else
      for (int i = 0; i < html.length; i += 8) {
        int count = Math.min(8, html.length - i);
        encoded.write(count);
        encoded.write(html, i, count);
      }
    ByteBuffer out = ByteBuffer.allocate(112 + encoded.size());
    out.put("Reader fixture".getBytes(StandardCharsets.US_ASCII));
    out.position(60);
    out.put("TEXtREAd".getBytes(StandardCharsets.US_ASCII));
    out.position(76);
    out.putShort((short) 2);
    out.putInt(96).putInt(1).putInt(112).putInt(2).putShort((short) 0);
    out.putShort((short) compression)
        .putShort((short) 0)
        .putInt(html.length)
        .putShort((short) 1)
        .putShort((short) 4096)
        .putShort((short) encryption)
        .putShort((short) 0);
    out.put(encoded.toByteArray());
    return out.array();
  }
}
