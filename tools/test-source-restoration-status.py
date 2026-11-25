#!/usr/bin/env python3
import hashlib
import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location('ledger', Path(__file__).with_name('source-restoration-status.py'))
ledger = importlib.util.module_from_spec(spec)
spec.loader.exec_module(ledger)


def fixtures():
    original = {'kind': 'book', 'id': 'https://fiction.example', 'json': '{"bookSourceUrl":"https://fiction.example"}'}
    original['sha256'] = hashlib.sha256(original['json'].encode()).hexdigest()
    remote = {'kind': 'book', 'id': original['id'], 'sha256': original['sha256'],
              'status': 'pending', 'supported': 1, 'enabled': 1, 'failures': 0}
    return original, remote


class RestorationLedgerTest(unittest.TestCase):
    def test_missing_and_extra_remote_sources_cannot_be_reported_as_complete(self):
        original, remote = fixtures()
        with self.assertRaisesRegex(ValueError, 'missing'):
            ledger.reconcile([original], [])
        extra = dict(remote, id='https://other.example')
        with self.assertRaisesRegex(ValueError, 'identity set'):
            ledger.reconcile([original], [remote, extra])

    def test_raw_bytes_and_remote_sha_must_both_match(self):
        original, remote = fixtures()
        changed = dict(original, json=original['json'] + ' ')
        with self.assertRaisesRegex(ValueError, 'archive checksum'):
            ledger.reconcile([changed], [remote])
        with self.assertRaisesRegex(ValueError, 'differs'):
            ledger.reconcile([original], [dict(remote, sha256='0' * 64)])

    def test_unsupported_and_temporary_failures_never_become_permanent_broken_claims(self):
        original, remote = fixtures()
        for changes, expected in [({'supported': 0, 'status': 'unsupported'}, 'needs_engine_compatibility'),
                                  ({'failures': 1}, 'attempted_needs_repair_or_retry')]:
            result = ledger.reconcile([original], [dict(remote, **changes)])
            self.assertEqual(expected, result['rows'][0]['cloudState'])
            self.assertFalse(result['rows'][0]['permanentlyClassifiedBroken'])

    def test_cloud_health_does_not_falsely_imply_the_client_has_enabled_it(self):
        original, remote = fixtures()
        result = ledger.reconcile([original], [dict(remote, status='healthy')], client_rows=[
            {'kind': 'book', 'id': original['id'], 'thin': False, 'enabled': 1}])
        self.assertEqual('cloud_verified', result['rows'][0]['cloudState'])
        self.assertEqual('full_rule_preserved', result['rows'][0]['clientSnapshotState'])

    def test_latest_journal_contains_only_fixed_diagnostics_and_stage_flags(self):
        original, remote = fixtures()
        result = ledger.reconcile([original], [remote], observations=[
            {'kind': 'book', 'id': original['id'], 'reasonCode': 'SOURCE_TIMEOUT',
             'stages': {'search': True, 'private-path': 'secret'}, 'error': 'private details'}])
        row = result['rows'][0]
        self.assertEqual({'search': True}, row['observedStages'])
        self.assertNotIn('error', row)
        self.assertEqual('SOURCE_TIMEOUT', row['latestReasonCode'])


if __name__ == '__main__':
    unittest.main()
