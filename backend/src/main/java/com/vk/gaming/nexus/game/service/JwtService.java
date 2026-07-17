package com.vk.gaming.nexus.game.service;

import com.vk.gaming.nexus.game.config.AppProperties;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import io.jsonwebtoken.security.WeakKeyException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.UUID;
import java.util.function.Function;

@Service
@Slf4j
public class JwtService {

    private static final int MINIMUM_SECRET_LENGTH = 32;

    private final AppProperties appProperties;

    public JwtService(AppProperties appProperties) {
        this.appProperties = appProperties;
        validateSecretLength();
    }

    private void validateSecretLength() {
        String secret = appProperties.getJwtSecret();
        if (secret == null || secret.length() < MINIMUM_SECRET_LENGTH) {
            throw new IllegalStateException(
                    "JWT secret must be at least " + MINIMUM_SECRET_LENGTH + " characters. " +
                            "Current length: " + (secret == null ? 0 : secret.length())
            );
        }
    }

    private SecretKey getSigningKey() {
        byte[] keyBytes = appProperties.getJwtSecret().getBytes(StandardCharsets.UTF_8);
        try {
            return Keys.hmacShaKeyFor(keyBytes);
        } catch (WeakKeyException e) {
            log.error("JWT secret is too weak for HMAC-SHA. Ensure secret is at least 256 bits.");
            throw new IllegalStateException("JWT secret is too weak. Use a secret of at least 32 characters.", e);
        }
    }

    public String generateToken(String username) {
        long expirationMs = appProperties.getJwtExpirationMs();
        return Jwts.builder()
                .id(UUID.randomUUID().toString())
                .subject(username)
                .issuedAt(new Date(System.currentTimeMillis()))
                .expiration(new Date(System.currentTimeMillis() + expirationMs))
                .signWith(getSigningKey())
                .compact();
    }

    public String extractUsername(String token) {
        return extractClaim(token, Claims::getSubject);
    }

    public Date extractExpiration(String token) {
        return extractClaim(token, Claims::getExpiration);
    }

    public String extractTokenId(String token) {
        return extractClaim(token, Claims::getId);
    }

    public <T> T extractClaim(String token, Function<Claims, T> claimsResolver) {
        final Claims claims = extractAllClaims(token);
        return claimsResolver.apply(claims);
    }

    private Claims extractAllClaims(String token) {
        return Jwts.parser()
                .verifyWith(getSigningKey())
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }

    private Boolean isTokenExpired(String token) {
        return extractExpiration(token).before(new Date());
    }

    public Boolean validateToken(String token, String username) {
        try {
            final String extractedUsername = extractUsername(token);
            return extractedUsername.equals(username) && !isTokenExpired(token);
        } catch (io.jsonwebtoken.ExpiredJwtException e) {
            log.warn("JWT token expired: {}", e.getMessage());
            return false;
        } catch (io.jsonwebtoken.UnsupportedJwtException e) {
            log.warn("Unsupported JWT token: {}", e.getMessage());
            return false;
        } catch (io.jsonwebtoken.MalformedJwtException e) {
            log.warn("Malformed JWT token: {}", e.getMessage());
            return false;
        } catch (io.jsonwebtoken.security.SignatureException e) {
            log.warn("Invalid JWT signature: {}", e.getMessage());
            return false;
        } catch (IllegalArgumentException e) {
            log.warn("JWT token is empty or null");
            return false;
        }
    }
}
