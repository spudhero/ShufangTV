/* One directional navigator; an explicit scope for each modal and reading menu. */
window.TVFocus = (() => {
  let root = null;
  let scope = null;
  const selector = 'button:not([disabled]), input:not([disabled]), [tabindex="0"]';
  const visible = el => !el.closest('[hidden],[inert]') && el.getBoundingClientRect().width > 0;
  function controls() { return [...(scope || root).querySelectorAll(selector)].filter(visible); }
  function focus(el) {
    if (!el || !visible(el)) return false;
    root.querySelectorAll('.tv-focus').forEach(node => node.classList.remove('tv-focus'));
    el.focus({preventScroll:true});
    el.classList.add('tv-focus');
    const scroller = el.closest('.od-scroll,.library-grid,.bookmarks-list,.dialog-content');
    if (scroller) {
      const a=el.getBoundingClientRect(), b=scroller.getBoundingClientRect();
      const scale=root.getBoundingClientRect().width/1920;
      if (a.bottom>b.bottom-12*scale) scroller.scrollTop += (a.bottom-b.bottom)/scale+20;
      if (a.top<b.top+12*scale) scroller.scrollTop += (a.top-b.top)/scale-20;
    }
    return true;
  }
  function setScope(next, first) { scope=next; if(first) focus(first); }
  function move(direction) {
    const items=controls();
    const from=document.activeElement;
    if(!items.includes(from)) return focus(items[0]);
    const a=from.getBoundingClientRect();
    const horizontal=direction==='left'||direction==='right';
    const sign=direction==='left'||direction==='up'?-1:1;
    const ac=horizontal?(a.left+a.right)/2:(a.top+a.bottom)/2;
    const ap=horizontal?(a.top+a.bottom)/2:(a.left+a.right)/2;
    const candidates=items.filter(el=>el!==from).map(el=>{
      const b=el.getBoundingClientRect();
      const bc=horizontal?(b.left+b.right)/2:(b.top+b.bottom)/2;
      const bp=horizontal?(b.top+b.bottom)/2:(b.left+b.right)/2;
      const advance=(bc-ac)*sign;
      const overlap=horizontal?Math.min(a.bottom,b.bottom)-Math.max(a.top,b.top):Math.min(a.right,b.right)-Math.max(a.left,b.left);
      return {el,advance,score:advance+Math.abs(bp-ap)*2.6+(overlap>0?0:700)};
    }).filter(c=>c.advance>4).sort((a,b)=>a.score-b.score);
    if(candidates[0]) return focus(candidates[0].el);
    return false;
  }
  function tab(reverse) {
    const items=controls(), i=items.indexOf(document.activeElement);
    if(items.length) focus(items[(i+(reverse?-1:1)+items.length)%items.length]);
  }
  function init(node) {
    root=node;
    root.addEventListener('focusin',event=>{
      root.querySelectorAll('.tv-focus').forEach(el=>el.classList.remove('tv-focus'));
      if(event.target.matches(selector)) event.target.classList.add('tv-focus');
    });
    root.addEventListener('pointerdown',event=>{
      const target=event.target.closest(selector);
      if(target && (!scope || scope.contains(target))) focus(target);
    });
  }
  return {init,focus,setScope,move,tab,controls};
})();
