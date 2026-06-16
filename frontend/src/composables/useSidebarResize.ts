import { ref } from 'vue';

/**
 * 左侧栏的宽度拖拽与展开状态。
 * <p>从 App.vue 抽出：拖动分隔条改宽度（限定在最小宽与"留出右侧间隙"之间），展开/收起切换。
 * 纯 UI 状态，无业务依赖。
 */
const SIDEBAR_MIN_W = 72;
const SIDEBAR_EDGE_GAP = 72;

export function useSidebarResize(initialWidth = 240) {
  const sbExp = ref(true);
  const sidebarW = ref(initialWidth);
  const sbDragging = ref(false);

  const startSbResize = (e: MouseEvent) => {
    e.preventDefault();
    const startX = e.clientX;
    const startW = sidebarW.value;
    sbDragging.value = true;
    const onMove = (ev: MouseEvent) => {
      const nextW = startW + ev.clientX - startX;
      const maxW = Math.max(SIDEBAR_MIN_W, window.innerWidth - SIDEBAR_EDGE_GAP);
      sidebarW.value = Math.max(SIDEBAR_MIN_W, Math.min(maxW, nextW));
    };
    const onUp = () => {
      sbDragging.value = false;
      document.removeEventListener('mousemove', onMove);
      document.removeEventListener('mouseup', onUp);
    };
    document.addEventListener('mousemove', onMove);
    document.addEventListener('mouseup', onUp);
  };

  return { sbExp, sidebarW, sbDragging, startSbResize };
}
