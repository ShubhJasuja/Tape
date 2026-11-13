const messages = {
  INSTRUMENT_NOT_TRADABLE: "Demo trading is unavailable for this listing.",
  INSTRUMENT_INACTIVE: "Inactive listings cannot be added to a watchlist.",
  IDEMPOTENCY_CONFLICT: 'This request key belongs to a different order.',
  QUOTE_UNAVAILABLE: 'No fresh synthetic quote is available. Try again shortly.',
  INSUFFICIENT_CASH: 'Insufficient available cash.',
  INSUFFICIENT_SHARES: 'Insufficient available shares.',
  ORDER_NOT_OPEN: 'The order has already reached a final state.',
  INVALID_TICKET: 'Check the symbol, order type, and quantity.',
  INVALID_TRIGGER: 'Enter a valid price with at most two decimal places.',
  ACCOUNT_LIMIT: 'This order exceeds the account limits.',
  TRADING_DISABLED: 'Trading is disabled for this account.',
};

export function orderMessage(order, money) {
  if (order.status === 'FILLED') return 'Filled at ' + money(order.fillPaise) + '.';
  if (order.status === 'REJECTED') return messages[order.rejectionCode] || 'Order rejected.';
  return 'Order is ' + order.status.toLowerCase() + '.';
}

async function send(path, options) {
  const response = await fetch('/api/v1' + path, {
    credentials: 'same-origin', cache: 'no-store', ...options,
    signal: options?.signal ? AbortSignal.any([options.signal, AbortSignal.timeout(10000)]) : AbortSignal.timeout(10000),
  });
  if (!response.ok) {
    const detail = await response.json().catch(() => ({}));
    const error = new Error(messages[detail.code] || (response.status === 401
      ? 'Your session expired. Sign in again.' : 'The request could not be completed.'));
    error.status = response.status;
    error.code = detail.code;
    throw error;
  }
  const body = await response.text();
  return body ? JSON.parse(body) : null;
}

export function demoApi(accountId, sessionExpired) {
  const base = '/accounts/' + accountId;
  async function read(path, options) {
    try { return await send(base + path, options); }
    catch (error) {
      if (error.status === 401) sessionExpired();
      throw error;
    }
  }
  async function write(method, path, payload) {
    const { headerName, token } = await send('/auth/csrf');
    const headers = { [headerName]: token };
    if (payload) headers['Content-Type'] = 'application/json';
    return read(path, { method, headers, body: payload && JSON.stringify(payload) });
  }
  return { read, write };
}

export function terminalOrder(order) {
  return {
    ...order, fill: order.fillPaise, trigger: order.triggerPaise, fee: order.feePaise,
    created: Date.parse(order.createdAt),
  };
}

export function pendingKey(accountId) { return 'tape.pending-demo-order.' + accountId; }

export async function settleOrder(api, accountId, newTicket) {
  const key = pendingKey(accountId);
  return navigator.locks.request(key, async () => {
    const pending = JSON.parse(localStorage.getItem(key) || 'null');
    let order;
    if (pending) {
      try {
        order = await api.read('/order-requests/' + pending.idempotencyKey);
      } catch (error) {
        if (error.status !== 404 || error.code !== 'ORDER_NOT_FOUND') throw error;
      }
    }
    const ticket = pending || (newTicket && { ...newTicket, idempotencyKey: crypto.randomUUID() });
    if (!ticket) return null;
    if (!order) {
      // Persist the intent before sending it. This stores no session token or credentials.
      localStorage.setItem(key, JSON.stringify(ticket));
      try {
        order = await api.write('POST', '/orders', ticket);
      } catch (error) {
        if (!pending && error.status === 400) localStorage.removeItem(key);
        throw error;
      }
    }
    localStorage.removeItem(key);
    return order;
  });
}
