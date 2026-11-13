package in.practice.tape;

import org.springframework.context.annotation.Profile;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Profile("accounts")
class OperationsApi {
    private final ReconciliationWorker worker;

    OperationsApi(ReconciliationWorker worker) { this.worker = worker; }

    @PostMapping("/internal/ops/reconcile")
    ResponseEntity<ReconciliationWorker.Result> reconcile() {
        var result = worker.reconcileBatch();
        return ResponseEntity.status(result.busy() ? HttpStatus.CONFLICT :
            result.errors() > 0 ? HttpStatus.SERVICE_UNAVAILABLE : HttpStatus.OK).body(result);
    }
}
