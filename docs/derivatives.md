
# Stage 2G derivatives foundation

The PostgreSQL derivatives book reuses Tape's account ownership and account-level lock. It has separate virtual funding, reservations, collateral, positions and an immutable journal. Equity cash, holdings and executions use their existing tables and logic. Stage 2G is exercised by trusted JVM tests. No servlet endpoint, worker, UI action, provider adapter or runtime database grant activates it.

## Contract and risk inputs

V7 adds an exchange/source contract identity and append-only specification versions. The identity includes exchange, underlying, type (future, call or put), expiry instant, strike and settlement type. Each specification supplies an effective instant, exchange quantity per lot, multiplier, tick size, initial and maintenance margin per lot and order/position exposure limits. All price and cash fields use integer paise. Contract time is UTC in storage. The futures mark session date uses Asia/Kolkata. A position pins its opening specification. Once a newer specification takes effect, the existing position can reduce or close at its pinned economics. Increasing or flipping exposure requires closing it and starting a position under the new version.

No exchange contracts, rates, settlement prices, or fictional catalog listings are seeded by this stage. Tests create disposable contracts and explicitly supply all rules and prices. A future authorized import must define source, effective dates, treatment of existing positions and corrections. Official trading parameters and risk schedules must come from an approved source. [NSE contract specifications](https://www.nseindia.com/static/products-services/equity-derivatives-contract-specifications) · [NSE historical equity derivatives settlement](https://www.nseclearing.in/clearing-settlement/equity-derivatives/settlement-mechanism)

## Journal and cash model

- `derivative_books` projects cash, reserved funds and margin collateral. Available cash is their difference. Opening funds are virtual and separate from the equity wallet.
- `derivative_positions` stores signed lots, total remaining basis, the pinned specification and posted collateral. The fill command checks lot divisibility and tick alignment. Prices and quantities multiply with checked arithmetic. Risk bounds guard new exposure.
- `derivative_events` records opening funds, reservation changes, fills, daily marks and expiry settlement. Projections and journal events commit in one PostgreSQL transaction under Tape's existing account lock. Request IDs are account scoped. Repeated lifecycle requests resolve to the same mark or settlement, including a retry using a different request ID. Conflicting payloads are rejected.
- Journal replay compares cash, reserves, posted collateral and positions to their projections. Collateral held against physical-delivery obligations is included. Realized P&L is reported from the journal. Database triggers protect contract identity, specifications and financial events from update, deletion and truncation.

Options post premium cash debits for buys and credits for sells. Voluntary fills must leave available cash nonnegative. Opening shorts require configured collateral covered independently of the premium for the newly opened lots. Closing part of a position allocates remaining basis pro rata with whole-paise arithmetic and keeps any rounding remainder with the open position. Futures never debit or credit total notional on entry. A daily mark posts signed variation cash and rebases the remaining position. Closing fills and expiry post only P&L since the latest basis. The book exposes unrealized P&L at a supplied mark, and reports maintenance collateral shortfalls. Marks and settlement record actual losses, including a cash deficit. No automatic liquidation is performed.

Cash expiry settles futures variation or options intrinsic value and releases position collateral. For physical expiry, the journal records signed delivery units, the opposite cash obligation and retained collateral. Physical futures also post the final variation margin. Delivery is pending at the end of Stage 2G. Completing it would require a separate inventory and cash delivery lifecycle. P&L for physical options represents the economic value at expiry. It does not imply cash delivery has completed. [NSE individual securities F&O](https://www.nseindia.com/static/products-services/equity-derivatives-individual-securities)

## Local verification

Use Java 21, Docker Desktop and the Maven Wrapper. From the repository root run:

```powershell
.\mvnw.cmd verify -Dtape.postgres.tests=true
```

On Linux use `./mvnw`. The flag enables PostgreSQL 17 Testcontainers, including fresh V7 installation, upgrade coverage, derivatives premium and variation accounting, risk rejection, reconciliation and equity regressions. The GitHub Actions workflow builds the existing React app with Node 22 and runs this Java suite on pushes, pull requests and manual runs. Test database roles and fixture credentials are disposable. No application database or market-data connection is required for verification.

## Stage 2H prerequisites

Add a dedicated execution adapter and authenticated command layer only after authorization for the intended price source. Model order-specific reservations, fills and fees, current margin inputs, broker rules, margin calls and forced risk reduction. Verify expiry jobs, physical delivery completion, holiday calendars, multi-leg risk, failure recovery and complete end-to-end reconciliation before adding an F&O entry point.
