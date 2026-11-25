#!/usr/bin/env python3
"""Verify a bounded private restoration plan without changing archived rules.

The plan and results must live outside the public project. Only sources whose
complete production verification passes become available in the cloud list.
Tokens stay in the existing OS credential store; logs contain aggregate counts.
"""
from __future__ import annotations

import argparse
from collections import Counter
from concurrent.futures import ThreadPoolExecutor, as_completed
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import time

ROOT = Path(__file__).resolve().parents[1]


def private_path(path: Path) -> Path:
    path = path.resolve()
    if path == ROOT or ROOT in path.parents:
        raise ValueError('Private restoration inputs/results must remain outside the public project')
    return path


def checked_entry(row):
    entry = row['entry']
    if entry.get('kind') not in {'book', 'rss'} or not isinstance(entry.get('id'), str):
        raise ValueError('Invalid source identity')
    raw = entry.get('json')
    if not isinstance(raw, str) or hashlib.sha256(raw.encode()).hexdigest() != entry.get('sha256'):
        raise ValueError('The original archived source SHA does not match')
    source = json.loads(raw)
    if (source.get('bookSourceUrl') if entry['kind'] == 'book' else source.get('sourceUrl')) != entry['id']:
        raise ValueError('Archived rule identity does not match the plan')
    return entry


def select(rows, book_limit, rss_limit):
    counts = Counter()
    seen = set()
    selected = []
    for row in rows:
        entry = checked_entry(row)
        kind = entry['kind']
        # Ranking/grouping is recorded by the private analysis step. Never
        # increase counts by checking aliases of the same host in one batch.
        key = kind, row.get('host') or entry['id']
        if key in seen or counts[kind] >= (book_limit if kind == 'book' else rss_limit):
            continue
        if row.get('status', {}).get('status') == 'healthy':
            continue
        seen.add(key)
        counts[kind] += 1
        selected.append(entry)
    return selected


def verify_entry(entry, api, keyword):
    started = time.monotonic()
    result = {'kind': entry['kind'], 'id': entry['id'], 'originalSha256': entry['sha256']}
    try:
        from urllib.parse import urlencode
        receipt = api('/v1/source-receipt?' + urlencode({'kind': entry['kind'], 'id': entry['id']}))
        if receipt.get('sha256') != entry['sha256'] or not receipt.get('stored'):
            raise ValueError('Stored original changed; verification refused')
        result['healthyBeforeVerification'] = receipt.get('status') == 'healthy'
        if not receipt.get('supported'):
            result.update(verified=False, reasonCode='ENGINE_UNSUPPORTED')
        else:
            payload = {'kind': entry['kind'], 'sourceId': entry['id']}
            if keyword is not None:
                payload['key'] = keyword
            reply = api('/v1/verify', payload)
            if reply.get('sha256') != entry['sha256']:
                raise ValueError('Source changed during verification')
            result.update(reply)
            result['verified'] = reply.get('verified') is True and reply.get('status') == 'healthy'
    except Exception as error:
        # Detailed diagnostics are private. Do not print URLs, rule strings,
        # cookie values, tokens or source identities into the public transcript.
        result.update(verified=False, error=str(error), reasonCode='REQUEST_FAILED')
    result['seconds'] = round(time.monotonic() - started, 3)
    result['newlyRestored'] = result.get('verified') is True and not result.get('healthyBeforeVerification', False)
    return result


def write_private(path, value):
    temporary = path.with_suffix(path.suffix + '.pending')
    with temporary.open('x') as stream:
        os.chmod(temporary, 0o600)
        json.dump(value, stream, ensure_ascii=False, indent=2)
        stream.write('\n')
    temporary.replace(path)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--plan', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--books', type=int, default=12)
    parser.add_argument('--rss', type=int, default=12)
    parser.add_argument('--keyword', default='我')
    parser.add_argument('--use-recorded-profile', action='store_true',
                        help='Reuse the server successful probe/public default without replacing its keyword')
    parser.add_argument('--verify', action='store_true')
    args = parser.parse_args()
    if not 0 <= args.books <= 100 or not 0 <= args.rss <= 100 or not 1 <= len(args.keyword) <= 200:
        parser.error('Use bounded source limits and a normal search keyword')
    plan, output = private_path(args.plan), private_path(args.output)
    if output.exists():
        parser.error('Do not replace an existing verification record')
    rows = json.loads(plan.read_text())
    selected = select(rows, args.books, args.rss)
    print(json.dumps({'selected': dict(Counter(entry['kind'] for entry in selected)), 'verificationRequested': args.verify}), flush=True)
    if not args.verify:
        return
    output.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
    module = importlib.util.spec_from_file_location('restoration_cloud_admin', ROOT / 'tools/cloud-admin.py')
    admin = importlib.util.module_from_spec(module)
    module.loader.exec_module(admin)
    completed = []
    with ThreadPoolExecutor(max_workers=2) as pool:
        keyword = None if args.use_recorded_profile else args.keyword
        futures = [pool.submit(verify_entry, entry, admin.api, keyword) for entry in selected]
        for future in as_completed(futures):
            completed.append(future.result())
            write_private(output, {'format': 'reader-source-restoration-verification-1', 'selected': len(selected), 'completed': completed})
            print(json.dumps({'completed': len(completed), 'verified': sum(item['verified'] for item in completed),
                              'newlyRestored': sum(item['newlyRestored'] for item in completed),
                              'byKind': dict(Counter(item['kind'] for item in completed if item['verified']))}), flush=True)
    print(json.dumps({'complete': True, 'verified': sum(item['verified'] for item in completed),
                      'newlyRestored': sum(item['newlyRestored'] for item in completed),
                      'reasons': dict(Counter(item.get('reasonCode', 'OK') for item in completed))}), flush=True)


if __name__ == '__main__':
    main()
