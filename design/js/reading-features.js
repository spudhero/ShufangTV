(() => {
  'use strict';

  const $ = (selector, root = document) => root.querySelector(selector);
  const $$ = (selector, root = document) => [...root.querySelectorAll(selector)];
  const esc = value => String(value ?? '').replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
  const clamp = (value, min, max) => Math.min(max, Math.max(min, value));
  const paths = {
    close: '<path d="m6 6 12 12M18 6 6 18"/>',
    chevron: '<path d="m9 5 7 7-7 7"/>',
    down: '<path d="m5 9 7 7 7-7"/>',
    back: '<path d="m14 5-7 7 7 7"/>',
    search: '<circle cx="10.5" cy="10.5" r="6.5"/><path d="m16 16 4 4"/>',
    book: '<path d="M4 4h6a3 3 0 0 1 3 3v14a4 4 0 0 0-4-3H4zM13 7a3 3 0 0 1 3-3h4v14h-3a4 4 0 0 0-4 3"/>',
    bookmark: '<path d="M6 3h12v18l-6-4-6 4z"/>',
    check: '<path d="m5 12 4 4L19 6"/>',
    pin: '<path d="m8 3 8 0-1 6 3 4H6l3-4zM12 13v8"/>',
    plus: '<path d="M12 4v16M4 12h16"/>',
    minus: '<path d="M4 12h16"/>',
    dots: '<circle cx="5" cy="12" r="1"/><circle cx="12" cy="12" r="1"/><circle cx="19" cy="12" r="1"/>',
    pause: '<path d="M8 5v14M16 5v14"/>',
    play: '<path d="m8 4 12 8-12 8z"/>',
    error: '<path d="M12 8v5M12 17h.01"/><path d="m12 3 10 18H2z"/>'
  };
  const icon = (name, extra = '') => `<svg class="od-icon rf-symbol ${extra}" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.7" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">${paths[name] || paths.book}</svg>`;
  const button = (label, action, key, extra = '', attrs = '') => `<button type="button" data-act="${action}" data-key="${key}" class="${extra}" ${attrs}>${label}</button>`;
  const opt = (label, action, key, active, attrs = '') => button(`${active ? icon('check', 'rf-check') : ''}<span>${esc(label)}</span>`, action, key, active ? 'selected' : '', `${attrs} aria-pressed="${!!active}"`);
  const field = (label, helper = '') => `<span class="od-field"><span>${esc(label)}</span>${helper ? `<small>${esc(helper)}</small>` : ''}</span>`;
  const baseBooks = (window.TV_BOOKS || []).map((book, index) => ({
    ...book,
    format: index === 2 || index === 4 ? 'TXT' : 'EPUB',
    sourceLabel: ({local:'电视本地', usb:'USB', smb:'SMB 家庭书库'})[book.source],
    local: index !== 4 && index !== 6,
    lastRead: 20-index,
    online: index === 4 || index === 6,
    pinned: index === 0,
    status: index === 0 || index === 1 ? 'reading' : index === 2 ? 'finished' : 'unread',
    groups: index === 0 || index === 2 || index === 5 ? ['philosophy'] : index === 1 || index === 4 ? ['essays'] : [],
    progress: {chapter: index === 1 ? 1 : 0, offset: 0},
    style: null,
    history: index === 0 ? ['知行', '本体'] : []
  }));
  const poetry = baseBooks.find(book => book.id === 'shijing');
  const books = [...baseBooks, ...(poetry ? [{...poetry, id:'shijing-pdf', title:'诗经（排印本）', format:'PDF', pinned:false, status:'unread', groups:[], progress:{chapter:0,offset:0}, history:[]}] : [])];
  const params = new URLSearchParams(location.search);
  const sampleCase = params.get('case') || '';
  const statusNames = {all:'全部', unread:'待读', reading:'在读', finished:'已读'};
  const sourceNames = {all:'全部来源', local:'电视本地', usb:'USB', smb:'SMB 家庭书库'};
  const state = {
    route: 'library', books,
    groups: [{id:'philosophy',name:'哲思'}, {id:'essays',name:'随笔'}, {id:'reread',name:'待细读'}],
    filters: {group:'all',status:'all',source:'all',availability:'all',query:'',sort:'recent'},
    libraryStatus: sampleCase === 'library-error' ? 'error' : sampleCase === 'library-loading' ? 'loading' : 'ready',
    markFilter: 'all',
    globalStyle: {size:22,line:1.65,face:'song',width:'medium',theme:'dark'},
    brightness: 100,
    reader: {bookId:'chuanxilu',chapter:0,offset:0},
    mode: 'normal', layers: [],
    search: {query:'',hits:[],index:0,origin:null,ran:false,loading:false,error:'',batch:0,total:0},
    speech: {paused:false,paragraph:0,rate:1,timer:'none',deadline:0,nextDue:0,reason:'',engine:sampleCase !== 'engine-missing'},
    selection: {index:0},
    auto: {interval:10,paused:false,nextDue:0,remaining:10},
    marks: [
      {id:'mark-1',kind:'paragraph',bookId:'chuanxilu',chapter:0,offset:0,text:'知是行的主意，行是知的功夫；知是行之始，行是知之成。',note:'知与行互相成就，读到这里，想起日常做事的习惯。',changed:sampleCase === 'text-changed'},
      {id:'mark-2',kind:'position',bookId:'fusheng',chapter:1,offset:0,text:'卷二 · 闲情记趣',note:''}
    ],
    lastLibraryKey:'book-chuanxilu', listScroll:{library:0,marks:0}, toastTimer:0, uid:3
  };
  if (sampleCase === 'long-title') state.books[0].title = '传习录：徐爱录、陆澄录与薛侃录校读笔记合编';
  if (sampleCase === 'long-note') state.marks[0].note = '每次重读，都能从知与行的关系中发现新的问题。'.repeat(36);
  if (sampleCase === 'empty-marks') state.marks = [];
  const screen = $('#rf-screen');
  const layerRoot = $('#rf-layer');
  const tv = $('#rf-tv');
  const measure = $('#rf-measure');
  const pageCache = new Map();
  const bookById = id => state.books.find(book => book.id === id);
  const currentBook = () => bookById(state.reader.bookId) || state.books[0];
  const currentStyle = () => currentBook().style || state.globalStyle;
  const currentChapter = () => currentBook().chapters[state.reader.chapter];
  const topLayer = () => state.layers[state.layers.length - 1];
  const isPDF = () => currentBook().format === 'PDF';
  const rangeName = () => currentBook().format === 'EPUB' ? '本章 EPUB 正文' : '当前 TXT 片段';
  const safeSlice = (text, start, end) => {
    if (end < text.length && /[\uD800-\uDBFF]/.test(text[end - 1])) end--;
    return text.slice(start, end);
  };
  const fullText = () => currentChapter().paragraphs.join('\n\n');

  function paragraphRanges() {
    let offset = 0;
    const ranges = [];
    for (const paragraph of currentChapter().paragraphs) {
      let start = 0;
      do {
        const text = safeSlice(paragraph, start, start + 4000);
        ranges.push({text,start:offset + start,end:offset + start + text.length,continuation:start > 0});
        start += text.length;
      } while (start < paragraph.length);
      offset += paragraph.length + 2;
    }
    return ranges;
  }

  function pages() {
    const book = currentBook();
    const style = currentStyle();
    const width = {narrow:480,medium:624,wide:768}[style.width];
    const key = [book.id,state.reader.chapter,style.size,style.line,style.face,width].join(':');
    if (pageCache.has(key)) return pageCache.get(key);
    measure.style.width = `${width}px`;
    measure.style.fontSize = `${style.size}px`;
    measure.style.lineHeight = String(style.line);
    measure.style.fontFamily = style.face === 'song' ? 'var(--font-book)' : 'var(--font-ui)';
    const maxHeight = 268;
    const fits = segments => {
      measure.innerHTML = segments.map(segment => `<p class="${segment.continuation ? 'continuation' : ''}">${esc(segment.text)}</p>`).join('');
      const height = measure.scrollHeight;
      if (height > 0) return height <= maxHeight;
      return segments.reduce((sum, segment) => sum + Math.ceil((segment.text.length + (segment.continuation ? 0 : 2)) / Math.floor(width / style.size)) * style.size * style.line + 12, -12) <= maxHeight;
    };
    const output = [];
    let pending = [];
    const flush = () => {
      if (!pending.length) return;
      output.push({segments:pending,start:pending[0].start,end:pending[pending.length - 1].end});
      pending = [];
    };
    for (const [paragraph, range] of paragraphRanges().entries()) {
      let consumed = 0;
      while (consumed < range.text.length) {
        const remaining = range.text.slice(consumed);
        const part = {text:remaining,start:range.start + consumed,end:range.end,paragraph,continuation:range.continuation || consumed > 0};
        if (fits([...pending,part])) { pending.push(part); consumed = range.text.length; continue; }
        let low = 0;
        let high = remaining.length;
        while (low < high) {
          const mid = Math.ceil((low + high) / 2);
          const text = safeSlice(remaining, 0, mid);
          if (text && fits([...pending,{...part,text,end:part.start + text.length}])) low = mid;
          else high = mid - 1;
        }
        const fitted = safeSlice(remaining, 0, low);
        if (!fitted) {
          if (pending.length) { flush(); continue; }
          const first = String.fromCodePoint(remaining.codePointAt(0));
          pending.push({...part,text:first,end:part.start + first.length});
          consumed += first.length;
        } else {
          pending.push({...part,text:fitted,end:part.start + fitted.length});
          consumed += fitted.length;
        }
        flush();
      }
    }
    flush();
    measure.textContent = '';
    pageCache.set(key, output.length ? output : [{segments:[],start:0,end:0}]);
    return pageCache.get(key);
  }

  function pageIndex() {
    const list = pages();
    const index = list.findIndex(page => state.reader.offset >= page.start && state.reader.offset < page.end);
    if (index >= 0) return index;
    return clamp(list.findLastIndex(page => page.start <= state.reader.offset), 0, list.length - 1);
  }

  function savePosition() {
    const book = currentBook();
    book.progress = {chapter:state.reader.chapter,offset:state.reader.offset};
    if (book.status === 'unread') book.status = 'reading';
  }

  function activeScope() { return topLayer() ? layerRoot : screen; }
  function focusKey(key) {
    const scope = activeScope();
    const element = $$('[data-key]',scope).find(node => node.dataset.key === key) || $('[data-initial]',scope) || $('button, input, textarea, [tabindex="0"]',scope);
    if (!element) return;
    element.focus({preventScroll:true});
    let parent = element.parentElement;
    while (parent && parent !== tv) {
      if (parent.classList.contains('rf-scroll')) {
        const rect = element.getBoundingClientRect();
        const box = parent.getBoundingClientRect();
        const scale = parseFloat(getComputedStyle(tv).getPropertyValue('--rf-scale')) || 1;
        if (rect.top < box.top + 6 * scale) parent.scrollTop -= (box.top + 6 * scale - rect.top) / scale;
        if (rect.bottom > box.bottom - 6 * scale) parent.scrollTop += (rect.bottom - box.bottom + 6 * scale) / scale;
      }
      parent = parent.parentElement;
    }
  }

  function render(key) {
    const oldKey = key || document.activeElement?.dataset.key;
    const oldList = $('.rf-book-grid, .rf-marks',screen);
    if (oldList) state.listScroll[oldList.classList.contains('rf-marks') ? 'marks' : 'library'] = oldList.scrollTop;
    screen.innerHTML = state.route === 'reader' ? readerHTML() : state.route === 'marks' ? marksHTML() : libraryHTML();
    const newList = $('.rf-book-grid, .rf-marks',screen);
    if (newList) newList.scrollTop = state.listScroll[state.route] || 0;
    screen.inert = state.layers.length > 0;
    screen.setAttribute('aria-hidden', state.layers.length ? 'true' : 'false');
    renderLayer();
    focusKey(oldKey);
  }

  function renderLayer() {
    const layer = topLayer();
    layerRoot.hidden = !layer;
    layerRoot.innerHTML = layer ? layerHTML(layer) : '';
    if (layer?.scroll) {
      const scroll = $('.rf-panel-content',layerRoot);
      if (scroll) scroll.scrollTop = layer.scroll;
    }
  }

  function openLayer(type, data = {}, focus) {
    const previous = topLayer();
    if (previous) previous.scroll = $('.rf-panel-content',layerRoot)?.scrollTop || 0;
    state.layers.push({type,...data,returnKey:document.activeElement?.dataset.key || 'reader-surface'});
    render(focus);
    resetAutoClock();
  }

  function closeLayer() {
    const layer = state.layers.pop();
    render(layer?.returnKey);
    resetAutoClock();
  }

  function closeAllLayers(focus = 'reader-surface') {
    state.layers = [];
    render(focus);
    resetAutoClock();
  }

  function toast(message) {
    clearTimeout(state.toastTimer);
    const node = $('#rf-toast');
    node.textContent = message;
    node.hidden = false;
    state.toastTimer = setTimeout(() => { node.hidden = true; }, 2600);
  }

  function sidebar(current) {
    return `<aside class="rf-sidebar"><div class="rf-brand"><strong>书房 TV</strong><span>暗色书房</span></div><nav aria-label="书房导航" class="rf-nav">${button(`${icon('book')}<span>书库</span>`,'route:library','nav-library',current === 'library' ? 'current' : '',current === 'library' ? 'aria-current="page"' : '')}${button(`${icon('bookmark')}<span>我的书签</span>`,'route:marks','nav-marks',current === 'marks' ? 'current' : '',current === 'marks' ? 'aria-current="page"' : '')}</nav><div class="rf-side-bottom"><span>读过的，留下来</span><span>本地 · 家庭书库</span></div></aside>`;
  }

  function filteredBooks() {
    const filter = state.filters;
    const output = state.books.filter(book => (filter.group === 'all' || book.groups.includes(filter.group)) && (filter.status === 'all' || book.status === filter.status) && (filter.source === 'all' || book.source === filter.source) && (filter.availability === 'all' || (filter.availability === 'online' ? book.online : book.local)) && (!filter.query || `${book.title} ${book.author}`.toLowerCase().includes(filter.query.toLowerCase())));
    return output.sort((a,b) => Number(b.pinned) - Number(a.pinned) || (filter.sort === 'title' ? a.title.localeCompare(b.title,'zh-CN') : b.lastRead-a.lastRead));
  }

  function libraryHTML() {
    const filter = state.filters;
    const groupName = state.groups.find(group => group.id === filter.group)?.name || '全部书单';
    const selectedSource = filter.source === 'all' && filter.availability !== 'all' ? (filter.availability === 'online' ? '在线来源' : '离线可用') : sourceNames[filter.source];
    const filtered = filteredBooks();
    let content;
    if (state.libraryStatus === 'loading') content = `<div class="rf-empty" role="status"><h3>正在整理书库</h3><p>保留当前书单与筛选</p></div>`;
    else if (state.libraryStatus === 'error') content = `<div class="rf-empty">${icon('error')}<h3>书库暂时无法读取</h3><p>稍后重试，已保存的书单不会改变。</p>${button('重新加载','library-retry','library-retry','primary')}</div>`;
    else if (!filtered.length) content = `<div class="rf-empty">${icon('book')}<h3>${filter.group !== 'all' && filter.status === 'all' && filter.source === 'all' && !filter.query ? '这个书单还没有书' : '没有符合条件的书'}</h3><p>${filter.group !== 'all' ? '在书籍操作中选择“加入书单”，同一本书可以归入多个书单。' : '换一个阅读状态或来源，或清除当前筛选。'}</p>${button('清除筛选','clear-filters','clear-filters','primary')}</div>`;
    else content = filtered.map(book => `<button type="button" class="rf-book" data-key="book-${book.id}" data-act="book:${book.id}" aria-label="${esc(book.title)}，${esc(book.author)}，${statusNames[book.status]}${book.pinned ? '，已置顶' : ''}"><span class="rf-cover" style="--book-paper:${book.color};--book-ink:${book.ink}" aria-hidden="true"><span class="rf-cover-spine"></span><span class="rf-cover-body"><span class="rf-cover-title">${esc(book.id === 'shijing-pdf' ? '诗经' : book.title.length > 8 ? '传习录' : book.title)}</span><span class="rf-cover-author">${esc(book.author)}</span></span></span><span class="rf-book-copy"><span class="rf-book-line"><span class="rf-book-title od-truncate">${esc(book.title)}</span>${book.pinned ? icon('pin','rf-pin') : ''}</span><span class="rf-book-source od-truncate">${esc(book.sourceLabel)} · ${book.format}</span><span class="rf-state ${book.status}">${statusNames[book.status]}</span></span></button>`).join('');
    return `<div class="rf-app">${sidebar('library')}<main class="rf-main"><header class="rf-page-head"><div class="od-field"><h1>书库</h1><p class="caption">在不同来源之间，接着读。</p></div><div class="od-row">${button(`${icon('search')}<span>找书</span>`,'library-search','library-search','quiet')}${button('排序','sort','library-sort','quiet')}</div></header><section class="rf-filters" aria-label="书库筛选"><div class="rf-filter-row">${button(`<span class="od-truncate">${esc(groupName)}</span>${icon('down')}`,'groups','filter-group','rf-selector')}${button(`<span class="od-truncate">${esc(selectedSource)}</span>${icon('down')}`,'source-filter','filter-source','rf-selector')}<span class="caption od-fill">${filtered.length} 本${filter.query ? ` · “${esc(filter.query)}”` : ''}</span></div><div class="rf-filter-row"><div class="rf-state-tabs" role="group" aria-label="阅读状态">${Object.entries(statusNames).map(([value,name])=>opt(name,`filter-status:${value}`,`filter-status-${value}`,filter.status === value)).join('')}</div>${filter.query ? button('清除搜索','clear-query','clear-query','text') : ''}</div></section><section aria-label="书架" class="rf-book-grid rf-scroll">${content}</section><footer class="rf-page-foot"><span>${filter.sort === 'title' ? '书名顺序' : '最近阅读'} · 置顶优先</span><div class="rf-key-hints"><span><kbd>方向键</kbd>移动</span><span><kbd>确定</kbd>书籍操作</span></div></footer></main></div>`;
  }

  function highlighted(segment) {
    if (state.mode !== 'search' || !state.search.hits.length) return esc(segment.text);
    const hit = state.search.hits[state.search.index];
    const start = Math.max(segment.start,hit.start);
    const end = Math.min(segment.end,hit.end);
    if (start >= end) return esc(segment.text);
    return `${esc(segment.text.slice(0,start - segment.start))}<mark>${esc(segment.text.slice(start - segment.start,end - segment.start))}</mark>${esc(segment.text.slice(end - segment.start))}`;
  }

  function readerHTML() {
    const book = currentBook();
    const style = currentStyle();
    const theme = {dark:['#1c201d','#e9e7dc','#b7bdb4'],paper:['#e2dac4','#33382f','#50594c'],ink:['#0d110f','#dfe4da','#aeb8ad']}[style.theme];
    const page = pages()[pageIndex()];
    const scope = book.style ? '仅此书' : '全局默认';
    const locationLabel = isPDF() ? `第 ${state.reader.chapter + 1} 页 / ${book.chapters.length} 页` : `${book.format === 'TXT' ? `片段 ${state.reader.chapter + 1} · ` : ''}本${book.format === 'TXT' ? '片段' : '章'} ${pageIndex() + 1} / ${pages().length} 屏`;
    const paragraphs = page.segments.map(segment => `<p class="${segment.continuation ? 'continuation ' : ''}${state.mode === 'selection' && segment.paragraph === state.selection.index ? 'selected-paragraph' : ''}${state.mode === 'speech' && segment.paragraph === state.speech.paragraph ? 'spoken-paragraph' : ''}">${highlighted(segment)}</p>`).join('');
    return `<main class="rf-reader" style="--rf-reader-paper:${theme[0]};--rf-reader-text:${theme[1]};--rf-reader-muted:${theme[2]};--rf-column:${{narrow:480,medium:624,wide:768}[style.width]}px;--rf-copy-size:${style.size}px;--rf-copy-line:${style.line};--rf-copy-font:var(--font-${style.face === 'song' ? 'book' : 'ui'})"><header class="rf-reader-head"><div class="od-field"><p class="rf-reader-title od-truncate">${esc(book.title)}</p><p class="rf-reader-chapter">${esc(isPDF() ? `排印页 · ${currentChapter().title}` : currentChapter().title)}</p></div><span class="rf-reader-badge">${book.format} · ${book.online && !book.local ? '在线阅读' : '离线阅读'}</span></header><section class="rf-reader-body" aria-label="阅读正文"><div class="rf-reading-surface" tabindex="0" role="region" data-key="reader-surface" data-initial aria-label="正文，左右翻页，确定打开工具">${isPDF() ? `<article class="rf-pdf" style="opacity:${state.brightness/100}"><h2>${esc(currentChapter().title)}</h2><p>${esc(currentChapter().paragraphs.slice(0,2).join('\n'))}</p><small>— ${state.reader.chapter + 1} —</small></article>` : `<article class="rf-copy" style="opacity:${state.brightness/100}">${paragraphs}</article>`}</div></section><footer class="rf-reader-foot">${state.mode === 'normal' ? `<div class="rf-normal-bar"><span class="rf-reader-location">${locationLabel}</span><div class="od-row">${button('上一页','turn:-1','reader-prev')}${button('阅读工具','menu','reader-menu')}${button('下一页','turn:1','reader-next')}</div></div>` : contextHTML()}<div class="rf-reader-hint"><div class="rf-key-hints">${state.mode === 'selection' ? '<span><kbd>↑ ↓</kbd>选段</span><span><kbd>确定</kbd>保存</span><span><kbd>← →</kbd>操作</span>' : state.mode === 'auto' ? '<span><kbd>确定</kbd>暂停 / 继续</span><span><kbd>← →</kbd>手动翻页后暂停</span>' : state.mode === 'speech' ? '<span><kbd>确定</kbd>暂停 / 继续</span><span><kbd>↓</kbd>操作栏</span>' : '<span><kbd>← →</kbd>翻页</span><span><kbd>确定</kbd>阅读工具</span><span><kbd>↓</kbd>操作栏</span>'}</div><span>${state.mode === 'normal' ? `${scope} · ${style.size} 号` : locationLabel}</span></div></footer></main>`;
  }

  function contextHTML() {
    const end = button('结束','mode-end','mode-end','quiet');
    const tools = button(icon('dots'),'menu','context-menu','','aria-label="阅读工具"');
    if (state.mode === 'search') return `<div class="rf-context"><div class="rf-context-info"><strong>正文搜索</strong><small>${currentBook().format === 'EPUB' ? '本章' : '当前片段'} · 命中 ${state.search.hits.length ? state.search.index + 1 : 0} / ${state.search.hits.length}</small></div>${button('上一条','search-prev','search-prev','',`aria-disabled="${state.search.index === 0}"`)}${button('下一条','search-next','search-next','',`aria-disabled="${state.search.index >= state.search.hits.length - 1}"`)}${button('结果','search-results','search-results')}${button('回到搜索前','search-origin','search-origin')}${end}${tools}</div>`;
    if (state.mode === 'speech') return `<div class="rf-context"><div class="rf-context-info"><strong data-live-speech>${state.speech.paused ? '朗读已暂停' : '正在朗读'}</strong><small>${state.speech.reason || `${state.speech.rate} 倍 · ${speechTimerLabel()}`}</small></div>${button(icon('back'),'speech-step:-1','speech-prev','','aria-label="上一段"')}${button(state.speech.paused ? '继续' : '暂停','speech-toggle','speech-toggle','primary')}${button(icon('chevron'),'speech-step:1','speech-next','','aria-label="下一段"')}${button('语速 / 定时','speech-options','speech-options')}${end}${tools}</div>`;
    if (state.mode === 'selection') return `<div class="rf-context"><div class="rf-context-info"><strong>收藏正文段落</strong><small>已选第 ${state.selection.index + 1} 段</small></div>${button('上一段','selection-step:-1','selection-prev')}${button('下一段','selection-step:1','selection-next')}${button('保存段落','selection-save','selection-save','primary')}${button('写备注','selection-note','selection-note')}${button('退出','mode-end','mode-end','quiet')}</div>`;
    return `<div class="rf-context"><div class="rf-context-info"><strong data-live-auto>${autoStatus()}</strong><small data-live-countdown>${state.auto.interval} 秒一页${autoCanRun() ? ` · ${state.auto.remaining} 秒后翻页` : ''}</small></div>${button(state.auto.paused ? '继续' : '暂停','auto-toggle','auto-toggle','primary')}${button(`${state.auto.interval} 秒`,'auto-options','auto-options')}${button('关闭','mode-end','mode-end','quiet')}${tools}</div>`;
  }

  function marksHTML() {
    const marks = state.marks.filter(mark => state.markFilter === 'all' || mark.kind === state.markFilter);
    return `<div class="rf-app">${sidebar('marks')}<main class="rf-main"><header class="rf-page-head"><div class="od-field"><h1>我的书签</h1><p class="caption">位置与段落，放在一起。</p></div>${button('导出','export','marks-export','quiet')}</header><section class="rf-filter-row" aria-label="书签类型">${[['all','全部'],['position','位置书签'],['paragraph','段落收藏']].map(([value,label])=>opt(label,`mark-filter:${value}`,`mark-filter-${value}`,state.markFilter === value)).join('')}<span class="caption od-fill">${marks.length} 条</span></section><section class="rf-marks rf-scroll" aria-label="收藏列表">${marks.length ? marks.map(mark=>{const book=bookById(mark.bookId);return `<button class="rf-mark-card" type="button" data-key="mark-${mark.id}" data-act="mark:${mark.id}"><span class="rf-mark-heading"><strong class="od-truncate">${esc(book?.title || '书籍已移除')}</strong><span class="rf-mark-kind">${mark.kind === 'paragraph' ? '段落收藏' : '位置书签'}</span></span><span class="caption od-truncate">${esc(book?.chapters[mark.chapter]?.title || '')}${mark.changed ? ' · 原文待核对' : ''}</span>${mark.kind === 'paragraph' ? `<blockquote class="od-clamp-2">${esc(mark.text)}</blockquote>` : ''}${mark.note ? `<span class="rf-mark-note od-truncate">备注 · ${esc(mark.note)}</span>` : ''}</button>`}).join('') : `<div class="rf-empty">${icon('bookmark')}<h3>${state.marks.length ? '这里还没有这类书签' : '留住一处，或一段话'}</h3><p>阅读时打开工具，添加位置书签；也可以选择正文段落，写下自己的备注。</p>${button('返回书库','route:library','empty-marks-library','primary')}</div>`}</section><footer class="rf-page-foot"><span>收藏不改变原书内容</span><div class="rf-key-hints"><span><kbd>确定</kbd>打开操作</span><span><kbd>返回</kbd>书库</span></div></footer></main></div>`;
  }

  function panel(title, subtitle, content, footer = '', options = {}) {
    return `<div class="rf-mask ${options.modal ? 'center' : ''}"><section class="${options.modal ? 'rf-modal' : 'rf-panel'}" role="dialog" aria-modal="true" aria-labelledby="rf-layer-title"><header class="rf-panel-head"><div class="od-field"><h2 id="rf-layer-title">${title}</h2>${subtitle ? `<p class="caption">${subtitle}</p>` : ''}</div>${button(icon('close'),'close-layer','layer-close','','aria-label="关闭面板"')}</header><div class="rf-panel-content rf-scroll">${content}</div><footer class="rf-panel-foot">${footer || button('返回','close-layer','layer-back','quiet')}</footer></section></div>`;
  }

  function tool(label, helper, action, key, active = false) {
    return button(field(label,helper),action,key,`rf-tool ${active ? 'selected' : ''}`);
  }

  function menuHTML() {
    const style = currentStyle();
    const group = (title,items) => `<section class="rf-group"><h3 class="rf-group-label">${title}</h3><div class="rf-tool-grid">${items}</div></section>`;
    const location = group('位置',tool(isPDF() ? '页码跳转' : '目录 / 跳转',isPDF() ? `当前第 ${state.reader.chapter + 1} 页` : currentBook().format === 'TXT' ? '当前 TXT 片段' : '本书目录','contents','tool-contents') + tool('位置书签','留住当前页','position-mark','tool-bookmark'));
    const appearance = group('外观',tool('字体与排版',`${style.size} 号 · ${{narrow:'窄栏',medium:'中栏',wide:'宽栏'}[style.width]}`,'settings:layout','tool-layout') + tool('亮度与纸色',`${{dark:'暗色',paper:'暖纸',ink:'墨黑'}[style.theme]} · ${state.brightness}%`,'settings:appearance','tool-appearance'));
    const reading = group('阅读模式',(!isPDF() ? tool('正文搜索',currentBook().format === 'EPUB' ? '仅本章' : '仅当前片段','start-search','tool-search',state.mode === 'search') + tool('朗读正文',state.mode === 'speech' ? '正在使用' : '前台朗读','start-speech','tool-speech',state.mode === 'speech') + tool('收藏段落','上下选择正文','start-selection','tool-selection',state.mode === 'selection') : '') + tool('自动翻页',state.mode === 'auto' ? state.auto.paused ? '已暂停' : '运行中' : '按间隔翻页','auto-options','tool-auto',state.mode === 'auto'));
    return panel('阅读工具',esc(currentBook().title),`<div class="od-stack rf-menu-content">${location}${!isPDF() ? appearance : group('外观',tool('亮度与纸色','纸色与显示亮度','settings:appearance','tool-appearance'))}${reading}${isPDF() ? '<p class="caption">PDF 按页面阅读</p>' : ''}</div>`,button('返回书库','reader-exit','tool-library','quiet'));
  }

  function settingsHTML(layer) {
    const book = currentBook();
    const style = currentStyle();
    const scope = `<section class="rf-scope"><div class="rf-setting-label"><span>调整范围</span><span>${book.style ? '仅此书' : '全局默认'}</span></div><div class="rf-option-grid">${opt('全局默认','scope:global','scope-global',!book.style)}${opt('仅此书','scope:book','scope-book',!!book.style)}</div><p class="caption">${book.style ? '本书单独保存，其他书保持原样。' : '应用到所有未单独设置的书籍。'}</p></section>`;
    const tabs = !isPDF() ? `<div class="rf-option-grid" style="--rf-options:2">${opt('排版','settings-tab:layout','settings-layout',layer.tab === 'layout')}${opt('外观','settings-tab:appearance','settings-appearance',layer.tab === 'appearance')}</div>` : '';
    const layout = `<section class="rf-setting-row"><div class="rf-setting-label"><span>字号</span><span>${style.size} 号</span></div><div class="rf-stepper">${button(icon('minus'),'font:-2','font-minus','','aria-label="缩小字号"')}<output>${style.size}</output>${button(icon('plus'),'font:2','font-plus','','aria-label="放大字号"')}</div></section><section class="rf-setting-row"><div class="rf-setting-label"><span>行距</span><span>${{1.45:'紧凑',1.65:'舒适',1.85:'宽松'}[style.line]}</span></div><div class="rf-option-grid">${[1.45,1.65,1.85].map((value,index)=>opt(['紧凑','舒适','宽松'][index],`style:line:${value}`,`line-${value}`,style.line === value)).join('')}</div></section><section class="rf-setting-row"><span>字体</span><div class="rf-option-grid" style="--rf-options:2">${opt('宋体','style:face:song','face-song',style.face === 'song')}${opt('黑体','style:face:hei','face-hei',style.face === 'hei')}</div></section><section class="rf-setting-row"><span>居中栏宽</span><div class="rf-option-grid">${['narrow','medium','wide'].map((value,index)=>opt(['窄','中','宽'][index],`style:width:${value}`,`width-${value}`,style.width === value)).join('')}</div></section>`;
    const appearance = `<section class="rf-setting-row"><span>纸色</span><div class="rf-option-grid">${['dark','paper','ink'].map((value,index)=>opt(['暗色','暖纸','墨黑'][index],`style:theme:${value}`,`theme-${value}`,style.theme === value)).join('')}</div></section><section class="rf-setting-row"><div class="rf-setting-label"><span>显示亮度</span><span>${state.brightness}%</span></div><div class="rf-option-grid">${[60,80,100].map(value=>opt(`${value}%`,`brightness:${value}`,`brightness-${value}`,state.brightness === value)).join('')}</div><p class="caption">亮度对所有书籍生效。</p></section>`;
    return panel('阅读设置','调整时，正文同步预览。',`<div class="od-stack">${tabs}${scope}${layer.tab === 'layout' && !isPDF() ? layout : appearance}${book.style ? button('恢复全局默认','scope-reset','scope-reset','text') : ''}</div>`,button('完成','close-layer','settings-done','primary'));
  }

  function searchHTML() {
    const search = state.search;
    const history = currentBook().history;
    let results = '';
    if (search.loading) results = '<div class="rf-loading" role="status"><span class="caption">正在查找当前正文</span><span class="rf-skeleton"></span><span class="rf-skeleton"></span></div>';
    else if (search.ran && !search.hits.length) results = `<div class="rf-empty"><h3>这段正文中没有找到</h3><p>试试短一点的词。搜索只包含${currentBook().format === 'EPUB' ? '当前章节' : '当前 TXT 片段'}。</p></div>`;
    else if (search.hits.length) {
      const batch = search.hits.slice(search.batch * 8,search.batch * 8 + 8);
      results = `<div class="rf-result-heading"><span class="caption">${search.total > 200 ? '超过 200 条，显示前 200 条' : `${search.hits.length} 条结果`}</span><span class="caption">${currentBook().format === 'EPUB' ? '本章' : '当前片段'}</span></div><div class="rf-plain-list">${batch.map((hit,index)=>{const number=search.batch*8+index;const text=fullText();const start=Math.max(0,hit.start-16);const end=Math.min(text.length,hit.end+26);return button(`<small>第 ${number+1} 条</small><span class="od-clamp-2">${start ? '…' : ''}${esc(text.slice(start,hit.start))}<mark>${esc(text.slice(hit.start,hit.end))}</mark>${esc(text.slice(hit.end,end))}${end<text.length ? '…' : ''}</span>`,`search-hit:${number}`,`hit-${number}`,'rf-search-result',number === search.index ? 'aria-current="true"' : '')}).join('')}</div>${search.hits.length > 8 ? `<div class="rf-pages">${button('上一组','search-batch:-1','search-batch-prev','quiet',`aria-disabled="${search.batch === 0}"`)}<span>${search.batch+1} / ${Math.ceil(search.hits.length/8)}</span>${button('下一组','search-batch:1','search-batch-next','quiet',`aria-disabled="${search.batch >= Math.ceil(search.hits.length/8)-1}"`)}</div>` : ''}`;
    }
    return panel('搜索正文',`范围：${rangeName()}，不是全书检索。`,`<div class="od-stack"><section class="rf-setting-row"><label for="rf-search-input">查询词</label><div class="rf-search-form"><input id="rf-search-input" data-key="search-input" data-input="search-query" value="${esc(search.query)}" maxlength="128" placeholder="输入正文中的词句" aria-describedby="rf-search-error"/>${button(icon('search'),'search-submit','search-submit','primary','aria-label="开始搜索"')}</div>${search.error ? `<p class="rf-error" id="rf-search-error">${esc(search.error)}</p>` : ''}</section>${!search.ran && history.length ? `<section class="rf-group"><div class="rf-result-heading"><span class="caption">本书搜索历史</span>${button('清空','search-history-clear','search-history-clear','text')}</div><div class="rf-search-history">${history.map((value,index)=>button(esc(value),`search-history:${index}`,`search-history-${index}`)).join('')}</div></section>` : ''}${results}</div>`,button('返回阅读','search-close','search-close','quiet'));
  }

  function speechTimerLabel() {
    return state.speech.timer === 'none' ? '不定时' : state.speech.timer === 'end' ? (currentBook().format === 'EPUB' ? '本章末尾停止' : '本片段末尾停止') : `${state.speech.timer} 分钟后停止`;
  }

  function speechOptionsHTML() {
    return panel('朗读设置','只在前台持续，离开后需主动继续。',`<div class="od-stack"><section class="rf-setting-row"><div class="rf-setting-label"><span>语速</span><span>${state.speech.rate} 倍</span></div><div class="rf-option-grid">${[.75,1,1.25,1.5,2].map(value=>opt(`${value} 倍`,`speech-rate:${value}`,`rate-${value}`,state.speech.rate === value)).join('')}</div></section><section class="rf-setting-row"><div class="rf-setting-label"><span>停止时间</span></div><div class="rf-option-grid" style="--rf-options:2">${[['none','不定时'],['15','15 分钟'],['30','30 分钟'],['60','60 分钟'],['end',currentBook().format === 'EPUB' ? '本章末尾' : '本片段末尾']].map(([value,label])=>opt(label,`speech-timer:${value}`,`timer-${value}`,state.speech.timer === value)).join('')}</div></section></div>`,button('返回正文','close-layer','speech-options-done','primary'));
  }

  function autoStatus() {
    if (state.auto.paused) return '自动翻页已暂停';
    if (state.layers.length) return '菜单打开 · 自动翻页暂挂';
    if (document.hidden) return '已暂挂';
    return '自动翻页中';
  }
  function autoCanRun() { return state.mode === 'auto' && !state.auto.paused && !state.layers.length && !document.hidden; }
  function resetAutoClock() { state.auto.nextDue = Date.now() + state.auto.interval * 1000; state.auto.remaining = state.auto.interval; }

  function autoHTML() {
    return panel('自动翻页',state.mode === 'auto' ? state.auto.paused ? '已暂停，需要手动继续。' : '打开工具时暂挂，关闭后继续。' : '开启后按所选间隔翻到下一页。',`<div class="od-stack"><section class="rf-setting-row"><span>每页停留</span><div class="rf-option-grid" style="--rf-options:4">${[5,10,20,30,45,60,90,120].map(value=>opt(`${value} 秒`,`auto-interval:${value}`,`interval-${value}`,state.auto.interval === value)).join('')}</div></section><div class="rf-info">确定键暂停或继续。手动翻页后保持暂停，需要主动继续。<br/>朗读、搜索与选段会结束自动翻页。</div></div>`,`${button(state.mode === 'auto' ? state.auto.paused ? '继续翻页' : '暂停翻页' : '开启自动翻页','auto-start','auto-start','primary')}${state.mode === 'auto' ? button('关闭','auto-stop','auto-stop','quiet') : ''}`);
  }

  function bookActionsHTML(layer) {
    const book = bookById(layer.bookId);
    return panel('书籍操作','',`<div class="od-stack"><div class="rf-book-details"><h2>${esc(book.title)}</h2><p class="caption">${esc(book.author)} · ${book.format} · ${esc(book.sourceLabel)}</p></div>${button(book.status === 'reading' ? '继续阅读' : book.online && !book.local ? '在线阅读' : '开始阅读',`read:${book.id}`,'book-read','primary')}<section class="rf-setting-row"><span class="rf-group-label">阅读状态</span><div class="rf-option-grid">${['unread','reading','finished'].map(value=>opt(statusNames[value],`book-status:${value}`,`book-status-${value}`,book.status === value)).join('')}</div></section><div class="rf-plain-list">${button(`${field(book.pinned ? '取消置顶' : '置顶此书',book.pinned ? '已在书架前排' : '优先出现在书架前排')}${book.pinned ? icon('check','rf-check') : icon('pin')}`,'book-pin','book-pin','rf-list-row')}${button(`${field('加入书单',book.groups.map(id=>state.groups.find(group=>group.id===id)?.name).filter(Boolean).join('、') || '尚未归入书单')}${icon('chevron')}`,'book-groups','book-groups','rf-list-row')}${book.online ? button(field(book.local ? '移除离线副本' : '保存离线',book.local ? '保留在线来源' : '占用电视本地空间'),'book-offline','book-offline','rf-list-row') : ''}${button('从书库移除','book-remove','book-remove','text danger')}</div></div>`);
  }

  function markActionsHTML(layer) {
    const mark = state.marks.find(item=>item.id===layer.markId);
    if (!mark) return panel('书签已移除','', '<p>返回列表继续阅读。</p>');
    const book = bookById(mark.bookId);
    return panel(mark.kind === 'paragraph' ? '段落收藏' : '位置书签',esc(book?.title || '书籍已移除'),`<div class="od-stack"><p class="caption">${esc(book?.chapters[mark.chapter]?.title || '')}</p>${mark.kind === 'paragraph' ? `<div class="rf-quote-box rf-scroll" tabindex="0" data-key="mark-quote"><p class="rf-quote">${esc(mark.text)}</p></div>${mark.note ? `<section class="rf-group"><span class="rf-group-label">我的备注</span><p class="small" style="white-space:pre-wrap;overflow-wrap:anywhere">${esc(mark.note)}</p></section>` : '<p class="caption">还没有备注</p>'}` : ''}${mark.changed ? '<p class="rf-warning">原文有变化，回跳前需要核对这段内容。</p>' : ''}<div class="rf-plain-list">${button('回到原文',`mark-jump:${mark.id}`,'mark-jump','primary')}${mark.kind === 'paragraph' ? button('编辑备注',`mark-edit:${mark.id}`,'mark-edit','quiet') : ''}${button('删除书签',`mark-delete:${mark.id}`,'mark-delete','text danger')}</div></div>`);
  }

  function noteHTML(layer) {
    const mark = layer.markId ? state.marks.find(item=>item.id===layer.markId) : null;
    const text = mark?.text || layer.text || '';
    const note = layer.draft ?? mark?.note ?? '';
    return panel(mark ? '编辑备注' : '收藏这一段',esc(mark ? bookById(mark.bookId)?.title : currentBook().title),`<div class="od-stack"><div class="rf-quote-box rf-scroll" data-key="note-quote" tabindex="0"><p class="rf-quote">${esc(text)}</p></div><div class="rf-note-area"><label for="rf-note-input"><span>备注 <small>可留空</small></span><small id="rf-note-count">${note.length} / 2000</small></label><textarea id="rf-note-input" data-key="note-input" data-input="note" maxlength="2000" placeholder="记下自己的理解">${esc(note)}</textarea></div></div>`,`${button('取消','close-layer','note-cancel','quiet')}${button('保存','note-save','note-save','primary')}`,{modal:true});
  }

  function contentsHTML() {
    if (isPDF()) return panel('页码跳转',`当前第 ${state.reader.chapter+1} 页，共 ${currentBook().chapters.length} 页。`,`<div class="od-stack"><label class="rf-setting-row" for="rf-page-input"><span>页码</span><input inputmode="numeric" type="text" id="rf-page-input" data-key="page-input" data-input="page" value="${topLayer().page || state.reader.chapter+1}" maxlength="6"/></label>${topLayer().error ? `<p class="rf-error">${esc(topLayer().error)}</p>` : ''}</div>`,button('跳转','page-submit','page-submit','primary'));
    if (currentBook().format === 'TXT') return panel('当前 TXT 片段','左右翻页可继续前后片段。',`<div class="od-stack"><h3>${esc(currentChapter().title)}</h3><p class="caption">TXT 按已载入的正文片段阅读；此处不生成整本目录。</p></div>`,button('返回阅读','close-layer','contents-back','primary'));
    return panel('本书目录',esc(currentBook().title),`<div class="rf-plain-list">${currentBook().chapters.map((chapter,index)=>button(`<span class="od-truncate">${esc(chapter.title)}</span>${index===state.reader.chapter ? icon('check','rf-check') : icon('chevron')}`,`chapter:${index}`,`chapter-${index}`,`rf-list-row ${index===state.reader.chapter ? 'selected' : ''}`)).join('')}</div>`);
  }

  function exportText(format) {
    const lines = [format === 'md' ? '# 我的书签' : '我的书签',''];
    for (const mark of state.marks) {
      const book = bookById(mark.bookId);
      lines.push(`${format === 'md' ? '## ' : ''}${book?.title || '书籍已移除'}`,book?.chapters[mark.chapter]?.title || '',mark.kind === 'position' ? '类型：位置书签' : '类型：段落收藏');
      if (mark.kind === 'paragraph') lines.push(format === 'md' ? `> ${mark.text.replace(/\n/g,'\n> ')}` : mark.text);
      if (mark.note) lines.push('',`备注：${mark.note}`);
      lines.push('');
    }
    return lines.join('\n');
  }

  function layerHTML(layer) {
    if (layer.type === 'menu') return menuHTML();
    if (layer.type === 'settings') return settingsHTML(layer);
    if (layer.type === 'search') return searchHTML();
    if (layer.type === 'speech-options') return speechOptionsHTML();
    if (layer.type === 'auto') return autoHTML();
    if (layer.type === 'book') return bookActionsHTML(layer);
    if (layer.type === 'mark') return markActionsHTML(layer);
    if (layer.type === 'note') return noteHTML(layer);
    if (layer.type === 'contents') return contentsHTML();
    if (layer.type === 'groups') return panel('选择书单','书单与来源独立，同一本书可归入多个书单。',`<div class="rf-plain-list">${opt('全部书单','filter-group:all','group-all',state.filters.group==='all')}${state.groups.map(group=>opt(group.name,`filter-group:${group.id}`,`group-${group.id}`,state.filters.group===group.id)).join('')}${button(`${icon('plus')}<span>新建书单</span>`,'new-group','group-new','quiet')}</div>`);
    if (layer.type === 'book-groups') { const book=bookById(layer.bookId); return panel('加入书单',esc(book.title),`<div class="rf-plain-list">${state.groups.map(group=>opt(group.name,`book-group:${group.id}`,`book-group-${group.id}`,book.groups.includes(group.id))).join('')}${button('新建书单','new-group','book-group-new','quiet')}</div>`,button('完成','close-layer','book-groups-done','primary')); }
    if (layer.type === 'new-group') return panel('新建书单','按自己阅读的习惯整理。',`<div class="od-stack"><label class="rf-setting-row" for="rf-group-name"><span>书单名称</span><input id="rf-group-name" data-key="group-name" data-input="group-name" value="${esc(layer.name || '')}" maxlength="24" placeholder="例如：反复重读"/></label>${layer.error ? `<p class="rf-error">${esc(layer.error)}</p>` : ''}</div>`,button('创建','group-create','group-create','primary'),{modal:true});
    if (layer.type === 'source') return panel('筛选来源','保留当前书单和阅读状态。',`<div class="od-stack"><section class="rf-group"><span class="rf-group-label">可用方式</span><div class="rf-option-grid">${[['all','全部'],['online','在线'],['local','离线']].map(([value,label])=>opt(label,`filter-availability:${value}`,`availability-${value}`,state.filters.availability===value)).join('')}</div></section><div class="rf-plain-list">${Object.entries(sourceNames).map(([value,label])=>opt(label,`filter-source:${value}`,`source-${value}`,state.filters.source===value)).join('')}</div></div>`);
    if (layer.type === 'sort') return panel('书架排序','置顶的书籍始终优先。',`<div class="rf-plain-list">${opt('最近阅读','sort:recent','sort-recent',state.filters.sort==='recent')}${opt('按书名','sort:title','sort-title',state.filters.sort==='title')}</div>`);
    if (layer.type === 'library-search') return panel('在书库找书','按书名或作者查找。',`<div class="od-stack"><label class="rf-setting-row" for="rf-library-query"><span>书名或作者</span><input id="rf-library-query" data-key="library-query" data-input="library-query" value="${esc(layer.query ?? state.filters.query)}" maxlength="80" placeholder="输入书名或作者"/></label></div>`,button('查找','library-query-submit','library-query-submit','primary'));
    if (layer.type === 'export') return panel('导出书签','位置书签、收藏原文和备注一并导出。',`<div class="rf-plain-list">${button(field('文本文件','.txt · 通用文字格式'),'export-format:txt','export-txt','rf-list-row')}${button(field('Markdown','.md · 保留标题与引用'),'export-format:md','export-md','rf-list-row')}</div>`);
    if (layer.type === 'export-preview') return panel(layer.format==='md' ? 'Markdown 导出预览' : '文本导出预览',`${state.marks.length} 条书签`,`<textarea readonly class="rf-export-text" aria-label="导出内容" data-key="export-content">${esc(exportText(layer.format))}</textarea>`,`${button('全选文本','export-select','export-select','quiet')}${button('完成','close-layer','export-done','primary')}`,{modal:true});
    if (layer.type === 'engine') return panel('需要中文语音引擎','朗读暂时不可用。','<div class="od-stack"><p>请在电视的系统语音设置中启用中文语音引擎，再回到书房继续朗读。</p><p class="caption">阅读位置会保留。</p></div>',`${button('稍后再说','close-layer','engine-cancel','quiet')}${button('系统语音设置','engine-settings','engine-settings','primary')}`,{modal:true});
    if (layer.type === 'system-return') return panel('启用后返回继续','系统语音设置','<div class="od-stack"><p>选择支持中文的语音引擎后，返回书房 TV。</p><p class="caption">重新选择“朗读正文”即可继续。</p></div>',button('返回书房','close-layer','system-return','primary'),{modal:true});
    if (layer.type === 'confirm') return panel(esc(layer.title),'',`<p>${esc(layer.message)}</p>`,`${button('取消','close-layer','confirm-cancel','quiet')}${button(esc(layer.label || '确认'),'confirm','confirm-yes',layer.danger ? 'quiet danger' : 'primary')}`,{modal:true});
    if (layer.type === 'changed') return panel('原文位置需要核对','保留的段落与当前正文不同。',`<div class="od-stack"><p class="rf-warning">先核对收藏原文。旧位置可能已不准确，备注和收藏仍会保留。</p><div class="rf-quote-box rf-scroll" tabindex="0" data-key="changed-quote"><p class="rf-quote">${esc(layer.mark.text)}</p></div></div>`,`${button('返回收藏','close-layer','changed-back','quiet')}${button('打开所在章节','changed-open','changed-open','primary')}`,{modal:true});
    return '';
  }

  function endMode(restoreSearch = false) {
    if (restoreSearch && state.search.origin) state.reader = {...state.search.origin};
    state.mode = 'normal';
    state.speech.nextDue = 0;
    state.speech.reason = '';
    state.auto.nextDue = 0;
    if (state.route === 'reader') savePosition();
  }

  function readBook(id, position) {
    if (state.route === 'reader') savePosition();
    const book = bookById(id);
    if (!book) return;
    endMode();
    state.reader = {bookId:id,...(position || book.progress)};
    state.reader.chapter = clamp(state.reader.chapter,0,book.chapters.length-1);
    state.route = 'reader';
    state.layers = [];
    book.lastRead = Date.now();
    if (book.status === 'unread') book.status = 'reading';
    render('reader-surface');
  }

  function turn(delta, automatic = false) {
    if (!automatic && state.mode === 'auto') state.auto.paused = true;
    if (!automatic && state.mode === 'speech') endMode();
    if (isPDF()) {
      const next = clamp(state.reader.chapter + delta,0,currentBook().chapters.length-1);
      if (next === state.reader.chapter) {
        if (automatic) { state.auto.paused = true; toast('已到最后一页，自动翻页已暂停'); }
        else toast(delta > 0 ? '已到最后一页' : '已到第一页');
      }
      state.reader.chapter = next;
      state.reader.offset = 0;
    } else {
      const list = pages();
      const next = pageIndex() + delta;
      if (next >= 0 && next < list.length) state.reader.offset = list[next].start;
      else {
        const nextChapter = state.reader.chapter + delta;
        if (nextChapter < 0 || nextChapter >= currentBook().chapters.length) {
          if (automatic) { state.auto.paused = true; toast('已到正文末尾，自动翻页已暂停'); }
          else toast(delta > 0 ? '已到正文末尾' : '已到正文开头');
        } else {
          if (state.mode === 'search') { endMode(); toast('已离开搜索范围，搜索结束'); }
          state.reader.chapter = nextChapter;
          state.reader.offset = delta > 0 ? 0 : pages()[pages().length-1].start;
        }
      }
    }
    savePosition();
    if (automatic) resetAutoClock();
    render('reader-surface');
  }

  function startSearch() {
    if (isPDF()) return;
    if (state.mode !== 'search') {
      endMode();
      state.search = {query:'',hits:[],index:0,origin:{...state.reader},ran:false,loading:false,error:'',batch:0,total:0,text:fullText()};
      state.mode = 'search';
    }
    openLayer('search',{},state.search.hits.length ? `hit-${state.search.index}` : 'search-input');
  }

  function submitSearch() {
    const query = state.search.query.trim();
    if (!query) { state.search.error = '先输入要查找的词句。'; render('search-input'); return; }
    state.search.query = query;
    state.search.error = '';
    state.search.loading = true;
    state.search.ran = true;
    state.search.hits = [];
    state.search.batch = 0;
    const request = ++state.uid;
    state.search.request = request;
    const snapshot = state.search.text || fullText();
    render('search-submit');
    setTimeout(() => {
      if (state.mode !== 'search' || request !== state.search.request) return;
      const haystack = snapshot.toLocaleLowerCase();
      const needle = query.toLocaleLowerCase();
      let offset = 0;
      let total = 0;
      const hits = [];
      while (offset < haystack.length) {
        const index = haystack.indexOf(needle,offset);
        if (index === -1) break;
        if (hits.length < 200) hits.push({start:index,end:index+query.length});
        total++;
        offset = index + Math.max(1,query.length);
      }
      Object.assign(state.search,{loading:false,hits,index:0,total});
      const book = currentBook();
      book.history = [query,...book.history.filter(value=>value !== query)].slice(0,6);
      render(hits.length ? 'hit-0' : 'search-input');
    },420);
  }

  function jumpHit(index) {
    if (!state.search.hits.length) return;
    state.search.index = clamp(index,0,state.search.hits.length-1);
    state.reader.chapter = state.search.origin.chapter;
    state.reader.offset = state.search.hits[state.search.index].start;
    savePosition();
    closeAllLayers('reader-surface');
  }

  function startSpeech() {
    if (isPDF()) return;
    if (!state.speech.engine) { openLayer('engine',{},'engine-settings'); return; }
    if (state.mode !== 'speech') {
      endMode();
      state.mode = 'speech';
      state.speech.paragraph = Math.max(0,paragraphRanges().findIndex(range=>range.end > state.reader.offset));
      state.speech.paused = false;
      state.speech.reason = '';
      state.speech.nextDue = Date.now()+4200/state.speech.rate;
      if (/^\d+$/.test(state.speech.timer)) state.speech.deadline = Date.now()+Number(state.speech.timer)*60000;
    }
    revealParagraph(state.speech.paragraph);
    closeAllLayers('reader-surface');
  }

  function revealParagraph(index) {
    const range = paragraphRanges()[index];
    if (range) state.reader.offset = range.start;
    savePosition();
  }

  function speechStep(delta, timed = false) {
    const ranges = paragraphRanges();
    const next = state.speech.paragraph + delta;
    if (next >= ranges.length && timed) {
      if (state.speech.timer === 'end' || state.reader.chapter >= currentBook().chapters.length-1) {
        endMode(); render('reader-surface'); toast('朗读已结束'); return;
      }
      state.reader.chapter++;
      state.speech.paragraph = 0;
    } else state.speech.paragraph = clamp(next,0,ranges.length-1);
    revealParagraph(state.speech.paragraph);
    state.speech.nextDue = Date.now()+4200/state.speech.rate;
    render(timed ? document.activeElement?.dataset.key : 'reader-surface');
  }

  function toggleSpeech() {
    state.speech.paused = !state.speech.paused;
    state.speech.reason = '';
    state.speech.nextDue = Date.now()+4200/state.speech.rate;
    render(document.activeElement?.dataset.key);
  }

  function startSelection() {
    if (isPDF()) return;
    endMode();
    state.mode = 'selection';
    state.selection.index = Math.max(0,paragraphRanges().findIndex(range=>range.end > state.reader.offset));
    revealParagraph(state.selection.index);
    closeAllLayers('reader-surface');
  }

  function selectionStep(delta) {
    state.selection.index = clamp(state.selection.index+delta,0,paragraphRanges().length-1);
    revealParagraph(state.selection.index);
    render('reader-surface');
  }

  function editSelection(withNote) {
    const range = paragraphRanges()[state.selection.index];
    if (!range) return;
    openLayer('note',{text:range.text,offset:range.start,draft:''},withNote ? 'note-input' : 'note-save');
  }

  function positionBookmark() {
    const duplicate = state.marks.find(mark=>mark.kind==='position' && mark.bookId===state.reader.bookId && mark.chapter===state.reader.chapter && mark.offset===state.reader.offset);
    if (duplicate) { toast('此位置已有书签'); return; }
    state.marks.unshift({id:`mark-${++state.uid}`,kind:'position',bookId:state.reader.bookId,chapter:state.reader.chapter,offset:state.reader.offset,text:currentChapter().title,note:''});
    toast('已添加位置书签');
  }

  function saveNote() {
    const layer = topLayer();
    const draft = safeSlice(layer.draft || '',0,2000);
    if (layer.markId) {
      const mark = state.marks.find(item=>item.id===layer.markId);
      if (mark) mark.note = draft;
      closeLayer();
      toast('备注已保存');
    } else {
      state.marks.unshift({id:`mark-${++state.uid}`,kind:'paragraph',bookId:state.reader.bookId,chapter:state.reader.chapter,offset:layer.offset,text:layer.text,note:draft,changed:false});
      endMode();
      closeAllLayers('reader-surface');
      toast(draft ? '段落与备注已收藏' : '段落已收藏');
    }
  }

  function jumpMark(mark, acknowledge = false) {
    if (!mark) return;
    const book = bookById(mark.bookId);
    if (!book) { toast('这本书已不在书库中'); return; }
    const text = book.chapters[mark.chapter].paragraphs.join('\n\n');
    const actual = mark.kind === 'paragraph' ? text.indexOf(mark.text) : mark.offset;
    if (mark.kind === 'paragraph' && (mark.changed || actual === -1) && !acknowledge) { openLayer('changed',{mark},'changed-back'); return; }
    readBook(mark.bookId,{chapter:mark.chapter,offset:acknowledge ? 0 : Math.max(0,actual)});
    if (mark.kind === 'paragraph' && !acknowledge && !isPDF()) {
      state.mode = 'selection';
      state.selection.index = Math.max(0,paragraphRanges().findIndex(range=>range.end > actual));
      render('reader-surface');
    }
  }

  function confirmAction(title,message,operation,label = '确认',danger = false) {
    openLayer('confirm',{title,message,operation,label,danger},danger ? 'confirm-cancel' : 'confirm-yes');
  }

  function actionBook() {
    return bookById([...state.layers].reverse().find(layer=>layer.bookId)?.bookId);
  }

  function act(action) {
    const [type,value,detail] = action.split(':');
    if (type === 'close-layer') { closeLayer(); return; }
    if (type === 'route') {
      if (state.route === 'reader') { savePosition(); endMode(); }
      state.route = value;
      state.layers = [];
      render(value === 'library' ? state.lastLibraryKey : 'mark-filter-all');
      return;
    }
    if (type === 'book') { state.lastLibraryKey=`book-${value}`; openLayer('book',{bookId:value},'book-read'); return; }
    if (type === 'read') { readBook(value); return; }
    if (type === 'menu') { openLayer('menu',{},'tool-contents'); return; }
    if (type === 'reader-exit') { savePosition(); endMode(); state.route='library'; closeAllLayers(state.lastLibraryKey); return; }
    if (type === 'turn') { turn(Number(value)); return; }
    if (type === 'groups') { openLayer('groups',{},`group-${state.filters.group}`); return; }
    if (type === 'source-filter') { openLayer('source',{},`source-${state.filters.source}`); return; }
    if (type === 'sort' && !value) { openLayer('sort',{},`sort-${state.filters.sort}`); return; }
    if (type === 'sort') { state.filters.sort=value; closeLayer(); return; }
    if (type === 'filter-status') { state.filters.status=value; render(`filter-status-${value}`); return; }
    if (type === 'filter-group') { state.filters.group=value; closeLayer(); return; }
    if (type === 'filter-source') { state.filters.source=value; closeLayer(); return; }
    if (type === 'filter-availability') { state.filters.availability=value; render(`availability-${value}`); return; }
    if (type === 'clear-filters') { state.filters={...state.filters,group:'all',status:'all',source:'all',availability:'all',query:''}; render('filter-group'); return; }
    if (type === 'clear-query') { state.filters.query=''; render('library-search'); return; }
    if (type === 'library-search') { openLayer('library-search',{query:state.filters.query},'library-query'); return; }
    if (type === 'library-query-submit') { state.filters.query=(topLayer().query || '').trim(); closeAllLayers('filter-group'); return; }
    if (type === 'library-retry') { state.libraryStatus='loading'; render('nav-library'); setTimeout(()=>{state.libraryStatus='ready';render('filter-group');},650); return; }
    if (type === 'book-status') { actionBook().status=value; render(`book-status-${value}`); return; }
    if (type === 'book-pin') { const book=actionBook(); book.pinned=!book.pinned; render('book-pin'); return; }
    if (type === 'book-groups') { openLayer('book-groups',{bookId:actionBook().id},'book-groups-done'); return; }
    if (type === 'book-group') { const book=actionBook(); book.groups=book.groups.includes(value)?book.groups.filter(id=>id!==value):[...book.groups,value]; render(`book-group-${value}`); return; }
    if (type === 'new-group') { openLayer('new-group',{name:''},'group-name'); return; }
    if (type === 'group-create') {
      const layer=topLayer(); const name=(layer.name || '').trim();
      if (!name || state.groups.some(group=>group.name===name)) { layer.error=name?'已有同名书单，请换个名称。':'填写一个书单名称。'; render('group-name'); return; }
      const group={id:`group-${++state.uid}`,name}; state.groups.push(group);
      const book=actionBook(); if (book) book.groups.push(group.id);
      closeLayer(); toast(book?'书单已创建，此书已加入':'书单已创建'); return;
    }
    if (type === 'book-offline') { const book=actionBook(); confirmAction(book.local?'移除离线副本？':'保存为离线副本？',book.local?'在线来源仍会保留，需要联网时可继续阅读。':'书籍会占用电视本地空间，在线来源仍会保留。',{kind:'offline',bookId:book.id},book.local?'移除副本':'保存离线',book.local); return; }
    if (type === 'book-remove') { const book=actionBook(); confirmAction('从书库移除？',`移除《${book.title}》的书库入口，不删除来源中的原书。`,{kind:'book-delete',bookId:book.id},'移除',true); return; }
    if (type === 'settings') { openLayer('settings',{tab:value || 'layout'},value==='appearance'?'theme-'+currentStyle().theme:'font-plus'); return; }
    if (type === 'settings-tab') { topLayer().tab=value; render(`settings-${value}`); return; }
    if (type === 'scope') {
      if (value==='book' && !currentBook().style) { currentBook().style={...state.globalStyle}; render('scope-book'); }
      else if (value==='global' && currentBook().style) confirmAction('使用全局默认？','本书单独保存的排版与纸色将恢复为全局默认。',{kind:'scope-reset'},'使用全局默认');
      return;
    }
    if (type === 'scope-reset') { confirmAction('恢复全局默认？','本书将跟随全局默认，其他书的设置不变。',{kind:'scope-reset'},'恢复'); return; }
    if (type === 'font') { currentStyle().size=clamp(currentStyle().size+Number(value),16,40); render(value==='2'?'font-plus':'font-minus'); return; }
    if (type === 'style') { currentStyle()[value]=value==='line'?Number(detail):detail; render(`${value==='width'?'width':value==='face'?'face':value==='theme'?'theme':'line'}-${detail}`); return; }
    if (type === 'brightness') { state.brightness=Number(value); render(`brightness-${value}`); return; }
    if (type === 'contents') { openLayer('contents',{},isPDF()?'page-input':`chapter-${state.reader.chapter}`); return; }
    if (type === 'chapter') { state.reader.chapter=Number(value);state.reader.offset=0;endMode();closeAllLayers('reader-surface');return; }
    if (type === 'page-submit') { const layer=topLayer();const number=Number(layer.page || state.reader.chapter+1);if (!Number.isInteger(number)||number<1||number>currentBook().chapters.length) { layer.error=`输入 1 到 ${currentBook().chapters.length} 之间的页码。`;render('page-input');return; }state.reader.chapter=number-1;state.reader.offset=0;savePosition();closeAllLayers('reader-surface');return; }
    if (type === 'position-mark') { positionBookmark(); return; }
    if (type === 'start-search') { startSearch(); return; }
    if (type === 'search-submit') { submitSearch(); return; }
    if (type === 'search-history') { state.search.query=currentBook().history[Number(value)];submitSearch();return; }
    if (type === 'search-history-clear') { currentBook().history=[];render('search-input');return; }
    if (type === 'search-hit') { jumpHit(Number(value)); return; }
    if (type === 'search-prev' || type === 'search-next') { const next=state.search.index+(type==='search-prev'?-1:1);if(next<0||next>=state.search.hits.length){toast(type==='search-prev'?'已是第一条':'已是最后一条');return;}jumpHit(next);return; }
    if (type === 'search-results') { openLayer('search',{},`hit-${state.search.index}`);return; }
    if (type === 'search-origin') { endMode(true); closeAllLayers('reader-surface'); return; }
    if (type === 'search-close') { if(!state.search.hits.length){endMode(true);}closeAllLayers('reader-surface');return; }
    if (type === 'search-batch') { state.search.batch=clamp(state.search.batch+Number(value),0,Math.ceil(state.search.hits.length/8)-1);render(`hit-${state.search.batch*8}`);return; }
    if (type === 'start-speech') { startSpeech();return; }
    if (type === 'speech-toggle') { toggleSpeech();return; }
    if (type === 'speech-step') { speechStep(Number(value));return; }
    if (type === 'speech-options') { openLayer('speech-options',{},`rate-${state.speech.rate}`);return; }
    if (type === 'speech-rate') { state.speech.rate=Number(value);state.speech.nextDue=Date.now()+4200/state.speech.rate;render(`rate-${value}`);return; }
    if (type === 'speech-timer') { state.speech.timer=value;state.speech.deadline=/^\d+$/.test(value)?Date.now()+Number(value)*60000:0;render(`timer-${value}`);return; }
    if (type === 'engine-settings') { topLayer().type='system-return';render('system-return');return; }
    if (type === 'start-selection') { startSelection();return; }
    if (type === 'selection-step') { selectionStep(Number(value));return; }
    if (type === 'selection-save' || type === 'selection-note') { editSelection(type==='selection-note');return; }
    if (type === 'note-save') { saveNote();return; }
    if (type === 'mark-filter') { state.markFilter=value;render(`mark-filter-${value}`);return; }
    if (type === 'mark') { openLayer('mark',{markId:value},'mark-jump');return; }
    if (type === 'mark-edit') { const mark=state.marks.find(item=>item.id===value);openLayer('note',{markId:value,draft:mark.note},'note-input');return; }
    if (type === 'mark-jump') { jumpMark(state.marks.find(item=>item.id===value));return; }
    if (type === 'changed-open') { jumpMark(topLayer().mark,true);return; }
    if (type === 'mark-delete') { confirmAction('删除这条书签？','原书不受影响，收藏原文和备注会一并移除。',{kind:'mark-delete',markId:value},'删除',true);return; }
    if (type === 'export') { if(!state.marks.length){toast('还没有可导出的书签');return;}openLayer('export',{},'export-txt');return; }
    if (type === 'export-format') { openLayer('export-preview',{format:value},'export-content');return; }
    if (type === 'export-select') { const node=$('[data-key="export-content"]');node.focus();node.select();toast('已选中文本');return; }
    if (type === 'auto-options') { openLayer('auto',{},`interval-${state.auto.interval}`);return; }
    if (type === 'auto-interval') { state.auto.interval=Number(value);resetAutoClock();render(`interval-${value}`);return; }
    if (type === 'auto-start') { if(state.mode==='auto')state.auto.paused=!state.auto.paused;else{endMode();state.mode='auto';state.auto.paused=false;}resetAutoClock();closeAllLayers('reader-surface');return; }
    if (type === 'auto-toggle') { state.auto.paused=!state.auto.paused;resetAutoClock();render(document.activeElement?.dataset.key);return; }
    if (type === 'auto-stop' || type === 'mode-end') { endMode();closeAllLayers('reader-surface');return; }
    if (type === 'confirm') {
      const operation=topLayer().operation;
      if(operation.kind==='scope-reset'){currentBook().style=null;closeLayer();render('scope-global');}
      if(operation.kind==='offline'){const book=bookById(operation.bookId);book.local=!book.local;closeLayer();toast(book.local?'已保存离线副本':'离线副本已移除');}
      if(operation.kind==='book-delete'){state.books=state.books.filter(book=>book.id!==operation.bookId);state.layers=[];render('filter-group');toast('已从书库移除');}
      if(operation.kind==='mark-delete'){state.marks=state.marks.filter(mark=>mark.id!==operation.markId);state.layers=[];render('marks-export');toast('书签已删除');}
      return;
    }
  }

  function back() {
    if (state.layers.length) { closeLayer();return; }
    if (state.route==='reader') {
      if (state.mode==='search') { openLayer('search',{},`hit-${state.search.index}`);return; }
      if (state.mode!=='normal') { endMode();render('reader-surface');return; }
      savePosition();state.route='library';render(state.lastLibraryKey);return;
    }
    if (state.route==='marks') {state.route='library';render('nav-marks');return;}
    focusKey('nav-library');
  }

  function moveFocus(direction) {
    const active=document.activeElement;
    const rect=active?.getBoundingClientRect();
    if(!rect){focusKey();return;}
    const vertical=direction==='ArrowUp'||direction==='ArrowDown';
    const sign=direction==='ArrowDown'||direction==='ArrowRight'?1:-1;
    const cx=rect.left+rect.width/2;
    const cy=rect.top+rect.height/2;
    const choices=$$('button,input,textarea,[tabindex="0"]',activeScope()).filter(node=>!node.hidden&&node!==active&&!node.closest('[hidden]'));
    let best=null;
    let score=Infinity;
    for(const node of choices){
      const box=node.getBoundingClientRect();
      if(!box.width||!box.height)continue;
      const x=box.left+box.width/2-cx;
      const y=box.top+box.height/2-cy;
      const primary=(vertical?y:x)*sign;
      const secondary=Math.abs(vertical?x:y);
      if(primary<2)continue;
      const lane=vertical?Math.max(0,secondary-(rect.width+box.width)/2):Math.max(0,secondary-(rect.height+box.height)/2);
      const candidate=primary+secondary*.6+lane*3;
      if(candidate<score){score=candidate;best=node;}
    }
    if(best)focusKey(best.dataset.key);
  }

  document.addEventListener('click',event=>{
    const target=event.target.closest('[data-act]');
    if(!target||!activeScope().contains(target))return;
    event.preventDefault();
    target.focus({preventScroll:true});
    act(target.dataset.act);
  });

  document.addEventListener('input',event=>{
    const node=event.target;
    const type=node.dataset.input;
    if(type==='search-query')state.search.query=node.value;
    if(type==='note'){topLayer().draft=node.value.slice(0,2000);const count=$('#rf-note-count');if(count)count.textContent=`${node.value.length} / 2000`;}
    if(type==='library-query')topLayer().query=node.value;
    if(type==='group-name')topLayer().name=node.value;
    if(type==='page')topLayer().page=node.value;
  });

  document.addEventListener('keydown',event=>{
    if(event.isComposing)return;
    const node=document.activeElement;
    const textField=node?.matches('input,textarea');
    if(event.key==='Escape'||(event.key==='Backspace'&&!textField)){event.preventDefault();back();return;}
    if(event.key==='Tab'){
      const items=$$('button,input,textarea,[tabindex="0"]',activeScope()).filter(item=>!item.closest('[hidden]'));
      const index=items.indexOf(node);event.preventDefault();focusKey(items[(index+(event.shiftKey?-1:1)+items.length)%items.length]?.dataset.key);return;
    }
    if(textField){
      if(event.key==='Enter'&&node.tagName==='INPUT'){
        event.preventDefault();
        const type=node.dataset.input;
        if(type==='search-query')submitSearch();
        if(type==='library-query')act('library-query-submit');
        if(type==='group-name')act('group-create');
        if(type==='page')act('page-submit');
        return;
      }
      if(event.key==='ArrowLeft'||event.key==='ArrowRight')return;
      if(node.tagName==='TEXTAREA'){
        if(event.key==='ArrowUp'&&node.selectionStart>0)return;
        if(event.key==='ArrowDown'&&node.selectionEnd<node.value.length)return;
        if(event.key==='Enter')return;
      }
    }
    if(node?.matches('.rf-quote-box')&&['ArrowUp','ArrowDown'].includes(event.key)){
      const delta=event.key==='ArrowDown'?72:-72;
      const limit=Math.max(0,node.scrollHeight-node.clientHeight);
      if((delta>0&&node.scrollTop<limit)||(delta<0&&node.scrollTop>0)){
        event.preventDefault();node.scrollTop=clamp(node.scrollTop+delta,0,limit);return;
      }
    }
    if(!state.layers.length&&state.route==='reader'){
      const surface=node?.dataset.key==='reader-surface';
      if(surface){
        if(event.key==='Enter'){
          event.preventDefault();
          if(state.mode==='auto')act('auto-toggle');
          else if(state.mode==='speech')toggleSpeech();
          else if(state.mode==='selection')editSelection(false);
          else openLayer('menu',{},'tool-contents');
          return;
        }
        if(state.mode==='selection'&&['ArrowUp','ArrowDown'].includes(event.key)){event.preventDefault();selectionStep(event.key==='ArrowDown'?1:-1);return;}
        if(['ArrowLeft','ArrowRight'].includes(event.key)){
          event.preventDefault();
          if(state.mode==='selection')focusKey(event.key==='ArrowLeft'?'selection-prev':'selection-save');
          else turn(event.key==='ArrowRight'?1:-1);
          return;
        }
        if(event.key==='ArrowDown'){event.preventDefault();focusKey(state.mode==='normal'?'reader-menu':state.mode==='search'?'search-results':state.mode==='speech'?'speech-toggle':state.mode==='auto'?'auto-toggle':'selection-save');return;}
      }else if(event.key==='ArrowUp'){event.preventDefault();focusKey('reader-surface');return;}
    }
    if(['ArrowLeft','ArrowRight','ArrowUp','ArrowDown'].includes(event.key)){event.preventDefault();moveFocus(event.key);return;}
    if(event.key==='Enter'&&node?.dataset.act){event.preventDefault();act(node.dataset.act);}
  });

  document.addEventListener('visibilitychange',()=>{
    if(state.mode==='speech'&&document.hidden){state.speech.paused=true;state.speech.reason='离开后已暂停，请主动继续';render(document.activeElement?.dataset.key);}
    resetAutoClock();
  });

  setInterval(()=>{
    if(state.route!=='reader')return;
    const now=Date.now();
    if(state.mode==='auto'){
      if(!autoCanRun()){resetAutoClock();return;}
      state.auto.remaining=Math.max(0,Math.ceil((state.auto.nextDue-now)/1000));
      const counter=$('[data-live-countdown]');if(counter)counter.textContent=`${state.auto.interval} 秒一页 · ${state.auto.remaining} 秒后翻页`;
      if(now>=state.auto.nextDue)turn(1,true);
    }
    if(state.mode==='speech'){
      if(state.speech.deadline&&now>=state.speech.deadline){endMode();render('reader-surface');toast('定时已到，朗读结束');return;}
      if(!state.speech.paused&&!state.layers.length&&!document.hidden&&now>=state.speech.nextDue)speechStep(1,true);
    }
  },250);

  function resize() {
    const scale=Math.min(window.innerWidth/960,window.innerHeight/540);
    document.documentElement.style.setProperty('--rf-scale',String(scale));
  }
  window.addEventListener('resize',resize);
  resize();
  if(params.get('view')==='reader'){
    const requested=params.get('format');
    const book=requested==='pdf'?bookById('shijing-pdf'):requested==='txt'?state.books.find(item=>item.format==='TXT'):state.books[0];
    state.reader={bookId:book.id,...book.progress};state.route='reader';
  }
  if(params.get('view')==='marks')state.route='marks';
  render(state.route==='reader'?'reader-surface':state.route==='marks'?'mark-filter-all':'book-chuanxilu');
  if(state.libraryStatus==='loading')setTimeout(()=>{state.libraryStatus='ready';render('book-chuanxilu');},900);
})();
