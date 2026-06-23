package com.vk.gaming.nexus.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;

@Configuration
public class AppConfiguration {

    @Bean
    public RestTemplate restTemplate(RestTemplateBuilder builder) {
        return builder
                // FIX: Spring Boot 3.4+ — replaced deprecated setConnectTimeout/setReadTimeout
                .connectTimeout(Duration.ofSeconds(10))   // was: setConnectTimeout
                .readTimeout(Duration.ofSeconds(30))      // was: setReadTimeout
                .build();
    }

    @Bean
    public ObjectMapper objectMapper() {
        return new ObjectMapper();
    }
}
