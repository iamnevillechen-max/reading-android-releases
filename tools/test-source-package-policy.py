#!/usr/bin/env python3
"""Privacy regression for the public Android/server corresponding-source archive."""
from pathlib import Path
import json
import tempfile
import unittest

from source_package_policy import iter_source_files, public_source_bytes, publishable


class SourcePackagePolicyTest(unittest.TestCase):
    def test_runtime_credentials_and_private_databases_are_excluded(self):
        for path in ['server/worker/node_modules/cheerio/index.js', 'server/worker/.wrangler/state/db.sqlite',
                     'server/worker/.env', 'server/worker/.env.production', 'server/worker/.dev.vars.local',
                     '.toolchain/cloud-deployment.json', 'server/worker/credentials.json', 'server/worker/tokens.json',
                     'server/worker/private.db', 'server/worker/private.db-wal', 'server/worker/source.sqlite3-shm',
                     'tools/__pycache__/cloud-admin.pyc', 'server/private.key', 'server/worker/outputs/private-import.json']:
            with self.subTest(path=path):
                self.assertFalse(publishable(path))

    def test_source_build_configuration_license_and_tests_remain_complete(self):
        for path in ['server/LICENSE', 'server/catalogue/taxonomy.json', 'server/worker/src/index.ts',
                     'server/worker/src/compiled-sources.ts', 'server/worker/package-lock.json',
                     'server/worker/wrangler.jsonc', 'server/worker/migrations/0001_sources.sql',
                     'server/worker/tests/api.test.ts', 'tools/cloud-admin.py',
                     'upstream/app/src/main/res/xml/pref_main.xml']:
            with self.subTest(path=path):
                self.assertTrue(publishable(path))

    def test_recursive_collection_prunes_runtime_directories(self):
        with tempfile.TemporaryDirectory() as temporary:
            root = Path(temporary)
            for path in ['server/worker/src/index.ts', 'server/LICENSE', 'server/worker/node_modules/.env',
                         'server/worker/.wrangler/secrets.json', 'server/worker/private.db']:
                target = root / path
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_text('fixture')
            self.assertEqual({'server/LICENSE', 'server/worker/src/index.ts'},
                {path.relative_to(root).as_posix() for path in iter_source_files(root / 'server', root)})

    def test_build_diagnostics_stay_local_while_acceptance_results_remain_public(self):
        for path in ['docs/evidence/reader-1.1.3-build.log',
                     'multiread/docs/evidence/reader-1.2.1-build.log']:
            with self.subTest(path=path):
                self.assertFalse(publishable(path))
        for path in ['docs/evidence/reader-1.2.1-native-results.json',
                     'docs/evidence/reader-1.2.1-native.log', 'tools/build.sh']:
            with self.subTest(path=path):
                self.assertTrue(publishable(path))

    def test_public_worker_template_preserves_runtime_contract_without_owner_ids(self):
        configuration = {
            'name': 'fixture-reader', 'main': 'src/index.ts',
            'compatibility_date': '2026-10-28', 'compatibility_flags': ['nodejs_compat'],
            'account_id': 'fixture-owner',
            'd1_databases': [{'binding': 'DB', 'database_name': 'fixture-db',
                              'database_id': 'fixture-database',
                              'preview_database_id': 'fixture-preview', 'migrations_dir': 'migrations'}],
            'triggers': {'crons': ['*/30 * * * *']},
            'vars': {'MAINTENANCE_ENABLED': 'true', 'MAINTENANCE_DAILY_WRITES': '5000'},
        }
        original = json.dumps(configuration).encode()
        public = public_source_bytes('server/worker/wrangler.jsonc', original)
        expected = json.loads(original)
        expected.pop('account_id')
        expected['d1_databases'][0].pop('preview_database_id')
        expected['d1_databases'][0]['database_id'] = 'REPLACE_WITH_YOUR_D1_DATABASE_ID'
        self.assertEqual(expected, json.loads(public))
        for identifier in (b'fixture-owner', b'fixture-database', b'fixture-preview'):
            self.assertNotIn(identifier, public)
        self.assertEqual(configuration, json.loads(original))
        self.assertEqual(public, public_source_bytes('server/feedback/wrangler.jsonc', original))
        self.assertEqual(original, public_source_bytes('server/worker/src/index.ts', original))

    def test_unreadable_worker_configuration_stops_public_packaging(self):
        for data in (b'not JSON', b'{}', b'{"d1_databases":null}', b'[]'):
            with self.subTest(data=data):
                with self.assertRaisesRegex(ValueError, 'public Wrangler deployment template'):
                    public_source_bytes('server/worker/wrangler.jsonc', data)


if __name__ == '__main__':
    unittest.main()
