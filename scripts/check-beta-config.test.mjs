import { test } from 'node:test';
import assert from 'node:assert/strict';
import { randomBytes } from 'node:crypto';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';
import { checkBetaConfig } from './check-beta-config.mjs';

function fixture() {
  return { services: {
    app: {image:'tape-beta:abc1234',environment:{SPRING_PROFILES_ACTIVE:'accounts,beta,brevo',TAPE_ACCOUNTS_MIGRATE:'false',TAPE_REGISTRATION_MODE:'ALLOWLIST',TAPE_REGISTRATION_ALLOWLIST:'invited@example.test',TAPE_ACCOUNTS_PASSWORD:randomBytes(24).toString('hex'),TAPE_MAIL_FROM:'accounts@example.test',TAPE_IDENTITY_MAIL_KEY:randomBytes(32).toString('base64'),TAPE_OPS_TOKEN:randomBytes(32).toString('hex'),TAPE_SMTP_USER:'fixture',TAPE_SMTP_PASSWORD:randomBytes(24).toString('hex'),TAPE_PUBLIC_ORIGIN:'https://localhost:8443'}},
    postgres: {environment:{POSTGRES_PASSWORD:randomBytes(24).toString('hex')}},
    migrate:{image:'tape-beta:abc1234'},
    backup:{environment:{TAPE_BACKUP_RECIPIENT:'age1'+'q'.repeat(58)}}, // Syntax-only fixture. The backup job validates the actual recipient with age.
    proxy:{environment:{TAPE_BETA_HOST:'localhost',TAPE_BETA_HTTPS_PORT:'8443'},ports:[{host_ip:'127.0.0.1',published:'8443',target:443},{host_ip:'127.0.0.1',published:'8088',target:80}]}
  }};
}
test('local defaults remain private and redirect mismatches fail',()=>{
  const c=fixture(); assert.equal(checkBetaConfig(c).configuration,'passed');
  c.services.proxy.ports[0].published='9443'; assert.throws(()=>checkBetaConfig(c));
});
test('remote settings validate without DNS, network requests or deployments',()=>{
  const c=fixture(); c.services.app.environment.TAPE_PUBLIC_ORIGIN='https://beta.tape-project.com';
  c.services.proxy.environment={TAPE_BETA_HOST:'beta.tape-project.com',TAPE_BETA_HTTPS_PORT:'443'};
  c.services.proxy.ports=[{host_ip:'0.0.0.0',published:'443',target:443},{host_ip:'0.0.0.0',published:'80',target:80}];
  assert.equal(checkBetaConfig(c,true).configuration,'passed');
  const missing=structuredClone(c); missing.services.backup.environment.TAPE_BACKUP_RECIPIENT='';
  assert.throws(()=>checkBetaConfig(missing,true),/recipient/);
  assert.throws(()=>checkBetaConfig(c));
  c.services.app.image='tape-beta:local'; c.services.migrate.image=c.services.app.image;
  assert.throws(()=>checkBetaConfig(c,true));
});
test('public onboarding, empty invitations and provider enablement are rejected',()=>{
  for (const [name,value] of [['TAPE_REGISTRATION_MODE','PUBLIC'],['TAPE_REGISTRATION_ALLOWLIST',''],['UPSTOX_ANALYTICS_TOKEN','fixture-only-not-a-token']]) {
    const c=fixture(); c.services.app.environment[name]=value; assert.throws(()=>checkBetaConfig(c));
  }
});
test('private services cannot publish ports and owner credentials cannot reach app',()=>{
  const c=fixture(); c.services.postgres.ports=[{published:'5432',target:5432}]; assert.throws(()=>checkBetaConfig(c));
  delete c.services.postgres.ports; c.services.app.environment.TAPE_POSTGRES_PASSWORD='fixture-only'; assert.throws(()=>checkBetaConfig(c));
});
test('invalid keys, shared passwords and image mismatch fail without printing values',()=>{
  const c=fixture(); c.services.app.environment.TAPE_IDENTITY_MAIL_KEY='invalid'; assert.throws(()=>checkBetaConfig(c),/Identity mail key/);
  const d=fixture(); d.services.app.environment.TAPE_ACCOUNTS_PASSWORD=d.services.postgres.environment.POSTGRES_PASSWORD; assert.throws(()=>checkBetaConfig(d));
  const e=fixture(); e.services.migrate.image='tape-beta:different'; assert.throws(()=>checkBetaConfig(e));
});
test('CLI rejects malformed Compose JSON without leaking source snippets',()=>{
  const sentinel='DO_NOT_PRINT_FIXTURE_SECRET';
  const result=spawnSync(process.execPath,[fileURLToPath(new URL('./check-beta-config.mjs',import.meta.url))],{
    input:`{"sensitive":"${sentinel}",broken`,encoding:'utf8'});
  assert.equal(result.status,1);
  assert.equal(result.stdout,'');
  assert.ok(!result.stderr.includes(sentinel));
  assert.match(result.stderr,/configuration was not printed/);
});
