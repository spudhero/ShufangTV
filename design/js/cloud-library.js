(() => {
  'use strict';

  const $ = (selector, root = document) => root.querySelector(selector);
  const $$ = (selector, root = document) => Array.from(root.querySelectorAll(selector));
  const escapeHTML = value => String(value ?? '').replace(/[&<>"']/g, char => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[char]));
  const providers = [
    { id: 'ali', name: '阿里云盘', mark: '阿', app: '阿里云盘 App' },
    { id: 'baidu', name: '百度网盘', mark: '百度', app: '百度网盘 App' },
    { id: 'quark', name: '夸克网盘', mark: 'Q', app: '夸克 App' },
    { id: 'uc', name: 'UC网盘', mark: 'UC', app: 'UC 浏览器' }
  ];
  const provider = id => providers.find(item => item.id === id) || providers[0];
  const driveName = id => ({resource:'资源库', backup:'备份盘', root:'我的网盘'}[id] || '选择存储位置');
  const seedSources = () => [
    {id:'ali-literature', provider:'ali', drive:'resource', path:['我的书籍','文学'], name:'文学'},
    {id:'ali-technology', provider:'ali', drive:'resource', path:['我的书籍','技术'], name:'技术'}
  ];
  const state = {
    page: 'sources', accounts: {ali:true}, sources: seedSources(), provider:'ali',
    folder: {drive:null, path:[], page:0, history:[]}, browse: {source:null, path:[], page:0, history:[]},
    fixture:'normal', sourceFixture:'connected', modals:[], bridge:null, afterAdd:false,
    qrSerial:0, lastMode:null
  };
  let authTimer, readTimer, toastTimer, focusVersion = 0;
  const scrollMemory = new Map();
  const root = $('#cloud-content');
  const overlay = $('#cloud-overlay');
  const tv = $('#cloud-tv');

  const paths = {
    book:'M3 4.5c4-1 6-.5 9 1.5 3-2 5-2.5 9-1.5v15c-4-1-6-.5-9 1.5-3-2-5-2.5-9-1.5zM12 6v15',
    home:'m3 10 9-7 9 7M5 9v12h5v-7h4v7h5V9',
    cloud:'M6 18h12a4 4 0 0 0 1-7.9A7 7 0 0 0 5.5 8.5 4.8 4.8 0 0 0 6 18Z',
    folder:'M3 6h7l2 3h9v11H3zM3 6V4h7l2 2h8v3',
    right:'m9 5 7 7-7 7', left:'m15 5-7 7 7 7', up:'m5 14 7-7 7 7',
    plus:'M12 4v16M4 12h16', close:'m6 6 12 12M18 6 6 18',
    check:'m5 12 4 4L19 6', more:'M5 11v2M12 11v2M19 11v2',
    disk:'M4 4h16v16H4zM7 8h10M7 16h3',
    backup:'M5 4h14v16H5zM9 15V9m-3 3 3-3 3 3M14 8h2M14 16h2',
    network:'M9 3h6v5H9zM3 16h6v5H3zM15 16h6v5h-6zM12 8v4M6 16v-4h12v4',
    globe:'M21 12a9 9 0 1 1-18 0 9 9 0 0 1 18 0ZM3 12h18M12 3c5 6 5 12 0 18-5-6-5-12 0-18',
    usb:'M12 21V4m-3 3 3-4 3 4M12 16c-5 0-6-3-6-6M12 13c5 0 6-3 6-6M4 8h4v3H4M16 4h4v3h-4',
    info:'M12 11v6M12 7v1M21 12a9 9 0 1 1-18 0 9 9 0 0 1 18 0Z',
    refresh:'M20 7a9 9 0 0 0-15 0L3 10M3 4v6h6M4 17a9 9 0 0 0 15 0l2-3M21 20v-6h-6',
    clock:'M12 7v5l4 2M21 12a9 9 0 1 1-18 0 9 9 0 0 1 18 0Z',
    download:'M12 3v12m-5-5 5 5 5-5M4 15v6h16v-6',
    logout:'M9 4H4v16h5M8 12h13m-5-5 5 5-5 5',
    trash:'M3 6h18M9 6V3h6v3M6 6l1 15h10l1-15M10 10v7M14 10v7',
    alert:'m12 3 10 18H2zM12 9v5M12 17v1',
    file:'M5 3h9l5 5v13H5zM14 3v6h5M9 13h6M9 17h6'
  };
  function icon(name) {
    return `<svg class="od-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path d="${paths[name] || paths.file}"/></svg>`;
  }
  function mark(id) {
    const p = provider(id);
    return `<span class="provider-mark ${p.id}" aria-hidden="true">${p.mark}</span>`;
  }
  function button(label, action, key, cls = 'quiet-button', extra = '') {
    return `<button type="button" class="${cls}" data-action="${action}" data-focus-key="${key}" ${extra}>${label}</button>`;
  }
  function hints() {
    return `<div class="screen-hints"><span><kbd class="keycap">↔ ↕</kbd>移动焦点</span><span><kbd class="keycap">OK</kbd>确定</span><span><kbd class="keycap">↩</kbd>返回</span></div>`;
  }
  function sourcePath(source) {
    return [provider(source.provider).name, driveName(source.drive), ...source.path].join(' / ');
  }
  function currentModal() { return state.modals.at(-1); }
  function scope() { return currentModal() ? overlay : root; }
  function focusKey() { return document.activeElement?.dataset?.focusKey || ''; }
  function controls(within = scope()) {
    return $$('button, a[href], [tabindex="0"]', within).filter(el => !el.disabled && !el.closest('[hidden], [inert]') && el.getClientRects().length && getComputedStyle(el).visibility !== 'hidden');
  }
  function focus(key, fallback) {
    const version = ++focusVersion;
    requestAnimationFrame(() => {
      if (version !== focusVersion || state.bridge) return;
      const items = controls();
      const target = items.find(el => el.dataset.focusKey === key) || items.find(el => el.dataset.focusKey === fallback) || items[0];
      if (!target) return;
      target.focus({preventScroll:true});
      reveal(target);
    });
  }
  function reveal(target) {
    const scroller = target.closest('.directory-list, .source-list, .dialog-body');
    if (!scroller) return;
    const item = target.getBoundingClientRect(), region = scroller.getBoundingClientRect();
    const scale = tv.getBoundingClientRect().width / 960;
    if (item.top < region.top + 6 * scale) scroller.scrollTop -= (region.top - item.top) / scale + 6;
    if (item.bottom > region.bottom - 6 * scale) scroller.scrollTop += (item.bottom - region.bottom) / scale + 6;
  }
  function moveFocus(direction) {
    const list = controls();
    const active = document.activeElement;
    if (!list.includes(active)) return focus(defaultFocus());
    const a = active.getBoundingClientRect();
    const vertical = direction === 'up' || direction === 'down';
    const sign = direction === 'up' || direction === 'left' ? -1 : 1;
    const ax = (a.left+a.right)/2, ay = (a.top+a.bottom)/2;
    const candidates = list.filter(el => el !== active).map(el => {
      const b = el.getBoundingClientRect(), bx = (b.left+b.right)/2, by = (b.top+b.bottom)/2;
      const along = (vertical ? by-ay : bx-ax) * sign;
      const across = Math.abs(vertical ? bx-ax : by-ay);
      const inBeam = vertical ? b.right > a.left+2 && b.left < a.right-2 : b.bottom > a.top+2 && b.top < a.bottom-2;
      return {el, along, score:along + across*2.5 + (inBeam ? 0 : 1000)};
    }).filter(item => item.along > 3).sort((a,b) => a.score-b.score);
    if (candidates[0]) { candidates[0].el.focus({preventScroll:true}); reveal(candidates[0].el); }
  }
  function defaultFocus() {
    if (state.page === 'folder') return !state.folder.drive ? 'drive-resource' : 'folder-0';
    if (state.page === 'browse') return 'entry-0';
    return state.sources.length ? 'source-'+state.sources[0].id : 'provider-ali';
  }
  function report(message) { $('#preview-message').textContent = message; }
  function toast(message) {
    clearTimeout(toastTimer);
    $('#cloud-toast').textContent = message;
    $('#cloud-toast').hidden = false;
    toastTimer = setTimeout(() => { $('#cloud-toast').hidden = true; }, 2200);
  }
  function clearAsync() { clearTimeout(authTimer); clearTimeout(readTimer); }
  function pageMemoryKey() {
    return state.page + ':' + (state.page === 'browse' ? state.browse.source?.id + ':' + state.browse.path.join('/') : state.folder.path.join('/'));
  }
  function sourcesHTML() {
    const anyAccount = Object.values(state.accounts).some(Boolean);
    const accounts = `<section aria-labelledby="accounts-title"><div class="section-label"><h2 id="accounts-title">网盘账号</h2><p>一个账号，可以添加多个文件夹</p></div><div class="providers">${providers.map(p => button(`${mark(p.id)}<span class="provider-name">${p.name}</span><span class="provider-sub ${state.accounts[p.id]?'connected':''}">${state.accounts[p.id]?icon('check')+'已连接 · 管理':'扫码连接'}</span>`, 'provider', 'provider-'+p.id, 'provider-card', `data-provider="${p.id}"`)).join('')}</div></section>`;
    const folders = `<section class="folders-section" aria-labelledby="folders-title"><div class="section-label folders-heading"><h2 id="folders-title">书籍文件夹</h2>${anyAccount ? button(icon('plus')+'添加文件夹','add-folder','add-folder','text-button') : '<p>连接网盘后，选择想读的文件夹</p>'}</div>${state.sources.length ? `<div class="source-list" data-scroll-id="sources">${state.sources.map(s => `<div class="source-row">${button(`<span class="folder-glyph">${icon('folder')}</span><span class="od-field"><strong class="od-truncate">${escapeHTML(s.name)}</strong><small class="od-truncate">${escapeHTML(provider(s.provider).name+' · '+driveName(s.drive)+' / '+s.path.join('/'))}</small></span><span class="online-label">${icon(state.accounts[s.provider]?'check':'logout')}${state.accounts[s.provider]?'在线':'需登录'}</span>${icon('right')}`, 'open-source', 'source-'+s.id, 'source-open', `data-id="${escapeHTML(s.id)}" aria-label="打开${escapeHTML(s.name)}，${escapeHTML(provider(s.provider).name)}"`)}${button(icon('more'),'source-menu','source-menu-'+s.id,'icon-button',`data-id="${escapeHTML(s.id)}" aria-label="管理${escapeHTML(s.name)}来源"`)}</div>`).join('')}</div>` : `<div class="source-empty"><span class="folder-glyph">${icon('folder')}</span><div><h3>${anyAccount?'账号已连接，还没有书籍来源':'把自己的书，带到书房'}</h3><p>${anyAccount?'选择一个文件夹，之后就能从这里打开。':'先选择上方网盘，再用手机扫码连接。'}</p></div></div>`}</section>`;
    return `<div class="cloud-shell"><aside class="cloud-sidebar"><div><svg class="brand-book" viewBox="0 0 28 28" fill="none" stroke="currentColor" stroke-width="1.3" aria-hidden="true"><path d="M3 4h9l2 3 2-3h9v20h-9l-2-2-2 2H3zM14 7v15M7 9h3M7 13h3M18 9h3M18 13h3"/></svg><strong class="brand-title">书房 TV</strong><span class="brand-caption">一卷在手<br>片刻清宁</span></div><nav class="cloud-nav" aria-label="书房导航"><a href="index.html" class="nav-item" data-focus-key="nav-home">${icon('home')}<span>我的书房</span></a>${button(icon('cloud')+'<span>书籍来源</span>','sources','nav-sources','nav-item','aria-current="page"')}</nav><p class="sidebar-footer">书在身边<br>读在此刻</p></aside><main class="sources-main"><header class="page-header"><div><h1>书籍来源</h1><p>连接自己的书库，把想读的书留在手边。</p></div></header>${accounts}${folders}<section class="secondary-section" aria-label="本地与家庭存储"><div class="section-label"><h2>本地与家庭存储</h2></div><div class="secondary-sources">${button(icon('usb')+'<span>本地 / USB</span>'+icon('right'),'local','local','secondary-source')}${button(icon('network')+'<span>SMB</span>'+icon('right'),'legacy','legacy-smb','secondary-source','data-source="smb"')}${button(icon('globe')+'<span>WebDAV</span>'+icon('right'),'legacy','legacy-webdav','secondary-source','data-source="webdav"')}</div></section>${hints()}</main></div>`;
  }
  function folderEntries() {
    if (state.fixture === 'empty' || state.folder.path.at(-1) === '待整理') return [];
    const last = state.folder.path.at(-1);
    let names = !last ? (state.folder.drive === 'backup' ? ['设备备份','阅读资料','旧书归档'] : ['我的书籍','阅读资料','共享文档','技术资料与阅读摘录——计算机科学、系统设计及长期归档']) :
      last === '我的书籍' ? ['文学','技术','古籍','待整理'] :
      last === '文学' ? ['散文','诗词','小说'] : last === '技术' ? ['计算机','设计','资料归档'] : [];
    if (state.fixture === 'paged') names = ['我的书籍','阅读资料','共享文档','技术资料与阅读摘录——计算机科学、系统设计及长期归档','古籍','诗词','家庭藏书','待整理'];
    return names.map(name => ({name, type:'folder'}));
  }
  function browseEntries() {
    if (state.fixture === 'empty') return [];
    let entries = [
      {name:'散文',type:'folder'}, {name:'古籍',type:'folder'},
      {name:'浮生六记.epub',type:'epub',book:'fusheng'},
      {name:'道德经.txt',type:'txt',book:'daodejing'},
      {name:'苏轼诗集.pdf',type:'pdf',book:'sushi'}
    ];
    if (state.browse.path.length) entries = [
      {name:'陶庵梦忆.epub',type:'epub',book:'taoan'},
      {name:'论语.txt',type:'txt',book:'lunyu'},
      {name:'传习录.pdf',type:'pdf',book:'chuanxilu'}
    ];
    if (state.browse.source?.name === '技术' && !state.browse.path.length) entries = [{name:'阅读资料',type:'folder'},{name:'排版参考',type:'folder'},{name:'古籍文本',type:'folder'}];
    if (state.fixture === 'paged') entries = [...entries,{name:'诗经.epub',type:'epub',book:'shijing'},{name:'古文选读',type:'folder'}];
    return entries;
  }
  function locationBar(kind) {
    const isFolder = kind === 'folder';
    const current = isFolder ? state.folder : state.browse;
    const rootLabel = isFolder ? driveName(current.drive) : current.source.name;
    const atRoot = current.path.length === 0;
    const canUp = !atRoot || isFolder && current.drive && state.provider === 'ali';
    let crumbs = button(escapeHTML(rootLabel),'crumb-'+kind,'crumb-root','crumb', 'data-depth="0"');
    if (current.path.length > 1) crumbs += icon('right')+button('…','path-info','path-ellipsis','crumb','aria-label="查看完整路径"');
    if (current.path.length) crumbs += icon('right') + button(`<span class="od-truncate">${escapeHTML(current.path.at(-1))}</span>`,'path-info','crumb-current','crumb current-crumb',`aria-label="查看完整路径：${escapeHTML(current.path.join('/'))}"`);
    return `<div class="location-bar">${button(icon('up')+'上一级','up','parent','parent-button',`aria-disabled="${!canUp}"`)}<nav class="breadcrumbs" aria-label="当前位置">${crumbs}</nav>${button(icon('info'),'path-info','path-info','icon-button','aria-label="查看当前位置的完整路径"')}</div>`;
  }
  function flowHeader(title, subtitle, step = '') {
    return `<header class="flow-header">${button(icon('left'),'back','flow-back','icon-button','aria-label="返回上一页"')}<div class="flow-title"><h1>${escapeHTML(title)}</h1><p>${escapeHTML(subtitle)}</p></div>${step ? `<div class="flow-steps"><span class="step-number">${step} / 3</span><span>${step==='2'?'选择文件夹':'浏览书籍'}</span></div>` : '<span class="online-label">'+icon('check')+'在线</span>'}</header>`;
  }
  function directoryState(kind) {
    if (state.fixture === 'error') return `<div class="state-block">${icon('alert')}<h2>暂时无法读取${kind==='folder'?'文件夹':'来源'}</h2><p>请检查连接后重试，当前选择会保留。</p>${button(icon('refresh')+'重试','retry','retry')}</div>`;
    if (state.fixture === 'loading') return `<div class="od-stack" role="status" aria-live="polite"><p class="caption">正在读取${kind==='folder'?'文件夹':'来源'}…</p><div class="loading-list" aria-hidden="true">${Array.from({length:3},()=>'<div class="skeleton-row"><span class="skeleton-square"></span><span class="skeleton-line"></span></div>').join('')}</div></div>`;
    return '';
  }
  function fileList(kind) {
    const special = directoryState(kind);
    if (special) return special;
    const isFolder = kind === 'folder';
    const all = isFolder ? folderEntries() : browseEntries();
    if (!all.length) return `<div class="state-block">${icon('folder')}<h2>${isFolder?'这里没有子文件夹':'这个文件夹里还没有书籍'}</h2><p>${isFolder?'可以选择当前文件夹，也可以返回上一级。':'返回上一级，看看其他文件夹。'}</p></div>`;
    const current = isFolder ? state.folder : state.browse;
    const pageSize = isFolder ? 4 : 5;
    current.page = Math.min(current.page, Math.max(0,Math.ceil(all.length/pageSize)-1));
    const items = all.slice(current.page*pageSize,(current.page+1)*pageSize);
    const list = `<div class="directory-list" data-scroll-id="directory">${items.map((item,index) => button(`${item.type==='folder'?'<span class="folder-glyph">'+icon('folder')+'</span>':`<span class="file-tag ${item.type}">${item.type.toUpperCase()}</span>`}<span class="file-name od-truncate">${escapeHTML(item.name)}</span><span class="row-type">${item.type==='folder'?'文件夹':'书籍'}</span>${icon('right')}`,isFolder?'enter-folder':'entry',`${isFolder?'folder':'entry'}-${index}`,'file-row',`data-index="${current.page*pageSize+index}" aria-label="${escapeHTML(item.name)}${item.type==='folder'?'，打开文件夹':'，选择阅读方式'}"`)).join('')}</div>`;
    return list + (all.length > pageSize ? `<div class="pagination"><span>第 ${current.page+1} 页</span>${button('上一页','page-prev','page-prev','quiet-button',`aria-disabled="${current.page===0}"`)}${button('下一页','page-next','page-next','quiet-button',`aria-disabled="${(current.page+1)*pageSize>=all.length}"`)}</div>` : '');
  }
  function folderHTML() {
    const selectingDrive = !state.folder.drive;
    const name = state.folder.path.at(-1) || driveName(state.folder.drive);
    const canSave = !selectingDrive && !['error','loading'].includes(state.fixture);
    return `<main class="flow-page">${flowHeader('选择书籍文件夹',provider(state.provider).name+' · 账号已连接','2')}${selectingDrive?'<div class="location-bar"><span class="caption">阿里云盘</span>'+icon('right')+'<span>选择存储位置</span></div>':locationBar('folder')}<section class="directory-region" aria-label="文件夹列表">${selectingDrive?`<div class="list-label">先选择资源库或备份盘，再找到书籍文件夹</div><div class="drive-list">${button(icon('disk')+'<span class="od-field"><strong>资源库</strong><small>浏览资源库中的文件夹</small></span>'+icon('right'),'drive','drive-resource','drive-choice','data-drive="resource"')}${button(icon('backup')+'<span class="od-field"><strong>备份盘</strong><small>浏览备份盘中的文件夹</small></span>'+icon('right'),'drive','drive-backup','drive-choice','data-drive="backup"')}</div>`:`<div class="list-label"><span>子文件夹</span><span>按确定键进入文件夹</span></div>${fileList('folder')}`}</section><footer class="selection-footer"><div class="selection-summary"><strong class="od-truncate">${selectingDrive?'选择书籍所在的位置':'当前文件夹 · '+escapeHTML(name)}</strong><p>只保存位置，不下载整个文件夹。</p></div>${button('将此文件夹设为书籍来源','save-source','save-source','primary-button',`aria-disabled="${!canSave}"`)}</footer></main>`;
  }
  function browseHTML() {
    const source = state.browse.source;
    return `<main class="flow-page">${flowHeader(source.name,provider(source.provider).name+' · '+driveName(source.drive),state.afterAdd?'3':'')}${locationBar('browse')}<section class="directory-region" aria-label="来源中的文件与目录"><div class="list-label"><span>文件与目录</span><span class="online-label">${icon('check')}在线来源</span></div>${fileList('browse')}</section><footer class="selection-footer"><div class="selection-summary"><strong>先选一本书，再决定怎样读。</strong><p>在线按需读取；保存离线会占用电视空间。</p></div>${button(icon('info')+'来源信息','source-info','source-info')}</footer></main>`;
  }
  function renderPage(key) {
    if (state.bridge) return;
    const scrollEl = $('[data-scroll-id]',root);
    if (scrollEl) scrollMemory.set(root.dataset.memoryKey, scrollEl.scrollTop);
    root.innerHTML = state.page === 'sources' ? sourcesHTML() : state.page === 'folder' ? folderHTML() : browseHTML();
    root.dataset.memoryKey = pageMemoryKey();
    const nextScroll = $('[data-scroll-id]',root);
    if (nextScroll) nextScroll.scrollTop = scrollMemory.get(root.dataset.memoryKey) || 0;
    root.inert = !!currentModal();
    syncPreview();
    if (!currentModal()) focus(key || defaultFocus(),state.page==='folder'?'save-source':'flow-back');
  }

  // Deliberately non-decodable, deterministic QR-like matrix; never a login ticket.
  function syntheticQR(serial) {
    const n=29, quiet=4, modules=[];
    const protectedCell=(x,y)=>(x<8&&y<8)||(x>=n-8&&y<8)||(x<8&&y>=n-8);
    let seed=0x53f041+serial*97;
    for(let y=0;y<n;y++) for(let x=0;x<n;x++) {
      seed=(Math.imul(seed,1664525)+1013904223)>>>0;
      if(!protectedCell(x,y) && ((seed>>>28)&1)) modules.push([x,y]);
    }
    for(const [ox,oy] of [[0,0],[n-7,0],[0,n-7]]) for(let y=0;y<7;y++) for(let x=0;x<7;x++) {
      if(x===0||x===6||y===0||y===6||(x>=2&&x<=4&&y>=2&&y<=4)) modules.push([ox+x,oy+y]);
    }
    return `<svg class="qr-art" viewBox="0 0 ${n+quiet*2} ${n+quiet*2}" role="img" aria-label="不可登录的合成二维码示意" shape-rendering="crispEdges"><rect width="100%" height="100%" fill="#fff"/><path fill="#141716" d="${modules.map(([x,y])=>`M${x+quiet} ${y+quiet}h1v1h-1z`).join('')}"/></svg>`;
  }
  function dialog(title,subtitle,body,actions='',className='') {
    return `<section class="cloud-dialog ${className}" role="dialog" aria-modal="true" aria-labelledby="cloud-dialog-title"><header class="dialog-header"><div class="dialog-title"><h2 id="cloud-dialog-title">${escapeHTML(title)}</h2>${subtitle?'<p>'+escapeHTML(subtitle)+'</p>':''}</div>${button(icon('close'),'close','dialog-close','icon-button','aria-label="关闭弹窗"')}</header><div class="dialog-body">${body}</div>${actions?'<footer class="dialog-actions">'+actions+'</footer>':''}</section>`;
  }
  function qrHTML(modal) {
    const p=provider(modal.provider), status=modal.status;
    const copy={waiting:['等待扫码','在手机上打开扫码功能。'],scanned:['已扫码','请在手机上确认授权。'],authorizing:['授权中','正在建立账号连接。'],expired:['二维码已过期','刷新后重新扫码。']}[status];
    return dialog('连接'+p.name,'1 / 3 · 扫码连接账号',`<div class="qr-columns"><div class="qr-paper">${syntheticQR(state.qrSerial)}${status==='expired'?'<div class="qr-expired">'+icon('refresh')+'<strong>二维码已过期</strong><span>请刷新后重新扫码</span></div>':''}</div><div class="scan-instructions"><h3>使用${p.app}扫码</h3><ol class="scan-steps"><li><span>1</span><span>打开手机上的${p.app}</span></li><li><span>2</span><span>扫描左侧二维码</span></li><li><span>3</span><span>在手机上确认授权</span></li></ol><div class="scan-status ${status}" role="status" aria-live="polite">${icon(status==='scanned'?'check':status==='expired'?'alert':'clock')}<div><span>${copy[0]}</span><small>${copy[1]}</small></div></div></div></div>`,`<span class="qr-security">授权后，继续选择书籍文件夹</span>${button(icon('refresh')+'刷新二维码','qr-refresh','qr-refresh',status==='expired'?'primary-button':'quiet-button')}${button('取消','close','qr-cancel','quiet-button')}`,'qr-dialog');
  }
  function modalHTML(modal) {
    if(modal.type==='qr') return qrHTML(modal);
    if(modal.type==='account') {
      const p=provider(modal.provider);
      return dialog('管理'+p.name,'账号已连接',[
        button(icon('plus')+'<span class="od-field"><strong>添加书籍文件夹</strong><small>同一账号可以添加多个来源</small></span>'+icon('right'),'account-add','account-add','menu-row',`data-provider="${p.id}"`),
        button(icon('refresh')+'<span class="od-field"><strong>重新登录</strong><small>保留已选择的来源位置</small></span>'+icon('right'),'relogin','relogin','menu-row',`data-provider="${p.id}"`),
        button(icon('logout')+'<span class="od-field"><strong>退出账号</strong><small>不会删除网盘文件</small></span>','logout','logout','menu-row',`data-provider="${p.id}"`)
      ].join(''));
    }
    if(modal.type==='accounts') return dialog('选择网盘账号','向已连接的账号添加书籍文件夹',providers.filter(p=>state.accounts[p.id]).map(p=>button(mark(p.id)+`<span class="od-field"><strong>${p.name}</strong><small>已连接</small></span>`+icon('right'),'account-add','choose-account-'+p.id,'menu-row',`data-provider="${p.id}"`)).join(''));
    if(modal.type==='logout') return dialog('退出'+provider(modal.provider).name+'？','', '<p>书籍来源位置会保留。重新登录后，可以继续打开这些文件夹；网盘文件不会被删除。</p>',button('取消','close','logout-cancel')+button('退出账号','confirm-logout','confirm-logout','quiet-button danger',`data-provider="${modal.provider}"`));
    if(modal.type==='source-menu') {
      const source=state.sources.find(s=>s.id===modal.id);
      return dialog(source.name,provider(source.provider).name,
        button(icon('folder')+'<span class="od-field"><strong>打开书籍来源</strong><small>浏览文件，按需选择阅读方式</small></span>'+icon('right'),'open-source','menu-open','menu-row',`data-id="${source.id}"`)+
        button(icon('info')+'<span>查看完整位置</span>','source-details','source-details','menu-row',`data-id="${source.id}"`)+
        button(icon('trash')+'<span>移除此来源</span>','remove-source','remove-source','menu-row danger',`data-id="${source.id}"`));
    }
    if(modal.type==='remove-source') return dialog('移除此来源？','',`<p>只移除书房里的“${escapeHTML(modal.source.name)}”入口，网盘中的文件夹与书籍会保留。</p>`,button('取消','close','remove-cancel')+button('移除来源','confirm-remove','confirm-remove','quiet-button danger',`data-id="${modal.source.id}"`));
    if(modal.type==='path') return dialog(modal.title,'',`<div class="source-detail"><small>完整位置</small><p>${escapeHTML(modal.path)}</p></div>`,button('知道了','close','path-close'));
    if(modal.type==='exists') return dialog('这个文件夹已经添加','',`<div class="source-detail"><small>书籍来源</small><p>${escapeHTML(sourcePath(modal.source))}</p></div>`,button('返回','close','exists-back')+button('打开来源','open-source','exists-open','primary-button',`data-id="${modal.source.id}"`));
    if(modal.type==='book') return dialog(modal.entry.name,state.browse.source.name+' · '+provider(state.browse.source.provider).name,
      button(icon('book')+'<span class="od-field"><strong>在线阅读</strong><small>按需读取</small></span>'+icon('right'),'mode-online','mode-online','mode-choice online','aria-label="在线阅读，按需读取"')+
      button(icon('download')+'<span class="od-field"><strong>保存离线</strong><small>占用本地空间</small></span>'+icon('right'),'mode-offline','mode-offline','mode-choice','aria-label="保存离线，占用本地空间"'));
    if(modal.type==='local') return dialog('本地与 USB','打开身边的书籍',button(icon('disk')+'<span>电视本地</span>'+icon('right'),'legacy','local-device','menu-row','data-source="local"')+button(icon('usb')+'<span>USB 书库</span>'+icon('right'),'legacy','local-usb','menu-row','data-source="usb"'));
    return '';
  }
  function renderModal(key) {
    const modal=currentModal();
    root.inert=!!modal;
    overlay.hidden=!modal;
    if(!modal) { overlay.replaceChildren(); syncPreview(); return; }
    overlay.innerHTML=modalHTML(modal);
    syncPreview();
    focus(key || (modal.type==='qr'?'qr-refresh':modal.type==='book'?'mode-online':modal.type==='account'?'account-add':modal.type==='logout'?'logout-cancel':modal.type==='remove-source'?'remove-cancel':'dialog-close'));
  }
  function openModal(modal, key) {
    state.modals.push({...modal, returnKey:focusKey() || defaultFocus()});
    renderModal(key);
  }
  function closeModal() {
    const previous=state.modals.pop();
    if(previous?.type==='qr') clearTimeout(authTimer);
    if(currentModal()) renderModal(previous?.returnKey);
    else { renderModal(); renderPage(previous?.returnKey); }
  }
  function closeAll() { clearAsync(); state.modals=[]; root.inert=false; overlay.hidden=true; overlay.replaceChildren(); }
  function openQR(id, resumeSourceId) {
    const returnKey='provider-'+id;
    closeAll(); state.provider=id;
    state.modals=[{type:'qr',provider:id,status:'waiting',resumeSourceId,returnKey}];
    renderModal('qr-refresh');
    report('二维码为不可登录的合成示意。使用画面外的“模拟扫码”推进流程。');
  }
  function setQRStatus(status) {
    const modal=currentModal();
    if(modal?.type!=='qr') return;
    clearTimeout(authTimer);
    modal.status=status;
    renderModal(focusKey());
  }
  function completeLogin() {
    const modal=currentModal();
    if(modal?.type!=='qr'||modal.status!=='authorizing') return;
    const id=modal.provider, resume=state.sources.find(s=>s.id===modal.resumeSourceId);
    state.accounts[id]=true; state.sourceFixture='custom';
    closeAll();
    if(resume) openSource(resume);
    else startFolder(id);
    report('模拟授权完成。接下来仅选择并保存文件夹位置，不会下载文件。');
  }
  function startFolder(id) {
    closeAll(); state.provider=id; state.page='folder'; state.fixture='normal'; state.afterAdd=false;
    state.folder={drive:id==='ali'?null:'root',path:[],page:0,history:[]};
    renderPage();
  }
  function openSource(source) {
    if(!state.accounts[source.provider]) return openQR(source.provider,source.id);
    closeAll(); state.page='browse'; state.fixture='normal'; state.provider=source.provider;
    state.browse={source,path:[],page:0,history:[]}; renderPage();
  }
  function saveSource() {
    if(!state.folder.drive||['error','loading'].includes(state.fixture)) return;
    const existing=state.sources.find(s=>s.provider===state.provider&&s.drive===state.folder.drive&&JSON.stringify(s.path)===JSON.stringify(state.folder.path));
    if(existing) return openModal({type:'exists',source:existing},'exists-open');
    const source={id:'source-'+Date.now().toString(36),provider:state.provider,drive:state.folder.drive,path:[...state.folder.path],name:state.folder.path.at(-1)||driveName(state.folder.drive)};
    state.sources.push(source); state.sourceFixture='custom'; state.afterAdd=true;
    openSource(source); toast('已添加书籍来源');
    report('已保存来源位置：'+sourcePath(source)+'。未下载文件夹或书籍。');
  }
  function pathInfo() {
    const path=state.page==='folder'?[provider(state.provider).name,driveName(state.folder.drive),...state.folder.path].join(' / '):sourcePath(state.browse.source)+ (state.browse.path.length?' / '+state.browse.path.join(' / '):'');
    openModal({type:'path',title:'当前位置',path},'path-close');
  }
  function goUp() {
    clearTimeout(readTimer);
    if(state.page==='folder') {
      if(state.folder.path.length) {
        const old=state.folder.path.pop(), previous=state.folder.history.pop();
        state.fixture=previous?.fixture||'normal'; state.folder.page=previous?.page||0;
        const index=previous?.index??folderEntries().findIndex(item=>item.name===old);
        renderPage('folder-'+Math.max(0,index%4));
      } else if(state.folder.drive&&state.provider==='ali') {
        const previous=state.folder.drive; state.folder.drive=null; state.fixture='normal'; renderPage('drive-'+previous);
      }
    } else if(state.page==='browse'&&state.browse.path.length) {
      const old=state.browse.path.pop(), previous=state.browse.history.pop();
      state.fixture=previous?.fixture||'normal'; state.browse.page=previous?.page||0;
      const index=previous?.index??browseEntries().findIndex(item=>item.name===old); renderPage('entry-'+Math.max(0,index%5));
    }
  }
  function back() {
    if(state.bridge) return closeBridge();
    if(currentModal()) return closeModal();
    if(state.page==='folder' && (state.folder.path.length || state.folder.drive&&state.provider==='ali')) return goUp();
    if(state.page==='browse'&&state.browse.path.length) return goUp();
    if(state.page!=='sources') {
      const key=state.page==='browse'?'source-'+state.browse.source.id:'provider-'+state.provider;
      closeAll(); state.page='sources'; state.afterAdd=false; renderPage(key);
    } else openBridge('home',null,'nav-home');
  }

  // Reuse the existing prototype unchanged. Only its public DOM controls are activated.
  function openBridge(kind, value, returnKey) {
    closeAll();
    state.bridge={kind,value,returnKey:returnKey||defaultFocus()};
    const frame=document.createElement('iframe');
    frame.className='legacy-frame'; frame.title=kind==='reader'?'原有中文阅读界面':'原有本地与家庭书库流程';
    frame.addEventListener('load',() => {
      if(!state.bridge) return;
      try {
        const doc=frame.contentDocument;
        if(!doc?.querySelector('#app')) throw new Error('frame access unavailable');
        const route=kind==='reader'?'library':kind==='home'?'home':'sources';
        doc.querySelector(`[data-action="nav"][data-route="${route}"]`)?.click();
        const target=kind==='reader'?doc.querySelector(`[data-action="read"][data-book="${value}"]`):kind==='home'?null:doc.querySelector(`[data-focus-key="source-${value}"]`);
        if(!target&&kind!=='home') throw new Error('existing entry unavailable');
        target?.click();
        doc.addEventListener('keydown',event => {
          if(event.key==='F6') {event.preventDefault();event.stopImmediatePropagation();$('#preview-tabs button[aria-pressed="true"]').focus();return;}
          if(!['Escape','Backspace'].includes(event.key)||event.key==='Backspace'&&/INPUT|TEXTAREA/.test(doc.activeElement?.tagName)) return;
          const menu=doc.querySelector('#reader-menu'), modal=doc.querySelector('#modal-layer');
          if(menu?.hidden&&modal?.hidden) {event.preventDefault();event.stopImmediatePropagation();closeBridge();}
        },true);
        if(kind==='reader') {
          const observer=new MutationObserver(() => {
            if(!doc.querySelector('.reader-screen')&&state.bridge?.kind==='reader') {observer.disconnect();closeBridge();}
          });
          observer.observe(doc.querySelector('#app'),{childList:true});
        }
        frame.focus();
      } catch {
        report('当前浏览器限制本地页面联动。可以在原有书房内继续操作，或通过本地 HTTP 预览打开本稿。');
      }
    },{once:true});
    frame.src='index.html';
    root.replaceChildren(frame); root.inert=false; syncPreview();
  }
  function closeBridge() {
    const previous=state.bridge; state.bridge=null; renderPage(previous?.returnKey);
    report('已返回网盘流程。原有首页、阅读页和本地来源文件保持不变。');
  }
  function activate(el) {
    if(el.getAttribute('aria-disabled')==='true') return;
    const d=el.dataset, action=d.action;
    if(action==='provider') state.accounts[d.provider]?openModal({type:'account',provider:d.provider},'account-add'):openQR(d.provider);
    else if(action==='sources') {closeAll();state.page='sources';renderPage('nav-sources');}
    else if(action==='add-folder') {
      const connected=providers.filter(p=>state.accounts[p.id]);
      if(connected.length===1) startFolder(connected[0].id); else openModal({type:'accounts'});
    }
    else if(action==='account-add') startFolder(d.provider);
    else if(action==='relogin') openQR(d.provider);
    else if(action==='logout') openModal({type:'logout',provider:d.provider},'logout-cancel');
    else if(action==='confirm-logout') {state.accounts[d.provider]=false;state.sourceFixture='custom';closeAll();renderPage('provider-'+d.provider);toast('账号已退出，来源位置已保留');}
    else if(action==='close') closeModal();
    else if(action==='qr-refresh') {state.qrSerial++;setQRStatus('waiting');report('合成二维码已刷新。不会生成真实登录票据。');}
    else if(action==='drive') {state.folder.drive=d.drive;state.folder.path=[];state.folder.page=0;state.folder.history=[];renderPage();}
    else if(action==='enter-folder') {const index=Number(d.index),entry=folderEntries()[index];if(entry){state.folder.history.push({page:state.folder.page,fixture:state.fixture,index});state.folder.path.push(entry.name);state.folder.page=0;state.fixture='normal';renderPage();}}
    else if(action==='up') goUp();
    else if(action==='back') back();
    else if(action==='path-info') pathInfo();
    else if(action==='crumb-folder') {state.folder.path=state.folder.path.slice(0,Number(d.depth));state.folder.history=[];state.folder.page=0;state.fixture='normal';renderPage();}
    else if(action==='crumb-browse') {state.browse.path=state.browse.path.slice(0,Number(d.depth));state.browse.history=[];state.browse.page=0;state.fixture='normal';renderPage();}
    else if(action==='save-source') saveSource();
    else if(action==='open-source') {const source=state.sources.find(s=>s.id===d.id);if(source){state.afterAdd=false;openSource(source);}}
    else if(action==='source-menu') openModal({type:'source-menu',id:d.id},'menu-open');
    else if(action==='source-details'||action==='source-info') {const source=action==='source-info'?state.browse.source:state.sources.find(s=>s.id===d.id);openModal({type:'path',title:source.name,path:sourcePath(source)},'path-close');}
    else if(action==='remove-source') openModal({type:'remove-source',source:state.sources.find(s=>s.id===d.id)},'remove-cancel');
    else if(action==='confirm-remove') {state.sources=state.sources.filter(s=>s.id!==d.id);state.sourceFixture='custom';closeAll();renderPage(defaultFocus());toast('已移除来源，网盘文件保留');}
    else if(action==='page-next'||action==='page-prev') {const current=state.page==='folder'?state.folder:state.browse;current.page+=action==='page-next'?1:-1;renderPage(state.page==='folder'?'folder-0':'entry-0');}
    else if(action==='retry') {state.fixture='loading';renderPage('flow-back');clearTimeout(readTimer);readTimer=setTimeout(()=>{state.fixture='normal';renderPage();report('读取成功状态已恢复；没有访问真实网盘。');},800);}
    else if(action==='entry') {
      const entry=browseEntries()[Number(d.index)]; if(!entry) return;
      if(entry.type==='folder') {state.browse.history.push({page:state.browse.page,fixture:state.fixture,index:Number(d.index)});state.browse.path.push(entry.name);state.browse.page=0;state.fixture='normal';renderPage();}
      else openModal({type:'book',entry,entryKey:d.focusKey},'mode-online');
    }
    else if(action==='mode-online'||action==='mode-offline') {
      const modal=currentModal(), mode=action==='mode-online'?'online':'offline';
      state.lastMode={mode,file:modal.entry.name,source:sourcePath(state.browse.source)};
      window.dispatchEvent(new CustomEvent('cloud-preview:mode-selected',{detail:{...state.lastMode}}));
      const entry=modal.entry, key=modal.entryKey;
      if(mode==='online') {openBridge('reader',entry.book,key);report('已选择在线按需读取。这里复用原有阅读页及本地示例文本，不读取真实云端文件。');}
      else {closeModal();report('已选择“保存离线”：原生端应接入下载任务与本地空间检查。本稿仅记录选择，不下载文件，也不显示虚假的保存成功。');}
    }
    else if(action==='local') openModal({type:'local'},'local-device');
    else if(action==='legacy') openBridge('source',d.source,d.source==='smb'||d.source==='webdav'?'legacy-'+d.source:'local');
  }
  function syncPreview() {
    const modal=currentModal();
    const active=modal?.type==='qr'?'qr':state.page;
    $$('#preview-tabs button').forEach(el=>el.setAttribute('aria-pressed',String(el.dataset.screen===active)));
    $('#preview-provider').value=state.provider;
    $('#preview-source-state').value=state.sourceFixture;
    $('#preview-directory-state').value=state.fixture;
    $('#qr-controls').hidden=modal?.type!=='qr';
    $('#directory-controls').hidden=!['folder','browse'].includes(state.page)||!!state.bridge;
    $('#bridge-return').hidden=!state.bridge;
    if(modal?.type==='qr') {
      $('#preview-qr-state').value=modal.status;
      $('#simulate-scan').textContent=modal.status==='authorizing'?'模拟授权完成':modal.status==='scanned'?'模拟确认授权':'模拟扫码';
      $('#simulate-scan').disabled=modal.status==='expired';
    }
    requestAnimationFrame(resize);
  }
  function resetSources(fixture) {
    closeAll(); state.bridge=null; state.page='sources'; state.provider='ali'; state.fixture='normal'; state.sourceFixture=fixture;
    state.accounts=fixture==='unconnected'?{}:{ali:true};
    state.sources=fixture==='connected'?seedSources():[];
    state.lastMode=null; state.afterAdd=false;
    renderPage(); report('当前为'+({connected:'阿里账号已连接，包含文学与技术两个来源',empty:'账号已连接、尚未添加来源',unconnected:'尚未连接网盘账号'}[fixture])+'。所有账号和文件均为预览状态。');
  }
  function switchScreen(screen) {
    closeAll(); state.bridge=null;
    if(screen==='sources') {state.page='sources';renderPage();}
    else if(screen==='qr') {state.page='sources';renderPage();openQR(state.provider);}
    else if(screen==='folder') {state.accounts[state.provider]=true;state.sourceFixture='custom';startFolder(state.provider);}
    else {
      const source=state.sources.find(s=>s.provider===state.provider)||{...seedSources()[0],provider:state.provider,drive:state.provider==='ali'?'resource':'root'};
      state.accounts[source.provider]=true;state.afterAdd=false;openSource(source);
    }
  }
  function resize() {
    const stage=$('.preview-stage'), frame=$('.tv-frame');
    const padX=window.innerWidth<=700?16:40;
    const availableWidth=Math.max(1,stage.clientWidth-padX);
    const availableHeight=Math.max(160,window.innerHeight-$('.preview-toolbar').offsetHeight-$('.preview-status').offsetHeight-32);
    const scale=Math.min(availableWidth/960,availableHeight/540,2);
    frame.style.width=(960*scale)+'px'; frame.style.height=(540*scale)+'px';
    tv.style.transform=`scale(${scale})`;
  }
  tv.addEventListener('click',event=>{
    const el=event.target.closest('[data-action]');
    if(!el||el.closest('[inert]')) return;
    el.focus({preventScroll:true}); activate(el);
  });
  document.addEventListener('keydown',event=>{
    if(event.key==='F6') {event.preventDefault();if(tv.contains(document.activeElement))$('#preview-tabs button[aria-pressed="true"]').focus();else focus(defaultFocus());return;}
    if(!tv.contains(document.activeElement)||state.bridge) return;
    if(['Escape','Backspace'].includes(event.key)) {event.preventDefault();back();return;}
    const direction={ArrowUp:'up',ArrowDown:'down',ArrowLeft:'left',ArrowRight:'right'}[event.key];
    if(direction) {event.preventDefault();moveFocus(direction);return;}
    if(event.key==='Tab') {
      event.preventDefault(); const list=controls();const current=list.indexOf(document.activeElement);const index=(current+(event.shiftKey?-1:1)+list.length)%list.length;
      list[index]?.focus({preventScroll:true});if(list[index])reveal(list[index]);
    }
    if(event.key==='Enter'&&event.repeat) event.preventDefault();
  });
  $('#preview-tabs').addEventListener('click',event=>{const button=event.target.closest('[data-screen]');if(button)switchScreen(button.dataset.screen);});
  $('#preview-provider').addEventListener('change',event=>{state.provider=event.target.value;const active=currentModal()?.type==='qr'?'qr':state.page;if(active==='sources')focus('provider-'+state.provider);else switchScreen(active);});
  $('#preview-source-state').addEventListener('change',event=>{if(event.target.value!=='custom')resetSources(event.target.value);});
  $('#preview-directory-state').addEventListener('change',event=>{
    clearTimeout(readTimer);state.fixture=event.target.value;
    if(state.page==='folder'&&!state.folder.drive)state.folder.drive=state.provider==='ali'?'resource':'root';
    (state.page==='folder'?state.folder:state.browse).page=0;renderPage();
  });
  $('#preview-qr-state').addEventListener('change',event=>setQRStatus(event.target.value));
  $('#simulate-scan').addEventListener('click',()=>currentModal()?.status==='authorizing'?completeLogin():setQRStatus(currentModal()?.status==='scanned'?'authorizing':'scanned'));
  $('#reset-preview').addEventListener('click',()=>resetSources('connected'));
  $('#focus-tv').addEventListener('click',()=>state.bridge?$('.legacy-frame').focus():focus(defaultFocus()));
  $('#bridge-return').addEventListener('click',closeBridge);
  window.addEventListener('resize',resize);
  renderPage();
  resize();
})();
