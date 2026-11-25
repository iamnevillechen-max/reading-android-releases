#!/usr/bin/env python3
"""Preserve the installed reader's private files without uninstalling or clearing it.

Stops only io.multiread.reader.debug for a consistent snapshot. Output is private
and must remain outside public source/release folders. Requires authorized ADB.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import sqlite3
import subprocess
import tarfile
import tempfile

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument('--adb', required=True)
parser.add_argument('--serial', required=True)
parser.add_argument('--output', type=Path, required=True)
parser.add_argument('--already-stopped', action='store_true',
                    help='Use after stopping the reader through Appium; verify that it is no longer running')
parser.add_argument('--gzip', action='store_true', help='Compress the private snapshot for wireless transfer')
args = parser.parse_args()
package = 'io.multiread.reader.debug'
base = [args.adb, '-s', args.serial]
output = args.output.resolve()
project = Path(__file__).resolve().parents[1]
if output == project or project in output.parents:
    raise SystemExit('Private snapshots must be saved outside the public project')
if output.exists():
    raise SystemExit('Refusing to replace an existing private snapshot')
output.parent.mkdir(parents=True, exist_ok=True)
os.chmod(output.parent, 0o700)
if args.already_stopped:
    running = subprocess.run(base + ['shell', 'pidof', package], capture_output=True, text=True)
    if running.stdout.strip():
        raise SystemExit('The reader is still running; stop it before taking a consistent snapshot')
    if running.returncode not in (0, 1):
        raise SystemExit('Could not confirm that the reader is stopped')
else:
    subprocess.run(base + ['shell', 'am', 'force-stop', package], check=True, capture_output=True)
with output.open('xb') as stream:
    os.chmod(output, 0o600)
    result = subprocess.run(base + ['exec-out', 'run-as', package, 'tar', 'czf' if args.gzip else 'cf', '-',
                                   'databases', 'shared_prefs', 'files'], stdout=stream, stderr=subprocess.PIPE)
if result.returncode:
    raise SystemExit('Private snapshot failed; installed application data was not removed')
running = subprocess.run(base + ['shell', 'pidof', package], capture_output=True, text=True)
if running.returncode not in (0, 1) or running.stdout.strip():
    raise SystemExit('The reader resumed during the snapshot; keep this unverified copy and take a new consistent backup')
hash_value = hashlib.sha256()
with output.open('rb') as stream:
    for block in iter(lambda: stream.read(1024 * 1024), b''):
        hash_value.update(block)
with tempfile.TemporaryDirectory(prefix='reader-private-check-') as temporary:
    os.chmod(temporary, 0o700)
    directory = Path(temporary)
    with tarfile.open(output) as archive:
        for filename in ['databases/legado.db', 'databases/legado.db-wal', 'databases/legado.db-shm']:
            try:
                entry = archive.getmember(filename)
            except KeyError:
                continue
            if not entry.isfile():
                raise SystemExit('Invalid private database entry')
            target = directory / Path(filename).name
            with archive.extractfile(entry) as source, target.open('xb') as destination:
                for block in iter(lambda: source.read(1024 * 1024), b''):
                    destination.write(block)
            os.chmod(target, 0o600)
    database = sqlite3.connect('file:' + str(directory / 'legado.db') + '?mode=ro', uri=True)
    if database.execute('pragma integrity_check').fetchone()[0] != 'ok':
        raise SystemExit('Snapshot database integrity check failed; keep original data')
    tables = [row[0] for row in database.execute("select name from sqlite_master where type='table' and name not like 'sqlite_%' and name not like 'room_%'")]
    counts = {table: database.execute('select count(*) from "' + table.replace('"', '""') + '"').fetchone()[0] for table in tables}
    database.close()
info = subprocess.run(base + ['shell', 'dumpsys', 'package', package], check=True, capture_output=True, text=True).stdout
version = re.search(r'versionName=([^\s]+)', info)
report = {'package': package, 'version': version.group(1) if version else None,
          'bytes': output.stat().st_size, 'sha256': hash_value.hexdigest(), 'integrity': 'ok', 'counts': counts}
report_path = output.with_suffix('.json')
with report_path.open('x') as destination:
    os.chmod(report_path, 0o600)
    json.dump(report, destination, ensure_ascii=False, indent=2)
print(json.dumps({'backupComplete': True, 'version': report['version'], 'integrity': 'ok',
                  'bytes': report['bytes'], 'books': counts['books'], 'history': counts['readRecord'],
                  'bookSources': counts['book_sources'], 'rssSources': counts['rssSources']}))
