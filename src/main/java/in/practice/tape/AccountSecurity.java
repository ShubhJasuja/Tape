package in.practice.tape;

import jakarta.servlet.DispatcherType;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.web.server.WebServerFactoryCustomizer;
import org.springframework.boot.web.servlet.server.ConfigurableServletWebServerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import org.springframework.security.web.context.SecurityContextHolderFilter;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.InetAddress;

@Configuration(proxyBeanMethods = false)
@Profile("accounts")
class AccountSecurity {
    @Bean
    PasswordEncoder accountPasswordEncoder() { return new BCryptPasswordEncoder(12); }

    @Bean
    HealthIndicator identityDelivery(IdentityActions identities, Environment environment) {
        return () -> environment.acceptsProfiles(Profiles.of("accounts-local")) || identities.ready()
            ? Health.up().build() : Health.down().build();
    }

    @Bean
    HealthIndicator accountStorage(JdbcTemplate db) {
        return () -> {
            try {
                Boolean usable = db.queryForObject("""
                    SELECT bool_and(coalesce(has_table_privilege(to_regclass(table_name), permission), false))
                        AND has_table_privilege('identity_actions','SELECT')
                        AND has_table_privilege('identity_actions','INSERT')
                        AND has_table_privilege('identity_actions','DELETE')
                        AND has_column_privilege('local_credentials','password_hash','UPDATE')
                        AND has_column_privilege('local_credentials','verified_at','UPDATE')
                        AND has_column_privilege('local_credentials','auth_version','UPDATE')
                    FROM (VALUES ('tape_http_session'), ('tape_http_session_attributes'), ('auth_request_budget'), ('identity_mail')) AS tables(table_name)
                    CROSS JOIN (VALUES ('SELECT'), ('INSERT'), ('UPDATE'), ('DELETE')) AS required(permission)
                    """, Boolean.class);
                return Boolean.TRUE.equals(usable) ? Health.up().build() : Health.down().build();
            } catch (org.springframework.dao.DataAccessException failure) {
                return Health.down().build();
            }
        };
    }

    @Bean
    @Profile("accounts-local")
    WebServerFactoryCustomizer<ConfigurableServletWebServerFactory> accountLocalLoopback() {
        return server -> server.setAddress(InetAddress.getLoopbackAddress());
    }

    @Bean
    SecurityFilterChain accountsFilterChain(HttpSecurity http, AccountDirectory directory,
                                        PasswordEncoder encoder, Environment environment, AuthRequestBudget authBudget,
                                        TradingMetrics metrics) throws Exception {
        boolean local = environment.acceptsProfiles(Profiles.of("accounts-local"));
        if (local && environment.acceptsProfiles(Profiles.of("beta")))
            throw new IllegalStateException("Private beta cannot use local authentication or insecure cookies");
        if (environment.acceptsProfiles(Profiles.of("h2-legacy", "postgres-dev"))) {
            throw new IllegalStateException("Select accounts without a legacy database profile.");
        }
        if (!local && !environment.getProperty("server.servlet.session.cookie.secure", Boolean.class, false)) {
            throw new IllegalStateException("The accounts profile requires Secure session cookies.");
        }
        if (!local) {
            http.addFilterBefore(new OncePerRequestFilter() {
                @Override
                protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                    throws ServletException, IOException {
                    if (request.isSecure()) chain.doFilter(request, response);
                    else response.setStatus(403);
                }
            }, SecurityContextHolderFilter.class);
        }
        var provider = new DaoAuthenticationProvider(directory::load);
        provider.setPasswordEncoder(encoder);
        provider.setPreAuthenticationChecks(details -> {});
        provider.setPostAuthenticationChecks(details -> {
            if (!details.isEnabled()) throw new org.springframework.security.authentication.BadCredentialsException("Invalid credentials");
        });
        return http
            .authenticationProvider(provider)
            .headers(headers -> headers.referrerPolicy(policy -> policy.policy(
                org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER)))
            .requestCache(cache -> cache.disable())
            .authorizeHttpRequests(auth -> auth
                .dispatcherTypeMatchers(DispatcherType.ERROR).permitAll()
                .requestMatchers(HttpMethod.GET, "/", "/index.html", "/assets/**", "/api/v1/auth/csrf").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/v1/auth/register", "/api/v1/auth/login", "/api/v1/auth/verification",
                    "/api/v1/auth/recovery", "/api/v1/auth/verify", "/api/v1/auth/reset").permitAll()
                .requestMatchers(HttpMethod.POST, "/api/v1/auth/sessions/revoke").authenticated()
                .requestMatchers(HttpMethod.GET, "/api/v1/auth/me", "/api/v1/accounts", "/api/v1/accounts/*").authenticated()
                .requestMatchers(HttpMethod.GET, "/api/v1/accounts/*/orders", "/api/v1/accounts/*/orders/*",
                    "/api/v1/accounts/*/order-requests/*", "/api/v1/accounts/*/wallet", "/api/v1/accounts/*/portfolio",
                    "/api/v1/accounts/*/market", "/api/v1/accounts/*/candles",
                    "/api/v1/accounts/*/instruments", "/api/v1/accounts/*/instruments/*").authenticated()
                .requestMatchers(HttpMethod.POST, "/api/v1/accounts/*/orders").authenticated()
                .requestMatchers(HttpMethod.GET, "/api/v1/accounts/*/options", "/api/v1/accounts/*/futures").authenticated()
                .requestMatchers(HttpMethod.POST, "/api/v1/accounts/*/options/orders", "/api/v1/accounts/*/futures/orders").authenticated()
                .requestMatchers(HttpMethod.DELETE, "/api/v1/accounts/*/options/orders/*", "/api/v1/accounts/*/futures/orders/*").authenticated()
                .requestMatchers(HttpMethod.GET, "/api/v1/market-data/status", "/api/v1/market-data/instruments", "/api/v1/market-data/instrument",
                    "/api/v1/market-data/indices", "/api/v1/market-data/futures", "/api/v1/market-data/options",
                    "/api/v1/market-data/option-chain", "/api/v1/market-data/quote", "/api/v1/market-data/exchange-status",
                    "/api/v1/market-data/candles", "/api/v1/market-data/intraday", "/api/v1/market-data/market-session",
                    "/api/v1/market-data/stream", "/api/v1/market-data/streams", "/api/v1/market-data/watchlist",
                    "/api/v1/market-data/watchlists").authenticated()
                .requestMatchers(HttpMethod.POST, "/api/v1/market-data/watchlists").authenticated()
                .requestMatchers(HttpMethod.PUT, "/api/v1/market-data/watchlist").authenticated()
                .requestMatchers(HttpMethod.DELETE, "/api/v1/market-data/watchlist", "/api/v1/market-data/watchlists").authenticated()
                .requestMatchers(HttpMethod.DELETE, "/api/v1/accounts/*/orders/*").authenticated()
                .requestMatchers(HttpMethod.PUT, "/api/v1/accounts/*/watchlist").authenticated()
                .anyRequest().denyAll())
            .exceptionHandling(errors -> errors
                .authenticationEntryPoint((request, response, failure) -> response.setStatus(401))
                .accessDeniedHandler((request, response, failure) -> response.setStatus(403)))
            .sessionManagement(session -> session.sessionFixation(fixation -> fixation.changeSessionId()))
            .formLogin(form -> form.loginPage("/").loginProcessingUrl("/api/v1/auth/login")
                .usernameParameter("email")
                .successHandler((request, response, authentication) -> response.setStatus(204))
                .failureHandler((request, response, failure) -> response.setStatus(401)))
            .logout(logout -> logout.logoutUrl("/api/v1/auth/logout").deleteCookies("JSESSIONID")
                .logoutSuccessHandler((request, response, authentication) -> response.setStatus(204)))
            .addFilterBefore(new OncePerRequestFilter() {
                @Override protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
                    throws ServletException, IOException {
                    var context = org.springframework.security.core.context.SecurityContextHolder.getContext();
                    if (context.getAuthentication() != null &&
                        context.getAuthentication().getPrincipal() instanceof AccountDirectory.Principal principal) {
                        try {
                            if (!directory.current(principal)) {
                                var session = request.getSession(false);
                                if (session != null) session.invalidate();
                                org.springframework.security.core.context.SecurityContextHolder.clearContext();
                                response.setStatus(401);
                                return;
                            }
                        } catch (org.springframework.dao.DataAccessException failure) {
                            response.setStatus(503);
                            return;
                        }
                    }
                    chain.doFilter(request, response);
                }
            }, UsernamePasswordAuthenticationFilter.class)
            .addFilterBefore(new AuthAttempts(local ? null : authBudget, metrics), UsernamePasswordAuthenticationFilter.class)
            .build();
    }
}
