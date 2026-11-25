#!/usr/bin/env python3
"""Inventory coverage and privacy checks using only self-written fixtures."""
from pathlib import Path
import hashlib
import importlib.util
import json
import tempfile
import unittest

spec = importlib.util.spec_from_file_location('source_inventory', Path(__file__).with_name('source-inventory.py'))
inventory = importlib.util.module_from_spec(spec)
spec.loader.exec_module(inventory)


class SourceInventoryTest(unittest.TestCase):
    def entry(self, kind, source):
        raw = json.dumps(source, ensure_ascii=False)
        return {'kind': kind, 'id': source['bookSourceUrl' if kind == 'book' else 'sourceUrl'],
                'json': raw, 'sha256': hashlib.sha256(raw.encode()).hexdigest()}

    def test_all_modes_and_every_identity_get_a_handling_row(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary); output = root / 'out'; output.mkdir()
            entries = [self.entry('book', {'bookSourceUrl': f'https://fixture.example/mode/{mode}', 'bookSourceType': mode,
                'ruleSearch': {'name': 'a@text'}, 'searchUrl': '/search?q={{key}}'}) for mode in range(4)]
            entries.append(self.entry('rss', {'sourceUrl': 'https://feed.example/rss', 'singleUrl': False}))
            source = root / 'original.jsonl'; source.write_text(''.join(json.dumps(entry) + '\n' for entry in entries))
            summary = inventory.inventory(source, output)
            self.assertEqual(5, summary['exactOriginalEnvelopesVerified'])
            self.assertEqual({'text': 1, 'audio': 1, 'image': 1, 'download': 1, 'article_feed': 1}, summary['recordsByMode'])
            rows = [json.loads(line) for line in (output / 'all-source-handling.jsonl').read_text().splitlines()]
            self.assertEqual({(entry['kind'], entry['id']) for entry in entries}, {(row['kind'], row['id']) for row in rows})
            self.assertTrue(all(row['liveAvailabilityVerifiedByThisInventory'] is False for row in rows))
            self.assertFalse(any(entry['id'] in json.dumps(summary) for entry in entries))
            self.assertEqual(0, summary['networkRequests']); self.assertEqual(0, summary['untrustedSourceScriptsExecuted'])
            pipelines = json.loads((output / 'reverse-module-priorities.json').read_text())['requiredModePipelines']
            self.assertEqual({'text': 1, 'audio': 1, 'image': 1, 'download': 1},
                {mode: pipelines[mode] for mode in ('text', 'audio', 'image', 'download')})

    def test_a_wrong_original_hash_stops_instead_of_silently_dropping_the_source(self):
        entry = self.entry('book', {'bookSourceUrl': 'https://fixture.example/'})
        entry['sha256'] = '0' * 64
        with self.assertRaisesRegex(ValueError, 'SHA'):
            inventory.checked(entry)

    def test_requirements_keep_rhino_post_xpath_and_session_boundaries_distinct(self):
        source = {'bookSourceUrl': 'https://fixture.example/', 'loginUrl': '/login',
            'searchUrl': '/search, {"method":"POST","body":"key={{key}}"}',
            'ruleSearch': {'name': '@xpath://h1/text()', 'bookUrl': '@js:java.ajax(result); java.put("key", result);'},
            'header': '{"Cookie":"owned-fixture-cookie"}'}
        tags, calls = inventory.requirements(source)
        self.assertTrue({'javascript_runtime', 'xpath', 'http_post', 'request_options', 'private_or_nonstandard_headers',
                         'session_or_login_configuration'} <= set(tags))
        self.assertIn('java.ajax', calls); self.assertIn('java.put', calls)
        self.assertEqual('needs_session_or_browser_configuration_review',
            inventory.disposition('book', 0, tags, {'supported': False}, True))

    def test_invalid_identity_is_an_unmapped_task_and_not_a_dead_website(self):
        self.assertFalse(inventory.static_public_identity(' https://fixture.example/'))
        self.assertFalse(inventory.static_public_identity('https://127.0.0.1/'))
        self.assertTrue(inventory.static_public_identity('https://fixture.example/#edition-a'))
        self.assertEqual('needs_identity_and_public_request_mapping',
            inventory.disposition('book', 0, [], {'supported': False}, False))
        self.assertEqual('static_engine_supported_awaiting_live_verification',
            inventory.disposition('book', 0, [], {'supported': True}, False))

    def test_private_outputs_cannot_be_written_into_the_public_project(self):
        with self.assertRaisesRegex(ValueError, 'outside'):
            inventory.private_path(inventory.ROOT / 'docs/evidence/private.json')

    def test_engine_normalized_identity_is_not_lost_by_a_stricter_python_url_parser(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary); output = root / 'out'; output.mkdir()
            entry = self.entry('book', {'bookSourceUrl': r'https:\\fixture.example\book', 'bookSourceType': 0})
            source = root / 'original.jsonl'; source.write_text(json.dumps(entry) + '\n')
            assessed = root / 'assessment.jsonl'; assessed.write_text(json.dumps({'kind': 'book', 'id': entry['id'],
                'sha256': entry['sha256'], 'supported': True, 'reason': None, 'normalizedHost': 'fixture.example',
                'staticPublicIdentity': True, 'capabilities': []}) + '\n')
            summary = inventory.inventory(source, output, assessed)
            self.assertEqual(1, summary['sourceHostnameCount'])
            self.assertEqual(1, summary['staticPublicSourceIdentities'])
            self.assertEqual({'static_engine_supported_awaiting_live_verification': 1}, summary['handlingCounts'])


if __name__ == '__main__':
    unittest.main()
