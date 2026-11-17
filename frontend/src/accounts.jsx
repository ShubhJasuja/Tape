import React, { useCallback, useEffect, useState } from 'react';
import { Activity } from 'lucide-react';
import './accounts.css';
import MarketData from './marketData.jsx';
import OptionsWorkspace from './options.jsx';

const api = '/api/v1';
const amount = new Intl.NumberFormat('en-IN', { style: 'currency', currency: 'INR' });

async function request(path, options) {
  return fetch(api + path, { credentials: 'same-origin', cache: 'no-store', ...options });
}

async function write(path, body, contentType, method = 'POST') {
  const tokenResponse = await request('/auth/csrf');
  if (!tokenResponse.ok) throw new Error('Could not prepare a secure request. Please try again.');
  const { headerName, token } = await tokenResponse.json();
  const headers = { [headerName]: token };
  if (contentType) headers['Content-Type'] = contentType;
  return request(path, { method, headers, body });
}

export default function TapeEntry({ Terminal, Chart }) {
  const [phase, setPhase] = useState('loading');
  const [user, setUser] = useState(null);
  const [error, setError] = useState('');

  const signOut = useCallback(() => setUser(null), []);

  async function restore() {
    setError('');
    if (/^#(?:verify|reset)=/.test(location.hash)) {
      setUser(null);
      setPhase('accounts');
      return;
    }
    try {
      const response = await request('/auth/me');
      if (response.status === 404) {
        setUser(null);
        setPhase('legacy');
      } else if (response.status === 401) {
        setUser(null);
        setPhase('accounts');
      } else if (response.ok) {
        setUser(await response.json());
        setPhase('accounts');
      } else throw new Error('The Tape server is unavailable. Please try again.');
    } catch {
      setError('Could not connect to Tape. Check that the server is running.');
      setPhase('error');
    }
  }

  useEffect(() => { restore(); }, []);
  if (phase === 'legacy') return <Terminal />;
  if (phase === 'loading') return <main className="account-page"><p role="status">Connecting to Tape…</p></main>;
  if (phase === 'error') return <main className="account-page"><p role="alert">{error}</p>
    <button className="quiet" onClick={restore}>Try again</button></main>;
  return <AccountWorkspace user={user} restore={restore} signOut={signOut} Terminal={Terminal} Chart={Chart} />;
}

function AccountWorkspace({ user, restore, signOut, Terminal, Chart }) {
  const [workspace, setWorkspace] = useState('paper');
  const [registering, setRegistering] = useState(false);
  const [email, setEmail] = useState('');
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState('');
  const [error, setError] = useState('');
  const [accounts, setAccounts] = useState(null);
  const [selection, setSelection] = useState('');
  const [identity, setIdentity] = useState(() => {
    const values = new URLSearchParams(location.hash.slice(1));
    for (const kind of ['verify', 'reset']) {
      const token = values.get(kind);
      if (token) {
        history.replaceState(null, '', location.pathname + location.search);
        return { kind, token };
      }
    }
    return null;
  });

  useEffect(() => {
    if (!user) {
      setAccounts(null);
      setSelection('');
      return;
    }
    const controller = new AbortController();
    request('/accounts', { signal: controller.signal }).then(async response => {
      if (controller.signal.aborted) return;
      if (response.status === 401) {
        setMessage('Your session expired. Sign in again.');
        signOut();
      } else if (response.ok) {
        const items = await response.json();
        if (!controller.signal.aborted) {
          setAccounts(items);
          setSelection(items[0]?.id || '');
        }
      } else throw new Error('Could not load your accounts.');
    }).catch(failure => {
      if (!controller.signal.aborted) setError(failure.message || 'Could not load your accounts.');
    });
    return () => controller.abort();
  }, [user]);

  async function submit(event) {
    event.preventDefault();
    const form = event.currentTarget;
    const password = new FormData(form).get('password');
    setBusy(true);
    setError('');
    setMessage('');
    try {
      const response = registering
        ? await write('/auth/register', JSON.stringify({ email, password }), 'application/json')
        : await write('/auth/login', new URLSearchParams({ email, password }), 'application/x-www-form-urlencoded');
      if (response.status === 429) throw new Error('Too many attempts. Wait a minute and try again.');
      if (registering && response.status === 503) throw new Error('Email verification is temporarily unavailable. Please try again later.');
      if (registering && response.status === 202) {
        setRegistering(false);
        setMessage('If registration is available for this email, a verification link will arrive shortly.');
      } else if (registering && response.status === 201) {
        setRegistering(false);
        setMessage('Account created. Sign in to view your virtual balances.');
      } else if (!registering && response.status === 204) {
        setEmail('');
        await restore();
      } else if (registering && response.status === 409) {
        throw new Error('An account could not be created with that email. Try signing in.');
      } else if (registering && response.status === 400) {
        throw new Error('Enter a valid email and a password of at least 12 characters and at most 72 UTF-8 bytes.');
      } else if (!registering && response.status === 401) {
        throw new Error('Invalid email or password.');
      } else throw new Error('The request could not be completed. Please try again.');
    } catch (failure) {
      setError(failure.message || 'The request failed.');
    } finally {
      const field = form.elements.namedItem('password');
      if (field) field.value = '';
      setBusy(false);
    }
  }

  async function logout() {
    setBusy(true);
    setError('');
    setMessage('');
    try {
      const response = await write('/auth/logout');
      if (response.status !== 204 && response.status !== 401) throw new Error('Sign-out failed. Please try again.');
      signOut();
    } catch (failure) {
      setError(failure.message);
    } finally { setBusy(false); }
  }

  async function revokeSessions() {
    setBusy(true);
    try {
      const response = await write('/auth/sessions/revoke');
      if (!response.ok) throw new Error('Could not end your sessions. Try again.');
      signOut();
      setMessage('Signed out on all devices.');
    } catch (failure) { setError(failure.message); }
    finally { setBusy(false); }
  }

  const active = accounts?.find(account => account.id === selection);
  const demo = accounts?.find(account => account.mode === 'DEMO');
  const showMarket = !!user && workspace === 'market';
  const showOptions = !!user && workspace === 'options';
  const showFutures = !!user && workspace === 'futures';
  const showDerivatives = showOptions || showFutures;
  return <>
    <header className="top account-top">
      <a className="brand" href="#workspace"><span className="brand-mark"><Activity size={23}/></span> Tape
        <span className="brand-sub">PAPER TRADING</span></a>
      {user && <nav className="tabs" aria-label="Workspace">
        <button className={!showMarket && !showDerivatives ? 'selected' : ''} aria-pressed={!showMarket && !showDerivatives} onClick={() => setWorkspace('paper')}>Paper accounts</button>
        <button className={showMarket ? 'selected' : ''} aria-pressed={showMarket} onClick={() => setWorkspace('market')}>Market data</button>
        <button className={showOptions ? 'selected' : ''} aria-pressed={showOptions} onClick={() => setWorkspace('options')}>Options practice</button>
        <button className={showFutures ? 'selected' : ''} aria-pressed={showFutures} onClick={() => setWorkspace('futures')}>Futures practice</button>
      </nav>}
      {user && <div className="account-session"><span>{user.email}</span>
        <button className="quiet" onClick={logout} disabled={busy}>Sign out</button></div>}
    </header>
    <main id={showDerivatives ? undefined : 'workspace'} className="account-page" hidden={showMarket || showDerivatives}>
      <section className="panel account-panel" aria-labelledby="account-title">
        <h1 id="account-title">{user ? 'Your paper accounts' : registering ? 'Create your account' : 'Sign in to Tape'}</h1>
        {error && <p className="notice" role="alert">{error}</p>}
        {message && <p className="notice" role="status">{message}</p>}
        {user ? <>
          <p>Account balances are virtual. Live-data trading is unavailable.</p>
          <button className="quiet" disabled={busy} onClick={revokeSessions}>Sign out on all devices</button>
          {!accounts && !error && <p role="status">Loading your accounts…</p>}
          {accounts?.length === 0 && <p>No accounts are available.</p>}
          {active && <>
            <label className="account-select">Account
              <select value={selection} onChange={event => setSelection(event.target.value)}>
                {accounts.map(account => <option key={account.id} value={account.id}>
                  {account.mode === 'DEMO' ? 'Demo' : 'Live data'}
                </option>)}
              </select>
            </label>
            {!active.tradingEnabled && <dl className="account-balances">
              <div><dt>Virtual balance</dt><dd>{amount.format(active.balancePaise / 100)}</dd></div>
              <div><dt>Reserved cash</dt><dd>{amount.format(active.reservedPaise / 100)}</dd></div>
            </dl>}
            <p className="account-note">{active.mode === 'LIVE_DATA' ? 'Live-data trading requires market-data permissions.' : active.tradingEnabled ? 'Demo trades use synthetic quotes and virtual cash.' : 'Demo trading is disabled on this server.'}</p>
          </>}
        </> : identity ? <IdentityForm flow={identity} write={write} onBack={() => { setIdentity(null); setRegistering(false); }} /> : <>
          <form className="account-form" onSubmit={submit}>
            <label>Email<input type="email" required maxLength={254} autoComplete="username"
              value={email} onChange={event => setEmail(event.target.value)} /></label>
            <label>Password<input name="password" type="password" required minLength={registering ? 12 : undefined}
              maxLength={72} autoComplete={registering ? 'new-password' : 'current-password'} /></label>
            {registering && <p className="account-note">Use at least 12 characters.</p>}
            <button className="primary" disabled={busy}>{registering ? 'Create account' : 'Sign in'}</button>
          </form>
          <button className="quiet" type="button" disabled={busy} onClick={() => {
            setRegistering(!registering); setError(''); setMessage('');
          }}>{registering ? 'Back to sign in' : 'Create an account'}</button>
          {!registering && <div className="identity-actions">
            <button className="quiet" onClick={() => setIdentity({ kind: 'recovery' })}>Forgot password?</button>
            <button className="quiet" onClick={() => setIdentity({ kind: 'verification' })}>Resend verification</button>
          </div>}
        </>}
      </section>
    </main>
    {showMarket ? <MarketData signOut={signOut} Chart={Chart} /> :
      showDerivatives ? (demo ?
        <OptionsWorkspace key={`${demo.id}:${workspace}`} desk={workspace} account={demo.id} read={request} write={write} signOut={signOut} /> :
        <main id="workspace"><p role={error ? 'alert' : 'status'}>{error || (accounts ? 'No demo account is available.' : 'Loading your demo account…')}</p></main>) :
      active?.tradingEnabled && <Terminal key={active.id} demoAccount={active.id} sessionExpired={signOut} />}
  </>;
}

function IdentityForm({ flow, write, onBack }) {
  const confirm = ['verify', 'reset'].includes(flow.kind);
  const [busy, setBusy] = useState(false);
  const [message, setMessage] = useState('');
  const [error, setError] = useState('');
  async function submit(event) {
    event.preventDefault();
    const form = event.currentTarget;
    const data = new FormData(form);
    setBusy(true);
    setError('');
    try {
      if (confirm && data.get('password') !== data.get('confirmation')) throw new Error('The passwords must match.');
      if (confirm && ([...data.get('password')].length < 12 || new TextEncoder().encode(data.get('password')).length > 72))
        throw new Error('Use at least 12 characters and at most 72 UTF-8 bytes.');
      const payload = confirm ? { token: flow.token, password: data.get('password') } : { email: data.get('email') };
      const response = await write('/auth/' + flow.kind, JSON.stringify(payload), 'application/json');
      if (response.status === 429) throw new Error('Too many attempts. Wait a minute and try again.');
      if (response.status === 503) throw new Error('Email verification and recovery are temporarily unavailable.');
      if (!response.ok) throw new Error(confirm ? 'This link is invalid or expired. Request a new email.' : 'Please try again.');
      setMessage(confirm ? 'Password saved. You can sign in now.' : 'If the email is eligible, a link will arrive shortly.');
      form.reset();
    } catch (failure) { setError(failure.message); }
    finally { setBusy(false); }
  }
  return <section aria-label="Account recovery">
    <h2>{flow.kind === 'verify' ? 'Verify your email' : flow.kind === 'reset' ? 'Choose a new password' :
      flow.kind === 'verification' ? 'Request a verification email' : 'Recover your account'}</h2>
    {message ? <p role="status">{message}</p> : <form className="account-form" onSubmit={submit}>
      {confirm ? <>
        <label>New password<input name="password" type="password" required minLength={12} maxLength={72} autoComplete="new-password"/></label>
        <label>Confirm password<input name="confirmation" type="password" required minLength={12} maxLength={72} autoComplete="new-password"/></label>
        <p className="account-note">Use at least 12 characters.</p>
      </> : <label>Email<input type="email" name="email" required maxLength={254} autoComplete="email"/></label>}
      <button className="primary" disabled={busy}>{confirm ? 'Save password' : 'Send email'}</button>
    </form>}
    {error && <p className="notice" role="alert">{error}</p>}
    <button type="button" className="quiet" disabled={busy} onClick={onBack}>Back to sign in</button>
  </section>;
}
