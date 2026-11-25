import { readFile } from 'node:fs/promises';
import { createHash } from 'node:crypto';
import { performance } from 'node:perf_hooks';
import { createEngine } from '../src/engine';

// Opt-in network check. Uses only committed public samples, reports no bodies,
// cookies, device records or user reading titles. Run with an optional hostname.
const root = new URL('../../../', import.meta.url);
const sources = JSON.parse(await readFile(new URL('upstream/app/src/main/assets/reader-verified-sources.json',root),'utf8'));
const cases = [];
for (const file of ['verified-combined-source-cases.json','verified-v114-public-fiction-cases.json']) {
  const document=JSON.parse(await readFile(new URL(`tools/source-packs/${file}`,root),'utf8'));
  cases.push(...document.verification);
}
const engine=createEngine();
const selected=sources.filter((source:any)=>!process.argv[2] || source.bookSourceUrl.includes(process.argv[2]));
for (const source of selected) {
  const sample=cases.find(item=>item.sourceUrl===source.bookSourceUrl);
  if (!sample) { console.log(JSON.stringify({sourceId:source.bookSourceUrl,stage:'NO_COMMITTED_SAMPLE',passed:false})); continue; }
  const report:any={sourceId:source.bookSourceUrl,passed:false,timings:{}};
  let at=performance.now(),stage='search';
  try {
    const books=await engine.search(source,sample.keyword);
    report.timings.search=Math.round(performance.now()-at); report.searchCount=books.length;
    const target=sample.expectedBookUrl ? books.find(book=>book.bookUrl===sample.expectedBookUrl) : books.find(book=>book.name===sample.expectedBookName);
    if (!target) throw new Error('Public sample not returned');
    at=performance.now();stage='info'; const book=await engine.info(source,target);report.timings.info=Math.round(performance.now()-at);
    at=performance.now();stage='toc';const toc=await engine.toc(source,book);report.timings.toc=Math.round(performance.now()-at);report.chapterCount=toc.chapters.length;
    const chapter=sample.expectedContentChapterUrl ? toc.chapters.find(chapter=>chapter.url===sample.expectedContentChapterUrl)
      : sample.contentChapterTitlePrefix ? toc.chapters.find(chapter=>chapter.title.startsWith(sample.contentChapterTitlePrefix)) : toc.chapters[0];
    if (!chapter) throw new Error('Public sample chapter not returned');
    at=performance.now();stage='content'; const result=await engine.content(source,toc.book,chapter);report.timings.content=Math.round(performance.now()-at);
    report.characters=result.text.length;report.bodySha256=createHash('sha256').update(result.text.replace(/\s/g,'')).digest('hex');report.passed=result.text.length>=80;
  } catch(error) {report.stage=stage;report.error=(error as Error).message;}
  console.log(JSON.stringify(report));
}
