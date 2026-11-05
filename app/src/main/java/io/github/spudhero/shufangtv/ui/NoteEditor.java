package io.github.spudhero.shufangtv.ui;

import android.app.Activity;
import android.text.InputFilter;
import android.widget.EditText;
import android.widget.Toast;
import io.github.spudhero.shufangtv.data.LibraryStore;
import java.io.IOException;

public final class NoteEditor {
  private NoteEditor() {}

  public static void edit(
      Activity activity, LibraryStore library, LibraryStore.Mark mark, Runnable changed) {
    EditText input = new EditText(activity);
    input.setHint("写下自己的想法…");
    input.setText(mark.note);
    input.setMaxLines(6);
    input.setFilters(new InputFilter[] {new InputFilter.LengthFilter(2000)});
    new ReadingDialog(activity)
        .width(480)
        .setTitle("编辑备注")
        .setMessage(
            mark.excerpt.substring(
                0, io.github.spudhero.shufangtv.core.TextNavigator.boundary(mark.excerpt, 160)))
        .setView(input)
        .setPositiveButton(
            "保存",
            (d, w) -> {
              try {
                library.updateNote(mark, input.getText().toString());
                changed.run();
              } catch (IOException e) {
                Toast.makeText(activity, "备注未能保存", Toast.LENGTH_LONG).show();
              }
            })
        .setNegativeButton("取消", null)
        .show();
  }
}
