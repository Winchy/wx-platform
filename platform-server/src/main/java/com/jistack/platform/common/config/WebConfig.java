package com.jistack.platform.common.config;

import com.jistack.platform.module.system.AuthInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@RequiredArgsConstructor
public class WebConfig implements WebMvcConfigurer {

    private final AuthInterceptor authInterceptor;

    /** 逗号分隔的跨域来源；留空表示同源部署，不注册 CORS 映射 */
    @Value("${platform.cors.allowed-origin-patterns:}")
    private String allowedOriginPatterns;

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(authInterceptor)
                .addPathPatterns("/api/**")
                .excludePathPatterns("/api/auth/login");
    }

    @Override
    public void addCorsMappings(CorsRegistry registry) {
        // 同源部署（dev 走 vite proxy、生产走 Nginx 反代）下浏览器依然会在 POST 上带 Origin，
        // 此时任何一条不匹配的规则都会让 Spring 直接回 403 Invalid CORS request，
        // 所以默认不注册；只有前端与接口不同源时才通过配置显式放开来源。
        if (!StringUtils.hasText(allowedOriginPatterns)) {
            return;
        }
        registry.addMapping("/api/**")
                .allowedOriginPatterns(StringUtils.commaDelimitedListToStringArray(allowedOriginPatterns))
                .allowedMethods("GET", "POST", "PUT", "DELETE", "OPTIONS")
                .allowedHeaders("*")
                .allowCredentials(false)
                .maxAge(3600);
    }
}
