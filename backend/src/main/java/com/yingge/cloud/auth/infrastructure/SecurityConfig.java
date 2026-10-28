package com.yingge.cloud.auth.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.yingge.cloud.shared.api.ApiError;
import com.yingge.cloud.shared.api.TraceIdFilter;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.authentication.configuration.AuthenticationConfiguration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.argon2.Argon2PasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfFilter;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import com.yingge.cloud.mcp.security.McpBearerAuthenticationFilter;
import com.yingge.cloud.mobile.security.MobileBearerAuthenticationFilter;
import com.yingge.cloud.mobile.security.MobileWebSessionScopeFilter;

import java.time.Instant;
import java.util.List;

@Configuration
@EnableMethodSecurity
public class SecurityConfig {
    @Bean
    FilterRegistrationBean<McpBearerAuthenticationFilter> disableMcpBearerServletRegistration(
            McpBearerAuthenticationFilter filter) {
        FilterRegistrationBean<McpBearerAuthenticationFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    @Bean
    FilterRegistrationBean<MobileBearerAuthenticationFilter> disableMobileBearerServletRegistration(
            MobileBearerAuthenticationFilter filter) {
        FilterRegistrationBean<MobileBearerAuthenticationFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    @Bean
    FilterRegistrationBean<MobileWebSessionScopeFilter> disableMobileWebScopeServletRegistration(
            MobileWebSessionScopeFilter filter) {
        FilterRegistrationBean<MobileWebSessionScopeFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setEnabled(false);
        return registration;
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return Argon2PasswordEncoder.defaultsForSpringSecurity_v5_8();
    }

    @Bean
    AuthenticationManager authenticationManager(AuthenticationConfiguration configuration) throws Exception {
        return configuration.getAuthenticationManager();
    }

    @Bean
    HttpSessionSecurityContextRepository securityContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }

    @Bean
    SecurityFilterChain securityFilterChain(
            HttpSecurity http,
            ObjectMapper objectMapper,
            Environment environment,
            HttpSessionSecurityContextRepository repository,
            McpBearerAuthenticationFilter mcpBearerAuthenticationFilter,
            MobileBearerAuthenticationFilter mobileBearerAuthenticationFilter,
            MobileWebSessionScopeFilter mobileWebSessionScopeFilter
    ) throws Exception {
        var csrfRepository = CookieCsrfTokenRepository.withHttpOnlyFalse();
        csrfRepository.setCookieName("XSRF-TOKEN");
        csrfRepository.setHeaderName("X-XSRF-TOKEN");
        var requestHandler = new CsrfTokenRequestAttributeHandler();
        requestHandler.setCsrfRequestAttributeName(null);
        org.springframework.security.web.util.matcher.RequestMatcher mobileBearerRequest = request -> {
            String authorization = request.getHeader("Authorization");
            return request.getRequestURI().startsWith("/api/v1/") && authorization != null
                    && authorization.regionMatches(true, 0, "Bearer ", 0, 7);
        };
        boolean production = environment.matchesProfiles("prod");

        http
                .securityContext(context -> context.securityContextRepository(repository).requireExplicitSave(true))
                .csrf(csrf -> csrf
                        .csrfTokenRepository(csrfRepository)
                        .csrfTokenRequestHandler(requestHandler)
                        .ignoringRequestMatchers("/mcp", "/mcp/**", "/oauth2/register", "/oauth2/token", "/oauth2/revoke",
                                "/api/v1/mobile/auth/login", "/api/v1/mobile/auth/refresh",
                                "/api/v1/mobile/auth/registration-options", "/api/v1/mobile/auth/register",
                                "/api/v1/mobile/web-sessions/exchange")
                        .ignoringRequestMatchers(mobileBearerRequest))
                .cors(cors -> cors.disable())
                .httpBasic(httpBasic -> httpBasic.disable())
                .formLogin(form -> form.disable())
                .logout(logout -> logout.disable())
                .requestCache(cache -> cache.disable())
                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.IF_REQUIRED)
                        .sessionFixation(fixation -> fixation.migrateSession())
                        .maximumSessions(5))
                .authorizeHttpRequests(authorize -> {
                    authorize.requestMatchers(
                            "/actuator/health", "/actuator/health/**",
                            "/api/v1/auth/csrf", "/api/v1/auth/login", "/api/v1/auth/register",
                            "/api/v1/auth/registration-options",
                            "/api/v1/auth/feishu/options", "/api/v1/auth/feishu/start",
                            "/api/v1/auth/feishu/callback", "/api/v1/auth/feishu/binding/confirm",
                            "/api/v1/mobile/auth/login", "/api/v1/mobile/auth/refresh",
                            "/api/v1/mobile/auth/registration-options", "/api/v1/mobile/auth/register",
                            "/api/v1/mobile/web-sessions/exchange",
                            "/api/v1/forum/public/**",
                            "/.well-known/oauth-protected-resource/**",
                            "/.well-known/oauth-authorization-server",
                            "/oauth2/register", "/oauth2/authorize", "/oauth2/token", "/oauth2/revoke"
                    ).permitAll();
                    if (production) {
                        authorize.requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui").hasRole("ORG_ADMIN");
                    } else {
                        authorize.requestMatchers("/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui").permitAll();
                    }
                    authorize.anyRequest().authenticated();
                })
                .addFilterBefore(mcpBearerAuthenticationFilter, AuthorizationFilter.class)
                .addFilterBefore(mobileBearerAuthenticationFilter, AuthorizationFilter.class)
                .addFilterBefore(mobileWebSessionScopeFilter, CsrfFilter.class)
                .exceptionHandling(exceptions -> exceptions
                        .authenticationEntryPoint((request, response, exception) -> writeError(
                                objectMapper, request, response, HttpServletResponse.SC_UNAUTHORIZED, "UNAUTHENTICATED", "请先登录"))
                        .accessDeniedHandler((request, response, exception) -> writeError(
                                objectMapper, request, response, HttpServletResponse.SC_FORBIDDEN, "FORBIDDEN", "没有执行该操作的权限")))
                .headers(headers -> headers
                        .contentTypeOptions(Customizer.withDefaults())
                        .frameOptions(frame -> frame.sameOrigin())
                        .contentSecurityPolicy(policy -> policy.policyDirectives(
                                "default-src 'self'; script-src 'self' 'unsafe-inline'; style-src 'self' 'unsafe-inline'; "
                                + "img-src 'self' data: blob:; media-src 'self' blob:; "
                                + "connect-src 'self' ws: wss:; frame-src 'self'; frame-ancestors 'self'; "
                                + "font-src 'self' data:; object-src 'none'; base-uri 'self'"))
                        .referrerPolicy(referrer -> referrer.policy(
                                org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy.STRICT_ORIGIN_WHEN_CROSS_ORIGIN)));
        return http.build();
    }

    private void writeError(ObjectMapper mapper, jakarta.servlet.http.HttpServletRequest request,
                            HttpServletResponse response, int status, String code, String message)
            throws java.io.IOException {
        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        Object trace = request.getAttribute(TraceIdFilter.TRACE_ID_ATTRIBUTE);
        mapper.writeValue(response.getOutputStream(), new ApiError(code, message, List.of(),
                trace == null ? "unknown" : trace.toString(), Instant.now()));
    }
}
