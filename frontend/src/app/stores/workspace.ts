import { defineStore } from 'pinia';
import {
  listWorkspaces,
  createWorkspace as apiCreateWorkspace,
  deleteWorkspace as apiDeleteWorkspace,
  type Workspace,
} from '../../api/workspaces';
import { getCurrentWorkspaceId, setCurrentWorkspaceId } from '../../api/http';

export const useWorkspaceStore = defineStore('workspace', {
  state: () => ({
    list: [] as Workspace[],
    currentId: '',
    ready: false,
    loading: false,
  }),
  getters: {
    current: (s): Workspace | null => s.list.find((w) => w.id === s.currentId) || null,
  },
  actions: {
    async init() {
      this.loading = true;
      try {
        this.list = await listWorkspaces();
        const stored = getCurrentWorkspaceId();
        const hit = this.list.find((w) => w.id === stored);
        if (hit) {
          this.currentId = hit.id;
        } else {
          setCurrentWorkspaceId('');
          this.currentId = '';
        }
      } finally {
        this.ready = true;
        this.loading = false;
      }
    },
    async refresh() {
      this.list = await listWorkspaces();
    },
    select(id: string) {
      this.currentId = id;
      setCurrentWorkspaceId(id);
    },
    async create(name: string, description?: string) {
      const ws = await apiCreateWorkspace({ name, description });
      await this.refresh();
      this.select(ws.id);
      return ws;
    },
    async remove(id: string) {
      await apiDeleteWorkspace(id);
      if (this.currentId === id) {
        this.currentId = '';
        setCurrentWorkspaceId('');
      }
      await this.refresh();
    },
  },
});
