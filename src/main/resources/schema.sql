CREATE TABLE IF NOT EXISTS paper_order (
  seq BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
  mode VARCHAR(8) NOT NULL,
  id UUID NOT NULL,
  symbol VARCHAR(24) NOT NULL,
  side VARCHAR(4) NOT NULL,
  kind VARCHAR(8) NOT NULL,
  quantity INT NOT NULL CHECK (quantity > 0),
  trigger_price BIGINT,
  status VARCHAR(12) NOT NULL,
  fill_price BIGINT,
  fee BIGINT,
  created_at BIGINT NOT NULL,
  updated_at BIGINT NOT NULL,
  UNIQUE(mode, id)
);
CREATE INDEX IF NOT EXISTS paper_order_mode_seq ON paper_order(mode, seq);
CREATE TABLE IF NOT EXISTS watchlist (
  symbol VARCHAR(24) PRIMARY KEY
);
