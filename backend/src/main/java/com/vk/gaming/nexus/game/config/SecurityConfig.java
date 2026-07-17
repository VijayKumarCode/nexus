package com.vk.gaming.nexus.game.config;

import com.vk.gaming.nexus.game.repository.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

/**
 * Spring Security 6.4+ Configuration
 *
 * Modern approach: expose UserDetailsService and PasswordEncoder as beans.
 * Spring Boot auto-configures DaoAuthenticationProvider internally.
 * No manual ProviderManager or DaoAuthenticationProvider instantiation needed.
 */
@Configuration
@EnableWebSecurity
@Slf4j
public class SecurityConfig {

    private final AppProperties appProperties;
    private final JwtAuthenticationFilter jwtAuthFilter;
    private final UserRepository userRepository;

    public SecurityConfig(AppProperties appProperties, JwtAuthenticationFilter jwtAuthFilter, UserRepository userRepository) {
        this.appProperties = appProperties;
        this.jwtAuthFilter = jwtAuthFilter;
        this.userRepository = userRepository;
    }

    /**
     * UserDetailsService bean — Spring Boot auto-configures DaoAuthenticationProvider using this.
     */
    @Bean
    public UserDetailsService userDetailsService() {
        return username -> userRepository.findByUsername(username)
                .map(user -> org.springframework.security.core.userdetails.User
                        .withUsername(user.getUsername())
                        .password(user.getPassword())
                        .disabled(!user.isEnabled())
                        .authorities("ROLE_USER")
                        .build())
                .orElseThrow(() -> new UsernameNotFoundException("User not found: " + username));
    }

    /**
     * PasswordEncoder bean — Spring Boot auto-configures DaoAuthenticationProvider using this.
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    /**
     * AuthenticationManager via AuthenticationConfiguration.
     * Spring Boot internally uses the auto-configured DaoAuthenticationProvider
     * backed by our UserDetailsService and PasswordEncoder beans.
     */
    @Bean
    public AuthenticationManager authenticationManager(AuthenticationConfiguration authenticationConfiguration) throws Exception {
        return authenticationConfiguration.getAuthenticationManager();
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .csrf(AbstractHttpConfigurer::disable)
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(new AntPathRequestMatcher("/**", "OPTIONS")).permitAll()
                        .requestMatchers("/api/users/login", "/api/users/register",
                                "/api/users/activate", "/api/users/resend-activation").permitAll()
                        .requestMatchers("/api/users/check-username").permitAll()
                        .requestMatchers("/api/users/health").permitAll()
                        .requestMatchers("/api/recovery/**").permitAll()
                        .requestMatchers("/api/feedback", "/api/feedback/**").permitAll()
                        .requestMatchers("/game-websocket/**").permitAll()
                        .anyRequest().authenticated()
                )
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();

        List<String> origins = appProperties.getAllowedOrigins();
        if (origins == null || origins.isEmpty()) {
            log.warn("CRITICAL SECURITY WARNING: app.allowed-origins is empty. Using production-safe fallback.");
            origins = List.of("https://nexusgame.space", "https://www.nexusgame.space");
        }

        config.setAllowedOrigins(origins);
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS", "PATCH"));
        config.setAllowedHeaders(List.of("*"));
        config.setAllowCredentials(true);
        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }
}
