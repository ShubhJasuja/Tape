package in.practice.tape;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
@Profile("accounts")
class TradingRequestLog extends OncePerRequestFilter {
    private static final Logger LOG = LoggerFactory.getLogger(TradingRequestLog.class);

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().matches(".*/api/v1/accounts/[^/]+/orders(?:/[^/]+)?$");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
        throws ServletException, IOException {
        try (var correlation = OperationsLog.correlation()) {
            response.setHeader("X-Correlation-ID", MDC.get("correlation_id"));
            boolean failed = true;
            try {
                chain.doFilter(request, response);
                failed = false;
            } finally {
                LOG.atInfo().addKeyValue("http_status", failed ? 500 : response.getStatus())
                    .log("Synthetic trading request completed");
            }
        }
    }
}
