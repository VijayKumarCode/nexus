package com.vk.gaming.nexus.config;

import com.vk.gaming.nexus.repository.UserRepository;
import com.vk.gaming.nexus.service.JwtService;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
@RequiredArgsConstructor
@Slf4j
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtService jwtService;
    private final UserRepository userRepository;

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        String path = request.getRequestURI();
        return path.startsWith("/api/feedback")
                || path.startsWith("/api/recovery")
                || path.startsWith("/api/users/login")
                || path.startsWith("/api/users/register")
                || path.startsWith("/api/users/activate")
                || path.startsWith("/api/users/resend-activation")
                || path.startsWith("/api/users/check-username")
                || path.startsWith("/api/users/health")
                || path.startsWith("/game-websocket");
    }

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain
    ) throws ServletException, IOException {
        final String authHeader = request.getHeader("Authorization");

        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            filterChain.doFilter(request, response);
            return;
        }

        final String jwt = authHeader.substring(7);
        final String username;

        try {
            username = jwtService.extractUsername(jwt);
        } catch (Exception e) {
            log.warn("Failed to extract username from JWT: {}", e.getMessage());
            filterChain.doFilter(request, response);
            return;
        }

        if (username != null && SecurityContextHolder.getContext().getAuthentication() == null) {
            try {
                var user = userRepository.findByUsername(username)
                        .orElse(null);

                if (user == null) {
                    filterChain.doFilter(request, response);
                    return;
                }

                if (!user.isEnabled()) {
                    log.warn("Authentication attempted for disabled user: {}", username);
                    filterChain.doFilter(request, response);
                    return;
                }

                if (jwtService.validateToken(jwt, user.getUsername())) {
                    UsernamePasswordAuthenticationToken authToken =
                            new UsernamePasswordAuthenticationToken(
                                    user.getUsername(),
                                    null,
                                    java.util.List.of()
                            );
                    authToken.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                    SecurityContextHolder.getContext().setAuthentication(authToken);
                    log.debug("Authenticated user: {}, URI: {}", username, request.getRequestURI());
                } else {
                    log.warn("Invalid JWT token for user: {}", username);
                }
            } catch (Exception e) {
                log.warn("JWT authentication failed for user '{}': {}", username, e.getMessage());
            }
        }

        filterChain.doFilter(request, response);
    }
}
