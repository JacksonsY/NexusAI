package com.nexusai.config;

import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * HTTP 响应 Jackson 定制：<b>Long 全局序列化为字符串</b>。
 * <p>雪花 id（nai_project/nai_token/nai_channel… ASSIGN_ID 19 位）超过 JS {@code Number.MAX_SAFE_INTEGER}（2^53），
 * 以 number 下发到前端会精度丢失（末几位被抹 → 前端拿错 id 回查 404"不存在"）。统一转字符串下发，
 * 前端以 string 持有、拼 URL/比对天然无损。仅影响 Spring 响应编码器；管线内部 {@code JsonUtils} 不受影响。
 *
 * @author nexusai
 */
@Configuration
public class JacksonConfig {

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer longToStringCustomizer() {
        return builder -> builder
                .serializerByType(Long.class, ToStringSerializer.instance)
                .serializerByType(Long.TYPE, ToStringSerializer.instance);
    }
}
