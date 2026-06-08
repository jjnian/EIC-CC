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
  return sse('/api/explore/run', body, exploreSseAdapter(handlers));
}

export interface ExploreHandlers {
  onStep?: (key: string, label: string) => void;
  onComplete?: (exp: Experience) => void;
  onError?: (msg: string) => void;
  onClose?: () => void;
}

/**
 * 探索一个已接入并保存的 web 系统(经验条目)。后端按其保存的连接配置(含真实密码,服务端读取)运行探索,
 * 每次另产一篇 origin=explore 的业务说明经验。
 */
export function runSavedExplore(
  body: { experienceId: string; modelOverride?: string; configId?: string },
  handlers: ExploreHandlers,
): SseHandle {
  return sse('/api/explore/run-saved', body, exploreSseAdapter(handlers));
}

/** /run 与 /run-saved 的 SSE 事件解析共用一份。 */
function exploreSseAdapter(handlers: ExploreHandlers) {
  return {
    onEvent: (event: string, data: string) => {
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
    onError: (err: Error) => handlers.onError?.(err.message),
    onClose: () => handlers.onClose?.(),
  };
}
