package in.practice.tape;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@Configuration(proxyBeanMethods = false)
@Profile("accounts")
class OperationsSecurity {
    @Bean
    @Order(1)
    SecurityFilterChain operationsFilterChain(HttpSecurity http,
                                              @Value("${tape.ops.token:}") String token) throws Exception {
        if (!token.isEmpty() && !token.matches("[A-Za-z0-9_-]{32,128}")) {
            throw new IllegalArgumentException("Invalid Tape operations token format");
        }
        return http.securityMatcher("/actuator/**", "/internal/ops/**")
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .requestCache(cache -> cache.disable())
            // This chain accepts only an explicit bearer token, never browser session credentials.
            .csrf(csrf -> csrf.disable())
            .addFilterBefore(new OncePerRequestFilter() {
                @Override
                protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                    throws ServletException, IOException {
                    String supplied = request.getHeader("Authorization");
                    if (token.isEmpty() || !InetAddress.getByName(request.getRemoteAddr()).isLoopbackAddress()
                        || supplied == null || !MessageDigest.isEqual(
                            ("Bearer " + token).getBytes(StandardCharsets.UTF_8), supplied.getBytes(StandardCharsets.UTF_8))) {
                        response.setStatus(403);
                        return;
                    }
                    chain.doFilter(request, response);
                }
            }, SecurityContextHolderFilter.class)
            .authorizeHttpRequests(auth -> auth
                .requestMatchers(HttpMethod.GET, "/actuator/health/liveness", "/actuator/health/readiness",
                    "/actuator/metrics", "/actuator/metrics/*").permitAll()
                .requestMatchers(HttpMethod.POST, "/internal/ops/reconcile").permitAll()
                .anyRequest().denyAll())
            .build();
    }
}
