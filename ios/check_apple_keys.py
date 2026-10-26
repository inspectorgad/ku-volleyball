"""Checks the six Apple secrets the iOS build will use, without printing them.

- The App Store Connect key (ASC_ISSUER_ID, ASC_KEY_ID, ASC_KEY_P8) is used to
  look the app up by its bundle ID, which needs a working key and an app
  record created with that ID.
- The push key (APPLE_TEAM_ID, APNS_KEY_ID, APNS_KEY_P8) is used for one
  request to Apple's push service, addressed to a made-up device. Apple checks
  the credentials before the device, so "BadDeviceToken" means the key, its ID
  and the team ID all match, while "InvalidProviderToken" means one of them
  does not. Nothing reaches any phone.

Only pass/fail lines and non-secret facts (the app's name, the App ID's
capabilities) are printed.
"""
import json
import os
import re
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

import jwt

BUNDLE_ID = "io.github.inspectorgad.kuvolleyball"
ASC = "https://api.appstoreconnect.apple.com/v1"

results = []


def report(ok, what, detail=""):
    results.append(ok)
    print(f"{'PASS' if ok else 'FAIL'}  {what}" + (f" - {detail}" if detail else ""))


def secret(name):
    value = os.environ.get(name, "").strip()
    report(bool(value), f"{name} is set", "" if value else "missing or empty")
    return value


def shape(name, value, pattern, hint):
    ok = bool(re.fullmatch(pattern, value))
    report(ok, f"{name} looks right", "" if ok else hint)
    return ok


def asc_get(token, path, params):
    url = f"{ASC}/{path}?{urllib.parse.urlencode(params)}"
    req = urllib.request.Request(url, headers={"Authorization": f"Bearer {token}"})
    try:
        with urllib.request.urlopen(req, timeout=30) as resp:
            return resp.status, json.load(resp)
    except urllib.error.HTTPError as e:
        try:
            body = json.load(e)
        except Exception:
            body = {}
        return e.code, body


def asc_error(body):
    errors = body.get("errors") or [{}]
    return f"{errors[0].get('status', '')} {errors[0].get('code', '')}: {errors[0].get('detail', '')}".strip()


def check_app_store_connect(issuer, key_id, p8):
    now = int(time.time())
    try:
        token = jwt.encode(
            {"iss": issuer, "iat": now, "exp": now + 600, "aud": "appstoreconnect-v1"},
            p8, algorithm="ES256", headers={"kid": key_id, "typ": "JWT"},
        )
    except Exception as e:
        report(False, "ASC_KEY_P8 is a usable private key", type(e).__name__)
        return
    report(True, "ASC_KEY_P8 is a usable private key")

    status, body = asc_get(token, "apps", {"filter[bundleId]": BUNDLE_ID})
    if status == 401:
        report(False, "App Store Connect accepts the key",
               "401 - the Issuer ID, Key ID and .p8 do not belong together, or the key was revoked")
        return
    if status != 200:
        report(False, "App Store Connect accepts the key", f"HTTP {status} {asc_error(body)}")
        return
    report(True, "App Store Connect accepts the key")
    apps = body.get("data", [])
    if apps:
        name = apps[0]["attributes"].get("name", "?")
        report(True, "the app exists in App Store Connect", f'"{name}"')
    else:
        report(False, "the app exists in App Store Connect",
               f"no app with bundle ID {BUNDLE_ID} - Part 3 of the steps")

    status, body = asc_get(token, "bundleIds",
                           {"filter[identifier]": BUNDLE_ID, "include": "bundleIdCapabilities"})
    if status == 200 and body.get("data"):
        caps = sorted({c["attributes"].get("capabilityType", "?") for c in body.get("included", [])
                       if c.get("type") == "bundleIdCapabilities"})
        report(True, "the App ID is registered", ", ".join(caps) or "no capabilities listed")
        for wanted in ("PUSH_NOTIFICATIONS", "APP_GROUPS"):
            report(wanted in caps, f"the App ID has {wanted}",
                   "" if wanted in caps else "tick it in Certificates, IDs & Profiles > Identifiers")
    else:
        report(False, "the App ID is registered", f"HTTP {status} {asc_error(body)}")


def check_push(team, key_id, p8):
    try:
        token = jwt.encode({"iss": team, "iat": int(time.time())}, p8,
                           algorithm="ES256", headers={"kid": key_id})
    except Exception as e:
        report(False, "APNS_KEY_P8 is a usable private key", type(e).__name__)
        return
    report(True, "APNS_KEY_P8 is a usable private key")

    fake_device = "0" * 64
    for env_name, host in (("sandbox", "api.sandbox.push.apple.com"), ("production", "api.push.apple.com")):
        out = subprocess.run(
            ["curl", "--http2", "-s", "-o", "-", "-w", "\n%{http_code}",
             "-H", f"authorization: bearer {token}",
             "-H", f"apns-topic: {BUNDLE_ID}",
             "-H", "apns-push-type: alert",
             "--data", '{"aps":{"alert":"key check"}}',
             f"https://{host}/3/device/{fake_device}"],
            capture_output=True, text=True, timeout=60,
        ).stdout.rsplit("\n", 1)
        body, code = (out[0], out[1]) if len(out) == 2 else ("", out[0])
        try:
            reason = json.loads(body).get("reason", "")
        except Exception:
            reason = body.strip()[:80]
        ok = reason in ("BadDeviceToken", "DeviceTokenNotForTopic")
        hint = {
            "InvalidProviderToken": "the push Key ID, the .p8 and the Team ID do not belong together",
            "ExpiredProviderToken": "clock problem on the runner; re-run",
            "TopicDisallowed": "the key is limited to other apps; use a Team Scoped key",
        }.get(reason, f"HTTP {code} {reason}")
        report(ok, f"Apple's {env_name} push service accepts the push key", "" if ok else hint)


team = secret("APPLE_TEAM_ID")
issuer = secret("ASC_ISSUER_ID")
asc_key_id = secret("ASC_KEY_ID")
asc_p8 = secret("ASC_KEY_P8")
apns_key_id = secret("APNS_KEY_ID")
apns_p8 = secret("APNS_KEY_P8")

shape("APPLE_TEAM_ID", team, r"[A-Z0-9]{10}", "should be 10 capital letters and digits")
shape("ASC_ISSUER_ID", issuer, r"[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}",
      "should look like 69a6de7e-....-....-....-............ (the long one above the keys list)")
shape("ASC_KEY_ID", asc_key_id, r"[A-Z0-9]{10}", "should be 10 capital letters and digits")
shape("APNS_KEY_ID", apns_key_id, r"[A-Z0-9]{10}", "should be 10 capital letters and digits")
for name, value in (("ASC_KEY_P8", asc_p8), ("APNS_KEY_P8", apns_p8)):
    report("BEGIN PRIVATE KEY" in value and "END PRIVATE KEY" in value, f"{name} is a whole .p8 file",
           "" if "BEGIN PRIVATE KEY" in value else "paste the whole file, BEGIN and END lines included")
report(asc_key_id != apns_key_id or not asc_key_id, "the two Key IDs are different keys",
       "" if asc_key_id != apns_key_id else "ASC_KEY_ID and APNS_KEY_ID are the same - one secret has the wrong key")

if issuer and asc_key_id and asc_p8:
    check_app_store_connect(issuer, asc_key_id, asc_p8)
if team and apns_key_id and apns_p8:
    check_push(team, apns_key_id, apns_p8)

passed = sum(results)
print(f"\n{passed} of {len(results)} checks passed")
sys.exit(0 if all(results) else 1)
