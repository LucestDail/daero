package com.daero.web;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 오픈 API 이므로 CORS 전체 허용(인증 없음). 정적 페이지는 캐시 비활성(개발 중 갱신 즉시 반영).
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        // 공개 읽기 전용 API — 모든 origin 허용, GET만(모든 엔드포인트가 GET이라 POST 불필요).
        registry.addMapping("/api/**").allowedOrigins("*").allowedMethods("GET");
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler("/**")
                .addResourceLocations("classpath:/static/")
                .setCachePeriod(0); // 브라우저 캐시로 인한 옛 UI 방지
    }
}
