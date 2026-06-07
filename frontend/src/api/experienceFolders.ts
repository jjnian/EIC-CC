import { request } from './http';

/** 经验库文件夹（工作空间内任意层级，parentId 自引用，缺省=根）。与数据源文件夹结构平行。 */
export interface ExperienceFolder {
  id: string;
  name: string;
  parentId?: string;
  sortOrder?: number;
  createdAt?: number;
  updatedAt?: number;
}

export function listExperienceFolders(workspaceId?: string) {
  const tail = workspaceId ? `?workspaceId=${encodeURIComponent(workspaceId)}` : '';
  return request<ExperienceFolder[]>(`/api/experience-folders${tail}`);
}

export function createExperienceFolder(payload: { name: string; parentId?: string | null }) {
  return request<ExperienceFolder>('/api/experience-folders', {
    method: 'POST',
    body: JSON.stringify(payload),
  });
}

/** 仅重命名（不传 parentId，避免被当成移动）。 */
export function renameExperienceFolder(id: string, name: string) {
  return request<{ success: boolean }>(`/api/experience-folders/${encodeURIComponent(id)}`, {
    method: 'PUT',
    body: JSON.stringify({ name }),
  });
}

/** 移动文件夹到新父级（parentId=null 移到根）。服务端做环检测。 */
export function moveExperienceFolder(id: string, parentId: string | null) {
  return request<{ success: boolean }>(`/api/experience-folders/${encodeURIComponent(id)}`, {
    method: 'PUT',
    body: JSON.stringify({ parentId }),
  });
}

/** 删除文件夹：其子文件夹与经验会上提到父级（不丢数据）。 */
export function deleteExperienceFolder(id: string) {
  return request<{ success: boolean }>(`/api/experience-folders/${encodeURIComponent(id)}`, {
    method: 'DELETE',
  });
}

/** 移动经验到文件夹（folderId=null 移到根）。 */
export function moveExperienceToFolder(id: string, folderId: string | null) {
  return request<{ success: boolean }>(`/api/experiences/${encodeURIComponent(id)}/folder`, {
    method: 'PUT',
    body: JSON.stringify({ folderId }),
  });
}
