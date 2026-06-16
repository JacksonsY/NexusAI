package com.nexusai.common.event;

/**
 * 配置变更事件（网关内进程级缓存失效信号）。
 * <p>定位：解耦"谁监听到变更"与"谁持有缓存"（relay 的 token 缓存、router 的 RouteCache 等），避免 L2 反依赖 L3。用 Spring 标准 {@code ApplicationEvent}
 * （POJO 事件，无需继承 ApplicationEvent，故本类放 common·L1 无 spring-context 依赖）同步广播到本节点各缓存 Bean。
 *
 * @param version 变更版本号（无全局语义，仅作日志/排序提示）
 * @param scope   失效域
 * @author nexusai
 */
public record ConfigChangedEvent(long version, Scope scope) {

    public enum Scope {
        /** 全部失效 */
        ALL,
        /** 配置域：路由/模型/渠道等本地缓存失效 */
        CONFIG,
        /** Token 域：API Key 鉴权缓存，由 Key 同步/吊销触发 */
        TOKEN
    }

    public static ConfigChangedEvent config(long version) {
        return new ConfigChangedEvent(version, Scope.CONFIG);
    }

    public static ConfigChangedEvent token(long version) {
        return new ConfigChangedEvent(version, Scope.TOKEN);
    }

    /** 该事件是否命中目标域（含 ALL 全域） */
    public boolean hits(Scope target) {
        return scope == Scope.ALL || scope == target;
    }
}
