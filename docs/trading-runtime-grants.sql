-- Apply after accounts-runtime-grants.sql and Flyway V4, as the schema owner.
GRANT EXECUTE ON FUNCTION lock_tape_trading_account(uuid, uuid) TO tape_runtime;
GRANT UPDATE (balance_paise, reserved_paise) ON wallets TO tape_runtime;
GRANT SELECT ON instruments, orders, executions, cash_ledger_entries, positions TO tape_runtime;
GRANT INSERT ON orders, executions, positions TO tape_runtime;
GRANT UPDATE (status, updated_at, rejection_code) ON orders TO tape_runtime;
GRANT UPDATE (quantity, reserved_quantity, cost_paise) ON positions TO tape_runtime;
GRANT SELECT, INSERT ON watchlists TO tape_runtime;
GRANT SELECT, INSERT, DELETE ON watchlist_items TO tape_runtime;
