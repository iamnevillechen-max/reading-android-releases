#!/usr/bin/env python3
"""Create an authenticated private source restore asset; never publish its key."""
import argparse,hashlib,json,subprocess,zipfile
from pathlib import Path
p=argparse.ArgumentParser();p.add_argument('--archive',type=Path,required=True);p.add_argument('--private-directory',type=Path,required=True);p.add_argument('--output',type=Path,required=True);p.add_argument('--version',required=True);a=p.parse_args()
root=Path(__file__).resolve().parents[1];private=a.private_directory.resolve();assert root not in private.parents and private!=root
private.mkdir(parents=True,exist_ok=True);a.output.parent.mkdir(parents=True,exist_ok=True)
counts={'book':0,'rss':0};ids=set();digest=hashlib.sha256();total_bytes=0
with a.archive.open('rb') as stream:
 for line in stream:
  digest.update(line);total_bytes+=len(line);row=json.loads(line);assert row['kind'] in counts
  assert hashlib.sha256(row['json'].encode()).hexdigest()==row['sha256'];key=(row['kind'],row['id']);assert key not in ids;ids.add(key);counts[row['kind']]+=1
manifest={'format':'reader-local-source-restore-1','total':len(ids),'books':counts['book'],'rss':counts['rss'],'archiveBytes':total_bytes,'archiveSha256':digest.hexdigest()}
plain=private/'original-sources-authenticated-payload.zip'
with zipfile.ZipFile(plain,'w',zipfile.ZIP_DEFLATED,compresslevel=6) as z:
 z.writestr('manifest.json',json.dumps(manifest,separators=(',',':'),ensure_ascii=False)+'\n');z.write(a.archive,'sources.jsonl')
assert plain.stat().st_size<=64*1024*1024
script=r'''const fs=require('node:fs'),crypto=require('node:crypto');
const [plainPath,outPath,deliveryPath,version,totalText]=process.argv.slice(1);
const key=crypto.randomBytes(32),iv=crypto.randomBytes(12),plain=fs.readFileSync(plainPath);
const header=Buffer.alloc(64);header.write('RDRPK001',0,'ascii');iv.copy(header,8);crypto.createHash('sha256').update(plain).digest().copy(header,20);header.writeBigInt64BE(BigInt(plain.length),52);header.writeInt32BE(Number(totalText),60);
const cipher=crypto.createCipheriv('aes-256-gcm',key,iv);cipher.setAAD(header);const encrypted=Buffer.concat([header,cipher.update(plain),cipher.final(),cipher.getAuthTag()]);fs.writeFileSync(outPath,encrypted);
const url='https://github.com/iamnevillechen-max/reading-android-releases/releases/download/v'+version+'/OriginalSources.readerpack#key='+key.toString('base64url');
fs.writeFileSync(deliveryPath,JSON.stringify({version,privateImportUrl:url,assetSha256:crypto.createHash('sha256').update(encrypted).digest('hex'),assetBytes:encrypted.length,plainZipSha256:crypto.createHash('sha256').update(plain).digest('hex')},null,2)+'\n',{mode:0o600});key.fill(0);
'''
delivery=private/'private-import-delivery.json';assert not delivery.exists(),'Never silently rotate an already delivered restore key'
subprocess.run(['node','-e',script,str(plain),str(a.output.resolve()),str(delivery),a.version,str(len(ids))],check=True)
x=json.loads(delivery.read_text());public={**manifest,'encryptedAssetBytes':x['assetBytes'],'encryptedAssetSha256':x['assetSha256'],'keyPublished':False,'sourceWebsiteProbes':0}
(private/'original-source-pack-integrity.json').write_text(json.dumps(public,ensure_ascii=False,indent=2)+'\n');print(json.dumps(public,ensure_ascii=False,indent=2))
