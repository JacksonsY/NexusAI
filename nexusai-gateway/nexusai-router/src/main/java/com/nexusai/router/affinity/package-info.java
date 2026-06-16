/**
 * 会话亲和——<b>记忆侧</b>（数据面·router）：{@code StickyAffinity}/{@code AffinityEntryStore}——
 * 按会话键记忆「键→Key」映射并在四层选号中复用（①锚定 ②粘性），含命中/切换/失效统计与一键清除。
 * <p><b>同名包消歧（{@code affinity} 出现在两模块·有意分层·勿合并）</b>：本包<b>不碰请求体、只记忆键→Key</b>；
 * 对侧 {@code com.nexusai.relay.affinity}（AffinityKeyResolver/DigestSessionStore）是<b>键提取侧·碰请求体</b>——
 * 从请求体产出会话键。两侧经 {@code RelayContext.affinityKey} 衔接。
 * <p><b>控制面边界（已评审·接受直接依赖）</b>：control-domain 的 {@code CacheMonitorService} 直接注入本类做亲和统计/列表（读·返回 {@code EntryView}）
 * 与清除（写·{@code removeByCacheKey}/{@code clearAll}）——属合法「控制→数据」方向。<b>不抽 admin-facing SPI</b>：读操作依赖本类嵌套视图
 * 类型（{@code EntryView}），抽写口 SPI 也消不掉读侧 import，是伪 SPI（2026-06-14 评审定论）。新增「指标型」只读应优先走
 * {@code ModuleMetricsRegistry}/{@code CacheRegistry} 注册表反转，而非扩本类被控制面直接依赖的面。
 *
 * @author nexusai
 */
package com.nexusai.router.affinity;
