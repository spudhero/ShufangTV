'use strict';
(() => {
  const $ = (selector, parent = document) => parent.querySelector(selector);
  const stage = $('#tv-stage');
  const shell = $('#preview-shell');
  const note = $('#preview-note');
  const example = {
    title: '书房使用指南：遥控器操作、图片与 PDF 阅读，以及自在阅读的客厅使用手册',
    author: '书房 TV 使用指南',
    chapters: ['一卷在手，片刻清宁', '用遥控器自在阅读', '图片与 PDF 阅读说明'],
    paragraphs: [
      '欢迎来到书房 TV。在客厅里坐定，让文字回到眼前。一卷在手，片刻清宁。每一次阅读，都可以从上次停下的地方继续。',
      '用遥控器左右翻页，按确定键打开阅读工具。调整字体与纸色，让正文保持舒适。返回时先收起工具，再回到书库。',
      '图片可以选择整图显示，也可以按屏幕宽度阅读。PDF 的 100% 表示适合屏宽；上下键滚动页面，放大后可以移动画面。'
    ]
  };
  const state = { view: 'home', panel: null, engine: 'missing', format: 'EPUB', resolution: '1080', modal: false, size: 22, face: 'serif', line: 'comfortable', width: 'middle', paper: 'dark', chapter: 0, paragraph: 0, paused: true, speed: 1, timer: '30', auto: false, autoPaused: false, seconds: 20, page: 1, bookmark: false, collected: false, query: '', searchNotice: '', selection: false, returnView: 'home', panelReturn: 'menu-speech', scroll: {}, focus: {}, remembered: 'continue', systemNotice: false };
  const escapeHTML = value => String(value).replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
  const icon = name => `<svg class="od-icon" viewBox="0 0 24 24" aria-hidden="true">${({ home: '<path d="M3 11 12 4l9 7M5 10v10h14V10M9 20v-7h6v7"/>', library: '<path d="M4 4h5v16H4zM12 4h4v16h-4zM19 5l3 14"/>', source: '<path d="M5 8h14v12H5zM8 8V4h8v4M9 13h6"/>', bookmark: '<path d="M6 3h12v18l-6-4-6 4z"/>', settings: '<path d="M4 7h16M4 17h16M8 4v6M16 14v6"/>', close: '<path d="m6 6 12 12M18 6 6 18"/>', back: '<path d="m14 5-7 7 7 7M7 12h14"/>', book: '<path d="M12 5C8 2 4 3 2 4v15c4-1 7-1 10 1 3-2 6-2 10-1V4c-2-1-6-2-10 1Zm0 0v15"/>' }[name] || '<path d="M5 12h14"/>')}</svg>`;
  const button = (label, action, key, cls = 'action', attrs = '') => `<button type="button" class="${cls}" data-action="${action}" data-focus-key="${key}" ${attrs}>${label}</button>`;
  const choice = (label, group, value, selected) => button(escapeHTML(label), `choice:${group}:${value}`, `${group}-${value}`, 'choice', `aria-pressed="${selected}"`);
  const textFormat = () => state.format === 'EPUB' || state.format === 'TXT';
  const engineCopy = () => state.engine === 'missing' ? '未检测到语音引擎' : '开启朗读时检查中文支持';
  function previewMessage(message) { note.textContent = message; }
  function sidebar(current) {
    return `<aside class="sidebar"><div class="brand"><strong>书房 TV</strong><span>一卷在手，片刻清宁</span></div><nav aria-label="主导航">
      ${button(`${icon('home')}<span>首页</span>`, 'home', 'nav-home', 'nav-item', current === 'home' ? 'aria-current="page"' : '')}
      <a class="nav-item" href="reading-features.html" data-focus-key="nav-library">${icon('library')}<span>我的书库</span></a>
      <a class="nav-item" href="cloud-library.html" data-focus-key="nav-sources">${icon('source')}<span>书籍来源</span></a>
      <a class="nav-item" href="reading-features.html" data-focus-key="nav-bookmarks">${icon('bookmark')}<span>我的书签</span></a>
      ${button(`${icon('settings')}<span>设置</span>`, 'settings', 'nav-settings', 'nav-item', current === 'settings' ? 'aria-current="page"' : '')}
      </nav><p class="side-verse">慢一点，<br>读进去。</p></aside>`;
  }
  function home() {
    return `<div class="app-shell" ${state.modal ? 'inert' : ''}>${sidebar('home')}<section class="page"><header class="page-header"><div><h1>接着，读下去。</h1><p>从上次停下的地方开始</p></div></header><div class="page-body od-scroll" data-scroll-id="home">
      <section class="hero" aria-label="继续阅读"><div class="synthetic-cover" aria-hidden="true"><span>书房使用指南</span></div><div class="hero-info">
      <h2 class="book-title od-clamp-2" aria-label="${escapeHTML(example.title)}">${escapeHTML(example.title)}</h2><p class="hero-author">${example.author}</p>
      <div class="reading-position"><span>第一章 · 一卷在手，片刻清宁</span><small>已保存正文位置</small></div><div class="hero-actions">${button(`${icon('book')}<span>继续阅读</span>`, 'continue', 'continue', 'action primary', `aria-label="继续阅读：${escapeHTML(example.title)}"`)}${button('书名详情', 'title', 'title-details', 'action quiet')}</div></div></section>
      <div class="section-heading"><h2>最近阅读</h2><small>阅读指南</small></div><div class="recent-list">${button('<span class="format-mark">TXT</span><span class="od-field od-fill"><span class="od-truncate">遥控器操作练习手册</span><small>本地 · 使用指南</small></span>', 'open-txt', 'recent-txt', 'recent-card')}${button('<span class="format-mark">PDF</span><span class="od-field od-fill"><span class="od-truncate">图片与 PDF 阅读指南</span><small>本地 · 使用指南</small></span>', 'open-pdf', 'recent-pdf', 'recent-card')}</div>
      </div><footer class="page-footer"><span>↑↓←→ 移动</span><span>确定 打开</span><span>返回 回到导航</span></footer></section></div>`;
  }
  function speechSettings() {
    const missing = state.engine === 'missing';
    return `<div class="app-shell">${sidebar('settings')}<section class="page"><header class="page-header"><div><h1>设置</h1><p>全局阅读默认与使用说明</p></div>${button('阅读帮助', 'help', 'jump-help', 'action quiet')}</header><div class="page-body od-scroll" data-scroll-id="settings">
      <section class="settings-section" aria-labelledby="voice-heading"><h2 id="voice-heading">朗读与语音</h2><div class="engine-state"><div><h3>${missing ? '未检测到语音引擎' : '检测到语音引擎'}</h3><p>${missing ? '这台电视暂时没有可用的朗读服务。请在系统中安装或启用语音引擎，再返回书房检查。' : '开启朗读时检查中文支持。检测到服务，不代表中文语音已经可用。'}</p></div>${button('系统语音设置', 'system-settings', 'system-settings', 'action')}</div><div class="settings-hint"><span>仅在前台朗读；返回应用后需主动继续。</span>${button('重新检测', 'detect', 'detect', 'action quiet')}</div></section>
      <section class="settings-section"><h2>阅读默认</h2><p class="scope-note">以下设置用于未单独调整过的书籍。</p>${appearanceRows()}</section>
      <section class="settings-section" aria-labelledby="help-heading"><h2 id="help-heading">阅读帮助</h2>
      <article class="help-block" tabindex="0" data-focus-key="help-text"><h3>文字阅读</h3><p>左右键翻页，确定键打开阅读工具。返回键先收起工具，再返回书库。</p><p>朗读适用于 EPUB 和 TXT 正文。没有语音引擎时，可前往系统语音设置；开启朗读时还会检查中文支持。</p></article>
      <article class="help-block" tabindex="0" data-focus-key="help-image"><h3>图片：整图与屏宽</h3><p><strong>整图</strong>：完整显示一张图片，保留全部画面。</p><p><strong>屏宽</strong>：按屏幕宽度显示；长图使用上下键滚动，左右键切换图片。</p></article>
      <article class="help-block" tabindex="0" data-focus-key="help-pdf"><h3>PDF：100% 即适合屏宽</h3><p><strong>100%</strong> 以适合屏幕宽度为基准，不代表原纸张的实际尺寸。</p><p><strong>上下滚动</strong>：用上下键查看当前页，滚动到边缘后继续翻页；也可使用菜单或底部翻页控件。</p><p><strong>放大与平移</strong>：放大后左右键横向平移，上下键继续滚动。返回键先收起工具，再退出阅读。</p><p>PDF 和纯图片不提供文字朗读，也不会自动识别图片中的文字。</p></article>
      <article class="help-block" tabindex="0" data-focus-key="help-speech"><h3>朗读时的遥控操作</h3><p>暂停或继续、上一段、下一段均可在正文下方的控制栏操作。语速和定时在朗读设置中调整。</p><p>打开其他应用或让书房进入后台后，朗读暂停。回到书房时，由你主动继续。</p></article>
      ${button('回到设置顶部', 'settings-top', 'settings-top', 'action quiet')}
      </section></div><footer class="page-footer"><span>↑↓ 选择并滚动</span><span>确定 调整</span><span>返回 首页</span></footer></section></div>`;
  }
  function appearanceRows() {
    return `<div class="settings-row"><div class="od-field"><span>字号</span><small>正文大小</small></div><div class="choice-row">${button('−', 'font-down', 'font-down', 'choice', 'aria-label="减小字号"')}<output class="step-value">${state.size} 号</output>${button('+', 'font-up', 'font-up', 'choice', 'aria-label="增大字号"')}</div></div>
      <div class="settings-row"><span>字体</span><div class="choice-row">${choice('宋体', 'face', 'serif', state.face === 'serif')}${choice('黑体', 'face', 'sans', state.face === 'sans')}</div></div>
      <div class="settings-row"><span>行距</span><div class="choice-row">${choice('紧凑', 'line', 'compact', state.line === 'compact')}${choice('舒适', 'line', 'comfortable', state.line === 'comfortable')}${choice('宽松', 'line', 'loose', state.line === 'loose')}</div></div>
      <div class="settings-row"><span>居中栏宽</span><div class="choice-row">${choice('窄', 'width', 'narrow', state.width === 'narrow')}${choice('中', 'width', 'middle', state.width === 'middle')}${choice('宽', 'width', 'wide', state.width === 'wide')}</div></div>
      <div class="settings-row"><span>纸色</span><div class="choice-row">${choice('暖纸', 'paper', 'warm', state.paper === 'warm')}${choice('暗色', 'paper', 'dark', state.paper === 'dark')}${choice('墨黑', 'paper', 'black', state.paper === 'black')}</div></div>`;
  }
  const tile = (title, sub, action, key, cls = '') => button(`<span>${title}</span>${sub ? `<small>${sub}</small>` : ''}`, action, key, `tool-tile ${cls}`);
  function menuContents() {
    return `<section class="tool-group"><h3>阅读</h3><div class="tool-grid">${tile(state.format === 'PDF' ? '页码跳转' : '目录与跳转', '', 'panel:toc', 'menu-toc')}${tile('自动翻页', state.auto ? (state.autoPaused ? '已暂停' : '已开启') : '未开启', 'panel:auto', 'menu-auto')}${textFormat() ? `${tile('搜索当前正文', state.format === 'EPUB' ? '仅本章内容' : '仅当前片段', 'panel:search', 'menu-search')}${tile('朗读正文', engineCopy(), 'panel:speech', 'menu-speech', 'speech-entry')}` : ''}</div></section>
      <section class="tool-group"><h3>记录</h3><div class="tool-grid">${tile(state.bookmark ? '已添加位置书签' : '添加位置书签', '', 'bookmark', 'menu-bookmark')}${textFormat() ? tile('收藏正文段落', '', 'panel:selection', 'menu-selection') : ''}</div></section>
      <section class="tool-group"><h3>显示</h3><div class="tool-grid">${textFormat() ? tile('字体与排版', `${state.size} 号 · 全局默认`, 'panel:type', 'menu-type') : ''}${tile(state.format === 'PDF' ? '阅读亮度' : '亮度与纸色', state.format === 'PDF' ? `${state.brightness || 100}% · 全局` : ({ warm: '暖纸', dark: '暗色', black: '墨黑' })[state.paper], 'panel:paper', 'menu-paper')}</div></section>
      ${!textFormat() ? '<p class="scope-note">PDF 与纯图片不提供文字搜索、段落收藏和朗读。</p>' : ''}`;
  }
  function speechContents() {
    if (state.engine === 'missing') return `<section class="voice-warning"><h3>未检测到语音引擎</h3><p>这台电视暂时无法开启朗读。请先安装或启用系统语音引擎。</p>${button('系统语音设置', 'system-settings', 'reader-system', 'action')}<p class="scope-note">语音引擎可用后，开启朗读时还会检查中文支持。</p>${button('前往朗读与语音', 'voice-settings', 'reader-voice-settings', 'action quiet')}</section>`;
    if (state.engine === 'detected') return `<section class="voice-warning"><h3>检测到语音引擎</h3><p>开启朗读时检查中文支持。</p><p class="scope-note">当前尚未确认中文语音可用。</p>${button('检查并开启朗读', 'check-chinese', 'check-chinese', 'action primary')}${state.systemNotice ? '<p class="notice" role="status">中文语音尚未就绪。请在系统语音设置中确认后重试。</p>' : ''}${button('系统语音设置', 'system-settings', 'reader-system', 'action quiet')}</section>`;
    return `<p class="scope-note">仅在前台使用。离开书房后暂停，返回时需主动继续。</p><div class="tool-grid">${tile('上一段', '', 'speech-prev', 'speech-prev')}${tile('下一段', '', 'speech-next', 'speech-next')}${tile(state.paused ? '继续朗读' : '暂停朗读', '', 'speech-toggle', 'speech-toggle')}${tile('结束朗读', '', 'speech-end', 'speech-end')}</div><h3 class="panel-label">语速</h3><div class="choice-row">${[.75, 1, 1.25, 1.5, 2].map(v => choice(`${v} 倍`, 'speed', String(v), state.speed === v)).join('')}</div><h3 class="panel-label">定时结束</h3><div class="choice-row">${['15', '30', '60', 'chapter'].map(v => choice(v === 'chapter' ? '本章末尾' : `${v} 分钟`, 'timer', v, state.timer === v)).join('')}</div>`;
  }
  function panelBody() {
    switch (state.panel) {
      case 'menu': return menuContents();
      case 'speech': return speechContents();
      case 'type': return `<p class="scope-note">当前使用全局默认。</p>${appearanceRows()}`;
      case 'paper': return `${state.format === 'PDF' ? '<p class="scope-note">阅读亮度为全局设置，PDF 保留原页面颜色。</p>' : `<p class="scope-note">纸色只影响正文，阅读工具保持暗色。</p><div class="choice-row">${choice('暖纸', 'paper', 'warm', state.paper === 'warm')}${choice('暗色', 'paper', 'dark', state.paper === 'dark')}${choice('墨黑', 'paper', 'black', state.paper === 'black')}</div>`}<p class="panel-label">阅读亮度</p><div class="choice-row">${[60, 80, 100].map(v => choice(`${v}%`, 'brightness', String(v), (state.brightness || 100) === v)).join('')}</div>`;
      case 'toc': return `<p class="scope-note">${state.format === 'PDF' ? '选择页码，回到正文。' : '选择章节，回到正文。'}</p><div class="panel-list">${example.chapters.map((t, i) => tile(state.format === 'PDF' ? `第 ${i + 1} 页` : t, state.chapter === i ? '当前位置' : '', `chapter:${i}`, `chapter-${i}`)).join('')}</div>`;
      case 'search': return `<label class="panel-label" for="chapter-query">${state.format === 'EPUB' ? '搜索本章' : '搜索当前 TXT 片段'}</label><input class="search-input" id="chapter-query" data-focus-key="chapter-query" value="${escapeHTML(state.query)}" placeholder="输入要查找的文字"><p class="scope-note">${state.format === 'EPUB' ? '仅当前章节，不包含全书。' : '仅当前已打开的片段，不包含全文。'}</p>${button('查找', 'search', 'search-submit', 'action')}${state.searchNotice ? `<p class="notice" role="status">${escapeHTML(state.searchNotice)}</p>` : ''}${state.query ? `<div class="panel-list">${example.paragraphs.map((p, i) => p.includes(state.query) ? tile(escapeHTML(p), '查看所在段落', `hit:${i}`, `hit-${i}`) : '').join('')}</div>` : ''}`;
      case 'selection': return `<p class="scope-note">上下选择段落，确定保存；返回退出。</p><p class="help-block" tabindex="0" data-focus-key="selected-paragraph">${escapeHTML(example.paragraphs[state.paragraph])}</p><div class="tool-grid">${tile('上一段', '', 'selection-prev', 'selection-prev')}${tile('下一段', '', 'selection-next', 'selection-next')}</div>${button(state.collected ? '已保存段落' : '保存段落', 'collect', 'collect', 'action primary')}`;
      case 'auto': return `<p class="scope-note">打开工具时暂挂；关闭工具后继续。手动暂停或翻页后，需主动继续。</p><h3 class="panel-label">翻页间隔</h3><div class="choice-row">${[5, 10, 20, 30, 45, 60, 90, 120].map(v => choice(`${v} 秒`, 'seconds', String(v), state.seconds === v)).join('')}</div><div class="choice-row">${button(state.auto ? (state.autoPaused ? '继续自动翻页' : '暂停自动翻页') : '开启自动翻页', 'auto-toggle', 'auto-toggle', 'action primary')}${state.auto ? button('关闭自动翻页', 'auto-end', 'auto-end', 'action') : ''}</div>`;
      default: return '';
    }
  }
  function toolPanel() {
    const headings = { menu: '阅读工具', speech: '朗读与语音', type: '字体与排版', paper: state.format === 'PDF' ? '阅读亮度' : '亮度与纸色', toc: state.format === 'PDF' ? '页码跳转' : '目录与跳转', search: '搜索当前正文', selection: '收藏正文段落', auto: '自动翻页' };
    return `<aside class="tool-panel" role="dialog" aria-modal="true" aria-labelledby="panel-heading" data-focus-scope><header class="tool-panel-header"><h2 id="panel-heading">${headings[state.panel]}</h2>${button(icon('close'), 'panel-close', 'panel-close', 'icon-button', 'aria-label="收起阅读工具"')}</header><div class="panel-scroll od-scroll" data-scroll-id="panel-${state.panel}">${panelBody()}</div><footer class="tool-footer">${state.panel === 'menu' ? button('返回书库', 'home', 'reader-home', 'action quiet') : button('返回工具', 'menu', 'back-to-menu', 'action quiet')}${button('继续阅读', 'panel-close', 'resume-reading', 'action primary')}</footer></aside>`;
  }
  function reader() {
    const activeControls = state.engine === 'controls' && textFormat();
    const body = state.format === 'PDF' || state.format === 'IMAGE' ? `<h1>图片与 PDF 阅读指南</h1><p>${state.format === 'PDF' ? 'PDF 默认 100%，适合屏幕宽度。' : '整图显示，保留完整画面。'}</p><p>适合屏宽时，上下滚动；PDF 放大后可左右平移。</p>` : `<h1>${example.chapters[state.chapter]}</h1>${example.paragraphs.map((p, i) => `<p class="${(activeControls || state.selection) && state.paragraph === i ? 'highlight' : ''}" id="paragraph-${i}">${escapeHTML(p)}</p>`).join('')}`;
    return `<section class="reader ${state.panel ? 'has-panel' : ''}"><header class="reader-header" ${state.panel ? 'inert' : ''}><div class="od-field od-fill"><span class="od-truncate">书房使用指南</span><small>${state.format === 'IMAGE' ? '图片阅读' : state.format} · ${example.chapters[state.chapter]}</small></div>${button('返回书库', 'home', 'back-library', 'action quiet')}</header><div class="reader-grid"><article class="reader-copy od-scroll" ${state.panel ? 'inert' : 'tabindex="0" data-focus-key="reader-copy"'} data-scroll-id="reader-copy" aria-label="当前阅读内容">${body}</article>${state.panel ? toolPanel() : ''}</div>
      ${!state.panel && activeControls ? `<footer class="speech-strip" aria-label="朗读控制"><div class="speech-label"><strong>${state.paused ? '朗读已暂停' : '朗读控制'}</strong><small>${state.speed} 倍 · ${state.timer === 'chapter' ? '本章末尾结束' : `${state.timer} 分钟定时`} · 仅前台</small></div>${button('上一段', 'speech-prev', 'strip-prev', 'action quiet')}${button(state.paused ? '继续' : '暂停', 'speech-toggle', 'strip-toggle', 'action primary')}${button('下一段', 'speech-next', 'strip-next', 'action quiet')}${button('设置', 'panel:speech', 'strip-settings', 'action quiet')}${button('结束', 'speech-end', 'strip-end', 'action quiet')}</footer>` : `<footer class="reader-footer" ${state.panel ? 'inert' : ''}><span>${state.auto ? `自动翻页 · ${state.autoPaused ? '已暂停' : `${state.seconds} 秒`}` : state.format === 'PDF' ? '↑↓ 滚动 · 放大后 ←→ 平移' : '← → 翻页'}</span>${button('阅读工具', 'menu', 'open-menu', 'action quiet')}<small>第 ${state.page} 页</small></footer>`}</section>`;
  }
  function assets() {
    return `<section class="assets-page"><header><h1>启动器资产</h1><p class="asset-intro">沿用原书本图形 · 横幅加入“书房 TV” · 以下为设计资产预览</p></header><div class="assets-scroll od-scroll" data-scroll-id="assets"><div class="banner-row"><a class="asset-item" href="assets/ui-refinements/tv-banner-640x360.png" download data-focus-key="banner-640"><img src="assets/ui-refinements/tv-banner-640x360.png" width="640" height="360" alt="640×360电视横幅，深灰背景、原琥珀书本与书房 TV 字样"><strong>640 × 360 px</strong><small>高分辨率横幅 · 确定键获取 PNG</small></a><a class="asset-item" href="assets/ui-refinements/tv-banner-320x180.png" download data-focus-key="banner-320"><img src="assets/ui-refinements/tv-banner-320x180.png" width="320" height="180" alt="320×180电视横幅，完整的书本图形和书房 TV 字样"><strong>320 × 180 px</strong><small>电视横幅基准尺寸</small></a></div><div class="icon-row"><div class="icon-sample" tabindex="0" data-focus-key="icon-safe"><div class="adaptive-mask safe"><img src="assets/ui-refinements/adaptive-foreground.svg" width="108" height="108" alt="保留原书本路径的108dp图标前景，虚线标示66dp圆形安全区"></div><span>108dp 画布 / 66dp 安全圆</span></div><div class="icon-sample"><div class="adaptive-mask circle"><img src="assets/ui-refinements/adaptive-foreground.svg" width="108" height="108" alt="原书本图形在圆形遮罩内的表现"></div><span>圆形遮罩</span></div><div class="icon-sample"><div class="adaptive-mask squircle"><img src="assets/ui-refinements/adaptive-foreground.svg" width="108" height="108" alt="原书本图形在圆角方形遮罩内的表现"></div><span>圆角方形遮罩</span></div><div class="icon-spec"><strong>图形保持完整</strong><span>前景透明，背景铺满。</span><span>图标不塞入中文名称；“书房 TV”保留在横幅。</span><small>安全区虚线仅供预览，不进入导出资产。</small></div></div></div><footer class="page-footer">↑↓←→ 移动 · 确定 获取横幅 · 返回 首页</footer></section>`;
  }
  function modal() { return `<div class="modal-layer"><section class="detail-dialog" role="dialog" aria-modal="true" aria-labelledby="detail-heading" data-focus-scope><h2 id="detail-heading">书名详情</h2><div class="od-scroll"><p class="full-title">${escapeHTML(example.title)}</p><p>${example.author}</p></div>${button('返回', 'modal-close', 'modal-close', 'action primary')}</section></div>`; }
  function rememberScroll() { stage.querySelectorAll('[data-scroll-id]').forEach(el => { state.scroll[el.dataset.scrollId] = el.scrollTop; }); }
  function focusKey(key) {
    const scope = $('[data-focus-scope]', stage) || stage;
    const candidate = Array.from(scope.querySelectorAll('[data-focus-key]')).find(el => el.dataset.focusKey === key) || scope.querySelector('button, a, input, [tabindex="0"]');
    if (candidate) { candidate.focus({ preventScroll: true }); candidate.scrollIntoView({ block: 'nearest', inline: 'nearest' }); }
  }
  function render(key, shouldFocus = true) {
    rememberScroll();
    stage.innerHTML = ({ home, settings: speechSettings, reader, assets }[state.view])() + (state.modal ? modal() : '');
    const themes = { dark: ['#1c201d', '#e9e7dc'], warm: ['#e2dac4', '#33382f'], black: ['#0d110f', '#dfe4da'] };
    stage.style.setProperty('--paper', themes[state.paper][0]);
    stage.style.setProperty('--paper-text', themes[state.paper][1]);
    stage.style.setProperty('--body-size', String(state.size));
    stage.style.setProperty('--reading-font', state.face === 'serif' ? 'var(--font-book)' : 'var(--font-ui)');
    stage.style.setProperty('--reading-line', ({ compact: 1.5, comfortable: 1.8, loose: 2 })[state.line]);
    const copy = $('.reader-copy', stage);
    if (copy) {
      copy.style.setProperty('--reading-brightness', `${state.brightness || 100}%`);
      if (!state.panel) { copy.style.maxWidth = `calc(${({ narrow: 540, middle: 660, wide: 820 })[state.width]} * var(--dp))`; copy.style.marginInline = 'auto'; }
    }
    stage.querySelectorAll('[data-scroll-id]').forEach(el => { el.scrollTop = state.scroll[el.dataset.scrollId] || 0; });
    document.querySelectorAll('[data-view]').forEach(el => el.setAttribute('aria-pressed', String(el.dataset.view === state.view)));
    $('#engine').value = state.engine;
    $('#format').value = state.format;
    if (shouldFocus) requestAnimationFrame(() => focusKey(key));
  }
  function go(view, key) {
    if (document.activeElement?.dataset.focusKey) state.focus[state.view] = document.activeElement.dataset.focusKey;
    state.view = view; state.modal = false;
    if (view !== 'reader') { state.panel = null; state.autoPaused = state.auto || state.autoPaused; state.paused = true; }
    if (view === 'reader') state.panel = 'menu';
    render(key || state.focus[view] || ({ home: 'continue', settings: 'system-settings', reader: 'menu-speech', assets: 'banner-640' })[view]);
  }
  function back() {
    if (state.modal) { state.modal = false; render('title-details'); return; }
    if (state.view === 'reader' && state.panel && state.panel !== 'menu') { state.panel = 'menu'; render(state.panelReturn); return; }
    if (state.view === 'reader' && state.panel) { state.panel = null; render(state.engine === 'controls' ? 'strip-toggle' : 'open-menu'); return; }
    if (state.view === 'settings' && state.returnView === 'reader') { state.returnView = 'home'; state.view = 'reader'; state.panel = 'speech'; render('reader-voice-settings'); return; }
    if (state.view !== 'home') { go('home', 'continue'); return; }
    focusKey('nav-home');
  }
  function doAction(action, key) {
    if (action.startsWith('choice:')) {
      const [, group, value] = action.split(':');
      state[group] = ['speed', 'seconds', 'brightness'].includes(group) ? Number(value) : value;
      render(key); return;
    }
    if (action.startsWith('panel:')) {
      state.panelReturn = key; state.panel = action.slice(6); state.selection = state.panel === 'selection';
      if (['speech', 'selection', 'search'].includes(state.panel)) { state.auto = false; state.autoPaused = false; }
      render(({ speech: state.engine === 'missing' ? 'reader-system' : state.engine === 'detected' ? 'check-chinese' : 'speech-toggle', search: 'chapter-query', selection: 'collect', type: 'font-up', paper: state.format === 'PDF' ? `brightness-${state.brightness || 100}` : `paper-${state.paper}`, auto: 'seconds-20', toc: 'chapter-0' })[state.panel]); return;
    }
    if (action.startsWith('chapter:')) { state.chapter = Number(action.split(':')[1]); state.page = state.chapter + 1; state.panel = null; render('open-menu'); return; }
    if (action.startsWith('hit:')) { state.paragraph = Number(action.split(':')[1]); state.selection = true; state.panel = null; render('open-menu'); requestAnimationFrame(() => $(`#paragraph-${state.paragraph}`, stage)?.scrollIntoView({ block: 'center' })); return; }
    switch (action) {
      case 'home': go('home', 'continue'); break;
      case 'settings': state.returnView = 'home'; go('settings', 'system-settings'); break;
      case 'continue': state.format = 'EPUB'; state.view = 'reader'; state.panel = null; render('open-menu'); break;
      case 'open-txt': case 'open-pdf': state.format = action === 'open-txt' ? 'TXT' : 'PDF'; state.view = 'reader'; state.panel = null; render('open-menu'); break;
      case 'title': state.modal = true; render('modal-close'); break;
      case 'modal-close': state.modal = false; render('title-details'); break;
      case 'menu': state.panel = 'menu'; render(state.panelReturn); break;
      case 'panel-close': state.panel = null; state.selection = false; render(state.engine === 'controls' ? 'strip-toggle' : 'open-menu'); break;
      case 'voice-settings': state.returnView = 'reader'; go('settings', 'system-settings'); break;
      case 'system-settings': previewMessage('系统语音设置：原生端跳转电视的语音设置；本预览不会操作本机。返回后重新检测引擎，开启朗读时再检查中文支持。'); break;
      case 'detect': previewMessage(state.engine === 'missing' ? '重新检测样例：仍未检测到语音引擎。可在画布外切换“检测到服务”，查看另一种能力状态。' : '重新检测样例：服务存在，中文支持仍需在开启朗读时检查。'); break;
      case 'check-chinese': state.systemNotice = true; render('check-chinese'); previewMessage('中文检查失败样例：预览不调用语音服务，也不宣称可发声。现有控制布局可在“语音样例”中单独查看。'); break;
      case 'help': focusKey('help-image'); break;
      case 'settings-top': focusKey('system-settings'); break;
      case 'font-up': state.size = Math.min(32, state.size + 2); render(key); break;
      case 'font-down': state.size = Math.max(18, state.size - 2); render(key); break;
      case 'bookmark': state.bookmark = !state.bookmark; render(key); break;
      case 'collect': state.collected = true; render(key); break;
      case 'selection-prev': case 'speech-prev': state.paragraph = Math.max(0, state.paragraph - 1); render(key); break;
      case 'selection-next': case 'speech-next': state.paragraph = Math.min(example.paragraphs.length - 1, state.paragraph + 1); render(key); break;
      case 'speech-toggle': state.paused = !state.paused; render(key); break;
      case 'speech-end': state.engine = 'detected'; state.paused = true; state.panel = null; render('open-menu'); previewMessage('已退出无声控件样例。检测到服务时，仍需在开启朗读时检查中文支持。'); break;
      case 'search': {
        state.query = $('#chapter-query').value.trim();
        const n = state.query ? example.paragraphs.filter(p => p.includes(state.query)).length : 0;
        state.searchNotice = state.query ? (n ? `找到 ${n} 段相关内容` : '没有找到相关内容，请换个词试试。') : '请输入要查找的文字。'; render('search-submit'); break;
      }
      case 'auto-toggle': if (!state.auto) { state.auto = true; state.autoPaused = false; } else state.autoPaused = !state.autoPaused; render(key); break;
      case 'auto-end': state.auto = false; state.autoPaused = false; render('auto-toggle'); break;
    }
  }
  function visibleElements(scope) {
    return Array.from(scope.querySelectorAll('button:not(:disabled),a[href],input,select,[tabindex="0"]')).filter(el => !el.closest('[inert],[hidden]') && el.getClientRects().length);
  }
  function moveFocus(key) {
    const scope = $('[data-focus-scope]', stage) || stage;
    const list = visibleElements(scope);
    const active = document.activeElement;
    if (!list.includes(active)) { (list.find(el => el.dataset.focusKey === 'continue') || list[0])?.focus(); return; }
    const r = active.getBoundingClientRect();
    const cx = r.x + r.width / 2, cy = r.y + r.height / 2;
    const vertical = key === 'ArrowUp' || key === 'ArrowDown';
    const direction = key === 'ArrowUp' || key === 'ArrowLeft' ? -1 : 1;
    const candidates = list.filter(el => el !== active).map(el => {
      const b = el.getBoundingClientRect();
      const dx = b.x + b.width / 2 - cx, dy = b.y + b.height / 2 - cy;
      const primary = vertical ? dy : dx, secondary = vertical ? dx : dy;
      const overlap = vertical ? Math.min(r.right, b.right) - Math.max(r.left, b.left) > 0 : Math.min(r.bottom, b.bottom) - Math.max(r.top, b.top) > 0;
      return { el, primary, score: Math.abs(primary) + Math.abs(secondary) * (overlap ? .2 : 3) + (overlap ? 0 : 80) };
    }).filter(item => item.primary * direction > 2).sort((a, b) => a.score - b.score);
    if (candidates.length) { candidates[0].el.focus({ preventScroll: true }); candidates[0].el.scrollIntoView({ block: 'nearest', inline: 'nearest' }); }
  }
  stage.addEventListener('click', event => {
    const target = event.target.closest('[data-action]');
    if (target) doAction(target.dataset.action, target.dataset.focusKey);
  });
  document.querySelectorAll('[data-view]').forEach(el => el.addEventListener('click', () => { state.returnView = 'home'; go(el.dataset.view); }));
  $('#resolution').addEventListener('change', event => { state.resolution = event.target.value; shell.dataset.resolution = state.resolution; previewMessage(state.resolution === '720' ? '720p：使用 640×360dp 重排，保留文字与操作尺寸；设置和阅读工具可向下滚动。' : '1080p：960×540dp，长书名使用两行省略；作者、位置与主操作分别占据独立行。'); });
  $('#engine').addEventListener('change', event => {
    state.engine = event.target.value; state.systemNotice = false; state.paused = true; state.auto = false;
    previewMessage(state.engine === 'controls' ? '仅演示已有朗读控件布局，不连接语音服务，不代表这台电视支持中文发声。' : '能力状态样例：服务存在与中文可用分开表达；Sony 默认使用“无语音引擎”状态。');
    if (state.engine === 'controls') { state.view = 'reader'; state.panel = null; }
    render(undefined, false);
  });
  $('#format').addEventListener('change', event => { state.format = event.target.value; state.view = 'reader'; state.panel = 'menu'; state.auto = false; render(undefined, false); previewMessage(textFormat() ? '文本内容显示搜索、段落收藏与朗读入口。' : 'PDF 与纯图片隐藏文字搜索、段落收藏和朗读，不进行 OCR。'); });
  document.addEventListener('keydown', event => {
    if (event.key === 'Escape' || event.key === 'BrowserBack') { event.preventDefault(); back(); return; }
    if (!stage.contains(document.activeElement) && document.activeElement !== document.body) return;
    const active = document.activeElement;
    if (active?.tagName === 'INPUT') {
      if (event.key === 'Enter') { event.preventDefault(); doAction('search', 'search-submit'); }
      if (event.key !== 'ArrowDown' && event.key !== 'ArrowUp') return;
    }
    if (state.view === 'reader' && !state.panel && !state.modal) {
      if ((event.key === 'ArrowLeft' || event.key === 'ArrowRight') && active?.dataset.focusKey === 'reader-copy') {
        event.preventDefault(); state.page = Math.max(1, state.page + (event.key === 'ArrowRight' ? 1 : -1)); if (state.auto) state.autoPaused = true; render('reader-copy'); return;
      }
      if (event.key === 'Enter' && active?.dataset.focusKey === 'reader-copy') {
        event.preventDefault(); if (state.auto) { state.autoPaused = !state.autoPaused; render('reader-copy'); } else { state.panel = 'menu'; render('menu-speech'); } return;
      }
      if ((event.key === 'ArrowDown' || event.key === 'ArrowUp') && active?.dataset.focusKey === 'reader-copy') {
        const delta = active.clientHeight * .5 * (event.key === 'ArrowDown' ? 1 : -1);
        const before = active.scrollTop; active.scrollTop += delta;
        if (before !== active.scrollTop) { event.preventDefault(); return; }
      }
    }
    if (event.key.startsWith('Arrow')) { event.preventDefault(); moveFocus(event.key); }
    if (event.key === 'Tab' && $('[data-focus-scope]', stage)) {
      const list = visibleElements($('[data-focus-scope]', stage)); const i = list.indexOf(active);
      event.preventDefault(); list[(i + (event.shiftKey ? -1 : 1) + list.length) % list.length]?.focus();
    }
  });
  document.addEventListener('visibilitychange', () => {
    if (document.hidden) { state.paused = true; state.autoPaused = state.auto || state.autoPaused; state.remembered = document.activeElement?.dataset.focusKey || ''; }
    else render(state.remembered, Boolean(state.remembered));
  });
  let lastPageTime = Date.now();
  setInterval(() => {
    if (!state.auto || state.autoPaused || state.panel || state.view !== 'reader' || document.hidden) { lastPageTime = Date.now(); return; }
    if (Date.now() - lastPageTime >= state.seconds * 1000) { state.page += 1; lastPageTime = Date.now(); render(document.activeElement?.dataset.focusKey); }
  }, 1000);
  render('continue');
})();
