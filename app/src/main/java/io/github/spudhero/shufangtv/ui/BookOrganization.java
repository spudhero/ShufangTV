package io.github.spudhero.shufangtv.ui;

import android.app.Activity;
import android.app.AlertDialog;
import android.widget.EditText;
import android.widget.Toast;
import io.github.spudhero.shufangtv.data.BookRecord;
import io.github.spudhero.shufangtv.data.LibraryStore;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/** Logical shelf metadata only: no source file moves or provider calls. */
public final class BookOrganization {
  public static final String[] STATES = {"unread", "reading", "finished"};
  public static final String[] LABELS = {"待读", "在读", "已读"};
  private final Activity activity;
  private final LibraryStore library;
  private final Runnable changed;

  public BookOrganization(Activity activity, LibraryStore library, Runnable changed) {
    this.activity = activity;
    this.library = library;
    this.changed = changed;
  }

  public static String stateLabel(BookRecord book) {
    for (int i = 0; i < STATES.length; i++) if (STATES[i].equals(book.readState)) return LABELS[i];
    return LABELS[0];
  }

  public void pin(BookRecord book) {
    update(book, () -> book.pinned = !book.pinned);
  }

  public void state(BookRecord book) {
    new ReadingDialog(activity)
        .setTitle("阅读状态 · " + stateLabel(book))
        .setSingleChoiceItems(
            LABELS,
            java.util.Arrays.asList(STATES).indexOf(book.readState),
            (d, i) -> {
              d.dismiss();
              update(book, () -> book.readState = STATES[i]);
            })
        .setNegativeButton("返回", null)
        .show();
  }

  public void group(BookRecord book) {
    List<String> groups = library.groups();
    List<String> labels = new ArrayList<>();
    labels.add("不加入书单");
    labels.addAll(groups);
    labels.add("新建书单…");
    new ReadingDialog(activity)
        .setTitle("加入书单")
        .setItems(
            labels.toArray(new String[0]),
            (d, i) -> {
              if (i == labels.size() - 1) newGroup(book);
              else update(book, () -> book.group = i == 0 ? "" : groups.get(i - 1));
            })
        .setNegativeButton("返回", null)
        .show();
  }

  private void newGroup(BookRecord book) {
    EditText input = new EditText(activity);
    input.setSingleLine(true);
    input.setHint("例如：睡前读");
    input.setFilters(
        new android.text.InputFilter[] {new android.text.InputFilter.LengthFilter(24)});
    AlertDialog dialog =
        new ReadingDialog(activity)
            .setTitle("新建书单")
            .setMessage("只整理书房中的条目，原文件保持在原位置。")
            .setView(input)
            .setPositiveButton("保存", null)
            .setNegativeButton("取消", null)
            .create();
    dialog.show();
    dialog
        .getButton(AlertDialog.BUTTON_POSITIVE)
        .setOnClickListener(
            v -> {
              String name = input.getText().toString().trim();
              if (name.isEmpty()) {
                input.setError("请输入书单名称");
                return;
              }
              update(book, () -> book.group = name);
              dialog.dismiss();
            });
  }

  private void update(BookRecord book, Runnable action) {
    boolean pinned = book.pinned;
    String group = book.group, state = book.readState;
    action.run();
    try {
      library.save();
      changed.run();
    } catch (IOException e) {
      book.pinned = pinned;
      book.group = group;
      book.readState = state;
      Toast.makeText(activity, "书架设置未能保存", Toast.LENGTH_LONG).show();
    }
  }
}
