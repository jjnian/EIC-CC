import { ref } from 'vue';
import {
  listWorkspaces,
  createWorkspace as apiCreate,
  updateWorkspace as apiUpdate,
  deleteWorkspace as apiDelete,
  type Workspace,
} from '../api/workspaces';
import {
  getCurrentWorkspaceId,
  setCurrentWorkspaceId,
} from '../api/http';

const workspaces = ref<Workspace[]>([]);
const currentId = ref<string>(getCurrentWorkspaceId());
const loading = ref(false);

export function useWorkspaces() {
  const reload = async () => {
    loading.value = true;
    try {
      workspaces.value = await listWorkspaces();
    } finally {
      loading.value = false;
    }
  };

  const current = (): Workspace | undefined =>
    workspaces.value.find(w => w.id === currentId.value);

  const setCurrent = (id: string) => {
    currentId.value = id;
    setCurrentWorkspaceId(id);
  };

  const ensureValidCurrent = async (): Promise<boolean> => {
    if (workspaces.value.length === 0) await reload();
    if (workspaces.value.length === 0) {
      currentId.value = '';
      setCurrentWorkspaceId('');
      return false;
    }
    const exists = currentId.value && workspaces.value.some(w => w.id === currentId.value);
    if (!exists) {
      const def = workspaces.value.find(w => w.isDefault) ?? workspaces.value[0];
      setCurrent(def.id);
    }
    return true;
  };

  const create = async (input: { name: string; description?: string }) => {
    const w = await apiCreate(input);
    workspaces.value.unshift(w);
    return w;
  };

  const update = async (id: string, input: Partial<Workspace>) => {
    const w = await apiUpdate(id, input);
    const idx = workspaces.value.findIndex(x => x.id === id);
    if (idx >= 0) workspaces.value[idx] = w;
    return w;
  };

  const remove = async (id: string): Promise<{ ok: boolean; switchedTo?: string }> => {
    await apiDelete(id);
    workspaces.value = workspaces.value.filter(w => w.id !== id);
    if (currentId.value === id) {
      const def = workspaces.value.find(w => w.isDefault) ?? workspaces.value[0];
      if (def) {
        setCurrent(def.id);
        return { ok: true, switchedTo: def.id };
      }
      currentId.value = '';
      setCurrentWorkspaceId('');
    }
    return { ok: true };
  };

  return {
    workspaces,
    currentId,
    loading,
    reload,
    current,
    setCurrent,
    ensureValidCurrent,
    create,
    update,
    remove,
  };
}
