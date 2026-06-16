package com.nexusai.common.model;

import lombok.Getter;

/**
 * 框架无关分页入参（admin 控制面 list 端点统一）：归一默认值 + pageSize 上限防爆，与 MyBatis-Plus {@code IPage} 解耦。
 * <p>用法：{@code PageQuery pq = PageQuery.of(pageNum, pageSize);} 再用 {@code pq.getPageNum()/getPageSize()} 构造底层分页对象，
 * 避免每个 list 端点重复写默认值与 pageSize 防御上限。common 不依赖任何 ORM，故只做归一不做查询。
 *
 * @author nexusai
 */
@Getter
public final class PageQuery {

    /** 默认页码 */
    public static final long DEFAULT_PAGE_NUM = 1L;
    /** 默认每页条数 */
    public static final long DEFAULT_PAGE_SIZE = 10L;
    /** 每页条数上限（防御 pageSize=99999 拖垮库/内存） */
    public static final long MAX_PAGE_SIZE = 200L;

    private final long pageNum;
    private final long pageSize;

    private PageQuery(long pageNum, long pageSize) {
        this.pageNum = pageNum;
        this.pageSize = pageSize;
    }

    /**
     * 归一化构造：pageNum&lt;1 回落 {@link #DEFAULT_PAGE_NUM}；pageSize&lt;1 回落 {@link #DEFAULT_PAGE_SIZE}、
     * 超过 {@link #MAX_PAGE_SIZE} 截断到上限。
     */
    public static PageQuery of(long pageNum, long pageSize) {
        long num = pageNum < 1 ? DEFAULT_PAGE_NUM : pageNum;
        long size = pageSize < 1 ? DEFAULT_PAGE_SIZE : Math.min(pageSize, MAX_PAGE_SIZE);
        return new PageQuery(num, size);
    }
}
