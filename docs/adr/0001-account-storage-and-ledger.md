# ADR 0001: PostgreSQL accounts and an immutable trading ledger

Status: Proposed for Stage 2. Stage 1 retains H2, the existing simulator and the local terminal.

## Context

Tape stores mutable orders in a file-backed H2 database. It reconstructs virtual cash and holdings from fills and derives reservations from open orders. A process-local lock serializes the current writer. Shared deployment requires account ownership and transaction rules that hold across connections and processes.

## Decision

Keep the Spring Boot modular monolith. Use PostgreSQL with versioned Flyway migrations. Introduce Spring Security with authenticated principals and secure same-origin sessions. Resolve account ownership server-side for every user read, mutation and export.

- Map a stable identity to a user. Create one account per user, currency and data mode with an explicit opening cash posting. Keep watchlist rows user-scoped.
- Keep orders as current state, with a unique account-scoped idempotency key and a normalized payload hash. Store order transitions and executions as append-only records. Give executions a durable sequence and store the actual event time separately.
- Record immutable cash postings in integer paise for funding, trade proceeds and fees. Record share movements in executions. Keep current balances, average cost and reservations as transactional projections that can be reconciled from authoritative records.
- Lock the account row in every trading transaction. Check ownership, available cash or shares and the order state under that lock. Apply a conditional state transition, execution, postings and projection changes in one commit. Use database uniqueness for idempotency and execution identity. Begin with one matcher.
- Store data mode, quote source, event timestamp and execution policy version for new fills. Keep imported provenance marked as unknown where the legacy data cannot supply it. The shared pilot uses synthetic data.

## Migration and rollout

Build the PostgreSQL path behind a separate configuration while the H2 demo remains runnable. Rehearse imports from a validated H2 backup into a designated owner account. Preserve original IDs, order states, fill order, prices and fees. Derive opening funding, historical postings and open reservations without fabricating missing timestamps or quote evidence.

Reconcile order counts, cash, reserved cash, positions, reserved shares, cost basis and realized P&L per data mode. Prove isolation, concurrent overspend and oversell prevention, cancel/fill races, retry after an ambiguous response, and restart recovery before cutover. Quiesce the legacy writer for final import and retain the old database. Once the new app accepts writes, a rollback requires reconciliation of those writes before returning to H2.
