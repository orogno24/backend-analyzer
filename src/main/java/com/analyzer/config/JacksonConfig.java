package com.analyzer.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * Jackson ObjectMapper 설정
 */
@Configuration
public class JacksonConfig {

    /**
     * 전역 ObjectMapper 빈 설정
     */
    @Bean
    @Primary
    public ObjectMapper objectMapper() {
        ObjectMapper mapper = new ObjectMapper();

        // Java 8 날짜/시간 지원
        mapper.registerModule(new JavaTimeModule());

        // 날짜를 ISO-8601 문자열로 직렬화 (타임스탬프 대신)
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        // null 값 제외하지 않음 (명시적 표현)
        // mapper.setSerializationInclusion(JsonInclude.Include.NON_NULL);

        // 알 수 없는 속성 무시 (역직렬화 시 유연성)
        // mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

        return mapper;
    }
}
