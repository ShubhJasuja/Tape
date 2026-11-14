
import React, { useCallback, useEffect, useState } from 'react';
import { ChartCandlestick, Layers3, Search, Star } from 'lucide-react';
import './marketData.css';

const format = new Intl.NumberFormat('en-IN', { maximumFractionDigits: 4 });
const timestamp = new Intl.DateTimeFormat('en-IN', {
  timeZone: 'Asia/Kolkata', dateStyle: 'medium', timeStyle: 'medium'
});
const date = value => value ? timestamp.format(new Date(value)) + ' IST' : '—';
const value = n => n == null ? '—' : format.format(n);
const labels = { DISCONNECTED: 'Disconnected', CONFIGURED: 'Ready to connect', TOKEN_REQUIRED: 'Token required',
  CONNECTING: 'Connecting', CONNECTED: 'Connected', MARKET_AUTH_FAILED: 'Credentials rejected',
  MARKET_RATE_LIMITED: 'Rate limited', STALE: 'Stale trade', UNKNOWN: 'Freshness unknown', RECENT: 'Recent trade',
  UNAVAILABLE: 'Quote unavailable' };
const hints = { MARKET_AUTH_FAILED: 'Upstox rejected the credential. Renew it on the server.',
  MARKET_TOKEN_REQUIRED: 'Set the authorized token on the server.',
  MARKET_PERMISSION_REQUIRED: 'This account has no permission for this data.',
  MARKET_RATE_LIMITED: 'The feed is rate limited. Try again shortly.',
  MARKET_TIMEOUT: 'The provider timed out. Try again.',
  MARKET_QUOTE_UNAVAILABLE: 'Upstox returned no quote for this instrument.' };
const intervalLabel = { DAILY: 'Daily', MINUTE: '1 min', FIVE_MINUTES: '5 min' };
const empty = [];

function useRead(read, path, delay = 0) {
  const [state, setState] = useState(null);
  useEffect(() => {
    if (!path) return;
    const request = new AbortController();
    const timer = setTimeout(() => {
      read(path, request.signal).then(data => {
        if (!request.signal.aborted) setState({ path, data });
      }).catch(error => {
        if (!request.signal.aborted) setState({ path, error: error.message });
      });
    }, delay);
    return () => { clearTimeout(timer); request.abort(); };
  }, [read, path, delay]);
  return path && state?.path === path ? state : {};
}

export default function MarketData({ signOut, Chart }) {
  const read = useCallback(async (path, signal, method = 'GET', payload) => {
    const headers = { Accept: 'application/json' };
    if (method !== 'GET') {
      const response = await fetch('/api/v1/auth/csrf', { signal });
      if (!response.ok) throw new Error('Could not verify the session.');
      const csrf = await response.json();
      headers[csrf.headerName] = csrf.token;
    }
    if (payload) headers['Content-Type'] = 'application/json';
    const response = await fetch('/api/v1/market-data' + path, { signal, method, headers,
      body: payload ? JSON.stringify(payload) : undefined, cache: 'no-store' });
    if (response.status === 401) signOut();
    if (!response.ok) {
      const problem = await response.json().catch(() => ({}));
      throw new Error(hints[problem.code] || 'Market data could not be loaded.');
    }
    return response.status === 204 ? null : response.json();
  }, [signOut]);

  const status = useRead(read, '/status');
  const capabilities = status.data?.capabilities || empty;
  const ready = status.data?.status === 'CONFIGURED';
  const can = name => ready && capabilities.includes(name);
  const [search, setSearch] = useState('');
  const [exchange, setExchange] = useState('');
  const [segment, setSegment] = useState('EQUITY');
  const [page, setPage] = useState(1);
  const [instrument, setInstrument] = useState(null);
  const [interval, setInterval] = useState('DAILY');
  const [expiry, setExpiry] = useState('');
  const [strike, setStrike] = useState('');
  const [streaming, setStreaming] = useState(false);
  const [stream, setStream] = useState(null);
  const [revision, setRevision] = useState(0);
  const [pinError, setPinError] = useState('');
  const key = instrument?.key;
  const params = options => '?' + new URLSearchParams(options);
  const searchPath = can('CATALOG') && search.trim() &&
    '/instruments' + params({ q: search.trim(), exchange, segment, page });
  const results = useRead(read, searchPath, 260);
  const snapshot = useRead(read, can('QUOTES') && key && '/quote' + params({ key }));
  const session = useRead(read, can('QUOTES') && instrument && '/exchange-status' + params({ exchange: instrument.exchange }));
  const history = useRead(read, can('HISTORY') && key && '/candles' + params({ key, interval }));
  const hasWatchlist = capabilities.includes('WATCHLIST');
  const watchlist = useRead(read, hasWatchlist && '/watchlist' + params({ revision }));
  const detail = useRead(read, can('CATALOG') && can('CATALOG_STORAGE') && instrument?.fromWatchlist &&
    '/instrument' + params({ key }));
  const underlying = instrument?.underlyingKey || ((instrument?.exchange === 'NSE' && ['INDEX', 'EQUITY'].includes(instrument.type)) ? key : '');
  const contracts = useRead(read, can('CATALOG') && can('DERIVATIVES') && underlying && '/options' + params({ key: underlying }));
  const activeExpiry = expiry || instrument?.expiry || contracts.data?.map(row => row.expiry).sort()[0] || '';
  const chain = useRead(read, can('OPTION_CHAIN') && can('DERIVATIVES') && underlying && activeExpiry &&
    '/option-chain' + params({ key: underlying, expiry: activeExpiry }));
  const streamEnabled = can('STREAM') && streaming && !!key;
  useEffect(() => {
    if (!streamEnabled) return;
    const controller = new AbortController();
    let timer;
    async function poll() {
      try {
        const data = await read('/stream' + params({ key }), controller.signal);
        if (!controller.signal.aborted) setStream({ key, ...data });
      } catch (failure) {
        if (!controller.signal.aborted) setStream({ key, status: 'DISCONNECTED', error: failure.message });
      }
      if (!controller.signal.aborted) timer = setTimeout(poll, 2000);
    }
    poll();
    return () => { clearTimeout(timer); controller.abort(); };
  }, [read, streamEnabled, key]);

  const tick = streamEnabled && stream?.key === key ? stream : null;
  const snap = snapshot.data;
  const streamQuote = tick?.quote;
  const quote = streamQuote && (!snap?.exchangeAt || streamQuote.exchangeAt >= snap.exchangeAt) ? streamQuote : snap;
  const freshness = quote?.freshness === 'RECENT' && quote.exchangeAt && Date.now() - Date.parse(quote.exchangeAt) > 30_000
    ? 'STALE' : quote?.freshness;
  const pinned = watchlist.data?.some(row => row.instrumentKey === key);
  const chart = React.useMemo(() => history.data?.candles.slice(-220).map(bar => ({
    ...bar, open: bar.open * 100, high: bar.high * 100, low: bar.low * 100, close: bar.close * 100
  })) || empty, [history.data]);
  const resultRows = results.data?.instruments || empty;
  const lots = new Map((contracts.data || empty).map(row => [row.key, row.lotSize]));
  const strikes = (chain.data?.strikes || empty).filter(row => !strike || String(row.strike).includes(strike.trim()));
  const expiries = [...new Set([instrument?.expiry, ...(contracts.data || empty).map(row => row.expiry)].filter(Boolean))].sort();

  function select(row) {
    setInstrument(row);
    setExpiry('');
    setStrike('');
    setStream(null);
    setStreaming(false);
    setPinError('');
  }

  async function pin() {
    try {
      setPinError('');
      if (pinned) await read('/watchlist' + params({ key }), undefined, 'DELETE');
      else await read('/watchlist', undefined, 'PUT', { instrumentKey: key });
      setRevision(n => n + 1);
    } catch (error) { setPinError(error.message); }
  }

  async function unpin(entry) {
    try {
      await read('/watchlist' + params({ key: entry }), undefined, 'DELETE');
      setRevision(n => n + 1);
    } catch (error) { setPinError(error.message); }
  }

  return <main className="market-workspace" id="market-data">
    <div className="heading market-heading"><div><h1>Market data</h1><p>NSE / BSE · Read-only</p></div>
      <p className="feed" role="status"><span className={'dot' + (tick?.status === 'CONNECTED' ? ' ready' : '')} />Upstox · {labels[tick?.status || status.data?.status] || (status.error ? 'Unavailable' : 'Checking connection')}</p></div>
    {!ready && <p className="notice" role="status">{status.error || (status.data?.status === 'TOKEN_REQUIRED'
      ? 'The server needs an authorized Upstox Analytics Token.' : status.data
        ? 'Provider data is disabled for this account. Demo trading is available in Paper accounts.'
        : 'Checking provider permissions…')}</p>}
    <div className="terminal-grid market-data-grid">
      <section className="panel markets" aria-label="Provider market watch">
        <div className="panel-title"><Layers3 size={16}/><h2>Market watch</h2></div>
        <label className="search"><Search size={16}/><input aria-label="Find provider instruments" placeholder="Symbol or company"
          maxLength={80} value={search} disabled={!can('CATALOG')} onChange={e => { setPage(1); setSearch(e.target.value); }}
          onKeyDown={e => { if (e.key === 'Enter' && resultRows.length) select(resultRows[0]); }}/></label>
        <div className="catalog-filters">
          <select aria-label="Provider exchange" value={exchange} disabled={!can('CATALOG')}
            onChange={e => { setPage(1); setExchange(e.target.value); }}>
            <option value="">All exchanges</option><option>NSE</option><option>BSE</option>
          </select>
          <select aria-label="Provider segment" value={segment} disabled={!can('CATALOG')}
            onChange={e => { setPage(1); setSegment(e.target.value); }}>
            <option value="EQUITY">Equities</option><option value="INDEX">Indices</option>
            {can('DERIVATIVES') && <><option value="FUTURES">Futures</option><option value="OPTIONS">Options</option></>}
          </select>
        </div>
        <div className="market-rows">
          {resultRows.map(row => <button className="stock" key={row.key} aria-pressed={row.key === key} onClick={() => select(row)}>
            <span><strong>{row.symbol}</strong><small>{row.exchange} · {row.name || row.type}</small></span>
          </button>)}
          {!resultRows.length && <p className="empty" role={results.error ? 'alert' : 'status'}>{results.error ||
            (searchPath ? results.data ? 'No matching listings.' : 'Searching Upstox…' : ready ? 'Search by symbol or company.' : 'Instrument search is disconnected.')}</p>}
        </div>
        {searchPath && <div className="catalog-filters catalog-pages">
          <button className="quiet" disabled={page <= 1} onClick={() => setPage(n => n - 1)}>Previous</button>
          <button className="quiet" disabled={!results.data?.nextPage} onClick={() => setPage(results.data.nextPage)}>Next</button>
        </div>}
        {hasWatchlist && <div className="provider-pins">
          <h3>Your watchlist</h3>
          {(watchlist.data || empty).map(({ instrumentKey }) => <div key={instrumentKey}>
            <button className="quiet" onClick={() => select({ fromWatchlist: true, key: instrumentKey, symbol: instrumentKey.split('|')[1],
              exchange: instrumentKey.slice(0, 3), type: instrumentKey.startsWith('NSE_INDEX|') ? 'INDEX' :
                instrumentKey.startsWith('NSE_FO|') ? 'CONTRACT' : 'EQUITY' })}>{instrumentKey}</button>
            <button className="quiet" onClick={() => unpin(instrumentKey)} aria-label={'Remove ' + instrumentKey}>×</button>
          </div>)}
          {!watchlist.data?.length && <p className="muted" role={watchlist.error ? 'alert' : 'status'}>{watchlist.error || (watchlist.data ? 'No instruments pinned.' : 'Loading watchlist…')}</p>}
        </div>}
      </section>

      <section className="panel chart" aria-label="Provider price and history">
        <div className="chart-title">
          <div><div className="instrument"><h2>{instrument?.symbol || 'Price and history'}</h2>{instrument && <span>{instrument.exchange}</span>}</div>
            {instrument && <ListingDetails listing={detail.data || instrument}/>}</div>
          {key && hasWatchlist && <button className="icon-button" type="button" aria-label={pinned ? 'Remove provider pin' : 'Pin provider instrument'}
            aria-pressed={!!pinned} onClick={pin}><Star size={17} fill={pinned ? 'currentColor' : 'none'}/></button>}
        </div>
        {pinError && <p className="empty" role="alert">{pinError}</p>}
        {key && <><div className="quote"><strong className="mono">{quote?.last == null ? '—' : '₹' + value(quote.last)}</strong>
          {quote?.change != null && <span>{quote.change > 0 && '+'}{value(quote.change)}
            {quote.percentChange != null && ' (' + value(quote.percentChange) + '%)'} vs previous close</span>}</div>
          <p className="provider-stamp muted" role="status">{tick?.error || snapshot.error || (quote
            ? (labels[freshness] || freshness) + ' · Last trade ' + date(quote.exchangeAt) + ' · Received ' + date(quote.receivedAt)
            : snapshot.data || !can('QUOTES') ? 'Quote unavailable.' : 'Waiting for a quote.')}
            {session.data && ' · ' + session.data.status.replaceAll('_', ' ').toLowerCase()}</p>
          {snap && <dl className="provider-ohlc mono">{[['Open','open'],['High','high'],['Low','low'],['Close','close'],['Volume','volume']].map(([label, field]) =>
            <div key={field}><dt>{label}</dt><dd>{value(snap[field])}</dd></div>)}</dl>}
          {can('STREAM') && <div className="provider-stamp"><button className="quiet" onClick={() => { setStream(null); setStreaming(!streaming); }}>
            {streaming ? 'Stop price updates' : 'Start price updates'}</button>
            {streaming && <span role="status"> {labels[tick?.status] || 'Connecting'}</span>}</div>}</>}
        <div className="chart-tools"><span><ChartCandlestick size={15}/> Historical candles</span>
          {key && can('HISTORY') && <div className="tabs">{status.data.intervals.map(item =>
            <button className={interval === item ? 'selected' : ''} aria-pressed={interval === item} key={item}
              onClick={() => setInterval(item)}>{intervalLabel[item]}</button>)}</div>}</div>
        {chart.length ? <><Chart candles={chart} symbol={instrument.exchange + ':' + instrument.symbol} interval={interval}/>
          <p className="provider-stamp muted">Upstox · Last bar {date(chart.at(-1).exchangeAt)} · Retrieved {date(history.data.receivedAt)}</p></> :
          <p className="empty-chart" role={history.error ? 'alert' : 'status'}>{history.error ||
            (key && can('HISTORY') && !history.data ? 'Loading historical candles…' : 'Historical candles unavailable.')}</p>}
      </section>
    </div>

    <section className="panel provider-chain" aria-labelledby="provider-chain-title">
      <div className="panel-title"><h2 id="provider-chain-title">Options chain</h2></div>
      {underlying && can('DERIVATIVES') && <div className="catalog-filters contract-controls">
        <label>Expiry<select aria-label="Option expiry" value={activeExpiry} onChange={e => setExpiry(e.target.value)}>
          {!expiries.length && <option value="">No available expiries</option>}
          {expiries.map(item => <option key={item}>{item}</option>)}
        </select></label>
        <label>Strike<input inputMode="decimal" aria-label="Filter by strike" value={strike} onChange={e => setStrike(e.target.value)}/></label>
      </div>}
      {strikes.length > 0 ? <div className="provider-table" tabIndex={0} aria-label="Scrollable options table">
        <table><thead><tr><th scope="colgroup" colSpan={6}>Calls</th><th scope="col" rowSpan={2}>Strike</th>
          <th scope="colgroup" colSpan={6}>Puts</th></tr><tr>{['Call','Put'].flatMap(side => ['LTP','Volume','OI','Bid','Ask','Lot'].map(label =>
            <th scope="col" key={side + label}>{label}</th>))}</tr></thead>
          <tbody>{strikes.map(row => <tr key={row.strike}>{legCells(row.call, lots, 'call')}
            <th scope="row">{value(row.strike)}</th>{legCells(row.put, lots, 'put')}</tr>)}</tbody></table>
      </div> : <p className="empty" role={chain.error || contracts.error ? 'alert' : 'status'}>{chain.error || contracts.error ||
        (chain.data ? 'No strikes match the filter.' : !can('OPTION_CHAIN') ? 'Option-chain data is unavailable.' :
          !underlying ? 'Select an NSE equity or index.' : !activeExpiry && contracts.data ? 'No available expiries.' : 'Loading contracts…')}</p>}
      {chain.data && <p className="provider-stamp muted">Upstox · Retrieved {date(chain.data.receivedAt)} · Exchange timestamp unavailable</p>}
    </section>
  </main>;
}

function ListingDetails({ listing }) {
  const { name, segment, status, isin, expiry, strike, side, lotSize, tickSizePaise } = listing;
  return <p className="muted catalog-detail">{[name, segment, status, isin, expiry, strike && 'Strike ₹' + value(strike), side,
    lotSize != null && 'Lot ' + value(lotSize), tickSizePaise != null && 'Tick ' + value(tickSizePaise) + ' paise']
    .filter(Boolean).join(' · ')}</p>;
}

function legCells(leg, lots, side) {
  return ['last', 'volume', 'openInterest', 'bid', 'ask', 'lot'].map(field =>
    <td key={side + field}>{value(field === 'lot' ? lots.get(leg?.key) : leg?.[field])}</td>);
}
