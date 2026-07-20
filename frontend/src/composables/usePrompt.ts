// Lightweight prompt dialog used for rename flows.

export interface PromptOptions {
  title?: string;
  message: string;
  defaultValue?: string;
  placeholder?: string;
  confirmLabel?: string;
  cancelLabel?: string;
}

const STYLE_ID = 'eic-prompt-style';

const ensureStyle = () => {
  if (document.getElementById(STYLE_ID)) return;
  const style = document.createElement('style');
  style.id = STYLE_ID;
  style.textContent = `
.eic-prompt-backdrop {
  position: fixed; inset: 0; z-index: 9998;
  background: rgba(0,0,0,0.32);
  display: flex; align-items: center; justify-content: center;
  animation: eicPrFade 0.16s ease-out;
  font-family: inherit;
}
@keyframes eicPrFade { from { opacity: 0; } to { opacity: 1; } }
.eic-prompt-card {
  width: 420px; max-width: 92vw;
  background: #ffffff;
  border: 1px solid rgba(0,0,0,0.09);
  border-radius: 14px;
  box-shadow: var(--shadow-lg, 0 12px 32px rgba(0,0,0,0.12));
  padding: 20px;
  display: flex; flex-direction: column; gap: 14px;
  color: var(--text-main, #18181b);
}
.eic-prompt-title {
  font-size: 14px; font-weight: 600; letter-spacing: 0.3px;
}
.eic-prompt-msg {
  font-size: 13px; line-height: 1.55;
  color: var(--text-dim, #52525b);
  white-space: pre-line;
}
.eic-prompt-input {
  width: 100%;
  border: 1px solid rgba(0,0,0,0.09);
  border-radius: 8px;
  background: #ffffff;
  color: var(--text-main, #18181b);
  font: inherit;
  font-size: 13px;
  padding: 9px 10px;
  outline: none;
}
.eic-prompt-input:focus { border-color: #2563eb; box-shadow: 0 0 0 3px rgba(37,99,235,0.15); }
.eic-prompt-foot {
  display: flex; gap: 8px; justify-content: flex-end;
}
.eic-prompt-btn {
  padding: 7px 16px; border-radius: 8px; border: 1px solid transparent;
  font-size: 13px; font-weight: 600; cursor: pointer; font-family: inherit;
}
.eic-prompt-btn-cancel {
  background: #ffffff;
  color: var(--text-dim, #52525b);
  border-color: rgba(0,0,0,0.09);
}
.eic-prompt-btn-cancel:hover { background: rgba(0,0,0,0.045); }
.eic-prompt-btn-ok {
  background: #18181b; color: #fff;
}
.eic-prompt-btn-ok:hover { background: #000000; }
`;
  document.head.appendChild(style);
};

export const prompt = (opts: PromptOptions): Promise<string | null> => {
  if (typeof document === 'undefined') {
    return Promise.resolve(null);
  }
  ensureStyle();
  return new Promise<string | null>((resolve) => {
    const backdrop = document.createElement('div');
    backdrop.className = 'eic-prompt-backdrop';

    const card = document.createElement('div');
    card.className = 'eic-prompt-card';
    backdrop.appendChild(card);

    if (opts.title) {
      const title = document.createElement('div');
      title.className = 'eic-prompt-title';
      title.textContent = opts.title;
      card.appendChild(title);
    }

    const msg = document.createElement('div');
    msg.className = 'eic-prompt-msg';
    msg.textContent = opts.message;
    card.appendChild(msg);

    const input = document.createElement('input');
    input.className = 'eic-prompt-input';
    input.type = 'text';
    input.value = opts.defaultValue || '';
    input.placeholder = opts.placeholder || '';
    card.appendChild(input);

    const foot = document.createElement('div');
    foot.className = 'eic-prompt-foot';
    card.appendChild(foot);

    const cancelBtn = document.createElement('button');
    cancelBtn.type = 'button';
    cancelBtn.className = 'eic-prompt-btn eic-prompt-btn-cancel';
    cancelBtn.textContent = opts.cancelLabel || '取消';
    foot.appendChild(cancelBtn);

    const okBtn = document.createElement('button');
    okBtn.type = 'button';
    okBtn.className = 'eic-prompt-btn eic-prompt-btn-ok';
    okBtn.textContent = opts.confirmLabel || '确定';
    foot.appendChild(okBtn);

    let done = false;
    const finish = (value: string | null) => {
      if (done) return;
      done = true;
      document.removeEventListener('keydown', onKey, true);
      backdrop.remove();
      resolve(value);
    };

    const onKey = (ev: KeyboardEvent) => {
      if (ev.key === 'Escape') {
        ev.preventDefault();
        finish(null);
      } else if (ev.key === 'Enter') {
        ev.preventDefault();
        finish(input.value);
      }
    };

    cancelBtn.addEventListener('click', () => finish(null));
    okBtn.addEventListener('click', () => finish(input.value));
    backdrop.addEventListener('mousedown', (e) => {
      if (e.target === backdrop) finish(null);
    });
    document.addEventListener('keydown', onKey, true);

    document.body.appendChild(backdrop);
    setTimeout(() => {
      input.focus();
      input.select();
    }, 0);
  });
};
