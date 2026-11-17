-- Run as the schema owner after Flyway V12, with the tape_runtime role already created.
-- Use an unprivileged LOGIN role without role memberships or ownership.
GRANT CONNECT ON DATABASE tape TO tape_runtime;
GRANT USAGE ON SCHEMA public TO tape_runtime;
GRANT INSERT ON users, trading_accounts, wallets, cash_ledger_entries TO tape_runtime;
GRANT SELECT ON trading_accounts, wallets TO tape_runtime;
GRANT SELECT, INSERT ON local_credentials TO tape_runtime;
GRANT SELECT, INSERT, UPDATE, DELETE ON tape_http_session, tape_http_session_attributes, auth_request_budget TO tape_runtime;
GRANT UPDATE (password_hash, verified_at, auth_version) ON local_credentials TO tape_runtime;
GRANT SELECT, INSERT, DELETE ON identity_actions TO tape_runtime;
GRANT SELECT, INSERT, UPDATE, DELETE ON identity_mail TO tape_runtime;
