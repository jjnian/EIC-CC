// Promise-based confirm dialog (replaces window.confirm).
//
//   if (await confirm({ message: '确定删除？', danger: true })) { ... }

export interface ConfirmOptions {
  title?: string;
  message: string;
  confirmLabel?: string;
  cancelLabel?: string;
  danger?: boolean;
}

const STYLE_ID = 'eic-confirm-style';

const ensureStyle = () => {
  if (document.getElementById(STYLE_ID)) return;
  const style = document.createElement('style');
  style.id = STYLE_ID;
  style.textContent = `
.eic-confirm-backdrop {
  position: fixed; inset: 0; z-index: 9998;
  background: rgba(0,0,0,0.32);
  display: flex; align-items: center; justify-content: center;
  animation: eicCfFade 0.16s ease-out;
  font-family: inherit;
}
@keyframes eicCfFade { from { opacity: 0; } to { opacity: 1; } }
.eic-confirm-card {
  width: 380px; max-width: 92vw;
  background: #ffffff;
  border: 1px solid rgba(0,0,0,0.09);
  border-radius: 14px;
  box-shadow: var(--shadow-lg, 0 12px 32px rgba(0,0,0,0.12));
  padding: 20px;
  display: flex; flex-direction: column; gap: 14px;
  color: var(--text-main, #18181b);
}
.eic-confirm-title {
  font-size: 14px; font-weight: 600; letter-spacing: 0.3px;
}
.eic-confirm-msg {
  font-size: 13px; line-height: 1.55;
  color: var(--text-dim, #52525b);
  white-space: pre-line;
}
.eic-confirm-foot {
  display: flex; gap: 8px; justify-content: flex-end;
}
.eic-confirm-btn {
  padding: 7px 16px; border-radius: 8px; border: 1px solid transparent;
  font-size: 13px; font-weight: 600; cursor: pointer; font-family: inherit;
}
.eic-confirm-btn-cancel {
  background: #ffffff;
  color: var(--text-dim, #52525b);
  border-color: rgba(0,0,0,0.09);
}
.eic-confirm-btn-cancel:hover { background: rgba(0,0,0,0.045); }
.eic-confirm-btn-ok {
  background: #18181b; color: #fff;
}
.eic-confirm-btn-ok:hover { background: #000000; }
.eic-confirm-btn-danger {
  background: #dc2626; color: #fff;
}
.eic-confirm-btn-danger:hover { background: #b91c1c; }
`;
  document.head.appendChild(style);
};

export const confirm = (opts: ConfirmOptions): Promise<boolean> => {
  if (typeof document === 'undefined') {
    return Promise.resolve(false);
  }
  ensureStyle();
  return new Promise<boolean>((resolve) => {
    const backdrop = document.createElement('div');
    backdrop.className = 'eic-confirm-backdrop';

    const card = document.createElement('div');
    card.className = 'eic-confirm-card';
    backdrop.appendChild(card);

    if (opts.title) {
      const title = document.createElement('div');
      title.className = 'eic-confirm-title';
      title.textContent = opts.title;
      card.appendChild(title);
    }

    const msg = document.createElement('div');
    msg.className = 'eic-confirm-msg';
    msg.textContent = opts.message;
    card.appendChild(msg);

    const foot = document.createElement('div');
    foot.className = 'eic-confirm-foot';
    card.appendChild(foot);

    const cancelBtn = document.createElement('button');
    cancelBtn.type = 'button';
    cancelBtn.className = 'eic-confirm-btn eic-confirm-btn-cancel';
    cancelBtn.textContent = opts.cancelLabel || '取消';
    foot.appendChild(cancelBtn);

    const okBtn = document.createElement('button');
    okBtn.type = 'button';
    okBtn.className = 'eic-confirm-btn ' + (opts.danger ? 'eic-confirm-btn-danger' : 'eic-confirm-btn-ok');
    okBtn.textContent = opts.confirmLabel || '确认';
    foot.appendChild(okBtn);

    let done = false;
    const finish = (ok: boolean) => {
      if (done) return;
      done = true;
      document.removeEventListener('keydown', onKey, true);
      backdrop.remove();
      resolve(ok);
    };

    const onKey = (ev: KeyboardEvent) => {
      if (ev.key === 'Escape') { ev.preventDefault(); finish(false); }
      else if (ev.key === 'Enter') { ev.preventDefault(); finish(true); }
    };

    cancelBtn.addEventListener('click', () => finish(false));
    okBtn.addEventListener('click', () => finish(true));
    backdrop.addEventListener('mousedown', (e) => {
      if (e.target === backdrop) finish(false);
    });
    document.addEventListener('keydown', onKey, true);

    document.body.appendChild(backdrop);
    // Focus on the confirm button for keyboard-first UX.
    setTimeout(() => okBtn.focus(), 0);
  });
};
