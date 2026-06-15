import { computed, effectScope, nextTick, onScopeDispose, shallowRef, watch } from 'vue';
import { useElementSize } from '@vueuse/core';
import * as echarts from 'echarts/core';
import {
  BarChart,
  CustomChart,
  GaugeChart,
  LineChart,
  PictorialBarChart,
  PieChart,
  RadarChart,
  ScatterChart
} from 'echarts/charts';
import type {
  BarSeriesOption,
  CustomSeriesOption,
  GaugeSeriesOption,
  LineSeriesOption,
  PictorialBarSeriesOption,
  PieSeriesOption,
  RadarSeriesOption,
  ScatterSeriesOption
} from 'echarts/charts';
import {
  DatasetComponent,
  GridComponent,
  LegendComponent,
  TitleComponent,
  ToolboxComponent,
  TooltipComponent,
  TransformComponent
} from 'echarts/components';
import type {
  DatasetComponentOption,
  GridComponentOption,
  LegendComponentOption,
  TitleComponentOption,
  ToolboxComponentOption,
  TooltipComponentOption
} from 'echarts/components';
import { LabelLayout, UniversalTransition } from 'echarts/features';
import { CanvasRenderer } from 'echarts/renderers';
import { useThemeStore } from '@/store/modules/theme';

export type ECOption = echarts.ComposeOption<
  | BarSeriesOption
  | CustomSeriesOption
  | LineSeriesOption
  | PieSeriesOption
  | ScatterSeriesOption
  | PictorialBarSeriesOption
  | RadarSeriesOption
  | GaugeSeriesOption
  | TitleComponentOption
  | LegendComponentOption
  | TooltipComponentOption
  | GridComponentOption
  | ToolboxComponentOption
  | DatasetComponentOption
>;

echarts.use([
  TitleComponent,
  LegendComponent,
  TooltipComponent,
  GridComponent,
  DatasetComponent,
  TransformComponent,
  ToolboxComponent,
  BarChart,
  CustomChart,
  LineChart,
  PieChart,
  ScatterChart,
  PictorialBarChart,
  RadarChart,
  GaugeChart,
  LabelLayout,
  UniversalTransition,
  CanvasRenderer
]);

interface ChartHooks {
  onRender?: (chart: echarts.ECharts) => void | Promise<void>;
  onUpdated?: (chart: echarts.ECharts) => void | Promise<void>;
  onDestroy?: (chart: echarts.ECharts) => void | Promise<void>;
}

function isPlainObject(value: unknown): value is Record<string, unknown> {
  return Object.prototype.toString.call(value) === '[object Object]';
}

function cloneValue<T>(value: T): T {
  if (Array.isArray(value)) {
    return value.map(item => cloneValue(item)) as T;
  }
  if (isPlainObject(value)) {
    const cloned: Record<string, unknown> = {};
    Object.entries(value).forEach(([k, v]) => {
      cloned[k] = cloneValue(v);
    });
    return cloned as T;
  }
  return value;
}

function mergeArray(target: unknown[] = [], source: unknown[]): unknown[] {
  if (source.length === 0) return [];

  const objectLikeSource = source.every(item => isPlainObject(item) || item === undefined);
  if (!objectLikeSource) {
    return source.map(item => cloneValue(item));
  }

  const result = target.map(item => cloneValue(item));
  source.forEach((srcItem, index) => {
    const targetItem = result[index];
    if (isPlainObject(srcItem) && isPlainObject(targetItem)) {
      result[index] = mergeOptions(targetItem, srcItem);
    } else {
      result[index] = cloneValue(srcItem);
    }
  });
  return result;
}

function mergeOptions<T>(target: T, source: unknown): T {
  if (Array.isArray(source)) {
    const targetArray = Array.isArray(target) ? (target as unknown[] | undefined) : [];
    return mergeArray(targetArray, source) as T;
  }

  if (!isPlainObject(source)) {
    return cloneValue(source) as T;
  }

  const base = isPlainObject(target) ? cloneValue(target) : {};
  const result: Record<string, unknown> = base as Record<string, unknown>;

  Object.entries(source).forEach(([key, srcValue]) => {
    const targetValue = result[key];

    if (Array.isArray(srcValue)) {
      const targetArray = Array.isArray(targetValue) ? targetValue : [];
      result[key] = mergeArray(targetArray, srcValue);
      return;
    }

    if (isPlainObject(srcValue)) {
      const targetObject = isPlainObject(targetValue) ? targetValue : {};
      result[key] = mergeOptions(targetObject, srcValue);
      return;
    }

    result[key] = cloneValue(srcValue);
  });

  return result as T;
}

/**
 * use echarts
 *
 * @param optionsFactory echarts options factory function
 * @param darkMode dark mode
 */
export function useEcharts<T extends ECOption>(optionsFactory: () => T, hooks: ChartHooks = {}) {
  const scope = effectScope();

  const themeStore = useThemeStore();
  const darkMode = computed(() => themeStore.darkMode);

  const domRef = shallowRef<HTMLElement | null>(null);
  const initialSize = { width: 0, height: 0 };
  const { width, height } = useElementSize(domRef, initialSize);

  const chart = shallowRef<echarts.ECharts | null>(null);
  const chartOptions: T = optionsFactory();

  const {
    onRender = instance => {
      const textColor = darkMode.value ? 'rgb(224, 224, 224)' : 'rgb(31, 31, 31)';
      const maskColor = darkMode.value ? 'rgba(0, 0, 0, 0.4)' : 'rgba(255, 255, 255, 0.8)';

      instance.showLoading({
        color: themeStore.themeColor,
        textColor,
        fontSize: 14,
        maskColor
      });
    },
    onUpdated = instance => {
      instance.hideLoading();
    },
    onDestroy
  } = hooks;

  /** is chart rendered */
  function isRendered() {
    return Boolean(domRef.value && chart.value);
  }

  /**
   * update chart options
   *
   * @param callback callback function
   */
  async function updateOptions(callback: (opts: T, optsFactory: () => T) => ECOption = () => chartOptions) {
    const updatedOpts = callback(chartOptions, optionsFactory) as T;
    const nextOptions =
      updatedOpts === chartOptions ? cloneValue(updatedOpts) : mergeOptions(chartOptions, updatedOpts);
    Object.keys(chartOptions as Record<string, unknown>).forEach(key => {
      delete (chartOptions as Record<string, unknown>)[key];
    });
    Object.assign(chartOptions, nextOptions);

    await nextTick();

    if (!isRendered()) return;

    chart.value?.setOption({ ...chartOptions, backgroundColor: 'transparent' }, { notMerge: true });

    await onUpdated?.(chart.value!);
  }

  function setOptions(options: T) {
    chart.value?.setOption(options);
  }

  function isDomReady() {
    const dom = domRef.value;
    if (!dom || !dom.isConnected) return false;
    return dom.clientWidth > 0 && dom.clientHeight > 0;
  }

  /** render chart */
  async function render() {
    if (isRendered()) return true;
    if (!isDomReady()) return false;

    const chartTheme = darkMode.value ? 'dark' : 'light';

    chart.value = echarts.init(domRef.value, chartTheme);

    chart.value?.setOption({ ...chartOptions, backgroundColor: 'transparent' });

    await onRender?.(chart.value!);
    return true;
  }

  /** resize chart */
  function resize() {
    chart.value?.resize();
  }

  /** destroy chart */
  async function destroy() {
    if (!chart.value) return;

    await onDestroy?.(chart.value);
    chart.value?.dispose();
    chart.value = null;
  }

  /** change chart theme */
  async function changeTheme() {
    await destroy();
    const rendered = await render();
    if (rendered && chart.value) {
      await onUpdated?.(chart.value);
    }
  }

  /**
   * render chart by size
   *
   * @param w width
   * @param h height
   */
  async function renderChartBySize(w: number, h: number) {
    initialSize.width = w;
    initialSize.height = h;

    // resize chart
    if (isRendered()) {
      resize();

      return;
    }

    // render chart
    const rendered = await render();

    if (rendered && chart.value) {
      await onUpdated?.(chart.value);
    }
  }

  scope.run(() => {
    watch(
      [width, height],
      ([newWidth, newHeight]) => {
        renderChartBySize(newWidth, newHeight);
      },
      { flush: 'post' }
    );

    watch(darkMode, () => {
      changeTheme();
    });
  });

  onScopeDispose(() => {
    destroy();
    scope.stop();
  });

  return {
    domRef,
    chart,
    updateOptions,
    setOptions
  };
}
