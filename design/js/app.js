(() => {
  'use strict';
  const books = window.TV_BOOKS;
  const F = window.TVFocus;
  const $ = selector => document.querySelector(selector);
  const esc = value => String(value ?? '').replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
  const paths = {
    home:'M3 10 12 3l9 7M5 9v12h5v-7h4v7h5V9',
    book:'M3 4c4-1 7 0 9 2 2-2 5-3 9-2v15c-4-1-7 0-9 2-2-2-5-3-9-2V4Zm9 2v15',
    library:'M4 4h4v16H4zM10 4h4v16h-4zM16 5l4-1 4 15-4 1z',
    bookmark:'M6 3h12v18l-6-4-6 4V3Z',
    folder:'M3 6h7l2 3h9v11H3V6Z',
    settings:'M12 8a4 4 0 1 0 0 8 4 4 0 0 0 0-8ZM10 2h4l1 3 3 1 3 2-1 4 1 4-3 2-3 1-1 3h-4l-1-3-3-1-3-2 1-4-1-4 3-2 3-1 1-3Z',
    right:'m9 5 7 7-7 7', left:'m15 5-7 7 7 7', close:'m6 6 12 12M6 18 18 6',
    search:'M10 3a7 7 0 1 0 0 14 7 7 0 0 0 0-14Zm5 12 6 6',
    sort:'M7 4v16m-4-4 4 4 4-4M14 5h7m-7 6h5m-5 6h3',
    check:'m4 12 5 5L20 6', plus:'M12 4v16M4 12h16', minus:'M4 12h16',
    disk:'M4 3h16v18H4zM7 7h10M7 11h10M8 17h.1M12 17h.1',
    usb:'M8 8V2h8v6M6 8h12v12H6zM10 5h.1M14 5h.1M9 12h6',
    network:'M8 3h8v6H8zM12 9v5M4 14h16M4 14v3m16-3v3M1 17h6v5H1zM17 17h6v5h-6z',
    server:'M3 3h18v7H3zM3 14h18v7H3zM7 6.5h.1M7 17.5h.1M11 6.5h6M11 17.5h6',
    globe:'M12 2a10 10 0 1 0 0 20 10 10 0 0 0 0-20ZM2 12h20M12 2c-6 5-6 15 0 20 6-5 6-15 0-20Z',
    lock:'M6 10h12v11H6zM8 10V6a4 4 0 0 1 8 0v4M12 14v3',
    disconnect:'m3 3 18 18M7 7l-2 2a4 4 0 0 0 5 6l1-1M14 10l1-1a4 4 0 0 0-5-6L8 5',
    sun:'M12 7a5 5 0 1 0 0 10 5 5 0 0 0 0-10ZM12 1v3m0 16v3M1 12h3m16 0h3M4 4l2 2m12 12 2 2M4 20l2-2M18 6l2-2',
    type:'M3 5h18M12 5v16M8 21h8M3 3v4m18-4v4',
    list:'M8 5h13M8 12h13M8 19h13M3 5h.1M3 12h.1M3 19h.1',
    trash:'M3 6h18M9 6V3h6v3M5 6l1 15h12l1-15M10 10v7m4-7v7',
    back:'m8 5-7 7 7 7M2 12h13a6 6 0 0 1 6 6',
    remote:'M8 2h8l2 3v14l-2 3H8l-2-3V5l2-3ZM12 6v6M9 9h6M10 17h.1M14 17h.1',
    moon:'M20 14A9 9 0 0 1 10 2a10 10 0 1 0 10 12Z'
  };
  const icon = (name,extra='') => `<svg class="od-icon ${extra}" viewBox="0 0 24 24" aria-hidden="true" fill="none" stroke="currentColor" stroke-linecap="round" stroke-linejoin="round"><path d="${paths[name] || paths.book}"/></svg>`;
  const btn = (label,action,extra='',cls='secondary',ic='') => `<button type="button" class="${cls}" data-action="${action}" ${extra}>${ic?icon(ic):''}<span>${label}</span></button>`;
  const bookById = id => books.find(book=>book.id===id) || books[0];
  const STORE = 'tv-reader.design.v1';
  const defaults = () => ({version:1,lastBook:'chuanxilu',settings:{size:38,line:1.8,font:'serif',theme:'dark',brightness:100,hints:true},progress:{chuanxilu:{chapter:0,offset:280,updated:5},fusheng:{chapter:1,offset:0,updated:4},taoan:{chapter:0,offset:80,updated:3},daodejing:{chapter:2,offset:0,updated:2,finished:true},shijing:{chapter:0,offset:0,updated:1}},bookmarks:[],connections:{}});
  let storageAvailable = true;
  function loadState() {
    const base=defaults();
    try {
      const raw=JSON.parse(localStorage.getItem(STORE)||'null');
      if(!raw || raw.version!==1) return base;
      base.lastBook=books.some(b=>b.id===raw.lastBook)?raw.lastBook:base.lastBook;
      const s=raw.settings||{};
      base.settings={size:[32,34,36,38,40,42,44,46,48].includes(s.size)?s.size:38,line:[1.6,1.8,2].includes(s.line)?s.line:1.8,font:['serif','sans'].includes(s.font)?s.font:'serif',theme:['dark','paper','ink'].includes(s.theme)?s.theme:'dark',brightness:[60,70,80,90,100].includes(s.brightness)?s.brightness:100,hints:typeof s.hints==='boolean'?s.hints:true};
      if(raw.progress && typeof raw.progress==='object') {
        base.progress={};
        books.forEach(book=>{const p=raw.progress[book.id];if(p && Number.isInteger(p.chapter) && p.chapter>=0 && p.chapter<book.chapters.length && Number.isInteger(p.offset)&&p.offset>=0) base.progress[book.id]={chapter:p.chapter,offset:Math.min(p.offset,book.chapters[p.chapter].paragraphs.join('\n').length-1),updated:Number(p.updated)||0,finished:!!p.finished};});
      }
      base.bookmarks=Array.isArray(raw.bookmarks)?raw.bookmarks.filter(b=>b && books.some(x=>x.id===b.bookId) && Number.isInteger(b.chapter) && b.chapter>=0 && b.chapter<bookById(b.bookId).chapters.length && Number.isInteger(b.offset) && b.offset>=0).slice(0,200):[];
      if(raw.connections && typeof raw.connections==='object') for(const type of ['smb','webdav','ftp','sftp']) {
        const c=raw.connections[type];if(c && typeof c.address==='string') base.connections[type]={address:c.address.slice(0,160),path:String(c.path||'').slice(0,160),connected:!!c.connected};
      }
    } catch { storageAvailable=false; }
    return base;
  }
  let state=loadState();
  let route='home';
  let library={filter:'all',query:'',sort:'recent',source:null};
  let settingsTab='reading';
  let reader={bookId:state.lastBook,index:0,origin:'home',returnKey:'continue'};
  let menuOpen=false;
  let modal=null;
  const modalStack=[];
  let connectTimer=null;
  let toastTimer=null;
  let pagesCache=new Map();
  const scrollPositions=new Map();
  const nav=[['home','首页','home'],['library','我的书库','library'],['bookmarks','我的书签','bookmark'],['sources','书籍来源','folder'],['settings','设置','settings']];
  function save() { try { localStorage.setItem(STORE,JSON.stringify(state)); } catch { storageAvailable=false; } }
  function cover(book,extra='') {
    const patterns={
      mountain:'<path d="M0 100 34 40 50 65 76 15 120 100M12 100l25-42 30 42M46 100l28-64 36 64M10 112h100M0 124h100"/>',
      circle:'<circle cx="56" cy="65" r="42"/><circle cx="56" cy="65" r="32"/><path d="M0 118h112M12 130h88"/>',
      leaf:'<path d="M28 144C32 88 66 52 94 6M38 105C7 93 9 71 14 61c28 2 34 17 24 44ZM61 65c-9-25 4-43 17-48 11 21 5 35-17 48ZM45 92c8-28 28-33 43-29-4 24-19 32-43 29Z"/>',
      window:'<path d="M12 130V42a44 44 0 0 1 88 0v88ZM12 60h88M12 96h88M40 4v126M72 4v126M0 142h112"/>',
      grid:'<path d="M8 6h104v136H8zM20 18h80v112H20zM40 18v112M80 18v112M20 42h80M20 70h80M20 98h80"/>',
      wave:'<path d="M0 48Q28 20 56 48T112 48M0 64Q28 36 56 64T112 64M0 80Q28 52 56 80T112 80M0 96Q28 68 56 96T112 96M0 112Q28 84 56 112T112 112M0 128Q28 100 56 128T112 128"/>'
    };
    return `<div class="book-cover ${extra}" style="--cover-color:${book.color};--cover-ink:${book.ink}" aria-hidden="true"><span class="cover-title">${book.title}</span><span class="cover-author">${book.era} · ${book.author}</span><svg class="cover-art" viewBox="0 0 120 150" fill="none" stroke="currentColor" stroke-width=".9">${patterns[book.motif]}</svg><span class="cover-seal">${book.seal}</span></div>`;
  }
  function applySettings() {
    const s=state.settings;
    const theme={dark:['#1c201d','#e9e7dc'],paper:['#e2dac4','#33382f'],ink:['#0d110f','#dfe4da']}[s.theme];
    const root=$('#tv');
    root.style.setProperty('--reader-size',s.size+'px');root.style.setProperty('--reader-line',s.line);
    root.style.setProperty('--reading-font',s.font==='serif'?'var(--font-book)':'var(--font-ui)');
    root.style.setProperty('--reader-bg',theme[0]);root.style.setProperty('--reader-ink',theme[1]);
  }
  function paragraphsHTML(text,continuation=false) { return text.split('\n').filter(Boolean).map((p,i)=>`<p${i===0&&continuation?' class="continuation"':''}>${esc(p)}</p>`).join(''); }
  function getPages(book) {
    const key=[book.id,state.settings.size,state.settings.line,state.settings.font].join(':');
    if(pagesCache.has(key))return pagesCache.get(key);
    const measure=$('#measure-copy'), pages=[];
    book.chapters.forEach((chapter,ci)=>{
      const text=chapter.paragraphs.join('\n');let start=0;
      while(start<text.length) {
        let low=start+1,high=text.length,end=low;
        while(low<=high) {
          const mid=Math.floor((low+high)/2);
          measure.innerHTML=paragraphsHTML(text.slice(start,mid),start>0&&text[start-1]!=='\n');
          if(measure.scrollHeight<=596){end=mid;low=mid+1;}else high=mid-1;
        }
        if(end<text.length) {
          const segment=text.slice(start,end), breakAt=Math.max(segment.lastIndexOf('。'),segment.lastIndexOf('！'),segment.lastIndexOf('？'),segment.lastIndexOf('\n'));
          if(breakAt>segment.length*.65) end=start+breakAt+1;
          while(/[”’」』，。、；：！？）]/.test(text[end]||'')&&end>start+1)end--;
        }
        const piece=text.slice(start,end);
        pages.push({chapter:ci,start,end,text:piece,continuation:start>0&&text[start-1]!=='\n'});
        start=end;while(text[start]==='\n')start++;
      }
    });
    pagesCache.set(key,pages);return pages;
  }
  function pageFor(book,anchor) {
    const pages=getPages(book);if(!anchor)return 0;
    let index=pages.findIndex(p=>p.chapter===anchor.chapter&&anchor.offset>=p.start&&anchor.offset<p.end);
    if(index<0)index=pages.findIndex(p=>p.chapter===anchor.chapter);
    return Math.max(0,index);
  }
  function progress(book) {
    const p=state.progress[book.id];if(!p)return {index:0,total:getPages(book).length,percent:0,label:'未读'};
    const total=getPages(book).length,index=pageFor(book,p);
    return {index,total,percent:p.finished?100:Math.round((index+1)/total*100),label:p.finished?'已读完':'读至 '+Math.round((index+1)/total*100)+'%'};
  }
  function clockHTML() { return '<div class="clock"><time data-clock></time><span data-date></span></div>'; }
  function updateClock() {
    const now=new Date();document.querySelectorAll('[data-clock]').forEach(el=>el.textContent=now.toLocaleTimeString('zh-CN',{hour:'2-digit',minute:'2-digit',hour12:false}));
    document.querySelectorAll('[data-date]').forEach(el=>el.textContent=now.toLocaleDateString('zh-CN',{month:'long',day:'numeric',weekday:'long'}));
  }
  function footer() { return state.settings.hints?'<footer class="page-footer"><span class="hint"><kbd>方向键</kbd> 移动焦点</span><span class="hint"><kbd>OK</kbd> 确定</span><span class="hint"><kbd>返回</kbd> 上一级</span><span class="footer-tail">书在身边，心自悠然</span></footer>':'<footer class="page-footer"></footer>'; }
  function shell(title,subtitle,body,bodyClass='') {
    return `<div class="app-shell"><aside class="sidebar"><div class="brand"><span class="brand-mark">${icon('book')}</span><div><span class="brand-name">TV Reader</span><span class="brand-sub">我的书房</span></div></div><nav class="nav-list" aria-label="主导航">${nav.map(([id,label,ic])=>btn(label,'nav',`data-route="${id}" data-focus-key="nav-${id}" ${route===id?'aria-current="page"':''}`,'nav-button',ic)).join('')}</nav><div class="sidebar-bottom"><span class="quiet-line"></span><p>一卷在手<br>片刻清宁</p></div></aside><main class="main-screen"><header class="page-heading"><div><h1>${title}</h1><p>${subtitle}</p></div>${clockHTML()}</header><section class="screen-body ${bodyClass}" aria-label="${title}">${body}</section>${footer()}</main></div>`;
  }
  function bookButton(book,context='library') {
    const p=progress(book);
    return `<button type="button" class="book-button" data-action="read" data-book="${book.id}" data-focus-key="${context}-${book.id}" aria-label="阅读${book.title}，${book.author}，${p.label}">${cover(book)}<span class="od-field"><span class="book-name">${book.title}</span><span class="book-author">${book.author}</span>${context==='library'?`<span class="book-status">${p.label}</span>`:''}</span></button>`;
  }
  function homeHTML() {
    const book=bookById(state.lastBook),p=progress(book),chapter=book.chapters[getPages(book)[p.index].chapter];
    const recent=books.filter(b=>b.id!==book.id&&state.progress[b.id]).sort((a,b)=>(state.progress[b.id]?.updated||0)-(state.progress[a.id]?.updated||0)).slice(0,4);
    const body=`<section class="hero" aria-label="正在阅读"><div class="hero-cover">${cover(book)}</div><div class="hero-info"><span class="eyebrow">正在阅读</span><h2>${book.title}</h2><p class="hero-author">${book.era} · ${book.author}</p><p class="hero-quote">${book.subtitle}</p>${btn('继续阅读','read',`data-book="${book.id}" data-focus-key="continue"`,'primary','book')}</div><div class="hero-position"><span class="small">上次读到</span><h3>${chapter.title}</h3><div class="progress-track" role="progressbar" aria-label="阅读进度" aria-valuenow="${p.percent}" aria-valuemin="0" aria-valuemax="100" style="--progress:${p.percent}%"><span></span></div><div class="progress-caption"><span>第 ${p.index+1} / ${p.total} 页</span><span>${p.percent}%</span></div><span class="source-note">${icon('folder')}${sourceName(book.source)}</span></div></section><div class="section-heading"><h2>最近阅读</h2>${btn('查看书库','nav','data-route="library" data-focus-key="all-books"','plain-button','right')}</div><div class="shelf">${recent.map(b=>bookButton(b,'recent')).join('')}<aside class="home-sources"><span class="eyebrow">身边的书库</span>${btn('电视本地','files','data-source="local" data-focus-key="home-local"','plain-button','disk')}${btn('USB 书库','files','data-source="usb" data-focus-key="home-usb"','plain-button','usb')}${btn('管理书籍来源','nav','data-route="sources" data-focus-key="home-sources"','plain-button','right')}</aside></div>`;
    return shell('接着，读下去。','留一点时间，给书里的世界。',body);
  }
  function sourceName(source) {return {local:'电视本地',usb:'USB 书库',smb:'家庭书库',webdav:'WebDAV',ftp:'FTP',sftp:'SFTP'}[source]||source;}
  const aliases={chuanxilu:'传习录 王阳明 wangyangming',daodejing:'道德经 老子 laozi',shijing:'诗经 佚名 yiming',fusheng:'浮生六记 沈复 fushengliuji shenfu',taoan:'陶庵梦忆 张岱 taoanmengyi zhangdai',lunyu:'论语 孔子 kongzi',sushi:'苏轼诗集 苏轼 sushishiji'};
  function filteredBooks() {
    const q=library.query.toLowerCase().trim().replace(/\s/g,'');
    return books.filter(b=>{
      const p=state.progress[b.id];return (!library.source||b.source===library.source)&&(!q||(b.id+b.title+b.author+b.category+aliases[b.id]).toLowerCase().replace(/\s/g,'').includes(q))&&(library.filter==='all'||(library.filter==='reading'&&p&&!p.finished)||(library.filter==='unread'&&!p)||(library.filter==='finished'&&p?.finished));
    }).sort((a,b)=>library.sort==='title'?a.title.localeCompare(b.title,'zh-CN'):library.sort==='author'?a.author.localeCompare(b.author,'zh-CN'):(state.progress[b.id]?.updated||0)-(state.progress[a.id]?.updated||0));
  }
  function libraryHTML() {
    const result=filteredBooks();
    const filters=[['all','全部'],['reading','在读'],['unread','未读'],['finished','读完']];
    const toolbar=`<div class="library-toolbar">${filters.map(([id,label])=>btn(label,'filter',`data-value="${id}" data-focus-key="filter-${id}" aria-pressed="${library.filter===id}"`,'chip')).join('')}<span class="toolbar-spacer"></span>${library.source?btn('全部来源','clear-source','data-focus-key="clear-source"','plain-button'):''}${btn(library.query?esc(library.query.length>7?library.query.slice(0,7)+'…':library.query):'搜索书籍','search','data-focus-key="search"','secondary','search')}${btn({recent:'最近阅读',title:'按书名',author:'按作者'}[library.sort],'sort','data-focus-key="sort"','plain-button','sort')}</div>`;
    const empty=`<div class="empty-state">${icon('search')}<h2>这里还没有找到书</h2><p>${library.query?'换个书名或作者试试，也可以清除搜索。':'试试其他筛选，或到书籍来源寻找新书。'}</p>${btn('显示全部书籍','reset-library','data-focus-key="reset-library"','secondary')}</div>`;
    return shell('我的书库',`${library.source?sourceName(library.source)+' · ':''}${result.length} 本书${library.query?' · 搜索“'+esc(library.query)+'”':''}`,toolbar+(result.length?`<div class="library-grid od-scroll">${result.map(b=>bookButton(b)).join('')}</div>`:empty),'library-body');
  }
  function sourcesHTML() {
    const localCount=books.filter(b=>b.source==='local').length,usbCount=books.filter(b=>b.source==='usb').length,smb=state.connections.smb;
    const card=(type,title,description,status,ic,actions)=>`<section class="source-card">${icon(ic,'source-icon')}<div class="od-field"><h2>${title}</h2><p class="od-clamp-2">${description}</p></div><span class="source-state ${status.includes('未连接')?'offline':''}">${icon(status.includes('未连接')?'network':'check')}${status}</span><div class="source-actions">${actions}</div></section>`;
    const body=`<div class="source-layout"><div class="source-primary">${card('local','电视本地','收藏在电视里的书，随时翻开。',localCount+' 本书 · 可用','disk',btn('浏览书籍','files','data-source="local" data-focus-key="source-local"','secondary','folder'))}${card('usb','USB 书库','接上 U 盘，带来另一架好书。',usbCount+' 本书 · 已接入','usb',btn('浏览书籍','files','data-source="usb" data-focus-key="source-usb"','secondary','folder'))}${card('smb','家庭书库',smb?.connected?esc(smb.address+' / '+smb.path):'连接家中的 NAS 或共享文件夹。',smb?.connected?'已连接 · SMB':'未连接 · SMB','network',smb?.connected?btn('浏览书籍','files','data-source="smb" data-focus-key="source-smb"','secondary','folder')+btn('断开','disconnect','data-source="smb" data-focus-key="disconnect-smb"','plain-button'):btn('连接书库','connect','data-source="smb" data-focus-key="source-smb"','secondary','plus'))}</div><div class="section-heading"><h2>其他连接方式</h2></div><div class="source-secondary">${[['webdav','WebDAV','globe'],['ftp','FTP','server'],['sftp','SFTP','lock']].map(([id,title,ic])=>`<button type="button" class="source-row" data-action="${state.connections[id]?.connected?'connection-options':'connect'}" data-source="${id}" data-focus-key="source-${id}">${icon(ic)}<span class="od-field"><strong>${title}</strong><small>${state.connections[id]?.connected?'已连接':'添加连接'}</small></span>${icon('right')}</button>`).join('')}</div></div>`;
    return shell('书籍来源','从身边的存储，找到想读的书。',body);
  }
  function stepper(key,label,value,unit='') {return `<div class="settings-row"><div class="od-field"><span>${label}</span></div><div class="stepper">${btn('','setting-step',`data-setting="${key}" data-delta="-1" data-focus-key="${key}-down" aria-label="减小${label}"`,'','minus')}<output>${value}${unit}</output>${btn('','setting-step',`data-setting="${key}" data-delta="1" data-focus-key="${key}-up" aria-label="增大${label}"`,'','plus')}</div></div>`;}
  function choiceRow(label,key,choices) {return `<div class="settings-row"><span>${label}</span><div class="setting-options">${choices.map(([id,text])=>btn(text,'setting-choice',`data-setting="${key}" data-value="${id}" data-focus-key="${key}-${id}" aria-pressed="${String(state.settings[key])===String(id)}"`,'chip')).join('')}</div></div>`;}
  function readingControls() {return stepper('size','字号',state.settings.size)+stepper('line','行距',state.settings.line.toFixed(1),' 倍')+choiceRow('字体','font',[['serif','宋体'],['sans','黑体']]);}
  function appearanceControls() {return choiceRow('纸色','theme',[['dark','暗色'],['paper','纸色'],['ink','墨黑']])+stepper('brightness','亮度',state.settings.brightness,'%');}
  function previewHTML() {return `<aside class="settings-preview" style="filter:brightness(${state.settings.brightness}%)"><span class="eyebrow">阅读预览</span><h3>道德经 · 第八章</h3><p>上善若水。水善利万物而不争。</p><p class="preview-caption">设置会自动保存，并应用于阅读。</p></aside>`;}
  function settingsHTML() {
    const remote=`<div class="remote-guide"><div class="settings-row"><span>移动焦点</span><kbd>↑ ↓ ← →</kbd></div><div class="settings-row"><span>翻页</span><kbd>← →</kbd></div><div class="settings-row"><span>确定 / 阅读菜单</span><kbd>OK</kbd></div><div class="settings-row"><span>逐层返回</span><kbd>返回</kbd></div>${choiceRow('按键提示','hints',[[true,'显示'],[false,'隐藏']])}</div>`;
    const body=`<div class="settings-layout"><nav class="settings-tabs" aria-label="设置分组">${[['reading','阅读'],['appearance','外观'],['remote','遥控器']].map(([id,label])=>btn(label,'settings-tab',`data-value="${id}" data-focus-key="settings-${id}" aria-pressed="${settingsTab===id}"`,'chip')).join('')}</nav><div class="settings-content"><div class="settings-controls">${settingsTab==='reading'?readingControls():settingsTab==='appearance'?appearanceControls():remote}</div>${previewHTML()}</div></div>`;
    return shell('设置','让文字与眼睛，保持舒适的距离。',body);
  }
  function bookmarksHTML() {
    const items=state.bookmarks.slice().sort((a,b)=>b.created-a.created);
    const body=items.length?`<div class="bookmarks-list od-scroll">${items.map(mark=>{const b=bookById(mark.bookId),chapter=b.chapters[mark.chapter];return `<article class="bookmark-row"><button type="button" class="bookmark-open" data-action="open-bookmark" data-id="${esc(mark.id)}" data-focus-key="bookmark-${esc(mark.id)}">${cover(b)}<span class="bookmark-copy"><strong>${b.title}</strong><span>${chapter.title}</span><p class="od-clamp-2">${esc(chapter.paragraphs.join(' ').slice(mark.offset,mark.offset+64))}…</p></span></button>${btn('移除','remove-bookmark',`data-id="${esc(mark.id)}" data-focus-key="remove-${esc(mark.id)}" aria-label="移除${b.title}的书签"`,'plain-button','trash')}</article>`;}).join('')}</div>`:`<div class="empty-state">${icon('bookmark')}<h2>留住让你停下来的文字</h2><p>阅读时按确定键，打开菜单即可添加书签。</p>${btn('继续阅读','read',`data-book="${state.lastBook}" data-focus-key="bookmark-continue"`,'primary','book')}</div>`;
    return shell('我的书签',items.length?`${items.length} 处停留，随时重读。`:'值得回来的地方，替你记着。',body);
  }
  function currentPage() {return getPages(bookById(reader.bookId))[reader.index];}
  function markOnPage() {const p=currentPage();return state.bookmarks.find(b=>b.bookId===reader.bookId&&b.chapter===p.chapter&&b.offset>=p.start&&b.offset<p.end);}
  function readerHTML() {
    const book=bookById(reader.bookId),pages=getPages(book),p=pages[reader.index];
    return `<main class="reader-screen" tabindex="0" data-focus-key="reading-surface" aria-label="${book.title}正文"><header class="reader-top"><div class="reader-title"><strong>${book.title}</strong><span>${book.era} · ${book.author}</span></div><span>${markOnPage()?icon('bookmark')+' 已有书签':''}</span></header><article class="reader-article" style="filter:brightness(${state.settings.brightness}%)"><h1 class="reader-chapter">${book.chapters[p.chapter].title}</h1><div class="reader-copy">${paragraphsHTML(p.text,p.continuation)}</div></article><footer class="reader-bottom">${btn('上一页','page','data-delta="-1" aria-label="上一页"','plain-button','left')}<span class="reader-page" aria-live="polite">${reader.index+1} / ${pages.length} <span style="opacity:.55">·</span> ${Math.round((reader.index+1)/pages.length*100)}%</span>${btn(state.settings.hints?'确定键 · 阅读菜单':'阅读菜单','reader-menu','data-focus-key="reader-menu-trigger"','plain-button')}${btn('下一页','page','data-delta="1" aria-label="下一页"','plain-button','right')}</footer></main>`;
  }
  function renderApp(focusKey) {
    applySettings();
    const renderer={home:homeHTML,library:libraryHTML,sources:sourcesHTML,settings:settingsHTML,bookmarks:bookmarksHTML,reader:readerHTML};
    const app=$('#app'),previousScroll=app.querySelector('.library-grid,.bookmarks-list');
    if(previousScroll)scrollPositions.set(app.dataset.view,previousScroll.scrollTop);
    app.innerHTML=renderer[route]();app.dataset.view=route;
    const nextScroll=app.querySelector('.library-grid,.bookmarks-list');if(nextScroll)nextScroll.scrollTop=scrollPositions.get(route)||0;
    updateClock();syncScope();
    if(focusKey && !modal && !menuOpen) focusByKey(focusKey);
  }
  function focusByKey(key) {
    const scope=modal?$('#modal-layer'):menuOpen?$('#reader-menu'):$('#app');
    const target=[...scope.querySelectorAll('[data-focus-key]')].find(el=>el.dataset.focusKey===key);
    F.focus(target || scope.querySelector('main button:not([disabled])') || scope.querySelector('button:not([disabled]),input,[tabindex="0"]'));
  }
  function syncScope() {
    $('#app').inert=!!modal||menuOpen;$('#reader-menu').inert=!!modal;
    F.setScope(modal?$('#modal-layer'):menuOpen?$('#reader-menu'):$('#app'));
  }
  function toast(message) {
    clearTimeout(toastTimer);const el=$('#toast');el.innerHTML=icon('check')+`<span>${esc(message)}</span>`;el.hidden=false;
    toastTimer=setTimeout(()=>el.hidden=true,2600);
  }
  function navigate(next) {
    if(route==='reader')commitPosition();
    route=next;menuOpen=false;$('#reader-menu').hidden=true;
    renderApp(next==='home'?'continue':'nav-'+next);
  }
  function commitPosition() {
    const p=currentPage();state.progress[reader.bookId]={chapter:p.chapter,offset:p.start,updated:Date.now(),finished:reader.index===getPages(bookById(reader.bookId)).length-1};state.lastBook=reader.bookId;save();
  }
  function openBook(id,anchor) {
    const origin=route==='reader'?reader.origin:route,returnKey=modal?.trigger||document.activeElement?.dataset.focusKey||'continue';
    if(modal){modal=null;modalStack.length=0;$('#modal-layer').hidden=true;}
    reader={bookId:id,index:pageFor(bookById(id),anchor||state.progress[id]),origin,returnKey};
    route='reader';menuOpen=false;$('#reader-menu').hidden=true;commitPosition();renderApp('reading-surface');
    $('#app .reader-screen').classList.remove('tv-focus');
  }
  function turnPage(delta) {
    const pages=getPages(bookById(reader.bookId)),next=Math.max(0,Math.min(pages.length-1,reader.index+delta));
    if(next===reader.index){toast(delta>0?'已到本书末页':'已是本书第一页');return;}
    reader.index=next;commitPosition();renderApp(menuOpen?null:'reading-surface');
    $('#app .reader-screen')?.classList.remove('tv-focus');
    if(menuOpen)renderMenu('menu-page');
  }
  function renderMenu(key='menu-toc') {
    const book=bookById(reader.bookId),count=getPages(book).length;
    $('#reader-menu').innerHTML=`<div class="reader-menu-head"><span>${book.title} · 第 ${reader.index+1} / ${count} 页</span><span>返回键收起菜单</span></div><div class="reader-menu-items">${[['目录','toc','list'],[markOnPage()?'已加书签':'添加书签','toggle-bookmark','bookmark'],['字体排版','reader-font','type'],['亮度纸色','reader-appearance','sun'],['跳转页码','page-picker','book'],['收起菜单','reader-menu','close'],['退出阅读','exit-reader','back']].map(([label,action,ic])=>btn(label,action,`data-focus-key="menu-${action==='toc'?'toc':action}" ${action==='toggle-bookmark'?`aria-pressed="${!!markOnPage()}"`:''}`,'menu-button',ic)).join('')}</div>`;
    $('#reader-menu').hidden=false;syncScope();focusByKey(key);
  }
  function toggleMenu() {menuOpen=!menuOpen;if(menuOpen)renderMenu();else{$('#reader-menu').hidden=true;syncScope();focusByKey('reading-surface');$('#app .reader-screen')?.classList.remove('tv-focus');}}
  function exitReader() {commitPosition();route=reader.origin;menuOpen=false;$('#reader-menu').hidden=true;renderApp(reader.returnKey);}
  function openModal(type,data={},trigger) {
    if(modal)modalStack.push(modal);
    modal={type,data,trigger:trigger||document.activeElement?.dataset.focusKey};renderModal();
  }
  function closeModal() {
    if(connectTimer){clearTimeout(connectTimer);connectTimer=null;}
    const previous=modal;modal=modalStack.pop()||null;
    if(modal){renderModal(previous?.trigger);return;}
    $('#modal-layer').hidden=true;$('#modal-layer').innerHTML='';syncScope();
    if(menuOpen)renderMenu(previous?.trigger);else focusByKey(previous?.trigger);
  }
  function modalFrame(title,content,options={}) {
    return `<section class="dialog-panel ${options.wide?'wide':''} ${options.drawer?'drawer-panel':''}" role="dialog" aria-modal="true" aria-labelledby="dialog-title"><header class="dialog-header"><h2 id="dialog-title">${title}</h2>${btn('','close-modal','data-focus-key="modal-close" aria-label="关闭"','icon-button','close')}</header>${options.subtitle?`<p class="dialog-subtitle">${options.subtitle}</p>`:''}<div class="dialog-content">${content}</div>${options.actions?`<div class="dialog-actions">${options.actions}</div>`:''}</section>`;
  }
  function connectionForm() {
    const type=modal.data.source,label=type.toUpperCase(),values=modal.data.values||{},smb=type==='smb';
    const field=(id,title,placeholder,required=false,kind='text')=>`<div class="field"><label for="connect-${id}">${title}<small>${required?'必填':'可选'}</small></label><input id="connect-${id}" type="${kind}" value="${esc(values[id]||'')}" placeholder="${placeholder}" data-field="${id}" data-focus-key="connect-${id}" autocomplete="off" autocapitalize="off" spellcheck="false" ${required?'aria-required="true"':''} aria-describedby="error-${id}"><span class="field-error" id="error-${id}" hidden></span></div>`;
    return modalFrame(smb?'连接家庭书库':'连接 '+label,`<p class="form-summary" id="form-summary" role="alert" hidden></p>${field('address',smb?'服务器地址':type==='webdav'?'服务器网址':'服务器地址',smb?'例如 192.168.1.10':type==='webdav'?'https://nas.local/dav':'nas.local',true)}${field('path',smb?'共享名称或路径':'文件夹路径',smb?'例如 books 或 books/古籍':'/',smb)}<div class="field-pair">${field('username','用户名','留空使用访客身份')}${field('password','密码','输入密码',false,'password')}</div>`,{subtitle:'填写连接信息。使用遥控器时，选中输入框后按确定键。',actions:btn('取消','close-modal','data-focus-key="connection-cancel"','secondary')+btn('连接书库','submit-connection','data-focus-key="connection-submit"','primary')});
  }
  function keyboardHTML(value,purpose) {
    const keyboardKeys='abcdefghijklmnopqrstuvwxyz0123456789./:-_@!?'.split('');
    return `<div class="field"><label for="keyboard-input">${esc(purpose)}</label><input id="keyboard-input" type="${modal.data.field==='password'?'password':'text'}" class="search-input" value="${esc(value)}" data-focus-key="keyboard-input" autocomplete="off" spellcheck="false"></div><div class="search-keyboard">${keyboardKeys.map(k=>btn(modal.data.upper?k.toUpperCase():k,'keyboard-key',`data-value="${esc(modal.data.upper?k.toUpperCase():k)}" data-focus-key="key-${esc(k)}"`,'keyboard-key')).join('')}${btn('大小写','keyboard-shift','data-focus-key="key-shift"','keyboard-key wide-key')}${btn('空格','keyboard-key','data-value=" " data-focus-key="key-space"','keyboard-key wide-key')}${btn('退格','keyboard-delete','data-focus-key="key-delete"','keyboard-key wide-key')}${btn('清空','keyboard-clear','data-focus-key="key-clear"','keyboard-key wide-key')}</div>`;
  }
  function renderModal(focusKey) {
    if(!modal)return;
    const {type,data}=modal;let html='',first='modal-close';
    $('#modal-layer').className='modal-layer'+(type==='toc'?' drawer-layer':'');
    if(type==='search') {
      html=modalFrame('找一本书',keyboardHTML(data.value??library.query,'书名、作者或拼音')+`<div class="search-suggestions">${books.slice(0,5).map(b=>btn(b.title,'search-suggestion',`data-value="${b.title}" data-focus-key="suggestion-${b.id}"`,'chip')).join('')}</div><p class="search-note">支持中文输入，也可用屏幕键盘输入拼音，如 chuanxilu。</p>`,{wide:true,actions:btn('清除搜索','clear-search','data-focus-key="clear-search"','secondary')+btn('搜索','apply-search','data-focus-key="apply-search"','primary','search')});first='keyboard-input';
    } else if(type==='keyboard') {
      html=modalFrame('输入'+data.label,keyboardHTML(data.value||'',data.label),{wide:true,actions:btn('取消','close-modal','data-focus-key="keyboard-cancel"','secondary')+btn('完成输入','keyboard-done','data-focus-key="keyboard-done"','primary')});first='keyboard-input';
    } else if(type==='sort') {
      html=modalFrame('书库排序',`<div class="option-list">${[['recent','最近阅读'],['title','按书名'],['author','按作者']].map(([id,label])=>btn(label,'set-sort',`data-value="${id}" data-focus-key="sort-${id}"`,'dialog-option'+(library.sort===id?' selected':''),library.sort===id?'check':'sort')).join('')}</div>`);first='sort-'+library.sort;
    } else if(type==='toc') {
      const book=bookById(reader.bookId),pages=getPages(book);
      html=modalFrame('目录',`<div class="option-list">${book.chapters.map((c,i)=>`<button type="button" class="dialog-option ${currentPage().chapter===i?'selected':''}" data-action="chapter" data-index="${i}" data-focus-key="chapter-${i}"><span class="chapter-number">0${i+1}</span><span class="od-field"><span>${c.title}</span><small>第 ${pages.findIndex(p=>p.chapter===i)+1} 页</small></span>${currentPage().chapter===i?icon('check'):''}</button>`).join('')}</div>`,{drawer:true,subtitle:book.title});first='chapter-'+currentPage().chapter;
    } else if(type==='reader-font' || type==='reader-appearance') {
      html=modalFrame(type==='reader-font'?'字体与排版':'亮度与纸色',type==='reader-font'?readingControls():appearanceControls(),{subtitle:'调整即时生效，关闭后继续阅读。',actions:btn('完成','close-modal','data-focus-key="reader-settings-done"','primary')});first=type==='reader-font'?'size-up':'theme-'+state.settings.theme;
    } else if(type==='page-picker') {
      const count=getPages(bookById(reader.bookId)).length;
      html=modalFrame('跳转页码',`<div class="option-list">${Array.from({length:count},(_,i)=>btn(`第 ${i+1} 页`,'goto-page',`data-index="${i}" data-focus-key="goto-${i}"`,'dialog-option'+(i===reader.index?' selected':''),i===reader.index?'check':'book')).join('')}</div>`);first='goto-'+reader.index;
    } else if(type==='connect') {
      html=connectionForm();first='connect-address';
    } else if(type==='connecting') {
      html=modalFrame('正在连接',`<div class="connection-wait">${icon('network')}<h3>正在打开${sourceName(data.source)}</h3><p>${esc(data.address)}</p><div class="connection-track" role="progressbar" aria-label="连接中"><span></span></div></div>`,{actions:btn('取消连接','close-modal','data-focus-key="cancel-connecting"','secondary')});first='cancel-connecting';
    } else if(type==='connected') {
      html=modalFrame('连接成功',`<div class="connection-wait">${icon('check')}<h3>${sourceName(data.source)}已就绪</h3><p>${esc(data.address)} / ${esc(data.path)}</p></div>`,{actions:btn('完成','close-modal','data-focus-key="connected-done"','secondary')+btn('浏览书籍','connected-files',`data-source="${data.source}" data-focus-key="connected-files"`,'primary','folder')});first='connected-files';
    } else if(type==='files') {
      const list=books.filter(b=>b.source===data.source),connection=state.connections[data.source];
      const location=connection?`<p class="connection-location">${esc(connection.address)} / ${esc(connection.path)}</p>`:'';
      html=modalFrame(sourceName(data.source),location+(list.length?`<div class="source-browser">${list.map(b=>`<button type="button" class="dialog-option" data-action="read" data-book="${b.id}" data-focus-key="file-${b.id}">${icon('book')}<span class="od-field"><span>${b.title}.epub</span><small>${b.author} · 已在书库中</small></span>${icon('right')}</button>`).join('')}</div>`:'<div class="empty-state" style="min-height:320px"><h2>文件夹里还没有书</h2><p>将书籍放入此文件夹后，再来看看。</p></div>'),{subtitle:'选择书籍，开始阅读。'});first=list.length?'file-'+list[0].id:'modal-close';
    } else if(type==='disconnect' || type==='remove-bookmark') {
      html=modalFrame(type==='disconnect'?'断开书库连接？':'移除此书签？',`<p class="dialog-subtitle">${type==='disconnect'?'已加入书库的书籍和阅读位置仍会保留。':'书籍和阅读位置会保留，之后仍可重新添加书签。'}</p>`,{actions:btn('取消','close-modal','data-focus-key="confirm-cancel"','secondary')+btn(type==='disconnect'?'断开连接':'移除书签',type==='disconnect'?'confirm-disconnect':'confirm-remove',`data-source="${data.source||''}" data-id="${data.id||''}" data-focus-key="confirm-action"`,'primary')});first='confirm-cancel';
    } else if(type==='connection-options') {
      html=modalFrame(sourceName(data.source),btn('浏览书籍','connected-files',`data-source="${data.source}" data-focus-key="connection-files"`,'dialog-option','folder')+btn('断开连接','disconnect',`data-source="${data.source}" data-focus-key="connection-disconnect"`,'dialog-option','disconnect'));first='connection-files';
    }
    $('#modal-layer').innerHTML=html;$('#modal-layer').hidden=false;syncScope();focusByKey(focusKey||first);
  }
  function snapshotForm() {
    const values={};$('#modal-layer').querySelectorAll('[data-field]').forEach(el=>values[el.dataset.field]=el.dataset.field==='password'?el.value:el.value.trim());return values;
  }
  function validateConnection(values,show=true) {
    const errors={},type=modal.data.source;
    if(!values.address)errors.address='请填写服务器地址，例如 192.168.1.10。';
    else if(type==='webdav'?!/^https?:\/\/[^\s/]+(?:\/[^\s]*)?$/.test(values.address):!/^(?:smb:\/\/)?[a-zA-Z0-9][a-zA-Z0-9.:-]*$/.test(values.address))errors.address=type==='webdav'?'请输入以 http:// 或 https:// 开头的网址。':'请填写 IP 地址或主机名，不含空格。';
    if(type==='smb'&&!values.path)errors.path='请填写共享名称或路径，例如 books。';
    if(show) {
      for(const key of ['address','path','username','password']) {const input=$('#connect-'+key),error=$('#error-'+key);input?.setAttribute('aria-invalid',String(!!errors[key]));if(error){error.textContent=errors[key]||'';error.hidden=!errors[key];}}
      const summary=$('#form-summary');summary.textContent=Object.keys(errors).length?'还有 '+Object.keys(errors).length+' 项连接信息需要补充。':'';summary.hidden=!Object.keys(errors).length;
    }
    return errors;
  }
  function submitConnection() {
    const values=snapshotForm(),errors=validateConnection(values);
    if(Object.keys(errors).length){focusByKey('connect-'+Object.keys(errors)[0]);return;}
    const source=modal.data.source,trigger=modal.trigger;
    modal={type:'connecting',data:{source,...values},trigger};renderModal();
    connectTimer=setTimeout(()=>{
      connectTimer=null;if(!modal||modal.type!=='connecting')return;
      if(/(^|[.\/-])(fail|offline|invalid)([.\/-]|$)/i.test(values.address)) {
        modal={type:'connect',data:{source,values:{...values,password:''}},trigger};renderModal('connect-address');
        $('#form-summary').hidden=false;$('#form-summary').textContent='暂时无法连接，请检查地址后重试。';return;
      }
      state.connections[source]={address:values.address,path:values.path||'/',connected:true};save();
      modal={type:'connected',data:{source,address:values.address,path:values.path||'/'},trigger};renderApp();renderModal();
    },1200);
  }
  function changeSetting(key,value) {
    const anchor=route==='reader'?{chapter:currentPage().chapter,offset:currentPage().start}:null;
    state.settings[key]=value;save();applySettings();
    if(anchor){reader.index=pageFor(bookById(reader.bookId),anchor);commitPosition();}
    const focusKey=document.activeElement?.dataset.focusKey;renderApp(modal?null:focusKey);
    if(modal)renderModal(focusKey);else if(menuOpen)renderMenu(focusKey);
  }
  function keyboardValue() {return $('#keyboard-input')?.value||'';}
  function back() {
    if(modal){closeModal();return;}
    if(menuOpen){toggleMenu();return;}
    if(route==='reader'){exitReader();return;}
    if(route!=='home'){navigate('home');return;}
    focusByKey('continue');
  }
  function act(button) {
    const a=button.dataset.action,d=button.dataset;
    if(a==='nav')navigate(d.route);
    else if(a==='read')openBook(d.book);
    else if(a==='filter'){library.filter=d.value;renderApp(d.focusKey);}
    else if(a==='search')openModal('search',{value:library.query});
    else if(a==='sort')openModal('sort');
    else if(a==='set-sort'){library.sort=d.value;closeModal();renderApp('sort');}
    else if(a==='apply-search'){library.query=keyboardValue().trim();closeModal();renderApp('search');}
    else if(a==='clear-search'){library.query='';closeModal();renderApp('search');}
    else if(a==='search-suggestion'){library.query=d.value;closeModal();renderApp('search');}
    else if(a==='reset-library'){library={filter:'all',query:'',sort:'recent',source:null};renderApp('filter-all');}
    else if(a==='clear-source'){library.source=null;renderApp('filter-all');}
    else if(a==='settings-tab'){settingsTab=d.value;renderApp(d.focusKey);}
    else if(a==='setting-choice')changeSetting(d.setting,d.setting==='hints'?d.value==='true':d.value);
    else if(a==='setting-step'){
      const options={size:[32,34,36,38,40,42,44,46,48],line:[1.6,1.8,2],brightness:[60,70,80,90,100]}[d.setting];
      const i=options.indexOf(state.settings[d.setting]),next=options[Math.max(0,Math.min(options.length-1,i+Number(d.delta)))];
      changeSetting(d.setting,next);
    }
    else if(a==='page')turnPage(Number(d.delta));
    else if(a==='reader-menu')toggleMenu();
    else if(a==='exit-reader')exitReader();
    else if(['toc','reader-font','reader-appearance','page-picker'].includes(a))openModal(a);
    else if(a==='chapter'||a==='goto-page'){
      reader.index=a==='chapter'?getPages(bookById(reader.bookId)).findIndex(p=>p.chapter===Number(d.index)):Number(d.index);commitPosition();renderApp();closeModal();
    }
    else if(a==='toggle-bookmark') {
      const existing=markOnPage(),p=currentPage();
      if(existing){state.bookmarks=state.bookmarks.filter(b=>b.id!==existing.id);toast('已移除本页书签');}
      else {state.bookmarks.push({id:globalThis.crypto?.randomUUID?.()||'b'+Date.now(),bookId:reader.bookId,chapter:p.chapter,offset:p.start,created:Date.now()});toast('已添加书签');}
      save();renderApp();renderMenu('menu-toggle-bookmark');
    }
    else if(a==='open-bookmark'){const b=state.bookmarks.find(b=>b.id===d.id);if(b)openBook(b.bookId,b);}
    else if(a==='remove-bookmark')openModal('remove-bookmark',{id:d.id});
    else if(a==='confirm-remove'){state.bookmarks=state.bookmarks.filter(b=>b.id!==d.id);save();renderApp();closeModal();toast('书签已移除');}
    else if(a==='files')openModal('files',{source:d.source});
    else if(a==='connect')openModal('connect',{source:d.source,values:state.connections[d.source]||{}});
    else if(a==='connection-options')openModal('connection-options',{source:d.source});
    else if(a==='disconnect') {
      if(modal){modal.type='disconnect';modal.data={source:d.source};renderModal();}else openModal('disconnect',{source:d.source});
    }
    else if(a==='confirm-disconnect'){state.connections[d.source].connected=false;save();renderApp();closeModal();toast('已断开连接');}
    else if(a==='submit-connection')submitConnection();
    else if(a==='connected-files'){modal={type:'files',data:{source:d.source},trigger:modal?.trigger||'source-'+d.source};modalStack.length=0;renderModal();}
    else if(a==='close-modal')closeModal();
    else if(a==='keyboard-key'){const input=$('#keyboard-input');input.value+=d.value;modal.data.value=input.value;}
    else if(a==='keyboard-delete'){const input=$('#keyboard-input');input.value=Array.from(input.value).slice(0,-1).join('');modal.data.value=input.value;}
    else if(a==='keyboard-clear'){$('#keyboard-input').value='';modal.data.value='';}
    else if(a==='keyboard-shift'){modal.data.value=keyboardValue();modal.data.upper=!modal.data.upper;renderModal('key-shift');}
    else if(a==='keyboard-done'){const value=keyboardValue(),field=modal.data.field;const parent=modalStack[modalStack.length-1];if(parent){parent.data.values[field]=value;}closeModal();}
  }
  function onKey(event) {
    if(event.isComposing || event.altKey || event.ctrlKey || event.metaKey)return;
    const key=event.key,target=event.target,isInput=target instanceof HTMLInputElement;
    if(key==='Escape' || (key==='Backspace'&&!isInput) || key==='BrowserBack') {event.preventDefault();if(!event.repeat)back();return;}
    if(key==='Tab'){event.preventDefault();F.tab(event.shiftKey);return;}
    if(key==='Enter') {
      event.preventDefault();if(event.repeat)return;
      if(isInput) {
        if(modal?.type==='connect'){modal.data.values=snapshotForm();const field=target.dataset.field;openModal('keyboard',{field,value:target.value,label:target.labels?.[0]?.childNodes[0]?.textContent||'内容'},target.dataset.focusKey);}
        else if(modal?.type==='search'){F.focus($('#modal-layer [data-action="apply-search"]'));}
        else if(modal?.type==='keyboard'){F.focus($('#modal-layer [data-action="keyboard-done"]'));}
      } else if(route==='reader'&&!menuOpen&&!modal&&!(target instanceof HTMLButtonElement))toggleMenu();
      else if(target instanceof HTMLButtonElement)target.click();
      return;
    }
    const dirs={ArrowUp:'up',ArrowDown:'down',ArrowLeft:'left',ArrowRight:'right'};
    if(dirs[key]) {
      if(isInput&&(key==='ArrowLeft'||key==='ArrowRight'))return;
      event.preventDefault();
      if(route==='reader'&&!menuOpen&&!modal){
        if(target instanceof HTMLButtonElement && target.closest('.reader-bottom')) {
          if(key==='ArrowUp')focusByKey('reading-surface');
          else if(key==='ArrowLeft'||key==='ArrowRight') {
            const controls=[...document.querySelectorAll('.reader-bottom button')],index=controls.indexOf(target);
            F.focus(controls[Math.max(0,Math.min(controls.length-1,index+(key==='ArrowRight'?1:-1)))]);
          }
        } else if(key==='ArrowLeft'||key==='ArrowRight')turnPage(key==='ArrowRight'?1:-1);
        else if(key==='ArrowDown')focusByKey('reader-menu-trigger');
        return;
      }
      F.move(dirs[key]);
    }
  }
  function resize() {
    const scale=Math.min(window.innerWidth/1920,window.innerHeight/1080);
    $('#tv').style.transform=`scale(${scale})`;
    $('.tv-viewport').style.width=1920*scale+'px';$('.tv-viewport').style.height=1080*scale+'px';
  }
  F.init($('#tv'));
  $('#tv').addEventListener('click',event=>{const button=event.target.closest('[data-action]');if(button && !button.disabled){event.preventDefault();act(button);}});
  $('#tv').addEventListener('input',event=>{if(event.target.id==='keyboard-input'&&modal)modal.data.value=event.target.value;});
  $('#tv').addEventListener('focusout',event=>{
    if(modal?.type==='connect'&&event.target.matches('[data-field]')&&event.target.value.trim()) {
      const errors=validateConnection(snapshotForm(),false),key=event.target.dataset.field,error=$('#error-'+key);
      if(error){error.textContent=errors[key]||'';error.hidden=!errors[key];event.target.setAttribute('aria-invalid',String(!!errors[key]));}
    }
  });
  document.addEventListener('keydown',onKey);window.addEventListener('resize',resize);
  resize();applySettings();renderApp('continue');updateClock();setInterval(updateClock,30000);
  if(!storageAvailable)toast('当前环境未允许保存，设置将在本次打开期间保留');
})();
