#!/usr/bin/env python3
"""Owner-only Cloudflare administration. Credentials stay in the macOS Keychain.

Examples: cloud-admin.py init-secrets | status | seed-public | verify-public
The service is isolated from other Cloudflare products. No paid resources are enabled.
"""
from __future__ import annotations
import argparse
import ctypes
import hashlib
import json
import os
from pathlib import Path
import secrets
import subprocess
import sys
import time
from concurrent.futures import ThreadPoolExecutor
import urllib.request
import urllib.error

ROOT = Path(__file__).resolve().parents[1]
WORKER = ROOT / 'server/worker'
SERVICE = b'hk.neville.reading-source-cloud'
ACCOUNT = b'owner'


def credentials(create=False):
    if sys.platform != 'darwin':
        raise RuntimeError('Use an OS credential store on this host; do not write tokens to source files')
    security = ctypes.CDLL('/System/Library/Frameworks/Security.framework/Security')
    security.SecKeychainFindGenericPassword.argtypes = [ctypes.c_void_p, ctypes.c_uint32, ctypes.c_char_p,
        ctypes.c_uint32, ctypes.c_char_p, ctypes.POINTER(ctypes.c_uint32), ctypes.POINTER(ctypes.c_void_p), ctypes.c_void_p]
    length, data = ctypes.c_uint32(), ctypes.c_void_p()
    result = security.SecKeychainFindGenericPassword(None, len(SERVICE), SERVICE, len(ACCOUNT), ACCOUNT,
        ctypes.byref(length), ctypes.byref(data), None)
    if result == 0:
        try:
            return json.loads(ctypes.string_at(data, length.value))
        finally:
            security.SecKeychainItemFreeContent(None, data)
    if result != -25300 or not create:
        raise RuntimeError('Reading service credentials are unavailable in the Keychain')
    value = {key: secrets.token_urlsafe(48) for key in ('ADMIN_TOKEN', 'READ_TOKEN', 'MIGRATION_TOKEN')}
    encoded = json.dumps(value).encode()
    security.SecKeychainAddGenericPassword.argtypes = [ctypes.c_void_p, ctypes.c_uint32, ctypes.c_char_p,
        ctypes.c_uint32, ctypes.c_char_p, ctypes.c_uint32, ctypes.c_void_p, ctypes.c_void_p]
    result = security.SecKeychainAddGenericPassword(None, len(SERVICE), SERVICE, len(ACCOUNT), ACCOUNT,
        len(encoded), encoded, None)
    if result != 0:
        raise RuntimeError('Could not save reading service credentials in the Keychain')
    return value


def endpoint():
    value = json.loads((ROOT / '.toolchain/cloud-deployment.json').read_text())['url'].rstrip('/')
    if not value.startswith('https://'):
        raise RuntimeError('The deployed service must use HTTPS')
    return value


def api(path, payload=None, role='ADMIN_TOKEN'):
    data = None if payload is None else json.dumps(payload, ensure_ascii=False, separators=(',', ':')).encode()
    req = urllib.request.Request(endpoint() + path, data=data, headers={
        'Authorization': 'Bearer ' + credentials()[role], 'Content-Type': 'application/json',
        'Accept': 'application/json', 'User-Agent': 'ReaderCloudAdmin/1.0'})
    try:
        with urllib.request.urlopen(req, timeout=55) as response:
            envelope = json.load(response)
    except urllib.error.HTTPError as error:
        try: code = json.loads(error.read()).get('error', {}).get('code', '')
        except Exception: code = ''
        raise RuntimeError('Reading service HTTP {} {}'.format(error.code, code)) from None
    if 'data' not in envelope:
        raise RuntimeError('Incomplete reading service response')
    return envelope['data']


def seed_public():
    sources = json.loads((ROOT / 'upstream/app/src/main/assets/reader-verified-sources.json').read_text())
    receipts = []
    for start in range(0, len(sources), 8):
        batch = []
        for source in sources[start:start + 8]:
            raw = json.dumps(source, ensure_ascii=False, separators=(',', ':'))
            batch.append({'id': source['bookSourceUrl'], 'json': raw, 'sha256': hashlib.sha256(raw.encode()).hexdigest()})
        received = api('/v1/import', {'kind': 'book', 'sources': batch})['receipts']
        assert {(r['id'], r['sha256'], r['stored']) for r in received} == {(r['id'], r['sha256'], True) for r in batch}
        receipts.extend(received)
    print(json.dumps({'uploaded': len(receipts), 'supported': sum(bool(r['supported']) for r in receipts),
        'healthy': sum(r['status'] == 'healthy' for r in receipts)}, ensure_ascii=False))


def verify_public():
    """Recheck public IDs using the server's successful profile or reviewed default.

    Do not replace a working small public sample with an obsolete huge catalogue.
    """
    sources = json.loads((ROOT / 'upstream/app/src/main/assets/reader-verified-sources.json').read_text())
    def verify(source):
        identifier = source['bookSourceUrl']
        started = time.monotonic()
        try:
            result = api('/v1/verify', {'kind': 'book', 'sourceId': identifier})
            row = {'sourceId': identifier, 'status': result.get('status'),
                   'verified': result.get('verified', False), 'stages': result.get('stages', {}),
                   'reasonCode': result.get('reasonCode'), 'sha256': result.get('sha256')}
        except RuntimeError as error:
            row = {'sourceId': identifier, 'verified': False, 'error': str(error)}
        row['elapsedSeconds'] = round(time.monotonic() - started, 2)
        print(json.dumps({'completedPublicSource': identifier, 'verified': row['verified']}, ensure_ascii=False), flush=True)
        return row
    with ThreadPoolExecutor(max_workers=2) as pool:
        results = list(pool.map(verify, sources))
    target = ROOT / '.toolchain/cloud-public-verify-final.json'
    target.write_text(json.dumps(results, ensure_ascii=False, indent=2) + '\n')
    print(json.dumps({'checked': len(results), 'healthy': sum(bool(row['verified']) for row in results)}, ensure_ascii=False))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('action', choices=('init-secrets', 'status', 'seed-public', 'verify-public'))
    parser.add_argument('--wrangler', type=Path)
    args = parser.parse_args()
    if args.action == 'init-secrets':
        if not args.wrangler or not args.wrangler.is_file():
            parser.error('--wrangler must identify the installed Wrangler JavaScript entry')
        completed = subprocess.run(['node', str(args.wrangler), 'secret', 'bulk', '--config', 'wrangler.jsonc'],
            cwd=WORKER, input=json.dumps(credentials(create=True)), text=True, capture_output=True)
        if completed.returncode:
            print('Could not configure reading service secrets. Existing credentials remain in the Keychain.', file=sys.stderr)
            return completed.returncode
        print('Configured 3 private service credentials; saved only in the OS Keychain and Worker secrets.')
    elif args.action == 'status':
        print(json.dumps(api('/v1/status'), ensure_ascii=False))
    elif args.action == 'seed-public':
        seed_public()
    elif args.action == 'verify-public':
        verify_public()
    return 0


if __name__ == '__main__':
    try: sys.exit(main())
    except Exception as error:
        print(str(error), file=sys.stderr)
        sys.exit(1)
