/** 节点类型 / 来源的统一视觉编码（图谱、抽屉、分析页共用）。 */

export interface TypeStyle { color: string; bg: string; label: string }

const TYPE_STYLES: Record<string, TypeStyle> = {
  class:     { color: '#4f46e5', bg: '#eef2ff', label: '对象' },
  entity:    { color: '#4f46e5', bg: '#eef2ff', label: '实体' },
  process:   { color: '#059669', bg: '#ecfdf5', label: '流程' },
  event:     { color: '#d97706', bg: '#fffbeb', label: '事件' },
  rule:      { color: '#db2777', bg: '#fdf2f8', label: '规则' },
  metric:    { color: '#7c3aed', bg: '#f5f3ff', label: '指标' },
  attribute: { color: '#0891b2', bg: '#ecfeff', label: '属性' },
  constraint:{ color: '#e11d48', bg: '#fff1f2', label: '约束' },
};

const FALLBACK: TypeStyle = { color: '#64748b', bg: '#f1f5f9', label: '节点' };

export function typeStyle(type?: string): TypeStyle {
  return TYPE_STYLES[type || ''] || FALLBACK;
}

export function typeLabel(type?: string): string {
  return (TYPE_STYLES[type || ''] || FALLBACK).label;
}

const SOURCE_META: Record<string, { label: string; cls: string }> = {
  derived:  { label: '派生', cls: 'bg-emerald-50 text-emerald-700' },
  inferred: { label: '推断', cls: 'bg-amber-50 text-amber-700' },
  manual:   { label: '人工', cls: 'bg-sky-50 text-sky-700' },
  preset:   { label: '预置', cls: 'bg-slate-100 text-slate-600' },
};

export function sourceMeta(source?: string): { label: string; cls: string } {
  return SOURCE_META[source || ''] || { label: source || '未知', cls: 'bg-slate-100 text-slate-500' };
}

const ORIGIN_META: Record<string, { label: string; cls: string }> = {
  manual:    { label: '手写', cls: 'bg-slate-100 text-slate-600' },
  upload:    { label: '上传', cls: 'bg-sky-50 text-sky-700' },
  ddl:       { label: '库结构', cls: 'bg-indigo-50 text-indigo-700' },
  websystem: { label: 'Web 系统', cls: 'bg-violet-50 text-violet-700' },
  explore:   { label: '系统探索', cls: 'bg-emerald-50 text-emerald-700' },
  chat:      { label: '对话', cls: 'bg-amber-50 text-amber-700' },
  websearch: { label: '联网调研', cls: 'bg-cyan-50 text-cyan-700' },
  datasource:{ label: '数据源', cls: 'bg-indigo-50 text-indigo-700' },
};

export function originMeta(origin?: string): { label: string; cls: string } {
  return ORIGIN_META[origin || ''] || { label: origin || '经验', cls: 'bg-slate-100 text-slate-500' };
}
