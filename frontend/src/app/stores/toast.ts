import { defineStore } from 'pinia';

export type ToastKind = 'success' | 'error' | 'info';

export interface ToastItem {
  id: number;
  kind: ToastKind;
  text: string;
}

let seq = 0;

export const useToastStore = defineStore('toast', {
  state: () => ({ items: [] as ToastItem[] }),
  actions: {
    push(kind: ToastKind, text: string) {
      const id = ++seq;
      this.items.push({ id, kind, text });
      setTimeout(() => {
        this.items = this.items.filter((t) => t.id !== id);
      }, 3600);
    },
    success(text: string) { this.push('success', text); },
    error(text: string) { this.push('error', text); },
    info(text: string) { this.push('info', text); },
  },
});
