<script setup lang="ts">
import { useAppStore } from '@/store/modules/app';
import { useThemeStore } from '@/store/modules/theme';
import PwdLogin from './modules/pwd-login.vue';

defineOptions({ name: 'LoginPage' });

const appStore = useAppStore();
const themeStore = useThemeStore();

/** 品牌区特性点（真实产品能力·PM 文案：一句价值 + 一句机制） */
const features = [
  { icon: 'lucide:route', title: '多渠道智能路由', desc: '两级调度选号，故障自动转移' },
  { icon: 'lucide:gauge', title: '用量精确计量', desc: 'USD 强闸门，按真实 usage 结算' },
  { icon: 'lucide:plug-zap', title: '协议无缝透传', desc: 'OpenAI / Anthropic / Gemini 任意互转' }
];
</script>

<template>
  <div class="login-page relative size-full flex items-center justify-center overflow-hidden">
    <!-- 白天背景：浅灰蓝底 + 两团极淡主题色光晕（缓漂·尊重 reduced-motion） -->
    <div class="login-bg absolute inset-0" aria-hidden="true">
      <div class="login-halo login-halo--1" />
      <div class="login-halo login-halo--2" />
    </div>

    <!-- 右上工具：仅语言切换（登录页固定白天观感） -->
    <div class="absolute right-24px top-20px z-10">
      <LangSwitch
        v-if="themeStore.header.multilingual.visible"
        :lang="appStore.locale"
        :show-tooltip="false"
        class="text-18px"
        @change-lang="appStore.changeLocale"
      />
    </div>

    <!-- 主卡：左品牌区 + 右表单区 -->
    <div class="login-card relative z-1 grid w-920px overflow-hidden lt-md:w-360px lg:grid-cols-[42%_58%]">
      <!-- 品牌区（窄屏隐藏） -->
      <div class="login-brand hidden flex-col justify-between p-40px lg:flex">
        <div class="flex items-center gap-10px">
          <SystemLogo class="text-32px text-primary" />
          <span class="text-20px font-bold tracking-wide">NexusAI</span>
        </div>

        <div class="flex flex-col gap-28px">
          <div>
            <h1 class="m-0 text-26px font-bold leading-snug">统一 AI 中转网关</h1>
            <p class="m-0 mt-8px text-14px op-60">一个入口接入全部模型供应商，路由、计量、可观测开箱即用。</p>
          </div>
          <ul class="m-0 flex flex-col gap-18px p-0">
            <li v-for="f in features" :key="f.title" class="flex items-start gap-12px">
              <span class="brand-feature-icon flex shrink-0 items-center justify-center rounded-10px p-8px">
                <SvgIcon :icon="f.icon" class="text-18px" />
              </span>
              <span class="flex flex-col gap-2px">
                <span class="text-14px font-medium">{{ f.title }}</span>
                <span class="text-12px op-55">{{ f.desc }}</span>
              </span>
            </li>
          </ul>
        </div>

        <p class="m-0 text-12px op-40">既能独立运营，也能作为业务系统的网关底座。</p>
      </div>

      <!-- 表单区 -->
      <div class="flex flex-col justify-center gap-28px bg-white p-40px lt-md:p-32px dark:bg-#1c1c20">
        <div class="lg:hidden flex items-center justify-center gap-8px">
          <SystemLogo class="text-28px text-primary" />
          <span class="text-18px font-bold">NexusAI</span>
        </div>
        <div>
          <h2 class="m-0 text-22px font-bold">欢迎回来</h2>
          <p class="m-0 mt-6px text-14px op-60">登录控制台，管理你的渠道、项目与用量</p>
        </div>
        <PwdLogin />
        <p class="m-0 text-center text-12px op-40">© 2026 NexusAI · 统一 AI 中转网关</p>
      </div>
    </div>
  </div>
</template>

<style scoped>
.login-page {
  background: linear-gradient(160deg, #f5f7fd 0%, #eef1f9 55%, #f7f8fc 100%);
}

.login-halo {
  position: absolute;
  width: 560px;
  height: 560px;
  border-radius: 50%;
  filter: blur(90px);
  opacity: 0.5;
  will-change: transform;
  animation: halo-drift 14s ease-in-out infinite alternate;
}

.login-halo--1 {
  top: -180px;
  left: -120px;
  background: radial-gradient(circle, rgb(100 108 255 / 22%), transparent 65%);
}

.login-halo--2 {
  right: -160px;
  bottom: -200px;
  background: radial-gradient(circle, rgb(24 160 88 / 14%), transparent 65%);
  animation-delay: -7s;
}

@keyframes halo-drift {
  from {
    transform: translate3d(0, 0, 0);
  }

  to {
    transform: translate3d(40px, 24px, 0);
  }
}

@media (prefers-reduced-motion: reduce) {
  .login-halo {
    animation: none;
  }
}

.login-card {
  border: 1px solid rgb(100 108 255 / 10%);
  border-radius: 20px;
  box-shadow:
    0 24px 64px rgb(70 80 160 / 12%),
    0 2px 8px rgb(70 80 160 / 6%);
}

.login-brand {
  background:
    radial-gradient(120% 90% at 0% 0%, rgb(100 108 255 / 14%), transparent 60%),
    linear-gradient(165deg, rgb(100 108 255 / 8%) 0%, rgb(100 108 255 / 3%) 100%);
}

.brand-feature-icon {
  background: rgb(100 108 255 / 12%);
  color: #646cff;
}
</style>
