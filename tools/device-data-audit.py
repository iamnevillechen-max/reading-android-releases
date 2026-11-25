#!/usr/bin/env python3
"""Compare private Android data tar snapshots without exporting personal row/file names.

SQLite and bounded migration evidence enter a mode-0700 temporary directory.
Source archives remain read-only. SQLite may replay a WAL in the disposable
copy; application queries are read-only. Native exact JSON proves rule hashes;
Python does not attempt to reproduce GSON serialization. Successful migration
validation is separate from removal of phone-local backup/plan copies.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import sqlite3
import struct
import tarfile
import tempfile
import re
from urllib.parse import urlsplit
import xml.etree.ElementTree as ET

SOURCE_TABLES = {"book_sources": "bookSourceUrl", "rssSources": "sourceUrl"}
INTERNAL_TABLES = {"android_metadata", "room_master_table"}
MARKER = "reader-cloud://source"
BOOK_RULES = {"bookUrlPattern", "jsLib", "header", "loginUrl", "loginUi", "loginCheckJs", "coverDecodeJs", "searchUrl", "exploreUrl", "exploreScreen", "ruleExplore", "ruleSearch", "ruleBookInfo", "ruleToc", "ruleContent", "ruleReview"}
RSS_RULES = {"jsLib", "header", "loginUrl", "loginUi", "loginCheckJs", "coverDecodeJs", "sortUrl", "ruleArticles", "ruleNextPage", "ruleTitle", "rulePubDate", "ruleDescription", "ruleImage", "ruleLink", "ruleContent", "contentWhitelist", "contentBlacklist", "shouldOverrideUrlLoading", "style", "injectJs"}
RULE_OBJECTS = {"ruleExplore", "ruleSearch", "ruleBookInfo", "ruleToc", "ruleContent", "ruleReview"}
BOOK_PRESERVED = {"bookSourceUrl", "bookSourceName", "bookSourceGroup", "bookSourceType", "customOrder", "enabled", "enabledExplore", "lastUpdateTime", "respondTime", "weight"}
RSS_PRESERVED = {"sourceUrl", "sourceName", "sourceIcon", "sourceGroup", "enabled", "customOrder", "lastUpdateTime", "articleStyle"}
BOOK_DEFAULTS = {key: None for key in BOOK_RULES | {"concurrentRate", "variableComment"}}
BOOK_DEFAULTS.update(enabledCookieJar=1, searchUrl=MARKER)
# Room's BookSource.Converters returns GSON.toJson(null), the TEXT "null".
BOOK_DEFAULTS.update({key: "null" for key in RULE_OBJECTS})
RSS_DEFAULTS = {key: None for key in RSS_RULES | {"concurrentRate", "variableComment"}}
RSS_DEFAULTS.update(enabledCookieJar=1, enableJs=0, loadWithBaseUrl=1, singleUrl=0, ruleArticles=MARKER, ruleContent=MARKER)
COMMENT_PREFIX = "雲端規則 SHA-256 "
ARCHIVE_PREFIX = "雲端存檔（未核驗） SHA-256 "
BOOLEAN_FIELDS = {"enabled", "enabledExplore", "enabledCookieJar", "singleUrl", "enableJs", "loadWithBaseUrl"}
MIGRATION_FILE = re.compile(r"^([A-Za-z0-9_-]+)\.(backup\.jsonl|backup\.pending|plan\.jsonl)$")


class AuditError(Exception):
    pass


def digest(value):
    return hashlib.sha256(value).hexdigest()


def cell(value):
    if value is None:
        return b"N"
    if isinstance(value, int):
        return b"I" + str(value).encode()
    if isinstance(value, float):
        return b"F" + struct.pack(">d", value)
    if isinstance(value, bytes):
        return b"B" + value
    return b"T" + value.encode("utf-8", "surrogateescape")


def set_hash(values):
    result = hashlib.sha256()
    for value in sorted(values):
        result.update(len(value).to_bytes(8, "big")); result.update(value)
    return result.hexdigest()


def quoted(value):
    return '"' + value.replace('"', '""') + '"'


def framed(values):
    return b"".join(len(value).to_bytes(8, "big") + value for value in values)


def typed_source_hash(table, column, value):
    """Bind native JSON to Room columns without pretending to reproduce GSON bytes."""
    if column in BOOLEAN_FIELDS and value is not None:
        value = int(bool(value))
    if table == "book_sources" and column in RULE_OBJECTS and value is not None:
        if isinstance(value, str):
            try:
                value = json.loads(value)
            except (ValueError, TypeError):
                return digest(b"INVALID_ROOM_RULE")
        if value is None:
            return digest(cell(None))
        def clean(item):
            if isinstance(item, dict):
                return {key: clean(data) for key, data in item.items() if data is not None}
            if isinstance(item, list):
                return [clean(data) for data in item]
            return item
        value = json.dumps(clean(value), ensure_ascii=True, sort_keys=True, separators=(",", ":"))
    return digest(cell(value))


def source_row(table, columns, row):
    data = dict(zip(columns, row))
    return {"raw": {key: digest(cell(value)) for key, value in data.items()},
            "typed": {key: typed_source_hash(table, key, value) for key, value in data.items()},
            "remote": data.get("searchUrl" if table == "book_sources" else "ruleArticles") == MARKER,
            "hasExplore": isinstance(data.get("exploreUrl"), str) and bool(data["exploreUrl"].strip())}


def cookie_summary(value):
    """Only hashed cookie names/values and fixed categories remain in memory."""
    items = {}
    for part in str(value or "").split(";"):
        if "=" not in part:
            continue
        name, data = part.split("=", 1)
        name = name.strip()
        if not name:
            continue
        lowered = name.lower()
        category = ("cloudflare" if lowered.startswith(("__cf", "cf_clearance")) else
                    "analytics" if lowered.startswith(("hm_lvt_", "hm_lpvt_", "_ga", "_gid", "_gat")) else
                    "session" if lowered in {"jsessionid", "phpsessid", "asp.net_sessionid", "session", "sessionid", "sid"} else
                    "csrf" if "csrf" in lowered or "xsrf" in lowered else "other")
        items[digest(cell(name))] = (digest(cell(data.strip())), category)
    return items


def rule_stats(table, columns, row, stats):
    data = dict(zip(columns, row))
    remote = data.get("searchUrl" if table == "book_sources" else "ruleArticles") == MARKER
    stats["count"] += 1
    stats["remote"] += int(remote)
    stats["enabled"] += int(bool(data.get("enabled")))
    stats["enabledRemote"] += int(remote and bool(data.get("enabled")))
    amount = 0
    for key in BOOK_RULES if table == "book_sources" else RSS_RULES:
        value = data.get(key)
        if value is None or str(value).strip() in {"", "{}", "[]", "null", MARKER}:
            continue
        if remote and key in {"searchUrl", "exploreUrl", "sortUrl", "ruleArticles", "ruleContent"}:
            # Static/virtual route metadata is part of the lightweight stub.
            continue
        amount += len(value if isinstance(value, bytes) else str(value).encode("utf-8", "surrogateescape"))
    stats["ruleBytes"] += amount
    stats["fullRules"] += int(amount > 0)
    stats["remoteWithRules"] += int(remote and amount > 0)


def database_audit(path, include_sources=False):
    connection = sqlite3.connect(path)
    connection.text_factory = lambda value: value.decode("utf-8", "surrogateescape")
    try:
        connection.execute("PRAGMA trusted_schema=OFF")
        connection.execute("PRAGMA query_only=ON")
        if connection.execute("PRAGMA integrity_check(10)").fetchall() != [("ok",)]:
            raise AuditError("DATABASE_INTEGRITY_FAILED")
        tables = {}
        source_ids = {}
        sources = {}
        private_metadata = {"books": {}, "cookies": {}}
        private_sources = {}
        references = {"book": set(), "rss": set(), "complete": True}
        names = [row[0] for row in connection.execute("SELECT name FROM sqlite_master WHERE type='table' ORDER BY name")
                 if not row[0].startswith("sqlite_") and row[0] not in INTERNAL_TABLES]
        for name in names:
            schema = connection.execute("PRAGMA table_info(" + quoted(name) + ")").fetchall()
            columns = [item[1] for item in schema]
            rows = []
            column_hashes = [[] for _ in columns]
            primary = [item[1] for item in sorted(schema, key=lambda item: item[5]) if item[5] > 0]
            primary_indexes = [columns.index(column) for column in primary]
            identity_hashes = []
            by_identity = {}
            identities = []
            stats = {"count": 0, "remote": 0, "enabled": 0, "enabledRemote": 0, "fullRules": 0, "ruleBytes": 0, "remoteWithRules": 0}
            # Bind every field to its declared (possibly composite) primary key.
            # A column-value multiset alone misses two books swapping progress.
            query = "SELECT " + ("" if primary else "rowid,") + "* FROM " + quoted(name)
            for record in connection.execute(query):
                row = record if primary else record[1:]
                identity = hashlib.sha256(framed(cell(row[index]) for index in primary_indexes)
                                          if primary else framed([cell(record[0])])).digest()
                if identity in by_identity:
                    raise AuditError("AMBIGUOUS_ROW_IDENTITY")
                identity_hashes.append(identity)
                output = hashlib.sha256()
                output.update(identity)
                for index, value in enumerate(row):
                    encoded = cell(value)
                    output.update(len(encoded).to_bytes(8, "big")); output.update(encoded)
                    column_hashes[index].append(hashlib.sha256(identity + framed([encoded])).digest())
                rows.append(output.digest())
                by_identity[identity.hex()] = output.hexdigest()
                if name == "books" and "type" in columns:
                    private_metadata["books"][identity.hex()] = int(row[columns.index("type")] or 0)
                elif name == "cookies" and "cookie" in columns:
                    private_metadata["cookies"][identity.hex()] = cookie_summary(row[columns.index("cookie")])
                if name in SOURCE_TABLES:
                    identities.append(hashlib.sha256(cell(row[columns.index(SOURCE_TABLES[name])])).digest())
                    rule_stats(name, columns, row, stats)
                    if include_sources:
                        saved = source_row(name, columns, row)
                        saved["rowSha256"] = output.hexdigest()
                        private_sources.setdefault(name, {})[row[columns.index(SOURCE_TABLES[name])]] = saved
                if include_sources and name in {"books", "searchBooks", "rssArticles", "rssStars"} and "origin" in columns:
                    references["book" if name in {"books", "searchBooks"} else "rss"].add(row[columns.index("origin")])
            tables[name] = {"rows": len(rows), "sha256": set_hash(rows),
                            "schemaSha256": digest(json.dumps(schema, ensure_ascii=True).encode()),
                            "columnSha256": {column: set_hash(values) for column, values in zip(columns, column_hashes)},
                            "identityColumns": primary or ["__rowid__"], "identitySha256": set_hash(identity_hashes),
                            "_rowHashes": by_identity}
            if name in SOURCE_TABLES:
                source_ids[name] = {"count": len(identities), "sha256": set_hash(identities)}
                sources[name] = stats
        return {"integrity": "ok", "userVersion": connection.execute("PRAGMA user_version").fetchone()[0],
                "tables": tables, "sourceIds": source_ids, "sources": sources, "_metadata": private_metadata,
                "_sourceRows": private_sources, "_references": references}
    finally:
        connection.close()


def preference_summary(data):
    if b"<!DOCTYPE" in data.upper() or b"<!ENTITY" in data.upper():
        return None
    try:
        root = ET.fromstring(data)
        if root.tag != "map":
            return None
        result = {}
        for item in root:
            name = item.attrib["name"]
            if name in result:
                return None
            value = sorted({child.text or "" for child in item}) if item.tag == "set" else (item.text or "") if item.tag == "string" else item.attrib.get("value")
            encoded = json.dumps([item.tag, value], ensure_ascii=True, separators=(",", ":")).encode()
            result[name] = digest(encoded)
        return result
    except (ET.ParseError, KeyError):
        return None


def migration_artifacts():
    return {"operations": {}, "owner": None, "errors": 0,
            "counts": {key: {"count": 0, "bytes": 0} for key in
                       ("backup.jsonl", "backup.pending", "plan.jsonl", "owner-result.json", "owner-compaction-result.json", "other")}}


def strict_json(value):
    def unique(pairs):
        result = {}
        for key, data in pairs:
            if key in result:
                raise ValueError("DUPLICATE_JSON_KEY")
            result[key] = data
        return result
    return json.loads(value, object_pairs_hook=unique)


def read_migration_file(name, stream, size, evidence):
    match = MIGRATION_FILE.fullmatch(name)
    category = match[2] if match else name if name in {"owner-result.json", "owner-compaction-result.json"} else "other"
    evidence["counts"][category]["count"] += 1
    evidence["counts"][category]["bytes"] += size
    if category not in {"backup.jsonl", "plan.jsonl", "owner-result.json"}:
        return
    if category == "owner-result.json":
        try:
            if size > 65_536:
                raise ValueError()
            data = strict_json(stream.read())
            keys = {"uploaded", "converted", "archived", "archiveCleaned", "preserved", "skipped"}
            if not isinstance(data, dict) or not keys <= data.keys() or any(type(data[key]) is not int or data[key] < 0 for key in keys):
                raise ValueError()
            evidence["owner"] = {key: data[key] for key in keys}
        except (ValueError, TypeError, KeyError, UnicodeError):
            evidence["errors"] += 1
        return
    operation = evidence["operations"].setdefault(match[1], {})
    records = {}
    try:
        # The native envelope is one compact JSON line; its inner GSON string
        # retains the exact original whitespace/escaping used for SHA-256.
        for encoded_line in iter(lambda: stream.readline(2 * 1024 * 1024 + 1), b""):
            line = encoded_line.decode("utf-8")
            if len(encoded_line) > 2 * 1024 * 1024:
                raise ValueError()
            data = strict_json(line)
            kind, identity, sha = data["kind"], data["id"], data["sha256"]
            if kind not in {"book", "rss"} or not isinstance(identity, str) or not isinstance(sha, str) or not re.fullmatch("[a-f0-9]{64}", sha):
                raise ValueError()
            key = (kind, identity)
            if key in records:
                raise ValueError()
            if category == "backup.jsonl":
                raw = data["json"]
                if not isinstance(raw, str) or digest(raw.encode("utf-8")) != sha:
                    raise ValueError()
                source = strict_json(raw)
                table = "book_sources" if kind == "book" else "rssSources"
                if not isinstance(source, dict) or source.get(SOURCE_TABLES[table]) != identity:
                    raise ValueError()
                records[key] = {"sha256": sha, "fields": {column: typed_source_hash(table, column, value) for column, value in source.items()}}
            else:
                if data.get("mode") not in {"active", "archive"}:
                    raise ValueError()
                web = data.get("webUrl")
                if web is not None:
                    parsed = urlsplit(web)
                    if kind != "rss" or data["mode"] != "active" or parsed.scheme not in {"http", "https"} or not parsed.hostname or parsed.username or parsed.password or parsed.port not in {None, 80, 443} or re.search(r"[\x00-\x20\x7f]", web):
                        raise ValueError()
                records[key] = {"sha256": sha, "mode": data["mode"], "webUrl": web}
        operation[category] = records
    except (ValueError, TypeError, KeyError, UnicodeError):
        operation[category] = None
        evidence["errors"] += 1


def cloud_preferences(data):
    if preference_summary(data) is None:
        return None
    root = ET.fromstring(data)
    result = {}
    for entry in root:
        name = entry.attrib["name"]
        if name not in {"url", "token", "enabled", "approved_revision", "approved_book", "approved_rss"}:
            continue
        result[name] = ({child.text or "" for child in entry} if entry.tag == "set" else
                        entry.attrib.get("value") == "true" if entry.tag == "boolean" else entry.text or "")
    return result


def archive_audit(archive, include_migration=False):
    files, prefs = {}, {}
    migration_by_prefix, cloud_by_prefix = {}, {}
    protected_by_prefix = {}
    with tempfile.TemporaryDirectory(prefix="reader-private-audit-") as temporary:
        os.chmod(temporary, 0o700)
        groups = {}
        database_prefixes = []
        with tarfile.open(archive, "r|*") as source:
            for member in source:
                parts = tuple(part for part in PurePosixPath(member.name).parts if part not in {"/", ".", ""})
                if ".." in parts:
                    raise AuditError("UNSAFE_ARCHIVE_PATH")
                name = "/".join(parts)
                if member.isdir():
                    continue
                if name in files:
                    raise AuditError("DUPLICATE_ARCHIVE_MEMBER")
                if member.issym() or member.islnk():
                    files[name] = ("link", digest(member.linkname.encode("utf-8", "surrogateescape")), 0)
                    continue
                if not member.isfile():
                    raise AuditError("UNSUPPORTED_ARCHIVE_MEMBER")
                database = len(parts) >= 2 and parts[-2] == "databases" and parts[-1] in {"legado.db", "legado.db-wal", "legado.db-journal"}
                destination = None
                if database:
                    if member.size > 2 * 1024 ** 3:
                        raise AuditError("DATABASE_COPY_TOO_LARGE")
                    prefix = parts[:-2]
                    if prefix not in groups:
                        directory = Path(temporary) / str(len(groups))
                        directory.mkdir(mode=0o700); groups[prefix] = directory
                    destination = (groups[prefix] / parts[-1]).open("xb")
                    os.chmod(destination.name, 0o600)
                    if parts[-1] == "legado.db":
                        database_prefixes.append(prefix)
                preference = len(parts) >= 2 and parts[-2] == "shared_prefs" and parts[-1].endswith(".xml")
                migration = include_migration and len(parts) >= 3 and parts[-3:-1] == ("files", "reader-cloud-migration")
                protection = include_migration and len(parts) >= 3 and parts[-3] == "files" and parts[-1].endswith(".json") and (
                    parts[-2] == "multisource-health-v1" or (parts[-2] == "multisource-v1" and parts[-1].startswith("relation-")))
                data = bytearray() if preference and member.size <= 4 * 1024 ** 2 else None
                migration_stream = tempfile.SpooledTemporaryFile(max_size=1024 * 1024, dir=temporary) if migration else None
                protection_data = bytearray() if protection and member.size <= 1_000_000 else None
                hashed = hashlib.sha256()
                try:
                    with source.extractfile(member) as stream:
                        while True:
                            chunk = stream.read(1024 * 1024)
                            if not chunk:
                                break
                            hashed.update(chunk)
                            if destination:
                                destination.write(chunk)
                            if data is not None:
                                data.extend(chunk)
                            if migration_stream is not None:
                                migration_stream.write(chunk)
                            if protection_data is not None:
                                protection_data.extend(chunk)
                finally:
                    if destination:
                        destination.close()
                files[name] = ("file", hashed.hexdigest(), member.size)
                if data is not None:
                    prefs[name] = preference_summary(data)
                    if parts[-1] == "reader_cloud_v1.xml":
                        cloud_by_prefix[parts[:-2]] = cloud_preferences(data)
                if migration_stream is not None:
                    try:
                        migration_stream.seek(0)
                        read_migration_file(parts[-1], migration_stream, member.size,
                                            migration_by_prefix.setdefault(parts[:-3], migration_artifacts()))
                    finally:
                        migration_stream.close()
                if protection:
                    protected = protected_by_prefix.setdefault(parts[:-3], {"book": set(), "complete": True, "counts": {}, "bytes": 0})
                    protected["counts"][parts[-2]] = protected["counts"].get(parts[-2], 0) + 1
                    protected["bytes"] += member.size
                    try:
                        if protection_data is None or protected["counts"][parts[-2]] > 2000 or protected["bytes"] > 16_000_000:
                            raise ValueError()
                        value = json.loads(protection_data)
                        if parts[-2] == "multisource-v1":
                            identities = [value["book"]["origin"]]
                        else:
                            if value["version"] != 1:
                                raise ValueError()
                            identities = [item[0] for item in value["sources"]]
                            if value.get("cursor") is not None:
                                identities.append(value["cursor"])
                            if value.get("knownThrough") is not None:
                                identities.append(value["knownThrough"][2])
                        if not all(isinstance(identity, str) for identity in identities):
                            raise ValueError()
                        protected["book"].update(identities)
                    except (ValueError, TypeError, KeyError, IndexError):
                        protected["complete"] = False
        if len(database_prefixes) != 1:
            raise AuditError("PRIMARY_DATABASE_MISSING_OR_AMBIGUOUS")
        prefix = database_prefixes[0]
        def relative(name):
            parts = tuple(name.split("/"))
            return "/".join(parts[len(prefix):]) if parts[:len(prefix)] == prefix else "__archive_extra__/" + name
        state = database_audit(groups[prefix] / "legado.db", include_sources=include_migration)
        protection = protected_by_prefix.get(prefix, {"book": set(), "complete": True})
        state["_references"]["book"].update(protection["book"])
        state["_references"]["complete"] = protection["complete"]
        return {"database": state,
                "files": {relative(key): value for key, value in files.items()},
                "prefs": {relative(key): value for key, value in prefs.items()},
                "_migration": migration_by_prefix.get(prefix, migration_artifacts()), "_cloud": cloud_by_prefix.get(prefix)}


def expected_stub(table, original, record):
    preserved, defaults = (BOOK_PRESERVED, BOOK_DEFAULTS) if table == "book_sources" else (RSS_PRESERVED, RSS_DEFAULTS)
    known = preserved | set(defaults) | {"bookSourceComment" if table == "book_sources" else "sourceComment"}
    if set(original["raw"]) - known:
        return None
    expected = {column: original["raw"][column] for column in preserved & original["raw"].keys()}
    expected.update({column: digest(cell(value)) for column, value in defaults.items() if column in original["raw"]})
    archive = record["mode"] == "archive"
    updates = {"enabled": 0} if archive else {}
    comment = (ARCHIVE_PREFIX if archive else COMMENT_PREFIX) + record["sha256"]
    if table == "book_sources":
        updates.update(bookSourceComment=comment, exploreUrl=None if archive or not original["hasExplore"] else "全部::reader-cloud://category/all")
        if archive:
            updates["enabledExplore"] = 0
    else:
        web = record.get("webUrl")
        updates.update(sourceComment=comment, sortUrl=None if archive else web or "全部::reader-cloud://articles",
                       singleUrl=int(web is not None), ruleContent=None if web else MARKER)
    expected.update({column: digest(cell(value)) for column, value in updates.items() if column in original["raw"]})
    return expected


def migration_contract(before, after, evidence=None):
    left, right = before["database"], after["database"]
    artifacts = (evidence or after).get("_migration", migration_artifacts())
    owner = artifacts["owner"]
    candidates, failures = [], {}
    old_sources, new_sources = left.get("_sourceRows", {}), right.get("_sourceRows", {})
    def protected(kind, identity):
        first, second = left.get("_references", {}), right.get("_references", {})
        return not first.get("complete", False) or not second.get("complete", False) or identity in first.get(kind, set()) or identity in second.get(kind, set())
    changed = {(kind, identity) for table, kind in (("book_sources", "book"), ("rssSources", "rss"))
               for identity, row in old_sources.get(table, {}).items()
               if row["rowSha256"] != new_sources.get(table, {}).get(identity, {}).get("rowSha256")}
    def failed(code):
        failures[code] = failures.get(code, 0) + 1
    if not owner:
        failed("MISSING_COMPLETED_OWNER_RESULT")
    for operation in artifacts["operations"].values():
        backup, plan = operation.get("backup.jsonl"), operation.get("plan.jsonl")
        if backup is None or plan is None:
            continue
        errors = {}; fields = set(); active = archived = preserved = 0
        def reject(code, column=None):
            errors[code] = errors.get(code, 0) + 1
            if column:
                fields.add(column)
        expected_ids = {(kind, identity) for table, kind in (("book_sources", "book"), ("rssSources", "rss"))
                        for identity, row in old_sources.get(table, {}).items() if not row["remote"]}
        if set(backup) != expected_ids:
            reject("BACKUP_SOURCE_SET_MISMATCH")
        if not changed <= set(plan):
            reject("CHANGED_SOURCE_MISSING_FROM_PLAN")
        for key, record in plan.items():
            original = backup.get(key)
            if original is None or original["sha256"] != record["sha256"]:
                reject("PLAN_BACKUP_SHA_MISMATCH")
        for table, kind in (("book_sources", "book"), ("rssSources", "rss")):
            before_rows, after_rows = old_sources.get(table, {}), new_sources.get(table, {})
            if left["tables"].get(table, {}).get("schemaSha256") != right["tables"].get(table, {}).get("schemaSha256"):
                reject("SOURCE_SCHEMA_CHANGED")
            if before_rows.keys() != after_rows.keys():
                reject("SOURCE_PRIMARY_KEYS_CHANGED")
            for identity, first in before_rows.items():
                key = (kind, identity); second = after_rows.get(identity)
                if second is None:
                    continue
                native = backup.get(key)
                if native is not None:
                    if native["fields"].keys() - first["typed"].keys():
                        reject("UNKNOWN_NATIVE_SOURCE_FIELDS")
                    for column, expected in first["typed"].items():
                        if native["fields"].get(column, typed_source_hash(table, column, None)) != expected:
                            reject("NATIVE_BACKUP_DATABASE_BINDING_MISMATCH", column)
                record = plan.get(key)
                unchanged = first["rowSha256"] == second["rowSha256"]
                if record is None:
                    if not unchanged:
                        reject("UNPLANNED_SOURCE_CHANGED")
                    if not first["remote"]:
                        preserved += 1
                        if not protected(kind, identity):
                            reject("UNPROTECTED_SOURCE_LEFT_UNPLANNED")
                    continue
                # A new reference during upload may safely keep a planned
                # archive row byte-for-byte. An active plan must actually apply.
                if unchanged and record["mode"] == "archive":
                    preserved += 1
                    if not protected(kind, identity):
                        reject("UNPROTECTED_ARCHIVE_NOT_APPLIED")
                    continue
                references = right.get("_references", {})
                if record["mode"] == "archive" and (not references.get("complete", False) or identity in references.get(kind, set())):
                    reject("PROTECTED_SOURCE_ARCHIVED")
                expected = expected_stub(table, first, record)
                if expected is None:
                    reject("UNKNOWN_SOURCE_SCHEMA")
                else:
                    for column in set(first["raw"]) | set(second["raw"]) | set(expected):
                        if second["raw"].get(column) != expected.get(column):
                            reject("STUB_COLUMN_MISMATCH", column)
                if record["mode"] == "active":
                    active += 1
                else:
                    archived += 1
        counts = {"uploaded": len(backup), "converted": active, "archived": len(backup) - active,
                  "archiveCleaned": archived, "preserved": preserved,
                  "skipped": sum(len(rows) for rows in old_sources.values()) - len(backup)}
        if owner != counts or active == 0:
            reject("OWNER_RESULT_COUNTS_MISMATCH")
        if not errors:
            candidates.append({"counts": counts, "activeIds": {key[1] for key, value in plan.items() if key[0] == "book" and value["mode"] == "active"}})
        else:
            for code, amount in errors.items():
                failures[code] = failures.get(code, 0) + amount
            failures.setdefault("_fields", set()).update(fields)
    # Old interrupted plans may be incomplete; require one coherent completed
    # operation, never stitch unrelated plans together to hide a missing row.
    okay = bool(candidates) and owner is not None
    configuration = before.get("_cloud"), after.get("_cloud")
    config_ok = False
    if okay and all(isinstance(value, dict) for value in configuration):
        a, b = configuration
        revision = digest((str(b.get("url", "")) + "\x00" + str(b.get("token", ""))).encode("utf-8"))
        config_ok = bool(a.get("url") and a.get("token") and a.get("url") == b.get("url") and a.get("token") == b.get("token") and b.get("enabled") is True
                         and b.get("approved_revision") == revision and isinstance(b.get("approved_book"), set)
                         and any(item["activeIds"] <= b["approved_book"] for item in candidates))
    if not config_ok:
        failures["CLOUD_CONFIGURATION_NOT_CONFIRMED"] = 1
    result = {"okay": okay and config_ok, "matchingCompletedOperations": len(candidates), "cloudConfigurationVerified": config_ok,
              "originalShaEvidence": "Exact native backup JSON bytes; Room binding compares typed fields, without Python GSON reserialization",
              "interruptedOrInvalidArtifacts": artifacts["errors"], "usedExternalEvidenceSnapshot": evidence is not None}
    result["cleanupScope"] = "Migration backup/pending copies only; referenced original source rows may intentionally remain"
    if candidates:
        result["counts"] = candidates[0]["counts"]
    if not result["okay"]:
        if not candidates:
            failures["NO_COMPLETE_MATCHING_OPERATION"] = 1
        result["errors"] = {key: value for key, value in failures.items() if key != "_fields"}
        result["affectedColumns"] = sorted(failures.get("_fields", set()))
    copies = after.get("_migration", migration_artifacts())["counts"]
    result["localMigrationFiles"] = copies
    result["localOriginalRuleCopies"] = {"count": sum(copies[key]["count"] for key in ("backup.jsonl", "backup.pending")),
                                          "bytes": sum(copies[key]["bytes"] for key in ("backup.jsonl", "backup.pending"))}
    result["localOriginalRuleCopiesCleaned"] = not result["localOriginalRuleCopies"]["count"] and not copies["other"]["count"]
    result["localMigrationWorkFilesCleaned"] = result["localOriginalRuleCopiesCleaned"] and not copies["plan.jsonl"]["count"]
    return result


def compare(before, after, phase, evidence=None):
    left, right = before["database"], after["database"]
    tables = {}
    for name in sorted(set(left["tables"]) | set(right["tables"])):
        first, second = left["tables"].get(name), right["tables"].get(name)
        equal = first == second
        identities_equal = bool(first and second and first.get("identityColumns") == second.get("identityColumns") and
                                first.get("identitySha256") == second.get("identitySha256"))
        first_rows, second_rows = (first or {}).get("_rowHashes", {}), (second or {}).get("_rowHashes", {})
        tables[name] = {"beforeRows": first["rows"] if first else None, "afterRows": second["rows"] if second else None,
                        "equal": equal, "requiredEqual": phase == "installed" or name not in SOURCE_TABLES,
                        "identityEqual": identities_equal, "identityColumns": (first or second or {}).get("identityColumns", []),
                        "changedRows": sum(first_rows.get(key) != second_rows.get(key) for key in set(first_rows) | set(second_rows)),
                        "changedColumns": sorted(key for key in set((first or {}).get("columnSha256", {})) | set((second or {}).get("columnSha256", {}))
                                                 if (first or {}).get("columnSha256", {}).get(key) != (second or {}).get("columnSha256", {}).get(key)),
                        "beforeSha256": first["sha256"] if first else None, "afterSha256": second["sha256"] if second else None}
    business_equal = left["userVersion"] == right["userVersion"] and all(not value["requiredEqual"] or value["equal"] for value in tables.values())
    source_ids_equal = left["sourceIds"] == right["sourceIds"]
    migration = migration_contract(before, after, evidence) if phase == "migrated" else None
    differences = {"added": 0, "removed": 0, "changed": 0, "preferenceFilesChanged": 0, "preferenceKeysChanged": 0, "allowedCloudChanges": 0}
    groups = {}
    for name in sorted(set(before["files"]) | set(after["files"])):
        first, second = before["files"].get(name), after["files"].get(name)
        if first == second:
            continue
        if name in {"databases/legado.db", "databases/legado.db-wal", "databases/legado.db-shm", "databases/legado.db-journal"}:
            continue
        migration_name = name[len("files/reader-cloud-migration/"):]
        known_migration_file = name.startswith("files/reader-cloud-migration/") and (MIGRATION_FILE.fullmatch(migration_name) or migration_name in {"owner-result.json", "owner-compaction-result.json"})
        if name == "shared_prefs/reader_cloud_v1.xml" or (phase == "migrated" and known_migration_file):
            differences["allowedCloudChanges"] += 1; continue
        if name.startswith("shared_prefs/"):
            first_pref, second_pref = before["prefs"].get(name), after["prefs"].get(name)
            if first_pref is not None and second_pref is not None:
                if first_pref == second_pref:
                    continue
                differences["preferenceKeysChanged"] += sum(first_pref.get(key) != second_pref.get(key) for key in set(first_pref) | set(second_pref))
            differences["preferenceFilesChanged"] += 1
        change = "added" if first is None else "removed" if second is None else "changed"
        differences[change] += 1
        group = name.split("/", 1)[0]
        # Only fixed Android directory names enter the report, never book/cache filenames.
        group = group if group in {"files", "shared_prefs", "cache", "code_cache", "databases", "no_backup", "app_webview", "lib"} else "other"
        groups[group] = groups.get(group, 0) + 1
    other_equal = not any(differences[key] for key in ("added", "removed", "changed"))
    book_columns = (left["tables"].get("books") or {}).get("columnSha256", {})
    protected = sorted(column for column in book_columns if column.lower().startswith("dur") or
                       "pos" in column.lower() or column in {"bookUrl", "name", "author", "group", "customTag", "readConfig"})
    books = tables.get("books", {})
    reading_equal = bool(books.get("identityEqual") and protected and not (set(protected) & set(books.get("changedColumns", []))))
    type_before, type_after = left.get("_metadata", {}).get("books", {}), right.get("_metadata", {}).get("books", {})
    type_pairs = [(value, type_after[key]) for key, value in type_before.items() if key in type_after and value != type_after[key]]
    cookie_before = left.get("_metadata", {}).get("cookies", {})
    cookie_after = right.get("_metadata", {}).get("cookies", {})
    cookie_changes = {"addedItems": 0, "removedItems": 0, "updatedValues": 0, "orderOrWhitespaceOnlyRows": 0, "changedFamilies": {}}
    for key in set(cookie_before) | set(cookie_after):
        a, b = cookie_before.get(key, {}), cookie_after.get(key, {})
        if a == b:
            row_a = left["tables"].get("cookies", {}).get("_rowHashes", {}).get(key)
            row_b = right["tables"].get("cookies", {}).get("_rowHashes", {}).get(key)
            if row_a != row_b:
                cookie_changes["orderOrWhitespaceOnlyRows"] += 1
        for item in set(a) | set(b):
            if a.get(item) == b.get(item):
                continue
            change = "addedItems" if item not in a else "removedItems" if item not in b else "updatedValues"
            cookie_changes[change] += 1
            family = (b.get(item) or a[item])[1]
            cookie_changes["changedFamilies"][family] = cookie_changes["changedFamilies"].get(family, 0) + 1
    return {"format": "reader-device-data-audit-3", "phase": phase,
            "ok": business_equal and source_ids_equal and other_equal and (migration is None or migration["okay"]),
            "businessDataEqual": business_equal, "sourceIdsEqual": source_ids_equal, "otherPrivateDataEqual": other_equal,
            "integrity": {"before": left["integrity"], "after": right["integrity"]},
            "userVersion": {"before": left["userVersion"], "after": right["userVersion"]},
            "tables": tables, "sources": {"before": left["sources"], "after": right["sources"]},
            "booksReadingState": {"equal": reading_equal, "primaryKeyBound": True, "checkedColumns": protected,
                "onlyOtherRoutineMetadataChanged": bool(reading_equal and set(books.get("changedColumns", [])) <= {"lastCheckTime", "type"}
                    and all((a ^ b) & ~(8 | 16) == 0 for a, b in type_pairs)),
                "typeChanges": {"rows": len(type_pairs), "textBitAddedOnly": sum(a | 8 == b for a, b in type_pairs),
                                "otherBitsChanged": sum((a ^ b) & ~8 != 0 for a, b in type_pairs),
                                "updateErrorBitToggledOnly": sum((a ^ b) == 16 for a, b in type_pairs),
                                "mediaOrShelfBitsChanged": sum((a ^ b) & ~(8 | 16) != 0 for a, b in type_pairs)}},
            "cookieChanges": cookie_changes,
            "fileDifferences": differences, "fileDifferenceGroups": groups,
            **({"migrationContract": migration, "migrationSourceContractVerified": migration["okay"],
                "localOriginalRuleCleanupVerified": migration["okay"] and migration["localMigrationWorkFilesCleaned"]} if migration else {})}


def audit(before_tar, after_tar, phase, evidence_tar=None):
    return compare(archive_audit(before_tar, phase == "migrated"), archive_audit(after_tar, phase == "migrated"), phase,
                   archive_audit(evidence_tar, True) if evidence_tar is not None else None)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--before-tar", required=True, type=Path)
    parser.add_argument("--after-tar", required=True, type=Path)
    parser.add_argument("--phase", choices=("installed", "migrated"), required=True)
    parser.add_argument("--report", required=True, type=Path)
    parser.add_argument("--migration-evidence-tar", type=Path,
                        help="Private pre-cleanup post-migration snapshot retaining exact native backup/plan evidence")
    args = parser.parse_args()
    try:
        report = audit(args.before_tar, args.after_tar, args.phase, args.migration_evidence_tar)
    except Exception as error:
        report = {"format": "reader-device-data-audit-3", "phase": args.phase, "ok": False,
                  "errorCode": str(error) if isinstance(error, AuditError) else "READ_OR_COMPARE_FAILED",
                  "errorType": type(error).__name__}
    args.report.parent.mkdir(parents=True, exist_ok=True)
    descriptor = os.open(args.report, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    os.fchmod(descriptor, 0o600)
    with os.fdopen(descriptor, "w", encoding="utf-8") as stream:
        json.dump(report, stream, ensure_ascii=False, indent=2); stream.write("\n")
    print(json.dumps({key: report[key] for key in ("ok", "businessDataEqual", "sourceIdsEqual", "otherPrivateDataEqual", "migrationSourceContractVerified", "localOriginalRuleCleanupVerified", "errorCode") if key in report}))
    return 0 if report["ok"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
