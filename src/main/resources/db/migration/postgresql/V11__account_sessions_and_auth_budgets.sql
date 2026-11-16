CREATE TABLE tape_http_session (
    primary_id char(36) PRIMARY KEY,
    session_id char(36) NOT NULL UNIQUE,
    creation_time bigint NOT NULL,
    last_access_time bigint NOT NULL,
    max_inactive_interval integer NOT NULL,
    expiry_time bigint NOT NULL,
    principal_name varchar(254)
);
CREATE INDEX tape_http_session_expiry_idx ON tape_http_session(expiry_time);
CREATE INDEX tape_http_session_principal_idx ON tape_http_session(principal_name);

CREATE TABLE tape_http_session_attributes (
    session_primary_id char(36) NOT NULL REFERENCES tape_http_session(primary_id) ON DELETE CASCADE,
    attribute_name varchar(200) NOT NULL,
    attribute_bytes bytea NOT NULL,
    PRIMARY KEY (session_primary_id, attribute_name)
);

CREATE TABLE auth_request_budget (
    bucket bytea NOT NULL CHECK (octet_length(bucket) = 32),
    window_id bigint NOT NULL,
    used integer NOT NULL CHECK (used > 0),
    PRIMARY KEY (bucket, window_id)
);
CREATE INDEX auth_request_budget_window_idx ON auth_request_budget(window_id);
