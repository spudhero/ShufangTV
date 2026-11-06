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
  private LinearLayout root, body, pageHeader, pageFooter;
  private Dialog sourceMenu;
  private ReaderScreen reader;
  private String route = "home", filter = "all", query = "", sourcePath = "", returnRoute = "home";
  private SourceConfig browsing;
  private String browserFocusPath = "";
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
    boolean compact = getResources().getConfiguration().screenHeightDp < 450;
    route = page;
    root = horizontal(this);
    root.setGravity(Gravity.TOP);
    root.setBackgroundColor(BG);
    LinearLayout side = vertical(this);
    side.setPadding(
        dp(this, compact ? 20 : 28), dp(this, compact ? 20 : 34), dp(this, 18), dp(this, 22));
    side.setBackgroundColor(SIDEBAR);
    add(side, heading(this, "书房 TV", 18), -1, 26);
    gap(side, 4);
    add(side, text(this, "一卷在手 · 片刻清宁", 10, MUTED), -1, 24);
    gap(side, compact ? 12 : 26);
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
      if (page.equals(item[0])
          || (item[0].equals("sources")
              && (page.equals("cloud-folder") || page.equals("browser")))) {
        button.setTextColor(ACCENT);
        button.setBackground(panel(this, 0xff2a2e25, 0));
      }
      add(side, button, -1, compact ? 36 : 42);
      gap(side, 5);
    }
    View spacer = new View(this);
    side.addView(spacer, new LinearLayout.LayoutParams(1, 0, 1));
    add(side, text(this, "开源 · 无广告", 10, MUTED), -1, 24);
    root.addView(side, new LinearLayout.LayoutParams(dp(this, compact ? 126 : 146), -1));
    LinearLayout main = vertical(this);
    main.setPadding(
        dp(this, compact ? 20 : 28),
        dp(this, compact ? 16 : 30),
        dp(this, compact ? 20 : 32),
        dp(this, compact ? 10 : 14));
    root.addView(main, new LinearLayout.LayoutParams(0, -1, 1));
    LinearLayout head = horizontal(this);
    LinearLayout labels = vertical(this);
    add(labels, heading(this, title, compact ? 22 : 26), -1, compact ? 28 : 35);
    gap(labels, 5);
    add(labels, SourceUi.single(this, subtitle, compact ? 11 : 12, MUTED), -1, compact ? 20 : 25);
    head.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
    TextView time =
        text(
            this,
            new SimpleDateFormat("HH:mm\nMM月dd日 EEEE", Locale.SIMPLIFIED_CHINESE)
                .format(new Date()),
            12,
            MUTED);
    time.setGravity(Gravity.END);
    add(head, time, compact ? 92 : 126, compact ? 44 : 58);
    add(main, head, -1, compact ? 56 : 70);
    pageHeader = vertical(this);
    pageHeader.setVisibility(View.GONE);
    add(main, pageHeader, -1, -2);
    ScrollView scroll = new ScrollView(this);
    scroll.setFillViewport(true);
    scroll.setClipToPadding(false);
    scroll.setPadding(dp(this, 4), dp(this, 6), dp(this, 4), dp(this, 8));
    scroll.setFocusable(false);
    body = vertical(this);
    scroll.addView(body, new ScrollView.LayoutParams(-1, -2));
    main.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
    pageFooter = vertical(this);
    pageFooter.setVisibility(View.GONE);
    add(main, pageFooter, -1, -2);
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
        () -> {
          if (!online && isBundledGuide(book)) writeGuide(book);
          return BookSession.open(this, book, online, library, sources);
        },
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
                book.pdfX = mark.pdfX;
                book.pdfY = mark.pdfY;
                book.pdfZoom = mark.pdfZoom;
                book.imageY = mark.imageY;
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
    Intent intent =
        new Intent(Intent.ACTION_CREATE_DOCUMENT)
            .addCategory(Intent.CATEGORY_OPENABLE)
            .setType(markdown ? "text/markdown" : "text/plain")
            .putExtra(Intent.EXTRA_TITLE, "书房TV-书签与笔记." + (markdown ? "md" : "txt"));
    if (isDocumentPlaceholder(intent)) {
      message("这台电视没有可用的系统文件保存界面。收藏仍保存在书房 TV；安装支持系统文档保存的文件管理器后可重试。");
      return;
    }
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
          try {
            startActivityForResult(intent, EXPORT_NOTES);
          } catch (ActivityNotFoundException e) {
            file.delete();
            message("系统未提供文件保存界面，请启用系统文件应用后再试");
          }
        });
  }

  private void showSources() {
    boolean compact = getResources().getConfiguration().screenHeightDp < 450;
    shell("sources", "书籍来源", "连接自己的书库，把想读的书留在手边。");
    List<CloudAccount> accounts = clouds.accounts();
    add(body, SourceUi.section(this, "网盘账号", "一个账号，可以添加多个文件夹"), -1, 28);
    gap(body, 6);
    LinearLayout providers = horizontal(this);
    for (CloudProvider provider : clouds.providers()) {
      if (providers.getChildCount() > 0) gap(providers, 10);
      List<CloudAccount> connected = new ArrayList<>();
      for (CloudAccount account : accounts)
        if (account.providerId.equals(provider.id())) connected.add(account);
      LinearLayout card = horizontal(this);
      card.setPadding(dp(this, 12), dp(this, 10), dp(this, 12), dp(this, 10));
      TextView mark =
          SourceUi.single(
              this,
              provider.id().equals("aliyun")
                  ? "阿"
                  : provider.id().equals("baidu")
                      ? "百"
                      : provider.id().equals("quark") ? "夸" : "UC",
              15,
              connected.isEmpty() ? MUTED : ACCENT);
      mark.setGravity(Gravity.CENTER);
      mark.setBackground(panel(this, BG, SourceUi.BORDER));
      if (!compact) {
        add(card, mark, 28, 32);
        gap(card, 10);
      }
      LinearLayout labels = vertical(this);
      add(labels, SourceUi.single(this, provider.label(), 15, TEXT), -1, 24);
      add(
          labels,
          SourceUi.single(
              this,
              connected.isEmpty() ? "扫码连接" : "已连接 · 管理",
              11,
              connected.isEmpty() ? MUTED : ACCENT),
          -1,
          20);
      card.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
      SourceUi.target(
          card,
          provider.label() + (connected.isEmpty() ? "，扫码登录" : "，管理账号"),
          () -> {
            if (connected.isEmpty()) loginCloud(provider);
            else if (connected.size() == 1) accountActions(connected.get(0));
            else chooseSourceAccount(connected, true);
          });
      card.setTag("cloud-login-" + provider.id());
      providers.addView(card, new LinearLayout.LayoutParams(0, dp(this, 68), 1));
    }
    add(body, providers, -1, 68);
    gap(body, 18);
    LinearLayout folderTitle = horizontal(this);
    folderTitle.addView(
        SourceUi.single(this, "书籍文件夹", 15, TEXT),
        new LinearLayout.LayoutParams(0, dp(this, 28), 1));
    if (!accounts.isEmpty()) {
      TextView addFolder =
          button(
              this,
              "＋ 添加文件夹",
              false,
              () -> {
                if (accounts.size() == 1) chooseCloudFolder(accounts.get(0));
                else chooseSourceAccount(accounts, false);
              });
      addFolder.setTextColor(ACCENT);
      addFolder.setTag("cloud-folder-add");
      add(folderTitle, addFolder, 142, 40);
    } else add(folderTitle, SourceUi.single(this, "连接后选择文件夹", 11, MUTED), -2, 24);
    add(body, folderTitle, -1, 42);
    gap(body, 4);
    if (sources.sources.isEmpty()) {
      LinearLayout empty = horizontal(this);
      empty.setPadding(dp(this, 16), dp(this, 12), dp(this, 16), dp(this, 12));
      empty.setBackground(panel(this, SURFACE, 0));
      add(empty, new SourceUi.Mark(this, "folder", MUTED), 26, 26);
      gap(empty, 14);
      LinearLayout labels = vertical(this);
      add(labels, SourceUi.single(this, "还没有书籍来源", 14, TEXT), -1, 24);
      add(labels, text(this, "扫码连接后选择文件夹。在线打开书籍，离线保存由你决定。", 11, MUTED), -1, 34);
      empty.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
      add(body, empty, -1, 82);
    }
    View firstSource = null;
    for (SourceConfig source : sources.sources) {
      LinearLayout card = horizontal(this);
      String detail =
          source.type == SourceConfig.Type.CLOUD
              ? cloudLabel(source.providerId) + " · " + source.rootLabel
              : source.type == SourceConfig.Type.SMB ? "SMB 家庭共享" : "WebDAV 家庭书库";
      LinearLayout open =
          SourceUi.listRow(this, "folder", source.name, detail, "在线  〉", () -> browse(source, ""));
      open.setTag("source-browse-" + source.id);
      if (firstSource == null) firstSource = open;
      card.addView(open, new LinearLayout.LayoutParams(0, dp(this, 62), 1));
      gap(card, 6);
      TextView more = button(this, "···", false, () -> sourceActions(source));
      more.setContentDescription("管理来源：" + source.name);
      more.setTag("source-manage-" + source.id);
      add(card, more, 44, 44);
      add(body, card, -1, 62);
      gap(body, 4);
    }
    gap(body, 18);
    add(body, SourceUi.section(this, "其他书库", "本地文件或家庭网络"), -1, 28);
    gap(body, 6);
    LinearLayout other = horizontal(this);
    addSourceShortcut(other, "usb", "本地 / USB", "直接打开原文件", this::chooseLocal);
    gap(other, 10);
    addSourceShortcut(
        other, "server", "SMB", "家庭共享", () -> sourceForm(null, SourceConfig.Type.SMB));
    gap(other, 10);
    addSourceShortcut(
        other, "server", "WebDAV", "远程书库", () -> sourceForm(null, SourceConfig.Type.WEBDAV));
    add(body, other, -1, 66);
    focus(firstSource == null ? providers.getChildAt(0) : firstSource);
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

  private void chooseSourceAccount(List<CloudAccount> accounts, boolean manage) {
    LinearLayout content = sourceDialogContent(manage ? "选择要管理的账号" : "选择网盘账号", "每个账号可添加多个书籍文件夹。");
    ScrollView list = new ScrollView(this);
    list.setFillViewport(false);
    LinearLayout rows = vertical(this);
    View first = null;
    for (CloudAccount account : accounts) {
      LinearLayout row =
          SourceUi.row(
              this,
              "cloud",
              account.label,
              cloudLabel(account.providerId),
              "〉",
              () -> {
                dismissSourceDialog();
                if (manage) accountActions(account);
                else chooseCloudFolder(account);
              });
      if (first == null) first = row;
      add(rows, row, -1, 62);
      gap(rows, 8);
    }
    list.addView(rows);
    add(
        content,
        list,
        -1,
        Math.min(
            accounts.size() * 70,
            getResources().getConfiguration().screenHeightDp < 450 ? 140 : 210));
    gap(content, 16);
    TextView back = button(this, "返回", false, this::dismissSourceDialog);
    add(content, back, 120, 44);
    showSourceDialog(content, 580, first == null ? back : first);
  }

  private void addSourceShortcut(
      LinearLayout parent, String icon, String title, String detail, Runnable action) {
    LinearLayout card = SourceUi.row(this, icon, title, detail, "", action);
    parent.addView(card, new LinearLayout.LayoutParams(0, dp(this, 66), 1));
  }

  private LinearLayout sourceDialogContent(String title, String detail) {
    boolean compact = getResources().getConfiguration().screenHeightDp < 450;
    LinearLayout content = vertical(this);
    content.setPadding(
        dp(this, 24), dp(this, compact ? 16 : 22), dp(this, 24), dp(this, compact ? 16 : 22));
    content.setBackground(panel(this, SURFACE, SourceUi.BORDER));
    TextView name = heading(this, title, compact ? 19 : 21);
    name.setMaxLines(2);
    name.setEllipsize(TextUtils.TruncateAt.END);
    add(content, name, -1, -2);
    gap(content, compact ? 6 : 8);
    add(content, text(this, detail, 12, MUTED), -1, -2);
    gap(content, compact ? 12 : 18);
    return content;
  }

  private void showSourceDialog(LinearLayout content, int width, View initial) {
    if (sourceMenu != null) sourceMenu.dismiss();
    Dialog dialog = SourceUi.dialog(this, content, width);
    sourceMenu = dialog;
    dialog.setOnDismissListener(
        d -> {
          if (sourceMenu == dialog) sourceMenu = null;
        });
    dialog.show();
    initial.requestFocus();
  }

  private void dismissSourceDialog() {
    if (sourceMenu != null) sourceMenu.dismiss();
  }

  private void confirmSourceAction(String title, String detail, String confirm, Runnable action) {
    LinearLayout content = sourceDialogContent(title, detail);
    LinearLayout buttons = horizontal(this);
    TextView cancel = button(this, "取消", false, this::dismissSourceDialog);
    add(buttons, cancel, 120, 44);
    gap(buttons, 12);
    add(
        buttons,
        button(
            this,
            confirm,
            true,
            () -> {
              dismissSourceDialog();
              action.run();
            }),
        160,
        44);
    add(content, buttons, -1, 44);
    showSourceDialog(content, 520, cancel);
  }

  private void accountActions(CloudAccount account) {
    boolean compact = getResources().getConfiguration().screenHeightDp < 450;
    LinearLayout content =
        sourceDialogContent("管理网盘账号", account.label + " · " + cloudLabel(account.providerId));
    LinearLayout options = vertical(this);
    LinearLayout choose =
        SourceUi.row(
            this,
            "folder",
            "添加书籍文件夹",
            "从这个账号中选择书籍来源",
            "〉",
            () -> {
              dismissSourceDialog();
              chooseCloudFolder(account);
            });
    choose.setTag("cloud-account-choose-" + account.id);
    add(options, choose, -1, 64);
    gap(options, 10);
    LinearLayout reconnect =
        SourceUi.row(
            this,
            "cloud",
            "重新扫码登录",
            "授权失效时重连，或连接另一个账号",
            "〉",
            () -> {
              dismissSourceDialog();
              try {
                loginCloud(clouds.provider(account.providerId));
              } catch (IOException e) {
                message(e.getMessage());
              }
            });
    add(options, reconnect, -1, 64);
    gap(options, 10);
    add(
        options,
        SourceUi.row(
            this,
            "cloud",
            "退出账号",
            "在线来源将暂不可用，离线副本仍可阅读",
            "〉",
            () ->
                confirmSourceAction(
                    "退出此网盘账号？",
                    "此账号的在线来源将暂不可用。已保存的本地书籍仍可离线阅读。",
                    "退出账号",
                    () ->
                        job(
                            "正在退出网盘",
                            () -> {
                              clouds.logout(account.id);
                              return true;
                            },
                            ok -> showSources()))),
        -1,
        64);
    ScrollView scroll = new ScrollView(this);
    scroll.setClipToPadding(false);
    scroll.setPadding(dp(this, 3), dp(this, 3), dp(this, 3), dp(this, 3));
    scroll.addView(options);
    add(content, scroll, -1, compact ? 144 : 220);
    gap(content, compact ? 10 : 16);
    add(content, button(this, "返回", false, this::dismissSourceDialog), 120, 44);
    showSourceDialog(content, 580, choose);
  }

  private void sourceActions(SourceConfig source) {
    LinearLayout content = sourceDialogContent("管理书籍来源", source.name);
    add(
        content,
        SourceUi.row(
            this,
            "folder",
            "查看保存的位置",
            "核对来源名称与文件夹位置",
            "〉",
            () ->
                showSourceLocation(
                    source.name,
                    (source.type == SourceConfig.Type.CLOUD
                        ? cloudLabel(source.providerId) + "\n" + source.rootLabel
                        : source.type == SourceConfig.Type.SMB
                            ? "SMB 家庭共享\n" + source.root
                            : "WebDAV 书库\n" + source.root))),
        -1,
        64);
    gap(content, 10);
    if (source.type != SourceConfig.Type.CLOUD) {
      add(
          content,
          SourceUi.row(
              this,
              "server",
              "编辑连接",
              "修改家庭书库的连接信息",
              "〉",
              () -> {
                dismissSourceDialog();
                sourceForm(source, source.type);
              }),
          -1,
          64);
      gap(content, 10);
    }
    LinearLayout remove =
        SourceUi.row(
            this,
            "folder",
            "移除此来源",
            "保留原文件、离线副本、进度与书签",
            "〉",
            () ->
                confirmSourceAction(
                    "移除此书籍来源？",
                    "原文件不会删除，已保存到本地的书仍可离线阅读。",
                    "移除来源",
                    () -> {
                      sources.sources.remove(source);
                      saveSources();
                      showSources();
                    }));
    add(content, remove, -1, 64);
    gap(content, 16);
    TextView back = button(this, "返回", false, this::dismissSourceDialog);
    add(content, back, 120, 44);
    showSourceDialog(content, 580, back);
  }

  private void showSourceLocation(String title, String path) {
    LinearLayout content = sourceDialogContent(title, "当前位置");
    ScrollView scroll = new ScrollView(this);
    TextView location = text(this, path, 14, TEXT);
    location.setLineSpacing(dp(this, 4), 1);
    location.setPadding(dp(this, 12), dp(this, 12), dp(this, 12), dp(this, 12));
    location.setBackground(panel(this, BG, 0));
    scroll.addView(location);
    add(content, scroll, -1, getResources().getConfiguration().screenHeightDp < 450 ? 104 : 160);
    gap(content, 16);
    TextView back = button(this, "返回", false, this::dismissSourceDialog);
    add(content, back, 120, 44);
    showSourceDialog(content, 620, back);
  }

  private String cloudFolderTrail(String current) {
    StringJoiner path = new StringJoiner("  ›  ");
    Iterator<CloudLocation> parents = cloudParents.descendingIterator();
    while (parents.hasNext()) path.add(parents.next().label);
    path.add(current);
    return path.toString();
  }

  private String remoteFolderTrail(SourceConfig source, String path) {
    if (path.isEmpty())
      return source.rootLabel == null || source.rootLabel.isEmpty() ? "书库根目录" : source.rootLabel;
    if (source.type != SourceConfig.Type.CLOUD) return path;
    StringJoiner names = new StringJoiner("  ›  ");
    names.add(source.rootLabel == null || source.rootLabel.isEmpty() ? "书库根目录" : source.rootLabel);
    Iterator<String> parents = cloudBrowserParents.descendingIterator();
    while (parents.hasNext()) {
      String parent = parents.next();
      if (!parent.isEmpty()) names.add(cloudFolderNames.getOrDefault(parent, "书籍文件夹"));
    }
    names.add(cloudFolderNames.getOrDefault(path, "书籍文件夹"));
    return names.toString();
  }

  private static String bookFileType(String name) {
    int dot = name.lastIndexOf('.');
    return dot < 0 ? "书籍" : name.substring(dot + 1).toUpperCase(Locale.ROOT);
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
      loadCloudFolder(provider.rootId(account), "根目录", "");
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
          String previousFolder = pickingFolder;
          cloudParents.clear();
          cloudParents.addAll(nextParents);
          pickingFolder = folderId;
          pickingLabel = label;
          pickingCursor = cursor;
          shell("cloud-folder", "选择书籍文件夹", account.label + " · " + cloudLabel(account.providerId));
          boolean compact = getResources().getConfiguration().screenHeightDp < 450;
          pageHeader.setVisibility(View.VISIBLE);
          if (!compact) {
            add(pageHeader, SourceUi.steps(this, 1), -1, 28);
            gap(pageHeader, 8);
          } else add(pageHeader, SourceUi.single(this, "第 2 / 3 步 · 选择书籍文件夹", 11, ACCENT), -1, 22);
          LinearLayout tools = horizontal(this);
          TextView breadcrumb = SourceUi.single(this, cloudFolderTrail(label), 13, TEXT);
          breadcrumb.setEllipsize(TextUtils.TruncateAt.START);
          breadcrumb.setTag("cloud-folder-breadcrumb");
          SourceUi.target(
              breadcrumb,
              "查看完整位置：" + cloudFolderTrail(label),
              () -> showSourceLocation("文件夹位置", cloudFolderTrail(label)));
          breadcrumb.setPadding(dp(this, 8), dp(this, 10), dp(this, 8), dp(this, 10));
          tools.addView(breadcrumb, new LinearLayout.LayoutParams(0, dp(this, 42), 1));
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
          TextView cancel = action(tools, "取消", false, this::showSources);
          add(pageHeader, tools, -1, 44);
          gap(pageHeader, 4);
          add(pageHeader, SourceUi.section(this, "此位置中的文件夹", "按确定键进入文件夹"), -1, compact ? 24 : 28);
          int folders = 0;
          View firstFolder = null;
          for (CloudProvider.Entry entry : page.entries) {
            if (!entry.directory) continue;
            folders++;
            LinearLayout item =
                SourceUi.row(
                    this,
                    "folder",
                    entry.name,
                    "",
                    "进入  〉",
                    () -> {
                      Deque<CloudLocation> parents = new ArrayDeque<>(cloudParents);
                      parents.push(new CloudLocation(folderId, label, cursor));
                      loadCloudFolder(entry.id, entry.name, "", parents);
                    });
            item.setTag("cloud-folder-" + entry.id);
            if (firstFolder == null || entry.id.equals(previousFolder)) firstFolder = item;
            add(body, item, -1, 52);
            gap(body, 8);
          }
          boolean virtualRoot = folderId.startsWith("@");
          if (folders == 0) {
            gap(body, 18);
            add(body, heading(this, "这里没有子文件夹", 19), -1, 34);
            add(
                body,
                text(this, virtualRoot ? "请刷新网盘连接后重试。" : "如果书籍就在这里，可直接使用当前文件夹。", 13, MUTED),
                -1,
                40);
          }
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
          pageFooter.setVisibility(View.VISIBLE);
          line(pageFooter);
          gap(pageFooter, compact ? 6 : 10);
          LinearLayout selected = horizontal(this);
          LinearLayout labels = vertical(this);
          add(labels, SourceUi.single(this, "当前：" + label, 14, TEXT), -1, 24);
          add(
              labels,
              SourceUi.single(this, virtualRoot ? "请先进入资源库或备份盘" : "只保存文件夹位置，不自动下载书籍", 11, MUTED),
              -1,
              20);
          selected.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
          gap(selected, 12);
          TextView select = button(this, "使用此文件夹", true, this::saveCloudFolder);
          select.setTag("cloud-folder-save");
          select.setEnabled(!virtualRoot);
          select.setFocusable(!virtualRoot);
          select.setAlpha(virtualRoot ? .4f : 1);
          add(selected, select, 180, 44);
          add(pageFooter, selected, -1, 52);
          focus(firstFolder != null ? firstFolder : virtualRoot ? cancel : select);
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
    source.rootLabel = cloudFolderTrail(pickingLabel);
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
    edit.setTextColor(TEXT);
    edit.setHintTextColor(MUTED);
    edit.setBackgroundTintList(android.content.res.ColorStateList.valueOf(ACCENT));
    edit.setSingleLine(true);
    edit.setInputType(
        password
            ? android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            : android.text.InputType.TYPE_CLASS_TEXT
                | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
    add(form, edit, -1, 44);
    gap(form, 8);
    return edit;
  }

  private void sourceForm(SourceConfig original, SourceConfig.Type type) {
    LinearLayout form = vertical(this);
    ScrollView scroll = new ScrollView(this);
    scroll.setSmoothScrollingEnabled(false);
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
        new ReadingDialog(this)
            .width(620)
            .setTitle(type == SourceConfig.Type.SMB ? "连接 SMB 家庭书库" : "连接 WebDAV 家庭书库")
            .setView(scroll)
            .setPositiveButton("保存并浏览", null)
            .setNegativeButton("取消", null)
            .create();
    EditText finalPort = port, finalShare = share;
    dialog.show();
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
          if (browsing != source) browserFocusPath = "";
          else if (!path.equals(sourcePath)) browserFocusPath = sourcePath;
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
        (cloud
                ? cloudLabel(source.providerId)
                : source.type == SourceConfig.Type.SMB ? "SMB 家庭共享" : "WebDAV 书库")
            + " · 在线来源，离线保存由你决定");
    LinearLayout tools = horizontal(this);
    add(tools, SourceUi.badge(this, "在线书籍"), 72, 26);
    gap(tools, 12);
    TextView breadcrumb = SourceUi.single(this, remoteFolderTrail(source, path), 13, TEXT);
    breadcrumb.setEllipsize(TextUtils.TruncateAt.START);
    breadcrumb.setTag("remote-breadcrumb");
    SourceUi.target(
        breadcrumb,
        "查看完整位置：" + remoteFolderTrail(source, path),
        () -> showSourceLocation(source.name, remoteFolderTrail(source, path)));
    breadcrumb.setPadding(dp(this, 8), dp(this, 10), dp(this, 8), dp(this, 10));
    tools.addView(breadcrumb, new LinearLayout.LayoutParams(0, dp(this, 42), 1));
    TextView refresh = action(tools, "刷新", false, () -> browse(source, path));
    refresh.setTag("remote-refresh");
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
    pageHeader.setVisibility(View.VISIBLE);
    add(pageHeader, tools, -1, 44);
    gap(pageHeader, 12);
    List<RemoteEntry> sorted = new ArrayList<>(entries);
    sorted.sort(Comparator.comparing((RemoteEntry e) -> !e.directory).thenComparing(e -> e.name));
    add(pageHeader, SourceUi.section(this, "书籍与文件夹", "EPUB · AZW3 · MOBI · TXT · PDF"), -1, 28);
    int count = 0;
    View firstEntry = null;
    for (RemoteEntry entry : sorted) {
      if (!entry.directory && !BookRecord.supported(entry.name)) continue;
      count++;
      LinearLayout item =
          SourceUi.row(
              this,
              entry.directory ? "folder" : "book",
              entry.name,
              entry.directory
                  ? "文件夹"
                  : bookFileType(entry.name)
                      + " · "
                      + (entry.size > 0 ? bytes(entry.size) : "大小未知"),
              entry.directory ? "进入  〉" : "打开  〉",
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
      item.setTag("remote-" + entry.path);
      if (firstEntry == null || entry.path.equals(browserFocusPath)) firstEntry = item;
      add(body, item, -1, 62);
      gap(body, 8);
    }
    if (count == 0) {
      gap(body, 30);
      add(body, heading(this, "这个目录还没有可读的书", 21), -1, 40);
      add(body, text(this, "支持 EPUB、AZW3、MOBI、AZW、PRC、TXT 和 PDF。", 13, MUTED), -1, 34);
    }
    focus(firstEntry == null ? refresh : firstEntry);
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
    browserFocusPath = entry.path;
    LinearLayout content =
        sourceDialogContent(
            entry.name,
            bookFileType(entry.name)
                + " · "
                + source.name
                + (entry.size > 0 ? " · " + bytes(entry.size) : ""));
    LinearLayout online =
        SourceUi.row(
            this,
            "book",
            "在线打开",
            "按需读取，不保存整本书",
            "推荐  〉",
            () -> {
              dismissSourceDialog();
              openBook(recordFor(source, entry), true);
            });
    online.setTag("remote-open-online");
    SourceUi.primary(online);
    add(content, online, -1, 76);
    gap(content, 12);
    LinearLayout offline =
        SourceUi.row(
            this,
            "usb",
            "保存到本地",
            (entry.size > 0 ? "约 " + bytes(entry.size) + "，" : "确认大小与空间后下载，") + "占用电视存储，可离线阅读",
            "〉",
            () -> {
              dismissSourceDialog();
              download(recordFor(source, entry));
            });
    offline.setTag("remote-save-offline");
    add(content, offline, -1, 76);
    gap(content, 18);
    TextView cancel = button(this, "返回书库", false, this::dismissSourceDialog);
    cancel.setTag("remote-action-cancel");
    add(content, cancel, 140, 44);
    showSourceDialog(content, 620, online);
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
    new ReadingDialog(this)
        .width(480)
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
                                  ReadingDialog.updateTitle(
                                      working,
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
                    new ReadingDialog(this)
                        .width(480)
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
    if (isDocumentPlaceholder(intent)) {
      message("这台电视没有可用的系统文件选择器。可安装支持系统文档选择的文件管理器，或从网络书库在线打开、保存离线。");
      return;
    }
    try {
      startActivityForResult(intent, OPEN_DOCUMENT);
    } catch (ActivityNotFoundException e) {
      message("这台电视没有可用的系统文件选择器。可安装支持系统文件选择的文件管理器，或连接 SMB / WebDAV 书库。");
    }
  }

  private boolean isDocumentPlaceholder(Intent intent) {
    android.content.pm.ResolveInfo handler =
        getPackageManager()
            .resolveActivity(intent, android.content.pm.PackageManager.MATCH_DEFAULT_ONLY);
    // Some Android TV builds resolve document intents to a system stub that only cancels.
    // A null lookup may be package visibility filtering; let startActivity handle that case.
    return handler != null
        && handler.activityInfo != null
        && "com.google.android.tv.frameworkpackagestubs".equals(handler.activityInfo.packageName);
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
      message("支持 EPUB、AZW3、MOBI、AZW、PRC、TXT 和 PDF 文件。");
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
    new ReadingDialog(this)
        .width(480)
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
    new ReadingDialog(this)
        .width(480)
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
    View previous = route.equals("settings") && root != null ? root.findFocus() : null;
    String anchor = previous == null ? "settings-font-less" : String.valueOf(previous.getTag());
    shell("settings", "设置", "全局阅读默认值 · 本书独立排版不受影响");
    ReadingStyle style = ReadingStyle.defaults(preferences);
    ReadingPanel.section(body, "字号 · " + style.font + " 号");
    ReadingPanel.row(
        body,
        globalSetting(
            "减小字号",
            "font-less",
            false,
            () -> {
              style.font = Math.max(16, style.font - 2);
              style.saveDefaults(preferences);
              showSettings();
            }),
        globalSetting(
            "增大字号",
            "font-more",
            false,
            () -> {
              style.font = Math.min(40, style.font + 2);
              style.saveDefaults(preferences);
              showSettings();
            }));
    ReadingPanel.section(body, "字体");
    ReadingPanel.row(
        body,
        globalSetting(
            "宋体",
            "font-serif",
            style.serif,
            () -> {
              style.serif = true;
              style.saveDefaults(preferences);
              showSettings();
            }),
        globalSetting(
            "黑体",
            "font-sans",
            !style.serif,
            () -> {
              style.serif = false;
              style.saveDefaults(preferences);
              showSettings();
            }));
    ReadingPanel.section(body, "行距");
    String[] lineLabels = {"紧凑 1.45", "舒展 1.65", "宽松 1.85"};
    float[] lineValues = {1.45f, 1.65f, 1.85f};
    View[] spacing = new View[3];
    for (int i = 0; i < spacing.length; i++) {
      int choice = i;
      spacing[i] =
          globalSetting(
              lineLabels[i],
              "line-" + i,
              Math.abs(style.line - lineValues[i]) < .01f,
              () -> {
                style.line = lineValues[choice];
                style.saveDefaults(preferences);
                showSettings();
              });
    }
    ReadingPanel.row(body, spacing);
    ReadingPanel.section(body, "阅读栏宽");
    String[] widthLabels = {"窄栏", "中栏", "宽栏"};
    View[] widths = new View[3];
    for (int i = 0; i < widths.length; i++) {
      int choice = i;
      widths[i] =
          globalSetting(
              widthLabels[i],
              "width-" + i,
              style.width == i,
              () -> {
                style.width = choice;
                style.saveDefaults(preferences);
                showSettings();
              });
    }
    ReadingPanel.row(body, widths);
    ReadingPanel.section(body, "默认纸色 · 显示亮度可在阅读菜单调整");
    String[] paperLabels = {"暗色书房", "暖纸色", "墨黑"};
    View[] papers = new View[3];
    for (int i = 0; i < papers.length; i++) {
      int choice = i;
      papers[i] =
          globalSetting(
              paperLabels[i],
              "paper-" + i,
              style.paper == i,
              () -> {
                style.paper = choice;
                style.saveDefaults(preferences);
                showSettings();
              });
    }
    ReadingPanel.row(body, papers);
    ReadingPanel.section(body, "存储与阅读方式");
    TextView explanation =
        text(
            this,
            "在线阅读：按需读取，临时数据只留在内存。\n离线阅读：仅使用明确保存的副本，或已授权的本地 / USB 原文件。\n本地剩余空间："
                + bytes(library.booksDir.getUsableSpace()),
            13,
            MUTED);
    explanation.setLineSpacing(dp(this, 5), 1);
    add(body, explanation, -1, -2);
    gap(body, 16);
    ReadingPanel.row(
        body,
        globalSetting(
            "阅读操作说明",
            "help",
            false,
            () ->
                message(
                    "正文：左右翻页，确定键打开菜单。\n"
                        + "下键进入操作区，上键返回正文。\n"
                        + "正文搜索仅检索当前章或片段。\n"
                        + "选段时上下键移动，确定键保存并备注。\n"
                        + "朗读进入后台会暂停；自动翻页关菜单后恢复，手动暂停需主动继续。\n"
                        + "返回键逐层关闭弹窗或回到书库。")),
        globalSetting(
            "关于书房 TV",
            "about",
            false,
            () ->
                message(
                    "书房 TV "
                        + appVersion()
                        + "（测试版）\n"
                        + "独立编写的 Android TV 开源阅读器\nApache-2.0\ngithub.com/spudhero/ShufangTV\n"
                        + "支持 EPUB 图文、AZW3、MOBI、TXT、PDF；阿里、百度、夸克和 UC 网盘扫码直连。")));
    View restore = root.findViewWithTag(anchor);
    focus(restore != null ? restore : root.findViewWithTag("settings-font-less"));
  }

  private TextView globalSetting(String label, String key, boolean selected, Runnable action) {
    TextView view = ReadingPanel.option(this, label, selected, action);
    view.setTag("settings-" + key);
    view.setOnClickListener(
        v -> {
          v.requestFocus();
          action.run();
        });
    return view;
  }

  private String appVersion() {
    try {
      return getPackageManager().getPackageInfo(getPackageName(), 0).versionName;
    } catch (android.content.pm.PackageManager.NameNotFoundException e) {
      return "未知版本";
    }
  }

  private static boolean isBundledGuide(BookRecord book) {
    return book.title.equals("书房使用指南")
        && book.author.equals("书房 TV")
        && book.format.equals("txt")
        && book.sourceId.isEmpty()
        && book.documentUri.isEmpty()
        && book.offlineFile.equals(book.id + ".txt");
  }

  private void openGuide() {
    BookRecord existingGuide =
        library.books.stream().filter(MainActivity::isBundledGuide).findFirst().orElse(null);
    BookRecord b = existingGuide == null ? new BookRecord() : existingGuide;
    b.title = "书房使用指南";
    b.author = "书房 TV";
    b.format = "txt";
    b.offlineFile = b.id + ".txt";
    job(
        "正在打开指南",
        () -> {
          writeGuide(b);
          return b;
        },
        value -> {
          if (existingGuide == null) library.books.add(b);
          save();
          openBook(b, false);
        });
  }

  private void writeGuide(BookRecord book) throws IOException {
    try (OutputStream out = new FileOutputStream(library.file(book))) {
      out.write(guideContents().getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }
  }

  private static String guideContents() {
    return "欢迎来到书房 TV\n\n"
        + "一卷在手，片刻清宁。\n\n"
        + "书房 TV 是为客厅电视和遥控器编写的中文阅读器。你可以连接家庭书库在线读，也可以明确选择保存到本地后离线读。\n\n"
        + "在线阅读\n\n"
        + "在书籍来源中选择网盘，用手机扫码确认登录，再选择书籍文件夹。也可以添加 SMB 或 WebDAV 家庭书库。选中一本 EPUB、AZW3、MOBI、TXT 或"
        + " PDF，再选择在线打开。在线模式按需读取文件，临时数据保存在内存，不自动保存离线副本。EPUB 按章节和图片读取；Kindle"
        + " 格式需重建所选文件的压缩记录。服务器必须支持文件随机读取。\n\n"
        + "离线阅读\n\n"
        + "在同一个书籍菜单中选择保存到本地，确认大小与空间后再下载。电视本地和 U 盘中的文件可通过系统文件选择器直接读取，不会额外复制。\n\n"
        + "遥控器操作\n\n"
        + "左右键翻页；确定键打开目录、书签和排版菜单。下键进入底部操作区，上键回到正文。返回键逐层返回。\n\n"
        + "阅读位置\n\n"
        + "书房按章节和文字位置保存进度，改变字号、行距后继续读。在线与离线副本共享同一个阅读位置。\n\n"
        + "排版与正文搜索\n\n"
        + "阅读菜单可设置窄、中、宽栏，以及全局默认或仅本书排版。正文搜索只检索当前章节或片段；退出搜索可回到原位置。\n\n"
        + "朗读、收藏与自动翻页\n\n"
        + "朗读使用电视的中文语音引擎，离开应用会暂停。收藏正文时用上下键选段、确定键添加备注；我的书签可管理和导出。自动翻页关菜单后恢复，手动暂停或翻页后需主动继续。\n\n"
        + "整理书库\n\n"
        + "书籍菜单可置顶、加入书单并标为待读、在读或已读；这些操作不移动来源文件。\n\n"
        + "开源与边界\n\n"
        + "所有应用源码在 github.com/spudhero/ShufangTV。支持纯图片 EPUB、图文混排及无 DRM 的 AZW3/MOBI；PDF"
        + " 默认全屏撑满屏宽，上下滚动；确定打开菜单可缩放并恢复 100%，放大后左右平移。带 DRM"
        + " 的书籍无法读取。网盘直接连接，登录失效时请重新扫码；在线与离线阅读分开。\n";
  }

  private <T> void job(String title, Callable<T> work, Consumer<T> done) {
    int token = ++operation;
    ProgressBar bar = new ProgressBar(this);
    LinearLayout view = vertical(this);
    view.setPadding(dp(this, 28), dp(this, 20), dp(this, 28), dp(this, 20));
    add(view, bar, -1, 40);
    AlertDialog dialog =
        new ReadingDialog(this)
            .width(480)
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
      new ReadingDialog(this)
          .width(480)
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
    dismissSourceDialog();
    io.shutdown();
    super.onDestroy();
  }
}
