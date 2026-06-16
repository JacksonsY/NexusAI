package com.nexusai.control.domain.governance.idempotent.controller;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.nexusai.common.model.PageQuery;
import com.nexusai.common.model.PageResult;
import com.nexusai.common.model.R;
import com.nexusai.persistence.mapper.NaiIdempotentMapper;
import com.nexusai.persistence.po.NaiIdempotent;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * 幂等记录查询（运维台「可观测/幂等排查」）：nai_idempotent 命中/冲突审计。
 * 热路径去重用 Redis SETNX，本表作持久兜底/审计。
 *
 * @author nexusai
 */
@RestController
@RequestMapping("/admin/idempotents")
@RequiredArgsConstructor
public class IdempotentAdminController {

    private final NaiIdempotentMapper idempotentMapper;

    @GetMapping
    public Mono<R<PageResult<NaiIdempotent>>> list(@RequestParam(defaultValue = "1") long pageNum,
                                                   @RequestParam(defaultValue = "10") long pageSize) {
        PageQuery pq = PageQuery.of(pageNum, pageSize);
        return Mono.fromCallable(() -> {
            Page<NaiIdempotent> page = idempotentMapper.selectPage(new Page<>(pq.getPageNum(), pq.getPageSize()),
                    Wrappers.lambdaQuery(NaiIdempotent.class).orderByDesc(NaiIdempotent::getId));
            return R.ok(PageResult.of(page.getRecords(), page.getTotal(), pq.getPageNum(), pq.getPageSize()));
        }).subscribeOn(Schedulers.boundedElastic());
    }
}
