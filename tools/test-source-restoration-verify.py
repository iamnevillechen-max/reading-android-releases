#!/usr/bin/env python3
"""Restoration safety tests: exact originals, bounded work and truthful results."""
import hashlib
import importlib.util
import json
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location('restoration', Path(__file__).with_name('source-restoration-verify.py'))
restoration = importlib.util.module_from_spec(spec)
spec.loader.exec_module(restoration)


def row(identifier='https://fixture.example', kind='book', host='fixture.example'):
    raw = json.dumps({'bookSourceUrl' if kind == 'book' else 'sourceUrl': identifier})
    return {'entry': {'kind': kind, 'id': identifier, 'json': raw,
                      'sha256': hashlib.sha256(raw.encode()).hexdigest()},
            'host': host, 'status': {'status': 'pending'}}


class RestorationSafetyTest(unittest.TestCase):
    def test_changed_original_is_rejected_before_source_selection(self):
        value = row()
        value['entry']['json'] += ' '
        with self.assertRaisesRegex(ValueError, 'SHA'):
            restoration.select([value], 1, 0)

    def test_bounded_selection_does_not_count_duplicate_hosts_as_restored_sources(self):
        values = [row(), row('https://fixture.example/alias'),
                  row('https://second.example', host='second.example'),
                  row('https://feed.example', 'rss', 'feed.example')]
        selected = restoration.select(values, 1, 1)
        self.assertEqual(['book', 'rss'], [value['kind'] for value in selected])

    def test_receipt_mismatch_never_starts_verification(self):
        calls = []
        def api(path, payload=None):
            calls.append(path)
            return {'stored': True, 'supported': True, 'sha256': '0' * 64}
        result = restoration.verify_entry(row()['entry'], api, 'public sample')
        self.assertFalse(result['verified'])
        self.assertEqual(1, len(calls))
        self.assertTrue(calls[0].startswith('/v1/source-receipt?'))

    def test_unsupported_is_not_mislabeled_as_a_broken_website(self):
        entry = row()['entry']
        def api(path, payload=None):
            self.assertTrue(path.startswith('/v1/source-receipt?'))
            return {'stored': True, 'supported': False, 'sha256': entry['sha256']}
        result = restoration.verify_entry(entry, api, 'public sample')
        self.assertFalse(result['verified'])
        self.assertEqual('ENGINE_UNSUPPORTED', result['reasonCode'])

    def test_only_complete_production_health_pass_counts_as_restored(self):
        entry = row()['entry']
        def api(path, payload=None):
            if payload is None:
                return {'stored': True, 'supported': True, 'sha256': entry['sha256']}
            return {'verified': True, 'status': 'pending', 'sha256': entry['sha256']}
        self.assertFalse(restoration.verify_entry(entry, api, 'public sample')['verified'])

    def test_source_changed_during_verification_does_not_count_as_success(self):
        entry = row()['entry']
        def api(path, payload=None):
            if payload is None:
                return {'stored': True, 'supported': True, 'sha256': entry['sha256']}
            return {'verified': True, 'status': 'healthy', 'sha256': '0' * 64}
        result = restoration.verify_entry(entry, api, 'public sample')
        self.assertFalse(result['verified'])
        self.assertEqual('REQUEST_FAILED', result['reasonCode'])

    def test_successful_recorded_profile_can_be_reused_without_overwriting_its_keyword(self):
        entry = row()['entry']
        def api(path, payload=None):
            if payload is None:
                return {'stored': True, 'supported': True, 'sha256': entry['sha256']}
            self.assertNotIn('key', payload)
            return {'verified': True, 'status': 'healthy', 'sha256': entry['sha256']}
        self.assertTrue(restoration.verify_entry(entry, api, None)['verified'])

    def test_rechecking_an_already_healthy_identity_does_not_inflate_restoration_count(self):
        entry = row()['entry']
        def api(path, payload=None):
            if payload is None:
                return {'stored': True, 'supported': True, 'status': 'healthy', 'sha256': entry['sha256']}
            return {'verified': True, 'status': 'healthy', 'sha256': entry['sha256']}
        result = restoration.verify_entry(entry, api, None)
        self.assertTrue(result['verified'])
        self.assertFalse(result['newlyRestored'])

    def test_private_inputs_cannot_be_written_inside_public_source(self):
        with self.assertRaisesRegex(ValueError, 'outside'):
            restoration.private_path(restoration.ROOT / 'docs' / 'source-results.json')


if __name__ == '__main__':
    unittest.main()
