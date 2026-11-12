CREATE FUNCTION reject_immutable_record_change() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'Records in % are append-only', TG_TABLE_NAME USING ERRCODE = '55000';
END;
$$;

CREATE TRIGGER executions_append_only
BEFORE UPDATE OR DELETE OR TRUNCATE ON executions
FOR EACH STATEMENT EXECUTE FUNCTION reject_immutable_record_change();

CREATE TRIGGER cash_ledger_entries_append_only
BEFORE UPDATE OR DELETE OR TRUNCATE ON cash_ledger_entries
FOR EACH STATEMENT EXECUTE FUNCTION reject_immutable_record_change();
