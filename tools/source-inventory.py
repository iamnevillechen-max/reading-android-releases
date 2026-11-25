#!/usr/bin/env python3
"""Inventory every archived source without executing its code or fetching websites.

All row identities, hosts and family examples are private and must be written
outside the public project. Static engine support is not website availability.
"""
from __future__ import annotations

import argparse
from collections import Counter, defaultdict
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import time
from urllib.parse import urlsplit

ROOT = Path(__file__).resolve().parents[1]
TYPE_NAMES = {0: 'text', 1: 'audio', 2: 'image', 3: 'download'}
SCRIPT_FIELDS = {'jsLib', 'loginCheckJs', 'coverDecodeJs', 'injectJs', 'shouldOverrideUrlLoading'}
CONTEXT_FIELDS = {'exploreUrl', 'searchUrl', 'sortUrl', 'header', 'loginUrl', 'loginUi'} | SCRIPT_FIELDS
URL_PATTERN = re.compile(r'https?://[^\s\"\'<>\\]+', re.I)
CALL_PATTERN = re.compile(r'\b(java|book|chapter|cookie|cache|source)\s*\.\s*([A-Za-z_$][\w$]*)\s*\(')
JS_LITERAL = re.compile(r'''(?s)'(?:\\.|[^'\\])*'|"(?:\\.|[^"\\])*"|`(?:\\.|[^`\\])*`|/\*.*?\*/|//[^\n]*|\b\d+(?:\.\d+)?\b''')
EXTRA_TEMPLATES = re.compile(r'\{\{(?!key\}\}|page\}\}|\(page-1\)\*20\}\})')


def sha(data):
    return hashlib.sha256(data if isinstance(data, bytes) else data.encode()).hexdigest()


def private_path(value):
    value = Path(value).resolve()
    if value == ROOT or ROOT in value.parents:
        raise ValueError('Private source inventory must remain outside the public project')
    return value


def leaves(value, prefix=''):
    if isinstance(value, str):
        yield prefix, value
    elif isinstance(value, dict):
        for key, data in value.items():
            yield from leaves(data, prefix + '.' + key if prefix else key)
    elif isinstance(value, list):
        for data in value:
            yield from leaves(data, prefix + '[]')


def source_values(source):
    for key, value in source.items():
        if key.startswith('rule') or key in CONTEXT_FIELDS:
            yield from leaves(value, key)


def host(value):
    try:
        parsed = urlsplit(str(value))
        return parsed.hostname.lower().rstrip('.').encode('idna').decode() if parsed.hostname else None
    except (ValueError, UnicodeError):
        return None


def static_public_identity(value):
    if not isinstance(value, str) or value != value.strip() or re.search(r'[\x00-\x1f\x7f]|@js:|<js>|\{\{', value, re.I):
        return False
    try:
        parsed = urlsplit(value)
        name = parsed.hostname or ''
        if parsed.scheme not in ('http', 'https') or parsed.username or parsed.password or parsed.port not in (None, 80, 443):
            return False
        if re.search(r'(?:^|\.)(?:localhost|local|internal|test|invalid|onion)$', name, re.I):
            return False
        if re.fullmatch(r'\d+(?:\.\d+){3}', name):
            import ipaddress
            return ipaddress.ip_address(name).is_global
        return bool(re.fullmatch(r'(?:[A-Za-z0-9](?:[A-Za-z0-9-]*[A-Za-z0-9])?\.)+[A-Za-z][A-Za-z0-9-]*', name))
    except (ValueError, TypeError):
        return False


def structural_string(value, script=False):
    # Planning fingerprints only: not proof of equivalent behaviour, and never
    # a reason to delete an alias or substitute one source's rule for another.
    if script or '@js:' in value or '<js>' in value:
        value = JS_LITERAL.sub(lambda m: '' if m[0].startswith(('/*', '//')) else 'S' if m[0][0] in "'\"`" else 'N', value)
    else:
        def url_token(match):
            try:
                parsed = urlsplit(match[0])
                normalized = match[0].replace(parsed.netloc, 'HOST', 1) if parsed.netloc else 'UNMAPPED_URL'
                return re.sub(r'(?<=/)[0-9]+(?=/|[?.#]|$)', 'N', normalized)
            except ValueError:
                return 'UNMAPPED_URL'
        value = URL_PATTERN.sub(url_token, value)
    return re.sub(r'\s+', ' ', value).strip()


def structure(source):
    pairs = []
    for path, value in source_values(source):
        key = path.split('.')[0]
        pairs.append((path, structural_string(value, key in SCRIPT_FIELDS)))
    return sha(json.dumps(pairs, ensure_ascii=True, sort_keys=True, separators=(',', ':')))


def requirements(source):
    values = list(source_values(source))
    text = '\n'.join(value for _, value in values)
    tags = set()
    calls = sorted({'.'.join(match) for match in CALL_PATTERN.findall(text)})
    if '@js:' in text or '<js>' in text or any(source.get(key) for key in SCRIPT_FIELDS): tags.add('javascript_runtime')
    if re.search(r'@xpath:|(?:^|[\n@])(?:/|\./)/', text, re.I): tags.add('xpath')
    if re.search(r'@put:|@get:', text, re.I): tags.add('rule_context_put_get')
    if re.search(r'@web|java\.(?:webView|startBrowser|startBrowserAwait)\s*\(', text, re.I): tags.add('browser_runtime')
    if '{{' in text: tags.add('url_template')
    if EXTRA_TEMPLATES.search(text): tags.add('template_expression')
    if re.search(r'[\"\']?method[\"\']?\s*:\s*[\"\']post', text, re.I) or 'java.post' in calls: tags.add('http_post')
    if re.search(r',\s*\{\s*[\"\'](?:method|body|headers|retry|webView|type)[\"\']', text): tags.add('request_options')
    if source.get('loginUrl') or source.get('loginUi') or source.get('loginCheckJs'): tags.add('session_or_login_configuration')
    if source.get('jsLib'): tags.add('custom_javascript_library')
    if re.search(r'\b(?:Packages\.|importClass\s*\(|importPackage\s*\(|javax\.|android\.|org\.jsoup|java\.(?:lang|util|io|net|security)\.)', text): tags.add('jvm_or_android_packages')
    if re.search(r'\b(?:eval|Function)\s*\(', text): tags.add('dynamic_code_generation')
    if any(re.search(r'\.(?:readFile|writeFile|deleteFile|unzip|zip|downloadFile|readTxtFile)\b', call) for call in calls): tags.add('filesystem_or_download_helper')
    if any(re.search(r'(?:aes|rsa|des|base64|md5|sha|encodeURI|decodeURI)', call, re.I) for call in calls): tags.add('encoding_or_crypto_helper')
    if any(call.startswith('java.') for call in calls): tags.add('rhino_java_helper')
    if any(call in {'java.ajax', 'java.ajaxAll', 'java.connect', 'java.post', 'java.getCookie', 'cookie.getCookie', 'cookie.removeCookie'} for call in calls): tags.add('network_or_cookie_helper')
    if any('##' in value for path, value in values if path.startswith('rule')): tags.add('regex_replacement')
    if any(re.search(r'@(?!js:|put:|get:|web|xpath:)', value, re.I) for path, value in values if path.startswith('rule')): tags.add('selector_chain')
    if any(re.search(r'\$\.|@json:|jsonpath', value, re.I) for path, value in values if path.startswith('rule')): tags.add('jsonpath')
    if re.search(r'(?:class|tag|id)\.[\w-]+\.[-\d]|:matches(?:Own)?\(|\[[^\]]+~=', text, re.I): tags.add('legacy_jsoup_selector')
    raw_header = source.get('header')
    if raw_header:
        try:
            headers = json.loads(raw_header) if isinstance(raw_header, str) else raw_header
            if not isinstance(headers, dict): raise ValueError()
            if any(str(key).lower() not in {'user-agent', 'accept', 'accept-language', 'referer', 'content-type'} for key in headers): tags.add('private_or_nonstandard_headers')
            if any(not isinstance(value, str) or '\n' in value or '\r' in value for value in headers.values()): tags.add('dynamic_headers')
        except (ValueError, TypeError):
            tags.add('dynamic_headers')
    return sorted(tags), calls


def checked(entry):
    if entry.get('kind') not in ('book', 'rss') or not isinstance(entry.get('id'), str):
        raise ValueError('Invalid archived envelope identity')
    raw = entry.get('json')
    if not isinstance(raw, str) or sha(raw) != entry.get('sha256'):
        raise ValueError('An exact archived SHA did not match; inventory stopped')
    source = json.loads(raw)
    if not isinstance(source, dict) or source.get('bookSourceUrl' if entry['kind'] == 'book' else 'sourceUrl') != entry['id']:
        raise ValueError('Archived source identity did not match its exact envelope')
    return source


def disposition(source_kind, source_type, tags, assessment, static_id):
    if source_kind == 'book' and source_type != 0:
        return 'needs_' + TYPE_NAMES.get(source_type, 'unknown_mode') + '_pipeline'
    if assessment.get('supported'): return 'static_engine_supported_awaiting_live_verification'
    if not static_id: return 'needs_identity_and_public_request_mapping'
    if set(tags) & {'session_or_login_configuration', 'private_or_nonstandard_headers', 'browser_runtime'}:
        return 'needs_session_or_browser_configuration_review'
    if set(tags) & {'javascript_runtime', 'rhino_java_helper', 'rule_context_put_get'}:
        return 'needs_reviewed_javascript_and_rhino_api_adapter'
    if 'xpath' in tags: return 'needs_xpath_and_selector_adapter'
    if set(tags) & {'http_post', 'template_expression', 'request_options', 'dynamic_headers'}:
        return 'needs_http_template_adapter'
    return 'needs_individual_rule_compatibility_review'


ASSESSOR = r'''import fs from 'node:fs';
import readline from 'node:readline';
import {pathToFileURL} from 'node:url';
import {createHash} from 'node:crypto';
const [input,output,enginePath]=process.argv.slice(2);
const {assessSource,isPublicHost}=await import(pathToFileURL(enginePath).href);
const target=fs.openSync(output,'wx',0o600);
let count=0;
try {
 for await(const line of readline.createInterface({input:fs.createReadStream(input),crlfDelay:Infinity})) {
  if(!line.trim())throw new Error('Empty archived envelope');
  const entry=JSON.parse(line), source=JSON.parse(entry.json);
  if(createHash('sha256').update(entry.json).digest('hex')!==entry.sha256)throw new Error('Archived SHA mismatch');
  const value=assessSource(entry.kind,source);
  let normalizedHost=null,staticPublicIdentity=false;
  try {
   const address=new URL(entry.id);normalizedHost=address.hostname.toLowerCase().replace(/\.$/,'');
   staticPublicIdentity=entry.id===entry.id.trim()&&!/[\u0000-\u001F\u007F]|@js:|<js>|\{\{/i.test(entry.id)&&
    ['http:','https:'].includes(address.protocol)&&!address.username&&!address.password&&
    (!address.port||['80','443'].includes(address.port))&&isPublicHost(address.hostname);
  }catch{}
  fs.writeSync(target,JSON.stringify({kind:entry.kind,id:entry.id,sha256:entry.sha256,supported:value.supported===true,
    reason:value.reason??null,capabilities:value.capabilities??[],normalizedHost,staticPublicIdentity})+'\n');count++;
 }
} finally {fs.closeSync(target);}
process.stdout.write(JSON.stringify({assessed:count,networkRequests:0,sourceScriptsExecuted:0})+'\n');
'''


def assess_engine(input_path, output_dir, node):
    runner = output_dir / 'offline-engine-assessment.mts'
    assessment = output_dir / 'engine-static-assessment.jsonl'
    with runner.open('x') as stream:
        os.chmod(runner, 0o600); stream.write(ASSESSOR)
    cli = ROOT / 'server/worker/node_modules/tsx/dist/cli.mjs'
    if not cli.is_file(): raise ValueError('Install the existing worker dependencies before static engine assessment')
    components = ['server/worker/src/engine.ts', 'server/worker/src/source-adapters.ts',
                  'server/catalogue/source-adapters.json', 'server/worker/src/compiled-sources.ts', 'server/worker/package-lock.json']
    before = {name: sha((ROOT / name).read_bytes()) for name in components}
    result = subprocess.run([node, str(cli), str(runner), str(input_path), str(assessment), str(ROOT / 'server/worker/src/engine.ts')],
                            check=True, capture_output=True, text=True, timeout=240)
    after = {name: sha((ROOT / name).read_bytes()) for name in components}
    dump_private(output_dir / 'engine-assessment-context.json', {'componentSha256Before': before,
        'componentSha256After': after, 'componentsStableDuringAssessment': before == after,
        'sourceScriptExecution': False, 'networkRequests': 0})
    return json.loads(result.stdout)


def dump_private(path, value):
    with path.open('x') as stream:
        os.chmod(path, 0o600); json.dump(value, stream, ensure_ascii=False, indent=2); stream.write('\n')


def inventory(input_path, output_dir, assessment_path=None):
    assessments = {}
    if assessment_path:
        with assessment_path.open() as stream:
            for line in stream:
                value = json.loads(line); key = value['kind'], value['id']
                if key in assessments: raise ValueError('Duplicate assessment identity')
                assessments[key] = value
    totals = Counter(); modes = Counter(); tags_count = Counter(); calls_count = Counter(); reasons = Counter(); routes = Counter()
    families = {}; websites = defaultdict(lambda: {'kinds': Counter(), 'modes': Counter(), 'families': set(), 'records': 0})
    modules = {key: {'records': 0, 'currentlyUnsupported': 0, 'lowerRiskCandidates': 0, 'modes': Counter()} for key in ('http_templates', 'javascript_rhino', 'selector_semantics')}
    source_file = output_dir / 'all-source-handling.jsonl'
    seen = set(); total_bytes = 0; digest = hashlib.sha256(); malformed_ids = 0; offset = 0
    with input_path.open('rb') as source_stream, source_file.open('x') as output:
        os.chmod(source_file, 0o600)
        for number, encoded in enumerate(source_stream, 1):
            digest.update(encoded); total_bytes += len(encoded)
            entry = json.loads(encoded); source = checked(entry); key = entry['kind'], entry['id']
            if key in seen: raise ValueError('Duplicate archived envelope identity')
            seen.add(key)
            assessment = assessments.get(key, {})
            if assessments and assessment.get('sha256') != entry['sha256']: raise ValueError('Assessment SHA differs from original')
            mode = source.get('bookSourceType', 0) if entry['kind'] == 'book' else 'website' if source.get('singleUrl') is True else 'article_feed'
            if isinstance(mode, bool) or not isinstance(mode, (int, str)): mode = 'unknown'
            mode_name = TYPE_NAMES.get(mode, 'unknown') if entry['kind'] == 'book' else mode
            requirements_list, calls = requirements(source)
            requirements_set = set(requirements_list)
            static_id = static_public_identity(entry['id'])
            if assessments:
                # The live engine's identity gate runs before mode, script,
                # template or header checks and uses WHATWG URL normalization.
                # Its result also handles IDN/percent-encoded hosts that urllib
                # alone would classify more strictly.
                static_id = assessment.get('staticPublicIdentity', assessment.get('reason') not in {'來源識別缺失', '來源識別不是可執行的靜態公開網址'})
            malformed_ids += not static_id
            domain = assessment.get('normalizedHost') or host(entry['id'])
            website = domain if static_id and domain else 'unmapped:' + sha(entry['id'])[:16]
            structural = structure(source)
            family = sha(json.dumps([entry['kind'], mode_name, website, structural], ensure_ascii=True, separators=(',', ':')))
            assignment = disposition(entry['kind'], mode, requirements_list, assessment, static_id)
            row = {'line': number, 'byteOffset': offset, 'envelopeBytes': len(encoded), 'kind': entry['kind'], 'id': entry['id'],
                   'originalSha256': entry['sha256'], 'name': source.get('bookSourceName', source.get('sourceName', '')),
                   'sourceType': mode, 'mode': mode_name, 'host': domain, 'staticPublicIdentity': static_id,
                   'websiteFamily': family, 'ruleStructure': structural, 'requirements': requirements_list, 'rhinoApiCalls': calls,
                   'engineStaticAssessment': assessment, 'handling': assignment, 'liveAvailabilityVerifiedByThisInventory': False}
            output.write(json.dumps(row, ensure_ascii=False, separators=(',', ':')) + '\n')
            offset += len(encoded); totals[entry['kind']] += 1; modes[mode_name] += 1
            tags_count.update(requirements_list); calls_count.update(calls); reasons[str(assessment.get('reason') or ('STATIC_SUPPORTED' if assessment.get('supported') else 'NOT_ASSESSED'))] += 1
            routes[assignment] += 1
            website_stats = websites[website]; website_stats['records'] += 1; website_stats['kinds'][entry['kind']] += 1; website_stats['modes'][mode_name] += 1; website_stats['families'].add(family)
            data = families.setdefault(family, {'familyId': family, 'websiteKey': website, 'kind': entry['kind'], 'mode': mode_name,
                'ruleStructure': structural, 'records': 0, 'requirements': Counter(), 'examples': []})
            data['records'] += 1; data['requirements'].update(requirements_list)
            if len(data['examples']) < 3: data['examples'].append({'line': number, 'id': entry['id'], 'originalSha256': entry['sha256']})
            module_tags = {
                'http_templates': {'http_post', 'request_options', 'template_expression', 'dynamic_headers'},
                'javascript_rhino': {'javascript_runtime', 'rhino_java_helper', 'rule_context_put_get'},
                'selector_semantics': {'xpath', 'selector_chain', 'legacy_jsoup_selector', 'jsonpath', 'regex_replacement'},
            }
            hard_boundary = {'session_or_login_configuration', 'private_or_nonstandard_headers', 'browser_runtime',
                             'jvm_or_android_packages', 'dynamic_code_generation', 'filesystem_or_download_helper'}
            for name, required in module_tags.items():
                if requirements_set & required:
                    item = modules[name]; item['records'] += 1; item['modes'][mode_name] += 1
                    item['currentlyUnsupported'] += assessment.get('supported') is False
                    item['lowerRiskCandidates'] += static_id and not requirements_set & hard_boundary
    if assessments and seen != assessments.keys(): raise ValueError('Assessment does not cover the exact complete source set')
    ordered_families = sorted(families.values(), key=lambda item: (-item['records'], item['familyId']))
    with (output_dir / 'website-rule-families.jsonl').open('x') as stream:
        os.chmod(output_dir / 'website-rule-families.jsonl', 0o600)
        for item in ordered_families:
            item['requirements'] = dict(item['requirements']); stream.write(json.dumps(item, ensure_ascii=False) + '\n')
    dump_private(output_dir / 'website-index.json', [dict(websiteKey=key, records=value['records'], kinds=dict(value['kinds']),
        modes=dict(value['modes']), ruleFamilies=len(value['families'])) for key, value in sorted(websites.items(), key=lambda item: (-item[1]['records'], item[0]))])
    summary = {'format': 'reader-complete-source-inventory-1', 'createdAtUnix': int(time.time()), 'complete': True,
        'originalArchiveSha256': digest.hexdigest(), 'originalBytes': total_bytes, 'exactOriginalEnvelopesVerified': len(seen),
        'recordsByKind': dict(totals), 'recordsByMode': dict(modes), 'allOriginalModesRetained': True,
        'staticPublicSourceIdentities': len(seen) - malformed_ids, 'nonStaticOrUnmappedIdentities': malformed_ids,
        'identityClassification': 'Existing engine first-match identity gate' if assessments else 'Strict offline urllib/hostname heuristic',
        'sourceHostnameCount': len({key for key in websites if not key.startswith('unmapped:')}),
        'websiteRuleFamilyCount': len(families), 'distinctRuleStructureCount': len({item['ruleStructure'] for item in families.values()}),
        'staticRequirementsSourceCounts': dict(tags_count.most_common()), 'rhinoApiSourceCounts': dict(calls_count.most_common()),
        'engineStaticReasons': dict(reasons.most_common()), 'handlingCounts': dict(routes.most_common()),
        'moduleCoverage': modules, 'topFamilySizes': [{'familyId': item['familyId'], 'records': item['records'], 'kind': item['kind'], 'mode': item['mode']} for item in ordered_families[:50]],
        'networkRequests': 0, 'untrustedSourceScriptsExecuted': 0, 'liveAvailabilityVerified': False,
        'interpretation': ['Static support or a missing feature is not evidence that a website is healthy or dead.',
            'Every original envelope has a handling row; audio, image and download modes are retained explicitly.',
            'Module counts overlap and are potential implementation coverage, never a claim of restored usable sources.',
            'Structural/website families are planning aids, not equivalence proof or permission to delete/replace original IDs.',
            'Session, browser, crypto, JVM and filesystem requirements remain explicit boundaries for independent review.']}
    dump_private(output_dir / 'complete-inventory-summary.json', summary)
    write_module_plan(source_file, output_dir, modules)
    return summary


def write_module_plan(source_file, output_dir, modules):
    definitions = {
        'selector_semantics': ('Legado宣告式解析：節點、屬性、XPath與替換順序',
            {'xpath', 'selector_chain', 'legacy_jsoup_selector', 'jsonpath', 'regex_replacement'},
            ['重現鏈式取值、@text/@html/@alt與##替換的順序，不能把HTML屬性當書名。',
             '以自寫HTML/JSON/XML比對原生解析，再按網站家族正常核驗搜尋、詳情、目錄及各模式內容。']),
        'http_templates': ('公開請求描述器：GET/POST、body、編碼與分頁模板',
            {'http_post', 'request_options', 'template_expression', 'dynamic_headers'},
            ['把URL選項、POST body、charset及分頁運算解析為有界請求描述，不能只支援三種替換字串。',
             '保留公開主機、重導向、逾時、大小及請求數限制；私人Cookie/登入配置另行審閱。']),
        'javascript_rhino': ('受限JavaScript/Rhino上下文及常見java helper',
            {'javascript_runtime', 'rhino_java_helper', 'rule_context_put_get'},
            ['先對規則做離線語法/API盤點，按家族提供java.ajax/getString/getElements/put/get等受限配接。',
             '共用API配接不等於任意JS/Java可直接執行；JVM、檔案、瀏覽器及動態程式另有明確去向。']),
    }
    hard = {'session_or_login_configuration', 'private_or_nonstandard_headers', 'browser_runtime',
            'jvm_or_android_packages', 'dynamic_code_generation', 'filesystem_or_download_helper'}
    detail = {key: {'families': set(), 'lowerRiskCurrentlyUnsupported': 0} for key in definitions}
    login = Counter()
    mode_totals = Counter()
    with source_file.open() as stream:
        for encoded in stream:
            row = json.loads(encoded); flags = set(row['requirements']); assessed = row['engineStaticAssessment']
            mode_totals[row['mode']] += 1
            for key, (_, required, _) in definitions.items():
                if flags & required:
                    detail[key]['families'].add(row['websiteFamily'])
                    detail[key]['lowerRiskCurrentlyUnsupported'] += row['staticPublicIdentity'] and not flags & hard and assessed.get('supported') is False
            if assessed.get('reason') == '登入、注入或自訂程式需獨立維護':
                login['currentReasonCount'] += 1
                executable = {'javascript_runtime', 'rhino_java_helper', 'rule_context_put_get', 'browser_runtime',
                              'jvm_or_android_packages', 'dynamic_code_generation', 'filesystem_or_download_helper'}
                if not flags & executable:
                    login['noDeclaredExecutableHookOrJavaReference'] += 1
                    if not flags & {'private_or_nonstandard_headers', 'dynamic_headers', 'http_post', 'template_expression', 'request_options', 'xpath'}:
                        login['simpleAnonymousDeclarativePilotCandidates'] += 1
    ranked = []
    for key in sorted(definitions, key=lambda name: -modules[name]['lowerRiskCandidates']):
        title, _, steps = definitions[key]
        ranked.append({'module': key, 'title': title, 'sourceRowsUsingThisSharedLayer': modules[key]['records'],
            'websiteRuleFamiliesUsingLayer': len(detail[key]['families']),
            'lowerRiskCandidatesBeforeLiveVerification': modules[key]['lowerRiskCandidates'],
            'lowerRiskCurrentlyUnsupported': detail[key]['lowerRiskCurrentlyUnsupported'],
            'modesIncluded': dict(modules[key]['modes']), 'implementationSteps': steps,
            'restoredUsableSourcesProvenByInventory': 0})
    dump_private(output_dir / 'reverse-module-priorities.json', {
        'format': 'reader-offline-reverse-module-plan-1', 'rankedBy': 'Coverage without declared session/browser/JVM/file/dynamic-code boundary',
        'modules': ranked, 'optionalLoginConfigurationReview': dict(login),
        'optionalLoginInterpretation': 'Presence of loginUrl/UI is not proof that public content requires login. Probe normal anonymous public paths independently; do not call every such source broken or gated.',
        'requiredModePipelines': {**{mode: mode_totals[mode] for mode in ('text', 'audio', 'image', 'download')},
                                'note': 'These are original inventory totals. Shared parser/HTTP/JS work does not by itself implement audio, image or download result delivery.'},
        'coverageCountsOverlap': True, 'networkRequests': 0, 'untrustedSourceScriptsExecuted': 0,
        'completeWebsiteAvailabilityClaimed': False})


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--input', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--assess-engine', action='store_true')
    parser.add_argument('--node', default=shutil.which('node'))
    args = parser.parse_args()
    source, target = private_path(args.input), private_path(args.output)
    if not source.is_file(): parser.error('The complete original JSONL archive is required')
    if target.exists() and any(target.iterdir()): parser.error('Use a new private output directory; existing evidence is not replaced')
    target.mkdir(mode=0o700, parents=True, exist_ok=True); os.chmod(target, 0o700)
    assessment = None
    if args.assess_engine:
        if not args.node: parser.error('A local Node runtime is required for static engine assessment')
        assessed = assess_engine(source, target, args.node)
        assessment = target / 'engine-static-assessment.jsonl'
        print(json.dumps(assessed), flush=True)
    result = inventory(source, target, assessment)
    print(json.dumps({key: result[key] for key in ('complete', 'exactOriginalEnvelopesVerified', 'recordsByKind', 'recordsByMode',
        'websiteRuleFamilyCount', 'networkRequests', 'untrustedSourceScriptsExecuted')}, ensure_ascii=False), flush=True)


if __name__ == '__main__':
    main()
