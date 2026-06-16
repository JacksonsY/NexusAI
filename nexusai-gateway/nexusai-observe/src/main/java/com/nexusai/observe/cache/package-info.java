/**
 * 缓存监控注册表（可观测·控制面只读）：{@code CacheRegistry}——各热路径 Caffeine/磁盘缓存在启动时自注册统计供给器，
 * 控制面缓存管理页按名查命中率/容量，<b>解除控制面对数据面缓存具体类型的依赖</b>（注册表反转）。
 * <p><b>同名包消歧（全仓共 3 个 {@code cache} 包·职责互不相同·勿合并）</b>：
 * <ul>
 *   <li>本包 {@code com.nexusai.observe.cache} = <b>缓存监控注册表</b>（自注册+只读统计·不持有任何业务缓存数据）；</li>
 *   <li>{@code com.nexusai.relay.cache}（ResponseCache）= <b>响应缓存</b>（命中即回放上游响应·省转发）；</li>
 *   <li>{@code com.nexusai.router.cache}（RouteCache）= <b>路由热数据缓存</b>（ability/channel/key 三热·选号加速）。</li>
 * </ul>
 *
 * @author nexusai
 */
package com.nexusai.observe.cache;
