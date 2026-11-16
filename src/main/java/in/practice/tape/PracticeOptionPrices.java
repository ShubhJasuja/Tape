package in.practice.tape;

import java.time.Instant;

import static in.practice.tape.DerivativeContracts.*;

/** Bounded, deterministic prices for the dedicated practice index. No network or provider credentials. */
final class PracticeOptionPrices {
    static final String SOURCE = "TAPE_OPTIONS_PRACTICE_V1";
    static final String UNDERLYING = "PRACTICE_INDEX";
    static final long LOW = 90_000, HIGH = 110_000;

    static boolean supports(Contract contract) {
        return SOURCE.equals(contract.source()) && "NSE".equals(contract.exchange()) &&
            UNDERLYING.equals(contract.underlyingKey()) && contract.underlying() == Underlying.INDEX &&
            contract.settlement() == Settlement.CASH && contract.kind() != Kind.FUTURE &&
            contract.strikePaise() >= LOW && contract.strikePaise() <= HIGH;
    }

    static long underlying(Instant at) {
        long phase = Math.floorMod(at.getEpochSecond() / 5, 80);
        return LOW + Math.min(phase, 80 - phase) * 500;
    }

    static long premium(Contract contract, Spec spec, Instant at) {
        long intrinsic = contract.kind() == Kind.CALL ? underlying(at) - contract.strikePaise()
            : contract.strikePaise() - underlying(at);
        long value = Math.max(0, intrinsic) + 100;
        return Math.max(spec.tickPaise(), value / spec.tickPaise() * spec.tickPaise());
    }

    static long maximumPayout(Contract contract, Spec spec) {
        return Math.multiplyExact(spec.units(1), contract.kind() == Kind.CALL ? HIGH - contract.strikePaise()
            : contract.strikePaise() - LOW);
    }
}
