package com.vk.gaming.nexus.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import java.util.List;

@ConfigurationProperties(prefix = "app")
@Validated
public class AppProperties {

    @NotBlank(message = "app.base-url must be configured")
    private String baseUrl;

    @NotBlank(message = "app.mail-from must be configured")
    private String mailFrom;

    private List<String> allowedOrigins;

    @NotBlank(message = "app.jwt-secret must be configured (minimum 32 characters)")
    private String jwtSecret;

    @Min(value = 3600000, message = "JWT expiration must be at least 1 hour")
    private long jwtExpirationMs = 86400000;

    public String getBaseUrl() {
        return baseUrl;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String getMailFrom() {
        return mailFrom;
    }

    public void setMailFrom(String mailFrom) {
        this.mailFrom = mailFrom;
    }

    public List<String> getAllowedOrigins() {
        return allowedOrigins;
    }

    public void setAllowedOrigins(List<String> allowedOrigins) {
        this.allowedOrigins = allowedOrigins;
    }

    public String getJwtSecret() {
        return jwtSecret;
    }

    public void setJwtSecret(String jwtSecret) {
        this.jwtSecret = jwtSecret;
    }

    public long getJwtExpirationMs() {
        return jwtExpirationMs;
    }

    public void setJwtExpirationMs(long jwtExpirationMs) {
        this.jwtExpirationMs = jwtExpirationMs;
    }
}
