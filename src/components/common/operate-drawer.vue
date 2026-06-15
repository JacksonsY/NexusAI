<script setup lang="ts">
import { computed, nextTick, onUnmounted, reactive, ref } from 'vue';
import { NDrawer, NDrawerContent } from 'naive-ui';
import { useAppStore } from '@/store/modules/app';

defineOptions({
  name: 'OperateDrawer'
});

interface Props {
  title: string;
  minSize?: number;
  maxSize?: number;
  defaultFullscreen?: boolean;
}
const props = withDefaults(defineProps<Props>(), { minSize: 360, maxSize: undefined, defaultFullscreen: false });

const emit = defineEmits<{
  (e: 'update:modelValue', modelValue: boolean): void;
}>();

const model = defineModel<boolean>({ default: false });

const slots = defineSlots();

const appStore = useAppStore();

const state = reactive({ width: 0 });
const isFullscreen = ref(props.defaultFullscreen);
const drawerWidth = computed(() => {
  const maxMinWidth = props.minSize;
  const maxMaxWidth = Math.max(props.maxSize ?? props.minSize + 240, props.minSize, 600);
  if (appStore.isMobile) {
    return state.width * 0.9 >= maxMinWidth ? `${maxMinWidth}px` : '90%';
  }
  let minWidth = state.width * 0.3 >= maxMinWidth ? `${maxMinWidth}px` : '30%';
  minWidth = state.width <= 420 ? '90%' : minWidth;
  let maxWidth = state.width * 0.85 >= maxMaxWidth ? `${maxMaxWidth}px` : '85%';
  maxWidth = state.width <= 420 ? '90%' : maxWidth;
  return isFullscreen.value ? maxWidth : minWidth;
});

const getState = () => {
  state.width = document.documentElement.clientWidth;
};

nextTick(() => {
  getState();
  window.addEventListener('resize', getState);
});

onUnmounted(() => {
  window.removeEventListener('resize', getState);
});

const onUpdateShow = (value: boolean) => {
  emit('update:modelValue', value);
};
</script>

<template>
  <NDrawer v-model:show="model" display-directive="if" :width="drawerWidth" @update:show="onUpdateShow">
    <NDrawerContent :title="props.title" :native-scrollbar="false" closable header-class="operate-drawer-header">
      <template #header>
        <div class="flex items-center justify-between w-full">
          <span>{{ props.title }}</span>
          <div
            v-if="!appStore.isMobile"
            class="fullscreen text-18px color-#6a6a6a"
            @click="isFullscreen = !isFullscreen"
          >
            <IconGridiconsFullscreenExit v-if="isFullscreen" />
            <IconGridiconsFullscreen v-else />
          </div>
        </div>
      </template>
      <slot />
      <template v-if="slots.footer" #footer>
        <slot name="footer" />
      </template>
    </NDrawerContent>
  </NDrawer>
</template>

<style scoped>
.fullscreen {
  height: 22px;
  width: 22px;
  display: flex;
  justify-content: center;
  align-items: center;
}

.fullscreen:hover {
  background-color: #e8e8e8;
  color: #696969;
  border-radius: 6px;
  cursor: pointer;
}
</style>
