import { computed, ref } from 'vue';

/**
 * 拖拽分隔条管理:暴露 chatW、isDragging、startDivider(mousedown 处理器)。
 * onResize 在拖拽过程中持续触发,可用于在父级重排画布。
 */
export function useDivider(initial = 360, onResize?: () => void) {
  const chatW = ref(initial);
  const divDrag = ref<{ sx: number; sw: number } | null>(null);
  const isDragging = computed(() => divDrag.value !== null);

  const startDivider = (e: MouseEvent) => {
    e.preventDefault();
    divDrag.value = { sx: e.clientX, sw: chatW.value };
    const mv = (ev: MouseEvent) => {
      if (!divDrag.value) return;
      const delta = divDrag.value.sx - ev.clientX;
      chatW.value = Math.max(0, Math.min(window.innerWidth - 72, divDrag.value.sw + delta));
      onResize?.();
    };
    const up = () => {
      divDrag.value = null;
      document.removeEventListener('mousemove', mv);
      document.removeEventListener('mouseup', up);
    };
    document.addEventListener('mousemove', mv);
    document.addEventListener('mouseup', up);
  };

  return { chatW, isDragging, startDivider };
}
