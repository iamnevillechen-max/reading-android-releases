"""Shared fail-closed filename policy for public corresponding-source archives."""
from __future__ import annotations

import json
import os
from pathlib import Path
from typing import Iterator

EXCLUDED_DIRECTORIES = frozenset({
    '.git', '.gradle', '.toolchain', '.wrangler', '.sites-runtime', '.vinext',
    'node_modules', '__pycache__', '.pytest_cache', '.mypy_cache', '.next',
    'build', 'dist', 'outputs', 'coverage', '閱讀升級備份', 'reader-cloud-migration',
})
PRIVATE_SUFFIXES = frozenset({'.db', '.sqlite', '.sqlite3', '.jks', '.keystore', '.p12', '.pfx', '.pem', '.key', '.token'})
PRIVATE_NAMES = frozenset({'tokens.json', 'token.json', 'credentials.json', 'credential.json', 'secrets.json',
    'secrets.toml', 'secrets.yaml', 'secrets.yml', '.ds_store'})


def excluded_name(name: str) -> bool:
    lower = name.lower()
    return (name in EXCLUDED_DIRECTORIES or lower in PRIVATE_NAMES
            or lower.startswith(('.env', '.dev.vars'))
            or lower.endswith(('.db-wal', '.db-shm', '.sqlite-wal', '.sqlite-shm', '.sqlite3-wal', '.sqlite3-shm', '.pyc', '.pyo'))
            or Path(lower).suffix in PRIVATE_SUFFIXES)


def publishable(relative: Path | str) -> bool:
    path = Path(relative)
    evidence = any(path.parts[index:index + 2] == ('docs', 'evidence')
                   for index in range(len(path.parts) - 1))
    # Build diagnostics may contain host paths; compact result JSON is the
    # public acceptance record. Keep the original diagnostics local.
    return not (any(excluded_name(part) for part in path.parts)
                or (evidence and path.name.endswith('-build.log')))


def public_source_bytes(relative: Path | str, data: bytes) -> bytes:
    """Preserve source bytes except for the owner's deployment identifiers.

    The archive retains a usable Wrangler configuration template under the
    original filename. The live configuration on disk is never changed.
    """
    if Path(relative).parts[-3:] not in {
        ('server', 'worker', 'wrangler.jsonc'), ('server', 'feedback', 'wrangler.jsonc')
    }:
        return data
    try:
        configuration = json.loads(data)
        configuration.pop('account_id', None)
        for database in configuration['d1_databases']:
            database['database_id'] = 'REPLACE_WITH_YOUR_D1_DATABASE_ID'
            database.pop('preview_database_id', None)
    except (ValueError, TypeError, KeyError, AttributeError) as error:
        raise ValueError('Cannot safely create the public Wrangler deployment template') from error
    return (json.dumps(configuration, ensure_ascii=False, indent=2) + '\n').encode('utf-8')


def iter_source_files(directory: Path, project: Path) -> Iterator[Path]:
    """Prune dependencies/runtime data before traversal instead of walking their files."""
    for root, directories, files in os.walk(directory, followlinks=False):
        directories[:] = sorted(name for name in directories if not excluded_name(name))
        for name in sorted(files):
            path = Path(root) / name
            if publishable(path.relative_to(project)) and path.is_file():
                yield path
