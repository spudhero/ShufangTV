package io.github.spudhero.shufangtv.ui;

import static io.github.spudhero.shufangtv.ui.TvStyle.*;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.BaseAdapter;
import android.widget.CheckedTextView;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListAdapter;
import android.widget.ListView;
import android.widget.TextView;

/** Reading-room dialogs retain native list/choice semantics and share one TV focus treatment. */
public class ReadingDialog extends AlertDialog.Builder {
  private final Activity activity;
  private int panelWidth = 360;
  private LinearLayout titleHeader;
  private CharSequence summary;
  private boolean hasChoices;

  public ReadingDialog(Activity activity) {
    super(activity);
    this.activity = activity;
  }

  public ReadingDialog width(int width) {
    panelWidth = width;
    return this;
  }

  @Override
  public AlertDialog.Builder setMessage(CharSequence message) {
    summary = message;
    return super.setMessage(message);
  }

  @Override
  public AlertDialog.Builder setItems(
      CharSequence[] items, android.content.DialogInterface.OnClickListener listener) {
    hasChoices = true;
    return super.setItems(items, listener);
  }

  @Override
  public AlertDialog.Builder setSingleChoiceItems(
      CharSequence[] items, int checked, android.content.DialogInterface.OnClickListener listener) {
    hasChoices = true;
    return super.setSingleChoiceItems(items, checked, listener);
  }

  @Override
  public AlertDialog.Builder setView(View view) {
    if (view instanceof EditText) {
      EditText input = (EditText) view;
      input.setTextColor(TEXT);
      input.setHintTextColor(MUTED);
      input.setTextSize(16);
      input.setMinHeight(dp(activity, 48));
      input.setBackgroundTintList(ColorStateList.valueOf(ACCENT));
    }
    FrameLayout inset = new FrameLayout(activity);
    inset.setPadding(dp(activity, 24), dp(activity, 8), dp(activity, 24), dp(activity, 12));
    inset.addView(view, new FrameLayout.LayoutParams(-1, -2));
    return super.setView(inset);
  }

  @Override
  public AlertDialog.Builder setTitle(CharSequence title) {
    super.setTitle(title);
    LinearLayout header = vertical(activity);
    titleHeader = header;
    header.setPadding(dp(activity, 24), dp(activity, 22), dp(activity, 24), dp(activity, 12));
    TextView label = heading(activity, title.toString(), 21);
    if (android.os.Build.VERSION.SDK_INT >= 28) label.setAccessibilityHeading(true);
    label.setMaxLines(2);
    add(header, label, -1, -2);
    gap(header, 12);
    line(header);
    super.setCustomTitle(header);
    return this;
  }

  @Override
  public AlertDialog create() {
    // AlertController otherwise suppresses the list when both a message and items are supplied.
    if (hasChoices && summary != null && titleHeader != null) {
      super.setMessage(null);
      android.widget.ScrollView preview = new android.widget.ScrollView(activity);
      TextView description = text(activity, summary.toString(), 14, MUTED);
      description.setLineSpacing(dp(activity, 3), 1f);
      description.setPadding(0, dp(activity, 10), 0, dp(activity, 8));
      preview.addView(description);
      add(titleHeader, preview, -1, summary.length() > 100 ? 112 : 64);
    }
    AlertDialog dialog = super.create();
    dialog.setOnShowListener(d -> style(dialog));
    return dialog;
  }

  private void style(AlertDialog dialog) {
    Window window = dialog.getWindow();
    if (window == null) return;
    int widthDp = activity.getResources().getConfiguration().screenWidthDp;
    int heightDp = activity.getResources().getConfiguration().screenHeightDp;
    int width = dp(activity, Math.min(panelWidth, Math.max(280, widthDp - 96)));
    window.setBackgroundDrawable(panel(activity, SURFACE, 0xff3b423c));
    window.setDimAmount(.32f);
    window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
    window.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
    WindowManager.LayoutParams params = window.getAttributes();
    params.x = dp(activity, Math.min(48, Math.max(16, (widthDp - panelWidth) / 2)));
    window.setAttributes(params);
    ListView list = dialog.getListView();
    int height = ViewGroup.LayoutParams.WRAP_CONTENT;
    if (list != null) {
      int count = list.getAdapter().getCount();
      height =
          dp(
              activity,
              Math.min(
                  heightDp - 80,
                  116 + count * 52 + (summary == null ? 0 : summary.length() > 100 ? 112 : 64)));
      ListAdapter original = list.getAdapter();
      int checked = list.getCheckedItemPosition();
      list.setAdapter(
          new BaseAdapter() {
            @Override
            public int getCount() {
              return original.getCount();
            }

            @Override
            public Object getItem(int position) {
              return original.getItem(position);
            }

            @Override
            public long getItemId(int position) {
              return original.getItemId(position);
            }

            @Override
            public boolean areAllItemsEnabled() {
              return original.areAllItemsEnabled();
            }

            @Override
            public boolean isEnabled(int position) {
              return original.isEnabled(position);
            }

            @Override
            public View getView(int position, View recycled, ViewGroup parent) {
              View row = original.getView(position, recycled, parent);
              if (row instanceof TextView) {
                TextView text = (TextView) row;
                text.setTextSize(16);
                text.setTextColor(
                    new ColorStateList(
                        new int[][] {new int[] {android.R.attr.state_checked}, new int[] {}},
                        new int[] {ACCENT, TEXT}));
                text.setMinHeight(dp(activity, 48));
                text.setPadding(
                    dp(activity, 16), dp(activity, 12), dp(activity, 16), dp(activity, 12));
                text.setMaxLines(2);
                text.setBackgroundColor(Color.TRANSPARENT);
                if (text instanceof CheckedTextView)
                  ((CheckedTextView) text).setCheckMarkTintList(ColorStateList.valueOf(ACCENT));
              }
              return row;
            }
          });
      if (checked >= 0) list.setItemChecked(checked, true);
      list.setPadding(dp(activity, 16), dp(activity, 4), dp(activity, 16), dp(activity, 4));
      list.setClipToPadding(false);
      list.setDivider(new ColorDrawable(0xff303730));
      list.setDividerHeight(dp(activity, 1));
      list.setSelector(panel(activity, 0xff343a34, FOCUS));
      list.setDrawSelectorOnTop(false);
      list.setSelection(checked >= 0 ? checked : 0);
      list.requestFocus();
    }
    window.setLayout(width, height);
    TextView message = dialog.findViewById(android.R.id.message);
    if (message != null) {
      message.setTextColor(MUTED);
      message.setTextSize(14);
      message.setLineSpacing(dp(activity, 3), 1f);
    }
    for (int id :
        new int[] {
          AlertDialog.BUTTON_POSITIVE, AlertDialog.BUTTON_NEGATIVE, AlertDialog.BUTTON_NEUTRAL
        }) {
      TextView button = dialog.getButton(id);
      if (button == null) continue;
      boolean primary = id == AlertDialog.BUTTON_POSITIVE;
      button.setAllCaps(false);
      button.setTextSize(14);
      button.setMinHeight(dp(activity, 44));
      button.setPadding(dp(activity, 14), dp(activity, 10), dp(activity, 14), dp(activity, 10));
      button.setTextColor(primary ? 0xff241e14 : TEXT);
      button.setBackground(panel(activity, primary ? ACCENT : SURFACE, 0));
      button.setOnFocusChangeListener(
          (v, focused) ->
              v.setBackground(panel(activity, primary ? ACCENT : SURFACE, focused ? FOCUS : 0)));
    }
  }
}
