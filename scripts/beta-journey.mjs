import assert from 'node:assert/strict';
import https from 'node:https';
import { mkdirSync, writeFileSync } from 'node:fs';
import { randomUUID } from 'node:crypto';
import { performance } from 'node:perf_hooks';
import { chrome, sleep } from './chrome.mjs';

const [origin, fixture] = process.argv.slice(2);
if (!origin?.startsWith('https://localhost:') || !fixture?.startsWith('http://127.0.0.1:')) throw new Error('Local TLS fixture only');
const emails = process.env.TAPE_FIXTURE_EMAILS.split(',');
const fixtureHeaders = { 'X-Fixture-Key': process.env.TAPE_FIXTURE_KEY };
const password = randomUUID();
const replacement = randomUUID();
const page = await chrome();
const wait = async (expression, label = 'UI condition') => {
  for (let i=0; i<120; i++) { if (await page.eval(expression)) return; await sleep(200); }
  throw new Error(`Timed out: ${label}`);
};
const body = text => `document.body.innerText.includes(${JSON.stringify(text)})`;
const click = async text => {
  const selector = `[...document.querySelectorAll('button')].find(b=>b.offsetParent!==null && b.textContent.trim()===${JSON.stringify(text)} && !b.disabled)`;
  await wait(`!!(${selector})`, `button ${text}`);
  await page.eval(`${selector}.click()`);
};
const change = async (selector, value) => {
  await wait(`!!document.querySelector(${JSON.stringify(selector)})`, 'form field');
  await page.eval(`(() => { const f=document.querySelector(${JSON.stringify(selector)}); Object.getOwnPropertyDescriptor(Object.getPrototypeOf(f),'value').set.call(f,${JSON.stringify(value)}); f.dispatchEvent(new Event(f.tagName==='SELECT'?'change':'input',{bubbles:true})); })()`);
};
async function link(email, kind) {
  for (let i=0;i<100;i++) {
    const delivered = await (await fetch(fixture, { headers: fixtureHeaders })).json();
    if (delivered[email]?.includes(`#${kind}=`)) return delivered[email];
    await sleep(200);
  }
  throw new Error('Local STARTTLS email was not delivered');
}
function client() {
  let cookie = '', csrf;
  async function request(path, method='GET', body, headers={}) {
    return new Promise((done,reject) => {
      const data = body === undefined ? undefined : body;
      const req = https.request(new URL(path,origin), { method, rejectUnauthorized:false,
        headers:{ ...(cookie?{Cookie:cookie}:{}), ...(data!==undefined?{'Content-Length':Buffer.byteLength(data)}:{}),...headers } }, response=> {
        if (response.headers['set-cookie']) cookie = response.headers['set-cookie'].map(value=>value.split(';')[0]).join('; ');
        let text=''; response.on('data',part=>text+=part); response.on('end',()=>done({status:response.statusCode,body:text}));
      });
      req.on('error',reject); req.setTimeout(60000,()=>req.destroy(new Error('Fixture request timeout')));
      req.end(data);
    });
  }
  return {
    request,
    async write(path, payload, login=false) {
      if (!csrf) { const response=await request('/api/v1/auth/csrf'); assert.equal(response.status,200); csrf=JSON.parse(response.body); }
      const response=await request(path,'POST',login?new URLSearchParams(payload).toString():JSON.stringify(payload),{
        [csrf.headerName]:csrf.token,'Content-Type':login?'application/x-www-form-urlencoded':'application/json'});
      if (path.endsWith('/login')) csrf=undefined;
      return response;
    }
  };
}
async function confirmFromMail(email, kind, chosen) {
  const delivered = await link(email,kind);
  await page.goto('about:blank');
  await page.goto(delivered);
  await wait(body(kind==='verify'?'Verify your email':'Choose a new password'),'email landing');
  assert.ok((await page.eval('location.hash'))==='','Identity token fragment must be removed');
  await change('input[name=password]',chosen);
  await change('input[name=confirmation]',chosen);
  await click('Save password');
  await wait(body('Password saved.'),'identity confirmation');
  await click('Back to sign in');
}
async function signIn(email, chosen) {
  await change('input[type=email]',email); await change('input[name=password]',chosen);
  await click('Sign in'); await wait(body('Your paper accounts'),'authenticated account');
}

try {
  await page.goto(origin);
  await wait(body('Sign in to Tape'),'initial page');
  await click('Create an account');
  await change('input[type=email]',emails[0]); await change('input[name=password]',password);
  await click('Create account');
  await wait(body('a verification link will arrive'),'registration acceptance');
  await confirmFromMail(emails[0],'verify',password);
  await signIn(emails[0],password);
  await wait(body('Market watch'),'equity terminal');
  await click('Place paper buy');
  await wait('document.querySelector("#portfolio tbody")?.innerText.includes("NSE:")','equity position');
  assert.ok(await page.eval(body('Unrealized P&L')));
  assert.ok(await page.eval(body('Realized P&L')));
  await click('Orders');
  await wait('document.querySelector("#portfolio tbody")?.innerText.includes("FILLED")','equity order history');
  await page.eval('[...document.querySelectorAll("#portfolio button")].find(button=>button.textContent.trim().startsWith("Positions")).click()');
  await wait('document.querySelector("#portfolio tbody")?.innerText.includes("NSE:")','equity positions return');
  await click('Options practice');
  await wait(body('Options chain'),'option chain');
  await wait(body('No open options positions.'),'option account');
  await click('Place virtual order');
  await wait('document.querySelector("#practice-positions").closest("section").querySelector("td")?.textContent === "+1"','option buy');
  await click('Sell'); await click('Place virtual order');
  await wait(body('No open options positions.'),'option close');
  await wait('document.querySelector("#practice-orders").closest("section").querySelectorAll("tbody tr").length === 2','option trade history');
  await click('Futures practice');
  await wait(body('Futures contracts'),'futures chain');
  await wait(body('No open futures positions.'),'futures account');
  await click('Place virtual order');
  await wait('!!document.querySelector("#practice-positions").closest("section").querySelector("td")','futures position');
  await click('Review close'); await click('Place virtual order');
  await wait(body('No open futures positions.'),'futures close');
  await wait('document.querySelector("#practice-orders").closest("section").querySelectorAll("tbody tr").length === 2','futures trade history');
  assert.ok(await page.eval(body('Realized P&L')));
  await wait('document.querySelector(".options-ticket button.primary").disabled','flat reduce-only ticket is blocked');
  assert.ok(await page.eval(body('turn off Reduce position only')));
  assert.ok(await page.eval(body('Variation and transaction journal')));
  mkdirSync('target/beta-browser',{recursive:true});
  for (const width of [1440,768,390]) {
    await page.screenshot(`target/beta-browser/futures-${width}.png`,width);
    assert.ok(await page.eval('document.documentElement.scrollWidth<=innerWidth+1'),`overflow at ${width}`);
  }
  await click('Market data'); await wait(body('Historical candles'),'market workspace');
  await wait(body('disconnected'),'provider remains disconnected');
  await click('Paper accounts'); await wait(body('Paper portfolio'),'portfolio return');
  await click('Sign out'); await wait(body('Sign in to Tape'),'signout');
  await signIn(emails[0],password);
  assert.equal((await fetch(fixture+'/restart',{method:'POST',headers:fixtureHeaders})).status,200);
  for(let i=0;i<4;i++) assert.equal(await page.eval('(async()=> (await fetch("/api/v1/auth/me")).status)()'),200);
  const oldSession=client();
  assert.equal((await oldSession.write('/api/v1/auth/login',{email:emails[0],password},true)).status,204);
  await click('Sign out'); await click('Forgot password?');
  await change('input[type=email]',emails[0]); await click('Send email');
  await wait(body('a link will arrive'),'recovery acceptance');
  await confirmFromMail(emails[0],'reset',replacement);
  for(let i=0;i<4;i++) assert.equal((await oldSession.request('/api/v1/auth/me')).status,401);
  await signIn(emails[0],replacement);
  await wait(body('Paper portfolio'),'preserved portfolio after recovery');

  // Six genuinely distinct verified users. Requests alternate across two backend instances.
  const clients=[];
  for (const email of emails.slice(1,7)) {
    const c=client();
    assert.equal((await c.write('/api/v1/auth/register',{email,password})).status,202);
    const delivered=await link(email,'verify');
    assert.equal((await c.write('/api/v1/auth/verify',{token:delivered.split('#verify=')[1],password})).status,204);
    assert.equal((await c.write('/api/v1/auth/login',{email,password},true)).status,204);
    const accounts=JSON.parse((await c.request('/api/v1/accounts')).body);
    const demo=accounts.find(account=>account.mode==='DEMO');
    assert.equal(demo.balancePaise,100000000);
    clients.push({c,account:demo.id});
  }
  assert.equal(new Set(clients.map(user=>user.account)).size,6);
  assert.equal((await clients[0].c.request(`/api/v1/accounts/${clients[1].account}`)).status,404);
  const uninvited=client();
  assert.equal((await uninvited.write('/api/v1/auth/register',{email:randomUUID()+'@example.test',password})).status,202);
  const latencies=[]; let failures=0;
  const started=performance.now();
  await Promise.all(clients.flatMap(({c,account})=>Array.from({length:3},async()=>{
    for(let i=0;i<10;i++) {
      const before=performance.now();
      const response=await c.request(`/api/v1/accounts/${account}/portfolio`);
      latencies.push(performance.now()-before);
      if(response.status!==200) failures++;
    }
  })));
  const seconds=(performance.now()-started)/1000;
  latencies.sort((a,b)=>a-b);
  const report={users:6,concurrency:18,requests:latencies.length,failures,seconds:+seconds.toFixed(2),requestsPerSecond:+(latencies.length/seconds).toFixed(2),p50Ms:+latencies[Math.floor(latencies.length*.5)].toFixed(1),p95Ms:+latencies[Math.floor(latencies.length*.95)].toFixed(1),p99Ms:+latencies[Math.floor(latencies.length*.99)].toFixed(1),scope:'loopback HTTPS, two nodes, PostgreSQL, authenticated portfolio reads; not a public capacity guarantee'};
  writeFileSync('target/beta-browser/load.json',JSON.stringify(report,null,2));
  assert.equal(failures,0);
  assert.deepEqual(page.errors,[]);
  console.log('PASS: allowlisted registration, actual local STARTTLS mail, email verification, secure login, equity/options/futures trades, journal, responsive widths, navigation, signout/in, node restart, session persistence and password reset revocation.');
  console.log(JSON.stringify(report));
} finally { await page.close(); }
