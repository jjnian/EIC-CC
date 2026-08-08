import { createRouter, createWebHistory } from 'vue-router';
import { useWorkspaceStore } from './stores/workspace';
import AppLayout from './layouts/AppLayout.vue';
import WorkspacePicker from './views/WorkspacePicker.vue';
import DashboardView from './views/DashboardView.vue';
import GraphView from './views/GraphView.vue';
import ExperiencesView from './views/ExperiencesView.vue';
import ChatView from './views/ChatView.vue';
import DataSourcesView from './views/DataSourcesView.vue';
import SettingsView from './views/SettingsView.vue';
import AnalysisView from './views/AnalysisView.vue';

export const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/workspaces', name: 'workspaces', component: WorkspacePicker, meta: { title: '工作空间' } },
    {
      path: '/',
      component: AppLayout,
      children: [
        { path: '', name: 'dashboard', component: DashboardView, meta: { title: '工作台' } },
        { path: 'graph', name: 'graph', component: GraphView, meta: { title: '血缘图谱' } },
        { path: 'experiences', name: 'experiences', component: ExperiencesView, meta: { title: '经验库' } },
        { path: 'chat', name: 'chat', component: ChatView, meta: { title: 'AI 对话' } },
        { path: 'datasources', name: 'datasources', component: DataSourcesView, meta: { title: '数据源' } },
        { path: 'settings', name: 'settings', component: SettingsView, meta: { title: '设置' } },
        { path: 'analysis', name: 'analysis', component: AnalysisView, meta: { title: '分析中心' } },
      ],
    },
    { path: '/:pathMatch(.*)*', redirect: '/' },
  ],
});

router.beforeEach(async (to) => {
  const ws = useWorkspaceStore();
  if (!ws.ready) {
    try { await ws.init(); } catch { /* 后端不可达时仍展示选择页 */ }
  }
  if (!ws.currentId && to.name !== 'workspaces') {
    return { name: 'workspaces' };
  }
});
