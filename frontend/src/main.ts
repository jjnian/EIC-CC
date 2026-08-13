import { createApp } from 'vue';
import { createPinia } from 'pinia';
import App from './app/App.vue';
import { router } from './app/router';
import { initTheme } from './app/lib/theme';
import './app/styles/main.css';

// 挂载前应用主题偏好，避免首屏闪烁
initTheme();

createApp(App).use(createPinia()).use(router).mount('#app');
