// Minimal test-only CDP transport. Uses an installed Chrome and Node 22's WebSocket.
import { spawn } from 'node:child_process';
import { existsSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, resolve, sep } from 'node:path';
export const sleep = ms => new Promise(done => setTimeout(done, ms));

export async function chrome() {
  const executable = [process.env.CHROME_PATH,
    join(process.env.PROGRAMFILES || 'C:/Program Files', 'Google/Chrome/Application/chrome.exe'),
    '/usr/bin/google-chrome', '/usr/bin/google-chrome-stable', '/usr/bin/chromium'].find(file => file && existsSync(file));
  if (!executable) throw new Error('Install Chrome or set CHROME_PATH to run browser tests');
  const profile = mkdtempSync(join(tmpdir(), 'tape-browser-'));
  const child = spawn(executable, ['--headless=new', '--disable-gpu', '--no-first-run', '--no-default-browser-check',
    '--ignore-certificate-errors', '--remote-debugging-address=127.0.0.1', '--remote-debugging-port=0',
    ...(process.env.CI === 'true' ? ['--no-sandbox'] : []), `--user-data-dir=${profile}`, 'about:blank'], { stdio: 'ignore' });
  let socket, browserSocket, serial = 0;
  const pending = new Map();
  const errors = [];
  const send = (method, params = {}) => new Promise((done, reject) => {
    const id = ++serial;
    const timer = setTimeout(() => { pending.delete(id); reject(new Error(`CDP timeout: ${method}`)); }, 30000);
    pending.set(id, message => { clearTimeout(timer); message.error ? reject(new Error(message.error.message)) : done(message.result); });
    socket.send(JSON.stringify({ id, method, params }));
  });
  try {
    let discovery;
    for (let i=0; i<100; i++) {
      try { discovery = readFileSync(join(profile, 'DevToolsActivePort'), 'utf8').trim().split('\n'); break; } catch {}
      if (child.exitCode !== null) throw new Error('Test Chrome exited');
      await sleep(100);
    }
    if (!discovery) throw new Error('Test Chrome did not start');
    const base = `http://127.0.0.1:${discovery[0]}`;
    const target = await (await fetch(`${base}/json/new?about:blank`, { method: 'PUT' })).json();
    socket = new WebSocket(target.webSocketDebuggerUrl);
    await new Promise((done, reject) => { socket.onopen = done; socket.onerror = reject; });
    socket.onmessage = ({ data }) => {
      const message = JSON.parse(data);
      if (message.id) { pending.get(message.id)?.(message); pending.delete(message.id); }
      if (message.method === 'Runtime.exceptionThrown') errors.push(message.params.exceptionDetails.text);
    };
    await send('Runtime.enable');
    await send('Page.enable');
    browserSocket = new WebSocket(`ws://127.0.0.1:${discovery[0]}${discovery[1]}`);
    await new Promise((done, reject) => { browserSocket.onopen = done; browserSocket.onerror = reject; });
    return {
      send, errors,
      async eval(expression) {
        const result = await send('Runtime.evaluate', { expression, awaitPromise: true, returnByValue: true });
        if (result.exceptionDetails) throw new Error('Browser evaluation failed');
        return result.result.value;
      },
      async goto(url) { await send('Page.navigate', { url }); },
      async screenshot(path, width) {
        await send('Emulation.setDeviceMetricsOverride', { width, height: 900, deviceScaleFactor: 1, mobile: false });
        await sleep(200);
        writeFileSync(path, Buffer.from((await send('Page.captureScreenshot', { format: 'png', captureBeyondViewport: true })).data, 'base64'));
      },
      async close() {
        socket.close();
        browserSocket.send(JSON.stringify({ id: 1, method: 'Browser.close' }));
        browserSocket.close();
        for (let i=0; i<50 && child.exitCode === null; i++) await sleep(100);
        // Delete only the unique, test-owned profile, and only after Chrome exits.
        if (child.exitCode !== null && resolve(profile).startsWith(resolve(tmpdir()) + sep))
          rmSync(profile, { recursive: true, force: true, maxRetries: 3, retryDelay: 100 });
      }
    };
  } catch (failure) {
    socket?.close(); browserSocket?.close(); child.kill();
    throw failure;
  }
}
