import React, { useEffect, useRef, useState } from 'react';
import './options.css';

const inr = new Intl.NumberFormat('en-IN', { style: 'currency', currency: 'INR' });
const signed = new Intl.NumberFormat('en-IN', { style: 'currency', currency: 'INR', signDisplay: 'exceptZero' });
const date = new Intl.DateTimeFormat('en-IN', { dateStyle: 'medium', timeZone: 'Asia/Kolkata' });
const time = new Intl.DateTimeFormat('en-IN', { timeStyle: 'medium', timeZone: 'Asia/Kolkata' });
const money = (paise, format = inr) => paise == null ? '—' : format.format(paise / 100);
const kind = value => value === 'CALL' ? 'CE' : 'PE';
const reasons = {
  OPTIONS_DISABLED: 'Options practice is disabled for this demo account.',
  INSUFFICIENT_MARGIN: 'Available virtual funds are too low for the premium, fees or margin.',
  OPEN_ORDER_EXISTS: 'This contract already has an open order. Cancel it before placing another.',
  INVALID_TICK: 'Use a limit price aligned with the displayed tick size.',
  POSITION_LIMIT: 'The order exceeds the allowed lot or position size.',
  NOTIONAL_LIMIT: 'The order exceeds the allowed premium value.',
  SPEC_CHANGED: 'The specification changed. Review the contract before placing another order.',
  CONTRACT_NOT_ACTIVE: 'This contract has expired.',
  DATABASE_UNAVAILABLE: 'The database is temporarily unavailable. Retry the same request.',
};

function toPaise(raw) {
  if (!/^\d+(\.\d{1,2})?$/.test(raw)) return NaN;
  const [rupees, paise = ''] = raw.split('.');
  return Number(rupees) * 100 + Number(paise.padEnd(2, '0'));
}

export default function OptionsWorkspace({ account, read, write, signOut }) {
  const path = `/accounts/${account}/options`;
  const [view, setView] = useState(null);
  const [readError, setReadError] = useState('');
  const [notice, setNotice] = useState('');
  const [writeError, setWriteError] = useState('');
  const [expiry, setExpiry] = useState('');
  const [contractId, setContract] = useState('');
  const [side, setSide] = useState('BUY');
  const [type, setType] = useState('Market');
  const [lots, setLots] = useState('1');
  const [limit, setLimit] = useState('');
  const pendingKey = `tape-practice-order:${account}`;
  const [pending, setPending] = useState(() => {
    try { return JSON.parse(sessionStorage.getItem(pendingKey)); }
    catch { return null; }
  });
  const [busy, setBusy] = useState(false);
  const [offset, setOffset] = useState(0);
  const [reload, setReload] = useState(0);
  const submitting = useRef(false);

  useEffect(() => {
    const controller = new AbortController();
    let timer;
    async function refresh() {
      try {
        const response = await read(`${path}?offset=${offset}`, { signal: controller.signal });
        if (response.status === 401) return signOut();
        if (!response.ok) throw new Error(response.status === 403 ? reasons.OPTIONS_DISABLED : 'Could not load options practice.');
        const data = await response.json();
        if (!controller.signal.aborted) { setView(data); setReadError(''); }
      } catch (failure) {
        if (!controller.signal.aborted) setReadError(failure.message);
      }
      if (!controller.signal.aborted) timer = setTimeout(refresh, 4000);
    }
    refresh();
    return () => { controller.abort(); clearTimeout(timer); };
  }, [path, offset, reload, read, signOut]);

  const chain = view?.chain || [];
  const expiries = [...new Set(chain.map(item => item.expiry))];
  const activeExpiry = expiries.includes(expiry) ? expiry : expiries[0];
  const contracts = chain.filter(item => item.expiry === activeExpiry);
  const selected = contracts.find(item => item.id === contractId) || contracts[0];
  const position = view?.positions.find(item => item.contractId === selected?.id);
  const price = type === 'Limit' ? toPaise(limit) : selected?.markPaise;
  const count = Number(lots);
  const valid = selected && Number.isSafeInteger(count) && count >= 1 && count <= selected.maxLots &&
    Number.isSafeInteger(price) && price > 0 && price % selected.tickPaise === 0;
  const premium = valid ? price * count * selected.lotSize * selected.multiplier : null;
  const margin = valid ? Math.max(0, -((position?.lots || 0) + (side === 'BUY' ? count : -count))) *
    selected.marginPerLotPaise - (position?.marginPaise || 0) : null;
  const safePremium = Number.isSafeInteger(premium) ? premium : null;

  function remember(ticket) {
    setPending(ticket);
    try {
      if (ticket) sessionStorage.setItem(pendingKey, JSON.stringify(ticket));
      else sessionStorage.removeItem(pendingKey);
    } catch { /* The in-memory retry remains available. */ }
  }

  async function mutate(ticket, id) {
    if (submitting.current) return;
    submitting.current = true;
    setBusy(true);
    setNotice('');
    setWriteError('');
    if (ticket) remember(ticket);
    try {
      const response = await write(`${path}/orders${id ? '/' + id : ''}`,
        ticket ? JSON.stringify(ticket) : undefined, ticket ? 'application/json' : undefined, id ? 'DELETE' : 'POST');
      if (response.status === 401) return signOut();
      const result = await response.json();
      if (!response.ok) {
        if (ticket && response.status < 500) remember(null);
        throw new Error(reasons[result.code] || 'The order could not be processed.');
      }
      if (ticket) remember(null);
      setNotice(`Order ${result.status.toLowerCase()}.`);
      setOffset(0);
    } catch (failure) {
      setWriteError(failure.message || 'Order status is unknown. Retry the same request.');
    } finally {
      submitting.current = false;
      setBusy(false);
      setReload(value => value + 1);
    }
  }

  function submit(event) {
    event.preventDefault();
    if (pending) return mutate(pending);
    if (valid && !readError) return mutate({ requestId: crypto.randomUUID(), contractId: selected.id,
      specVersion: selected.specVersion, side, lots: count, limitPaise: type === 'Limit' ? price : null });
  }

  return <main id="workspace" className="options-workspace">
    <div className="heading options-heading"><div><h1>Options practice</h1><p>Simulated Practice Index · cash-settled contracts · virtual funds</p></div>
      {view && <div className="options-index"><span className="muted">Practice Index</span><strong className="mono">{money(view.underlyingPaise)}</strong>
        <small>{time.format(new Date(view.asOf))} IST</small></div>}</div>
    {!view && !readError && <p role="status">Loading options practice…</p>}
    {readError && <p role="alert" className="notice">{readError} <button type="button" className="quiet" onClick={() => setReload(value => value + 1)}>Refresh</button></p>}
    {notice && <p className="notice" role="status">{notice}</p>}
    {writeError && <p className="notice" role="alert">{writeError}</p>}
    {pending && <p className="notice" role="status">An order submission is awaiting confirmation.
      {' '}<button type="button" className="quiet" disabled={busy} onClick={() => mutate(pending)}>Retry same order</button></p>}
    {view && <>
      <dl className="panel options-balances" aria-label="Practice option balances">
        {[['Available funds', 'availablePaise'], ['Virtual cash', 'cashPaise'], ['Open-order reserves', 'reservedPaise'],
          ['Posted margin', 'marginPaise'], ['Realized P&L', 'realizedPaise']].map(([label, key]) => <div key={key}>
          <dt>{label}</dt><dd className="mono">{money(view.balances[key], key === 'realizedPaise' ? signed : inr)}</dd></div>)}
      </dl>
      <div className="options-grid">
        <section className="panel" aria-labelledby="practice-chain"><div className="panel-title options-panel-heading">
          <h2 id="practice-chain">Options chain</h2><label>Expiry <select value={activeExpiry || ''} onChange={event => setExpiry(event.target.value)}>
            {expiries.length === 0 && <option value="">No active expiries</option>}
            {expiries.map(item => <option key={item} value={item}>{date.format(new Date(item))}</option>)}
          </select></label></div>
          {!contracts.length ? <p className="options-empty">No active practice contracts.</p> : <table className="options-chain">
            <thead><tr><th scope="col">Call premium</th><th scope="col">Strike</th><th scope="col">Put premium</th></tr></thead>
            <tbody>{[...new Set(contracts.map(item => item.strikePaise))].map(strike => <tr key={strike}>
              {['CALL', 'STRIKE', 'PUT'].map(side => {
                if (side === 'STRIKE') return <th key={side} scope="row" className="mono">{money(strike)}</th>;
                const item = contracts.find(contract => contract.strikePaise === strike && contract.kind === side);
                return <td key={side}>{item ? <button type="button" aria-pressed={selected?.id === item.id}
                  aria-label={`${money(strike)} ${kind(side)}, premium ${money(item.markPaise)}`} onClick={() => setContract(item.id)}>
                  {money(item.markPaise)}</button> : '—'}</td>;
              })}</tr>)}</tbody></table>}
          <p className="options-caption">Premiums shown per unit. Select a call or put to open its ticket.</p>
        </section>
        <section className="panel options-order-panel" aria-labelledby="practice-ticket"><div className="panel-title"><h2 id="practice-ticket">Order ticket</h2></div>
          {selected ? <form className="options-ticket" onSubmit={submit}>
            <p>{money(selected.strikePaise)} {kind(selected.kind)} · {date.format(new Date(selected.expiry))}</p>
            <fieldset disabled={busy || !!pending}><legend className="options-sr">Order fields</legend>
              <div className="tabs options-side" aria-label="Order side">{['BUY', 'SELL'].map(item => <button type="button" key={item}
                className={side === item ? 'selected' : ''} aria-pressed={side === item} onClick={() => setSide(item)}>{item === 'BUY' ? 'Buy' : 'Sell'}</button>)}</div>
              <div className="options-fields"><label>Order type<select value={type} onChange={event => setType(event.target.value)}><option>Market</option><option>Limit</option></select></label>
                <label>Lots<input type="number" min="1" max={selected.maxLots} step="1" required value={lots} onChange={event => setLots(event.target.value)} /></label></div>
              {type === 'Limit' && <label>Limit premium (₹ per unit)<input inputMode="decimal" required value={limit}
                onChange={event => setLimit(event.target.value)} /></label>}
            </fieldset>
            <p className="options-caption">{selected.lotSize} units per lot · {money(selected.tickPaise)} tick</p>
            <dl className="est"><div><dt>Estimated premium</dt><dd>{money(safePremium)}</dd></div>
              <div><dt>Trading fee</dt><dd>{money(view.orderFeePaise)}</dd></div>
              <div><dt>Illustrative margin change</dt><dd>{money(margin, signed)}</dd></div></dl>
            {type === 'Limit' && limit && !valid && <p className="options-caption" role="status">Enter a positive, tick-aligned premium and a valid lot count.</p>}
            <button className="primary" disabled={busy || !!pending || !valid || !!readError}>
              {busy ? 'Processing…' : 'Place virtual order'}</button>
          </form> : <p className="options-empty">Select an active call or put to place an order.</p>}
        </section>
        <section className="panel options-position-panel" aria-labelledby="practice-positions"><div className="panel-title"><h2 id="practice-positions">Open positions</h2></div>
          {!view.positions.length ? <p className="options-empty">No open options positions.</p> : <div className="options-scroll" tabIndex={0} aria-label="Open positions table">
            <table><thead><tr>{['Contract', 'Lots', 'Remaining premium basis', 'Posted margin', 'Mark per unit', 'Unrealized P&L'].map(label => <th key={label} scope="col">{label}</th>)}</tr></thead>
              <tbody>{view.positions.map(item => <tr key={item.contractId}><th scope="row">{money(item.strikePaise)} {kind(item.kind)} · {date.format(new Date(item.expiry))}</th>
                <td>{item.lots > 0 ? '+' : ''}{item.lots}</td><td>{money(item.basisPaise)}</td><td>{money(item.marginPaise)}</td>
                <td>{money(item.markPaise)}</td><td>{money(item.unrealizedPaise, signed)}</td></tr>)}</tbody></table>
          </div>}</section>
        <section className="panel options-wide" aria-labelledby="practice-orders"><div className="panel-title"><h2 id="practice-orders">Order and trade history</h2></div>
          {!view.orders.length ? <p className="options-empty">No options orders on this page.</p> : <div className="options-scroll" tabIndex={0} aria-label="Order history table"><table>
            <thead><tr>{['Contract', 'Side', 'Lots', 'Limit', 'Fill', 'Premium cash flow', 'Fee', 'Realized P&L', 'Status', 'Action'].map(label => <th key={label} scope="col">{label}</th>)}</tr></thead>
            <tbody>{view.orders.map(order => <tr key={order.id}><th scope="row">{money(order.strikePaise)} {kind(order.kind)} · {date.format(new Date(order.expiry))}</th>
              <td>{order.side}</td><td>{order.lots}</td><td>{money(order.limitPaise)}</td><td>{money(order.fillPaise)}</td>
              <td>{money(order.premiumPaise, signed)}</td><td>{money(order.fillPaise == null ? null : order.feePaise)}</td>
              <td>{money(order.realizedPaise, signed)}</td><td title={reasons[order.reason] || order.reason}>{order.status}</td>
              <td>{order.status === 'OPEN' && <button type="button" className="quiet" disabled={busy || !!pending} onClick={() => mutate(null, order.id)}>Cancel</button>}</td></tr>)}</tbody></table></div>}
          {(offset > 0 || view.hasMore) && <div className="options-paging"><button className="quiet" disabled={offset === 0} onClick={() => setOffset(value => value - 25)}>Previous</button>
            <button className="quiet" disabled={!view.hasMore} onClick={() => setOffset(value => value + 25)}>Next</button></div>}
        </section>
        {!!view.settlements?.length && <section className="panel options-wide" aria-labelledby="practice-expiries">
          <div className="panel-title"><h2 id="practice-expiries">Recent expiry settlements</h2></div>
          <div className="options-scroll" tabIndex={0} aria-label="Expiry settlements table"><table>
            <thead><tr>{['Contract', 'Lots settled', 'Final index', 'Cash flow', 'Realized P&L'].map(label => <th key={label} scope="col">{label}</th>)}</tr></thead>
            <tbody>{view.settlements.map(item => <tr key={item.contractId}>
              <th scope="row">{money(item.strikePaise)} {kind(item.kind)} · {date.format(new Date(item.expiry))}</th>
              <td>{item.lots > 0 ? '+' : ''}{item.lots}</td><td>{money(item.underlyingPaise)}</td>
              <td>{money(item.payoutPaise, signed)}</td><td>{money(item.realizedPaise, signed)}</td></tr>)}</tbody></table></div>
        </section>}
      </div>
    </>}
  </main>;
}
