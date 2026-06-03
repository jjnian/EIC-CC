import { request } from './http';

/** 数据源文件夹（工作空间内任意层级，parentId 自引用，缺省=根）。 */
export interface DataSourceFolder {
  id: string;
  name: string;
  parentId?: string;
  sortOrder?: number;
  createdAt?: number;
  updatedAt?: number;
}

export function listFolders(workspaceId?: string) {
  const tail = workspaceId ? `?workspaceId=${encodeURIComponent(workspaceId)}` : '';
  return request<DataSourceFolder[]>(`/api/data-source-folders${tail}`);
}

export function createFolder(payload: { name: string; parentId?: string | null }) {
  return request<DataSourceFolder>('/api/data-source-folders', {
    method: 'POST',
    body: JSON.stringify(payload),
  });
}

/** 仅重命名（不传 parentId，避免被当成移动）。 */
export function renameFolder(id: string, name: string) {
  return request<{ success: boolean }>(`/api/data-source-folders/${encodeURIComponent(id)}`, {
    method: 'PUT',
    body: JSON.stringify({ name }),
  });
}

/** 移动文件夹到新父级（parentId=null 移到根）。服务端做环检测。 */
export function moveFolder(id: string, parentId: string | null) {
  return request<{ success: boolean }>(`/api/data-source-folders/${encodeURIComponent(id)}`, {
    method: 'PUT',
    body: JSON.stringify({ parentId }),
  });
}

/** 删除文件夹：其子文件夹与数据源会上提到父级（不丢数据）。 */
export function deleteFolder(id: string) {
  return request<{ success: boolean }>(`/api/data-source-folders/${encodeURIComponent(id)}`, {
    method: 'DELETE',
  });
}

/** 移动数据源到文件夹（folderId=null 移到根）。 */
export function moveDataSourceToFolder(id: string, folderId: string | null) {
  return request<{ success: boolean }>(`/api/data-sources/${encodeURIComponent(id)}/folder`, {
    method: 'PUT',
    body: JSON.stringify({ folderId }),
  });
}
