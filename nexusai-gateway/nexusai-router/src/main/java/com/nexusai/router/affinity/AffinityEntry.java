package com.nexusai.router.affinity;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 亲和条目（{@link StickyAffinity} 语义层与 {@link AffinityEntryStore} 存储层共用的数据载体）：
 * 选中 Key + 创建/最近写入时间 + 复用次数 + 展示元数据（relay 成功后补写）+ 每会话累计缓存命中 tokens。
 *
 * @author nexusai
 */
public final class AffinityEntry {

    public long keyId;
    public long createdAt;
    public volatile long lastWriteAt;
    public final AtomicLong requestCount = new AtomicLong(1);
    public volatile String projectCode;
    public volatile String model;
    public volatile String apiFormat;
    public volatile String clientFamily;
    public volatile String keySource;
    public final AtomicLong cachedTokens = new AtomicLong();

    AffinityEntry(long keyId, long now) {
        this.keyId = keyId;
        this.createdAt = now;
        this.lastWriteAt = now;
    }
}
