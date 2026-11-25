import iconv from 'iconv-lite';
import { evaluateTemplateValue, templateExpressions } from './templates';

export class RequestTemplateError extends Error {
  readonly status = 422;
  constructor(public code: string, message: string) { super(message); }
}
const invalid = (message: string): never => { throw new RequestTemplateError('UNSUPPORTED_URL', message); };
export type RequestAddress = { url: string; charset?: string; method: 'GET'|'POST'; body?: string; contentType?: string };

/** JSON's sole compatibility extension is trailing commas, never executable JSON5. */
function staticJson(raw: string): any {
  let output = '', quoted = false;
  for (let at = 0; at < raw.length; at++) {
    const char = raw[at];
    if (quoted && char === '\\') { output += char + (raw[++at] || ''); continue; }
    if (char === '"') quoted = !quoted;
    if (!quoted && char === ',') {
      let next = at + 1; while (next < raw.length && /\s/.test(raw[next])) next++;
      if (raw[next] === '}' || raw[next] === ']') continue;
    }
    output += char;
  }
  return JSON.parse(output);
}

export function requestAddress(template: string, key = '', page = 1, baseUrl = ''): RequestAddress {
  if (template.length > 65536) invalid('URL模板超出服務上限');
  let url = template, charset: string | undefined, bodyValue: any;
  let method: 'GET'|'POST' = 'GET';
  const options = /,\s*(\{[\s\S]*\})\s*$/.exec(url);
  if (options) {
    let config: any;
    try { config = staticJson(options[1]); } catch { return invalid('URL選項不是靜態JSON'); }
    if (!config || Array.isArray(config) || Object.keys(config).some(name => !['charset','method','body'].includes(name))) invalid('URL選項只支援靜態method、body及charset');
    const requested = String(config.method || 'GET').toUpperCase();
    if (requested !== 'GET' && requested !== 'POST') invalid('僅支援公開GET或POST請求');
    method = requested as 'GET'|'POST'; bodyValue = config.body;
    if (method === 'GET' && bodyValue != null) invalid('GET請求不可包含POST內容');
    if (config.charset != null && typeof config.charset !== 'string') throw new RequestTemplateError('UNSUPPORTED_CHARSET','來源字元編碼無效');
    charset = config.charset || undefined; url = url.slice(0, options.index);
  }
  if (charset && !/^(utf-?8|gbk|gb2312|gb18030|big5)$/i.test(charset)) throw new RequestTemplateError('UNSUPPORTED_CHARSET','來源URL字元編碼不受支援');
  const encode = (value: string, form = false) => {
    const encoded = !charset || /^utf-?8$/i.test(charset) ? encodeURIComponent(value)
      : [...iconv.encode(value, charset)].map(byte => (byte >= 48 && byte <= 57) || (byte >= 65 && byte <= 90) || (byte >= 97 && byte <= 122) || [45,46,95,126].includes(byte)
        ? String.fromCharCode(byte) : `%${byte.toString(16).toUpperCase().padStart(2,'0')}`).join('');
    return form ? encoded.replace(/[!'()~]/g, char => `%${char.charCodeAt(0).toString(16).toUpperCase()}`).replaceAll('%20','+') : encoded;
  };
  const render = (value: string, mode: 'url'|'raw'|'json' = 'raw'): string => {
    const chunks: string[] = []; let position = 0, length = 0;
    try {
      for (const item of templateExpressions(value)) {
        chunks.push(value.slice(position,item.start));
        const result = evaluateTemplateValue(item.expression,{key,page,baseUrl}).value;
        if (result != null && !['string','number','boolean'].includes(typeof result)) invalid('URL模板結果必須是純文字或數值');
        const text = result == null ? '' : String(result);
        // Bare-key substitution retains the existing delimiter-safe contract.
        // Explicit encoding expressions see the raw keyword and are not encoded twice.
        const output = mode === 'json' ? JSON.stringify(text).slice(1,-1)
          : mode === 'url' && item.expression === 'key' ? encode(text) : text;
        length += item.start-position+output.length;
        if (length > 65536) invalid('URL模板結果超出服務上限');
        chunks.push(output); position = item.end;
      }
    } catch (error) {
      if (error instanceof RequestTemplateError) throw error;
      return invalid('未支援URL模板運算');
    }
    if (length + value.length-position > 65536) invalid('URL模板結果超出服務上限');
    chunks.push(value.slice(position));
    return chunks.join('');
  };
  url = render(url,'url');
  if (/@js:|<js>/i.test(url)) invalid('未支援URL模板程式');
  // Encode literal Unicode in URL query values using the declared native charset.
  // Already encoded expressions keep their percent triplets unchanged.
  const question = url.indexOf('?');
  if (question >= 0) {
    const query = url.slice(question+1);
    const encoded = [...query].map(char => /[\x21-\x7e]/.test(char) ? char : encode(char)).join('');
    url = url.slice(0,question+1)+encoded;
  }
  let body: string | undefined, contentType: string | undefined;
  if (method === 'POST') {
    bodyValue ??= '';
    // A pure expression may produce a serialized JSON body. Parse it as data.
    const serializedExpression = typeof bodyValue === 'string' && /^\s*\{\{/.test(bodyValue);
    if (serializedExpression) bodyValue = render(bodyValue);
    const json = typeof bodyValue === 'object' || (typeof bodyValue === 'string' && /^\s*[\[{]/.test(bodyValue));
    if (json) {
      if (typeof bodyValue === 'string') {
        // Expressions inside JSON strings are escaped; expressions outside them
        // are not accepted as executable JSON values by this data-only parser.
        let quoted = false, position = 0, output = '';
        const expressions = serializedExpression ? [] : templateExpressions(bodyValue);
        for (const item of expressions) {
          const prefix = bodyValue.slice(position,item.start);
          for (let at = 0; at < prefix.length; at++) {
            if (quoted && prefix[at] === '\\') { at++; continue; }
            if (prefix[at] === '"') quoted = !quoted;
          }
          if (!quoted) invalid('JSON模板只可替換字串值');
          output += prefix+render(bodyValue.slice(item.start,item.end),'json'); position=item.end;
        }
        output += bodyValue.slice(position);
        try { body = JSON.stringify(staticJson(output)); } catch { return invalid('POST內容不是靜態JSON'); }
      } else {
        let entries = 0;
        const fields = (value: any, depth = 0): any => {
          if (++entries > 4096 || depth > 24) invalid('JSON內容超出服務上限');
          return typeof value === 'string' ? render(value) : Array.isArray(value) ? value.map(item => fields(item,depth+1))
            : value && typeof value === 'object' ? Object.fromEntries(Object.entries(value).map(([name,field]) => [name,fields(field,depth+1)])) : value;
        };
        body = JSON.stringify(fields(bodyValue));
      }
      contentType = 'application/json';
    } else {
      if (typeof bodyValue !== 'string') invalid('POST表單內容須是靜態文字');
      const component = (value: string) => {
        const literal=(text:string)=>/^(?:[a-z\d*_.-]|%[a-f\d]{2})*$/i.test(text)?text:encode(text,true);
        // Whole-body expressions have already run. Their output is data and
        // must never be parsed as another round of template instructions.
        if(serializedExpression)return charset?encode(value,true):literal(value);
        if(charset)return encode(render(value),true);
        try{
          const pieces:string[]=[];let position=0,length=0;
          for(const item of templateExpressions(value)){
            const evaluated=evaluateTemplateValue(item.expression,{key,page,baseUrl});
            if(evaluated.hasUriEncoding&&!evaluated.uriEncoded)invalid('表單混合URI運算需要獨立編碼相容，未送出請求');
            if(evaluated.value!=null&&!['string','number','boolean'].includes(typeof evaluated.value))invalid('URL模板結果必須是純文字或數值');
            const text=evaluated.value==null?'':String(evaluated.value);
            let encoded='';
            if(evaluated.uriEncoded){
              for(let at=0;at<text.length;){
                if(/^%[a-f\d]{2}/i.test(text.slice(at,at+3))){encoded+=text.slice(at,at+3);at+=3;}
                else{const char=String.fromCodePoint(text.codePointAt(at)!);encoded+=encode(char,true);at+=char.length;}
              }
            }else encoded=encode(text,true);
            const prefix=literal(value.slice(position,item.start));
            length+=prefix.length+encoded.length;
            if(length>65536)invalid('POST搜尋內容超出服務上限');
            pieces.push(prefix,encoded);position=item.end;
          }
          const suffix=literal(value.slice(position));
          if(length+suffix.length>65536)invalid('POST搜尋內容超出服務上限');
          pieces.push(suffix);return pieces.join('');
        }catch(error){if(error instanceof RequestTemplateError)throw error;return invalid('未支援URL模板運算');}
      };
      body = bodyValue.split('&').map((field: string) => {
        const at = field.indexOf('='); return at < 0 ? component(field) : component(field.slice(0,at))+'='+component(field.slice(at+1));
      }).join('&');
      contentType = 'application/x-www-form-urlencoded';
    }
    if (body && new TextEncoder().encode(body).length > 65536) invalid('POST搜尋內容超出服務上限');
  }
  return { url,charset,method,body,contentType };
}
