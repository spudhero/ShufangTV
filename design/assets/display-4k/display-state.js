/* Display intent and observed reading output are deliberately independent. */
(function (root, factory) {
  const api = factory();
  if (typeof module === 'object' && module.exports) module.exports = api;
  else root.Display4K = api;
})(typeof globalThis === 'object' ? globalThis : this, function () {
  'use strict';
  const DEVICES = Object.freeze({
    sony: { name: 'Sony Android 10', panel: [3840, 2160], system: [1920, 1080], density: 2 },
    native: { name: '原生 4K 系统样例', panel: [3840, 2160], system: [3840, 2160], density: 4 },
    fullhd: { name: '1080p 面板样例', panel: [1920, 1080], system: [1920, 1080], density: 2 },
    unknown: { name: '面板信息待确认样例', panel: null, system: [1920, 1080], density: 2 }
  });
  const PROFILES = Object.freeze({
    '1080': { width: 1920, height: 1080, density: 2, dpWidth: 960, dpHeight: 540 },
    '4k': { width: 3840, height: 2160, density: 4, dpWidth: 960, dpHeight: 540 },
    '720': { width: 1280, height: 720, density: 2, dpWidth: 640, dpHeight: 360 }
  });
  const copy = value => JSON.parse(JSON.stringify(value));
  const is4K = size => Boolean(size && size[0] === 3840 && size[1] === 2160);
  const dimensions = size => size ? `${size[0]} × ${size[1]}` : '尚未确认';
  function createState(device = 'sony') {
    return { device: DEVICES[device] ? device : 'sony', policy: 'auto', entered: false,
      phase: 'idle', requestId: 0, target: null, actual: null, path: null,
      confirmed: false, reason: '' };
  }
  function targetFor(state) {
    const d = DEVICES[state.device];
    return state.policy === 'auto' && is4K(d.panel) && !is4K(d.system)
      ? { size: [3840, 2160], path: 'independent' }
      : { size: [...d.system], path: 'system' };
  }
  function begin(state) {
    const next = { ...state, requestId: state.requestId + 1, phase: 'checking',
      target: targetFor(state), actual: null, path: null, confirmed: false, reason: '' };
    return next;
  }
  function fallback(state, reason) {
    return { ...state, requestId: state.requestId + 1, phase: 'recovering',
      target: { size: [...DEVICES[state.device].system], path: 'system' },
      actual: null, path: null, confirmed: false, reason: reason || '未能确认高清阅读' };
  }
  function reduce(previous, event) {
    let s = copy(previous);
    switch (event.type) {
      case 'SELECT_POLICY':
        if (!['auto', 'compat'].includes(event.value) || event.value === s.policy) return s;
        s.policy = event.value;
        return s.entered ? begin(s) : { ...s, phase: 'idle', reason: '' };
      case 'ENTER': return s.entered ? s : begin({ ...s, entered: true });
      case 'LEAVE': return { ...s, entered: false, phase: 'idle', requestId: s.requestId + 1,
        target: null, actual: null, path: null, confirmed: false, reason: '' };
      case 'RETRY': return s.entered ? begin(s) : s;
      case 'SURFACE_LOST': return s.entered ? begin(s) : s;
      case 'FAILED':
        if (!s.entered || s.phase !== 'checking' || event.requestId !== s.requestId) return s;
        return s.target.path === 'independent' ? fallback(s, event.reason)
          : { ...s, phase: 'unavailable', actual: null, confirmed: false, reason: '原有阅读画面暂未恢复' };
      case 'OBSERVED': {
        if (!s.entered || !['checking', 'recovering'].includes(s.phase) || event.requestId !== s.requestId) return s;
        const matches = event.width === s.target.size[0] && event.height === s.target.size[1]
          && event.path === s.target.path && event.frameDrawn === true;
        if (!matches) return s.phase === 'checking' && s.target.path === 'independent'
          ? fallback(s, '未能确认高清阅读')
          : { ...s, phase: 'unavailable', actual: null, confirmed: false, reason: '原有阅读画面暂未恢复' };
        s.actual = [event.width, event.height]; s.path = event.path; s.confirmed = true;
        s.phase = s.phase === 'recovering' ? 'fallback' : s.path === 'independent' ? 'hd' : 'system';
        return s;
      }
      default: return s;
    }
  }
  function summary(s) {
    if (!s.entered) return '进入书籍后确认';
    if (s.confirmed && s.path === 'independent' && is4K(s.actual)) return '高清阅读 4K';
    if (s.confirmed) return '系统清晰度';
    return s.phase === 'unavailable' ? '显示暂不可用' : '清晰度确认中';
  }
  function status(s, fromReader = false) {
    const d = DEVICES[s.device];
    if (!s.entered) return { title: fromReader ? '返回阅读后确认' : '进入书籍后确认',
      description: s.policy === 'auto'
        ? '打开书籍后自动选择阅读清晰度，确认完成后显示实际效果。'
        : '已选择兼容模式，打开书籍后使用原有阅读方式。', tone: 'neutral' };
    if (s.phase === 'checking') return { title: s.policy === 'compat' ? '正在确认兼容阅读' : '正在确认阅读清晰度',
      description: '正文位置已保留，确认完成后更新当前状态。此时尚未确认高清阅读。', tone: 'pending' };
    if (s.phase === 'recovering') return { title: '正在恢复原有阅读',
      description: '这次未能启用高清阅读，正在恢复系统清晰度。', tone: 'pending' };
    if (s.phase === 'unavailable') return { title: '阅读显示暂不可用',
      description: '正文位置已保留。请重试兼容阅读，或返回设置。', tone: 'error' };
    if (s.phase === 'hd') return { title: '高清阅读 4K',
      description: '当前阅读已使用高清画布。书架、设置和阅读工具仍使用系统清晰度。', tone: 'ready' };
    if (s.phase === 'fallback') return { title: '已回退到系统清晰度',
      description: '这次未能启用高清阅读，已恢复原有阅读。仍保留自动选择，可稍后再次尝试。', tone: 'neutral' };
    return { title: '系统清晰度', description: s.policy === 'compat'
      ? '兼容模式已生效，当前使用原有阅读方式。'
      : is4K(d.system) ? '当前系统界面与阅读画布均为 4K，无需单独启用高清阅读。'
      : d.panel ? '当前使用系统清晰度，适合这台电视的显示能力。'
      : '面板信息尚未确认，当前继续使用系统清晰度。', tone: 'ready' };
  }
  return { DEVICES, PROFILES, createState, reduce, targetFor, summary, status, dimensions, is4K };
});
