import importlib.util
import io
import json
import hashlib
from pathlib import Path
import sqlite3
import tarfile
import tempfile
import unittest

SPEC = importlib.util.spec_from_file_location("device_data_audit", Path(__file__).resolve().parents[1] / "device-data-audit.py")
AUDIT = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(AUDIT)


class DeviceDataAuditTest(unittest.TestCase):
    def snapshot(self, directory, label, migrated=False, position=321, different_id=False,
                 book_updates=None, rss_updates=None, evidence_change=None, cleaned=False, protected=False, feed_reference=False):
        database = directory / (label + ".db")
        book = dict(AUDIT.BOOK_DEFAULTS, bookSourceUrl="PRIVATE_SOURCE_ID", bookSourceName="PRIVATE_SOURCE_NAME",
                    bookSourceGroup="PRIVATE_GROUP", bookSourceType=0, customOrder=37, enabled=1, enabledExplore=1,
                    lastUpdateTime=123, respondTime=180000, weight=4, bookSourceComment="PRIVATE_COMMENT",
                    searchUrl="https://fixture.invalid/search", exploreUrl="Fixture::https://fixture.invalid/explore",
                    jsLib="private program", ruleContent=json.dumps({"content": "#content"}))
        rss = dict(AUDIT.RSS_DEFAULTS, sourceUrl="PRIVATE_FEED_ID", sourceName="PRIVATE_FEED_NAME", sourceIcon="PRIVATE_ICON",
                   sourceGroup="PRIVATE_GROUP", customOrder=41, enabled=1, lastUpdateTime=456, articleStyle=2,
                   sourceComment="PRIVATE_COMMENT", ruleArticles="item", ruleContent="body", sortUrl="https://fixture.invalid/rss", enableJs=1)
        original = {"book": book.copy(), "rss": rss.copy()}
        entries, plan = [], []
        for kind, source in [("book", book), ("rss", rss)]:
            native = {key: (bool(value) if key in AUDIT.BOOLEAN_FIELDS else json.loads(value) if kind == "book" and key in AUDIT.RULE_OBJECTS else value)
                      for key, value in source.items() if value is not None}
            native = {key: value for key, value in native.items() if value is not None}
            raw = json.dumps(native, ensure_ascii=False, indent=2)
            sha = hashlib.sha256(raw.encode()).hexdigest()
            identity = source["bookSourceUrl" if kind == "book" else "sourceUrl"]
            entries.append({"kind": kind, "id": identity, "json": raw, "sha256": sha})
            plan.append({"kind": kind, "id": identity, "sha256": sha, "mode": "active"})
            if migrated:
                source.update(AUDIT.BOOK_DEFAULTS if kind == "book" else AUDIT.RSS_DEFAULTS)
                source.update({key: original[kind][key] for key in (AUDIT.BOOK_PRESERVED if kind == "book" else AUDIT.RSS_PRESERVED)})
                source["bookSourceComment" if kind == "book" else "sourceComment"] = AUDIT.COMMENT_PREFIX + sha
                source["exploreUrl" if kind == "book" else "sortUrl"] = "全部::reader-cloud://category/all" if kind == "book" else "全部::reader-cloud://articles"
        if protected:
            plan = plan[:1]
            rss = original["rss"].copy()
        if different_id:
            book["bookSourceUrl"] = "PRIVATE_SOURCE_OTHER"
        book.update(book_updates or {}); rss.update(rss_updates or {})
        with sqlite3.connect(database) as connection:
            connection.execute("PRAGMA user_version=75")
            connection.execute("CREATE TABLE books(bookUrl TEXT PRIMARY KEY, name TEXT, durChapterPos INTEGER)")
            connection.execute("INSERT INTO books VALUES(?,?,?)", ("PRIVATE_BOOK_URL", "PRIVATE_BOOK_TITLE", position))
            connection.execute("CREATE TABLE readRecord(id INTEGER PRIMARY KEY, readTime INTEGER)")
            connection.execute("INSERT INTO readRecord VALUES(1,3380)")
            if feed_reference:
                connection.execute("CREATE TABLE rssArticles(origin TEXT PRIMARY KEY)")
                connection.execute("INSERT INTO rssArticles VALUES('PRIVATE_FEED_ID')")
            for table, source, primary in [("book_sources", book, "bookSourceUrl"), ("rssSources", rss, "sourceUrl")]:
                columns = list(source)
                connection.execute("CREATE TABLE " + table + "(" + ",".join('"' + key + '" ' + ("INTEGER" if isinstance(source[key], int) else "TEXT") + (" PRIMARY KEY" if key == primary else "") for key in columns) + ")")
                connection.execute("INSERT INTO " + table + " VALUES(" + ",".join("?" for _ in columns) + ")", list(source.values()))
        archive = directory / (label + ".tar")
        native_entries = entries
        # Keep native exact JSON in its own evidence envelope, never regenerate
        # the hash from a SQLite row in the audit implementation.
        entries = {"./databases/legado.db": database.read_bytes(),
                   "./files/PRIVATE_BOOK_TITLE/chapter": b"self written cached text",
                   "./shared_prefs/settings.xml": b'<map><string name="secret">PRIVATE_TOKEN</string><long name="position" value="321"/></map>'}
        revision = hashlib.sha256(b"https://cloud.fixture.invalid\x00PRIVATE_TOKEN").hexdigest()
        entries["./shared_prefs/reader_cloud_v1.xml"] = ('<map><string name="url">https://cloud.fixture.invalid</string><string name="token">PRIVATE_TOKEN</string><boolean name="enabled" value="' + ('true' if migrated else 'false') + '"/>' + ('<string name="approved_revision">' + revision + '</string><set name="approved_book"><string>PRIVATE_SOURCE_ID</string></set>' if migrated else '') + '</map>').encode()
        if migrated:
            evidence = {"backup": native_entries, "plan": plan,
                        "owner": {"uploaded": 2, "converted": 1 if protected else 2, "archived": 1 if protected else 0,
                                  "archiveCleaned": 0, "preserved": 1 if protected else 0, "skipped": 0}}
            if evidence_change:
                evidence_change(evidence)
            if not cleaned:
                entries["./files/reader-cloud-migration/owned.backup.jsonl"] = ("\n".join(json.dumps(row) for row in evidence["backup"]) + "\n").encode()
                entries["./files/reader-cloud-migration/owned.plan.jsonl"] = ("\n".join(json.dumps(row) for row in evidence["plan"]) + "\n").encode()
            entries["./files/reader-cloud-migration/owner-result.json"] = json.dumps(evidence["owner"]).encode()
        with tarfile.open(archive, "w") as output:
            for name, data in entries.items():
                member = tarfile.TarInfo(name); member.size = len(data)
                output.addfile(member, io.BytesIO(data))
        return archive

    def test_installed_preserves_all_tables_and_report_contains_no_private_values(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            before = self.snapshot(directory, "before")
            after = self.snapshot(directory, "after")
            report = AUDIT.audit(before, after, "installed")
            self.assertTrue(report["ok"])
            self.assertEqual(1, report["tables"]["books"]["afterRows"])
            encoded = json.dumps(report)
            for secret in ("PRIVATE_TOKEN", "PRIVATE_BOOK_TITLE", "PRIVATE_SOURCE_ID", "PRIVATE_BOOK_URL", "PRIVATE_FEED_ID"):
                self.assertNotIn(secret, encoded)

    def test_migration_allows_only_rule_changes_and_semantic_pref_reordering(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            before = self.snapshot(directory, "before")
            after = self.snapshot(directory, "after", migrated=True)
            report = AUDIT.audit(before, after, "migrated")
            self.assertTrue(report["ok"])
            self.assertEqual(1, report["sources"]["before"]["book_sources"]["fullRules"])
            self.assertEqual(0, report["sources"]["after"]["book_sources"]["fullRules"])
            self.assertEqual(1, report["sources"]["after"]["rssSources"]["remote"])
            self.assertEqual(4, report["fileDifferences"]["allowedCloudChanges"])
            self.assertFalse(report["localOriginalRuleCleanupVerified"])
            self.assertFalse(AUDIT.audit(before, after, "installed")["businessDataEqual"])

    def test_reading_position_or_source_identity_change_is_rejected(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            before = self.snapshot(directory, "before")
            moved = self.snapshot(directory, "moved", migrated=True, position=0)
            report = AUDIT.audit(before, moved, "migrated")
            self.assertFalse(report["ok"])
            self.assertEqual(["durChapterPos"], report["tables"]["books"]["changedColumns"])
            other_source = self.snapshot(directory, "other-source", migrated=True, different_id=True)
            self.assertFalse(AUDIT.audit(before, other_source, "migrated")["sourceIdsEqual"])

    def test_migrated_source_contract_rejects_metadata_rule_or_marker_corruption(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            before = self.snapshot(directory, "before")
            changes = [
                ({"bookSourceName": "unexpected-name"}, None),
                ({"customOrder": 999}, None),
                ({"searchUrl": "https://unexpected.invalid/search"}, None),
                ({"bookSourceComment": AUDIT.COMMENT_PREFIX + "f" * 64}, None),
                ({"jsLib": "private original program still present"}, None),
                ({"ruleContent": None}, None),  # Production Room stores TEXT "null".
                (None, {"sourceGroup": "unexpected-group"}),
                (None, {"injectJs": "unexpected program"}),
                (None, {"sortUrl": "https://unexpected.invalid/feed"}),
                (None, {"ruleContent": "body"}),
            ]
            for index, (book, rss) in enumerate(changes):
                with self.subTest(case=index):
                    after = self.snapshot(directory, "after" + str(index), migrated=True, book_updates=book, rss_updates=rss)
                    report = AUDIT.audit(before, after, "migrated")
                    self.assertTrue(report["businessDataEqual"])
                    self.assertFalse(report["migrationContract"]["okay"])
                    self.assertFalse(report["ok"])
                    self.assertNotIn("PRIVATE_SOURCE_ID", json.dumps(report))

    def test_native_exact_sha_plan_coverage_and_duplicate_evidence_fail_closed(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            before = self.snapshot(directory, "before")
            def wrong_original(evidence):
                evidence["backup"][0]["json"] += " "
            def wrong_plan_hash(evidence):
                evidence["plan"][0]["sha256"] = "f" * 64
            def missing_plan_row(evidence):
                evidence["plan"].pop()
            def duplicate_plan(evidence):
                evidence["plan"].append(evidence["plan"][0].copy())
            for index, change in enumerate((wrong_original, wrong_plan_hash, missing_plan_row, duplicate_plan)):
                with self.subTest(case=index):
                    after = self.snapshot(directory, "after" + str(index), migrated=True, evidence_change=change)
                    report = AUDIT.audit(before, after, "migrated")
                    self.assertFalse(report["ok"])
                    self.assertFalse(report["migrationContract"]["okay"])
                    self.assertIn("NO_COMPLETE_MATCHING_OPERATION", report["migrationContract"]["errors"])

    def test_native_exact_json_must_bind_to_the_original_room_fields(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            before = self.snapshot(directory, "before")
            def changed_native(evidence):
                entry = evidence["backup"][0]
                source = json.loads(entry["json"]); source["customOrder"] = 999
                entry["json"] = json.dumps(source, indent=2)
                entry["sha256"] = hashlib.sha256(entry["json"].encode()).hexdigest()
                evidence["plan"][0]["sha256"] = entry["sha256"]
            after = self.snapshot(directory, "after", migrated=True, evidence_change=changed_native)
            report = AUDIT.audit(before, after, "migrated")
            self.assertIn("NATIVE_BACKUP_DATABASE_BINDING_MISMATCH", report["migrationContract"]["errors"])
            self.assertIn("customOrder", report["migrationContract"]["affectedColumns"])

    def test_unplanned_protected_sources_must_remain_byte_identical(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            before = self.snapshot(directory, "before", feed_reference=True)
            after = self.snapshot(directory, "after", migrated=True, protected=True, feed_reference=True)
            report = AUDIT.audit(before, after, "migrated")
            self.assertTrue(report["ok"])
            self.assertEqual(1, report["migrationContract"]["counts"]["preserved"])
            changed = self.snapshot(directory, "changed", migrated=True, protected=True, feed_reference=True, rss_updates={"sourceName": "unexpected"})
            report = AUDIT.audit(before, changed, "migrated")
            self.assertIn("UNPLANNED_SOURCE_CHANGED", report["migrationContract"]["errors"])

    def test_cleanup_requires_zero_phone_original_copies_and_external_exact_evidence(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            before = self.snapshot(directory, "before")
            evidence = self.snapshot(directory, "evidence", migrated=True)
            first = AUDIT.audit(before, evidence, "migrated")
            self.assertTrue(first["ok"])
            self.assertFalse(first["localOriginalRuleCleanupVerified"])
            self.assertGreater(first["migrationContract"]["localOriginalRuleCopies"]["bytes"], 0)
            cleaned = self.snapshot(directory, "cleaned", migrated=True, cleaned=True)
            self.assertFalse(AUDIT.audit(before, cleaned, "migrated")["ok"])
            report = AUDIT.audit(before, cleaned, "migrated", evidence)
            self.assertTrue(report["ok"])
            self.assertTrue(report["localOriginalRuleCleanupVerified"])
            self.assertTrue(report["migrationContract"]["usedExternalEvidenceSnapshot"])

    def test_archive_stub_is_disabled_and_cannot_clear_a_referenced_source(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            draft = self.snapshot(directory, "draft", migrated=True)
            with tarfile.open(draft) as archive:
                records = [json.loads(line) for line in archive.extractfile("./files/reader-cloud-migration/owned.backup.jsonl")]
            sha = records[1]["sha256"]
            def archive_feed(evidence):
                evidence["plan"][1]["mode"] = "archive"
                evidence["owner"].update(converted=1, archived=1, archiveCleaned=1)
            updates = {"enabled": 0, "sortUrl": None, "sourceComment": AUDIT.ARCHIVE_PREFIX + sha}
            before = self.snapshot(directory, "before")
            after = self.snapshot(directory, "after", migrated=True, rss_updates=updates, evidence_change=archive_feed)
            self.assertTrue(AUDIT.audit(before, after, "migrated")["ok"])
            protected_before = self.snapshot(directory, "protected-before", feed_reference=True)
            protected_after = self.snapshot(directory, "protected-after", migrated=True, feed_reference=True,
                                            rss_updates=updates, evidence_change=archive_feed)
            report = AUDIT.audit(protected_before, protected_after, "migrated")
            self.assertIn("PROTECTED_SOURCE_ARCHIVED", report["migrationContract"]["errors"])

    def test_same_column_multisets_cannot_hide_two_books_swapping_names_or_progress(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            before, after = directory / "before.db", directory / "after.db"
            for path, swapped in [(before, False), (after, True)]:
                with sqlite3.connect(path) as database:
                    database.execute("CREATE TABLE books(bookUrl TEXT PRIMARY KEY,name TEXT,durChapterPos INTEGER,readConfig TEXT)")
                    database.executemany("INSERT INTO books VALUES(?,?,?,?)", [
                        ("PRIVATE_BOOK_A", "PRIVATE_NAME_B" if swapped else "PRIVATE_NAME_A", 40 if swapped else 20, "private-anchor-a"),
                        ("PRIVATE_BOOK_B", "PRIVATE_NAME_A" if swapped else "PRIVATE_NAME_B", 20 if swapped else 40, "private-anchor-b"),
                    ])
            state = lambda path: {"database": AUDIT.database_audit(path), "files": {}, "prefs": {}}
            report = AUDIT.compare(state(before), state(after), "installed")
            self.assertTrue(report["tables"]["books"]["identityEqual"])
            self.assertEqual(2, report["tables"]["books"]["changedRows"])
            self.assertEqual(["durChapterPos", "name"], report["tables"]["books"]["changedColumns"])
            self.assertFalse(report["booksReadingState"]["equal"])
            self.assertNotIn("PRIVATE_BOOK_A", json.dumps(report))

    def test_composite_primary_key_binds_reading_time_to_the_actual_record(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            before, after = directory / "before.db", directory / "after.db"
            for path, swapped in [(before, False), (after, True)]:
                with sqlite3.connect(path) as database:
                    database.execute("CREATE TABLE readRecord(deviceId TEXT,bookName TEXT,readTime INTEGER,PRIMARY KEY(deviceId,bookName))")
                    database.executemany("INSERT INTO readRecord VALUES(?,?,?)", [
                        ("private-device", "private-book-a", 200 if swapped else 100),
                        ("private-device", "private-book-b", 100 if swapped else 200),
                    ])
            state = lambda path: {"database": AUDIT.database_audit(path), "files": {}, "prefs": {}}
            report = AUDIT.compare(state(before), state(after), "installed")
            self.assertEqual(["deviceId", "bookName"], report["tables"]["readRecord"]["identityColumns"])
            self.assertEqual(["readTime"], report["tables"]["readRecord"]["changedColumns"])
            self.assertFalse(report["businessDataEqual"])

    def test_metadata_and_cookie_rotation_are_explained_without_relaxing_strict_equality(self):
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            before, after = directory / "before.db", directory / "after.db"
            for path, changed in [(before, False), (after, True)]:
                with sqlite3.connect(path) as database:
                    database.execute('CREATE TABLE books(bookUrl TEXT PRIMARY KEY,name TEXT,"group" INTEGER,durChapterPos INTEGER,readConfig TEXT,type INTEGER,lastCheckTime INTEGER)')
                    database.execute("INSERT INTO books VALUES(?,?,?,?,?,?,?)", ("private-book", "private-title", 7, 321, "private-anchor", 8 if changed else 0, 20 if changed else 10))
                    database.execute("CREATE TABLE cookies(url TEXT PRIMARY KEY,cookie TEXT)")
                    database.execute("INSERT INTO cookies VALUES(?,?)", ("private-domain", "_ga=new-private-token; sid=unchanged" if changed else "sid=unchanged; _ga=old-private-token"))
            state = lambda path: {"database": AUDIT.database_audit(path), "files": {}, "prefs": {}}
            report = AUDIT.compare(state(before), state(after), "installed")
            self.assertFalse(report["ok"])
            self.assertTrue(report["booksReadingState"]["equal"])
            self.assertEqual({"rows": 1, "textBitAddedOnly": 1, "otherBitsChanged": 0,
                              "updateErrorBitToggledOnly": 0, "mediaOrShelfBitsChanged": 0}, report["booksReadingState"]["typeChanges"])
            self.assertEqual(1, report["cookieChanges"]["updatedValues"])
            self.assertEqual(0, report["cookieChanges"]["removedItems"])
            self.assertEqual({"analytics": 1}, report["cookieChanges"]["changedFamilies"])
            self.assertNotIn("private-token", json.dumps(report))


if __name__ == "__main__":
    unittest.main()
