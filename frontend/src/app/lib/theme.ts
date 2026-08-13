/** 亮/暗主题切换（对齐原型 index.html 的 toggleTheme）。 */
const KEY = 'eic-theme';

export function isDark(): boolean {
  return localStorage.getItem(KEY) === 'dark';
}

/** 应用启动时按本地偏好设置主题类。 */
export function initTheme() {
  document.documentElement.classList.toggle('theme-dark', isDark());
}

/** 切换主题并记忆偏好。 */
export function toggleTheme() {
  const dark = !isDark();
  localStorage.setItem(KEY, dark ? 'dark' : 'light');
  document.documentElement.classList.toggle('theme-dark', dark);
}
