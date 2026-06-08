import { sse } from './http';
import type { SseHandle } from './http';
import type { Experience } from './experiences';

/**
 * 启动一次「自动探索系统了解业务」(SSE)。
 * 智能体像人一样只读操作目标系统、摸清功能,结束后落一篇 origin=explore 的经验。
 */
export function runExplore(
  body: {
    baseUrl: string;
    /** 登录系统的用户名（可空，填了则探索前自动登录） */
    username?: string;
    /** 登录系统的密码（可空） */
    password?: string;
    maxSteps?: number;
    readOnly?: boolean;
    storageState?: string;
    modelOverride?: string;
    configId?: string;
  },
  handlers: {
    onStep?: (key: string, label: string) => void;
    onComplete?: (exp: Experience) => void;
    onError?: (msg: string) => void;
    onClose?: () => void;
  },
): SseHandle {
  return sse('/api/explore/run', body, {
    onEvent: (event, data) => {
      if (event === 'step') {
        try { const j = JSON.parse(data) as { key: string; label: string }; handlers.onStep?.(j.key, j.label); }
        catch { /* ignore */ }
      } else if (event === 'complete') {
        try { handlers.onComplete?.(JSON.parse(data) as Experience); }
        catch (e) { handlers.onError?.((e as Error).message); }
      } else if (event === 'error') {
        handlers.onError?.(data);
      }
    },
    onError: (err) => handlers.onError?.(err.message),
    onClose: () => handlers.onClose?.(),
  });
}
