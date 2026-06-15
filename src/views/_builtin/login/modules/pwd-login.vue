<script setup lang="ts">
import { reactive } from 'vue';
import { useAuthStore } from '@/store/modules/auth';
import { useNaiveForm } from '@/hooks/common/form';

defineOptions({
  name: 'PwdLogin'
});

const authStore = useAuthStore();
const { formRef, validate } = useNaiveForm();

interface FormModel {
  userName: string;
  password: string;
}

const model: FormModel = reactive({
  userName: 'admin',
  password: ''
});

const rules = {
  userName: [{ required: true, message: '请输入账号', trigger: 'blur' }],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }]
};

async function handleLogin() {
  await validate();
  await authStore.login(model.userName, model.password);
}
</script>

<template>
  <NForm ref="formRef" :model="model" :rules="rules" size="large" :show-label="false" @keyup.enter="handleLogin">
    <NFormItem path="userName">
      <NInput v-model:value="model.userName" :spellcheck="false" placeholder="账号，如 admin" aria-label="账号">
        <template #prefix>
          <SvgIcon icon="mdi:account-outline" class="text-18px text-gray-400" />
        </template>
      </NInput>
    </NFormItem>
    <NFormItem path="password">
      <NInput
        v-model:value="model.password"
        type="password"
        show-password-on="click"
        :input-props="{ autocomplete: 'current-password' }"
        placeholder="请输入密码"
        aria-label="密码"
      >
        <template #prefix>
          <SvgIcon icon="mdi:lock-outline" class="text-18px text-gray-400" />
        </template>
      </NInput>
    </NFormItem>
    <NSpace vertical :size="14">
      <NButton
        type="primary"
        size="large"
        :loading="authStore.loginLoading"
        class="login-btn w-full"
        @click="handleLogin"
      >
        {{ authStore.loginLoading ? '登录中…' : '登录控制台' }}
      </NButton>
      <div class="text-center text-12px text-gray-400">超管账号由部署配置管理，登录即可管理渠道、项目与用量</div>
    </NSpace>
  </NForm>
</template>

<style scoped>
.login-btn {
  height: 44px;
  font-size: 15px;
  font-weight: 600;
  letter-spacing: 4px;
  border-radius: 10px;
}
</style>
