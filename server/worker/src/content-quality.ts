import policy from '../../shared/content-quality-v1.json';

/** Shared rule data also generates Android's migration guard; ordinary short prose stays readable. */
// Keep deployed Android policy snapshots immutable; the server may reject a
// newly identified prompt more strictly before the next client policy release.
export const CONTENT_QUALITY_VERSION=policy.version+'-server-continued-login-1';
const patterns=[...policy.patterns,
  '^(?:请|請)(?:您)?(?:先)?(?:登录|登入|注册|註冊)(?:后|後|以|来|來|再)?(?:继续|繼續)(?:查看|阅读|閱讀|觀看|访问|訪問|解锁|解鎖)',
].map(pattern=>new RegExp(pattern,'iu'));
function normalize(text: string): string {
  return text.normalize('NFKC').replace(/<\/?(?:p|div|br|h[1-6]|section)\b[^>]*>/gi,'\n')
    .replace(/<[^>]*>/g,'').replace(/&nbsp;|&#160;/gi,' ').replace(/[\u200B-\u200D\uFEFF]/g,'').trim();
}
function gateLine(line:string): boolean {
  const trimmed=line.trim().replace(/^[【\[（(]\s*/, '');
  if(patterns.some(pattern=>pattern.test(trimmed)))return true;
  // Some source wrappers put the heading and template on one line. Punctuation
  // and dialogue quotes stop this prefix, so normal story quotations remain prose.
  const heading=/^(?:第[^\s。！？!?“”"'「」\n]{1,24}[章回節节卷篇]|chapter\s+\d+)[^。！？!?“”"'「」\n]{0,100}?\s+/iu.exec(trimmed);
  if(heading) {
    const tail=trimmed.slice(heading[0].length);
    if(patterns.some(pattern=>pattern.test(tail)))return true;
    // A short title may itself contain spaces; evaluate each remaining word boundary.
    for(const match of trimmed.slice(heading[0].length).matchAll(/\s+/g)) {
      if(patterns.some(pattern=>pattern.test(tail.slice((match.index||0)+match[0].length))))return true;
    }
  }
  return false;
}
export function canReadContent(value:unknown):value is string {
  if(typeof value!=='string')return false;
  const text=normalize(value);
  if(!text)return false;
  if((text.match(/[\uE000-\uF8FF]/g)?.length||0)>Math.max(8,text.length*0.05))return false;
  const lines=text.split(/\r?\n/).map(line=>line.trim()).filter(Boolean);
  const count=text.replace(/\s/g,'').length;
  const examined=count<=policy.shortTemplateLimit?lines.slice(0,16):lines.slice(0,2);
  return !examined.some(line=>line.length<=policy.templateLineLimit && gateLine(line));
}
export function canMigrateContent(value:unknown):value is string {
  return canReadContent(value) && Array.from(normalize(value).replace(/\s/g,'')).length>=policy.migrationMinimum;
}
