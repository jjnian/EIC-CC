/**
 * 轻量 Markdown → HTML 渲染器（零依赖）。
 * 仅覆盖经验文档常用语法：标题、粗体/斜体、行内代码、围栏代码块、有序/无序列表、
 * 引用、分隔线、链接、段落与换行。先整体转义 HTML 再做替换，避免 XSS。
 *
 * 注：刻意保持简单，不追求完整 CommonMark 兼容；够用即可，复杂排版请直接看原文。
 */
function escapeHtml(s: string): string {
  return s
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;');
}

/** 行内元素：代码 → 链接 → 粗体 → 斜体。输入须已转义。 */
function renderInline(text: string): string {
  let s = text;
  s = s.replace(/`([^`]+)`/g, (_m, c) => `<code>${c}</code>`);
  s = s.replace(/\[([^\]]+)\]\(([^)\s]+)\)/g,
    (_m, label, href) => `<a href="${href}" target="_blank" rel="noopener noreferrer">${label}</a>`);
  s = s.replace(/\*\*([^*]+)\*\*/g, (_m, c) => `<strong>${c}</strong>`);
  s = s.replace(/(^|[^*])\*([^*]+)\*/g, (_m, pre, c) => `${pre}<em>${c}</em>`);
  return s;
}

export function renderMarkdown(md: string): string {
  if (!md) return '';
  const src = escapeHtml(md.replace(/\r\n/g, '\n'));
  const lines = src.split('\n');
  const html: string[] = [];

  let inCode = false;
  let codeBuf: string[] = [];
  let listType: 'ul' | 'ol' | null = null;
  let paraBuf: string[] = [];

  const flushPara = () => {
    if (paraBuf.length) {
      html.push(`<p>${renderInline(paraBuf.join('<br/>'))}</p>`);
      paraBuf = [];
    }
  };
  const closeList = () => {
    if (listType) { html.push(`</${listType}>`); listType = null; }
  };

  for (const raw of lines) {
    const line = raw;

    // 围栏代码块
    if (/^\s*```/.test(line)) {
      if (inCode) {
        html.push(`<pre><code>${codeBuf.join('\n')}</code></pre>`);
        codeBuf = []; inCode = false;
      } else {
        flushPara(); closeList(); inCode = true;
      }
      continue;
    }
    if (inCode) { codeBuf.push(line); continue; }

    // 空行：结束段落与列表
    if (!line.trim()) { flushPara(); closeList(); continue; }

    // 分隔线
    if (/^\s*(-{3,}|\*{3,}|_{3,})\s*$/.test(line)) {
      flushPara(); closeList(); html.push('<hr/>'); continue;
    }

    // 标题
    const h = line.match(/^(#{1,6})\s+(.*)$/);
    if (h) {
      flushPara(); closeList();
      const level = h[1].length;
      html.push(`<h${level}>${renderInline(h[2].trim())}</h${level}>`);
      continue;
    }

    // 引用
    const bq = line.match(/^\s*>\s?(.*)$/);
    if (bq) {
      flushPara(); closeList();
      html.push(`<blockquote>${renderInline(bq[1])}</blockquote>`);
      continue;
    }

    // 列表项
    const ul = line.match(/^\s*[-*+]\s+(.*)$/);
    const ol = line.match(/^\s*\d+\.\s+(.*)$/);
    if (ul || ol) {
      flushPara();
      const want: 'ul' | 'ol' = ul ? 'ul' : 'ol';
      if (listType && listType !== want) closeList();
      if (!listType) { listType = want; html.push(`<${want}>`); }
      html.push(`<li>${renderInline((ul ? ul[1] : ol![1]).trim())}</li>`);
      continue;
    }

    // 普通段落行（连续行合并为一段，行内换行）
    closeList();
    paraBuf.push(line.trim());
  }

  if (inCode) html.push(`<pre><code>${codeBuf.join('\n')}</code></pre>`);
  flushPara();
  closeList();
  return html.join('\n');
}

/** 新建 md 经验时可一键填入的骨架模板。 */
export const MD_TEMPLATE = `# 标题

> 一句话概述这条经验解决什么问题 / 适用场景。

## 背景

-

## 关键结论

1.
2.

## 细节 / 步骤

\`\`\`text
在此粘贴关键配置、SQL 或代码
\`\`\`

## 参考

- [链接标题](https://)
`;
