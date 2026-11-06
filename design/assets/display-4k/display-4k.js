'use strict';
(() => {
  const C = window.Display4K;
  const $ = (selector, parent = document) => parent.querySelector(selector);
  const $$ = (selector, parent = document) => [...parent.querySelectorAll(selector)];
  const app = $('#display-app'), frame = $('#display-frame');
  const chapters = ['灯下的一页', '让文字保持舒适', '清晰度与阅读位置'];
  const paragraphs = [
    '夜幕降临，书房亮起一盏灯。愿每一页文字，都有温暖的声音。坐在熟悉的位置，拿起遥控器，让阅读从上次停下的地方继续。',
    '清晰的文字，不必变得更小。屏幕容纳更多像素，正文仍保留舒适的大小，行距与留白也依然从容。慢慢读，让目光顺着文字向前。',
    '按确定键打开阅读菜单，在显示与清晰度中查看当前效果。返回正文时，仍然是刚刚读到的这一页。一次调整，不应打断一段安静的阅读。'
  ];
  let state = C.createState();
  const ui = { page: 'display', origin: 'settings', menu: false, details: false, format: 'TXT',
    profile: '1080', outcome: 'success', modal: null, toast: '', pageNumber: 1, zoom: 100,
    bookmark: false, scrolls: {}, lastMainKey: 'policy-auto', modalReturn: 'menu-jump' };
  let confirmationTimer, toastTimer;
  const e = value => String(value).replace(/[&<>"']/g, c => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
  const icon = name => `<svg class="od-icon" viewBox="0 0 24 24" aria-hidden="true">${({
    home: '<path d="m3 11 9-7 9 7M5 10v10h14V10M9 20v-7h6v7"/>',
    library: '<path d="M4 4h5v16H4zM12 4h4v16h-4zM19 5l3 14"/>',
    settings: '<path d="M4 7h16M4 17h16M8 4v6M16 14v6"/>',
    back: '<path d="m14 5-7 7 7 7M7 12h14"/>',
    close: '<path d="m6 6 12 12M18 6 6 18"/>',
    check: '<path d="m5 12 4 4L19 6"/>',
    chevron: '<path d="m9 5 7 7-7 7"/>',
    down: '<path d="m5 9 7 7 7-7"/>',
    up: '<path d="m5 15 7-7 7 7"/>'
  }[name] || '')}</svg>`;
  const button = (label, action, key, cls = '', attrs = '') => `<button type="button" class="btn ${cls}" data-action="${action}" data-key="${key}" ${attrs}>${label}</button>`;
  const link = (label, href, key, cls = '') => `<a class="${cls}" href="${href}" target="_blank" rel="noopener" data-key="${key}">${label}</a>`;
  const device = () => C.DEVICES[state.device];
  const actualText = () => state.confirmed ? C.dimensions(state.actual) : state.entered ? '正在确认' : '未进入阅读';
  const rememberScroll = () => $$('[data-scroll]', app).forEach(node => { ui.scrolls[node.dataset.scroll] = [node.scrollLeft, node.scrollTop]; });
  function focus(key) {
    const node = $$('[data-key]', app).find(n => n.dataset.key === key && !n.closest('[inert],[hidden]'));
    if (!node) return;
    node.focus({ preventScroll: true });
    const scroll = node.closest('[data-scroll]');
    if (scroll && node !== scroll) {
      const a = node.getBoundingClientRect(), b = scroll.getBoundingClientRect(), margin = unit() * 6;
      if (a.top < b.top + margin) scroll.scrollTop -= b.top + margin - a.top;
      else if (a.bottom > b.bottom - margin) scroll.scrollTop += a.bottom - b.bottom + margin;
    }
  }
  function unit() { return app.getBoundingClientRect().width / C.PROFILES[ui.profile].dpWidth; }
  function render(key = null, saveScroll = true) {
    const hadFocus = app.contains(document.activeElement);
    const previousKey = document.activeElement?.dataset.key;
    if (saveScroll) rememberScroll();
    const page = ui.page === 'reader' ? reader() : ui.page === 'settings' ? settings() : display();
    app.innerHTML = page + (ui.page === 'reader' && ui.menu ? tools() : '') + (ui.modal ? modal() : '')
      + (ui.toast ? `<div class="toast" role="status">${e(ui.toast)}</div>` : '');
    $$('[data-scroll]', app).forEach(node => {
      const saved = ui.scrolls[node.dataset.scroll];
      if (saved) { node.scrollLeft = saved[0]; node.scrollTop = saved[1]; }
    });
    $$('.audit-nav button').forEach(node => node.setAttribute('aria-pressed', String(node.dataset.scene === ui.page)));
    $('#current-audit').textContent = `当前：${device().name}；选择${state.policy === 'auto' ? '自动' : '兼容模式'}；${C.summary(state)}。`;
    if (key || hadFocus) focus(key || previousKey || defaultKey());
    updateMetrics();
  }
  function defaultKey() {
    if (ui.modal) return 'modal-close';
    return ui.page === 'display' ? `policy-${state.policy}` : ui.page === 'settings' ? 'display-entry' : ui.menu ? 'menu-display' : 'reader-copy';
  }
  function sidebar() {
    return `<aside class="sidebar"><div class="brand"><strong>书房 TV</strong><small>一卷在手，片刻清宁</small></div><nav aria-label="主导航">
      ${link(`${icon('home')}<span>首页</span>`, 'ui-review.html', 'nav-home', 'nav-item')}
      ${link(`${icon('library')}<span>我的书库</span>`, 'reading-features.html', 'nav-library', 'nav-item')}
      <button type="button" class="nav-item" data-action="settings" data-key="nav-settings" aria-current="page">${icon('settings')}<span>设置</span></button>
      </nav><p class="sidebar-note">慢一点，<br>读进去。</p></aside>`;
  }
  function mode(value, title, description) {
    const checked = state.policy === value;
    return `<button type="button" class="mode-option" role="radio" aria-checked="${checked}" data-action="policy:${value}" data-key="policy-${value}"><span class="mode-mark" aria-hidden="true">${checked ? icon('check') : ''}</span><span class="od-field"><strong>${title}</strong><small>${description}</small></span></button>`;
  }
  function display() {
    const info = C.status(state, ui.origin === 'reader');
    return `<div class="app-shell">${sidebar()}<section class="display-main" aria-labelledby="display-title"><header class="page-header"><div><h1 id="display-title">显示与清晰度</h1><span class="label">仅调整阅读内容的显示方式</span></div></header>
      <div class="panel-scroll od-scroll" data-scroll="display-panel">
        <section class="status-block" data-tone="${info.tone}" tabindex="0" data-key="display-status" aria-label="当前效果：${e(info.title)}"><div class="status-top"><span class="status-dot" aria-hidden="true"></span><span>当前效果</span></div><h2 aria-live="polite">${info.title}</h2><p class="status-copy">${info.description}</p></section>
        <h2 class="section-label" id="mode-heading">阅读显示方式</h2><div class="mode-options" role="radiogroup" aria-labelledby="mode-heading">
          ${mode('auto', '自动（推荐）', '按电视能力尝试高清阅读；未能启用时使用原有阅读方式。')}
          ${mode('compat', '兼容模式', '显示异常时，退回原有阅读方式。')}
        </div>
        <section class="device-details"><button type="button" class="detail-toggle" aria-expanded="${ui.details}" aria-controls="device-details" data-action="details" data-key="details-toggle"><span>设备详情</span>${icon(ui.details ? 'up' : 'down')}</button>
          <div id="device-details" ${ui.details ? '' : 'hidden'}><dl class="details-list" tabindex="0" data-key="device-info"><div class="detail-row"><dt>电视面板</dt><dd>${C.dimensions(device().panel)}</dd></div><div class="detail-row"><dt>系统界面</dt><dd>${C.dimensions(device().system)}</dd></div><div class="detail-row"><dt>实际阅读画布</dt><dd>${actualText()}</dd></div></dl><p class="fine-copy details-note">面板分辨率与系统界面可以不同。实际阅读画布在打开书籍后确认。</p></div>
        </section>
        <article class="help-copy" tabindex="0" data-key="display-help"><h3>清晰度提高，文字大小不变</h3><p>高清阅读增加正文的显示细节，保留当前字号、行距和阅读位置。书架与设置沿用系统界面。</p><p>若出现黑屏、闪烁或显示异常，可选兼容模式后返回阅读。</p></article>
      </div><footer class="panel-footer">${button(ui.origin === 'reader' ? '返回阅读' : '打开阅读示例', 'read', 'open-reading', 'primary')}${button(ui.origin === 'reader' ? '返回阅读菜单' : '返回设置', 'back', 'panel-back', 'quiet back-action')}</footer>
      </section></div>`;
  }
  function settings() {
    return `<div class="app-shell">${sidebar()}<section class="settings-main"><header class="page-header"><div><h1>设置</h1><span class="label">阅读默认与使用偏好</span></div></header><div class="panel-scroll od-scroll" data-scroll="settings">
      <button type="button" class="summary-entry" data-action="display" data-key="display-entry"><span class="od-field"><strong>显示与清晰度</strong><span>${state.policy === 'auto' ? '自动（推荐）' : '兼容模式'}</span><small>打开书籍后确认实际阅读效果</small></span><span class="entry-action">管理显示 →</span></button>
      ${link('<span class="od-field"><strong>朗读与语音</strong><small>应用内离线中文、可选网络与系统语音</small></span><span class="entry-action">管理语音 →</span>', 'speech-offline.html', 'voice-settings', 'summary-entry')}
      <div class="settings-muted od-field" tabindex="0" data-key="reading-defaults"><span>阅读默认</span><small>22 号 · 宋体 · 舒适行距</small></div>
      <article class="help-copy" tabindex="0" data-key="setting-help"><p>阅读清晰度与文字大小分开调整。暂停、上下段、语速和定时停止保留在阅读中的朗读控制里。</p></article>
      </div><footer class="key-hints"><span>方向键 选择</span><span>确定 打开</span><span>返回 回到导航</span></footer></section></div>`;
  }
  function reader() {
    const locked = ui.menu || ui.modal;
    const copy = ui.format === 'PDF' ? pdf() : `<article class="reader-copy od-scroll" tabindex="0" data-key="reader-copy" data-scroll="text-${ui.pageNumber}" aria-label="当前正文，确定打开阅读菜单"><h1>${chapters[ui.pageNumber - 1]}</h1>${paragraphs.map(p => `<p>${p}</p>`).join('')}</article>`;
    return `<section class="reader-screen" ${locked ? 'inert' : ''}><header class="reader-header"><div class="od-field od-fill"><span class="od-truncate" aria-label="书房显示指南：清晰文字与舒适阅读的合成使用手册">书房显示指南：清晰文字与舒适阅读的合成使用手册</span><small>${ui.format} · 第 ${ui.pageNumber} 页</small></div>${button('设置', 'settings', 'reader-settings', 'quiet')}</header><div class="reader-area">${copy}</div><footer class="reader-footer"><span>${ui.format === 'PDF' ? `↑↓ 滚动 · ${ui.zoom > 100 ? '←→ 平移' : '←→ 翻页'} · ${ui.zoom}%` : '←→ 翻页 · ↑↓ 滚动'}</span>${button('阅读菜单', 'menu', 'reader-menu', 'quiet')}<span>第 ${ui.pageNumber} / 3 页</span></footer></section>`;
  }
  function pdf() {
    return `<div class="pdf-viewport" tabindex="0" data-key="reader-copy" data-scroll="pdf-${ui.pageNumber}" aria-label="PDF 当前页，100% 适合屏宽，上下滚动，放大后左右平移"><article class="pdf-page" style="--zoom:${ui.zoom / 100}"><h1>图片与 PDF 阅读指南</h1><p>这是一份合成阅读页面，用于查看屏宽、上下滚动和放大后的平移效果。</p><h2>01　100% 即适合屏宽</h2><p>页面横向适合当前阅读区域的宽度。页面较长时，上下滚动查看，不需要把整张纸缩成一张小图。</p><p>${paragraphs[0]}</p><h2>02　放大后移动画面</h2><p>在阅读菜单中选择 150% 或 200%，用左右键移动画面，上下键继续滚动。选择适合屏宽，可以回到 100%。</p><p>${paragraphs[1]}</p><h2>03　保留刚刚读到的位置</h2><p>${paragraphs[2]}</p><p>第 ${ui.pageNumber} 页 · 书房 TV 合成指南</p></article></div>`;
  }
  function entry(label, helper, action, key, cls = '') {
    return `<button type="button" class="tool-entry ${cls}" data-action="${action}" data-key="${key}"><span class="od-field"><span>${label}</span>${helper ? `<small>${helper}</small>` : ''}</span>${icon('chevron')}</button>`;
  }
  function tools() {
    return `<div class="reader-layer" ${ui.modal ? 'inert' : ''}><aside class="reader-tool" role="dialog" aria-modal="true" aria-labelledby="tool-title" data-scope><header class="tool-header"><h2 id="tool-title">阅读菜单</h2><button type="button" class="icon-btn" data-action="close-menu" data-key="close-menu" aria-label="收起阅读菜单">${icon('close')}</button></header><div class="tool-scroll od-scroll" data-scroll="reader-menu">
      <section class="tool-group"><h3>显示</h3>${entry('显示与清晰度', C.summary(state), 'display-reader', 'menu-display', 'display-entry')}${state.phase === 'fallback' ? `<p class="fine-copy">已恢复原有阅读方式。</p>${button('再次尝试高清阅读', 'retry', 'menu-retry', 'quiet')}` : ''}${state.phase === 'unavailable' ? `<p class="fine-copy">正文位置已保留。</p>${button('重试兼容阅读', 'retry-compat', 'menu-recover', 'quiet')}` : ''}
      ${ui.format === 'PDF' ? `<h3>页面缩放</h3><div class="od-cluster">${[100, 150, 200].map(z => button(`${z}%`, `zoom:${z}`, `zoom-${z}`, 'quiet', `aria-pressed="${ui.zoom === z}"`)).join('')}</div>${button('适合屏宽', 'fit-width', 'pdf-fit', 'quiet')}<p class="fine-copy">100% 表示适合屏宽，上下滚动查看。</p>` : '<p class="fine-copy">22 号 · 宋体 · 舒适行距</p>'}</section>
      <section class="tool-group"><h3>阅读与记录</h3>${entry(ui.format === 'PDF' ? '页码跳转' : '目录与跳转', `第 ${ui.pageNumber} / 3 页`, 'jump', 'menu-jump')}${entry(ui.bookmark ? '已添加位置书签' : '添加位置书签', '', 'bookmark', 'menu-bookmark')}</section>
      ${ui.format !== 'PDF' ? `<section class="tool-group"><h3>朗读</h3>${link('<span class="od-field"><span>朗读与语音</span><small>应用内离线中文</small></span>' + icon('chevron'), 'speech-offline.html', 'menu-speech', 'tool-entry')}</section>` : '<p class="fine-copy">PDF 不提供文字朗读，不识别图片中的文字。</p>'}
      </div><footer class="tool-footer">${button('继续阅读', 'close-menu', 'continue-reading', 'primary')}${button('返回设置', 'settings', 'menu-settings', 'quiet')}</footer></aside></div>`;
  }
  function modal() {
    return `<div class="modal-layer"><section class="modal" role="dialog" aria-modal="true" aria-labelledby="modal-title" data-scope><h2 id="modal-title">${ui.format === 'PDF' ? '页码跳转' : '目录与跳转'}</h2><div class="od-scroll" data-scroll="jump"><div class="od-stack">${chapters.map((title, i) => button(ui.format === 'PDF' ? `第 ${i + 1} 页` : title, `page:${i + 1}`, `page-${i + 1}`, 'quiet', `aria-pressed="${ui.pageNumber === i + 1}"`)).join('')}</div></div><footer class="modal-actions">${button('返回阅读菜单', 'modal-close', 'modal-close', 'quiet')}</footer></section></div>`;
  }
  function transition(event, key = null, run = true) {
    const previousId = state.requestId;
    state = C.reduce(state, event);
    render(key);
    if (run && state.entered && state.requestId !== previousId && ['checking', 'recovering'].includes(state.phase)) scheduleConfirmation();
  }
  function scheduleConfirmation() {
    clearTimeout(confirmationTimer);
    const id = state.requestId;
    const recovering = state.phase === 'recovering';
    const timeout = ui.outcome === 'timeout' && state.target.path === 'independent';
    confirmationTimer = setTimeout(() => {
      if (!state.entered || id !== state.requestId) return;
      if (!recovering && state.target.path === 'independent' && ui.outcome !== 'success') {
        transition({ type: 'FAILED', requestId: id, reason: timeout ? '确认超时' : '未能启用高清阅读' });
      } else transition({ type: 'OBSERVED', requestId: id, path: state.target.path,
        width: state.target.size[0], height: state.target.size[1], frameDrawn: true });
    }, recovering ? 700 : timeout ? 4800 : 1800);
  }
  function stopSession() {
    clearTimeout(confirmationTimer);
    state = C.reduce(state, { type: 'LEAVE' });
  }
  function currentInteraction() { $('#fixture').value = ''; }
  function openReading(menu = true) {
    ui.page = 'reader'; ui.menu = menu; ui.modal = null;
    transition({ type: 'ENTER' }, menu ? 'menu-display' : 'reader-copy');
  }
  function openDisplay(fromReader = false) {
    if (fromReader) rememberScroll();
    ui.origin = fromReader ? 'reader' : 'settings'; ui.page = 'display'; ui.menu = false;
    render('open-reading');
  }
  function openSettings() {
    stopSession(); ui.page = 'settings'; ui.menu = false; ui.modal = null; ui.origin = 'settings';
    render('display-entry');
  }
  function toast(message) {
    clearTimeout(toastTimer); ui.toast = message; render();
    toastTimer = setTimeout(() => { ui.toast = ''; render(); }, 2400);
  }
  function back() {
    if (ui.modal) { ui.modal = null; render(ui.modalReturn); return; }
    if (ui.page === 'reader') {
      if (ui.menu) { ui.menu = false; render('reader-copy'); }
      else openSettings();
      return;
    }
    if (ui.page === 'display') { ui.origin === 'reader' ? openReading(true) : openSettings(); return; }
    focus('nav-settings');
  }
  function setPage(page) {
    const next = Math.min(3, Math.max(1, page));
    ui.pageNumber = next; ui.modal = null; ui.menu = false;
    render('reader-copy');
  }
  function setZoom(zoom, close = false) {
    rememberScroll();
    const old = ui.zoom;
    const saved = ui.scrolls[`pdf-${ui.pageNumber}`] || [0, 0];
    ui.zoom = zoom;
    ui.scrolls[`pdf-${ui.pageNumber}`] = [zoom === 100 ? 0 : saved[0] * zoom / old, saved[1] * zoom / old];
    if (close) ui.menu = false;
    render(close ? 'reader-copy' : `zoom-${zoom}`, false);
  }
  function handleAction(action, key) {
    currentInteraction();
    if (action.startsWith('policy:')) { transition({ type: 'SELECT_POLICY', value: action.split(':')[1] }, key); return; }
    if (action.startsWith('page:')) { setPage(Number(action.split(':')[1])); return; }
    if (action.startsWith('zoom:')) { setZoom(Number(action.split(':')[1])); return; }
    switch (action) {
      case 'display': openDisplay(); break;
      case 'display-reader': openDisplay(true); break;
      case 'settings': openSettings(); break;
      case 'read': openReading(false); break;
      case 'back': back(); break;
      case 'details': ui.details = !ui.details; render(key); break;
      case 'menu': ui.menu = true; render('menu-display'); break;
      case 'close-menu': ui.menu = false; render('reader-copy'); break;
      case 'retry': transition({ type: 'RETRY' }, key); break;
      case 'retry-compat':
        if (state.policy !== 'compat') transition({ type: 'SELECT_POLICY', value: 'compat' }, key);
        else transition({ type: 'RETRY' }, key);
        break;
      case 'fit-width': setZoom(100, true); break;
      case 'jump': ui.modal = true; ui.modalReturn = key; render(`page-${ui.pageNumber}`); break;
      case 'modal-close': ui.modal = null; render(ui.modalReturn); break;
      case 'bookmark': ui.bookmark = !ui.bookmark; toast(ui.bookmark ? '已添加当前位置书签' : '已移除位置书签'); break;
    }
  }
  app.addEventListener('click', event => {
    const node = event.target.closest('[data-action]');
    if (node && !node.closest('[inert]')) handleAction(node.dataset.action, node.dataset.key);
  });
  app.addEventListener('focusin', event => {
    if (event.target.closest('.display-main')) ui.lastMainKey = event.target.dataset.key || 'policy-auto';
  });
  function focusables(scope) {
    return $$('button,a[href],[tabindex="0"]', scope).filter(node => !node.closest('[inert],[hidden]') && node.getClientRects().length && !node.disabled);
  }
  function navigate(event) {
    const active = document.activeElement;
    const direction = event.key;
    if (direction === 'Escape' || direction === 'Backspace') { event.preventDefault(); back(); return; }
    if (direction === 'Enter' && active.dataset.key === 'reader-copy') { event.preventDefault(); ui.menu = true; render('menu-display'); return; }
    if (direction === 'Enter' && active.matches('button,a')) { event.preventDefault(); active.click(); return; }
    const scope = $('.modal', app) || $('.reader-tool', app) || app;
    const nodes = focusables(scope);
    if (direction === 'Tab' && (ui.menu || ui.modal)) {
      event.preventDefault(); const index = nodes.indexOf(active), delta = event.shiftKey ? -1 : 1;
      nodes[(index + delta + nodes.length) % nodes.length]?.focus(); return;
    }
    if (!['ArrowUp', 'ArrowDown', 'ArrowLeft', 'ArrowRight'].includes(direction)) return;
    event.preventDefault();
    if (active.dataset.key === 'reader-copy' && !ui.menu) {
      if (direction === 'ArrowUp' || direction === 'ArrowDown') {
        const delta = (direction === 'ArrowDown' ? 1 : -1) * 56 * unit();
        const before = active.scrollTop; active.scrollTop += delta;
        if (direction === 'ArrowDown' && active.scrollTop === before) focus('reader-menu');
      } else if (ui.format === 'PDF' && ui.zoom > 100) active.scrollLeft += (direction === 'ArrowRight' ? 1 : -1) * 64 * unit();
      else {
        const next = ui.pageNumber + (direction === 'ArrowRight' ? 1 : -1);
        if (next >= 1 && next <= 3) setPage(next);
      }
      return;
    }
    if (ui.page === 'reader' && !ui.menu && active.dataset.key === 'reader-menu' && direction === 'ArrowUp') { focus('reader-copy'); return; }
    if (ui.page !== 'reader' && !ui.modal) {
      if (active.closest('.sidebar')) {
        const nav = focusables($('.sidebar', app));
        if (direction === 'ArrowRight') { focus(ui.page === 'display' ? ui.lastMainKey : 'display-entry'); return; }
        if (direction === 'ArrowUp' || direction === 'ArrowDown') nav[Math.max(0, Math.min(nav.length - 1, nav.indexOf(active) + (direction === 'ArrowDown' ? 1 : -1)))]?.focus();
        return;
      }
      if (direction === 'ArrowLeft' && !active.closest('.panel-footer')) { focus('nav-settings'); return; }
    }
    const row = active.closest('.od-cluster,.panel-footer,.tool-footer,.modal-actions');
    const siblings = row ? focusables(row) : [];
    if (direction === 'ArrowLeft' || direction === 'ArrowRight') {
      const index = siblings.indexOf(active) + (direction === 'ArrowRight' ? 1 : -1);
      if (siblings[index]) focus(siblings[index].dataset.key);
      else if (direction === 'ArrowLeft' && ui.page !== 'reader' && !ui.modal) focus('nav-settings');
      return;
    }
    let list = nodes;
    if (ui.page !== 'reader' && !ui.modal) list = nodes.filter(node => !node.closest('.sidebar'));
    let index = list.indexOf(active);
    if (row && siblings.length) index = list.indexOf(direction === 'ArrowDown' ? siblings[siblings.length - 1] : siblings[0]);
    const next = list[index + (direction === 'ArrowDown' ? 1 : -1)];
    if (next) focus(next.dataset.key);
  }
  app.addEventListener('keydown', navigate);
  $$('.audit-nav button').forEach(node => node.addEventListener('click', () => {
    currentInteraction();
    if (node.dataset.scene === 'reader') openReading(true);
    else if (node.dataset.scene === 'display') openDisplay(ui.page === 'reader');
    else openSettings();
  }));
  $('#canvas-profile').addEventListener('change', event => {
    rememberScroll(); ui.profile = event.target.value; frame.dataset.profile = ui.profile;
    render(); requestAnimationFrame(updateMetrics);
  });
  $('#reading-format').addEventListener('change', event => {
    currentInteraction(); rememberScroll(); ui.format = event.target.value; ui.zoom = 100; ui.modal = null;
    openReading(true);
  });
  $('#result-mode').addEventListener('change', event => { ui.outcome = event.target.value; });
  $('#rebuild').addEventListener('click', () => {
    currentInteraction();
    if (!state.entered) openReading(true);
    else transition({ type: 'SURFACE_LOST' });
  });
  function applyFixture(value) {
    if (!value) return;
    clearTimeout(confirmationTimer); rememberScroll();
    const key = value === 'native' ? 'native' : value === 'fullhd' ? 'fullhd' : value === 'unknown' ? 'unknown' : 'sony';
    state = C.createState(key); ui.modal = null; ui.toast = ''; ui.origin = 'settings'; ui.page = 'display'; ui.menu = false;
    if (value === 'compat') state = C.reduce(state, { type: 'SELECT_POLICY', value: 'compat' });
    if (value !== 'idle') {
      state = C.reduce(state, { type: 'ENTER' }); ui.page = 'reader'; ui.menu = true;
      if (value !== 'waiting') {
        if (value === 'fallback' || value === 'unavailable') state = C.reduce(state, { type: 'FAILED', requestId: state.requestId });
        if (value === 'unavailable') state = C.reduce(state, { type: 'OBSERVED', requestId: state.requestId, width: 0, height: 0, path: 'system', frameDrawn: false });
        else state = C.reduce(state, { type: 'OBSERVED', requestId: state.requestId, width: state.target.size[0], height: state.target.size[1], path: state.target.path, frameDrawn: true });
      }
    }
    render();
  }
  $('#fixture').addEventListener('change', event => applyFixture(event.target.value));
  function updateMetrics() {
    const profile = C.PROFILES[ui.profile], rect = app.getBoundingClientRect(), u = rect.width / profile.dpWidth;
    const action = $('.btn', app), body = $('.reader-copy', app);
    $('#size-metrics').textContent = `当前预览 ${Math.round(rect.width)} × ${Math.round(rect.height)} CSS px；逻辑 ${profile.dpWidth} × ${profile.dpHeight} dp。\n`
      + `操作高：${action ? (action.getBoundingClientRect().height / u).toFixed(1) : '—'} dp；正文设计字号：${body ? (parseFloat(getComputedStyle(body).fontSize) / u).toFixed(1) : '22.0'} sp。\n`
      + '目标换算：1080p@2 → 22sp = 44px、44dp = 88px；4K@4 → 22sp = 88px、44dp = 176px；两者占画面的比例相同。\n'
      + '此处为浏览器布局与目标换算，不读取电视面板，不证明设备已启用 4K。';
  }
  window.Display4KPreview = Object.freeze({
    state: () => JSON.parse(JSON.stringify(state)),
    metrics: () => {
      const r = app.getBoundingClientRect(), p = C.PROFILES[ui.profile], u = r.width / p.dpWidth;
      const action = $('.btn', app), readerBody = $('.reader-copy', app);
      return { profile: ui.profile, width: r.width, height: r.height, dpWidth: p.dpWidth, dpHeight: p.dpHeight,
        actionDp: action ? action.getBoundingClientRect().height / u : null,
        bodySp: readerBody ? parseFloat(getComputedStyle(readerBody).fontSize) / u : null,
        summary: C.summary(state) };
    }
  });
  new ResizeObserver(updateMetrics).observe(frame);
  document.addEventListener('visibilitychange', () => {
    if (document.hidden && state.entered) {
      clearTimeout(confirmationTimer); transition({ type: 'SURFACE_LOST' }, null, false);
    } else if (!document.hidden && state.entered && state.phase === 'checking') scheduleConfirmation();
  });
  render('open-reading');
})();
