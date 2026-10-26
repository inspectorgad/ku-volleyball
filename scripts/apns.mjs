// The iPhone's match alerts go out through Apple's push service (APNs). This
// sends them: it signs a provider token with the push key, then posts one
// alert per device over HTTP/2.
//
// Settings come from GitHub secrets, set once:
//   APPLE_TEAM_ID, APNS_KEY_ID, APNS_KEY_P8  the push key (checked by apple-keys-check.yml)
//   APNS_DEVICE_TOKENS                       each iPhone's alert code, from the Matches tab
//                                            of the iPhone app; several may be separated by
//                                            commas, spaces or new lines
// With any of them missing, nothing is sent and the caller carries on.
//
//   node scripts/apns.mjs test
//       Sends one test alert to every device (iphone-test-alert.yml).

import http2 from 'node:http2';
import { createPrivateKey, sign } from 'node:crypto';
import { pathToFileURL } from 'node:url';

export const BUNDLE_ID = 'io.github.inspectorgad.kuvolleyball';
// TestFlight and App Store builds use the production service.
const HOST = 'https://api.push.apple.com';

const b64url = (data) => Buffer.from(data).toString('base64url');

/** The provider token APNs expects: an ES256 JWT signed with the push key. Good for an hour. */
export function providerToken({ keyP8, keyId, teamId }, nowMs = Date.now()) {
  const head = b64url(JSON.stringify({ alg: 'ES256', kid: keyId }));
  const claims = b64url(JSON.stringify({ iss: teamId, iat: Math.floor(nowMs / 1000) }));
  const signature = sign('sha256', Buffer.from(`${head}.${claims}`), {
    key: createPrivateKey(keyP8),
    dsaEncoding: 'ieee-p1363', // JWS wants r||s, not DER
  });
  return `${head}.${claims}.${b64url(signature)}`;
}

/** The device tokens in a secret's text: hex, separated by commas, spaces or new lines. */
export function deviceTokens(text) {
  return String(text ?? '')
    .split(/[\s,]+/)
    .map((t) => t.trim().toLowerCase())
    .filter((t) => /^[0-9a-f]{32,200}$/.test(t));
}

/** Push settings from the environment, or null when any are missing. */
export function pushConfig(env = process.env) {
  const tokens = deviceTokens(env.APNS_DEVICE_TOKENS);
  if (!env.APNS_KEY_P8 || !env.APNS_KEY_ID?.trim() || !env.APPLE_TEAM_ID?.trim() || !tokens.length) return null;
  return { keyP8: env.APNS_KEY_P8, keyId: env.APNS_KEY_ID.trim(), teamId: env.APPLE_TEAM_ID.trim(), tokens };
}

/** The JSON Apple delivers to the phone: a title, a body, the default sound. */
export function payload(title, body) {
  return JSON.stringify({ aps: { alert: { title, body }, sound: 'default' } });
}

/**
 * Sends one alert to every device in [config]. [collapseId] makes a later
 * alert replace an earlier one with the same id, so a match keeps one
 * notification that updates set by set, as on Android. Resolves to one
 * { device, status, reason } per device (status 200 is delivered); a refused
 * device or a dropped connection is reported, never thrown.
 */
export async function sendAlert(config, { title, body, collapseId }) {
  const jwt = providerToken(config);
  const client = http2.connect(config.host ?? HOST); // host: a test server instead of Apple's
  client.on('error', () => {}); // each request reports its own failure below
  try {
    return await Promise.all(config.tokens.map((token) => new Promise((resolve) => {
      const device = `${token.slice(0, 8)}…`;
      const headers = {
        ':method': 'POST',
        ':path': `/3/device/${token}`,
        authorization: `bearer ${jwt}`,
        'apns-topic': BUNDLE_ID,
        'apns-push-type': 'alert',
        'apns-priority': '10',
        'content-type': 'application/json',
      };
      if (collapseId) headers['apns-collapse-id'] = collapseId.slice(0, 64);
      let status = 0;
      let text = '';
      const req = client.request(headers);
      req.setEncoding('utf8');
      req.setTimeout(30_000, () => req.close(http2.constants.NGHTTP2_CANCEL));
      req.on('response', (h) => { status = h[':status']; });
      req.on('data', (chunk) => { text += chunk; });
      req.on('end', () => {
        let reason = '';
        try { reason = JSON.parse(text).reason ?? ''; } catch { /* 200 has no body */ }
        resolve({ device, status, reason });
      });
      req.on('error', (e) => resolve({ device, status: 0, reason: e.message }));
      req.end(payload(title, body));
    })));
  } finally {
    client.close();
  }
}

/**
 * Sends, logs each device's outcome in a line, and never throws. True when
 * every device has a final answer - delivered, or refused for good (a bad or
 * expired code, which trying again would not change); false when one is
 * worth another try: no answer, too many requests, or a fault at Apple.
 */
export async function sendAndLog(config, alert) {
  try {
    const results = await sendAlert(config, alert);
    for (const r of results) {
      console.log(`  push to ${r.device}: ${r.status === 200 ? 'delivered' : `${r.status} ${r.reason}`}`);
    }
    return results.every((r) => r.status === 200 || (r.status >= 400 && r.status < 500 && r.status !== 429));
  } catch (e) {
    console.log(`  push failed: ${e.message}`);
    return false;
  }
}

async function main() {
  if (process.argv[2] !== 'test') {
    console.error('usage: apns.mjs test');
    process.exit(2);
  }
  const config = pushConfig();
  if (!config) {
    console.error('Not sent: APPLE_TEAM_ID, APNS_KEY_ID, APNS_KEY_P8 and APNS_DEVICE_TOKENS must all be set.');
    process.exit(1);
  }
  console.log(`Sending a test alert to ${config.tokens.length} device(s)`);
  const results = await sendAlert(config, {
    title: 'KU Volleyball test alert',
    body: 'Match alerts reach this iPhone. Set scores and finals will arrive like this on match nights.',
    collapseId: 'ku-test',
  });
  for (const r of results) console.log(`  ${r.device}: ${r.status === 200 ? 'delivered' : `${r.status} ${r.reason}`}`);
  if (!results.every((r) => r.status === 200)) process.exit(1);
}

if (import.meta.url === pathToFileURL(process.argv[1]).href) await main();
