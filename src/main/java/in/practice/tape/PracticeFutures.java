package in.practice.tape;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

import static in.practice.tape.DerivativeContracts.*;

@Service
@Profile("accounts")
final class PracticeFutures extends PracticeOptions {
    static final String SOURCE = "TAPE_FUTURES_PRACTICE_V1";
    private static final ZoneId INDIA = ZoneId.of("Asia/Kolkata");

    @Autowired
    PracticeFutures(JdbcTemplate db, TransactionTemplate tx,
                    @Value("${tape.practice-futures.enabled:false}") boolean enabled,
                    @Value("${tape.practice-futures.fee-paise:200}") long fee,
                    @Value("${tape.practice-futures.submissions:true}") boolean submissions) {
        this(db, tx, Clock.systemUTC(), enabled, fee, submissions);
    }

    PracticeFutures(JdbcTemplate db, TransactionTemplate tx, Clock clock, boolean enabled, long fee, boolean submissions) {
        super(db, tx, clock, enabled, fee, submissions, SOURCE);
    }

    @Bean
    @ConditionalOnProperty(name = "tape.practice-futures.enabled", havingValue = "true")
    static OperationsWorker practiceFuturesWorker(PracticeFutures futures) { return new PracticeOptionWorker(futures); }

    static boolean supports(Contract contract) {
        return SOURCE.equals(contract.source()) && "NSE".equals(contract.exchange()) &&
            PracticeOptionPrices.UNDERLYING.equals(contract.underlyingKey()) && contract.underlying() == Underlying.INDEX &&
            contract.kind() == Kind.FUTURE && contract.settlement() == Settlement.CASH;
    }

    static long quote(Spec spec, Instant at) {
        long session = at.atZone(INDIA).toLocalDate().toEpochDay();
        long basis = (Math.floorMod(session * 37, 21) - 10) * 500;
        long value = PracticeOptionPrices.underlying(at) + basis;
        return Math.max(spec.tickPaise(), value / spec.tickPaise() * spec.tickPaise());
    }
}
