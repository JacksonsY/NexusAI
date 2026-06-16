/**
 * dashboard 图表统一基础样式（recharts 观感）：
 * 12px 中灰轴标签/轴名/图例 + 淡虚线网格 + 隐藏刻度。中灰固定值在亮/暗底都可读，不随主题切换错乱。
 *
 * 用法：图表 option 工厂里 `const b = chartBase()`，把 `b.textStyle`/`b.axis`/`b.splitLine`/`b.legend`
 * 合并进 textStyle / xAxis / yAxis / legend。
 */
/**
 * 维度图表统一色板（柱图 Cell 与序号图例圆点按同一索引取色，chart-1..6）。
 * 请求量柱 / 图例圆点 / 性能榜柱 共用，保证「第 N 根柱 = 图例第 N 项」颜色一致。
 */
export const DIM_COLORS = ['#646cff', '#18a058', '#f0a020', '#d03050', '#2080f0', '#7c4dff', '#13c2c2', '#eb2f96', '#fa8c16', '#52c41a'];

export function chartBase() {
  const labelColor = '#8c8c8c';
  const lineColor = 'rgba(140, 140, 140, 0.2)';

  return {
    /** 全局文本（tooltip 等继承） */
    textStyle: { fontSize: 12 },
    /** 轴通用（axisLabel/axisLine/axisTick/nameTextStyle）·合并进 xAxis/yAxis */
    axis: {
      axisLabel: { fontSize: 12, color: labelColor },
      axisLine: { lineStyle: { color: lineColor } },
      axisTick: { show: false },
      nameTextStyle: { fontSize: 12, color: labelColor }
    },
    /** 网格虚线（仅数值轴用） */
    splitLine: { lineStyle: { color: lineColor, type: 'dashed' as const } },
    /** 紧凑图例 */
    legend: { textStyle: { fontSize: 12, color: labelColor }, itemWidth: 12, itemHeight: 8, icon: 'roundRect' }
  };
}
