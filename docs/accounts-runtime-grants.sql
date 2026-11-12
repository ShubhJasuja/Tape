-- Run as the schema owner after Flyway V3, with the tape_runtime role already created.
-- Use an unprivileged LOGIN role without role memberships or ownership.
GRANT CONNECT ON DATABASE tape TO tape_runtime;
GRANT USAGE ON SCHEMA public TO tape_runtime;
GRANT INSERT ON users, trading_accounts, wallets, cash_ledger_entries TO tape_runtime;
GRANT SELECT ON trading_accounts, wallets TO tape_runtime;
GRANT SELECT, INSERT ON local_credentials TO tape_runtime;
