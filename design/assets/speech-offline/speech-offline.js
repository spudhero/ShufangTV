(function () {
  'use strict';
  const C = window.SpeechPrototypeCore;
  const app = document.querySelector('#speech-app');
  const frame = document.querySelector('#tv-frame');
  const previewNote = document.querySelector('#review-message');
  const $ = (selector, scope = app) => scope.querySelector(selector);
  const e = value => String(value).replace(/[&<>"']/g, char => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[char]));
  let state = C.createState();
  const jobs = new Map();
  const ui = { page: 'manager', engine: 'offline', origin: 'settings', modal: null, tools: 'menu', format: 'EPUB', savedReader: null, scroll: {}, errors: {}, draft: { region: '', voice: 'zh-CN-XiaoxiaoNeural', hasKey: false, entered: false }, dirty: false, toast: '', toastId: 0 };
  const paragraphs = [C.SAMPLE, '一卷在手，片刻清宁。用遥控器选择自己的节奏，让阅读停在舒适的地方。', '听到这里，可以暂停片刻。下次回到书房，再从熟悉的文字继续。'];
  const regions = [['eastasia', '东亚 · 香港'], ['southeastasia', '东南亚 · 新加坡'], ['eastus', '美国东部'], ['westeurope', '西欧']];
  const voices = [['zh-CN-XiaoxiaoNeural', '晓晓 · 普通话女声'], ['zh-CN-YunxiNeural', '云希 · 普通话男声']];
  const findLabel = (values, value, fallback) => values.find(item => item[0] === value)?.[1] || fallback;
  const icon = name => `<svg class="od-icon" viewBox="0 0 24 24" aria-hidden="true">${({ back: '<path d="m14 5-7 7 7 7M7 12h14"/>', close: '<path d="m6 6 12 12M18 6 6 18"/>', next: '<path d="m9 5 7 7-7 7"/>', book: '<path d="M12 5C8 2 4 3 2 4v15c4-1 7-1 10 1 3-2 6-2 10-1V4c-2-1-6-2-10 1Zm0 0v15"/>', settings: '<path d="M4 7h16M4 17h16M8 4v6M16 14v6"/>', home: '<path d="m3 11 9-7 9 7M5 10v10h14V10M9 20v-7h6v7"/>' })[name] || '<path d="M4 12h16"/>'}</svg>`;
  const btn = (text, action, key, cls = '', attrs = '') => `<button type="button" class="btn ${cls}" data-action="${action}" data-key="${key}" ${attrs}>${text}</button>`;
  const mark = engine => state.selected === engine ? '<span class="current-mark" aria-label="当前使用">✓</span>' : '';
  function send(event) { state = C.reduce(state, event); }
  function message(text) { previewNote.textContent = text; }
  function schedule(key, delay, callback) {
    cancel(key);
    jobs.set(key, setTimeout(() => { jobs.delete(key); callback(); }, delay));
  }
  function cancel(key) { if (jobs.has(key)) clearTimeout(jobs.get(key)); jobs.delete(key); }
  function cancelPrefix(prefix) { Array.from(jobs.keys()).filter(key => key.startsWith(prefix)).forEach(cancel); }
  function clearJobs() { Array.from(jobs.keys()).forEach(cancel); }
  function toast(text) {
    ui.toast = text; render(undefined, false);
    schedule('toast', 5500, () => { ui.toast = ''; render(undefined, false); });
  }
  function syncDraft() { ui.draft = { region: state.azure.region, voice: state.azure.voice, hasKey: state.azure.hasKey, entered: false }; ui.dirty = false; ui.errors = {}; }
  function activeKey() { return document.activeElement?.dataset.key; }
  function visible(scope) {
    return Array.from(scope.querySelectorAll('button:not(:disabled), a[href], input, [tabindex="0"]')).filter(el => !el.closest('[inert],[hidden]') && el.getClientRects().length);
  }
  function focusScope() { return $('.modal-layer [data-scope]') || $('[data-scope]') || app; }
  function focusKey(key, scroll = true) {
    const scope = focusScope();
    const el = Array.from(scope.querySelectorAll('[data-key]')).find(node => node.dataset.key === key) || visible(scope)[0];
    if (!el) return;
    el.focus({ preventScroll: true });
    if (scroll) el.scrollIntoView({ block: 'nearest', inline: 'nearest' });
  }
  function render(key, focus = Boolean(key)) {
    const previousFocus = activeKey();
    app.querySelectorAll('[data-scroll]').forEach(node => { ui.scroll[node.dataset.scroll] = node.scrollTop; });
    const body = ui.page === 'manager' ? manager() : ui.page === 'settings' ? settings() : reader();
    app.innerHTML = body + (ui.modal ? modal() : '') + (ui.toast ? `<div class="toast" role="status">${e(ui.toast)}</div>` : '');
    app.querySelectorAll('[data-scroll]').forEach(node => { node.scrollTop = ui.scroll[node.dataset.scroll] || 0; });
    document.querySelectorAll('[data-page]').forEach(node => node.setAttribute('aria-pressed', String(node.dataset.page === ui.page)));
    document.querySelector('#background-toggle').textContent = state.foreground ? '模拟退到后台' : '模拟回到前台';
    if (focus || previousFocus) requestAnimationFrame(() => focusKey(key || previousFocus, Boolean(key)));
    if (ui.page === 'reader' && !ui.tools && ['playing', 'preparing'].includes(state.playback.phase)) requestAnimationFrame(() => $(`#reader-paragraph-${state.reader.paragraph}`)?.scrollIntoView({ block: 'nearest' }));
  }
  function engineSidebar() {
    const subtitle = { offline: '默认推荐', azure: '可选 · 需网络', system: '可选 · 按设备' };
    return `<aside class="engine-sidebar"><div class="brand"><strong>书房 TV</strong><small>朗读与语音</small></div><nav class="engine-nav" aria-label="语音引擎">${Object.keys(C.names).map(engine => `<button type="button" class="engine-tab" data-action="tab:${engine}" data-key="tab-${engine}" aria-current="${ui.engine === engine ? 'page' : 'false'}" aria-pressed="${state.selected === engine}"><span class="od-field"><span>${engine === 'azure' ? 'Azure 网络' : C.names[engine]}</span><small>${subtitle[engine]}</small></span>${mark(engine)}</button>`).join('')}</nav><div class="side-footer">${btn(`${icon('back')}<span>返回</span>`, 'manager-back', 'manager-back', 'quiet')}</div></aside>`;
  }
  function manager() {
    const titles = { offline: '离线中文朗读', azure: 'Azure 网络朗读', system: '系统语音' };
    return `<div class="shell" ${ui.modal ? 'inert' : ''}>${engineSidebar()}<section class="manager"><header class="page-header"><div><span class="label">设置 / 朗读与语音</span><h1>${titles[ui.engine]}</h1></div>${state.selected === ui.engine ? '<span class="current-badge">✓ 当前使用</span>' : '<span class="current-badge">可选引擎</span>'}</header><div class="manager-scroll od-scroll" data-scroll="manager-${ui.engine}">${ui.engine === 'offline' ? offlineBody() : ui.engine === 'azure' ? azureBody() : systemBody()}</div><footer class="manager-footer" id="manager-footer">${footerContents()}</footer></section></div>`;
  }
  function statusBlock(title, description, engine, extra = '') {
    return `<section class="status-block" tabindex="0" data-key="status-${engine}" aria-label="${e(title)}"><div class="status-heading"><span class="status-dot ${C.ready(state, engine) ? 'ready' : ''}" aria-hidden="true"></span><h2>${e(title)}</h2></div><p class="status-description">${description}</p>${extra}</section>`;
  }
  function sampleSection(engine) {
    const sample = state.sample.engine === engine ? state.sample : { phase: 'idle', sentence: 0 };
    const parts = ['夜幕降临，书房亮起一盏灯。', '愿每一页文字，都有温暖的声音。'];
    const status = { idle: '试听这段文字，感受声音与阅读的节奏。', preparing: engine === 'system' ? '正在初始化并检查中文支持…' : '正在准备试听…', playing: '正在试听', done: '试听已结束', failed: '试听未完成，可以重试。' };
    return `<section class="sample-section"><h3>试听文字</h3><p class="sample-quote" tabindex="0" data-key="sample-text-${engine}">${parts.map((part, i) => sample.phase === 'playing' && sample.sentence === i ? `<mark>${part}</mark>` : part).join('')}</p><p class="sample-state" role="status">${status[sample.phase]}</p></section>`;
  }
  function offlineBody() {
    const o = state.offline;
    const descriptions = {
      missing: '首次下载后即可在应用内朗读中文，无需电视自带语音服务。',
      downloading: '正在下载中文语音包，完成后将校验并安装。',
      installing: '下载已完成，正在检查并安装。完成前暂时不能朗读。',
      checking: '正在检查已有语音包，确认完整后才能继续使用。',
      ready: '可在应用内离线朗读中文，不需要系统语音引擎。',
      failed: e(o.error), interrupted: '上次下载或安装没有完成，请重新准备语音包。'
    };
    const progress = o.status === 'downloading' ? `<div class="progress-wrap"><div class="progress-caption"><span>${(C.PACK.downloadMiB * o.progress / 100).toFixed(1)} / 30.1 MiB</span><span>${Math.floor(o.progress)}%</span></div><div class="progress-track" role="progressbar" aria-label="语音包下载进度" aria-valuenow="${Math.floor(o.progress)}" aria-valuemin="0" aria-valuemax="100"><span class="progress-fill" style="--percent:${o.progress}"></span></div></div>` : '';
    return `${statusBlock(C.statusLabel(state, 'offline'), descriptions[o.status], 'offline', progress)}
      <div class="package-info"><div class="od-field"><small>首次下载</small><span>30.1 MiB</span></div><div class="od-field"><small>安装约占</small><span>31.2 MiB</span></div><div class="od-field"><small>建议预留</small><span>120 MiB</span></div></div>
      <p class="fine-copy"><strong>轻量中文普通话</strong> · Apache-2.0 开源许可<br>下载语音包需要网络；安装后朗读正文在本机完成。</p>
      ${o.status === 'interrupted' ? '<p class="notice error">未完成的语音包不会作为已就绪使用，也不会自动转用网络朗读。</p>' : ''}
      ${sampleSection('offline')}
      <div class="lower-actions">${o.validPackage && o.status === 'ready' ? btn('删除语音包', 'delete-request', 'delete-package', 'quiet danger') : ''}</div>`;
  }
  function fieldError(name) { return ui.errors[name] ? `<span class="field-error" id="error-${name}">${ui.errors[name]}</span>` : ''; }
  function azureBody() {
    const a = state.azure;
    const desc = { unconfigured: '配置自己的 Azure 语音资源后，可主动选择网络朗读。', configured: '配置已保存，开启前请先试听验证。保存配置不会切换当前引擎。', testing: '正在验证本次试听，当前引擎不会自动切换。', ready: '本次试听已通过，网络变化后仍可能暂时不可用。', failed: a.error };
    return `${statusBlock(C.statusLabel(state, 'azure'), e(desc[a.status]), 'azure')}
      <div class="privacy-note"><strong>使用网络语音前，请了解</strong><span>选用时，朗读段落将发送至 Azure，并可能按用量计费。不会自动从离线切换到网络。</span></div>
      <section class="form-section" aria-label="Azure 配置">
      ${Object.keys(ui.errors).length ? '<p class="notice error" role="alert">配置尚未保存，请补全下方标注的内容。</p>' : ''}
      <div class="form-field"><span class="form-label" id="region-label">地区</span><button type="button" class="field-button" data-action="pick-region" data-key="region" aria-labelledby="region-label region-value" ${ui.errors.region ? 'aria-invalid="true" aria-describedby="error-region"' : ''}><span id="region-value">${findLabel(regions, ui.draft.region, '选择语音资源所在地区')}</span>${icon('next')}</button><small>与自己的 Azure 语音资源地区保持一致。</small>${fieldError('region')}</div>
      <div class="form-field"><span class="form-label" id="voice-label">中文音色</span><button type="button" class="field-button" data-action="pick-voice" data-key="voice" aria-labelledby="voice-label voice-value"><span id="voice-value">${findLabel(voices, ui.draft.voice, '选择中文音色')}</span>${icon('next')}</button><small>具体可用音色以所选地区的服务为准。</small></div>
      <div class="form-field"><label class="form-label" for="azure-key">资源密钥</label><input class="key-input" id="azure-key" data-key="azure-key" type="password" autocomplete="off" spellcheck="false" maxlength="256" value="${ui.draft.entered ? 'DEMO-NOT-A-REAL-KEY' : ''}" placeholder="${a.hasKey ? '已保存 · 输入新密钥可替换' : '输入资源密钥'}" ${ui.errors.key ? 'aria-invalid="true" aria-describedby="error-key"' : ''}><small>密钥保持遮蔽；可随时清除已保存的配置。</small>${fieldError('key')}</div>
      </section>
      ${sampleSection('azure')}
      ${a.status === 'failed' ? '<p class="notice error">未切换到其他引擎。可以修改配置或重试试听。</p>' : ''}
      <div class="lower-actions">${a.hasKey ? btn(state.selected === 'azure' ? '重新确认选用网络语音' : '选用网络朗读', 'use-azure', 'use-azure') : ''}${a.hasKey || ui.draft.hasKey ? btn('清除配置', 'clear-request', 'clear-azure', 'quiet danger') : ''}</div>`;
  }
  function systemBody() {
    const s = state.system;
    const desc = { missing: '这台电视没有检测到语音服务。应用内离线朗读仍可使用。', detecting: '只检查电视是否提供语音服务。', detected: '检测到服务；开启朗读时检查中文支持，尚未确认可以发声。', checking: '正在初始化系统引擎并验证中文支持，结果以本次检查为准。', ready: '本次初始化与中文检查已通过，试听可用性以实际播放结果为准。', failed: s.error };
    return `${statusBlock(C.statusLabel(state, 'system'), e(desc[s.status]), 'system')}
      <div class="system-facts"><p>系统语音由电视上的服务提供。检测到服务，不代表支持中文，也不保证一定能发声。</p><p>没有系统语音服务时，可以继续选用应用内离线中文。</p></div>
      ${sampleSection('system')}
      <div class="lower-actions">${s.servicePresent ? btn('选为当前引擎', 'use-system', 'use-system') : ''}${btn('系统语音设置', 'system-settings', 'system-settings', 'quiet')}</div>`;
  }
  function sampleButton(engine, key) {
    const busy = state.sample.engine === engine && ['preparing', 'playing'].includes(state.sample.phase);
    const failed = state[engine].status === 'failed' || (state.sample.engine === engine && state.sample.phase === 'failed');
    return btn(busy ? '停止试听' : (failed ? '重试试听' : '试听声音'), busy ? 'sample-stop' : `test:${engine}`, key, 'primary');
  }
  function footerContents() {
    let actions = '';
    if (ui.engine === 'offline') {
      const s = state.offline.status;
      if (['missing', 'failed', 'interrupted'].includes(s)) actions = btn(s === 'missing' ? '下载中文语音' : '重新下载', 'download', 'offline-main', 'primary');
      else if (s === 'downloading') actions = btn('取消下载', 'cancel-download', 'offline-main');
      else if (s === 'ready') actions = sampleButton('offline', 'offline-main');
      else actions = btn(s === 'checking' ? '正在检查…' : '正在校验安装…', 'busy-info', 'offline-main', '', 'aria-disabled="true"');
      if (state.selected !== 'offline') actions += btn('选为当前', 'use-offline', 'use-offline', 'quiet');
    } else if (ui.engine === 'azure') {
      actions = ui.dirty || !state.azure.hasKey ? btn('保存配置', 'save-azure', 'azure-main', 'primary') : sampleButton('azure', 'azure-main');
    } else {
      actions = state.sample.engine === 'system' && ['preparing', 'playing'].includes(state.sample.phase) ? btn('停止试听', 'sample-stop', 'system-main', 'primary') : state.system.servicePresent ? btn('检查中文并试听', 'test:system', 'system-main', 'primary') : btn(state.system.status === 'detecting' ? '正在检测…' : '重新检测服务', 'detect-system', 'system-main', 'primary', state.system.status === 'detecting' ? 'aria-disabled="true"' : '');
    }
    return `${actions}${btn(ui.origin === 'reader' ? '返回阅读' : '返回设置', 'manager-back', 'footer-back', 'quiet footer-back')}`;
  }
  function settings() {
    return `<div class="settings-shell" ${ui.modal ? 'inert' : ''}><aside class="engine-sidebar"><div class="brand"><strong>书房 TV</strong><small>一卷在手</small></div><nav class="global-nav" aria-label="主导航"><a class="nav-link" data-key="home-link" href="ui-review.html">${icon('home')}首页</a><a class="nav-link" data-key="library-link" href="reading-features.html">${icon('book')}我的书库</a><button type="button" class="nav-link" data-key="nav-settings" data-action="settings" aria-current="page">${icon('settings')}设置</button></nav></aside><section class="summary-main"><header><h1>设置</h1><p>全局阅读默认与使用偏好</p></header><div class="manager-scroll od-scroll" data-scroll="settings"><button type="button" class="summary-entry" data-action="manage" data-key="speech-entry"><span class="od-field"><strong>朗读与语音</strong><span class="summary-line">${C.names[state.selected]} · ${C.statusLabel(state)}</span><small>${state.selected === 'offline' ? '默认使用应用内离线中文，不依赖系统语音。' : state.selected === 'azure' ? '仅主动选用时联网，朗读段落会发送至 Azure。' : '由电视语音服务提供，开启时验证中文。'}</small></span><span class="entry-action">管理语音 →</span></button><div class="summary-quiet"><span>阅读默认</span><span>22 号 · 宋体 · 舒适行距</span></div><p class="fine-copy">暂停、语速和定时停止在阅读中的朗读控制里调整。</p><div class="lower-actions">${btn('回到阅读', 'reader', 'summary-reader', 'quiet')}</div></div><footer class="key-hints"><span>方向键 移动</span><span>确定 打开</span><span>返回 上一级</span></footer></section></div>`;
  }
  function reader() {
    const textSupported = ['EPUB', 'TXT'].includes(ui.format);
    const active = ['playing', 'preparing', 'paused'].includes(state.playback.phase);
    const phase = { playing: '朗读中', preparing: '正在准备声音', paused: '朗读已暂停' }[state.playback.phase] || '朗读已结束';
    const sentences = p => p.match(/[^。！？]+[。！？]?/g) || [p];
    const copy = textSupported ? `<h1>一页文字，一段声音</h1>${paragraphs.map((p, i) => `<p id="reader-paragraph-${i}" class="${active && i === state.reader.paragraph ? 'reading' : ''}">${sentences(p).map((sentence, j) => `<span class="${active && i === state.reader.paragraph && j === state.reader.sentence ? 'current-sentence' : ''}">${sentence}</span>`).join('')}</p>`).join('')}` : '<h1>图片与 PDF 阅读指南</h1><p>完整显示画面，或按屏幕宽度阅读。</p><p>PDF 与纯图片不提供文字朗读，也不会自动识别图片中的文字。</p>';
    return `<section class="reader ${ui.tools ? 'has-tools' : ''}" ${ui.modal ? 'inert' : ''}><header class="reader-header" ${ui.tools ? 'inert' : ''}><div class="od-field od-fill"><span>书房声音使用指南</span><small>${ui.format === 'IMAGE' ? '纯图片' : ui.format} · 第一章</small></div>${btn('设置', 'settings', 'reader-settings', 'quiet')}</header><div class="reader-grid"><article class="reader-copy od-scroll" ${ui.tools ? 'inert' : 'tabindex="0" data-key="reader-copy"'} data-scroll="reader-copy" aria-label="正文，确定键打开阅读菜单">${copy}</article>${ui.tools ? readerTools() : ''}</div>${ui.tools ? `<footer class="reading-footer" inert><span>位置已保留 · 第 ${state.reader.paragraph + 1} 段</span><small>返回 收起工具</small></footer>` : active && textSupported ? `<footer class="speech-bar" aria-label="朗读控制"><div class="bar-state"><strong>${phase} · ${C.names[state.selected]}</strong><small>${state.playback.speed} 倍 · ${timerLabel()} · 仅前台</small></div>${btn('上一段', 'previous-paragraph', 'play-prev', 'quiet')}${btn(state.playback.phase === 'paused' ? '继续' : '暂停', 'toggle-play', 'play-toggle', 'primary')}${btn('下一段', 'next-paragraph', 'play-next', 'quiet')}${btn('设置', 'reader-controls', 'play-settings', 'quiet')}${btn('结束', 'end-play', 'play-end', 'quiet')}</footer>` : `<footer class="reading-footer"><span>${e(state.playback.reason || '左右翻页 · 确定打开工具')}</span>${btn('阅读菜单', 'reader-menu', 'open-reader-menu', 'quiet')}<small>第 ${state.reader.paragraph + 1} 段</small></footer>`}</section>`;
  }
  function timerLabel() { return state.playback.timer === 'chapter' ? '本章末尾停止' : `${state.playback.timer} 分钟停止`; }
  function readerTools() {
    const supported = ['EPUB', 'TXT'].includes(ui.format);
    const menu = `<p class="label">阅读</p>${supported ? `<button type="button" class="tool-entry" data-action="start-play" data-key="reader-speech"><span>朗读正文</span><small>${C.names[state.selected]} · ${C.statusLabel(state)}</small></button>${btn('朗读控制', 'reader-controls', 'reader-controls', 'quiet')}` : '<p class="notice">PDF 与纯图片不提供文字朗读，不进行文字识别。</p>'}<h3 class="subhead">朗读与语音</h3><p class="fine-copy">当前使用 ${C.names[state.selected]}。更换引擎或下载语音包后，会返回当前阅读位置。</p><div class="lower-actions">${btn('语音管理', 'manage-reader', 'reader-manage', 'quiet')}</div>`;
    const controls = `<p class="fine-copy">${C.names[state.selected]} · ${C.statusLabel(state)}</p><div class="lower-actions">${btn(state.playback.phase === 'playing' ? '暂停' : '继续朗读', 'start-play', 'controls-play', 'primary')}${btn('结束', 'end-play', 'controls-end', 'quiet')}</div><h3 class="subhead">语速</h3><div class="control-options">${[.75, 1, 1.25, 1.5, 2].map(v => btn(`${v} 倍`, `speed:${v}`, `speed-${v}`, '', `aria-pressed="${state.playback.speed === v}"`)).join('')}</div><h3 class="subhead">定时停止</h3><div class="control-options">${['15', '30', '60', 'chapter'].map(v => btn(v === 'chapter' ? '本章末尾' : `${v} 分钟`, `timer:${v}`, `timer-${v}`, '', `aria-pressed="${state.playback.timer === v}"`)).join('')}</div><p class="fine-copy">仅在前台朗读。进入后台后暂停，返回时由你主动继续。</p>`;
    return `<aside class="tool-panel" data-scope role="dialog" aria-modal="true" aria-labelledby="tool-heading"><header class="tool-header"><h2 id="tool-heading">${ui.tools === 'menu' ? '阅读菜单' : '朗读控制'}</h2><button type="button" class="close-button" data-action="close-tools" data-key="close-tools" aria-label="收起阅读工具">${icon('close')}</button></header><div class="tool-scroll od-scroll" data-scroll="tools-${ui.tools}">${ui.tools === 'menu' ? menu : controls}</div><footer class="tool-footer">${ui.tools === 'controls' ? btn('返回菜单', 'reader-menu', 'controls-back', 'quiet') : ''}${btn('继续阅读', 'close-tools', 'continue-reading', 'primary')}</footer></aside>`;
  }
  function modal() {
    const m = ui.modal;
    let title = '', content = '', actions = '';
    if (m.type === 'delete') { title = '删除离线中文语音包？'; content = '<p>删除后可释放约 31.2 MiB 本地空间，下次使用需重新下载。</p><p>当前阅读位置和语音选择会保留，不会自动切换到网络。</p>'; actions = btn('取消', 'modal-close', 'modal-cancel', 'quiet') + btn('删除语音包', 'confirm-delete', 'modal-confirm', 'danger'); }
    if (m.type === 'clear') { title = '清除 Azure 配置？'; content = '<p>清除地区、音色选择和已保存的密钥，并停止当前网络朗读。</p><p>不会自动改用其他引擎。</p>'; actions = btn('取消', 'modal-close', 'modal-cancel', 'quiet') + btn('清除配置', 'confirm-clear', 'modal-confirm', 'danger'); }
    if (m.type === 'consent') { title = m.purpose === 'test' ? '试听 Azure 网络声音' : '选用 Azure 网络朗读？'; content = `<p>${m.purpose === 'test' ? '本次试听会将下方试听文字发送至 Azure。' : '选用后，朗读段落将发送至 Azure。'}可能按用量计费，请确认自己的服务配置。</p><p>不会自动切换；可以随时回到离线中文。</p>${m.purpose === 'test' ? `<p class="sample-quote">${C.SAMPLE}</p>` : ''}`; actions = btn('取消', 'modal-close', 'modal-cancel', 'quiet') + btn(m.purpose === 'test' ? '同意并试听' : '确认选用', 'confirm-consent', 'modal-confirm', 'primary'); }
    if (m.type === 'unready') {
      title = state.selected === 'offline' ? '离线中文尚未就绪' : state.selected === 'azure' ? '网络语音尚未就绪' : '系统语音尚未就绪';
      content = `<p>${C.statusLabel(state)}。${state.selected === 'offline' ? '请先在语音管理中准备中文语音包，系统没有语音服务也不影响离线使用。' : '请进入语音管理完成配置或验证。'}</p><p>当前阅读位置会保留，准备完成后可返回这里继续。</p>`;
      actions = btn('返回阅读', 'modal-close', 'modal-cancel', 'quiet') + btn(state.selected === 'offline' && state.offline.status === 'missing' ? '下载语音' : '语音管理', 'unready-manage', 'modal-confirm', 'primary');
    }
    if (m.type === 'picker') {
      title = m.field === 'region' ? '选择地区' : '选择中文音色';
      const values = m.field === 'region' ? regions : voices;
      content = `<div class="picker-list">${values.map(([value, label]) => btn(label, `pick:${m.field}:${value}`, `pick-${value}`, '', `aria-pressed="${ui.draft[m.field] === value}"`)).join('')}</div>`;
      actions = btn('返回', 'modal-close', 'modal-cancel', 'quiet');
    }
    return `<div class="modal-layer"><section class="modal-card" role="dialog" aria-modal="true" aria-labelledby="modal-heading" data-scope><h2 id="modal-heading">${title}</h2><div class="modal-copy od-scroll" tabindex="0" data-key="modal-copy" data-scroll="modal-${m.type}">${content}</div><footer class="modal-actions">${actions}</footer></section></div>`;
  }
  function openModal(type, key, extra = {}) {
    ui.modal = { type, returnKey: key, ...extra };
    const firstValue = type === 'picker' ? ui.draft[extra.field] || (extra.field === 'region' ? regions[0][0] : voices[0][0]) : '';
    render(type === 'picker' ? `pick-${firstValue}` : 'modal-cancel');
  }
  function closeModal() { const key = ui.modal?.returnKey; ui.modal = null; render(key); }
  function openManager(fromReader = false) {
    send({ type: 'PAUSE', reason: '语音管理已打开' });
    if (fromReader) { ui.origin = 'reader'; ui.savedReader = { ...state.reader }; }
    else ui.origin = 'settings';
    ui.page = 'manager'; ui.engine = state.selected; ui.modal = null; syncDraft();
    render(`${ui.engine}-main`);
  }
  function managerBack() {
    cancelPrefix('sample'); send({ type: 'SAMPLE_STOP' });
    if (ui.origin === 'reader') { if (ui.savedReader) state.reader = { ...ui.savedReader }; ui.page = 'reader'; ui.tools = 'menu'; render('reader-speech'); }
    else { ui.page = 'settings'; render('speech-entry'); }
  }
  function startDownload() {
    cancelPrefix('package'); send({ type: 'DOWNLOAD' }); render('offline-main');
    message('正在演示下载、校验和安装流程；页面不会下载模型或写入文件。');
    function step() {
      if (state.offline.status !== 'downloading') return;
      if (document.querySelector('#result-mode').value === 'fail' && state.offline.progress >= 40) { send({ type: 'PACKAGE_FAIL', message: '下载连接中断，请检查网络后重试。' }); render(); return; }
      send({ type: 'PROGRESS', value: Math.min(100, state.offline.progress + 10) });
      if (state.offline.progress === 100) {
        send({ type: 'DOWNLOADED' }); render();
        schedule('package-install', 1800, () => { if (state.offline.status !== 'installing') return; send({ type: 'INSTALLED', verified: true }); render(); });
      } else { if (ui.page !== 'manager' || ui.engine !== 'azure') render(); schedule('package-download', 650, step); }
    }
    schedule('package-download', 650, step);
  }
  function startSample(engine) {
    cancelPrefix('sample'); send({ type: 'SAMPLE_START', engine });
    if (state.sample.phase !== 'preparing') { toast('当前语音尚未就绪，请先完成准备。'); return; }
    render(`${engine}-main`);
    message('试听为无声状态模拟：仅显示短句准备、当前句和结束；实际音频由 Android 原生实现。');
    schedule('sample-prepare', 1100, () => {
      const success = document.querySelector('#result-mode').value === 'success';
      send({ type: 'SAMPLE_RESULT', success }); render();
      if (!success) return;
      schedule('sample-next', 1700, () => { send({ type: 'SAMPLE_NEXT' }); render(); });
      schedule('sample-finish', 3500, () => { send({ type: 'SAMPLE_END' }); render(); });
    });
  }
  function requestSample(engine, key) {
    if (!state.foreground) { toast('已暂停在后台，请先回到前台。'); return; }
    if (engine === 'azure') {
      if (ui.dirty || !state.azure.hasKey) { toast('请先保存完整的地区、音色和密钥配置。'); return; }
      openModal('consent', key, { purpose: 'test' });
    } else if (engine === 'system' && !state.system.servicePresent) toast('未检测到系统语音引擎，应用内离线朗读仍可使用。');
    else if (engine === 'system' && state.system.status === 'checking') toast('正在检查中文支持，请稍候。');
    else startSample(engine);
  }
  function saveAzure() {
    ui.errors = {};
    if (!ui.draft.region) ui.errors.region = '请选择语音资源所在地区。';
    if (!ui.draft.hasKey) ui.errors.key = '请输入资源密钥。';
    if (Object.keys(ui.errors).length) { render(ui.errors.region ? 'region' : 'azure-key'); return; }
    cancelPrefix('sample'); send({ type: 'AZURE_SAVE', region: ui.draft.region, voice: ui.draft.voice, hasKey: true }); syncDraft(); render('azure-main');
    toast('配置已保存，当前引擎保持不变。请先试听验证。');
  }
  function startPlayback() {
    if (!['EPUB', 'TXT'].includes(ui.format)) return;
    if (!state.foreground) { toast('请先回到前台，再主动继续朗读。'); return; }
    if (!C.ready(state)) { openModal('unready', activeKey() || 'reader-speech'); return; }
    cancelPrefix('sample'); send({ type: 'PLAY_START' }); ui.tools = null; render('play-toggle');
    schedule('play-ready', 600, () => { send({ type: 'PLAY_READY' }); render(); });
  }
  function openTools(type) { send({ type: 'PAUSE', reason: '打开阅读工具后已暂停' }); ui.tools = type; render(type === 'menu' ? 'reader-speech' : 'controls-play'); }
  function moveParagraph(delta) {
    send({ type: 'MOVE_PARAGRAPH', delta }); render(delta > 0 ? 'play-next' : 'play-prev');
    if (state.playback.phase === 'preparing') schedule('play-ready', 600, () => { send({ type: 'PLAY_READY' }); render(); });
    requestAnimationFrame(() => $(`#reader-paragraph-${state.reader.paragraph}`)?.scrollIntoView({ block: 'nearest' }));
  }
  function action(name, key) {
    if (name.startsWith('tab:')) { ui.engine = name.slice(4); render(key); return; }
    if (name.startsWith('test:')) { requestSample(name.slice(5), key); return; }
    if (name.startsWith('pick:')) { const [, field, value] = name.split(':'); ui.draft[field] = value; ui.dirty = true; delete ui.errors[field]; ui.modal = null; render(field); return; }
    if (name.startsWith('speed:')) { send({ type: 'SPEED', value: Number(name.slice(6)) }); render(key); return; }
    if (name.startsWith('timer:')) { send({ type: 'TIMER', value: name.slice(6) }); render(key); return; }
    switch (name) {
      case 'settings': send({ type: 'PAUSE', reason: '已打开设置' }); ui.page = 'settings'; ui.modal = null; render('speech-entry'); break;
      case 'manage': openManager(false); break;
      case 'manage-reader': case 'unready-manage': openManager(true); break;
      case 'manager-back': managerBack(); break;
      case 'reader': ui.page = 'reader'; ui.tools = 'menu'; render('reader-speech'); break;
      case 'download': startDownload(); break;
      case 'cancel-download': cancelPrefix('package'); send({ type: 'CANCEL_DOWNLOAD' }); render('offline-main'); break;
      case 'busy-info': toast('准备完成前不能试听，当前不会显示为就绪。'); break;
      case 'use-offline': send({ type: 'SELECT', engine: 'offline' }); render('offline-main'); break;
      case 'delete-request': openModal('delete', key); break;
      case 'confirm-delete': cancelPrefix('package'); cancelPrefix('sample'); send({ type: 'DELETE_PACKAGE' }); ui.modal = null; render('offline-main'); break;
      case 'sample-stop': cancelPrefix('sample'); send({ type: 'SAMPLE_STOP' }); render(key); break;
      case 'pick-region': openModal('picker', key, { field: 'region' }); break;
      case 'pick-voice': openModal('picker', key, { field: 'voice' }); break;
      case 'save-azure': saveAzure(); break;
      case 'use-azure': if (ui.dirty) toast('请先保存修改，再选用网络语音。'); else openModal('consent', key, { purpose: 'select' }); break;
      case 'confirm-consent': { const purpose = ui.modal.purpose; ui.modal = null; send({ type: 'AZURE_CONSENT' }); if (purpose === 'test') startSample('azure'); else { send({ type: 'SELECT', engine: 'azure' }); render('azure-main'); } break; }
      case 'clear-request': openModal('clear', key); break;
      case 'confirm-clear': cancelPrefix('sample'); send({ type: 'AZURE_CLEAR' }); syncDraft(); ui.modal = null; render('azure-main'); break;
      case 'detect-system':
        if (state.system.status === 'detecting') break;
        send({ type: 'DETECT_SYSTEM' }); render('system-main');
        schedule('detect-system', 900, () => { send({ type: 'SYSTEM_DETECTED', present: state.system.servicePresent }); render(); }); break;
      case 'use-system': send({ type: 'SELECT', engine: 'system' }); render('system-main'); break;
      case 'system-settings': toast('未能打开系统语音设置。可在电视设置中检查语音服务。'); message('系统设置跳转失败样例：网页不会打开本机设置，也不会用系统服务缺失阻断离线中文。'); break;
      case 'modal-close': closeModal(); break;
      case 'start-play': startPlayback(); break;
      case 'toggle-play': if (state.playback.phase === 'paused') startPlayback(); else { send({ type: 'PAUSE' }); render(key); } break;
      case 'previous-paragraph': moveParagraph(-1); break;
      case 'next-paragraph': moveParagraph(1); break;
      case 'reader-menu': openTools('menu'); break;
      case 'reader-controls': openTools('controls'); break;
      case 'close-tools': ui.tools = null; render(['paused', 'playing', 'preparing'].includes(state.playback.phase) ? 'play-toggle' : 'open-reader-menu'); break;
      case 'end-play': cancel('play-ready'); send({ type: 'PLAY_END' }); ui.tools = null; render('open-reader-menu'); break;
    }
  }
  function back() {
    if (ui.modal) { closeModal(); return; }
    if (ui.page === 'manager') { managerBack(); return; }
    if (ui.page === 'reader' && ui.tools === 'controls') { ui.tools = 'menu'; render('reader-controls'); return; }
    if (ui.page === 'reader' && ui.tools) { ui.tools = null; render(state.playback.phase === 'paused' ? 'play-toggle' : 'open-reader-menu'); return; }
    if (ui.page === 'reader') { send({ type: 'PAUSE', reason: '已返回设置' }); ui.page = 'settings'; render('summary-reader'); return; }
    focusKey('nav-settings');
  }
  function directional(key) {
    const scope = focusScope();
    const items = visible(scope);
    const current = document.activeElement;
    if (!items.includes(current)) { items[0]?.focus(); return; }
    const vertical = key === 'ArrowUp' || key === 'ArrowDown';
    const sign = key === 'ArrowLeft' || key === 'ArrowUp' ? -1 : 1;
    if (!ui.modal && ui.page === 'manager') {
      const content = $('.manager-scroll');
      const footer = $('#manager-footer');
      const fields = visible(content);
      if (vertical && content.contains(current)) {
        const index = fields.indexOf(current), next = fields[index + sign];
        if (next) { next.focus({ preventScroll: true }); next.scrollIntoView({ block: 'nearest' }); return; }
        if (sign > 0) { visible(footer)[0]?.focus(); return; }
      }
      if (vertical && footer.contains(current) && sign < 0 && fields.length) { fields.at(-1).focus({ preventScroll: true }); fields.at(-1).scrollIntoView({ block: 'nearest' }); return; }
      if (current.closest('.engine-nav') && key === 'ArrowRight') { focusKey(`${ui.engine}-main`); return; }
      if (key === 'ArrowLeft' && content.contains(current)) { focusKey(`tab-${ui.engine}`); return; }
    }
    const bounds = current.getBoundingClientRect();
    const candidates = items.filter(item => item !== current).map(item => {
      const r = item.getBoundingClientRect();
      const primary = vertical ? r.y + r.height / 2 - bounds.y - bounds.height / 2 : r.x + r.width / 2 - bounds.x - bounds.width / 2;
      const secondary = vertical ? r.x + r.width / 2 - bounds.x - bounds.width / 2 : r.y + r.height / 2 - bounds.y - bounds.height / 2;
      const aligned = vertical ? r.left < bounds.right && r.right > bounds.left : r.top < bounds.bottom && r.bottom > bounds.top;
      return { item, primary, score: Math.abs(primary) + Math.abs(secondary) * (aligned ? .15 : 4) + (aligned ? 0 : 120) };
    }).filter(item => item.primary * sign > 2).sort((a, b) => a.score - b.score);
    if (candidates[0]) { candidates[0].item.focus({ preventScroll: true }); candidates[0].item.scrollIntoView({ block: 'nearest', inline: 'nearest' }); }
  }
  function applyFixture(value) {
    if (!value) return;
    clearJobs(); state = C.createState(); ui.modal = null; ui.toast = ''; ui.scroll = {}; ui.origin = 'settings'; ui.page = 'manager'; ui.engine = 'offline'; ui.tools = 'menu';
    if (value === 'downloading') { state.offline.status = 'downloading'; state.offline.progress = 40; }
    if (value === 'installing') { state.offline.status = 'installing'; state.offline.progress = 100; }
    if (value === 'ready' || value === 'restart') { state.offline.status = 'ready'; state.offline.progress = 100; state.offline.validPackage = true; }
    if (['failed', 'checksum', 'space'].includes(value)) { state.offline.status = 'failed'; state.offline.error = ({ failed: '下载连接中断，请检查网络后重试。', checksum: '语音包检查未通过，请重新下载。', space: '剩余空间不足，请整理本地空间后重试。建议预留 120 MiB。' })[value]; }
    if (value === 'interrupted') { state.offline.status = 'interrupted'; state.offline.error = '上次准备未完成。'; }
    if (value.startsWith('azure-')) {
      ui.engine = 'azure';
      if (value !== 'azure-empty') { state.azure = { status: value === 'azure-ready' ? 'ready' : 'failed', region: 'eastasia', voice: 'zh-CN-XiaoxiaoNeural', hasKey: true, consent: true, error: value === 'azure-failed' ? '试听未完成，请检查地区、密钥或网络后重试。' : '' }; state.selected = 'azure'; }
    }
    if (value.startsWith('system-')) {
      ui.engine = 'system'; state.selected = 'system';
      state.system.servicePresent = value !== 'system-missing'; state.system.status = ({ 'system-missing': 'missing', 'system-detected': 'detected', 'system-failed': 'failed' })[value]; state.system.error = value === 'system-failed' ? '当前引擎的中文支持未通过检查。' : '';
    }
    syncDraft();
    if (value === 'restart') restart(); else render(`${ui.engine}-main`);
    message('已载入合成状态样例；不会连接网络、下载语音包、读取密钥或实际发声。冻结的下载/安装样例可用取消、模拟重启或切换状态审核。');
  }
  function restart() {
    clearJobs(); send({ type: 'RESTART' }); ui.modal = null; ui.page = 'manager'; ui.engine = 'offline'; render('offline-main');
    message('模拟重启：保留当前引擎偏好，但重新检查语音包；未完成的下载或安装不会变成就绪。刷新网页会恢复未下载样例。');
    if (state.offline.status === 'checking') schedule('package-recheck', 1600, () => { send({ type: 'INSTALLED', verified: document.querySelector('#result-mode').value === 'success' }); render(); });
  }
  app.addEventListener('click', event => { const target = event.target.closest('[data-action]'); if (target && !target.closest('[inert]')) action(target.dataset.action, target.dataset.key); });
  app.addEventListener('input', event => {
    if (event.target.id !== 'azure-key') return;
    ui.draft.entered = Boolean(event.target.value); ui.draft.hasKey = ui.draft.entered || state.azure.hasKey; ui.dirty = true; delete ui.errors.key;
    if ($('#manager-footer')) $('#manager-footer').innerHTML = footerContents();
  });
  document.querySelectorAll('[data-page]').forEach(node => node.addEventListener('click', () => {
    cancelPrefix('sample'); send({ type: 'SAMPLE_STOP' }); send({ type: 'PAUSE', reason: '已离开阅读' }); ui.page = node.dataset.page; ui.modal = null; ui.toast = ''; ui.origin = 'settings';
    if (ui.page === 'reader') ui.tools = 'menu';
    if (ui.page === 'manager') ui.engine = state.selected;
    render(ui.page === 'reader' ? 'reader-speech' : ui.page === 'settings' ? 'speech-entry' : `${ui.engine}-main`);
  }));
  document.querySelector('#viewport-size').addEventListener('change', event => { frame.dataset.size = event.target.value; message(event.target.value === '720' ? '720p：按 640×360dp 重排，标签至少 12sp、操作至少 44dp；网络配置可纵向滚动。' : '1080p：960×540dp；状态与主操作固定可见，正文试听为无声模拟。'); });
  document.querySelector('#fixture').addEventListener('change', event => applyFixture(event.target.value));
  document.querySelector('#restart').addEventListener('click', restart);
  document.querySelector('#fill-config').addEventListener('click', () => { ui.page = 'manager'; ui.engine = 'azure'; ui.modal = null; ui.draft = { region: 'eastasia', voice: 'zh-CN-XiaoxiaoNeural', hasKey: true, entered: true }; ui.dirty = true; ui.errors = {}; render('azure-main'); message('已填入不可用的合成配置，不含真实账号或密钥；保存仍不会自动选用 Azure。'); });
  document.querySelector('#background-toggle').addEventListener('click', () => {
    if (state.foreground) { cancelPrefix('sample'); send({ type: 'BACKGROUND' }); message('后台状态样例：朗读暂停。回到前台后仍需主动继续。'); }
    else { send({ type: 'FOREGROUND' }); message('已回到前台，保持暂停；请在阅读控制栏主动继续。'); }
    render(undefined, false);
  });
  document.querySelector('#reading-format').addEventListener('change', event => { ui.format = event.target.value; send({ type: 'PLAY_END' }); ui.page = 'reader'; ui.modal = null; ui.tools = 'menu'; render(['EPUB', 'TXT'].includes(ui.format) ? 'reader-speech' : 'reader-manage'); });
  document.addEventListener('keydown', event => {
    if (!app.contains(document.activeElement) && document.activeElement !== document.body) return;
    if (event.key === 'Escape' || event.key === 'BrowserBack') { event.preventDefault(); back(); return; }
    if (document.activeElement?.tagName === 'INPUT') {
      if (event.key === 'Enter') { event.preventDefault(); saveAzure(); return; }
      if (['ArrowLeft', 'ArrowRight'].includes(event.key)) return;
    }
    if (document.activeElement?.dataset.key === 'modal-copy' && ['ArrowUp', 'ArrowDown'].includes(event.key)) {
      const copy = document.activeElement; const before = copy.scrollTop; copy.scrollTop += copy.clientHeight * .6 * (event.key === 'ArrowDown' ? 1 : -1);
      if (copy.scrollTop !== before) { event.preventDefault(); return; }
    }
    if (ui.page === 'reader' && !ui.tools && document.activeElement?.dataset.key === 'reader-copy' && event.key === 'Enter') { event.preventDefault(); openTools('menu'); return; }
    if (ui.page === 'reader' && !ui.tools && document.activeElement?.dataset.key === 'reader-copy' && ['ArrowUp', 'ArrowDown'].includes(event.key)) {
      const copy = document.activeElement; const before = copy.scrollTop; copy.scrollTop += copy.clientHeight * .6 * (event.key === 'ArrowDown' ? 1 : -1);
      if (copy.scrollTop !== before) { event.preventDefault(); return; }
    }
    if (['ArrowUp', 'ArrowDown', 'ArrowLeft', 'ArrowRight'].includes(event.key)) { event.preventDefault(); directional(event.key); }
    if (event.key === 'Tab' && $('[data-scope]')) {
      const items = visible(focusScope()); const i = items.indexOf(document.activeElement); event.preventDefault(); items[(i + (event.shiftKey ? -1 : 1) + items.length) % items.length]?.focus();
    }
  });
  document.addEventListener('visibilitychange', () => { cancelPrefix('sample'); send({ type: document.hidden ? 'BACKGROUND' : 'FOREGROUND' }); if (!document.hidden) render(undefined, false); });
  let sentenceClock = 0;
  setInterval(() => {
    if (state.playback.phase !== 'playing' || !state.foreground || ui.page !== 'reader') { sentenceClock = 0; return; }
    send({ type: 'PLAY_TICK', seconds: 1 }); sentenceClock += state.playback.speed;
    if (sentenceClock >= 4 && state.playback.phase === 'playing') { sentenceClock = 0; send({ type: 'NEXT_SENTENCE' }); render(undefined, false); }
    else if (state.playback.phase !== 'playing') render(undefined, false);
  }, 1000);
  render('offline-main');
})();
