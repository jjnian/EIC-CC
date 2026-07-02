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

/**
 * 业务访谈提纲模板：按「对象→流程→规则→数据流向」四段引导访谈，
 * 录音转写/笔记按此结构沉淀，经验库建图的抽取质量显著更高。
 * 其中「数据从哪来到哪去」一段是跨系统同步血缘（ETL/接口）的唯一采集入口，务必问到。
 */
export const INTERVIEW_TEMPLATE = `# 业务访谈：〈业务域/系统名〉 —— 〈受访人/角色〉

> 访谈日期：　　受访人：　　岗位/角色：　　涉及系统：

## 一、业务对象（有哪些"东西"）

- 这个业务里最核心的对象是哪些？（如 客户、订单、合同…）
- 每个对象业务上怎么称呼？系统页面上叫什么？有没有别名/简称？
- 每个对象有哪些关键属性/字段？哪些字段口径容易被误解？

## 二、业务流程（对象经历了什么）

- 一个〈对象〉从产生到结束经过哪些环节？每一步谁操作、在哪个系统？
- 哪一步会产生/修改哪些数据？
- 有没有分支流程、退回流程、异常流程？触发条件是什么？

## 三、业务规则（受什么约束）

- 有哪些审批/校验/风控规则？规则的依据（制度文件/系统配置）在哪？
- 有没有"制度上是 A、实际操作是 B"的情况？为什么？
- 数值口径：哪些指标的计算方式和字面含义不一致？

## 四、数据从哪来、到哪去（跨系统血缘 —— 重点）

- 这个系统的数据是录入产生的，还是从别的系统同步来的？同步方式（定时任务/接口/人工导入）和频率？
- 这里的数据会流向哪些下游系统/报表？谁在消费？
- 有没有"两边都有这份数据但经常对不上"的表/字段？以哪边为准？
- 历史遗留：有没有已停用但数据还在被引用的旧系统/旧表？

## 附：待确认清单

- [ ]
`;
