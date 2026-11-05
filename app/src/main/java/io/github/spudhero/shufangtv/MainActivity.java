package io.github.spudhero.shufangtv;

import static io.github.spudhero.shufangtv.ui.TvStyle.*;

import android.app.*;
import android.content.*;
import android.database.Cursor;
import android.net.Uri;
import android.os.*;
import android.provider.OpenableColumns;
import android.text.*;
import android.view.*;
import android.widget.*;
import io.github.spudhero.shufangtv.cloud.*;
import io.github.spudhero.shufangtv.data.*;
import io.github.spudhero.shufangtv.net.*;
import io.github.spudhero.shufangtv.reader.*;
import io.github.spudhero.shufangtv.ui.*;
import java.io.*;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** Independent native Android TV interface; no WebView and no embedded reference APK. */
public final class MainActivity extends Activity {
  private static final int OPEN_DOCUMENT = 31;
  private static final int EXPORT_NOTES = 32;
  private final ExecutorService io = Executors.newSingleThreadExecutor();
  private LibraryStore library;
  private SourceStore sources;
  private SharedPreferences preferences;
  private LinearLayout root, body;
  private ReaderScreen reader;
  private String route = "home", filter = "all", query = "", sourcePath = "", returnRoute = "home";
  private SourceConfig browsing;
  private boolean sortTitle;
  private String shelfGroup = "", shelfState = "", shelfFocus = "", markFocus = "", markKind = "";
  private volatile int operation;
  private volatile boolean destroyed;
  private NetworkLibrary network;
  private CloudLibrary clouds;
  private CloudLoginDialog cloudLogin;
  private final Deque<CloudLocation> cloudParents = new ArrayDeque<>();
  private final Deque<String> cloudBrowserParents = new ArrayDeque<>();
  private final Map<String, String> cloudFolderNames = new HashMap<>();
  private CloudAccount pickingAccount;
  private String pickingFolder = "", pickingLabel = "", pickingCursor = "";
  private AlertDialog working;

  @Override
  public void onCreate(Bundle state) {
    super.onCreate(state);
    getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
    getWindow()
        .getDecorView()
        .setSystemUiVisibility(
            View.SYSTEM_UI_FLAG_FULLSCREEN
                | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION);
    library = new LibraryStore(this);
    sources = new SourceStore(this);
    clouds = CloudLibrary.get(this);
    network = new NetworkLibrary(clouds);
    preferences = getSharedPreferences("reading", MODE_PRIVATE);
    if (Build.VERSION.SDK_INT >= 33)
      getOnBackInvokedDispatcher()
          .registerOnBackInvokedCallback(
              android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, this::goBack);
    showHome();
    if (library.loadError != null) message(library.loadError);
    if (sources.loadError != null) message(sources.loadError);
  }

  private void shell(String page, String title, String subtitle) {
    route = page;
    root = horizontal(this);
    root.setGravity(Gravity.TOP);
    root.setBackgroundColor(BG);
    LinearLayout side = vertical(this);
    side.setPadding(dp(this, 28), dp(this, 34), dp(this, 18), dp(this, 22));
    side.setBackgroundColor(SIDEBAR);
    add(side, heading(this, "书房 TV", 18), -1, 26);
    gap(side, 4);
    add(side, text(this, "一卷在手 · 片刻清宁", 10, MUTED), -1, 24);
    gap(side, 26);
    String[][] nav = {
      {"home", "首页"},
      {"library", "我的书库"},
      {"bookmarks", "我的书签"},
      {"sources", "书籍来源"},
      {"settings", "设置"}
    };
    for (String[] item : nav) {
      TextView button = button(this, item[1], false, () -> navigate(item[0]));
      button.setGravity(Gravity.CENTER_VERTICAL);
      button.setTag("nav-" + item[0]);
      if (page.equals(item[0])) {
        button.setTextColor(ACCENT);
        button.setBackground(panel(this, 0xff2a2e25, 0));
      }
      add(side, button, -1, 42);
      gap(side, 5);
    }
    View spacer = new View(this);
    side.addView(spacer, new LinearLayout.LayoutParams(1, 0, 1));
    add(side, text(this, "开源 · 无广告", 10, MUTED), -1, 24);
    root.addView(side, new LinearLayout.LayoutParams(dp(this, 146), -1));
    LinearLayout main = vertical(this);
    main.setPadding(dp(this, 28), dp(this, 30), dp(this, 32), dp(this, 14));
    root.addView(main, new LinearLayout.LayoutParams(0, -1, 1));
    LinearLayout head = horizontal(this);
    LinearLayout labels = vertical(this);
    add(labels, heading(this, title, 26), -1, 35);
    gap(labels, 5);
    add(labels, text(this, subtitle, 12, MUTED), -1, 25);
    head.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
    TextView time =
        text(
            this,
            new SimpleDateFormat("HH:mm\nMM月dd日 EEEE", Locale.SIMPLIFIED_CHINESE)
                .format(new Date()),
            12,
            MUTED);
    time.setGravity(Gravity.END);
    add(head, time, 126, 58);
    add(main, head, -1, 70);
    ScrollView scroll = new ScrollView(this);
    scroll.setFillViewport(true);
    scroll.setClipToPadding(false);
    scroll.setPadding(dp(this, 4), dp(this, 6), dp(this, 4), dp(this, 8));
    scroll.setFocusable(false);
    body = vertical(this);
    scroll.addView(body, new ScrollView.LayoutParams(-1, -2));
    main.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
    TextView hint = text(this, "方向键  移动焦点     OK  确定     返回  上一级", 10, MUTED);
    hint.setPadding(0, dp(this, 6), 0, 0);
    add(main, hint, -1, 24);
    setContentView(root);
  }

  private void navigate(String value) {
    switch (value) {
      case "library":
        showLibrary();
        break;
      case "bookmarks":
        showBookmarks();
        break;
      case "sources":
        showSources();
        break;
      case "settings":
        showSettings();
        break;
      default:
        showHome();
    }
  }

  private void focus(View view) {
    view.post(view::requestFocus);
  }

  private TextView action(LinearLayout row, String label, boolean primary, Runnable run) {
    TextView b = button(this, label, primary, run);
    add(row, b, -2, 40);
    gap(row, 10);
    return b;
  }

  private void showHome() {
    shell("home", "接着，读下去。", "留一点时间，给书里的世界。");
    List<BookRecord> recent = library.recent();
    if (recent.isEmpty()) {
      LinearLayout welcome = vertical(this);
      welcome.setPadding(dp(this, 28), dp(this, 25), dp(this, 28), dp(this, 25));
      welcome.setBackground(panel(this, SURFACE, 0));
      add(welcome, heading(this, "把你的书，带到客厅。", 24), -1, 42);
      gap(welcome, 12);
      TextView description =
          text(this, "在线打开家庭书库中的书，或直接阅读电视和 U 盘里的文件。\n需要随时离线读，再单独选择保存到本地。", 14, MUTED);
      description.setLineSpacing(dp(this, 5), 1);
      add(welcome, description, -1, -2);
      gap(welcome, 24);
      LinearLayout actions = horizontal(this);
      TextView primary =
          action(actions, "添加家庭书库", true, () -> sourceForm(null, SourceConfig.Type.SMB));
      action(actions, "打开本地 / USB 文件", false, this::chooseLocal);
      add(welcome, actions, -1, 44);
      add(body, welcome, -1, -2);
      focus(primary);
      gap(body, 24);
      TextView sample = button(this, "先读一篇使用指南", false, this::openGuide);
      add(body, sample, 210, 40);
      return;
    }
    BookRecord current = recent.get(0);
    LinearLayout hero = horizontal(this);
    hero.setPadding(dp(this, 4), dp(this, 8), 0, dp(this, 20));
    add(hero, new CoverView(this, current.title, current.author), 122, 171);
    gap(hero, 32);
    LinearLayout info = vertical(this);
    add(info, text(this, "正在阅读 · " + modeLabel(current.lastMode), 12, ACCENT), -1, 25);
    add(info, heading(this, current.title, 30), -1, 48);
    gap(info, 8);
    add(
        info,
        text(
            this,
            current.author.isEmpty() ? current.format.toUpperCase(Locale.ROOT) : current.author,
            14,
            MUTED),
        -1,
        26);
    gap(info, 7);
    add(info, text(this, positionLabel(current), 12, MUTED), -1, 26);
    gap(info, 10);
    TextView resume = button(this, "继续阅读", true, () -> openPreferred(current));
    add(info, resume, 150, 40);
    hero.addView(info, new LinearLayout.LayoutParams(0, -2, 1));
    add(body, hero, -1, -2);
    line(body);
    gap(body, 16);
    LinearLayout title = horizontal(this);
    TextView recentTitle = text(this, "最近阅读", 17, TEXT);
    title.addView(recentTitle, new LinearLayout.LayoutParams(0, 34, 1));
    action(title, "查看书库", false, this::showLibrary);
    add(body, title, -1, 42);
    gap(body, 10);
    addBookGrid(recent.subList(0, Math.min(5, recent.size())), 5);
    focus(resume);
  }

  private String positionLabel(BookRecord b) {
    if (b.lastRead == 0) return "尚未开始";
    if (b.format.equals("pdf")) return "读到第 " + (b.pdfPage + 1) + " 页";
    if (b.format.equals("txt")) return "已保存正文位置";
    return "读到第 " + (b.chapter + 1) + " 章";
  }

  private String modeLabel(String mode) {
    return "online".equals(mode) ? "在线阅读" : "离线阅读";
  }

  private void showLibrary() {
    shell("library", "我的书库", library.books.size() + " 本书 · 在线与离线分别打开");
    LinearLayout toolbar = horizontal(this);
    TextView group =
        action(toolbar, shelfGroup.isEmpty() ? "全部书单" : shelfGroup, false, this::chooseShelfGroup);
    group.setTag("shelf-group");
    TextView source =
        action(
            toolbar,
            filter.equals("all") ? "全部来源" : filter.equals("online") ? "在线书籍" : "本地 / 离线",
            false,
            this::chooseShelfSource);
    source.setTag("shelf-source");
    View space = new View(this);
    toolbar.addView(space, new LinearLayout.LayoutParams(0, 1, 1));
    action(toolbar, query.isEmpty() ? "搜索" : query, false, this::search);
    action(
        toolbar,
        sortTitle ? "按书名" : "最近阅读",
        false,
        () -> {
          sortTitle = !sortTitle;
          showLibrary();
        });
    add(body, toolbar, -1, 48);
    gap(body, 8);
    LinearLayout states = horizontal(this);
    String[] ids = {"", "unread", "reading", "finished"};
    String[] labels = {"全部", "待读", "在读", "已读"};
    for (int i = 0; i < ids.length; i++) {
      final String state = ids[i];
      TextView tab =
          ReadingPanel.option(
              this,
              labels[i],
              shelfState.equals(state),
              () -> {
                shelfState = state;
                shelfFocus = "shelf-state-" + state;
                showLibrary();
              });
      tab.setTag("shelf-state-" + state);
      add(states, tab, 88, 44);
      gap(states, 8);
    }
    add(body, states, -1, 44);
    gap(body, 16);
    List<BookRecord> books = new ArrayList<>();
    for (BookRecord b : library.shelf(sortTitle)) {
      if (!shelfGroup.isEmpty() && !shelfGroup.equals(b.group)) continue;
      if (!shelfState.isEmpty() && !shelfState.equals(b.readState)) continue;
      if (filter.equals("online") && !b.hasOnline() || filter.equals("offline") && !b.hasOffline())
        continue;
      if (!query.isEmpty()
          && !(b.title + " " + b.author)
              .toLowerCase(Locale.ROOT)
              .contains(query.toLowerCase(Locale.ROOT))) continue;
      books.add(b);
    }
    if (books.isEmpty()) {
      gap(body, 24);
      boolean filtered =
          !query.isEmpty()
              || !shelfGroup.isEmpty()
              || !shelfState.isEmpty()
              || !filter.equals("all");
      add(body, heading(this, filtered ? "没有符合条件的书" : "书架还留着位置", 21), -1, 40);
      add(body, text(this, filtered ? "可以清除筛选，查看所有书籍。" : "可以打开本地文件，或连接家庭书库。", 13, MUTED), -1, 30);
      TextView reset =
          button(
              this,
              filtered ? "清除筛选" : "添加书籍",
              true,
              () -> {
                if (filtered) {
                  query = "";
                  shelfGroup = "";
                  shelfState = "";
                  filter = "all";
                  showLibrary();
                } else showSources();
              });
      add(body, reset, 150, 40);
      focus(reset);
    } else {
      addBookGrid(books, 4);
      View restore = root.findViewWithTag(shelfFocus);
      focus(restore != null ? restore : root.findViewWithTag("book-" + books.get(0).id));
    }
  }

  private void addBookGrid(List<BookRecord> books, int columns) {
    for (int start = 0; start < books.size(); start += columns) {
      LinearLayout row = horizontal(this);
      row.setGravity(Gravity.TOP);
      for (int index = start; index < Math.min(books.size(), start + columns); index++) {
        BookRecord book = books.get(index);
        LinearLayout card = vertical(this);
        card.setGravity(Gravity.CENTER_HORIZONTAL);
        card.setPadding(dp(this, 6), dp(this, 6), dp(this, 6), dp(this, 8));
        card.setFocusable(true);
        card.setFocusableInTouchMode(true);
        card.setClickable(true);
        card.setTag("book-" + book.id);
        card.setContentDescription(
            book.title
                + "，"
                + BookOrganization.stateLabel(book)
                + (book.pinned ? "，已置顶" : "")
                + "，"
                + availability(book));
        card.setBackground(panel(this, BG, 0));
        card.setOnFocusChangeListener(
            (v, focused) ->
                v.setBackground(panel(this, focused ? SURFACE : BG, focused ? FOCUS : 0)));
        card.setOnClickListener(v -> bookActions(book));
        add(card, new CoverView(this, book.title, book.author), 96, 134);
        gap(card, 8);
        TextView title = text(this, book.title, 14, TEXT);
        title.setMaxLines(2);
        title.setEllipsize(TextUtils.TruncateAt.END);
        title.setGravity(Gravity.CENTER);
        add(card, title, -1, 38);
        TextView source =
            text(
                this, availability(book) + " · " + book.format.toUpperCase(Locale.ROOT), 11, MUTED);
        source.setMaxLines(1);
        source.setEllipsize(TextUtils.TruncateAt.END);
        source.setGravity(Gravity.CENTER);
        add(card, source, -1, 24);
        TextView state =
            text(
                this,
                BookOrganization.stateLabel(book),
                12,
                book.readState.equals("reading") ? ACCENT : MUTED);
        state.setGravity(Gravity.CENTER);
        add(card, state, -1, 22);
        if (book.pinned) {
          TextView pin = text(this, "置顶", 10, ACCENT);
          pin.setGravity(Gravity.CENTER);
          add(card, pin, -1, 18);
        }
        row.addView(card, new LinearLayout.LayoutParams(0, -2, 1));
      }
      for (int i = books.size() - start; i < columns; i++)
        row.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
      add(body, row, -1, -2);
      gap(body, 12);
    }
  }

  private String availability(BookRecord b) {
    return b.hasOnline() && b.hasOffline() ? "在线 · 已存本地" : b.hasOnline() ? "在线书籍" : "本地 / 离线";
  }

  private void chooseShelfGroup() {
    List<String> names = library.groups();
    List<String> labels = new ArrayList<>();
    labels.add("全部书单");
    labels.addAll(names);
    shelfFocus = "shelf-group";
    new ReadingDialog(this)
        .setTitle("按书单查看")
        .setItems(
            labels.toArray(new String[0]),
            (d, i) -> {
              shelfGroup = i == 0 ? "" : names.get(i - 1);
              showLibrary();
            })
        .setNegativeButton("返回", null)
        .show();
  }

  private void chooseShelfSource() {
    shelfFocus = "shelf-source";
    new ReadingDialog(this)
        .setTitle("书籍来源")
        .setSingleChoiceItems(
            new String[] {"全部来源", "在线书籍", "本地 / 离线"},
            filter.equals("all") ? 0 : filter.equals("online") ? 1 : 2,
            (d, i) -> {
              filter = new String[] {"all", "online", "offline"}[i];
              d.dismiss();
              showLibrary();
            })
        .setNegativeButton("返回", null)
        .show();
  }

  private void search() {
    EditText field = new EditText(this);
    field.setSingleLine();
    field.setText(query);
    new ReadingDialog(this)
        .setTitle("搜索书名或作者")
        .setView(field)
        .setPositiveButton(
            "搜索",
            (d, w) -> {
              query = field.getText().toString().trim();
              showLibrary();
            })
        .setNeutralButton(
            "清除",
            (d, w) -> {
              query = "";
              showLibrary();
            })
        .setNegativeButton("取消", null)
        .show();
  }

  private void bookActions(BookRecord b) {
    shelfFocus = "book-" + b.id;
    List<String> options = new ArrayList<>();
    if (b.hasOnline()) options.add("在线打开");
    if (b.hasOffline()) options.add("离线打开");
    if (b.hasOnline() && !b.hasOffline()) options.add("保存到本地");
    if (!b.offlineFile.isEmpty() && b.hasOnline()) options.add("删除离线副本");
    options.add(b.pinned ? "取消置顶" : "置顶");
    options.add("加入书单");
    options.add("阅读状态");
    options.add("从书库移除");
    new ReadingDialog(this)
        .setTitle(b.title)
        .setItems(
            options.toArray(new String[0]),
            (d, which) -> {
              String option = options.get(which);
              switch (option) {
                case "在线打开":
                  openBook(b, true);
                  break;
                case "离线打开":
                  openBook(b, false);
                  break;
                case "保存到本地":
                  download(b);
                  break;
                case "删除离线副本":
                  removeOffline(b);
                  break;
                case "置顶":
                case "取消置顶":
                  new BookOrganization(this, library, this::showLibrary).pin(b);
                  break;
                case "加入书单":
                  new BookOrganization(this, library, this::showLibrary).group(b);
                  break;
                case "阅读状态":
                  new BookOrganization(this, library, this::showLibrary).state(b);
                  break;
                default:
                  removeBook(b);
              }
            })
        .show();
  }

  private void openPreferred(BookRecord b) {
    boolean online = "online".equals(b.lastMode) && b.hasOnline() || !b.hasOffline();
    openBook(b, online);
  }

  private void openBook(BookRecord book, boolean online) {
    openBook(book, online, null);
  }

  private void openBook(BookRecord book, boolean online, LibraryStore.Mark mark) {
    if (!online && !book.hasOffline()) {
      message("这本书还没有离线副本，请选择在线打开或保存到本地。");
      return;
    }
    String previous = route.equals("reader") ? returnRoute : route;
    job(
        online ? "正在在线打开" : "正在打开本地书籍",
        () -> BookSession.open(this, book, online, library, sources),
        session -> {
          if (!session.title.isEmpty()) book.title = session.title;
          if (!session.author.isEmpty()) book.author = session.author;
          book.lastMode = online ? "online" : "offline";
          book.lastRead = System.currentTimeMillis();
          returnRoute = previous;
          route = "reader";
          reader = new ReaderScreen(this, io, library, preferences, session, this::exitReader);
          if (mark != null) reader.showMark(mark);
          setContentView(reader);
          save();
        });
  }

  private void exitReader() {
    if (reader != null) {
      reader.dispose();
      reader = null;
    }
    if (returnRoute.equals("browser") && browsing != null) browse(browsing, sourcePath);
    else navigate(returnRoute);
  }

  private void showBookmarks() {
    shell("bookmarks", "我的书签", library.marks.size() + " 处书签与段落收藏，随时重读。");
    if (library.marks.isEmpty()) {
      gap(body, 30);
      add(body, heading(this, "留住让你停下来的文字", 23), -1, 44);
      add(body, text(this, "阅读时按确定键，在菜单里添加书签或收藏段落。", 14, MUTED), -1, 36);
      focus(root.findViewWithTag("nav-bookmarks"));
      return;
    }
    LinearLayout toolbar = horizontal(this);
    String[] kinds = {"", "position", "note"};
    String[] labels = {"全部", "位置书签", "段落收藏"};
    for (int i = 0; i < kinds.length; i++) {
      final String kind = kinds[i];
      TextView tab =
          ReadingPanel.option(
              this,
              labels[i],
              kind.equals(markKind),
              () -> {
                markKind = kind;
                markFocus = "mark-kind-" + kind;
                showBookmarks();
              });
      tab.setTag("mark-kind-" + kind);
      add(toolbar, tab, 104, 44);
      gap(toolbar, 8);
    }
    toolbar.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1));
    action(
        toolbar,
        "导出收藏",
        false,
        () ->
            new ReadingDialog(this)
                .setTitle("导出书签与收藏")
                .setMessage("包含已保存的摘录和备注。")
                .setItems(new String[] {"文本", "Markdown"}, (d, i) -> exportNotes(i == 1))
                .setNegativeButton("返回", null)
                .show());
    add(body, toolbar, -1, 44);
    gap(body, 12);
    int count = 0;
    for (LibraryStore.Mark mark : new ArrayList<>(library.marks)) {
      BookRecord book = library.find(mark.bookId);
      if (book == null
          || markKind.equals("note") && !mark.isNote()
          || markKind.equals("position") && mark.isNote()) continue;
      count++;
      LinearLayout card = vertical(this);
      card.setTag("mark-" + mark.id);
      card.setPadding(dp(this, 16), dp(this, 14), dp(this, 16), dp(this, 14));
      card.setFocusable(true);
      card.setFocusableInTouchMode(true);
      card.setClickable(true);
      card.setBackground(panel(this, SURFACE, 0));
      card.setOnFocusChangeListener(
          (view, focused) ->
              view.setBackground(panel(this, focused ? 0xff45483a : SURFACE, focused ? FOCUS : 0)));
      card.setOnClickListener(view -> markActions(book, mark));
      card.setContentDescription(
          book.title
              + "，"
              + (mark.isNote() ? "段落收藏" : "位置书签")
              + (mark.note.isEmpty() ? "" : "，备注：" + mark.note));
      TextView title = text(this, (mark.isNote() ? "段落收藏 · " : "位置书签 · ") + book.title, 13, ACCENT);
      title.setMaxLines(1);
      title.setEllipsize(TextUtils.TruncateAt.END);
      add(card, title, -1, 22);
      TextView excerpt =
          heading(
              this, mark.excerpt.isEmpty() ? "第 " + (mark.chapter + 1) + " 章" : mark.excerpt, 16);
      excerpt.setMaxLines(2);
      excerpt.setEllipsize(TextUtils.TruncateAt.END);
      excerpt.setLineSpacing(dp(this, 3), 1f);
      add(card, excerpt, -1, 48);
      if (!mark.note.isEmpty()) {
        TextView note = text(this, "备注：" + mark.note, 13, MUTED);
        note.setMaxLines(1);
        note.setEllipsize(TextUtils.TruncateAt.END);
        add(card, note, -1, 22);
      }
      add(body, card, -1, 124);
      gap(body, 12);
    }
    if (count == 0) add(body, text(this, "这里还没有记录，阅读时可随手保存。", 14, MUTED), -1, 48);
    View restore = body.findViewWithTag(markFocus);
    focus(restore != null ? restore : toolbar.getChildAt(0));
  }

  private void markActions(BookRecord book, LibraryStore.Mark mark) {
    markFocus = "mark-" + mark.id;
    new ReadingDialog(this)
        .width(480)
        .setTitle(mark.isNote() ? "段落收藏" : "位置书签")
        .setMessage(
            book.title + "\n\n" + mark.excerpt + (mark.note.isEmpty() ? "" : "\n\n备注：" + mark.note))
        .setItems(
            new String[] {"回到原文", "编辑备注", "移除"},
            (d, i) -> {
              if (i == 0) {
                book.chapter = mark.chapter;
                book.offset = mark.offset;
                book.pdfPage = mark.pdfPage;
                book.txtByte = mark.txtByte;
                openBook(book, mark.mode.equals("online") && book.hasOnline(), mark);
              } else if (i == 1) NoteEditor.edit(this, library, mark, this::showBookmarks);
              else
                new ReadingDialog(this)
                    .setTitle("移除此书签？")
                    .setMessage("书籍和阅读位置会保留。")
                    .setPositiveButton(
                        "移除",
                        (confirm, w) -> {
                          try {
                            library.removeMark(mark);
                            showBookmarks();
                          } catch (IOException e) {
                            message("书签未能移除，请稍后重试");
                          }
                        })
                    .setNegativeButton("取消", null)
                    .show();
            })
        .setNegativeButton("返回", null)
        .show();
  }

  private File noteExportFile() {
    return new File(getCacheDir(), "pending-notes-export.txt");
  }

  private void exportNotes(boolean markdown) {
    String snapshot = NoteExport.render(library, markdown);
    job(
        "准备笔记导出",
        () -> {
          File file = noteExportFile();
          try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(snapshot.getBytes(java.nio.charset.StandardCharsets.UTF_8));
          }
          return file;
        },
        file -> {
          Intent intent =
              new Intent(Intent.ACTION_CREATE_DOCUMENT)
                  .addCategory(Intent.CATEGORY_OPENABLE)
                  .setType(markdown ? "text/markdown" : "text/plain")
                  .putExtra(Intent.EXTRA_TITLE, "书房TV-书签与笔记." + (markdown ? "md" : "txt"));
          try {
            startActivityForResult(intent, EXPORT_NOTES);
          } catch (ActivityNotFoundException e) {
            file.delete();
            message("系统未提供文件保存界面，请启用系统文件应用后再试");
          }
        });
  }

  private void showSources() {
    shell("sources", "书籍来源", "网盘扫码直连，选好文件夹就能读。离线保存由你决定。");
    add(body, heading(this, "连接网盘", 18), -1, 32);
    LinearLayout cloudButtons = horizontal(this);
    for (CloudProvider provider : clouds.providers()) {
      TextView login = action(cloudButtons, provider.label(), false, () -> loginCloud(provider));
      login.setTag("cloud-login-" + provider.id());
    }
    add(body, cloudButtons, -1, 44);
    gap(body, 12);
    for (CloudAccount account : clouds.accounts()) {
      LinearLayout accountCard =
          sourcePanel(account.label, cloudLabel(account.providerId) + " · 已连接 · 可添加多个书籍文件夹");
      LinearLayout accountActions = horizontal(this);
      action(accountActions, "选择书籍文件夹", true, () -> chooseCloudFolder(account));
      action(
          accountActions,
          "重新扫码",
          false,
          () -> {
            try {
              loginCloud(clouds.provider(account.providerId));
            } catch (IOException e) {
              message(e.getMessage());
            }
          });
      action(
          accountActions,
          "退出账号",
          false,
          () ->
              new AlertDialog.Builder(this)
                  .setTitle("退出此网盘账号？")
                  .setMessage("此账号的在线来源将暂不可用，已保存的本地书籍仍可离线阅读。")
                  .setNegativeButton("取消", null)
                  .setPositiveButton(
                      "退出",
                      (d, w) ->
                          job(
                              "正在退出网盘",
                              () -> {
                                clouds.logout(account.id);
                                return true;
                              },
                              ok -> showSources()))
                  .show());
      add(accountCard, accountActions, -1, 42);
      add(body, accountCard, -1, -2);
      gap(body, 12);
    }
    LinearLayout local = sourcePanel("电视本地 / USB", "直接打开原文件，不在电视上再复制一份。");
    TextView localButton = button(this, "打开文件", true, this::chooseLocal);
    add(local, localButton, 140, 40);
    add(body, local, -1, -2);
    gap(body, 12);
    for (SourceConfig source : sources.sources) {
      LinearLayout card =
          sourcePanel(
              source.name,
              source.type == SourceConfig.Type.CLOUD
                  ? cloudLabel(source.providerId) + " · " + source.rootLabel
                  : source.type == SourceConfig.Type.SMB ? "SMB 家庭共享" : "WebDAV 家庭书库");
      LinearLayout actions = horizontal(this);
      action(actions, "浏览书籍", false, () -> browse(source, ""));
      if (source.type != SourceConfig.Type.CLOUD)
        action(actions, "编辑", false, () -> sourceForm(source, source.type));
      action(
          actions,
          "移除连接",
          false,
          () ->
              new AlertDialog.Builder(this)
                  .setTitle("移除此书库连接？")
                  .setMessage("已保存到本地的书仍可离线阅读。")
                  .setPositiveButton(
                      "移除",
                      (d, w) -> {
                        sources.sources.remove(source);
                        saveSources();
                        showSources();
                      })
                  .setNegativeButton("取消", null)
                  .show());
      add(card, actions, -1, 42);
      add(body, card, -1, -2);
      gap(body, 12);
    }
    LinearLayout add = horizontal(this);
    action(add, "添加 SMB 家庭书库", false, () -> sourceForm(null, SourceConfig.Type.SMB));
    action(add, "添加 WebDAV 家庭书库", false, () -> sourceForm(null, SourceConfig.Type.WEBDAV));
    add(body, add, -1, 44);
    focus(cloudButtons.getChildAt(0));
  }

  private void loginCloud(CloudProvider provider) {
    if (cloudLogin != null) cloudLogin.close();
    cloudLogin =
        new CloudLoginDialog(
            this,
            provider,
            io,
            account ->
                job(
                    "正在保存网盘授权",
                    () -> {
                      clouds.save(account);
                      return account;
                    },
                    this::chooseCloudFolder));
    cloudLogin.show();
  }

  private String cloudLabel(String providerId) {
    for (CloudProvider provider : clouds.providers())
      if (provider.id().equals(providerId)) return provider.label();
    return "网盘";
  }

  private static final class CloudLocation {
    final String id, label, cursor;

    CloudLocation(String id, String label, String cursor) {
      this.id = id;
      this.label = label;
      this.cursor = cursor;
    }
  }

  private void chooseCloudFolder(CloudAccount account) {
    pickingAccount = account;
    cloudParents.clear();
    try {
      CloudProvider provider = clouds.provider(account.providerId);
      loadCloudFolder(provider.rootId(account), provider.label() + " · 根目录", "");
    } catch (IOException e) {
      message(e.getMessage());
    }
  }

  private void loadCloudFolder(String folderId, String label, String cursor) {
    loadCloudFolder(folderId, label, cursor, new ArrayDeque<>(cloudParents));
  }

  private void loadCloudFolder(
      String folderId, String label, String cursor, Deque<CloudLocation> nextParents) {
    CloudAccount account = pickingAccount;
    if (account == null) return;
    job(
        "正在加载网盘文件夹",
        () -> clouds.page(account, folderId, cursor, true),
        page -> {
          cloudParents.clear();
          cloudParents.addAll(nextParents);
          pickingFolder = folderId;
          pickingLabel = label;
          pickingCursor = cursor;
          shell("cloud-folder", "选择书籍文件夹", account.label + " · " + label);
          LinearLayout tools = horizontal(this);
          TextView select = action(tools, "将此文件夹设为书籍来源", true, this::saveCloudFolder);
          select.setTag("cloud-folder-save");
          if (!cloudParents.isEmpty())
            action(
                tools,
                "上一级",
                false,
                () -> {
                  Deque<CloudLocation> parents = new ArrayDeque<>(cloudParents);
                  CloudLocation parent = parents.pop();
                  loadCloudFolder(parent.id, parent.label, parent.cursor, parents);
                });
          action(tools, "取消", false, this::showSources);
          add(body, tools, -1, 44);
          gap(body, 12);
          add(body, text(this, "只保存文件夹位置。打开书籍时按需读取，不会自动下载整个文件夹。", 13, MUTED), -1, 36);
          gap(body, 10);
          int folders = 0;
          for (CloudProvider.Entry entry : page.entries) {
            if (!entry.directory) continue;
            folders++;
            TextView item =
                button(
                    this,
                    "▤  " + entry.name + "    〉",
                    false,
                    () -> {
                      Deque<CloudLocation> parents = new ArrayDeque<>(cloudParents);
                      parents.push(new CloudLocation(folderId, label, cursor));
                      loadCloudFolder(entry.id, entry.name, "", parents);
                    });
            item.setGravity(Gravity.CENTER_VERTICAL);
            item.setSingleLine(true);
            item.setEllipsize(TextUtils.TruncateAt.END);
            item.setTag("cloud-folder-" + entry.id);
            add(body, item, -1, 44);
            gap(body, 8);
          }
          if (folders == 0) add(body, text(this, "这里没有子文件夹，可以直接选择当前文件夹。", 14, MUTED), -1, 48);
          if (page.nextPageToken != null && !page.nextPageToken.isEmpty()) {
            TextView next =
                button(
                    this,
                    "下一页文件夹  〉",
                    false,
                    () -> loadCloudFolder(folderId, label, page.nextPageToken));
            add(body, next, -1, 44);
          }
          if (!cursor.isEmpty())
            add(
                body,
                button(this, "返回第一页", false, () -> loadCloudFolder(folderId, label, "")),
                -1,
                44);
          focus(select);
        });
  }

  private void saveCloudFolder() {
    CloudAccount account = pickingAccount;
    if (account == null) return;
    if (pickingFolder.startsWith("@")) {
      message("请先进入资源库或备份盘，再选择书籍文件夹");
      return;
    }
    for (SourceConfig existing : sources.sources)
      if (existing.type == SourceConfig.Type.CLOUD
          && existing.accountId.equals(account.id)
          && existing.root.equals(pickingFolder)) {
        message("这个文件夹已经添加到书籍来源");
        showSources();
        return;
      }
    SourceConfig source = new SourceConfig();
    source.id = UUID.randomUUID().toString();
    source.type = SourceConfig.Type.CLOUD;
    source.providerId = account.providerId;
    source.accountId = account.id;
    source.root = pickingFolder;
    source.rootLabel = pickingLabel;
    source.name = account.label + " · " + pickingLabel;
    job(
        "正在保存书籍来源",
        () -> {
          sources.sources.add(source);
          try {
            sources.save();
          } catch (IOException e) {
            sources.sources.remove(source);
            throw e;
          }
          return source;
        },
        saved -> {
          showSources();
          message("已添加书籍来源，可以浏览并在线阅读");
        });
  }

  private LinearLayout sourcePanel(String title, String detail) {
    LinearLayout panel = vertical(this);
    panel.setPadding(dp(this, 20), dp(this, 16), dp(this, 20), dp(this, 16));
    panel.setBackground(panel(this, SURFACE, 0));
    add(panel, text(this, title, 18, TEXT), -1, 32);
    add(panel, text(this, detail, 12, MUTED), -1, 32);
    return panel;
  }

  private EditText formField(
      LinearLayout form, String label, String value, String hint, boolean password) {
    add(form, text(this, label, 13, MUTED), -1, 24);
    EditText edit = new EditText(this);
    edit.setText(value == null ? "" : value);
    edit.setHint(hint);
    edit.setTextSize(14);
    edit.setSingleLine(true);
    edit.setInputType(
        password
            ? android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            : android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
    add(form, edit, -1, 42);
    gap(form, 8);
    return edit;
  }

  private void sourceForm(SourceConfig original, SourceConfig.Type type) {
    LinearLayout form = vertical(this);
    form.setPadding(dp(this, 24), dp(this, 8), dp(this, 24), dp(this, 16));
    ScrollView scroll = new ScrollView(this);
    scroll.addView(form);
    EditText name =
        formField(
            form,
            "书库名称",
            original == null ? type == SourceConfig.Type.SMB ? "家庭书库" : "WebDAV 书库" : original.name,
            "方便辨认的名称",
            false);
    EditText host =
        formField(
            form,
            type == SourceConfig.Type.SMB ? "服务器地址" : "服务地址（http:// 或 https://）",
            original == null ? "" : original.host,
            type == SourceConfig.Type.SMB ? "例如 nas.local" : "例如 https://dav.example.com",
            false);
    EditText port = null, share = null;
    if (type == SourceConfig.Type.SMB) {
      port =
          formField(
              form,
              "端口",
              original == null ? "445" : Integer.toString(original.port == 0 ? 445 : original.port),
              "445",
              false);
      port.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);
      share = formField(form, "共享名称", original == null ? "" : original.share, "例如 家庭共享", false);
    }
    EditText rootPath =
        formField(
            form,
            "书库目录（可选）",
            original == null ? "" : original.root,
            type == SourceConfig.Type.SMB ? "例如 书籍" : "例如 /dav/书籍",
            false);
    EditText username =
        formField(form, "用户名（可选）", original == null ? "" : original.username, "留空使用访客身份", false);
    EditText password =
        formField(form, "密码（可选）", original == null ? "" : original.password, "输入密码", true);
    EditText domain =
        type == SourceConfig.Type.SMB
            ? formField(form, "域（可选）", original == null ? "" : original.domain, "通常留空", false)
            : null;
    AlertDialog dialog =
        new AlertDialog.Builder(this)
            .setTitle(type == SourceConfig.Type.SMB ? "连接 SMB 家庭书库" : "连接 WebDAV 家庭书库")
            .setView(scroll)
            .setPositiveButton("保存并浏览", null)
            .setNegativeButton("取消", null)
            .create();
    EditText finalPort = port, finalShare = share;
    dialog.setOnShowListener(
        d -> {
          dialog
              .getButton(AlertDialog.BUTTON_POSITIVE)
              .setOnClickListener(
                  v -> {
                    if (host.getText().toString().trim().isEmpty()) {
                      host.setError("请输入服务器地址");
                      host.requestFocus();
                      return;
                    }
                    if (finalShare != null && finalShare.getText().toString().trim().isEmpty()) {
                      finalShare.setError("请输入共享名称");
                      finalShare.requestFocus();
                      return;
                    }
                    int portNumber = 0;
                    if (finalPort != null)
                      try {
                        portNumber = Integer.parseInt(finalPort.getText().toString());
                        if (portNumber < 1 || portNumber > 65535) throw new NumberFormatException();
                      } catch (NumberFormatException e) {
                        finalPort.setError("端口应在 1–65535 之间");
                        return;
                      }
                    SourceConfig config = new SourceConfig();
                    config.id = original == null ? UUID.randomUUID().toString() : original.id;
                    config.name = name.getText().toString().trim();
                    if (config.name.isEmpty())
                      config.name = type == SourceConfig.Type.SMB ? "家庭书库" : "WebDAV 书库";
                    config.type = type;
                    config.host = host.getText().toString().trim();
                    config.port = portNumber;
                    config.share = finalShare == null ? "" : finalShare.getText().toString().trim();
                    config.root = rootPath.getText().toString().trim();
                    config.username = username.getText().toString();
                    config.password = password.getText().toString();
                    config.domain = domain == null ? "" : domain.getText().toString();
                    config.https = config.host.startsWith("https://");
                    if (original != null) sources.sources.remove(original);
                    sources.sources.add(config);
                    try {
                      sources.save();
                      dialog.dismiss();
                      browse(config, "");
                    } catch (IOException e) {
                      sources.sources.remove(config);
                      if (original != null) sources.sources.add(original);
                      message(e.getMessage());
                    }
                  });
        });
    dialog.show();
  }

  private void browse(SourceConfig source, String path) {
    browse(
        source,
        path,
        () -> {
          if (source.type == SourceConfig.Type.CLOUD && path.isEmpty()) {
            cloudBrowserParents.clear();
            cloudFolderNames.clear();
          }
        });
  }

  private void browse(SourceConfig source, String path, Runnable commitNavigation) {
    job(
        "正在读取书库目录",
        () -> network.list(source, path),
        entries -> {
          browsing = source;
          sourcePath = path;
          commitNavigation.run();
          showRemote(source, path, entries);
        });
  }

  private void cloudBrowseUp(SourceConfig source) {
    Deque<String> parents = new ArrayDeque<>(cloudBrowserParents);
    String target = parents.isEmpty() ? "" : parents.pop();
    browse(
        source,
        target,
        () -> {
          cloudBrowserParents.clear();
          cloudBrowserParents.addAll(parents);
        });
  }

  private void showRemote(SourceConfig source, String path, List<RemoteEntry> entries) {
    boolean cloud = source.type == SourceConfig.Type.CLOUD;
    shell(
        "browser",
        source.name,
        path.isEmpty() ? "书库根目录" : cloud ? cloudFolderNames.getOrDefault(path, "书籍文件夹") : path);
    LinearLayout tools = horizontal(this);
    TextView refresh = action(tools, "刷新", false, () -> browse(source, path));
    if (!path.isEmpty())
      action(
          tools,
          "上一级",
          false,
          () -> {
            if (cloud) cloudBrowseUp(source);
            else browse(source, parent(path));
          });
    if (cloud) action(tools, "书籍来源", false, this::showSources);
    else action(tools, "连接设置", false, () -> sourceForm(source, source.type));
    add(body, tools, -1, 44);
    gap(body, 10);
    List<RemoteEntry> sorted = new ArrayList<>(entries);
    sorted.sort(Comparator.comparing((RemoteEntry e) -> !e.directory).thenComparing(e -> e.name));
    int count = 0;
    for (RemoteEntry entry : sorted) {
      if (!entry.directory && !BookRecord.supported(entry.name)) continue;
      count++;
      TextView item =
          button(
              this,
              (entry.directory ? "▤  " : "    ")
                  + entry.name
                  + (entry.directory ? "    〉" : "    " + bytes(entry.size)),
              false,
              () -> {
                if (entry.directory) {
                  if (cloud) {
                    Deque<String> parents = new ArrayDeque<>(cloudBrowserParents);
                    parents.push(path);
                    browse(
                        source,
                        entry.path,
                        () -> {
                          cloudBrowserParents.clear();
                          cloudBrowserParents.addAll(parents);
                          cloudFolderNames.put(entry.path, entry.name);
                        });
                  } else browse(source, entry.path);
                } else remoteActions(source, entry);
              });
      item.setGravity(Gravity.CENTER_VERTICAL);
      item.setSingleLine(true);
      item.setEllipsize(TextUtils.TruncateAt.END);
      item.setTag("remote-" + entry.path);
      add(body, item, -1, 44);
      gap(body, 8);
    }
    if (count == 0) {
      gap(body, 30);
      add(body, heading(this, "这个目录还没有可读的书", 21), -1, 40);
      add(body, text(this, "当前支持 EPUB、TXT 和 PDF。", 13, MUTED), -1, 34);
    }
    focus(refresh);
  }

  private String parent(String path) {
    int i = path.lastIndexOf('/');
    return i < 0 ? "" : path.substring(0, i);
  }

  private BookRecord recordFor(SourceConfig source, RemoteEntry entry) {
    BookRecord b = library.remote(source.id, entry.path);
    if (b == null) {
      b = new BookRecord();
      b.title = entry.name.replaceFirst("\\.[^.]+$", "");
      b.format = BookRecord.extension(entry.name);
      b.sourceId = source.id;
      b.remotePath = entry.path;
      b.size = entry.size;
      b.lastMode = "online";
      library.books.add(b);
      save();
    }
    return b;
  }

  private void remoteActions(SourceConfig source, RemoteEntry entry) {
    new AlertDialog.Builder(this)
        .setTitle(entry.name)
        .setItems(
            new String[] {"在线打开（不保存整本）", "保存到本地（" + bytes(entry.size) + "）"},
            (d, which) -> {
              BookRecord b = recordFor(source, entry);
              if (which == 0) openBook(b, true);
              else download(b);
            })
        .show();
  }

  private void download(BookRecord b) {
    SourceConfig source = sources.find(b.sourceId);
    if (source == null) {
      message("此书库连接已移除");
      return;
    }
    long free = library.booksDir.getUsableSpace();
    if (b.size > 128L * 1024 * 1024) {
      message("首版离线保存上限为 128 MiB，可选择在线阅读。");
      return;
    }
    if (b.size > 0 && free < b.size + 16L * 1024 * 1024) {
      message("本地空间不足。可以继续在线阅读，或先清理离线副本。");
      return;
    }
    new AlertDialog.Builder(this)
        .setTitle("保存到本地？")
        .setMessage(
            b.title + "\n书籍大小：" + bytes(b.size) + "\n剩余空间：" + bytes(free) + "\n完整保存后可在离线书库打开。")
        .setPositiveButton(
            "开始保存",
            (d, w) -> {
              final int token = operation + 1;
              job(
                  "正在保存到本地",
                  () -> {
                    network.download(
                        source,
                        b.remotePath,
                        library.file(b),
                        (done, total) -> {
                          if (Thread.currentThread().isInterrupted() || operation != token)
                            throw new IOException("保存已取消");
                          runOnUiThread(
                              () -> {
                                if (working != null && operation == token)
                                  working.setTitle(
                                      "正在保存  "
                                          + bytes(done)
                                          + (total > 0 ? " / " + bytes(total) : ""));
                              });
                        });
                    return b;
                  },
                  saved -> {
                    b.offlineFile = b.id + "." + b.format;
                    save();
                    new AlertDialog.Builder(this)
                        .setTitle("已保存到本地")
                        .setMessage(b.title)
                        .setPositiveButton("离线打开", (dlg, which) -> openBook(b, false))
                        .setNegativeButton("完成", (dlg, which) -> showLibrary())
                        .show();
                  });
            })
        .setNegativeButton("取消", null)
        .show();
  }

  private String bytes(long value) {
    if (value <= 0) return "大小未知";
    if (value >= 1024L * 1024 * 1024)
      return String.format(Locale.ROOT, "%.1f GiB", value / (1024.0 * 1024 * 1024));
    if (value >= 1024 * 1024)
      return String.format(Locale.ROOT, "%.1f MiB", value / (1024.0 * 1024));
    return String.format(Locale.ROOT, "%.0f KiB", value / 1024.0);
  }

  private void chooseLocal() {
    Intent intent =
        new Intent(Intent.ACTION_OPEN_DOCUMENT)
            .setType("*/*")
            .addCategory(Intent.CATEGORY_OPENABLE)
            .addFlags(
                Intent.FLAG_GRANT_READ_URI_PERMISSION
                    | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
    try {
      startActivityForResult(intent, OPEN_DOCUMENT);
    } catch (ActivityNotFoundException e) {
      message("这台电视没有可用的系统文件选择器。可安装支持系统文件选择的文件管理器，或连接 SMB / WebDAV 书库。");
    }
  }

  @Override
  protected void onActivityResult(int request, int result, Intent intent) {
    super.onActivityResult(request, result, intent);
    if (request == EXPORT_NOTES) {
      File snapshot = noteExportFile();
      if (result != RESULT_OK || intent == null || intent.getData() == null) {
        snapshot.delete();
        return;
      }
      Uri destination = intent.getData();
      job(
          "导出笔记",
          () -> {
            try (InputStream in = new FileInputStream(snapshot);
                OutputStream out = getContentResolver().openOutputStream(destination, "wt")) {
              if (out == null) throw new IOException("无法写入所选位置");
              byte[] buffer = new byte[16 * 1024];
              int count;
              while ((count = in.read(buffer)) != -1) out.write(buffer, 0, count);
            }
            return true;
          },
          done -> {
            snapshot.delete();
            message("书签与笔记已导出");
          });
      return;
    }
    if (request != OPEN_DOCUMENT
        || result != RESULT_OK
        || intent == null
        || intent.getData() == null) return;
    Uri uri = intent.getData();
    String name = "";
    long size = 0;
    try (Cursor c =
        getContentResolver()
            .query(
                uri,
                new String[] {OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE},
                null,
                null,
                null)) {
      if (c != null && c.moveToFirst()) {
        name = c.getString(0);
        if (!c.isNull(1)) size = c.getLong(1);
      }
    } catch (Exception e) {
      message("无法读取文件信息，请重新选择");
      return;
    }
    if (!BookRecord.supported(name)) {
      message("首版支持 EPUB、TXT 和 PDF 文件。");
      return;
    }
    try {
      getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
    } catch (SecurityException e) {
      message("此文件提供方无法保存读取授权，请改用本地或 USB 存储。");
      return;
    }
    for (BookRecord existing : library.books)
      if (existing.documentUri.equals(uri.toString())) {
        openBook(existing, false);
        return;
      }
    BookRecord b = new BookRecord();
    b.documentUri = uri.toString();
    b.title = name.replaceFirst("\\.[^.]+$", "");
    b.format = BookRecord.extension(name);
    b.size = size;
    library.books.add(b);
    save();
    openBook(b, false);
  }

  private void removeBook(BookRecord b) {
    String detail = b.offlineFile.isEmpty() ? "只移除书库记录，原文件保持在原位置。" : "将移除本应用保存的离线副本，远端原文件保持在书库中。";
    new AlertDialog.Builder(this)
        .setTitle("从书库移除？")
        .setMessage(b.title + "\n" + detail)
        .setPositiveButton(
            "移除",
            (d, w) -> {
              if (!b.offlineFile.isEmpty()
                  && library.file(b).exists()
                  && !library.file(b).delete()) {
                message("无法移除离线副本，请重试");
                return;
              }
              library.books.remove(b);
              library.marks.removeIf(m -> m.bookId.equals(b.id));
              save();
              showLibrary();
            })
        .setNegativeButton("取消", null)
        .show();
  }

  private void removeOffline(BookRecord b) {
    new AlertDialog.Builder(this)
        .setTitle("删除离线副本？")
        .setMessage(b.title + "\n在线入口、阅读进度和书签会保留。")
        .setPositiveButton(
            "删除副本",
            (d, w) -> {
              File file = library.file(b);
              if (file.exists() && !file.delete()) {
                message("无法删除离线副本，请重试");
                return;
              }
              b.offlineFile = "";
              b.lastMode = "online";
              save();
              showLibrary();
            })
        .setNegativeButton("取消", null)
        .show();
  }

  private void showSettings() {
    shell("settings", "设置", "让文字与眼睛，保持舒适的距离。");
    add(body, heading(this, "阅读", 18), -1, 36);
    LinearLayout font = horizontal(this);
    add(font, text(this, "字号  " + preferences.getInt("font", 22), 14, TEXT), 125, 40);
    TextView first =
        action(
            font,
            "－",
            false,
            () -> {
              preferences
                  .edit()
                  .putInt("font", Math.max(16, preferences.getInt("font", 22) - 2))
                  .apply();
              showSettings();
            });
    action(
        font,
        "＋",
        false,
        () -> {
          preferences
              .edit()
              .putInt("font", Math.min(32, preferences.getInt("font", 22) + 2))
              .apply();
          showSettings();
        });
    action(
        font,
        preferences.getBoolean("serif", true) ? "字体：宋体" : "字体：黑体",
        false,
        () -> {
          preferences.edit().putBoolean("serif", !preferences.getBoolean("serif", true)).apply();
          showSettings();
        });
    add(body, font, -1, 44);
    gap(body, 12);
    LinearLayout line = horizontal(this);
    action(
        line,
        "行距：紧凑",
        false,
        () -> {
          preferences.edit().putFloat("line", 1.45f).apply();
          showSettings();
        });
    action(
        line,
        "行距：舒展",
        false,
        () -> {
          preferences.edit().putFloat("line", 1.65f).apply();
          showSettings();
        });
    action(
        line,
        "行距：宽松",
        false,
        () -> {
          preferences.edit().putFloat("line", 1.85f).apply();
          showSettings();
        });
    add(body, line, -1, 44);
    gap(body, 20);
    add(body, heading(this, "外观", 18), -1, 36);
    LinearLayout paper = horizontal(this);
    String[] labels = {"暗色书房", "暖纸色", "墨黑"};
    for (int i = 0; i < labels.length; i++) {
      int index = i;
      action(
          paper,
          labels[i],
          preferences.getInt("paper", 0) == i,
          () -> {
            preferences.edit().putInt("paper", index).apply();
            showSettings();
          });
    }
    add(body, paper, -1, 44);
    gap(body, 20);
    add(body, heading(this, "存储与阅读方式", 18), -1, 36);
    TextView explanation =
        text(
            this,
            "在线阅读：按需读取，临时数据只留在内存。\n离线阅读：仅使用明确保存的副本，或已授权的本地 / USB 原文件。\n本地剩余空间："
                + bytes(library.booksDir.getUsableSpace()),
            13,
            MUTED);
    explanation.setLineSpacing(dp(this, 5), 1);
    add(body, explanation, -1, -2);
    gap(body, 20);
    LinearLayout actions = horizontal(this);
    action(
        actions,
        "阅读操作说明",
        false,
        () -> message("正文：左右翻页，确定键打开菜单。\n下键：进入底部操作区，上键：回到正文。\n返回：关闭弹窗或返回书库。\n在线中断后可重试，不会自动下载整本书。"));
    action(
        actions,
        "关于书房 TV",
        false,
        () ->
            message(
                "书房 TV 0.2.0-alpha.1（测试版）\n"
                    + "独立编写的 Android TV 开源阅读器\n"
                    + "Apache-2.0\n"
                    + "github.com/spudhero/ShufangTV\n"
                    + "支持 EPUB 文本、TXT、PDF；阿里、百度、夸克和 UC 网盘扫码直连。"));
    add(body, actions, -1, 44);
    focus(first);
  }

  private void openGuide() {
    for (BookRecord b : library.books)
      if (b.title.equals("书房使用指南")) {
        openBook(b, false);
        return;
      }
    BookRecord b = new BookRecord();
    b.title = "书房使用指南";
    b.author = "书房 TV";
    b.format = "txt";
    b.offlineFile = b.id + ".txt";
    String guide =
        "欢迎来到书房 TV\n\n"
            + "一卷在手，片刻清宁。\n\n"
            + "书房 TV 是为客厅电视和遥控器编写的中文阅读器。你可以连接家庭书库在线读，也可以明确选择保存到本地后离线读。\n\n"
            + "在线阅读\n\n"
            + "在书籍来源中选择网盘，用手机扫码确认登录，再选择书籍文件夹。也可以添加 SMB 或 WebDAV 家庭书库。选中一本 EPUB、TXT 或"
            + " PDF，再选择在线打开。在线模式按需读取文件，临时数据保存在内存，不下载整本书。服务器必须支持文件随机读取。\n\n"
            + "离线阅读\n\n"
            + "在同一个书籍菜单中选择保存到本地，确认大小与空间后再下载。电视本地和 U 盘中的文件可通过系统文件选择器直接读取，不会额外复制。\n\n"
            + "遥控器操作\n\n"
            + "左右键翻页；确定键打开目录、书签和排版菜单。下键进入底部操作区，上键回到正文。返回键逐层返回。\n\n"
            + "阅读位置\n\n"
            + "书房按章节和文字位置保存进度，改变字号、行距后继续读。在线与离线副本共享同一个阅读位置。\n\n"
            + "开源与边界\n\n"
            + "所有应用源码在 github.com/spudhero/ShufangTV。首版不支持带 DRM 的电子书和纯图片 EPUB；PDF"
            + " 以页面显示。网盘直接连接，登录失效时请重新扫码；在线与离线阅读分开。\n";
    job(
        "正在打开指南",
        () -> {
          try (OutputStream out = new FileOutputStream(library.file(b))) {
            out.write(guide.getBytes(java.nio.charset.StandardCharsets.UTF_8));
          }
          return b;
        },
        value -> {
          library.books.add(b);
          save();
          openBook(b, false);
        });
  }

  private <T> void job(String title, Callable<T> work, Consumer<T> done) {
    int token = ++operation;
    ProgressBar bar = new ProgressBar(this);
    LinearLayout view = vertical(this);
    view.setPadding(dp(this, 28), dp(this, 20), dp(this, 28), dp(this, 20));
    add(view, bar, -1, 40);
    AlertDialog dialog =
        new AlertDialog.Builder(this)
            .setTitle(title)
            .setView(view)
            .setNegativeButton("取消", null)
            .create();
    working = dialog;
    dialog.show();
    Future<?>[] pending = new Future<?>[1];
    Runnable cancel =
        () -> {
          if (operation == token) operation++;
          if (pending[0] != null) pending[0].cancel(true);
        };
    dialog.setOnCancelListener(d -> cancel.run());
    dialog
        .getButton(AlertDialog.BUTTON_NEGATIVE)
        .setOnClickListener(
            v -> {
              cancel.run();
              dialog.dismiss();
            });
    pending[0] =
        io.submit(
            () -> {
              try {
                T result = work.call();
                runOnUiThread(
                    () -> {
                      if (destroyed || token != operation) {
                        if (result instanceof AutoCloseable) {
                          Runnable close =
                              () -> {
                                try {
                                  ((AutoCloseable) result).close();
                                } catch (Exception ignored) {
                                }
                              };
                          if (io.isShutdown()) close.run();
                          else io.submit(close);
                        }
                        return;
                      }
                      dialog.dismiss();
                      if (working == dialog) working = null;
                      done.accept(result);
                    });
              } catch (Exception e) {
                runOnUiThread(
                    () -> {
                      if (destroyed || token != operation) return;
                      dialog.dismiss();
                      if (working == dialog) working = null;
                      message(e.getMessage() == null ? "操作未完成，请重试" : e.getMessage());
                    });
              }
            });
  }

  private void save() {
    try {
      library.save();
    } catch (IOException e) {
      message(e.getMessage());
    }
  }

  private void saveSources() {
    try {
      sources.save();
    } catch (IOException e) {
      message(e.getMessage());
    }
  }

  private void message(String value) {
    if (!isFinishing() && !destroyed)
      new AlertDialog.Builder(this)
          .setTitle("书房 TV")
          .setMessage(value)
          .setPositiveButton("知道了", null)
          .show();
  }

  private void goBack() {
    if (working != null && working.isShowing()) {
      working.cancel();
      working = null;
      return;
    }
    if (reader != null) {
      if (reader.back()) return;
      exitReader();
      return;
    }
    if (route.equals("browser") && browsing != null) {
      if (!sourcePath.isEmpty()) {
        if (browsing.type == SourceConfig.Type.CLOUD) cloudBrowseUp(browsing);
        else browse(browsing, parent(sourcePath));
      } else showSources();
      return;
    }
    if (route.equals("cloud-folder") && pickingAccount != null) {
      if (cloudParents.isEmpty()) showSources();
      else {
        Deque<CloudLocation> parents = new ArrayDeque<>(cloudParents);
        CloudLocation previous = parents.pop();
        loadCloudFolder(previous.id, previous.label, previous.cursor, parents);
      }
      return;
    }
    if (!route.equals("home")) {
      showHome();
      return;
    }
    finish();
  }

  @Override
  @android.annotation.SuppressLint(
      "GestureBackNavigation") // API 33+ uses the registered platform callback.
  public boolean dispatchKeyEvent(KeyEvent event) {
    if (Build.VERSION.SDK_INT < 33 && event.getKeyCode() == KeyEvent.KEYCODE_BACK) {
      if (event.getAction() == KeyEvent.ACTION_UP) goBack();
      return true;
    }
    if (reader != null && reader.handle(event)) return true;
    return super.dispatchKeyEvent(event);
  }

  @Override
  protected void onPause() {
    if (reader != null) reader.foreground(false);
    super.onPause();
  }

  @Override
  protected void onResume() {
    super.onResume();
    if (reader != null) reader.foreground(true);
  }

  @Override
  protected void onDestroy() {
    destroyed = true;
    operation++;
    if (reader != null) reader.dispose();
    if (cloudLogin != null) cloudLogin.close();
    io.shutdown();
    super.onDestroy();
  }
}
