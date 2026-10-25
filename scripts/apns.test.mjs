import { test } from 'node:test';
import assert from 'node:assert/strict';
import http2 from 'node:http2';
import { generateKeyPairSync, verify } from 'node:crypto';
import { BUNDLE_ID, deviceTokens, providerToken, pushConfig, sendAlert } from './apns.mjs';

// A throwaway P-256 key standing in for the APNs .p8 (the same kind of key).
const { privateKey, publicKey } = generateKeyPairSync('ec', { namedCurve: 'P-256' });
const keyP8 = privateKey.export({ type: 'pkcs8', format: 'pem' });
const tokenA = 'a'.repeat(64);
const tokenB = 'b'.repeat(64);

test('the provider token is an ES256 JWT the push key signed', () => {
  const jwt = providerToken({ keyP8, keyId: 'ABC123DEFG', teamId: 'TEAM123456' }, Date.parse('2026-10-30T23:00:00Z'));
  const [head, claims, sig] = jwt.split('.');
  assert.deepEqual(JSON.parse(Buffer.from(head, 'base64url')), { alg: 'ES256', kid: 'ABC123DEFG' });
  assert.deepEqual(JSON.parse(Buffer.from(claims, 'base64url')), { iss: 'TEAM123456', iat: 1793401200 });
  assert.equal(Buffer.from(sig, 'base64url').length, 64); // r||s, 32 bytes each
  assert.ok(verify('sha256', Buffer.from(`${head}.${claims}`), { key: publicKey, dsaEncoding: 'ieee-p1363' },
    Buffer.from(sig, 'base64url')));
});

test('device tokens are read from commas, spaces or lines, and junk is skipped', () => {
  assert.deepEqual(deviceTokens(` ${tokenA.toUpperCase()},\n${tokenB}  not-a-token `), [tokenA, tokenB]);
  assert.deepEqual(deviceTokens(undefined), []);
});

test('with any secret missing there is nothing to send', () => {
  const env = { APPLE_TEAM_ID: 'TEAM123456', APNS_KEY_ID: 'ABC123DEFG', APNS_KEY_P8: keyP8, APNS_DEVICE_TOKENS: tokenA };
  assert.equal(pushConfig(env).tokens.length, 1);
  for (const k of Object.keys(env)) assert.equal(pushConfig({ ...env, [k]: '' }), null, k);
});

test('each device gets one request as APNs expects, and refusals are reported', async () => {
  const seen = [];
  // Plain HTTP/2 on localhost in place of api.push.apple.com.
  const server = http2.createServer();
  server.on('stream', (stream, headers) => {
    let body = '';
    stream.setEncoding('utf8');
    stream.on('data', (c) => { body += c; });
    stream.on('end', () => {
      seen.push({ headers, body: JSON.parse(body) });
      if (headers[':path'].endsWith(tokenA)) {
        stream.respond({ ':status': 200 });
        stream.end();
      } else {
        stream.respond({ ':status': 400, 'content-type': 'application/json' });
        stream.end(JSON.stringify({ reason: 'BadDeviceToken' }));
      }
    });
  });
  await new Promise((r) => server.listen(0, r));
  try {
    const config = { keyP8, keyId: 'ABC123DEFG', teamId: 'TEAM123456', tokens: [tokenA, tokenB],
      host: `http://localhost:${server.address().port}` };
    const results = await sendAlert(config, {
      title: 'Kansas wins set 2, 25-20', body: 'Kansas leads 2-0 vs Utah', collapseId: 'ku-6625743',
    });
    assert.deepEqual(results.map((r) => [r.status, r.reason]).sort(), [[200, ''], [400, 'BadDeviceToken']]);
    assert.equal(seen.length, 2);
    for (const { headers, body } of seen) {
      assert.equal(headers[':method'], 'POST');
      assert.match(headers[':path'], /^\/3\/device\/[ab]{64}$/);
      assert.match(headers.authorization, /^bearer [\w-]+\.[\w-]+\.[\w-]+$/);
      assert.equal(headers['apns-topic'], BUNDLE_ID);
      assert.equal(headers['apns-push-type'], 'alert');
      assert.equal(headers['apns-collapse-id'], 'ku-6625743');
      assert.deepEqual(body, { aps: { alert: { title: 'Kansas wins set 2, 25-20', body: 'Kansas leads 2-0 vs Utah' }, sound: 'default' } });
    }
  } finally {
    server.close();
  }
});

test('an unreachable push service is a reported failure, not a crash', async () => {
  const config = { keyP8, keyId: 'ABC123DEFG', teamId: 'TEAM123456', tokens: [tokenA], host: 'http://localhost:1' };
  const [r] = await sendAlert(config, { title: 't', body: 'b' });
  assert.equal(r.status, 0);
  assert.ok(r.reason);
});
