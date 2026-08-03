/**
 * 工作空间色彩方案 —— 每个工作空间根据其在列表中的索引自动分配一套色彩。
 * 与原型 index.html 中的 WS_COLORS 保持一致。
 */
export interface WsColor {
  icon: string
  bg: string
  lightBg: string
  color: string
}

const COLORS: WsColor[] = [
  { icon: 'layers',       bg: 'linear-gradient(135deg, #6366F1, #8B5CF6)', lightBg: 'rgba(99,102,241,.1)',  color: '#6366F1' },
  { icon: 'megaphone',    bg: 'linear-gradient(135deg, #8B5CF6, #D946EF)', lightBg: 'rgba(139,92,246,.1)',  color: '#8B5CF6' },
  { icon: 'users',        bg: 'linear-gradient(135deg, #0EA5E9, #38BDF8)', lightBg: 'rgba(14,165,233,.1)',  color: '#0EA5E9' },
  { icon: 'shield-check', bg: 'linear-gradient(135deg, #10B981, #34D399)', lightBg: 'rgba(16,185,129,.1)',  color: '#10B981' },
  { icon: 'building-2',   bg: 'linear-gradient(135deg, #F59E0B, #FBBF24)', lightBg: 'rgba(245,158,11,.1)',  color: '#F59E0B' },
  { icon: 'globe',        bg: 'linear-gradient(135deg, #EC4899, #F472B6)', lightBg: 'rgba(236,72,153,.1)',  color: '#EC4899' },
]

/** 根据索引取色彩（循环取模，保证每个工作空间都有颜色） */
export function wsColorForIndex(idx: number): WsColor {
  return COLORS[idx % COLORS.length]
}
