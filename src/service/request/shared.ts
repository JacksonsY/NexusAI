import { localStg } from '@/utils/storage';
import type { RequestInstanceState } from './type';

/** 返回裸令牌（由 onRequest 注入 `NexusAI-Admin-Token` 头·Sa-Token 会话）。 */
export function getAuthorization() {
  const token = localStg.get('token');
  return token ? `${token}` : null;
}

export function showErrorMsg(state: RequestInstanceState, message: string) {
  if (!state.errMsgStack?.length) {
    state.errMsgStack = [];
  }

  const isExist = state.errMsgStack.includes(message);

  if (!isExist) {
    state.errMsgStack.push(message);

    window.$message?.error(message, {
      onLeave: () => {
        state.errMsgStack = state.errMsgStack.filter(msg => msg !== message);

        setTimeout(() => {
          state.errMsgStack = [];
        }, 5000);
      }
    });
  }
}
