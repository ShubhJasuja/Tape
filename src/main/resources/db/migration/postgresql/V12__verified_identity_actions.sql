ALTER TABLE local_credentials
    ADD COLUMN verified_at timestamptz,
    ADD COLUMN auth_version bigint NOT NULL DEFAULT 0 CHECK (auth_version >= 0);

CREATE TABLE identity_actions (
    id uuid PRIMARY KEY,
    user_id uuid NOT NULL REFERENCES local_credentials(user_id),
    kind text NOT NULL CHECK (kind IN ('VERIFY', 'RESET')),
    token_digest bytea NOT NULL UNIQUE CHECK (octet_length(token_digest) = 32),
    expires_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    CHECK (expires_at > created_at),
    UNIQUE (user_id, kind)
);
CREATE INDEX identity_actions_expiry_idx ON identity_actions(expires_at);

CREATE TABLE identity_mail (
    action_id uuid PRIMARY KEY REFERENCES identity_actions(id) ON DELETE CASCADE,
    encrypted_token bytea NOT NULL CHECK (octet_length(encrypted_token) > 28),
    attempts integer NOT NULL DEFAULT 0 CHECK (attempts BETWEEN 0 AND 8),
    next_attempt_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE INDEX identity_mail_due_idx ON identity_mail(next_attempt_at);
