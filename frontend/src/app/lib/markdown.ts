/**
 * 零依赖 Markdown → HTML 渲染（对话消息 / 经验预览用）。
 * 安全策略：整体先转义 HTML，再套用有限的 Markdown 语法；
 * 链接仅放行 http/https/mailto，其余协议一律按纯文本处理。
 */

function escapeHtml(s: string): string {
  return s
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;');
}

function renderInline(s: string): string {
  let out = escapeHtml(s);
  out = out.replace(/`([^`]+)`/g, '<code class="md-code">$1</code>');
  out = out.replace(/\*\*([^*]+)\*\*/g, '<strong>$1</strong>');
  out = out.replace(/\[([^\]]+)\]\(([^)\s]+)\)/g, (_m, text: string, url: string) => {
    const safe = /^(https?:|mailto:)/i.test(url) ? url : '#';
    return `<a class="md-link" href="${safe}" target="_blank" rel="noopener noreferrer">${text}</a>`;
  });
  return out;
}

export function renderMarkdown(src: string): string {
  const lines = String(src || '').replace(/\r\n?/g, '\n').split('\n');
  const html: string[] = [];
  let inCode = false;
  let codeBuf: string[] = [];
  let listBuf: string[] = [];

  const flushList = () => {
    if (listBuf.length) {
      html.push(`<ul class="md-ul">${listBuf.map((li) => `<li>${renderInline(li)}</li>`).join('')}</ul>`);
      listBuf = [];
    }
  };
  const flushCode = () => {
    html.push(`<pre class="md-pre"><code>${escapeHtml(codeBuf.join('\n'))}</code></pre>`);
    codeBuf = [];
  };

  for (const line of lines) {
    if (/^\s*```/.test(line)) {
      if (inCode) { flushCode(); inCode = false; }
      else { flushList(); inCode = true; codeBuf = []; }
      continue;
    }
    if (inCode) { codeBuf.push(line); continue; }

    const h = line.match(/^(#{1,4})\s+(.*)$/);
    if (h) {
      flushList();
      const level = h[1].length;
      html.push(`<div class="md-h md-h${level}">${renderInline(h[2])}</div>`);
      continue;
    }
    const li = line.match(/^\s*[-*]\s+(.*)$/);
    if (li) { listBuf.push(li[1]); continue; }

    flushList();
    if (line.trim() === '') { html.push(''); continue; }
    html.push(`<p class="md-p">${renderInline(line)}</p>`);
  }
  if (inCode) flushCode();
  flushList();
  return html.join('\n');
}
