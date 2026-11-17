package in.practice.tape;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.session.jdbc.PostgreSqlJdbcIndexedSessionRepositoryCustomizer;
import org.springframework.session.jdbc.config.annotation.web.http.EnableJdbcHttpSession;
import org.springframework.session.web.http.DefaultCookieSerializer;

@Configuration(proxyBeanMethods = false)
@Profile("accounts & !accounts-local")
@EnableJdbcHttpSession(tableName = "TAPE_HTTP_SESSION", maxInactiveIntervalInSeconds = 1200)
class AccountSessions {
    @Bean
    PostgreSqlJdbcIndexedSessionRepositoryCustomizer postgresSessionQueries() {
        return new PostgreSqlJdbcIndexedSessionRepositoryCustomizer();
    }

    @Bean
    DefaultCookieSerializer accountSessionCookies() {
        var cookies = new DefaultCookieSerializer();
        cookies.setCookieName("JSESSIONID");
        cookies.setCookiePath("/");
        cookies.setUseSecureCookie(true);
        cookies.setUseHttpOnlyCookie(true);
        cookies.setSameSite("Lax");
        return cookies;
    }
}
