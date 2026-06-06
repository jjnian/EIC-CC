import { request } from './http';
import type { ChatMsg } from '../composables/useConversations';

export interface ConversationDto {
  id: string;
  title: string;
  /** 绑定的本体血缘图 id：一会话一图，会话内改动都落到这张图。历史会话可能为空。 */
  modelId?: string;
  createdAt: number;
  updatedAt?: number;
  msgs: ChatMsg[];
}

export function listConversations(opts?: { workspaceId?: string }) {
  const qs = opts?.workspaceId
    ? `?workspaceId=${encodeURIComponent(opts.workspaceId)}`
    : '';
  return request<ConversationDto[]>(`/api/conversations${qs}`);
}

export function getConversation(id: string) {
  return request<ConversationDto>(`/api/conversations/${encodeURIComponent(id)}`);
}

export function createConversation(c: ConversationDto) {
  return request<ConversationDto>('/api/conversations', {
    method: 'POST',
    body: JSON.stringify(c),
  });
}

export function updateConversation(id: string, c: ConversationDto) {
  return request<ConversationDto>(`/api/conversations/${encodeURIComponent(id)}`, {
    method: 'PUT',
    body: JSON.stringify(c),
  });
}

export function deleteConversation(id: string) {
  return request<{ success: boolean }>(`/api/conversations/${encodeURIComponent(id)}`, {
    method: 'DELETE',
  });
}
