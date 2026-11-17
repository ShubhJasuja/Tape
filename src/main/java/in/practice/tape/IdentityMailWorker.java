package in.practice.tape;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Profile("accounts")
@ConditionalOnProperty(name = {"tape.identity.mail.enabled", "tape.identity.mail.scheduled"}, havingValue = "true")
final class IdentityMailWorker extends OperationsWorker {
    private final IdentityActions actions;

    IdentityMailWorker(IdentityActions actions, WorkerCoordinator coordinator) {
        super("tape-identity-mail", 5000, 1000, coordinator);
        this.actions = actions;
    }

    @Override void runOnce() { actions.deliverOne(); }
}
