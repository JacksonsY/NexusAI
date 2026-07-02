import { computed, reactive, ref } from 'vue';
import { useRoute } from 'vue-router';
import { defineStore } from 'pinia';
import { useLoading } from '@sa/hooks';
import { fetchAdminLogin, fetchAdminLogout, fetchAdminUserInfo } from '@/service/api';
import { useRouterPush } from '@/hooks/common/router';
import { localStg } from '@/utils/storage';
import { SetupStoreId } from '@/enum';
import { $t } from '@/locales';
import { useRouteStore } from '../route';
import { useTabStore } from '../tab';
import { clearAuthStorage, getToken } from './shared';

export const useAuthStore = defineStore(SetupStoreId.Auth, () => {
  const route = useRoute();
  const authStore = useAuthStore();
  const routeStore = useRouteStore();
  const tabStore = useTabStore();
  const { toLogin, redirectFromLogin } = useRouterPush(false);
  const { loading: loginLoading, startLoading, endLoading } = useLoading();

  const token = ref('');

  const userInfo: Api.Auth.UserInfo = reactive({
    userId: '',
    userName: '',
    roles: [],
    buttons: []
  });

  /** is super role in static route（nexusai 账号即超管，roles 含 VITE_STATIC_SUPER_ROLE） */
  const isStaticSuper = computed(() => {
    const { VITE_AUTH_ROUTE_MODE, VITE_STATIC_SUPER_ROLE } = import.meta.env;

    return VITE_AUTH_ROUTE_MODE === 'static' && userInfo.roles.includes(VITE_STATIC_SUPER_ROLE);
  });

  /** Is login */
  const isLogin = computed(() => Boolean(token.value));

  /** resetStore 防重入标志：401 并发 / 登出接口自身再 401 时，避免重复登出与重复回登录页 */
  let isResetting = false;

  /** Reset auth store */
  async function resetStore() {
    if (isResetting) return;
    isResetting = true;

    try {
      // 仅当本地仍存令牌时才尽力吊销服务端 Sa-Token 会话（无令牌=已登出·跳过避免空打登出接口再触发 401）
      if (getToken()) {
        fetchAdminLogout().catch(() => {});
      }

      recordUserId();

      clearAuthStorage();

      authStore.$reset();

      if (!route.meta.constant) {
        await toLogin();
      }

      tabStore.cacheTabs();
      routeStore.resetStore();
    } finally {
      isResetting = false;
    }
  }

  /** Record the user ID of the previous login session Used to compare with the current user ID on next login */
  function recordUserId() {
    if (!userInfo.userId) {
      return;
    }

    // Store current user ID locally for next login comparison
    localStg.set('lastLoginUserId', userInfo.userId);
  }

  /**
   * Check if current login user is different from previous login user If different, clear all tabs
   *
   * @returns {boolean} Whether to clear all tabs
   */
  function checkTabClear(): boolean {
    if (!userInfo.userId) {
      return false;
    }

    const lastLoginUserId = localStg.get('lastLoginUserId');

    // Clear all tabs if current user is different from previous user
    if (!lastLoginUserId || lastLoginUserId !== userInfo.userId) {
      localStg.remove('globalTabs');
      tabStore.clearTabs();

      localStg.remove('lastLoginUserId');
      return true;
    }

    localStg.remove('lastLoginUserId');
    return false;
  }

  /**
   * 登录（nexusai 控制台：账号密码 → Sa-Token 会话）。
   * POST /admin/login 校验 nexusai.admin 账号密码 → 后端签发 Sa-Token 令牌 → 存令牌（后续走 NexusAI-Admin-Token 头）。
   *
   * @param userName 账号
   * @param password 密码
   */
  async function login(userName: string, password: string) {
    startLoading();

    const { data, error } = await fetchAdminLogin(userName, password);

    if (!error && data?.token) {
      localStg.set('token', data.token);
      token.value = data.token;
      setAdminUserInfo(data.username || userName);

      checkTabClear();
      await redirectFromLogin(false, '/dashboard');

      window.$notification?.success({
        title: $t('page.login.common.loginSuccess'),
        content: $t('page.login.common.welcomeBack', { userName: userInfo.userName }),
        duration: 4500
      });
    } else {
      // 账号密码错误：请求拦截器已弹错误提示，仅清理本地令牌、停留登录页
      localStg.remove('token');
      token.value = '';
    }

    endLoading();
  }

  /** nexusai：账号即超级管理员（无独立用户体系，用户/钱/账在 业务面）。 */
  function setAdminUserInfo(name = 'admin') {
    Object.assign(userInfo, {
      userId: name,
      userName: name,
      roles: [import.meta.env.VITE_STATIC_SUPER_ROLE],
      buttons: []
    });
  }

  /**
   * 拉取当前登录用户信息（借鉴 snail-ai-admin 的 getUserInfo）。
   * 兼作服务端 Sa-Token 会话校验：令牌有效 → 灌入用户信息返回 true；令牌过期/被吊销 → 返回 false。
   */
  async function getUserInfo() {
    const { data, error } = await fetchAdminUserInfo();

    if (!error && data?.username) {
      setAdminUserInfo(data.username);
      return true;
    }

    return false;
  }

  async function initUserInfo() {
    const maybeToken = getToken();

    if (maybeToken) {
      token.value = maybeToken;
      // nexusai：token 即超管，但重进时仍向后端校验会话（过期/吊销则踢回登录页）
      const pass = await getUserInfo();

      if (!pass) {
        await resetStore();
      }
    }
  }

  return {
    token,
    userInfo,
    isStaticSuper,
    isLogin,
    loginLoading,
    resetStore,
    login,
    initUserInfo
  };
});
