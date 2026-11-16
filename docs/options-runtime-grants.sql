-- Apply as schema owner after V9 and the account and synthetic trading grants.
GRANT SELECT ON derivative_contracts, derivative_specs TO tape_runtime;
GRANT SELECT, INSERT, UPDATE ON derivative_books TO tape_runtime;
GRANT SELECT, INSERT, UPDATE, DELETE ON derivative_positions TO tape_runtime;
GRANT SELECT, INSERT ON derivative_events, derivative_lifecycle_retries TO tape_runtime;
GRANT USAGE ON SEQUENCE derivative_events_seq_seq TO tape_runtime;
GRANT SELECT ON practice_future_sessions TO tape_runtime;
GRANT SELECT, INSERT ON practice_derivative_orders TO tape_runtime;
GRANT UPDATE (status, reason, fill_request_id, updated_at) ON practice_derivative_orders TO tape_runtime;
