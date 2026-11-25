#!/usr/bin/env python3
"""Publish only verified 阅读 APK/corresponding source through the owner's existing gh login."""
from pathlib import Path
import argparse, hashlib, json, os, re, shutil, subprocess, zipfile
from source_package_policy import publishable

ROOT = Path(__file__).resolve().parents[1]
REPOSITORY = 'iamnevillechen-max/reading-android-releases'
CERTIFICATE = '2d4f471e4ac07c996f1943396c730de18afca91fc38945cdda44c8f2b9d48b73'

def run(arguments, **kwargs):
    return subprocess.run(arguments, check=True, text=True, capture_output=True, **kwargs)

def gh(*arguments):
    return run(['gh', *arguments]).stdout

def sha(path):
    value = hashlib.sha256()
    with path.open('rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''): value.update(block)
    return value.hexdigest()

def main():
    parser = argparse.ArgumentParser()
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument('--publish', action='store_true', help='Upload draft, verify remote asset size/digest, then publish latest')
    mode.add_argument('--stage-draft', action='store_true', help='Verify uploaded assets while keeping the release unpublished')
    args = parser.parse_args()
    delivery = json.loads((ROOT / 'dist/交付檔案核對.json').read_text())
    version = delivery['versionName']
    if not re.fullmatch(r'\d+\.\d+\.\d+', version): raise SystemExit('Publish a final numeric version, never a bootstrap')
    cloud_release = version in ('1.2.0', '1.2.1', '1.2.2', '1.2.3')
    if delivery['sourceWorkingTreeDirty']: raise SystemExit('Commit the tested source first')
    revision = run(['git', '-C', str(ROOT / 'upstream'), 'rev-parse', 'HEAD']).stdout.strip()
    if revision != delivery['sourceRevision']: raise SystemExit('Source revision differs from packaged APK/source record')
    if run(['git', '-C', str(ROOT / 'upstream'), 'status', '--porcelain']).stdout.strip(): raise SystemExit('Source changed after packaging')
    apk = ROOT / 'dist' / delivery['apk']['file']
    source = ROOT / 'dist' / delivery['source']['file']
    for path, record in [(apk, delivery['apk']), (source, delivery['source'])]:
        if path.stat().st_size != record['bytes'] or sha(path) != record['sha256']: raise SystemExit('Delivery file changed: ' + path.name)
    with zipfile.ZipFile(source) as archive:
        if archive.testzip() is not None: raise SystemExit('Source archive unreadable')
        for name in archive.namelist():
            if not publishable(name): raise SystemExit('Private/runtime file in source archive')
        if cloud_release:
            for required in ['multiread/server/LICENSE', 'multiread/server/worker/src/index.ts',
                             'multiread/server/worker/src/engine.ts', 'multiread/server/worker/package-lock.json']:
                if required not in archive.namelist(): raise SystemExit('Cloud corresponding source is incomplete')
    environment = os.environ.copy()
    environment.setdefault('JAVA_HOME', str(ROOT.parent / 'sightmate-android/.toolchain/jdk-17.0.21+9/Contents/Home'))
    signer = run([str(Path.home() / 'Library/Android/sdk/build-tools/35.0.0/apksigner'), 'verify', '--print-certs', str(apk)], env=environment).stdout
    if 'certificate SHA-256 digest: ' + CERTIFICATE not in signer: raise SystemExit('Installed update certificate mismatch')
    if gh('api', 'user', '--jq', '.login').strip() != REPOSITORY.split('/')[0]: raise SystemExit('Unexpected release account')
    tag = 'v' + version
    staging = ROOT / '.toolchain/releases' / tag
    staging.mkdir(parents=True, exist_ok=True)
    public_apk = staging / 'Reader.apk'
    if not public_apk.exists() or sha(public_apk) != sha(apk):
        shutil.copy2(apk, public_apk)
    public_source = staging / f'Reader-{version}-source.zip'
    if not public_source.exists() or sha(public_source) != sha(source):
        shutil.copy2(source, public_source)
    public_sources = None
    if delivery.get('bookSources'):
        record = delivery['bookSources']
        pack = ROOT / 'dist' / record['file']
        if pack.stat().st_size != record['bytes'] or sha(pack) != record['sha256']:
            raise SystemExit('Verified book source package changed')
        public_sources = staging / 'VerifiedBookSources.json'
        shutil.copy2(pack, public_sources)
    private_restore = None
    if version == '1.2.4' and delivery.get('originalSourceRestore'):
        record=delivery['originalSourceRestore']; restore=ROOT/'dist'/record['file']
        if restore.stat().st_size!=record['bytes'] or sha(restore)!=record['sha256']: raise SystemExit('Original restore asset changed')
        private_restore=staging/'OriginalSources.readerpack'; shutil.copy2(restore,private_restore)
    if version == '1.2.9':
        notes = ('修复听书锁屏、亮屏及阅读页重建时的空白画面崩溃与朗读接续问题。\n'
                 '原章只有付费预览，或中段夹有错误提示时，尝试从本地其他书源查找可正常阅读的同书同章。\n'
                 '在书源管理中可确认清理疑似失效的规则，也可恢复先前归档的规则。\n'
                 '优化大量发现分类和订阅的打开、滚动、筛选与切换。\n'
                 '在「我的」点「上传使用日志」可取得回执，方便维修时核对。')
    elif version == '1.2.8':
        notes = ('修复小说自动拼接遇到第一批来源不匹配就停住的问题，在限定时间内继续寻找同书同章正文。\n'
                 '阅读时收到章节与书源入口均永久移除的明确响应，先在本机备份规则，再移除该书源。\n'
                 '网络超时、临时错误、单章缺失、登录和付费提示均保留书源。')
    elif version == '1.2.7':
        notes = ('在线朗读提前备好下一段音频，遇到短暂的连接或服务器错误会重试同一段。\n'
                 '修复换章时下一章还在排版，排好后没有接着朗读的问题。\n'
                 '减少系统语音在段落读完后重复停止引擎造成的等待。')
    elif version == '1.2.6':
        notes = ('修复从书架打开旧收藏时，原书源失效或目录加载失败后停在空白章节的问题。\n'
                 '自动查找同书、同作者及对应章节的可用来源补回正文；保留收藏设置、同章进度、书签和原有缓存。\n'
                 '兼容旧收藏资料；只剩章名或错误提示时也会重新寻找正文。\n'
                 '更新提示直接说明修复内容，只显示下载和取消；下载时可取消或转为后台下载。')
    elif version == '1.2.5':
        notes = ('移除分類數量標題，取消11類限制，保留各來源原有的分類名稱。\n'
                 '發現與訂閱在前景分批載入分類，動態分類成功後補回列表，失敗不影響已載內容。\n'
                 '保留大量來源與分類的列表複用、閱讀位置及增量載入。\n'
                 '繼續使用本地解析，沿用原更新渠道與簽署；已導入的來源無須重匯。')
    elif version == '1.2.4':
        notes = ('恢復本地解析，移除閱讀伺服器入口、雲端同步及巡檢依賴。\n'
                 '分類在本機合併為最多11類，減少重複；大量來源採增量處理，保留已載結果與頁次。\n'
                 '提供完整原書源及訂閱還原包，一次網絡導入、分批保存，保留書架、進度、歷史及已有本機完整規則。\n'
                 '修復同ID導入與黑岩書名／作者解析，沿用相同簽署覆蓋升級。')
    elif version == '1.2.3':
        notes = ('完整目錄補回章節或改變順序時，按同章網址保留閱讀位置與書籤。\n'
                 '雲端正文逐章核對完整性、書籍身份及校驗值，保留原始快取與離線回退。\n'
                 '新增目錄及正文多頁解析、XPath與網址運算相容，延續書名及作者污染修復。\n'
                 '全部來源仍在逐批逆向與實站核驗；可用清單只接回已通過核驗的來源。\n'
                 '沿用現有更新入口、應用識別與簽署，覆蓋安裝保留資料。')
    elif version == '1.2.2':
        notes = ('修正黑岩发现书名和作者出现图片HTML碎片的问题。\n'
                 '修正云端已恢复的来源未能重新接回App的情况，新增经核验可用的书源与订阅。\n'
                 '已收藏书籍只修复明确解析错误的名称，保留阅读位置、分组、历史和原文缓存。\n'
                 '来源继续在服务器逐批恢复，无须手动重新导入；未核验来源仍保留存档。\n'
                 '沿用现有更新入口、应用识别与签署，覆盖安装保留资料。')
    elif cloud_release:
        notes = ('新增可连接本人 Cloudflare 服务的云端书源模式；迁移前保存原规则并核对存档与可读性。\n'
                 '发现与订阅可由服务器加载，相近分类合并为最多11个大类，保留各来源子分类的轮换。\n'
                 '保留原有多源拼接、书架、阅读进度与历史，以及原更新渠道和签署。\n'
                 '加快已存档书源的迁移核对，中断后重做时减少重复上传。\n'
                 '修正订阅返回误翻页、空首批停住、逾时空转与单源下一页失效。\n'
                 '维持撤下自制本机反馈表，保留原有关于、帮助与日志。')
    elif version == '1.1.5':
        notes = ('书架保留视觉封面，读屏不再多报装饰封面文字。\n'
                 '发现打开后自动整理分类，无需手动继续；已找到的分类与书籍逐步显示。\n'
                 '修复全部分类匹配和空首批停住的问题，失败来源会跳过并继续寻找。\n'
                 '分类继续载入时保留已显示的书籍及位置，减少无效翻页与隐藏页面加载。\n'
                 '沿用17个核验来源、现有更新渠道与签署，保留书架、进度、历史和旧规则。')
    elif version == '1.1.4':
        source_count = delivery.get('bookSources', {}).get('sources', 14)
        notes = ('发现与搜索的每本书、订阅的每篇文章合并为一个读屏焦点，保留点击及长按操作。\n'
                 '改善慢源等待、分批轮换及已加载结果的显示，减少反复加载。\n'
                 f'内置{source_count}个经实际解析核验的公开书源，只补入缺少标识，保留旧规则。\n'
                 '撤下自制的本机反馈表，恢复原有关于与帮助功能，旧反馈文件保留。\n'
                 '名称保持「阅读」，使用既有更新渠道与签署，保留书架、进度和历史。')
    elif version == '1.1.3':
        notes = ('修正空白原文缓存和失败的空白补章缓存阻止再次自动接续的问题。\n'
                 '自动接续会重新考虑后来找到的同书来源，并包含未分组的可用书源。\n'
                 '内置14个独立标识的公开书源，仅补入缺少的标识，保留原有同站规则。\n'
                 '沿用打开应用检查更新、App内下载与Android安装确认，以及本机问题反馈。\n'
                 '名称保持「阅读」，保留书架、进度、历史、旧书源和原始正文缓存。')
    elif version == '1.1.2':
        notes = ('修复原书源被移除、失效或长时间无回应时未自动寻找备用正文的问题。\n'
                 '原文不存在时，旧阅读位置不再阻止同书同章核对；失败后继续限量寻找可用来源。\n'
                 '优化发现与订阅的大量来源加载、分类复用和横向分类滚动。\n'
                 '打开应用静默检查新版；更新页重新检查最新版本，已下载的安装按钮明确版本。\n'
                 '提供13个经阅读原生核验、使用独立标识的书源，保留已有同站规则。\n'
                 '名称保持「阅读」，保留书架、进度、历史、旧书源及本机问题反馈。')
    else:
        notes = ('名称统一为「阅读」。\n修复自动拼接误截正文、规则更新缓存及备用正文离线重开。\n'
                 '搜索缺作者时先核对详情；保留书架、进度和历史。\n'
                 '新增经实际解析验证的书源包；综合站读取其公开免费章节，另含古籍来源。\n'
                 '保留横向分类、检查更新及私人问题反馈。')
    metadata = {'packageName': delivery['applicationId'], 'versionCode': delivery['versionCode'],
                'versionName': version, 'minSdk': 21, 'abi': 'universal',
                'apkUrl': f'https://github.com/{REPOSITORY}/releases/download/{tag}/Reader.apk',
                'bytes': public_apk.stat().st_size, 'sha256': sha(public_apk), 'notes': notes,
                'releasePageUrl': f'https://github.com/{REPOSITORY}/releases/tag/{tag}'}
    metadata_path = staging / 'update.json'
    metadata_path.write_text(json.dumps(metadata, ensure_ascii=False, indent=2) + '\n')
    body = staging / 'release-notes.md'
    feedback_note = ('本版撤下自制的本机反馈表，保留原有关于与帮助功能及既有反馈文件。'
                     if version in ('1.1.4', '1.1.5', '1.2.4', '1.2.5', '1.2.6', '1.2.7', '1.2.8') or cloud_release else '问题反馈保存于手机 App 内，可自行导出。')
    data_note = ('使用者主动连接并迁移时，原始书源与订阅规则会上传至本人配置的 Cloudflare 服务。'
                 '云端搜索和阅读会发送所需关键词、书籍或文章地址及解析上下文；书架分组、阅读进度与历史继续保留在本机。'
                 if cloud_release else '不自动上传书库、来源、正文或日志。')
    if version == '1.2.9':
        feedback_note = 'ChatGPT Dot 可以在[阅读项目反馈入口](https://reading-feedback.iamnevillechen.workers.dev/)提交问题。'
    body.write_text(notes + '\n\n应用识别与既有版本保持一致，安装更新会保留资料。' + feedback_note + data_note + '\n\n'
                    + f'对应源码版本：`{revision}`。完整 GPL 对应源码见附件，包含原 LICENSE 和作者声明。\n')
    if args.publish or args.stage_draft:
        query = subprocess.run(['gh', 'release', 'view', tag, '--repo', REPOSITORY, '--json', 'isDraft'], text=True, capture_output=True)
        if query.returncode:
            gh('release', 'create', tag, '--repo', REPOSITORY, '--draft', '--title', '阅读 ' + version, '--notes-file', str(body))
        elif not json.loads(query.stdout)['isDraft']:
            raise SystemExit('Release already public; inspect it rather than replacing installed binaries')
        assets = [public_apk, metadata_path, public_source]
        if public_sources is not None: assets.append(public_sources)
        if private_restore is not None: assets.append(private_restore)
        current = json.loads(gh('release', 'view', tag, '--repo', REPOSITORY, '--json', 'isDraft,assets'))
        if not current['isDraft']: raise SystemExit('Release became public before upload')
        existing = {item['name']: item for item in current['assets']}
        changed = [path for path in assets if path.name not in existing or
                   existing[path.name]['size'] != path.stat().st_size or
                   existing[path.name].get('digest') != 'sha256:' + sha(path)]
        if changed:
            gh('release', 'upload', tag, '--repo', REPOSITORY, '--clobber', *map(str, changed))
        # GitHub's by-tag REST endpoint omits unpublished drafts; CLI resolves the
        # owner's draft and preserves the ASCII asset names used for verification.
        response = json.loads(gh('release', 'view', tag, '--repo', REPOSITORY, '--json', 'isDraft,assets'))
        if not response['isDraft']: raise SystemExit('Release became public before verification')
        uploaded = {item['name']: item for item in response['assets']}
        if args.publish:
            unexpected = set(uploaded).difference(path.name for path in assets)
            if unexpected:
                raise SystemExit('Draft still contains assets outside the delivery: ' + ', '.join(sorted(unexpected)))
        for path in assets:
            item = uploaded.get(path.name)
            if item is None or item['size'] != path.stat().st_size: raise SystemExit('Remote asset size mismatch')
            if item.get('digest') and item['digest'] != 'sha256:' + sha(path): raise SystemExit('Remote asset digest mismatch')
        if args.publish:
            gh('release', 'edit', tag, '--repo', REPOSITORY, '--notes-file', str(body), '--draft=false', '--latest')
        print(json.dumps({'published': bool(args.publish), 'draftVerified': bool(args.stage_draft), 'tag': tag,
                          'assetsVerified': len(assets), 'release': metadata['releasePageUrl'], 'apkSha256': metadata['sha256']}, ensure_ascii=False))
    else:
        print(json.dumps({'prepared': True, 'tag': tag, 'metadata': str(metadata_path), 'apkSha256': metadata['sha256']}, ensure_ascii=False))

if __name__ == '__main__': main()
