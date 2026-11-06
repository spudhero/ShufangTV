package io.github.spudhero.shufangtv.reader;

import android.graphics.*;
import android.util.Base64;
import com.caverock.androidsvg.SVG;
import com.caverock.androidsvg.SVGExternalFileResolver;
import io.github.spudhero.shufangtv.core.*;
import java.io.*;
import java.nio.charset.StandardCharsets;

/** Decode on the I/O executor, with a bounded compressed input and sampled raster allocation. */
public final class IllustrationDecoder {
  private static final int MAX_BYTES = 16 * 1024 * 1024;
  private static final int MAX_PIXELS = 6 * 1024 * 1024;

  public static synchronized Bitmap read(BookArchive archive, BookDocument.Illustration image)
      throws IOException {
    byte[] data =
        image.svg.isEmpty()
            ? bytes(archive, image.source)
            : image.svg.getBytes(StandardCharsets.UTF_8);
    String prefix = new String(data, 0, Math.min(512, data.length), StandardCharsets.UTF_8);
    if (!image.svg.isEmpty()
        || image.source.toLowerCase(java.util.Locale.ROOT).endsWith(".svg")
        || prefix.contains("<svg")) {
      if (prefix.contains("<!DOCTYPE") || prefix.contains("<!ENTITY"))
        throw new IOException("图片含不支持的外部实体");
      try {
        SVG svg = SVG.getFromInputStream(new ByteArrayInputStream(data));
        SVG.registerExternalFileResolver(
            new SVGExternalFileResolver() {
              @Override
              public Bitmap resolveImage(String name) {
                try {
                  String source = BookParser.resolve(image.source, name);
                  return raster(bytes(archive, source));
                } catch (Exception ignored) {
                  return null;
                }
              }
            });
        RectF box = svg.getDocumentViewBox();
        float w = svg.getDocumentWidth(), h = svg.getDocumentHeight();
        if (w <= 0 || h <= 0) {
          w = box == null ? 1920 : box.width();
          h = box == null ? 1080 : box.height();
        }
        float scale =
            Math.min(
                1,
                Math.min(4096f / Math.max(w, h), (float) Math.sqrt(MAX_PIXELS / ((double) w * h))));
        int width = Math.max(1, (int) (w * scale)), height = Math.max(1, (int) (h * scale));
        svg.setDocumentWidth(width);
        svg.setDocumentHeight(height);
        Bitmap result = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
        svg.renderToCanvas(new Canvas(result));
        return result;
      } catch (Exception e) {
        throw new IOException("SVG 插图解析失败", e);
      } finally {
        SVG.deregisterExternalFileResolver();
      }
    }
    return raster(data);
  }

  private static Bitmap raster(byte[] data) throws IOException {
    BitmapFactory.Options options = new BitmapFactory.Options();
    options.inJustDecodeBounds = true;
    BitmapFactory.decodeByteArray(data, 0, data.length, options);
    if (options.outWidth <= 0 || options.outHeight <= 0) throw new IOException("图片格式无效或已损坏");
    int sample = 1;
    while ((long) (options.outWidth / sample) * (options.outHeight / sample) > MAX_PIXELS
        || options.outWidth / sample > 8192
        || options.outHeight / sample > 8192) sample *= 2;
    options.inJustDecodeBounds = false;
    options.inSampleSize = sample;
    Bitmap result = BitmapFactory.decodeByteArray(data, 0, data.length, options);
    if (result == null) throw new IOException("图片无法解码");
    return result;
  }

  private static byte[] bytes(BookArchive archive, String source) throws IOException {
    if (source.startsWith("data:image/")) {
      int comma = source.indexOf(',');
      if (comma < 0
          || comma > 128
          || !source.substring(0, comma).endsWith(";base64")
          || source.length() - comma > MAX_BYTES * 4L / 3 + 4) throw new IOException("内嵌图片无效或过大");
      try {
        return Base64.decode(source.substring(comma + 1), Base64.DEFAULT);
      } catch (IllegalArgumentException e) {
        throw new IOException("内嵌图片已损坏", e);
      }
    }
    if (source.isEmpty()) throw new IOException("图片资源地址缺失");
    try (InputStream in = archive.open(source);
        ByteArrayOutputStream out = new ByteArrayOutputStream()) {
      byte[] buffer = new byte[16384];
      int count;
      while ((count = in.read(buffer)) != -1) {
        if (out.size() + (long) count > MAX_BYTES) throw new IOException("单张图片超过 16 MiB");
        out.write(buffer, 0, count);
      }
      return out.toByteArray();
    }
  }
}
