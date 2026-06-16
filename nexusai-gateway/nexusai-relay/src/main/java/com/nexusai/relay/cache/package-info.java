/**
 * 响应缓存（数据面·LLM 响应 SPI）：{@code ResponseCache}/{@code ExactResponseCache}/{@code DiskCacheStore}——
 * 精确匹配缓存命中（key=sha256(model+body)）直接回放上游响应，省一次转发。
 * <p><b>同名包消歧（全仓共 3 个 {@code cache} 包·职责互不相同·勿合并）</b>：
 * <ul>
 *   <li>本包 {@code com.nexusai.relay.cache} = <b>响应缓存</b>（命中即回放·省转发）；</li>
 *   <li>{@code com.nexusai.router.cache}（RouteCache）= <b>路由热数据缓存</b>（ability/channel/key 三热·Caffeine 短 TTL·加速选号）；</li>
 *   <li>{@code com.nexusai.observe.cache}（CacheRegistry）= <b>缓存监控注册表</b>（各热路径缓存自注册·控制面只读统计）。</li>
 * </ul>
 *
 * @author nexusai
 */
package com.nexusai.relay.cache;
