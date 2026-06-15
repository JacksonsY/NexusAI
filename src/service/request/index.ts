import type { AxiosResponse } from 'axios';
import { BACKEND_ERROR_CODE, createFlatRequest } from '@sa/axios';
import { useAuthStore } from '@/store/modules/auth';
import { getServiceBaseURL } from '@/utils/service';
import { $t } from '@/locales';
import { getAuthorization, showErrorMsg } from './shared';
import type { RequestInstanceState } from './type';

const isHttpProxy = import.meta.env.DEV && import.meta.env.VITE_HTTP_PROXY === 'Y';
const { baseURL } = getServiceBaseURL(import.meta.env, isHttpProxy);

type BackendResponseData = {
  status?: unknown;
  code?: unknown;
  message?: unknown;
  msg?: unknown;
};

type BackendResponse = {
  data?: BackendResponseData;
};

/** 后端统一响应：业务码取 status（兼容 code）。 */
const getResponseCode = (response: BackendResponse) => {
  const d = response?.data;
  if (!d) return '';
  const code = d.status ?? d.code;
  if (code === undefined || code === null) return '';
  return String(code).trim();
};

/** 后端统一响应：消息取 message（兼容 msg）。 */
const getResponseMessage = (response: BackendResponse) => {
  const d = response?.data;
  if (!d) return '';
  const message = d.message ?? d.msg;
  if (message === undefined || message === null) return '';
  return String(message).trim();
};

export const request = createFlatRequest(
  {
    baseURL,
    headers: {
      timeout: 6000
    },
    paramsSerializer: {
      indexes: null // 使用 arr=1&arr=2 格式而不是 arr[0]=1&arr[1]=2
    }
  },
  {
    defaultState: {
      errMsgStack: []
    } as RequestInstanceState,
    transform(response: AxiosResponse<App.Service.Response<any>>) {
      const d = response.data;
      if (!d) return undefined;
      if (d.total !== undefined && d.total !== null) return d;
      return (d as { data?: unknown }).data;
    },
    async onRequest(config) {
      const token = getAuthorization();
      Object.assign(config.headers, { 'NexusAI-Admin-Token': token });
      return config;
    },
    isBackendSuccess(response) {
      return getResponseCode(response) === import.meta.env.VITE_SERVICE_SUCCESS_CODE;
    },
    async onBackendFail(response) {
      const authStore = useAuthStore();
      const responseCode = getResponseCode(response);
      const msg = getResponseMessage(response);

      function handleLogout() {
        authStore.resetStore();
      }

      function logoutAndCleanup() {
        handleLogout();
        window.removeEventListener('beforeunload', handleLogout);
        request.state.errMsgStack = request.state.errMsgStack.filter(m => m !== msg);
      }

      const logoutCodes = import.meta.env.VITE_SERVICE_LOGOUT_CODES?.split(',') || [];
      if (logoutCodes.includes(responseCode)) {
        handleLogout();
        return null;
      }

      const modalLogoutCodes = import.meta.env.VITE_SERVICE_MODAL_LOGOUT_CODES?.split(',') || [];
      if (modalLogoutCodes.includes(responseCode) && !request.state.errMsgStack?.includes(msg)) {
        request.state.errMsgStack = [...(request.state.errMsgStack || []), msg];
        window.addEventListener('beforeunload', handleLogout);
        window.$dialog?.error({
          title: $t('common.error'),
          content: msg,
          positiveText: $t('common.confirm'),
          maskClosable: false,
          closeOnEsc: false,
          onPositiveClick: logoutAndCleanup,
          onClose: logoutAndCleanup
        });
        return null;
      }

      if (msg) {
        showErrorMsg(request.state, msg);
      }

      return null;
    },
    onError(error) {
      // 会话失效/未登录（HTTP 401·如后端重启丢内存会话）：静默重置登录态回登录页，
      // 避免首屏并发请求每个各弹一条错误（resetStore 幂等，多次触发无害）
      if (error.response?.status === 401) {
        useAuthStore().resetStore();
        return;
      }
      let message = error.message;
      let backendErrorCode = '';
      if (error.code === BACKEND_ERROR_CODE) {
        const backendResponse = { data: (error.response?.data as BackendResponseData | undefined) ?? undefined };
        message = getResponseMessage(backendResponse) || message;
        backendErrorCode = getResponseCode(backendResponse);
      }
      const modalLogoutCodes = import.meta.env.VITE_SERVICE_MODAL_LOGOUT_CODES?.split(',') || [];
      if (modalLogoutCodes.includes(backendErrorCode)) return;
      showErrorMsg(request.state, message);
    }
  }
);
