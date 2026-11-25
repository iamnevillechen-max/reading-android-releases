#!/usr/bin/env python3
"""Package the tested development APK and complete GPL corresponding source, excluding caches/secrets."""
from pathlib import Path
import argparse, hashlib, json, re, shutil, subprocess, zipfile
from source_package_policy import iter_source_files, public_source_bytes, publishable

parser = argparse.ArgumentParser()
parser.add_argument('--preserve-apk', action='store_true', help='Refresh corresponding source/evidence while retaining the exact tested APK')
parser.add_argument('--apk-dir', type=Path, help='Directory containing the signed APK and its Gradle output-metadata.json')
args = parser.parse_args()

project = Path(__file__).resolve().parents[1]
repo = project / 'upstream'
out = project / 'dist'
out.mkdir(exist_ok=True)
apk_dir = args.apk_dir or repo / 'app/build/outputs/apk/app/debug'
metadata = json.loads((apk_dir / 'output-metadata.json').read_text())
assert metadata['applicationId'] == 'io.multiread.reader.debug'
assert len(metadata['elements']) == 1
element = metadata['elements'][0]
release = re.match(r'\d+\.\d+\.\d+', element['versionName']).group()
apk = apk_dir / element['outputFile']
if not apk.is_file():
    raise SystemExit('Build the APK first with tools/build.sh')
sha = lambda p: hashlib.sha256(p.read_bytes()).hexdigest()
apk_output = out / f'阅读_{release}.apk'
if args.preserve_apk:
    prior = json.loads((out / '交付檔案核對.json').read_text())
    revision_now = subprocess.check_output(['git', '-C', str(repo), 'rev-parse', 'HEAD']).decode().strip()
    if prior['sourceRevision'] != revision_now or prior['versionName'] != element['versionName'] or prior['versionCode'] != element['versionCode']:
        raise SystemExit('Pinned APK does not match current source revision/version')
    if apk_output.stat().st_size != prior['apk']['bytes'] or sha(apk_output) != prior['apk']['sha256']:
        raise SystemExit('The previously verified APK changed')
else:
    shutil.copy2(apk, apk_output)
names = subprocess.check_output(['git', '-C', str(repo), 'ls-files', '--cached', '--others', '--exclude-standard', '-z']).decode().split('\0')
source_files = {repo / name for name in names if name and (repo / name).is_file()}
source_files.update(p for folder in ['tools', 'docs/source', 'server']
                    for p in iter_source_files(project / folder, project))
source_files.update(p for p in [project / 'README.md', project / 'AGENTS.md'] if p.is_file())
for pattern in ['閱讀多書源拼接版_開發總表_v*.md', 'source-pack-*.md', 'source-pack-*.json', 'fiction*.md', 'fiction*.json']:
    source_files.update(p for p in (project / 'docs').glob(pattern) if p.is_file())
# Keep final acceptance evidence; local JVM diagnostics and superseded build attempts remain local.
source_files.update(p for p in (project / 'docs/evidence').rglob('*') if p.is_file() and (
    p.suffix in ['.json', '.xml', '.png'] or p.name in ['core-jvm-tests.txt', 'apk-signature.txt', 'streaming-hash-32m.txt', 'position-android-clean-install.log', 'position-reader-shengjing-tts.log', 'position-restart-prepare.log', 'position-restart-verify.log', 'final-android-regression.log', 'final-release-shengjing-tts.log', 'reader-1.1.0-android-regression.log', 'reader-1.1.0-shengjing-tts.log'] or
    bool(re.fullmatch(r'reader-\d+\.\d+\.\d+-.*\.log', p.name))))
source_files = sorted((p for p in source_files if publishable(p.relative_to(project))),
                      key=lambda p: p.relative_to(project).as_posix())
for path in source_files:
    if path.suffix.lower() in {'.jks', '.keystore', '.p12', '.pfx'}:
        raise SystemExit('Signing material must not be packaged: ' + str(path.relative_to(project)))
fingerprint = hashlib.sha256()
zip_output = out / f'阅读_{release}_完整源码.zip'
with zipfile.ZipFile(zip_output, 'w', zipfile.ZIP_DEFLATED, compresslevel=6) as z:
    for path in source_files:
        relative = path.relative_to(project).as_posix()
        if path.is_symlink():
            raise SystemExit('Inspect source symlink before packaging: ' + relative)
        data = public_source_bytes(relative, path.read_bytes())
        fingerprint.update(relative.encode() + b'\0' + data + b'\0')
        entry = zipfile.ZipInfo.from_file(path, 'multiread/' + relative)
        z.writestr(entry, data, compress_type=zipfile.ZIP_DEFLATED, compresslevel=6)
with zipfile.ZipFile(zip_output) as z:
    assert z.testzip() is None
    assert 'multiread/upstream/LICENSE' in z.namelist()
    assert 'multiread/upstream/app/src/main/java/io/legado/app/model/multisource/MultiSourceManager.kt' in z.namelist()
    if (project / 'server/feedback/worker.mjs').is_file():
        for name in ('worker.mjs', 'schema.sql', 'wrangler.jsonc', 'worker.test.mjs', 'README.md'):
            assert 'multiread/server/feedback/' + name in z.namelist(), name
    if (project / 'server/worker/src/index.ts').is_file():
        for required in ['server/LICENSE', 'server/worker/src/index.ts', 'server/worker/src/engine.ts',
                         'server/worker/package-lock.json', 'server/worker/migrations/0001_sources.sql',
                         'server/worker/migrations/0002_category_overrides.sql', 'server/worker/migrations/0003_maintenance.sql',
                         'server/worker/src/maintenance.ts','server/catalogue/verification-cases.json',
                         'server/shared/content-quality-v1.json','server/catalogue/taxonomy.json']:
            assert 'multiread/' + required in z.namelist(), required
    assert all(publishable(Path(name).relative_to('multiread')) for name in z.namelist())
revision = subprocess.check_output(['git', '-C', str(repo), 'rev-parse', 'HEAD']).decode().strip()
evidence_path = project / f'docs/evidence/reader-{release}-summary.json'
evidence = json.loads(evidence_path.read_text()) if evidence_path.is_file() else {}
handset_verified = bool(evidence.get('handset', {}).get('finalVersionInstalled') and
                        evidence.get('handset', {}).get('dataPreservationVerified'))
handset_note = ('handset installation not requested for this release'
                if evidence.get('handset', {}).get('installationRequested') is False
                else 'handset installation and data preservation verified' if handset_verified
                else 'handset installation/data verification pending')
manifest = {
    'applicationId': metadata['applicationId'], 'versionName': element['versionName'], 'versionCode': element['versionCode'],
    'type': f'tested release; verification in docs/evidence/reader-{release}-summary.json; ' +
            handset_note,
    'upstreamRevision': 'da17bb2bed44f30b12a524c2457e32a20b16fa41',
    'sourceRevision': revision,
    'sourceWorkingTreeDirty': bool(subprocess.check_output(['git', '-C', str(repo), 'status', '--porcelain']).strip()),
    'sourceTreeSha256': fingerprint.hexdigest(), 'sourceFiles': len(source_files),
    'apk': {'file': apk_output.name, 'bytes': apk_output.stat().st_size, 'sha256': sha(apk_output)},
    'source': {'file': zip_output.name, 'bytes': zip_output.stat().st_size, 'sha256': sha(zip_output)},
    'build': ':app:assembleAppDebug :app:testAppDebugUnitTest :app:assembleAppDebugAndroidTest',
    'license': 'GNU GPL v3; see corresponding source upstream/LICENSE and server/LICENSE'
}
if evidence.get('productionRevision') and evidence['productionRevision'] != revision:
    changed_paths = subprocess.check_output([
        'git', '-C', str(repo), 'diff', '--name-only', evidence['productionRevision'], revision
    ], text=True).splitlines()
    if not changed_paths or any(not name.startswith('app/src/androidTest/') for name in changed_paths):
        raise SystemExit('The retained APK and corresponding source differ outside instrumentation tests')
    if evidence['apkSha256'] != manifest['apk']['sha256']:
        raise SystemExit('The retained APK differs from the tested binary')
    manifest['apkBuildRevision'] = evidence['productionRevision']
source_packs = sorted(out.glob('阅读_新增书源_*.json'))
if source_packs:
    pack = source_packs[-1]
    manifest['bookSources'] = {'file': pack.name, 'bytes': pack.stat().st_size,
                              'sha256': sha(pack), 'sources': len(json.loads(pack.read_text()))}
restore_packs = sorted(out.glob('阅读_全部原来源_*.readerpack'))
if release == '1.2.4' and restore_packs:
    restore = restore_packs[-1]
    manifest['originalSourceRestore'] = {'file':restore.name,'bytes':restore.stat().st_size,
        'sha256':sha(restore),'bookSources':20706,'rssSources':443,'records':21149,'encrypted':True,'keyPublished':False}
(out / '交付檔案核對.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2))
print(json.dumps(manifest, ensure_ascii=False, indent=2))
