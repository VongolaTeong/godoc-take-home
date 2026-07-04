package com.godoc.consult.common.web;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.CorsRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * CORS for cross-origin UI deployments — the frontend on Cloudflare Pages calling the
 * API on Render. Off by default: with no origins configured there are no CORS mappings,
 * and the same-origin setup (UI served from the jar) works without any of this.
 */
@Configuration
public class CorsConfig {

    @Bean
    public WebMvcConfigurer corsConfigurer(
            @Value("${app.cors.allowed-origins:}") String allowedOrigins) {
        return new WebMvcConfigurer() {
            @Override
            public void addCorsMappings(CorsRegistry registry) {
                if (allowedOrigins.isBlank()) {
                    return;
                }
                registry.addMapping("/api/**")
                        .allowedOrigins(allowedOrigins.split("\\s*,\\s*"))
                        .allowedMethods("GET", "POST")
                        .allowedHeaders("Content-Type", "X-Patient-Id", "Idempotency-Key")
                        .maxAge(3600);
            }
        };
    }
}
