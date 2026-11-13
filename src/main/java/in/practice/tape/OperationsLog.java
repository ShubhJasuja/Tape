package in.practice.tape;

import org.slf4j.MDC;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

final class OperationsLog {
    private OperationsLog() {}

    static MDC.MDCCloseable correlation() {
        return MDC.putCloseable("correlation_id", UUID.randomUUID().toString());
    }

    static String reference(UUID id) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(id.toString().getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash, 0, 8);
        } catch (NoSuchAlgorithmException failure) { throw new IllegalStateException(failure); }
    }
}
