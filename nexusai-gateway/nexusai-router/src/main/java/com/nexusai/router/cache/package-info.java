/**
 * 路由热数据缓存（数据面·选号加速）：{@code RouteCache}——ability/channel/key 三类热数据本地 Caffeine 短 TTL 缓存，
 * 避免两级调度热路径每次打库。
 * <p><b>同名包消歧（全仓共 3 个 {@code cache} 包·职责互不相同·勿合并）</b>：
 * <ul>
 *   <li>本包 {@code com.nexusai.router.cache} = <b>路由热数据缓存</b>（选号加速·非响应缓存）；</li>
 *   <li>{@code com.nexusai.relay.cache}（ResponseCache）= <b>响应缓存</b>（命中即回放上游响应·省转发）；</li>
 *   <li>{@code com.nexusai.observe.cache}（CacheRegistry）= <b>缓存监控注册表</b>（各热路径缓存自注册·控制面只读统计）。</li>
 * </ul>
 *
 * @author nexusai
 */
package com.nexusai.router.cache;
