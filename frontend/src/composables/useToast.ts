// Lightweight toast singleton. Renders into a fixed container appended to <body>.
//
// Usage:
//   import { toast, mountToastRoot } from './composables/useToast';
//   onMounted(() => mountToastRoot());
//   toast.success('已保存');

type ToastKind = 'success' | 'info' | 'warn' | 'error';

interface ToastItem {
  id: number;
  kind: ToastKind;
  msg: string;
  timer: number;
}

const STYLE_ID = 'eic-toast-style';
const ROOT_ID = 'eic-toast-root';

const KIND_STYLE: Record<ToastKind, { bg: string; fg: string; border: string; icon: string }> = {
  success: { bg: 'rgba(66,184,131,0.16)', fg: '#5cc99a', border: 'rgba(66,184,131,0.45)', icon: '✓' },
  info:    { bg: 'rgba(99,179,237,0.16)', fg: '#63b3ed', border: 'rgba(99,179,237,0.45)', icon: 'ⓘ' },
  warn:    { bg: 'rgba(251,191,36,0.16)', fg: '#fbbf24', border: 'rgba(251,191,36,0.45)', icon: '⚠' },
  error:   { bg: 'rgba(255,99,99,0.16)',  fg: '#ff8a8a', border: 'rgba(255,99,99,0.45)',  icon: '✕' },
};

let mounted = false;
let seq = 0;
const items = new Map<number, ToastItem>();

const ensureStyle = () => {
  if (document.getElementById(STYLE_ID)) return;
  const style = document.createElement('style');
  style.id = STYLE_ID;
  style.textContent = `
#${ROOT_ID} {
  position: fixed; top: 18px; right: 18px; z-index: 9999;
  display: flex; flex-direction: column; gap: 8px;
  pointer-events: none; font-family: inherit;
}
.eic-toast {
  pointer-events: auto;
  min-width: 220px; max-width: 360px;
  display: flex; align-items: center; gap: 8px;
  padding: 9px 14px;
  border-radius: 10px;
  font-size: 13px;
  line-height: 1.4;
  backdrop-filter: blur(12px);
  box-shadow: 0 8px 24px rgba(0,0,0,0.35);
  animation: eicToastIn 0.18s ease-out;
  border: 1px solid;
}
.eic-toast-icon {
  flex-shrink: 0; font-weight: 700; font-family: 'JetBrains Mono', monospace;
  width: 16px; text-align: center;
}
.eic-toast-msg { flex: 1; white-space: pre-line; word-break: break-word; }
.eic-toast-x {
  background: none; border: none; color: inherit; cursor: pointer; opacity: 0.6;
  padding: 0 2px; font-size: 14px; line-height: 1;
}
.eic-toast-x:hover { opacity: 1; }
.eic-toast.leave { animation: eicToastOut 0.18s ease-in forwards; }
@keyframes eicToastIn {
  from { opacity: 0; transform: translateY(-6px); }
  to   { opacity: 1; transform: translateY(0); }
}
@keyframes eicToastOut {
  from { opacity: 1; transform: translateY(0); }
  to   { opacity: 0; transform: translateY(-6px); }
}
`;
  document.head.appendChild(style);
};

const ensureRoot = (): HTMLElement => {
  let root = document.getElementById(ROOT_ID);
  if (!root) {
    root = document.createElement('div');
    root.id = ROOT_ID;
    document.body.appendChild(root);
  }
  return root;
};

export const mountToastRoot = () => {
  if (mounted) return;
  if (typeof document === 'undefined') return;
  ensureStyle();
  ensureRoot();
  mounted = true;
};

const dismiss = (id: number) => {
  const it = items.get(id);
  if (!it) return;
  clearTimeout(it.timer);
  items.delete(id);
  const el = document.getElementById('eic-toast-' + id);
  if (el) {
    el.classList.add('leave');
    setTimeout(() => el.remove(), 180);
  }
};

const push = (kind: ToastKind, msg: string) => {
  if (typeof document === 'undefined') {
    // SSR / non-DOM fallback
    // eslint-disable-next-line no-console
    console.log('[toast:' + kind + ']', msg);
    return;
  }
  ensureStyle();
  const root = ensureRoot();
  const id = ++seq;
  const style = KIND_STYLE[kind];
  const node = document.createElement('div');
  node.id = 'eic-toast-' + id;
  node.className = 'eic-toast';
  node.style.background = style.bg;
  node.style.color = style.fg;
  node.style.borderColor = style.border;
  node.innerHTML = '';
  const icon = document.createElement('span');
  icon.className = 'eic-toast-icon';
  icon.textContent = style.icon;
  const body = document.createElement('span');
  body.className = 'eic-toast-msg';
  body.textContent = msg;
  const closeBtn = document.createElement('button');
  closeBtn.type = 'button';
  closeBtn.className = 'eic-toast-x';
  closeBtn.textContent = '×';
  closeBtn.addEventListener('click', () => dismiss(id));
  node.appendChild(icon);
  node.appendChild(body);
  node.appendChild(closeBtn);
  root.appendChild(node);
  const timer = window.setTimeout(() => dismiss(id), 3500);
  items.set(id, { id, kind, msg, timer });
};

export const useToast = () => ({
  success: (msg: string) => push('success', msg),
  info:    (msg: string) => push('info', msg),
  warn:    (msg: string) => push('warn', msg),
  error:   (msg: string) => push('error', msg),
});

export const toast = useToast();
