
import React, { useCallback, useEffect, useState } from 'react';
import { ChartCandlestick, Layers3, Search, Star } from 'lucide-react';
import './marketData.css';

const format = new Intl.NumberFormat('en-IN', { maximumFractionDigits: 4 });
const timestamp = new Intl.DateTimeFormat('en-IN', {
  timeZone: 'Asia/Kolkata', dateStyle: 'medium', timeStyle: 'medium'
});
const istDay = new Intl.DateTimeFormat('en-CA', {
  timeZone: 'Asia/Kolkata', year: 'numeric', month: '2-digit', day: '2-digit'
});
const date = value => value ? timestamp.format(new Date(value)) + ' IST' : '—';
const value = n => n == null ? '—' : format.format(n);
const recent = (quote, now) => quote?.freshness === 'RECENT' && now - Date.parse(quote.exchangeAt) <= 30_000;
const labels = { DISCONNECTED: 'Disconnected', CONFIGURED: 'Ready to connect', TOKEN_REQUIRED: 'Token required',
  CONNECTING: 'Connecting', CONNECTED: 'Connected', MARKET_AUTH_FAILED: 'Credentials rejected',
  MARKET_PROVIDER_FORBIDDEN: 'Upstox access denied',
  MARKET_STREAM_AUTH_FAILED: 'Stream authorization rejected',
  MARKET_RATE_LIMITED: 'Rate limited', STALE: 'Stale trade', UNKNOWN: 'Freshness unknown', RECENT: 'Recent trade',
  UNAVAILABLE: 'Quote unavailable' };
const hints = { MARKET_AUTH_FAILED: 'Upstox rejected the credential. Renew it on the server.',
  MARKET_TOKEN_REQUIRED: 'Set the authorized token on the server.',
  MARKET_PERMISSION_REQUIRED: 'This account has no permission for this data.',
  MARKET_PROVIDER_FORBIDDEN: 'Upstox denied this request. Check the account’s provider access.',
  MARKET_RATE_LIMITED: 'The feed is rate limited. Try again shortly.',
  MARKET_TIMEOUT: 'The provider timed out. Try again.',
  MARKET_QUOTE_UNAVAILABLE: 'Upstox returned no quote for this instrument.',
  MARKET_WATCHLIST_UNAVAILABLE: 'This watchlist no longer exists.',
  INVALID_MARKET_REQUEST: 'Check the instrument, date range or watchlist name.' };
const intervalLabel = { DAILY: 'Daily', MINUTE: '1 min', FIVE_MINUTES: '5 min' };
const chainColumns = [
  ['LTP', 'last'], ['Bid', 'bid'], ['Ask', 'ask'], ['Bid qty', 'bidQuantity'], ['Ask qty', 'askQuantity'],
  ['Volume', 'volume'], ['OI', 'openInterest'], ['Prev OI', 'previousOpenInterest'], ['Close', 'close'],
  ['IV %', 'iv'], ['Delta', 'delta'], ['Gamma', 'gamma'], ['Theta', 'theta'], ['Vega', 'vega'], ['POP %', 'probabilityOfProfit'], ['Lot', 'lot']
];
const empty = [];

function useRead(read, path, delay = 0, refresh = 0) {
  const [state, setState] = useState(null);
  useEffect(() => {
    if (!path) return;
    const request = new AbortController();
    let timer = setTimeout(load, delay);
    async function load() {
      try {
        if (!refresh || !document.hidden) {
          const data = await read(path, request.signal);
          if (!request.signal.aborted) setState({ path, data });
        }
      } catch (error) {
        if (!request.signal.aborted) setState({ path, error: error.message });
      } finally {
        if (refresh && !request.signal.aborted) timer = setTimeout(load, refresh);
      }
    }
    return () => { clearTimeout(timer); request.abort(); };
  }, [read, path, delay, refresh]);
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
  const [interval, setHistoryInterval] = useState('DAILY');
  const [chartMode, setChartMode] = useState('history');
  const [searchExpiry, setSearchExpiry] = useState('');
  const [expiry, setExpiry] = useState('');
  const [strike, setStrike] = useState('');
  const [chainDetail, setChainDetail] = useState(false);
  const [streaming, setStreaming] = useState(false);
  const [stream, setStream] = useState(null);
  const [now, setNow] = useState(Date.now);
  const [revision, setRevision] = useState(0);
  const [pinError, setPinError] = useState('');
  const [watchlistName, setWatchlistName] = useState('Default');
  const [newWatchlist, setNewWatchlist] = useState('');
  const key = instrument?.key;
  const params = options => '?' + new URLSearchParams(options);
  const detail = useRead(read, can('CATALOG') && can('CATALOG_STORAGE') && instrument?.fromWatchlist &&
    '/instrument' + params({ key }));
  const listing = detail.data || instrument;
  const withinScope = listing && !['EXPIRED', 'DELISTED', 'INACTIVE', 'SUSPENDED'].includes(listing.status) &&
    (!listing.expiry || listing.expiry >= istDay.format(now)) && (!key.startsWith('NSE_FO|') || can('DERIVATIVES'));
  const derivatives = ['FUTURES', 'OPTIONS'].includes(segment);
  const searchPath = can('CATALOG') && search.trim() &&
    '/instruments' + params({ q: search.trim(), exchange, segment, page, ...(derivatives && searchExpiry ? { expiry: searchExpiry } : {}) });
  const results = useRead(read, searchPath, 260);
  const snapshot = useRead(read, can('QUOTES') && withinScope && '/quote' + params({ key }));
  const session = useRead(read, can('QUOTES') && !can('CALENDAR') && withinScope && '/exchange-status' + params({ exchange: instrument.exchange }));
  const marketSession = useRead(read, can('CALENDAR') && withinScope && '/market-session' +
    params({ exchange: key.startsWith('NSE_FO|') ? 'NFO' : instrument.exchange }), 0, 60_000);
  const intraday = can('INTRADAY') && (!can('HISTORY') || chartMode === 'intraday');
  const chartInterval = intraday && interval === 'DAILY' ? 'FIVE_MINUTES' : interval;
  const history = useRead(read, withinScope && (intraday || can('HISTORY')) &&
    (intraday ? '/intraday' : '/candles') + params({ key, interval: chartInterval }), 0, intraday ? 30_000 : 0);
  const hasWatchlist = capabilities.includes('WATCHLIST');
  const lists = useRead(read, hasWatchlist && '/watchlists' + params({ revision }));
  const watchlist = useRead(read, hasWatchlist && '/watchlist' + params({ name: watchlistName, revision }));
  const underlying = listing?.underlyingKey || ((listing?.exchange === 'NSE' && ['INDEX', 'EQUITY'].includes(listing.type)) ? key : '');
  const contracts = useRead(read, can('CATALOG') && can('DERIVATIVES') && underlying && '/options' + params({ key: underlying }));
  const today = istDay.format(now);
  const expiries = [...new Set([listing?.expiry, ...(contracts.data || empty).map(row => row.expiry)]
    .filter(item => item && item >= today))].sort();
  const activeExpiry = expiries.includes(expiry) ? expiry :
    expiries.includes(listing?.expiry) ? listing.expiry : expiries[0] || '';
  const chain = useRead(read, can('OPTION_CHAIN') && can('DERIVATIVES') && underlying && activeExpiry &&
    '/option-chain' + params({ key: underlying, expiry: activeExpiry }));
  const watchKeys = (watchlist.data || empty).map(row => row.instrumentKey);
  const streamKeys = [...new Set([...(withinScope ? [key] : []), ...watchKeys])]
    .filter(item => !item.startsWith('NSE_FO|') || can('DERIVATIVES')).slice(0, 16).sort().join(',');
  const streamEnabled = can('STREAM') && streaming && !!streamKeys;
  useEffect(() => {
    if (!key && !streaming) return;
    setNow(Date.now());
    const timer = setInterval(() => setNow(Date.now()), 2_000);
    return () => clearInterval(timer);
  }, [key, streaming]);
  useEffect(() => {
    if (!streamEnabled) return;
    const controller = new AbortController();
    let timer;
    async function poll() {
      try {
        if (!document.hidden) {
          const data = await read('/streams' + params({ keys: streamKeys }), controller.signal);
          if (!controller.signal.aborted) setStream({ keys: streamKeys, ...data });
        }
      } catch (failure) {
        if (!controller.signal.aborted) setStream({ keys: streamKeys, status: 'DISCONNECTED', error: failure.message });
      }
      if (!controller.signal.aborted) timer = setTimeout(poll, 2000);
    }
    poll();
    return () => { clearTimeout(timer); controller.abort(); };
  }, [read, streamEnabled, streamKeys]);

  const tick = streamEnabled && stream?.keys === streamKeys ? stream : null;
  const snap = snapshot.data;
  const streamQuote = tick?.quotes?.[key];
  const quote = streamQuote && (!snap?.exchangeAt || Date.parse(streamQuote.exchangeAt) >= Date.parse(snap.exchangeAt))
    ? streamQuote : snap;
  const freshness = quote?.freshness === 'RECENT' && quote.exchangeAt && now - Date.parse(quote.exchangeAt) > 30_000
    ? 'STALE' : quote?.freshness;
  const pinned = watchlist.data?.some(row => row.instrumentKey === key);
  const chart = React.useMemo(() => history.data?.candles.slice(intraday ? -400 : -220).map(bar => ({
    ...bar, open: bar.open * 100, high: bar.high * 100, low: bar.low * 100, close: bar.close * 100
  })) || empty, [history.data, intraday]);
  const resultRows = results.data?.instruments || empty;
  const contractByKey = new Map((contracts.data || empty).map(row => [row.key, row]));
  const columns = chainDetail ? chainColumns : chainColumns.filter(([, field]) =>
    ['last', 'bid', 'ask', 'volume', 'openInterest', 'iv', 'lot'].includes(field));
  const strikes = (chain.data?.strikes || empty).filter(row => !strike || String(row.strike).includes(strike.trim()));
  const underlyingSpot = chain.data?.strikes.find(row => row.underlyingSpot != null)?.underlyingSpot;
  const window = marketSession.data?.windows?.find(row => Date.parse(row.closesAt) > now) || marketSession.data?.windows?.at(-1);

  function select(row) {
    setInstrument(row);
    setExpiry('');
    setStrike('');
    setPinError('');
  }

  function switchSegment(next) {
    setSegment(next);
    setExchange('');
    setSearchExpiry('');
    setPage(1);
  }

  async function changeList(event) {
    event.preventDefault();
    const name = newWatchlist.trim();
    if (!name) return;
    try {
      await read('/watchlists', undefined, 'POST', { name });
      setNewWatchlist('');
      setWatchlistName(name);
      setPinError('');
      setRevision(n => n + 1);
    } catch (error) { setPinError(error.message); }
  }

  async function removeList() {
    try {
      await read('/watchlists' + params({ name: watchlistName }), undefined, 'DELETE');
      setWatchlistName('Default');
      setPinError('');
      setRevision(n => n + 1);
    } catch (error) { setPinError(error.message); }
  }

  async function pin() {
    try {
      setPinError('');
      if (pinned) await read('/watchlist' + params({ key, name: watchlistName }), undefined, 'DELETE');
      else await read('/watchlist' + params({ name: watchlistName }), undefined, 'PUT', { instrumentKey: key });
      setRevision(n => n + 1);
    } catch (error) { setPinError(error.message); }
  }

  async function unpin(entry) {
    try {
      await read('/watchlist' + params({ key: entry, name: watchlistName }), undefined, 'DELETE');
      setRevision(n => n + 1);
    } catch (error) { setPinError(error.message); }
  }

  return <main className="market-workspace" id="market-data">
    <div className="heading market-heading"><div><h1>Market data</h1><p>NSE / BSE · Read-only provider data</p></div>
      <p className="feed" role="status"><span className={'dot' + (tick?.status === 'CONNECTED' ? ' ready' : '')} />Upstox · {labels[tick?.status || status.data?.status] || (status.error ? 'Unavailable' : 'Checking connection')}</p></div>
    {!ready && <p className="notice" role="status">{status.error || (status.data?.status === 'TOKEN_REQUIRED'
      ? 'The server needs an authorized Upstox Analytics Token.' : status.data
        ? 'Provider data is disabled for this account. Demo trading is available in Paper accounts.'
        : 'Checking provider permissions…')}</p>}
    <div className="terminal-grid market-data-grid">
      <section className="panel markets" aria-label="Provider market watch">
        <div className="panel-title"><Layers3 size={16}/><h2>Market watch</h2></div>
        <div className="provider-segments" role="group" aria-label="Provider segment">
          {[['EQUITY', 'Equities'], ['INDEX', 'Indices'], ['FUTURES', 'Futures'], ['OPTIONS', 'Options']].map(([code, label]) =>
            <button type="button" key={code} className="quiet" aria-pressed={segment === code} onClick={() => switchSegment(code)}
              disabled={!can('CATALOG') || (['FUTURES', 'OPTIONS'].includes(code) && !can('DERIVATIVES'))}>{label}</button>)}
        </div>
        <label className="search"><Search size={16}/><input aria-label="Find provider instruments" placeholder="Symbol or company"
          maxLength={50} value={search} disabled={!can('CATALOG')} onChange={e => { setPage(1); setSearch(e.target.value); }}
          onKeyDown={e => { if (e.key === 'Enter' && resultRows.length) select(resultRows[0]); }}/></label>
        <div className="catalog-filters">
          <select aria-label="Provider exchange" value={exchange} disabled={!can('CATALOG')}
            onChange={e => { setPage(1); setExchange(e.target.value); }}>
            <option value="">{derivatives ? 'NSE' : 'All exchanges'}</option>
            {!derivatives && <><option>NSE</option><option>BSE</option></>}
          </select>
          {derivatives && <input type="date" min={today} aria-label="Filter contracts by expiry" value={searchExpiry}
            onChange={e => { setPage(1); setSearchExpiry(e.target.value); }}/>}
        </div>
        {segment !== 'EQUITY' && can('CATALOG') && <div className="catalog-filters">
          {['Nifty 50', 'Bank Nifty'].map(name => <button key={name} type="button" className="quiet"
            onClick={() => { setExchange('NSE'); setPage(1); setSearch(name); }}>{name}</button>)}
        </div>}
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
          <h3>Your watchlists</h3>
          <div>
            <select aria-label="Personal watchlist" value={watchlistName} onChange={event => { setWatchlistName(event.target.value); setPinError(''); }}>
              {[...new Set(['Default', ...(lists.data || empty).map(row => row.name), watchlistName])].map(name => <option key={name}>{name}</option>)}
            </select>
            {watchlistName !== 'Default' && <button type="button" className="quiet" onClick={removeList}>Delete list</button>}
          </div>
          <form className="provider-watchlist-form" onSubmit={changeList}>
            <input aria-label="New watchlist name" placeholder="New watchlist" maxLength={40} value={newWatchlist}
              onChange={event => setNewWatchlist(event.target.value)}/>
            <button type="submit" className="quiet" disabled={!newWatchlist.trim()}>Create</button>
          </form>
          {pinError && <p className="muted" role="alert">{pinError}</p>}
          {lists.error && <p className="muted" role="alert">{lists.error}</p>}
          {watchKeys.map(instrumentKey => <div className="provider-pin" key={instrumentKey}>
            <button className="quiet" onClick={() => select({ fromWatchlist: true, key: instrumentKey, symbol: instrumentKey.split('|')[1],
              exchange: instrumentKey.slice(0, 3), type: instrumentKey.includes('_INDEX|') ? 'INDEX' :
                instrumentKey.startsWith('NSE_FO|') ? 'CONTRACT' : 'EQUITY' })}>{instrumentKey}</button>
            {streaming && <span className="mono" title={recent(tick?.quotes?.[instrumentKey], now) ? 'Recent trade' : 'No recent trade'}>
              {recent(tick?.quotes?.[instrumentKey], now) ? '₹' + value(tick.quotes[instrumentKey].last) : '—'}</span>}
            <button className="quiet" onClick={() => unpin(instrumentKey)} aria-label={'Remove ' + instrumentKey}>×</button>
          </div>)}
          {!watchlist.data?.length && <p className="muted" role={watchlist.error ? 'alert' : 'status'}>{watchlist.error || (watchlist.data ? 'No instruments pinned.' : 'Loading watchlist…')}</p>}
        </div>}
      </section>

      <section className="panel chart" aria-label="Provider price and history">
        <div className="chart-title">
          <div><div className="instrument"><h2>{listing?.symbol || 'Price and history'}</h2>{listing && <span>{listing.exchange}</span>}</div>
            {listing && <ListingDetails listing={listing}/>}</div>
          {key && hasWatchlist && <button className="icon-button" type="button" aria-label={pinned ? 'Remove provider pin' : 'Pin provider instrument'}
            aria-pressed={!!pinned} onClick={pin}><Star size={17} fill={pinned ? 'currentColor' : 'none'}/></button>}
        </div>
        {key && <><div className="quote"><strong className="mono">{quote?.last == null ? '—' : '₹' + value(quote.last)}</strong>
          {quote?.change != null && <span>{quote.change > 0 && '+'}{value(quote.change)}
            {quote.percentChange != null && ' (' + value(quote.percentChange) + '%)'} vs previous close</span>}</div>
          <p className="provider-stamp muted" role="status">{tick?.error || snapshot.error || (quote
            ? (labels[freshness] || freshness) + ' · Last trade ' + date(quote.exchangeAt) +
              (quote.providerAt ? ' · Provider ' + date(quote.providerAt) : '') + ' · Received ' + date(quote.receivedAt)
            : snapshot.data || !can('QUOTES') || !withinScope ? 'Quote unavailable.' : 'Waiting for a quote.')}
            {(quote?.marketStatus && quote.marketStatus !== 'UNKNOWN' ? quote.marketStatus : session.data?.status)
              ?.replaceAll('_', ' ').toLowerCase().replace(/^/, ' · ')}</p>
          {snap && <dl className="provider-ohlc mono">{[['Open','open'],['High','high'],['Low','low'],['Close','close'],['Volume','volume']].map(([label, field]) =>
            <div key={field}><dt>{label}</dt><dd>{value(snap[field])}</dd></div>)}</dl>}
          {marketSession.data && <p className="provider-stamp muted">{marketSession.data.exchange} session · {marketSession.data.status.toLowerCase()}
            {marketSession.data.holiday && ' · ' + marketSession.data.holiday}{window && ' · ' + date(window.opensAt) + ' to ' + date(window.closesAt)}</p>}
          {marketSession.error && <p className="provider-stamp muted" role="status">Exchange calendar unavailable. {marketSession.error}</p>}</>}
        {can('STREAM') && streamKeys && <div className="provider-stamp"><button className="quiet" onClick={() => { setStream(null); setStreaming(!streaming); }}>
          {streaming ? 'Stop price updates' : 'Start price updates'}</button>
          {streaming && <span role="status"> {labels[tick?.status] || 'Connecting'} · {streamKeys.split(',').length} instruments</span>}</div>}
        <div className="chart-tools"><span><ChartCandlestick size={15}/> {intraday ? 'Current session' : 'Historical candles'}</span>
          {key && <div className="tabs provider-chart-tabs">
            {can('HISTORY') && can('INTRADAY') && <>
              <button aria-pressed={!intraday} onClick={() => setChartMode('history')}>History</button>
              <button aria-pressed={intraday} onClick={() => setChartMode('intraday')}>Today</button>
            </>}
            {(intraday ? ['MINUTE', 'FIVE_MINUTES'] : status.data?.intervals || empty).map(item =>
              <button aria-pressed={chartInterval === item} key={item} onClick={() => setHistoryInterval(item)}>{intervalLabel[item]}</button>)}
          </div>}</div>
        {status.data?.displayDelay && status.data.displayDelay !== 'NONE' && can('HISTORY') &&
          <p className="provider-stamp muted">Delayed history · Minimum age {status.data.displayDelay}</p>}
        {chart.length ? <><Chart candles={chart} symbol={listing.exchange + ':' + listing.symbol} interval={chartInterval}/>
          <p className="provider-stamp muted">Upstox · Last bar {date(chart.at(-1).exchangeAt)} · Retrieved {date(history.data.receivedAt)}
            {intraday && ' · Current bar may be incomplete'}</p></> :
          <p className="empty-chart" role={history.error ? 'alert' : 'status'}>{history.error ||
            (withinScope && (intraday || can('HISTORY')) && !history.data ? 'Loading candles…' : 'No candles available.')}</p>}
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
        {strikes.length > 0 && <button type="button" className="quiet" aria-pressed={chainDetail}
          onClick={() => setChainDetail(!chainDetail)}>{chainDetail ? 'Show fewer fields' : 'Show all fields'}</button>}
      </div>}
      {strikes.length > 0 ? <div className="provider-table" tabIndex={0} aria-label="Scrollable options table">
        <table><thead><tr><th scope="colgroup" colSpan={columns.length}>Calls (CE)</th><th scope="col" rowSpan={2}>Strike</th>
          <th scope="colgroup" colSpan={columns.length}>Puts (PE)</th></tr><tr>{['Call','Put'].flatMap(side => columns.map(([label]) =>
            <th scope="col" key={side + label}>{label}</th>))}</tr></thead>
          <tbody>{strikes.map(row => <tr key={row.strike}>{legCells(row.call, contractByKey, 'CE', row.strike, columns, select)}
            <th scope="row">{value(row.strike)}{chainDetail && row.putCallRatio != null && <small className="muted">PCR {value(row.putCallRatio)}</small>}</th>
            {legCells(row.put, contractByKey, 'PE', row.strike, columns, select)}</tr>)}</tbody></table>
      </div> : <p className="empty" role={chain.error || contracts.error ? 'alert' : 'status'}>{chain.error || contracts.error ||
        (chain.data ? 'No strikes match the filter.' : !can('OPTION_CHAIN') ? 'Option-chain data is unavailable.' :
          !underlying ? 'Select an NSE equity or index.' : !activeExpiry && contracts.data ? 'No available expiries.' : 'Loading contracts…')}</p>}
      {chain.data && <p className="provider-stamp muted">Upstox · Retrieved {date(chain.data.receivedAt)} · Exchange timestamp unavailable
        {underlyingSpot != null && ' · Underlying ₹' + value(underlyingSpot)}</p>}
    </section>
  </main>;
}

function ListingDetails({ listing }) {
  const { name, segment, status, isin, expiry, strike, side, lotSize, tickSizePaise, quantityMultiplier, minimumLot, freezeQuantity, weekly } = listing;
  return <p className="muted catalog-detail">{[name, segment, status, isin, expiry, strike && 'Strike ₹' + value(strike), side,
    lotSize != null && 'Lot ' + value(lotSize), tickSizePaise != null && 'Tick ' + value(tickSizePaise) + ' paise',
    quantityMultiplier != null && 'Multiplier ' + value(quantityMultiplier),
    minimumLot != null && minimumLot !== lotSize && 'Minimum lot ' + value(minimumLot),
    freezeQuantity != null && 'Freeze quantity ' + value(freezeQuantity), weekly && 'Weekly']
    .filter(Boolean).join(' · ')}</p>;
}

function legCells(leg, contracts, side, strike, columns, select) {
  const contract = contracts.get(leg?.key);
  return columns.map(([, field]) => {
    const text = value(field === 'lot' ? contract?.lotSize : leg?.[field] ?? leg?.greeks?.[field]);
    return <td key={side + field}>{field === 'last' && contract
      ? <button type="button" className="quiet" aria-label={'Open ' + value(strike) + ' ' + side + ' contract'} onClick={() => select(contract)}>{text}</button>
      : text}</td>;
  });
}
