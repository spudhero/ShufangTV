package io.github.spudhero.shufangtv.ui;

import static io.github.spudhero.shufangtv.ui.TvStyle.*;

import android.app.*;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.view.*;
import android.widget.*;
import io.github.spudhero.shufangtv.data.*;
import io.github.spudhero.shufangtv.reader.*;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** Native reader with logical text positions shared by online and offline copies. */
public final class ReaderScreen extends LinearLayout {
  private final Activity activity;
  private final ExecutorService io;
  private final LibraryStore library;
  private final SharedPreferences prefs;
  private final BookSession session;
  private final BookRecord book;
  private final Runnable exit;
  private final PagedTextView page;
  private final ImageView image;
  private final TextView chapter, progress, status, bookTitle;
  private final FrameLayout body;
  private final LinearLayout bottom;
  private final View previousButton;
  private boolean active = true, busy, textReady;
  private int generation;
  private Bitmap bitmap;

  public ReaderScreen(
      Activity activity,
      ExecutorService io,
      LibraryStore library,
      SharedPreferences prefs,
      BookSession session,
      Runnable exit) {
    super(activity);
    this.activity = activity;
    this.io = io;
    this.library = library;
    this.prefs = prefs;
    this.session = session;
    book = session.record;
    this.exit = exit;
    setOrientation(VERTICAL);
    setPadding(dp(activity, 66), dp(activity, 28), dp(activity, 66), dp(activity, 22));
    LinearLayout header = horizontal(activity);
    bookTitle = heading(activity, book.title, 18);
    add(header, bookTitle, 0, 28);
    ((LayoutParams) header.getChildAt(0).getLayoutParams()).weight = 1;
    add(header, text(activity, session.online ? "在线阅读" : "离线阅读", 12, ACCENT), -2, 28);
    add(this, header, -1, 34);
    line(this);
    chapter = text(activity, "", 14, MUTED);
    gap(this, 16);
    add(this, chapter, -1, 24);
    body = new FrameLayout(activity);
    addView(body, new LayoutParams(-1, 0, 1));
    page = new PagedTextView(activity);
    image = new ImageView(activity);
    image.setScaleType(ImageView.ScaleType.FIT_CENTER);
    image.setFocusable(true);
    image.setFocusableInTouchMode(true);
    body.addView(page, new FrameLayout.LayoutParams(-1, -1));
    body.addView(image, new FrameLayout.LayoutParams(-1, -1));
    status = text(activity, "", 14, MUTED);
    status.setGravity(Gravity.CENTER);
    body.addView(status, new FrameLayout.LayoutParams(-1, -1));
    status.setVisibility(GONE);
    page.onPageReady = this::updateProgress;
    bottom = horizontal(activity);
    previousButton = button(activity, "〈 上一页", false, () -> turn(-1));
    add(bottom, previousButton, 100, 38);
    progress = text(activity, "", 12, MUTED);
    progress.setGravity(Gravity.CENTER);
    bottom.addView(progress, new LayoutParams(0, 38, 1));
    add(bottom, button(activity, "阅读菜单", false, this::menu), 115, 38);
    gap(bottom, 12);
    add(bottom, button(activity, "下一页 〉", false, () -> turn(1)), 100, 38);
    add(this, bottom, -1, 40);
    applyAppearance();
    if (book.format.equals("pdf")) {
      page.setVisibility(GONE);
      image.setVisibility(VISIBLE);
      book.pdfPage = Math.min(book.pdfPage, session.pdfPages() - 1);
      loadPdf();
    } else {
      image.setVisibility(GONE);
      book.chapter = Math.min(book.chapter, session.chapterCount() - 1);
      loadChapter(false, false);
    }
  }

  private void applyAppearance() {
    int theme = prefs.getInt("paper", 0);
    setBackgroundColor(theme == 1 ? 0xffe2dac4 : theme == 2 ? 0xff0d110f : 0xff1c201d);
    page.setAppearance(
        prefs.getInt("font", 22),
        prefs.getFloat("line", 1.65f),
        prefs.getBoolean("serif", true),
        theme == 1 ? 0xff33382f : 0xffe9e7dc);
    float brightness = prefs.getInt("brightness", 100) / 100f;
    page.setAlpha(brightness);
    image.setAlpha(brightness);
    chapter.setTextColor(theme == 1 ? 0xff50594c : MUTED);
    progress.setTextColor(theme == 1 ? 0xff50594c : MUTED);
    bookTitle.setTextColor(theme == 1 ? 0xff33382f : TEXT);
    status.setTextColor(theme == 1 ? 0xff50594c : MUTED);
  }

  private <T> void task(Callable<T> work, Consumer<T> done, Runnable retry) {
    busy = true;
    int id = ++generation;
    status.setText("正在读取…");
    status.setVisibility(VISIBLE);
    io.submit(
        () -> {
          try {
            T value = work.call();
            activity.runOnUiThread(
                () -> {
                  if (!active || id != generation) {
                    if (value instanceof Bitmap) ((Bitmap) value).recycle();
                    return;
                  }
                  busy = false;
                  status.setVisibility(GONE);
                  done.accept(value);
                });
          } catch (Exception e) {
            activity.runOnUiThread(
                () -> {
                  if (!active || id != generation) return;
                  busy = false;
                  status.setVisibility(GONE);
                  new AlertDialog.Builder(activity)
                      .setTitle("读取未完成")
                      .setMessage(safeError(e))
                      .setPositiveButton("重试", (d, w) -> retry.run())
                      .setNegativeButton("返回书库", (d, w) -> exit.run())
                      .setOnCancelListener(d -> surface().requestFocus())
                      .show();
                });
          }
        });
  }

  private String safeError(Exception e) {
    return e.getMessage() == null ? "请检查书库连接后重试。在线模式不会自动下载整本书。" : e.getMessage();
  }

  private void loadChapter(boolean end, boolean precedingSegment) {
    final int savedOffset = book.offset;
    task(
        () ->
            precedingSegment
                ? session.previousSegment()
                : session.chapter(book.chapter, book.txtByte),
        c -> {
          chapter.setText(c.title);
          if (session.segment != null) book.txtByte = session.segment.start;
          textReady = true;
          page.setContent(c.text, end ? Math.max(0, c.text.length() - 1) : savedOffset);
          if (end) {
            page.setContent(c.text, c.text.length());
            page.previous();
          }
          updateProgress();
          surface().requestFocus();
          save();
        },
        () -> loadChapter(end, precedingSegment));
  }

  private void loadPdf() {
    int width = Math.max(960, body.getWidth()), height = Math.max(540, body.getHeight());
    task(
        () -> session.renderPdf(book.pdfPage, Math.min(1920, width), Math.min(1080, height)),
        b -> {
          Bitmap old = bitmap;
          bitmap = b;
          image.setImageBitmap(b);
          if (old != null && old != b) old.recycle();
          chapter.setText("PDF · 第 " + (book.pdfPage + 1) + " 页");
          updateProgress();
          surface().requestFocus();
          save();
        },
        this::loadPdf);
  }

  public View surface() {
    return book.format.equals("pdf") ? image : page;
  }

  public boolean handle(KeyEvent event) {
    if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
    int key = event.getKeyCode();
    if (key == KeyEvent.KEYCODE_ESCAPE) {
      exit.run();
      return true;
    }
    if (busy)
      return key == KeyEvent.KEYCODE_DPAD_CENTER
          || key == KeyEvent.KEYCODE_ENTER
          || key == KeyEvent.KEYCODE_DPAD_LEFT
          || key == KeyEvent.KEYCODE_DPAD_RIGHT;
    if (surface().hasFocus()) {
      if (key == KeyEvent.KEYCODE_DPAD_RIGHT) {
        turn(1);
        return true;
      }
      if (key == KeyEvent.KEYCODE_DPAD_LEFT) {
        turn(-1);
        return true;
      }
      if (key == KeyEvent.KEYCODE_DPAD_CENTER || key == KeyEvent.KEYCODE_ENTER) {
        if (event.getRepeatCount() == 0) menu();
        return true;
      }
      if (key == KeyEvent.KEYCODE_DPAD_DOWN) {
        previousButton.requestFocus();
        return true;
      }
    } else if (bottom.hasFocus() && key == KeyEvent.KEYCODE_DPAD_UP) {
      surface().requestFocus();
      return true;
    }
    return false;
  }

  private void turn(int delta) {
    if (busy) return;
    if (book.format.equals("pdf")) {
      int next = Math.max(0, Math.min(session.pdfPages() - 1, book.pdfPage + delta));
      if (next != book.pdfPage) {
        book.pdfPage = next;
        loadPdf();
      } else Toast.makeText(activity, delta > 0 ? "已经到末页" : "已经到开头", Toast.LENGTH_SHORT).show();
      return;
    }
    if (delta > 0 ? page.next() : page.previous()) {
      book.offset = page.offset();
      updateProgress();
      save();
      return;
    }
    if (session.epub != null) {
      int next = book.chapter + delta;
      if (next >= 0 && next < session.chapterCount()) {
        book.chapter = next;
        book.offset = 0;
        loadChapter(delta < 0, false);
        return;
      }
    } else if (session.segment != null) {
      if (delta > 0 && session.segment.end < session.textLength()) {
        book.txtByte = session.segment.end;
        book.offset = 0;
        loadChapter(false, false);
        return;
      }
      if (delta < 0 && session.segment.start > 0) {
        book.offset = 0;
        loadChapter(true, true);
        return;
      }
    }
    Toast.makeText(activity, delta > 0 ? "已经到末尾" : "已经到开头", Toast.LENGTH_SHORT).show();
  }

  private void updateProgress() {
    if (book.format.equals("pdf")) {
      progress.setText((book.pdfPage + 1) + " / " + session.pdfPages());
      return;
    }
    if (!textReady) return;
    book.offset = page.offset();
    if (session.epub != null)
      progress.setText(
          "第 "
              + (book.chapter + 1)
              + " / "
              + session.chapterCount()
              + " 章  ·  "
              + Math.round(100.0 * page.nextOffset() / Math.max(1, page.totalChars()))
              + "%");
    else if (session.segment != null)
      progress.setText(
          Math.min(
                  100,
                  Math.round(
                      100.0
                          * (session.segment.start
                              + (session.segment.end - session.segment.start)
                                  * (double) page.nextOffset()
                                  / Math.max(1, page.totalChars()))
                          / Math.max(1, session.textLength())))
              + "%  ·  左右翻页");
  }

  private void menu() {
    if (busy) return;
    new AlertDialog.Builder(activity)
        .setTitle("阅读菜单")
        .setItems(
            new String[] {"目录 / 跳转", "添加书签", "字体与排版", "亮度与纸色", "返回书库"},
            (d, which) -> {
              switch (which) {
                case 0:
                  contents();
                  break;
                case 1:
                  try {
                    library.bookmark(
                        book,
                        book.format.equals("pdf")
                            ? "第 " + (book.pdfPage + 1) + " 页"
                            : page.excerpt());
                    Toast.makeText(activity, "书签已保存", Toast.LENGTH_SHORT).show();
                  } catch (IOException e) {
                    Toast.makeText(activity, e.getMessage(), Toast.LENGTH_LONG).show();
                  }
                  break;
                case 2:
                  fontMenu();
                  break;
                case 3:
                  paperMenu();
                  break;
                default:
                  exit.run();
              }
            })
        .setOnDismissListener(d -> surface().requestFocus())
        .show();
  }

  private void contents() {
    if (book.format.equals("pdf")) {
      EditText input = new EditText(activity);
      input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
      input.setText(Integer.toString(book.pdfPage + 1));
      new AlertDialog.Builder(activity)
          .setTitle("跳转页码（1–" + session.pdfPages() + "）")
          .setView(input)
          .setPositiveButton(
              "跳转",
              (d, w) -> {
                try {
                  int p = Integer.parseInt(input.getText().toString());
                  if (p < 1 || p > session.pdfPages()) throw new NumberFormatException();
                  book.pdfPage = p - 1;
                  loadPdf();
                } catch (NumberFormatException e) {
                  Toast.makeText(activity, "请输入有效页码", Toast.LENGTH_SHORT).show();
                }
              })
          .setNegativeButton("取消", null)
          .show();
      return;
    }
    if (session.epub == null) {
      Toast.makeText(activity, "TXT 按正文分段，使用左右键翻页", Toast.LENGTH_SHORT).show();
      return;
    }
    List<String> titles = session.chapterTitles();
    new AlertDialog.Builder(activity)
        .setTitle("目录")
        .setSingleChoiceItems(
            titles.toArray(new String[0]),
            book.chapter,
            (d, which) -> {
              book.chapter = which;
              book.offset = 0;
              d.dismiss();
              loadChapter(false, false);
            })
        .setNegativeButton("返回", null)
        .show();
  }

  private void fontMenu() {
    String[] options = {"字号减小", "字号增大", "行距紧凑（1.45）", "行距舒展（1.65）", "行距宽松（1.85）", "宋体", "黑体"};
    new AlertDialog.Builder(activity)
        .setTitle("字体与排版")
        .setItems(
            options,
            (d, which) -> {
              SharedPreferences.Editor edit = prefs.edit();
              int font = prefs.getInt("font", 22);
              if (which < 2)
                edit.putInt("font", Math.max(16, Math.min(32, font + (which == 0 ? -2 : 2))));
              else if (which < 5)
                edit.putFloat("line", which == 2 ? 1.45f : which == 3 ? 1.65f : 1.85f);
              else edit.putBoolean("serif", which == 5);
              edit.apply();
              applyAppearance();
              save();
            })
        .show();
  }

  private void paperMenu() {
    new AlertDialog.Builder(activity)
        .setTitle("亮度与纸色")
        .setItems(
            new String[] {"暗色书房", "暖纸色", "墨黑", "亮度 100%", "亮度 80%", "亮度 60%"},
            (d, which) -> {
              if (which < 3) prefs.edit().putInt("paper", which).apply();
              else
                prefs.edit().putInt("brightness", which == 3 ? 100 : which == 4 ? 80 : 60).apply();
              applyAppearance();
            })
        .show();
  }

  public void save() {
    book.lastRead = System.currentTimeMillis();
    book.lastMode = session.online ? "online" : "offline";
    if (textReady && !book.format.equals("pdf")) book.offset = page.offset();
    try {
      library.save();
    } catch (IOException e) {
      Toast.makeText(activity, "阅读进度未能保存", Toast.LENGTH_LONG).show();
    }
  }

  public void dispose() {
    if (!active) return;
    save();
    active = false;
    generation++;
    io.submit(
        () -> {
          try {
            session.close();
          } catch (IOException ignored) {
          }
        });
    image.setImageDrawable(null);
    if (bitmap != null) {
      bitmap.recycle();
      bitmap = null;
    }
  }
}
