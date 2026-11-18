import { isIP } from 'node:net';
import { pathToFileURL } from 'node:url';

// Input is resolved Compose JSON on stdin. Never echo it: it contains deployment secrets.
export function checkBetaConfig(config, remote = false) {
  const require = (condition, message) => { if (!condition) throw new Error(message); };
  const services = config.services || {};
  const app = services.app || {};
  const env = app.environment || {};
  const proxy = services.proxy || {};
  for (const name of ['TAPE_ACCOUNTS_PASSWORD','TAPE_MAIL_FROM','TAPE_IDENTITY_MAIL_KEY','TAPE_OPS_TOKEN','TAPE_SMTP_USER','TAPE_SMTP_PASSWORD'])
    require(typeof env[name] === 'string' && env[name].trim() && !env[name].includes('SET_'), `Configure ${name} privately`);
  const owner = services.postgres?.environment?.POSTGRES_PASSWORD;
  require(typeof owner === 'string' && owner && !owner.includes('SET_'), 'Configure database owner credentials privately');
  require(owner !== env.TAPE_ACCOUNTS_PASSWORD, 'Database owner and runtime passwords must differ');
  require(!('TAPE_POSTGRES_PASSWORD' in env) && !('TAPE_MIGRATION_PASSWORD' in env) && !('PGPASSWORD' in env), 'Owner credentials must not be passed to the application');
  let origin;
  try { origin = new URL(env.TAPE_PUBLIC_ORIGIN); } catch { throw new Error('Configure a valid canonical HTTPS origin'); }
  const host = proxy.environment?.TAPE_BETA_HOST;
  const tls = proxy.ports?.find(port => Number(port.target) === 443);
  const http = proxy.ports?.find(port => Number(port.target) === 80);
  require(origin.protocol === 'https:' && !origin.username && !origin.password && origin.pathname === '/' && !origin.search && !origin.hash, 'Origin must be HTTPS with no credentials, path, query or fragment');
  require(origin.hostname === host && Number(origin.port || 443) === Number(tls?.published), 'Origin, proxy hostname and published HTTPS port must agree');
  require(Number(proxy.environment?.TAPE_BETA_HTTPS_PORT || 8443) === Number(tls?.published), 'HTTPS redirect must match the published port');
  require(tls && http && proxy.ports.length === 2, 'Only HTTP and HTTPS proxy ports may be published');
  for (const [name, service] of Object.entries(services)) {
    require(!service.network_mode?.startsWith('host') && !service.privileged, 'Host networking and privileged containers are forbidden');
    if (name !== 'proxy') require(!service.ports?.length, 'Database, application and maintenance ports must remain private');
  }
  require(env.SPRING_PROFILES_ACTIVE === 'accounts,beta,brevo' && String(env.TAPE_ACCOUNTS_MIGRATE) === 'false', 'Use verified beta profiles with runtime migrations disabled');
  require(['CLOSED','ALLOWLIST'].includes(env.TAPE_REGISTRATION_MODE), 'Private beta registration must be CLOSED or ALLOWLIST');
  if (env.TAPE_REGISTRATION_MODE === 'ALLOWLIST') require(env.TAPE_REGISTRATION_ALLOWLIST?.trim(), 'Configure invited email addresses');
  for (const name of ['TAPE_MARKET_PERMISSIONS','TAPE_MARKET_PERMISSION_REF','UPSTOX_ANALYTICS_TOKEN','OLLAMA_MODEL'])
    require(!env[name], 'Provider access and real-price execution must remain disabled');
  const key = env.TAPE_IDENTITY_MAIL_KEY;
  require(/^[A-Za-z0-9+/]+={0,2}$/.test(key) && Buffer.from(key,'base64').length === 32 && Buffer.from(key,'base64').toString('base64').replace(/=+$/,'') === key.replace(/=+$/,''), 'Identity mail key must encode exactly 32 bytes');
  require(/^[A-Za-z0-9_-]{32,128}$/.test(env.TAPE_OPS_TOKEN), 'Invalid operations token format');
  require(app.image === services.migrate?.image, 'Application and maintenance must use the same release image');
  if (remote) {
    require(/^age1[a-z0-9]{58}$/.test(services.backup?.environment?.TAPE_BACKUP_RECIPIENT || ''), 'Configure the offline age public recipient before remote beta');
    require(host && host.includes('.') && !isIP(host) && !/(^localhost$|\.(test|invalid|example|localhost)$)/i.test(host), 'Remote mode requires your real DNS hostname');
    require(Number(tls.published) === 443 && Number(http.published) === 80, 'Use public ports 80 and 443 for standard ACME validation');
    require(tls.host_ip === '0.0.0.0' && http.host_ip === '0.0.0.0', 'Remote binding must be an explicit IPv4 opt-in');
    require(app.image && !/:(local|latest)$/.test(app.image) && !app.image.includes('SET_'), 'Pin the beta application to a release commit tag');
  } else require([tls,http].every(port => port.host_ip === '127.0.0.1'), 'Local verification must stay on loopback');
  return { configuration: 'passed', mode: remote ? 'remote configuration only, not deployed' : 'loopback', externalEmail: 'unverified', dnsAndCertificates: 'unverified' };
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  try {
    let input = '';
    for await (const part of process.stdin) input += part;
    console.log(JSON.stringify(checkBetaConfig(JSON.parse(input), process.argv.includes('--remote'))));
  } catch (failure) {
    // JSON parse errors can contain source snippets, so print only our own validation messages.
    console.error(failure instanceof SyntaxError ? 'Invalid Compose JSON; configuration was not printed' : failure.message);
    process.exitCode = 1;
  }
}
