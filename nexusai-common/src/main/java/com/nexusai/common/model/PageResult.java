package com.nexusai.common.model;

import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * 框架无关分页结果（admin 控制面 list 端点统一返回）：隔离 MyBatis-Plus {@code IPage}，杜绝持久化类型泄漏到 API 层。
 * <p>调用方从底层分页对象取 {@code getRecords()/getTotal()} 经 {@link #of} 构造，common 不依赖任何 ORM。
 *
 * @author nexusai
 */
@Data
@NoArgsConstructor
public class PageResult<T> implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /** 当前页数据 */
    private List<T> records;
    /** 总条数 */
    private long total;
    /** 当前页码 */
    private long pageNum;
    /** 每页条数 */
    private long pageSize;

    private PageResult(List<T> records, long total, long pageNum, long pageSize) {
        this.records = records;
        this.total = total;
        this.pageNum = pageNum;
        this.pageSize = pageSize;
    }

    /** 由记录与总数构造（records 为 null 时空列表）。 */
    public static <T> PageResult<T> of(List<T> records, long total, long pageNum, long pageSize) {
        return new PageResult<>(records == null ? List.of() : records, total, pageNum, pageSize);
    }

    /** 空结果。 */
    public static <T> PageResult<T> empty(long pageNum, long pageSize) {
        return new PageResult<>(List.of(), 0L, pageNum, pageSize);
    }
}
