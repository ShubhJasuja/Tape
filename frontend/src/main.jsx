import React, { useCallback, useEffect, useRef, useState } from 'react';
import { createRoot } from 'react-dom/client';
import { Activity, ArrowDownToLine, ArrowDownUp, ChartCandlestick, CircleHelp, Clock3, FlaskConical, Layers3, ListOrdered, Search, Star } from 'lucide-react';
import '@fontsource/dm-sans/400.css';
import '@fontsource/dm-sans/500.css';
import '@fontsource/dm-sans/600.css';
import '@fontsource/dm-sans/700.css';
import '@fontsource/ibm-plex-mono/400.css';
import './style.css';
import TapeEntry from './accounts.jsx';
import { demoApi, orderMessage as describeOrder, pendingKey, settleOrder, terminalOrder } from './demoApi.js';

const inr = new Intl.NumberFormat('en-IN', { minimumFractionDigits: 2, maximumFractionDigits: 2 });
const price = n => n == null ? '—' : inr.format(n / 100);
const money = n => n == null ? '—' : '₹' + price(n);
const ticker = s => s.split(':')[1];
const exchange = s => s.split(':')[0];
const ist = new Intl.DateTimeFormat('en-IN', { timeZone: 'Asia/Kolkata', hour: '2-digit', minute: '2-digit', second: '2-digit', hour12: false });
const dates = new Intl.DateTimeFormat('en-IN', { day: 'numeric', month: 'short', timeZone: 'UTC' });
const day = s => dates.format(new Date(s + 'T00:00:00Z'));
const exchangeDay = new Intl.DateTimeFormat('en-IN', { day: 'numeric', month: 'short', timeZone: 'Asia/Kolkata' });
const query = params => new URLSearchParams(params);
const tone = n => n > 0 ? 'up' : n < 0 ? 'down' : '';
async function api(path, options) {
  const res = await fetch('/api' + path, options);
  if (!res.ok) {
    const data = await res.json().catch(() => ({}));
    throw new Error(data.message || 'The request failed. Please try again.');
  }
  const raw = await res.text();
  return raw ? JSON.parse(raw) : null;
}

function Chart({ candles, symbol, interval = 'DAILY' }) {
  const canvas = useRef(null);
  const [hover, setHover] = useState(null);
  const hovered = candles[hover] || candles.at(-1);
  const barTime = row => row.exchangeAt ? (interval === 'DAILY' ? exchangeDay : ist).format(new Date(row.exchangeAt)) : day(row.date);
  useEffect(() => {
    setHover(null);
  }, [candles, symbol]);
  useEffect(() => {
    const el = canvas.current;
    if (!el || !candles.length) return;
    function paint() {
      const { width: w, height: h } = el.getBoundingClientRect();
      if (w < 100 || h < 100) return;
      const dpr = window.devicePixelRatio || 1;
      el.width = w * dpr;
      el.height = h * dpr;
      const ctx = el.getContext('2d');
      ctx.scale(dpr, dpr);
      const right = w - 78, bottom = h - 28, top = 15, left = 9;
      const step = (right - left) / candles.length;
      const min = Math.min(...candles.map(c => c.low));
      const max = Math.max(...candles.map(c => c.high));
      const pad = Math.max((max - min) * .12, 1);
      const y = value => top + (max + pad - value) / (max - min + 2 * pad) * (bottom - top - 49);
      const maxVolume = Math.max(1, ...candles.map(c => c.volume));
      ctx.font = '11px "IBM Plex Mono", monospace';
      ctx.textBaseline = 'middle';
      for (let n = 0; n < 5; n++) {
        const value = min - pad + (max - min + 2 * pad) * n / 4;
        const at = y(value);
        ctx.fillStyle = '#262b32';
        ctx.fillRect(left, at, right - left, 1);
        ctx.fillStyle = '#a5afbe';
        ctx.fillText(price(value), right + 9, at);
      }
      candles.forEach((c, n) => {
        const x = left + step * (n + .5);
        const width = Math.max(1, Math.min(step * .68, 15));
        const color = c.close >= c.open ? '#65bfa4' : '#dd8490';
        ctx.fillStyle = color;
        ctx.fillRect(x - .5, y(c.high), 1, Math.max(1, y(c.low) - y(c.high)));
        ctx.fillRect(x - width / 2, y(Math.max(c.open, c.close)), width, Math.max(1, Math.abs(y(c.open) - y(c.close))));
        ctx.globalAlpha = .27;
        if (c.volume != null) {
          const volume = c.volume / maxVolume * 34;
          ctx.fillRect(x - width / 2, bottom - volume, width, volume);
        }
        ctx.globalAlpha = 1;
      });
      const ticks = w < 420 ? 3 : 5;
      ctx.fillStyle = '#a5afbe';
      ctx.textAlign = 'center';
      for (let n = 0; n < ticks; n++) {
        const index = Math.round((candles.length - 1) * n / (ticks - 1));
        const x = Math.max(23, Math.min(right - 12, left + step * (index + .5)));
        ctx.fillText(barTime(candles[index]), x, h - 12);
      }
      if (hover != null && candles[hover]) {
        const x = left + step * (hover + .5);
        ctx.fillStyle = '#a1bde2';
        ctx.fillRect(x, top, 1, bottom - top);
      }
    }
    const observer = new ResizeObserver(paint);
    observer.observe(el);
    document.fonts.ready.then(paint);
    return () => observer.disconnect();
  }, [candles, hover, interval]);
  return <>
    <div className="ohlc mono">{hovered ? <><span>{barTime(hovered)}</span>{[['O', 'open'], ['H', 'high'], ['L', 'low'], ['C', 'close']].map(([label, key]) =>
      <span key={key}>{label} <b>{price(hovered[key])}</b></span>)}</> : 'Daily OHLC'}</div>
    {candles.length ? <canvas ref={canvas} role="img" aria-label={ticker(symbol) + ' candlestick and volume chart'}
      onPointerMove={e => {
        const rect = e.currentTarget.getBoundingClientRect();
        const index = Math.floor((e.clientX - rect.left - 9) / (rect.width - 87) * candles.length);
        setHover(index >= 0 && index < candles.length ? index : null);
      }} onPointerLeave={() => setHover(null)} /> : <div className="empty-chart">Daily candles are unavailable for this feed.</div>}
  </>;
}

function App({ demoAccount, sessionExpired }) {
  const secured = React.useMemo(() => demoAccount && demoApi(demoAccount, sessionExpired), [demoAccount, sessionExpired]);
  const [offset, setOffset] = useState(0);
  const [pending, setPending] = useState(() => demoAccount && !!localStorage.getItem(pendingKey(demoAccount)));
  const latestRefresh = useRef(0);
  const [mode, setMode] = useState('demo');
  const [symbol, setSymbol] = useState('NSE:RELIANCE');
  const [terminal, setTerminal] = useState(null);
  const [error, setError] = useState('');
  const [search, setSearch] = useState('');
  const [exchangeFilter, setExchangeFilter] = useState('');
  const [typeFilter, setTypeFilter] = useState('');
  const [catalogOffset, setCatalogOffset] = useState(0);
  const [discovery, setDiscovery] = useState(null);
  const [discoveryError, setDiscoveryError] = useState('');
  const [catalogRevision, setCatalogRevision] = useState(0);
  const [selection, setSelection] = useState(null);
  const [pinnedOnly, setPinnedOnly] = useState(false);
  const catalogQuery = query({ q: search, exchange: exchangeFilter, type: typeFilter,
    pinned: pinnedOnly, offset: catalogOffset, limit: 20 }).toString();

  const [range, setRange] = useState(30);
  const [series, setSeries] = useState(null);
  const [side, setSide] = useState('BUY');
  const [kind, setKind] = useState('MARKET');
  const [quantity, setQuantity] = useState('1');
  const [trigger, setTrigger] = useState('');
  const [busy, setBusy] = useState(false);
  const [orderMessage, setOrderMessage] = useState('');
  const [accountTab, setAccountTab] = useState('Positions');
  const [report, setReport] = useState(null);
  const [analysisBusy, setAnalysisBusy] = useState(false);
  const [analysisError, setAnalysisError] = useState('');
  const retry = useRef(null);

  const refresh = useCallback(async signal => {
    const sequence = ++latestRefresh.current;
    try {
      let data;
      if (secured) {
        const [market, portfolio, page] = await Promise.all([
          secured.read('/market', { signal }),
          secured.read('/portfolio', { signal }),
          secured.read('/orders?' + query({ offset, limit: 25 }), { signal }),
        ]);
        const { wallet, positions } = portfolio;
        data = { ...market, feed: { mode, status: 'Synthetic demo', quotes: market.quotes },
          tradeable: Object.keys(market.quotes), nextOffset: page.nextOffset,
          account: { cash: wallet.balancePaise, reserved: wallet.reservedPaise, available: wallet.availablePaise,
            realized: portfolio.realizedPaise, unrealized: portfolio.unrealizedPaise, equity: portfolio.equityPaise,
            orders: page.orders.map(terminalOrder), holdings: positions.map(p => ({
              ...p, cost: p.costPaise, value: p.valuePaise, pnl: p.unrealizedPaise,
            })) } };
      } else data = await api('/terminal?' + query({ mode }), { signal });
      if (sequence !== latestRefresh.current) return;
      if (!signal?.aborted) { setTerminal(data); setError(''); }
    } catch (e) {
      if (!signal?.aborted && sequence === latestRefresh.current && e.name !== 'AbortError') setError(e.message);
    }
  }, [mode, secured, offset]);
  useEffect(() => {
    const controller = new AbortController();
    refresh(controller.signal);
    let timer;
    async function poll() {
      if (controller.signal.aborted) return;
      await refresh(controller.signal);
      if (!controller.signal.aborted) timer = setTimeout(poll, 3000);
    }
    timer = setTimeout(poll, 3000);
    return () => { clearTimeout(timer); controller.abort(); };
  }, [refresh]);
  useEffect(() => {
    const controller = new AbortController();
    const request = secured ? secured.read('/candles?' + query({ symbol }), { signal: controller.signal })
      : api('/candles?' + query({ mode, symbol }), { signal: controller.signal });
    request
      .then(data => setSeries({ mode, symbol, data }))
      .catch(e => { if (e.name !== 'AbortError') setSeries({ mode, symbol, data: [] }); });
    return () => controller.abort();
  }, [mode, symbol, secured]);

  useEffect(() => {
    if (!secured) return;
    const controller = new AbortController();
    setDiscoveryError('');
    const timer = setTimeout(async () => {
      try {
        const page = await secured.read('/instruments?' + catalogQuery, { signal: controller.signal });
        if (!controller.signal.aborted) setDiscovery({ ...page, query: catalogQuery, revision: catalogRevision });
      } catch (failure) {
        if (!controller.signal.aborted) setDiscoveryError(failure.message);
      }
    }, 200);
    return () => { clearTimeout(timer); controller.abort(); };
  }, [secured, catalogQuery, catalogRevision]);

  useEffect(() => {
    if (!secured || !selection?.id) return;
    const controller = new AbortController();
    secured.read('/instruments/' + selection.id, { signal: controller.signal })
      .then(listing => {
        if (!controller.signal.aborted) {
          setSelection(listing);
          setSymbol(listing.symbol);
        }
      })
      .catch(failure => { if (!controller.signal.aborted) setDiscoveryError(failure.message); });
    return () => controller.abort();
  }, [secured, selection?.id, catalogRevision]);

  const active = terminal?.feed.mode === mode ? terminal : null;
  const account = active?.account;
  const catalogPage = discovery?.query === catalogQuery && discovery?.revision === catalogRevision ? discovery : null;
  const instrument = selection?.symbol === symbol ? selection : active?.instruments.find(i => i.symbol === symbol);
  const metadataOnly = secured && (instrument?.demoTradable === false ||
    (!!active && !active.instruments.some(i => i.symbol === symbol)));
  const quote = active?.feed.quotes[symbol];
  const candles = React.useMemo(() => series?.mode === mode && series.symbol === symbol ? series.data.slice(-range) : [],
    [series, mode, symbol, range]);
  const watched = active?.watchlist.includes(symbol);
  const tradeable = active?.tradeable.includes(symbol) && (!secured || Date.now() - quote?.time <= 6000);
  const working = account?.orders.filter(o => o.status === 'OPEN') || [];
  const visibleReport = report?.mode === mode && (report.symbol == null || report.symbol === symbol) ? report.data : null;
  const rows = secured ? catalogPage?.instruments || [] : active?.instruments.filter(i => (!pinnedOnly || active.watchlist.includes(i.symbol)) &&
    (i.symbol + ' ' + i.name).toLowerCase().includes(search.trim().toLowerCase())) || [];
  const unit = kind === 'MARKET' ? quote?.price : Math.round(Number(trigger) * 100);
  const notional = unit > 0 && Number.isInteger(Number(quantity)) && Number(quantity) > 0 ? unit * Number(quantity) : null;
  const estFee = notional == null ? null : Math.ceil(notional / 2000);

  async function reconcile(ticket) {
    setBusy(true);
    setOrderMessage('');
    try {
      const order = await settleOrder(secured, demoAccount, ticket);
      setOrderMessage(order ? describeOrder(order, money) : 'No pending submission in this browser.');
      setAccountTab('Orders');
      if (offset === 0) await refresh(); else setOffset(0);
    } catch (failure) { setOrderMessage(failure.message || 'Could not verify the order.'); }
    finally {
      setPending(!!localStorage.getItem(pendingKey(demoAccount)));
      setBusy(false);
    }
  }

  async function submit(e) {
    e.preventDefault();
    const ticket = { symbol, side, kind, quantity: Number(quantity), trigger: kind === 'MARKET' ? null : Number(trigger) };
    if (secured) return reconcile(ticket);
    const fingerprint = JSON.stringify({ mode, ticket });
    if (retry.current?.fingerprint !== fingerprint) retry.current = { fingerprint, id: crypto.randomUUID() };
    setBusy(true);
    setOrderMessage('');
    try {
      const result = await api('/orders?' + query({ mode }), { method: 'POST', headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ ...ticket, id: retry.current.id }) });
      retry.current = null;
      setOrderMessage(result.status === 'FILLED' ? 'Filled at ' + money(result.fill) + '.' : 'Order is ' + result.status.toLowerCase() + '.');
      setAccountTab(result.status === 'OPEN' ? 'Orders' : 'Positions');
      await refresh();
    } catch (e) { setOrderMessage(e.message); }
    finally { setBusy(false); }
  }

  async function cancel(id) {
    setBusy(true);
    try {
      if (secured) await secured.write('DELETE', '/orders/' + id);
      else await api('/orders/' + id + '?' + query({ mode }), { method: 'DELETE' });
      setOrderMessage('Order cancelled.');
      await refresh();
    } catch (e) { setOrderMessage(e.message); }
    finally { setBusy(false); }
  }

  async function pin() {
    try {
      if (secured) await secured.write('PUT', '/watchlist', { ...(instrument?.id ? { instrumentId: instrument.id } : { symbol }), pinned: !watched });
      else await api('/watchlist?' + query({ symbol, pinned: !watched }), { method: 'PUT' });
      if (secured) setCatalogRevision(n => n + 1);
      await refresh();
    } catch (e) { setError(e.message); }
  }

  async function analyze(target) {
    setAnalysisBusy(true);
    setAnalysisError('');
    setReport(null);
    try {
      const params = target === 'stock' ? { mode, symbol } : { mode };
      const data = await api('/research?' + query(params));
      setReport({ mode, symbol: params.symbol, data });
    } catch (e) { setAnalysisError(e.message); }
    finally { setAnalysisBusy(false); }
  }

  function download() {
    const header = 'id,symbol,side,kind,quantity,status,trigger_inr,fill_inr,fee_inr,created_utc';
    const values = (account?.orders || []).map(o => [o.id, o.symbol, o.side, o.kind, o.quantity, o.status,
      o.trigger == null ? '' : o.trigger / 100, o.fill == null ? '' : o.fill / 100, o.fee == null ? '' : o.fee / 100,
      new Date(o.created).toISOString()].join(','));
    const url = URL.createObjectURL(new Blob([[header, ...values].join('\n')], { type: 'text/csv;charset=utf-8' }));
    const a = document.createElement('a');
    a.href = url;
    a.download = 'tape-' + mode + '-orders.csv';
    a.click();
    setTimeout(() => URL.revokeObjectURL(url), 1000);
  }

  return <>
    {!secured && <header className="top">
      <a className="brand" href="#terminal"><span className="brand-mark"><Activity size={23}/></span> Tape <span className="brand-sub">PAPER TRADING</span></a>
      <nav aria-label="Workspace"><a href="#terminal">Terminal</a><a href="#portfolio">Portfolio</a><a href="#research">Research</a></nav>
      <div className="mode" aria-label="Market data mode">
        {['demo','live'].map(m => <button key={m} aria-pressed={mode === m} className={mode === m ? 'selected' : ''}
          disabled={busy} onClick={() => { setMode(m); setOrderMessage(''); setAnalysisError(''); }}>{m === 'demo' ? 'Demo' : 'Live data'}</button>)}
      </div>
    </header>}
    <main id="terminal">
      <div className="heading">
        <div><h1>Trading terminal</h1><p>NSE &amp; BSE equities <span className="separator">/</span> Virtual account</p></div>
        <div className="feed"><span className={'dot ' + (tradeable ? 'ready' : '')}/>{active?.feed.status || 'Connecting'}{quote && <span className="muted mono"> · {ist.format(quote.time)} IST</span>}</div>
      </div>
      {error && <div className="notice" role="alert">{error}</div>}
      {mode === 'live' && active && !active.liveConfigured && <div className="notice"><CircleHelp size={16}/> The legacy live-data ledger is available for review and cancellation. Provider data is in the authenticated Market data workspace.</div>}
      <div className="terminal-grid">
        <section className="panel markets" aria-label="Market watch">
          <div className="panel-title"><Layers3 size={16}/> <h2>Market watch</h2></div>
          <label className="search"><Search size={16}/><input aria-label="Search symbols" placeholder={secured ? "Symbol, company or ISIN" : "Find a symbol"} maxLength={100} value={search} onChange={e => { setSearch(e.target.value); setCatalogOffset(0); }} onKeyDown={e => {
            if (secured && e.key === "Enter" && rows.length) { e.preventDefault(); setSelection(rows[0]); setSymbol(rows[0].symbol); }
          }}/></label>
          {secured && <div className="catalog-filters">
            <select aria-label="Exchange filter" value={exchangeFilter} onChange={e => { setExchangeFilter(e.target.value); setCatalogOffset(0); }}>
              <option value="">All exchanges</option><option>NSE</option><option>BSE</option>
            </select>
            <select aria-label="Instrument type filter" value={typeFilter} onChange={e => { setTypeFilter(e.target.value); setCatalogOffset(0); }}>
              <option value="">All equities</option><option value="EQUITY">Equity</option><option value="SME_EQUITY">SME equity</option>
            </select>
          </div>}
          <div className="tabs watch-tabs"><button className={!pinnedOnly ? 'selected' : ''} onClick={() => { setPinnedOnly(false); setCatalogOffset(0); }}>All stocks</button>
            <button className={pinnedOnly ? 'selected' : ''} onClick={() => { setPinnedOnly(true); setCatalogOffset(0); }}>Watchlist</button></div>
          <div className="market-columns muted"><span>Symbol</span><span>Last / Change</span></div>
          <div className="market-rows">{rows.map(i => {
            const q = active?.feed.quotes[i.symbol];
            const pct = q && q.price !== q.change ? q.change / (q.price - q.change) * 100 : null;
            return <button key={i.id || i.symbol} className={'stock ' + (symbol === i.symbol ? 'active-stock' : '')} aria-pressed={symbol === i.symbol}
              onClick={() => { setSelection(secured ? i : null); setSymbol(i.symbol); setOrderMessage(''); }}>
              <span><strong>{ticker(i.symbol)}</strong><small>{exchange(i.symbol)} · {i.name}</small>{secured && <small>{i.demoTradable ? "Synthetic" : i.status === "ACTIVE" ? "Metadata only" : i.status.toLowerCase()}</small>}</span>
              <span className="mono">{price(q?.price)}<small className={tone(pct)}>{pct == null ? '—' : (pct > 0 ? '+' : '') + pct.toFixed(2) + '%'}</small></span>
            </button>;
          })}
          {discoveryError && <p className="empty" role="alert">{discoveryError}</p>}
          {!rows.length && !discoveryError && <p className="empty" role="status">{(secured ? catalogPage : active) ? 'No matching instruments.' : 'Loading instruments…'}</p>}</div>
          {secured && <div className="catalog-filters catalog-pages">
            <button className="quiet" disabled={catalogOffset === 0} onClick={() => setCatalogOffset(n => Math.max(0, n - 20))}>Previous</button>
            <button className="quiet" disabled={catalogPage?.nextOffset == null} onClick={() => setCatalogOffset(catalogPage.nextOffset)}>Next</button>
          </div>}
        </section>

        <section className="panel chart" aria-label="Price chart">
          <div className="chart-title"><div><div className="instrument"><h2>{ticker(symbol)}</h2><span>{exchange(symbol)}</span></div>
            <div className="muted">{instrument?.name || 'Equity'}</div>
            {secured && selection?.symbol === symbol && <div className="muted catalog-detail">
              {selection.fictional ? 'Fictional listing' : 'Tape listing'} · {selection.status}
              {selection.isin && <> · {selection.fictional ? 'Sample ISIN' : 'ISIN'} {selection.isin}</>}
              {selection.tickSizePaise != null && <> · Tick {money(selection.tickSizePaise)}</>}
            </div>}</div>
            <button className="icon-button" aria-label={watched ? 'Remove from watchlist' : 'Add to watchlist'} aria-pressed={!!watched} onClick={pin} disabled={!active || (secured && !watched && instrument?.status && instrument.status !== "ACTIVE")}><Star size={17} fill={watched ? 'currentColor' : 'none'}/></button>
          </div>
          <div className="quote"><strong className="mono">{money(quote?.price)}</strong>
            {quote && <span className={'mono ' + tone(quote.change)}>{quote.change > 0 ? '+' : quote.change < 0 ? '−' : ''}{money(Math.abs(quote.change))} <span className="muted">vs prev. close</span></span>}</div>
          <div className="chart-tools"><span><ChartCandlestick size={15}/> Daily candles</span><div className="tabs">
            {[['30D',30], ['60D',60], ['90D',90]].map(([label,n]) => <button key={n} title={n + " trading sessions"} className={range === n ? 'selected' : ''} aria-pressed={range === n} onClick={() => setRange(n)}>{label}</button>)}
          </div></div>
          {metadataOnly ? <div className="empty-chart">Price data unavailable for this listing.</div> : <Chart candles={candles} symbol={symbol}/>}
          <div className="chart-footer"><span><i/> Volume</span><span>{metadataOnly ? 'Metadata only · Historical prices unavailable' : mode === 'demo' ? 'Synthetic candles' : 'Provider data disconnected'} · INR</span></div>
        </section>

        <section className="panel ticket" aria-label="Order ticket">
          <div className="panel-title"><ArrowDownUp size={16}/><h2>Order ticket</h2></div>
          <div className="ticket-symbol"><strong>{ticker(symbol)}</strong><span>{exchange(symbol)} · Equity</span></div>
          <form onSubmit={submit}>
            <div className="side tabs">{['BUY','SELL'].map(s => <button type="button" key={s} className={side === s ? 'selected' : ''} aria-pressed={side === s} onClick={() => setSide(s)}>{s === 'BUY' ? 'Buy' : 'Sell'}</button>)}</div>
            <label>Order type<select value={kind} onChange={e => setKind(e.target.value)}><option value="MARKET">Market</option><option value="LIMIT">Limit</option><option value="STOP">Stop market</option></select></label>
            <label>Quantity<input type="number" min="1" max="100000" step="1" required value={quantity} onChange={e => setQuantity(e.target.value)}/></label>
            {kind !== 'MARKET' && <label>{kind === 'LIMIT' ? 'Limit price (₹)' : 'Stop trigger (₹)'}<input type="number" min="0.01" max="1000000" step="0.01" required value={trigger} onChange={e => setTrigger(e.target.value)}/></label>}
            <dl className="est"><div><dt>Order value</dt><dd>{money(notional)}</dd></div><div><dt>Estimated fee</dt><dd>{money(estFee)}</dd></div><div><dt>Buying power</dt><dd>{money(account?.available)}</dd></div>
              {secured && <><div><dt>Cash balance</dt><dd>{money(account?.cash)}</dd></div><div><dt>Reserved cash</dt><dd>{money(account?.reserved)}</dd></div></>}
              {secured && side === 'SELL' && <div><dt>Available shares</dt><dd>{account?.holdings.find(h => h.symbol === symbol)?.availableQuantity ?? 0}</dd></div>}</dl>
            <button className="primary" disabled={busy || pending || metadataOnly || !active || (mode === "live" && !active.liveConfigured) || (kind === 'MARKET' && (!tradeable || !!error))}>{busy ? 'Submitting…' : 'Place paper ' + side.toLowerCase()}</button>
            <p className="ticket-note">{metadataOnly ? 'Demo trading unavailable for this listing.' : kind === 'MARKET' && !tradeable ? 'Waiting for a tradeable quote.' : 'Fills use virtual funds and simulated fees.'}</p>
            {pending && <div className="feedback">
              <p>Confirm the previous submission before placing another order.</p>
              <button type="button" className="quiet" disabled={busy} onClick={() => reconcile()}>Resolve pending order</button>
            </div>}
            {orderMessage && <p className="feedback" role="status">{orderMessage}</p>}
          </form>
        </section>
      </div>
      <div className={secured ? "lower-grid single-portfolio" : "lower-grid"}>
        <section className="panel portfolio" id="portfolio">
          <div className="panel-title"><ListOrdered size={16}/><h2>Paper portfolio</h2><button className="quiet export" disabled={!account?.orders.length} onClick={download}><ArrowDownToLine size={14}/> CSV</button></div>
          <div className="balances">{[['Equity',account?.equity],['Available cash',account?.available],['Unrealized P&L',account?.unrealized],['Realized P&L',account?.realized]].map(([label,v]) =>
            <div key={label}><span>{label}</span><strong className="mono">{money(v)}</strong></div>)}</div>
          <div className="tabs account-tabs">{['Positions', 'Orders'].map(t => <button key={t} className={accountTab === t ? 'selected' : ''} onClick={() => setAccountTab(t)}>{t} <span>{t === 'Orders' ? (secured ? '' : working.length + ' open') : account?.holdings.length ?? 0}</span></button>)}</div>
          <div className="table-scroll">
            {accountTab === 'Positions' ? <table><thead><tr><th>Symbol</th><th>Shares</th><th>Avg. cost</th><th>Market value</th><th>P&L</th></tr></thead><tbody>
              {account?.holdings.map(h => <tr key={h.symbol}><th scope="row">{h.symbol}</th><td>{h.quantity}</td><td>{money(h.cost / h.quantity)}</td><td>{money(h.value)}</td><td className={tone(h.pnl)}>{money(h.pnl)}</td></tr>)}
              {!account?.holdings.length && <tr><td colSpan="5" className="empty">Your filled positions will appear here.</td></tr>}
            </tbody></table> : <table><thead><tr><th>Symbol</th><th>Order</th><th>Shares</th><th>Price</th>{secured && <th>Fee</th>}<th>Status</th></tr></thead><tbody>
              {account?.orders.map(o => <tr key={o.id}><th scope="row">{o.symbol}</th><td>{o.side} {o.kind}</td><td>{o.quantity}</td><td>{money(o.fill ?? o.trigger)}</td>{secured && <td>{money(o.fee)}</td>}<td>{secured && o.status === 'OPEN' && 'OPEN · '}{o.status === 'OPEN' ? <button className="quiet" disabled={busy} onClick={() => cancel(o.id)} aria-label={'Cancel order for ' + o.symbol}>Cancel</button> : secured && o.status === "REJECTED" ? describeOrder(o, money) : o.status}</td></tr>)}
              {!account?.orders.length && <tr><td colSpan={secured ? 6 : 5} className="empty">No paper orders yet.</td></tr>}
            </tbody></table>}
          </div>
          {secured && accountTab === 'Orders' && <div className="account-tabs tabs">
            <button disabled={!offset} onClick={() => setOffset(Math.max(0, offset - 25))}>Previous</button>
            <button disabled={active?.nextOffset == null} onClick={() => setOffset(active.nextOffset)}>Next</button>
          </div>}
        </section>
        {!secured && <section className="panel research" id="research">
          <div className="panel-title"><FlaskConical size={16}/><h2>Research lab</h2></div>
          <p className="muted">Explore price behavior, portfolio exposure and risk metrics.</p>
          <div className="research-actions"><button className="quiet" disabled={analysisBusy || !active} onClick={() => analyze('stock')}>Analyze {ticker(symbol)}</button>
            <button className="quiet" disabled={analysisBusy || !active} onClick={() => analyze('portfolio')}>Analyze portfolio</button></div>
          {analysisBusy && <p className="muted" role="status">Calculating observations…</p>}
          {analysisError && <p role="alert">{analysisError}</p>}
          {visibleReport ? <div className="report" aria-live="polite"><small>{visibleReport.subject} · {visibleReport.source}</small>
            {visibleReport.observations.map(o => <div key={o.id} className={o.id === visibleReport.focus ? 'observation focus' : 'observation'}>
              <div><strong>{o.label}</strong><b className="mono">{o.value}</b></div><p>{o.detail}</p></div>)}
            {!visibleReport.observations.length && <p className="muted">There is insufficient market data for this report.</p>}
          </div> : !analysisBusy && <div className="research-note"><Clock3 size={15}/> Observations use available data and explain how each metric is calculated.</div>}
        </section>}
      </div>
      <footer>Tape · Paper trading workspace <span>All balances and orders are simulated.</span></footer>
    </main>
  </>;
}
createRoot(document.getElementById('root')).render(<TapeEntry Terminal={App} Chart={Chart} />);
