package com.nexusai.persistence.log.mysql;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * 日志存储 driver 选号校验（评审 LOW）：{@code nexusai.log.store} 若被显式配成非法非空值，
 * 两个 driver 的 @ConditionalOnProperty 都不匹配 → RequestLogStore 零 bean → 下游硬注入启动崩且错误晦涩。
 * <p>本校验器无条件装配（log-mysql 始终在 classpath），构造期 fail-fast 给出可读文案；
 * 取值大小写不敏感（与 @ConditionalOnProperty 的 equalsIgnoreCase 行为一致）；缺省（不配）走 mysql 不报错。
 *
 * @author nexusai
 */
@Component
public class LogStoreValidator {

    private static final Set<String> VALID = Set.of("mysql", "pg", "clickhouse");

    public LogStoreValidator(@Value("${nexusai.log.store:mysql}") String store) {
        if (store == null || !VALID.contains(store.trim().toLowerCase())) {
            throw new IllegalStateException("nexusai.log.store 非法值 '" + store
                    + "'，仅支持 mysql（默认）/ pg（PostgreSQL）/ clickhouse");
        }
    }
}
