#!/usr/bin/env python3
"""Maintain a complete private restoration ledger bound to every original SHA.

This is a read-only reconciliation, not a health promotion or source deletion.
CLI output contains aggregate progress only; source identities remain private.
"""
from __future__ import annotations

import argparse
from collections import Counter
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import re

ROOT = Path(__file__).resolve().parents[1]


def private_path(value):
    path = Path(value).resolve()
    if path == ROOT or ROOT in path.parents:
        raise ValueError('Private source identities must remain outside the public project')
    return path


def reconcile(originals, remote_rows, observations=(), client_rows=()):
    remote = {}
    for row in remote_rows:
        key = row['kind'], row['id']
        if key in remote:
            raise ValueError('Duplicate remote identity')
        remote[key] = row
    notes = {}
    for row in observations:
        identifier = row.get('id', row.get('sourceId'))
        if identifier:
            notes[row.get('kind', 'book'), identifier] = row
    clients = {(row['kind'], row['id']): row for row in client_rows}
    rows = []
    seen = set()
    counts = Counter()
    for entry in originals:
        key = entry['kind'], entry['id']
        if key in seen:
            raise ValueError('Duplicate original identity')
        seen.add(key)
        sha = hashlib.sha256(entry['json'].encode()).hexdigest()
        if sha != entry['sha256']:
            raise ValueError('Original archive checksum mismatch')
        current = remote.get(key)
        if current is None:
            raise ValueError('An original source is missing from remote storage')
        if current['sha256'] != sha:
            raise ValueError('Remote original rule differs from the archived source')
        if current['status'] == 'healthy' and current.get('enabled'):
            state = 'cloud_verified'
        elif not current.get('supported') or current['status'] == 'unsupported':
            state = 'needs_engine_compatibility'
        elif current.get('failures', 0) or key in notes:
            state = 'attempted_needs_repair_or_retry'
        else:
            state = 'queued_for_live_verification'
        client = clients.get(key)
        client_state = ('unknown' if client is None else
                        'thin_enabled' if client.get('thin') and client.get('enabled') else
                        'thin_disabled' if client.get('thin') else 'full_rule_preserved')
        row = {'kind': key[0], 'id': key[1], 'originalSha256': sha,
               'archivePresent': True, 'remoteOriginalShaMatches': True,
               'cloudState': state, 'serverStatus': current['status'],
               'failures': int(current.get('failures', 0)),
               'clientSnapshotState': client_state,
               'permanentlyClassifiedBroken': False}
        observation = notes.get(key)
        if observation:
            code = observation.get('reasonCode')
            if isinstance(code, str) and re.fullmatch(r'[A-Z0-9_]{1,63}', code):
                row['latestReasonCode'] = code
            row['observedStages'] = {name: bool(value) for name, value in observation.get('stages', {}).items()
                                     if name in {'catalogue', 'search', 'info', 'toc', 'content', 'explore', 'articles', 'website'}}
        rows.append(row)
        counts[key[0] + ':' + state] += 1
    if set(remote) != seen:
        raise ValueError('Remote identity set differs from the complete original archive')
    return {'format': 'reader-complete-source-restoration-ledger-1',
            'scope': 'Every original rule is accounted for; cloud verification and client availability remain distinct.',
            'sources': len(rows), 'allOriginalIdentitiesPresent': True,
            'allRemoteOriginalShasMatch': True, 'permanentlyBrokenClaims': 0,
            'counts': dict(counts), 'rows': rows}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--archive', type=Path, required=True)
    parser.add_argument('--remote-metadata', type=Path, required=True)
    parser.add_argument('--observations', type=Path, nargs='*', default=[])
    parser.add_argument('--client-shapes', type=Path)
    parser.add_argument('--output', type=Path, required=True)
    args = parser.parse_args()
    archive, metadata, output = (private_path(value) for value in (args.archive, args.remote_metadata, args.output))
    observations = []
    for value in args.observations:
        data = json.loads(private_path(value).read_text())
        observations.extend(data if isinstance(data, list) else data.get('completed', [data]))
    clients = json.loads(private_path(args.client_shapes).read_text()) if args.client_shapes else []
    with archive.open() as stream:
        report = reconcile((json.loads(line) for line in stream if line.strip()),
                           json.loads(metadata.read_text()), observations, clients)
    report['generatedAtUtc'] = datetime.now(timezone.utc).isoformat()
    output.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    temporary = output.with_suffix(output.suffix + '.pending')
    with temporary.open('x') as stream:
        os.chmod(temporary, 0o600)
        json.dump(report, stream, ensure_ascii=False, indent=2)
        stream.write('\n')
    temporary.replace(output)
    print(json.dumps({key: value for key, value in report.items() if key not in {'scope', 'rows'}}))


if __name__ == '__main__':
    main()
