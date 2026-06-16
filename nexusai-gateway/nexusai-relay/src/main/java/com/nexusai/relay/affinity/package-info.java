/**
 * 会话亲和——<b>键提取侧</b>（数据面·relay）：{@code AffinityKeyResolver}/{@code DigestSessionStore}——
 * 从请求体三级提取稳定会话标识（metadata 稳定标识 → 摘要链 → tokenId），产出 {@code RelayContext.affinityKey}。
 * <p><b>同名包消歧（{@code affinity} 出现在两模块·有意分层·勿合并）</b>：本包<b>碰请求体、只产键</b>；
 * 对侧 {@code com.nexusai.router.affinity}（StickyAffinity/AffinityEntryStore）是<b>记忆侧·不碰请求体</b>——
 * 按本包产出的 key 记忆「键→Key」并在选号时复用。两侧经 {@code RelayContext.affinityKey} 衔接。
 *
 * @author nexusai
 */
package com.nexusai.relay.affinity;
