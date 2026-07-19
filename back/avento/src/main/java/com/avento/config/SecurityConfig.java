package com.avento.config;

import com.avento.api.ApiCodes;
import com.avento.api.ApiErrorResponses;
import com.avento.auth.config.AuthProperties;
import com.avento.auth.security.JwtCookieAuthenticationFilter;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;
import org.springframework.web.filter.OncePerRequestFilter;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final AuthProperties authProperties;

    private final JwtCookieAuthenticationFilter jwtCookieAuthenticationFilter;

    private final ObjectMapper objectMapper;

    @Value("${avento.security.allow-non-loopback:false}")
    private boolean allowNonLoopback;

    public SecurityConfig(
            AuthProperties authProperties,
            JwtCookieAuthenticationFilter jwtCookieAuthenticationFilter,
            ObjectMapper objectMapper) {
        this.authProperties = authProperties;
        this.jwtCookieAuthenticationFilter = jwtCookieAuthenticationFilter;
        this.objectMapper = objectMapper;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http.cors(cors -> cors.configurationSource(corsConfigurationSource()));

        if (authProperties.isEnabled()) {
            http.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                    .csrf(AbstractHttpConfigurer::disable)
                    .exceptionHandling(exception -> exception
                            .authenticationEntryPoint((request, response, authException) -> writeSecurityError(
                                    request,
                                    response,
                                    HttpServletResponse.SC_UNAUTHORIZED,
                                    ApiCodes.UNAUTHORIZED,
                                    authException.getMessage()))
                            .accessDeniedHandler((request, response, accessDeniedException) -> writeSecurityError(
                                    request,
                                    response,
                                    HttpServletResponse.SC_FORBIDDEN,
                                    ApiCodes.FORBIDDEN,
                                    accessDeniedException.getMessage())))
                    .addFilterBefore(jwtCookieAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
                    .authorizeHttpRequests(auth -> auth.dispatcherTypeMatchers(
                                    DispatcherType.ASYNC, DispatcherType.ERROR)
                            .permitAll()
                            .requestMatchers("/", "/docs.html", "/assets/**", "/favicon.svg", "/avento-logo.svg")
                            .permitAll()
                            .requestMatchers(HttpMethod.GET, "/api/health")
                            .permitAll()
                            .requestMatchers(
                                    HttpMethod.POST,
                                    "/api/auth/bootstrap",
                                    "/api/auth/login",
                                    "/api/auth/refresh",
                                    "/api/auth/logout")
                            .permitAll()
                            .anyRequest()
                            .authenticated());
        } else {
            http.csrf(AbstractHttpConfigurer::disable)
                    .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
        }

        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOriginPatterns(List.of("http://localhost:*", "http://127.0.0.1:*", "http://[::1]:*"));
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS", "PATCH"));
        configuration.setAllowedHeaders(List.of("*"));
        configuration.setAllowCredentials(true);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        // Apply CORS configuration to all endpoints
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }

    @Bean
    public FilterRegistrationBean<OncePerRequestFilter> loopbackOnlyFilter() {
        FilterRegistrationBean<OncePerRequestFilter> registration = new FilterRegistrationBean<>();
        registration.setFilter(new OncePerRequestFilter() {
            @Override
            protected void doFilterInternal(
                    HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
                    throws ServletException, IOException {
                if (allowNonLoopback || isLoopback(request.getRemoteAddr())) {
                    filterChain.doFilter(request, response);
                    return;
                }

                writeSecurityError(
                        request,
                        response,
                        HttpServletResponse.SC_FORBIDDEN,
                        ApiCodes.FORBIDDEN,
                        "Avento only accepts local requests by default.");
            }
        });
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 1);
        return registration;
    }

    private void writeSecurityError(
            HttpServletRequest request, HttpServletResponse response, int status, String code, String message)
            throws IOException {
        if (response.isCommitted()) {
            return;
        }

        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(
                response.getWriter(), ApiErrorResponses.body(request, HttpStatus.valueOf(status), code, message));
    }

    private boolean isLoopback(String remoteAddress) {
        return remoteAddress != null
                && (remoteAddress.equals("::1")
                        || remoteAddress.equals("0:0:0:0:0:0:0:1")
                        || remoteAddress.equals("127.0.0.1")
                        || remoteAddress.startsWith("127.")
                        || remoteAddress.equals("localhost"));
    }
}
