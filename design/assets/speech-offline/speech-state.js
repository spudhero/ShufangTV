/* Pure state transitions for the synthetic prototype. No storage, network or audio. */
(function (root) {
  'use strict';
  const SAMPLE = '夜幕降临，书房亮起一盏灯。愿每一页文字，都有温暖的声音。';
  const PACK = Object.freeze({ model: '轻量中文普通话', license: 'Apache-2.0', downloadMiB: 30.1, installedMiB: 31.2, reserveMiB: 120 });
  const names = { offline: '离线中文', azure: 'Azure 网络语音', system: '系统语音' };
  function createState() {
    return {
      selected: 'offline', foreground: true,
      offline: { status: 'missing', progress: 0, validPackage: false, error: '' },
      azure: { status: 'unconfigured', region: '', voice: 'zh-CN-XiaoxiaoNeural', hasKey: false, consent: false, error: '' },
      system: { status: 'missing', servicePresent: false, error: '' },
      sample: { engine: null, phase: 'idle', sentence: 0 },
      reader: { paragraph: 0, sentence: 0, page: 1 },
      playback: { phase: 'idle', speed: 1, timer: '30', elapsed: 0, reason: '' }
    };
  }
  function ready(s, engine = s.selected) {
    if (engine === 'offline') return s.offline.status === 'ready' && s.offline.validPackage;
    if (engine === 'azure') return s.azure.status === 'ready' && s.azure.hasKey && s.azure.consent;
    return s.system.status === 'ready';
  }
  function statusLabel(s, engine = s.selected) {
    if (engine === 'offline') return ({ missing: '语音包未下载', downloading: `下载中 · ${Math.floor(s.offline.progress)}%`, installing: '校验与安装中', checking: '正在检查语音包', ready: '语音包就绪', failed: '语音包未就绪', interrupted: '上次准备未完成' })[s.offline.status];
    if (engine === 'azure') return ({ unconfigured: '尚未配置', configured: '已配置 · 待试听验证', testing: '正在试听验证', ready: '本次试听已通过', failed: '试听失败 · 可重试' })[s.azure.status];
    return ({ missing: '未检测到系统语音引擎', detecting: '正在检测服务', detected: '开启时检查中文支持', checking: '正在检查中文支持', ready: '本次中文检查已通过', failed: '中文语音尚不可用' })[s.system.status];
  }
  function stopForChange(s, engine) {
    if (s.selected === engine) { s.playback.phase = 'idle'; s.playback.reason = ''; }
    if (s.sample.engine === engine) s.sample = { engine: null, phase: 'idle', sentence: 0 };
  }
  function reduce(previous, event) {
    const s = JSON.parse(JSON.stringify(previous));
    switch (event.type) {
      case 'SELECT':
        if (!names[event.engine]) break;
        if (event.engine === 'azure' && (!s.azure.hasKey || !s.azure.region || !s.azure.consent)) break;
        s.selected = event.engine; s.playback.phase = 'idle'; s.sample.phase = 'idle'; break;
      case 'DOWNLOAD':
        if (['downloading', 'installing', 'checking'].includes(s.offline.status)) break;
        stopForChange(s, 'offline');
        s.offline = { status: 'downloading', progress: 0, validPackage: false, error: '' }; break;
      case 'PROGRESS':
        if (s.offline.status === 'downloading') s.offline.progress = Math.min(100, Math.max(s.offline.progress, event.value)); break;
      case 'DOWNLOADED':
        if (s.offline.status === 'downloading' && s.offline.progress === 100) s.offline.status = 'installing'; break;
      case 'INSTALLED':
        if (s.offline.status !== 'installing' && s.offline.status !== 'checking') break;
        s.offline.validPackage = event.verified === true;
        s.offline.status = event.verified === true ? 'ready' : 'failed';
        s.offline.error = event.verified === true ? '' : '语音包检查未通过，请重新下载。'; break;
      case 'PACKAGE_FAIL':
        stopForChange(s, 'offline'); s.offline.status = 'failed'; s.offline.validPackage = false; s.offline.error = event.message || '下载未完成，请检查网络后重试。'; break;
      case 'CANCEL_DOWNLOAD':
        if (s.offline.status === 'downloading') s.offline = { status: 'missing', progress: 0, validPackage: false, error: '' }; break;
      case 'DELETE_PACKAGE':
        stopForChange(s, 'offline'); s.offline = { status: 'missing', progress: 0, validPackage: false, error: '' }; break;
      case 'RESTART':
        s.playback.phase = 'idle'; s.sample = { engine: null, phase: 'idle', sentence: 0 }; s.foreground = true;
        if (s.offline.validPackage) s.offline.status = 'checking';
        else if (['downloading', 'installing', 'checking'].includes(s.offline.status)) {
          s.offline.status = 'interrupted'; s.offline.error = '上次准备未完成，语音包尚不可用。';
        }
        if (['ready', 'testing'].includes(s.azure.status)) s.azure.status = s.azure.hasKey ? 'configured' : 'unconfigured';
        s.system.status = s.system.servicePresent ? 'detected' : 'missing'; break;
      case 'AZURE_SAVE':
        if (!event.region || !event.voice || !event.hasKey) break;
        stopForChange(s, 'azure');
        s.azure = { status: 'configured', region: event.region, voice: event.voice, hasKey: true, consent: false, error: '' }; break;
      case 'AZURE_CONSENT': s.azure.consent = true; break;
      case 'AZURE_CLEAR':
        stopForChange(s, 'azure'); s.azure = { status: 'unconfigured', region: '', voice: 'zh-CN-XiaoxiaoNeural', hasKey: false, consent: false, error: '' }; break;
      case 'DETECT_SYSTEM': s.system.status = 'detecting'; s.system.error = ''; break;
      case 'SYSTEM_DETECTED':
        s.system.servicePresent = Boolean(event.present); s.system.status = event.present ? 'detected' : 'missing'; break;
      case 'SAMPLE_START': {
        const engine = event.engine;
        if (!s.foreground) break;
        if (engine === 'offline' && !ready(s, 'offline')) break;
        if (engine === 'azure' && (!s.azure.hasKey || !s.azure.region || !s.azure.consent)) break;
        if (engine === 'system' && !s.system.servicePresent) break;
        s.playback.phase = 'idle'; s.sample = { engine, phase: 'preparing', sentence: 0 };
        if (engine === 'azure') { s.azure.status = 'testing'; s.azure.error = ''; }
        if (engine === 'system') { s.system.status = 'checking'; s.system.error = ''; }
        break;
      }
      case 'SAMPLE_RESULT': {
        const engine = s.sample.engine;
        if (s.sample.phase !== 'preparing' || !s.foreground) break;
        if (engine === 'azure') { s.azure.status = event.success ? 'ready' : 'failed'; s.azure.error = event.success ? '' : '试听未完成，请检查地区、密钥或网络后重试。'; }
        if (engine === 'system') { s.system.status = event.success ? 'ready' : 'failed'; s.system.error = event.success ? '' : '当前引擎的中文支持未通过检查。'; }
        s.sample.phase = event.success ? 'playing' : 'failed'; break;
      }
      case 'SAMPLE_NEXT': if (s.sample.phase === 'playing') s.sample.sentence = 1; break;
      case 'SAMPLE_END': if (s.sample.phase === 'playing') s.sample.phase = 'done'; break;
      case 'SAMPLE_STOP':
        if (s.azure.status === 'testing') s.azure.status = 'configured';
        if (s.system.status === 'checking') s.system.status = 'detected';
        s.sample.phase = 'idle'; break;
      case 'PLAY_START':
        if (ready(s) && s.foreground) { s.sample.phase = 'idle'; s.playback.phase = 'preparing'; s.playback.reason = ''; } break;
      case 'PLAY_READY': if (s.playback.phase === 'preparing' && ready(s) && s.foreground) s.playback.phase = 'playing'; break;
      case 'PAUSE':
        if (['playing', 'preparing'].includes(s.playback.phase)) { s.playback.phase = 'paused'; s.playback.reason = event.reason || '已暂停'; } break;
      case 'PLAY_END': s.playback.phase = 'idle'; s.playback.elapsed = 0; s.playback.reason = ''; break;
      case 'MOVE_PARAGRAPH':
        s.reader.paragraph = Math.max(0, Math.min(2, s.reader.paragraph + event.delta)); s.reader.sentence = 0;
        if (s.playback.phase === 'playing') s.playback.phase = 'preparing'; break;
      case 'PLAY_TICK':
        if (s.playback.phase !== 'playing' || !s.foreground) break;
        s.playback.elapsed += event.seconds || 1;
        if (s.playback.timer !== 'chapter' && s.playback.elapsed >= Number(s.playback.timer) * 60) { s.playback.phase = 'idle'; s.playback.reason = '定时已结束'; }
        break;
      case 'NEXT_SENTENCE':
        if (s.playback.phase !== 'playing') break;
        if (s.reader.sentence === 0) s.reader.sentence = 1;
        else if (s.reader.paragraph < 2) { s.reader.paragraph += 1; s.reader.sentence = 0; }
        else { s.playback.phase = 'idle'; s.playback.reason = '本章已读完'; }
        break;
      case 'SPEED': if ([.75, 1, 1.25, 1.5, 2].includes(event.value)) s.playback.speed = event.value; break;
      case 'TIMER': if (['15', '30', '60', 'chapter'].includes(event.value)) { s.playback.timer = event.value; s.playback.elapsed = 0; } break;
      case 'BACKGROUND':
        s.foreground = false; if (['playing', 'preparing'].includes(s.playback.phase)) { s.playback.phase = 'paused'; s.playback.reason = '离开应用后已暂停'; }
        if (s.azure.status === 'testing') s.azure.status = 'configured';
        if (s.system.status === 'checking') s.system.status = 'detected';
        s.sample.phase = 'idle'; break;
      case 'FOREGROUND': s.foreground = true; break;
    }
    return s;
  }
  const api = { SAMPLE, PACK, names, createState, ready, statusLabel, reduce };
  if (typeof module === 'object' && module.exports) module.exports = api;
  else root.SpeechPrototypeCore = api;
})(typeof globalThis !== 'undefined' ? globalThis : this);
