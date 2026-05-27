import { request } from './http';
import type { ChatMsg } from '../composables/useConversations';

export interface ConversationDto {
  id: string;
  title: string;
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
