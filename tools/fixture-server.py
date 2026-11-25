#!/usr/bin/env python3
"""Loopback-only synthetic fixtures for the dedicated Android UI test installation.

No external requests, application changes, or automatic imports. Start explicitly;
Android Emulator reaches the host loopback server through 10.0.2.2.
"""
import argparse
from collections import Counter
from html import escape
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import re
from threading import Lock
from urllib.parse import parse_qs, urlsplit

BOOK_NAME = "河谷來信・本機驗收"
AUTHOR = "測試寫手甲"
CHAPTER_TITLES = ["第一章 碼頭", "第二章 霧河", "第三章 老樹", "第四章 回信"]
# Purpose-written fiction. No text, source, or feed is fetched from another service.
CHAPTERS = [
    [
        "清晨的小鎮還沒有醒來，阿青背上竹籃，沿著石板路慢慢走向河邊。",
        "船夫早已解開纜繩，木船隨著水波輕輕搖晃，遠處傳來清脆的鐘聲。",
        "她將那封封好的信藏進衣袋，確認藍色的火漆仍然完整，才踏上船板。",
        "河上的白霧逐漸散去，一群灰色水鳥從蘆葦深處飛起，掠過平靜水面。",
        "老船夫指著東面的山口，說午時之前必須到達對岸，否則就要等待明日。",
        "阿青沒有回答，只把籃子放在腳旁，取出母親留下的地圖仔細看了一遍。",
        "地圖上的紅線繞過一座廢棄磨坊，最後停在山腳那棵分成三叉的老樹旁。",
        "忽然有個戴草帽的少年從岸邊跑來，他揮著一條白手巾，大聲呼喊船夫。",
        "「前面的水流十分湍急，兩位請坐穩。」船夫收起長篙，讓少年跳上木船。",
        "日光終於照亮河谷，阿青望著漸漸遠去的小鎮，握緊衣袋裡那封重要的信。",
    ],
    [
        "木船離開碼頭之後，兩岸的屋頂漸漸藏進霧裡，只剩教堂的尖塔還看得見。",
        "少年自稱小梁，他帶來一袋烘好的麵包，並把最大的一塊分給了阿青。",
        "船夫將長篙伸進水中試探深淺，每當河床變得平坦，他便輕輕敲一下船舷。",
        "「信裡畫著一枚銀色的葉子。」阿青低聲說，卻沒有把藏在衣袋裡的信拿出來。",
        "小梁聽到這句話，立刻想起山腳老樹上的刻痕，那些刻痕恰好也是葉子的形狀。",
        "一陣風穿過河谷，水面上的霧忽然分開，露出對岸一條狹窄而明亮的石階。",
        "老船夫收起掛在船頭的銅鈴，告訴兩人那條石階已經多年沒有旅客走過了。",
        "阿青把地圖攤在膝上，沿著紅線數了三個轉彎，終於找到河灣旁的小小標記。",
        "「我們應該在那裡上岸。」她指著一塊平整的岩石，語氣比出發時堅定了許多。",
        "木船緩緩靠近河岸，小梁先跳上岩石繫住纜繩，然後回身接過沉重的竹籃。",
    ],
    [
        "午後的山腳安靜得出奇，老樹的枝條向三個方向伸展，地面落滿細碎的樹影。",
        "阿青繞著樹幹走了一圈，在朝向河面的地方找到一塊可以輕輕推開的木板。",
        "木板後面沒有寶物，只有一本用棉線縫好的薄冊子，以及一支已經乾涸的筆。",
        "小梁坐在石階上慢慢翻閱冊子，發現每一頁都記著曾經經過河谷的旅人姓名。",
        "阿青在最後一頁看見母親熟悉的筆跡，旁邊還畫著一枚小小的銀色葉子。",
        "她終於打開那封信，紙上沒有艱深的謎語，只有母親年輕時寫給朋友的一段問候。",
        "「原來這條路一直有人記得。」小梁抬頭望向樹梢，陽光從葉縫間灑在他的臉上。",
        "阿青把信放回冊子中，另取一張乾淨的紙，仔細寫下自己今天來到這裡的日期。",
        "山風吹動攤開的書頁，兩人一起用石子壓住四角，直到墨水完全乾透才收起紙張。",
        "離開老樹之前，他們把木板輕輕推回原處，沒有帶走任何屬於其他旅人的東西。",
    ],
    [
        "傍晚的河水映著溫暖的金光，老船夫依照約定等在岩石旁，船頭的小燈已經點亮。",
        "阿青將寫好的回信交給船夫，請他下次經過上游村莊時，替自己送到母親手中。",
        "船夫把信收進乾燥的木匣，笑著說這條河上的每封信，都有它應該到達的地方。",
        "小梁拿出剩下的麵包，三個人坐在船尾分著吃完，誰也沒有急著打破安靜的時刻。",
        "木船經過廢棄磨坊時，阿青看見窗臺上長著一叢小花，顏色像清晨尚未散去的霧。",
        "她在地圖背面畫下那叢花，準備回到小鎮後，向母親講述今天沿途遇見的景色。",
        "「下一次出發，我們可以帶上新的筆和紙。」小梁說著，將草帽穩穩壓在膝上。",
        "遠處的鐘聲再次響起，碼頭上的人陸續點起燈籠，為晚歸的船照亮最後一段水路。",
        "阿青踏上熟悉的石板路，回頭向船夫道謝，並答應明天再來幫忙整理碼頭的木箱。",
        "夜色慢慢落在小鎮屋頂，她把空竹籃放在門邊，心裡已經記下了下一次相見的約定。",
    ],
]


def json_text(value):
    return json.dumps(value, ensure_ascii=False, indent=2)


def book_sources(base):
    result = []
    for order, (key, name, category) in enumerate([
        ("a", "本機書源A・缺段", "都市"),
        ("b", "本機書源B・完整及拆章", "都市生活"),
        ("c", "本機書源C・故障隔離", "都市生活"),
    ]):
        list_rule = {"bookList": ".book", "name": ".name@text", "author": ".author@text",
                     "bookUrl": "a@href", "intro": ".intro@text", "kind": ".kind@text"}
        result.append({
            "bookSourceUrl": f"{base}/{key}", "bookSourceName": name,
            "bookSourceGroup": "本機UI驗收", "bookSourceType": 0,
            "enabled": True, "enabledExplore": True, "customOrder": order,
            "bookSourceComment": "只連線本機迴環測試服務；所有正文為合成驗收資料。",
            "searchUrl": f"{base}/{key}/search?key={{{{key}}}}&page={{{{page}}}}",
            "exploreUrl": json_text([{"title": category, "url": f"{base}/{key}/explore?kind=urban"},
                                     {"title": "完結" if key != "b" else "已完結", "url": f"{base}/{key}/explore?kind=finished"}]),
            "ruleSearch": list_rule, "ruleExplore": dict(list_rule),
            "ruleBookInfo": {"name": "#name@text", "author": "#author@text", "intro": "#intro@text",
                             "kind": "#kind@text", "tocUrl": "#toc@href"},
            "ruleToc": {"chapterList": "a.chapter", "chapterName": "text", "chapterUrl": "href"},
            "ruleContent": {"content": "#content@html"},
        })
    return result


def rss_sources(base):
    return [{"sourceUrl": f"{base}/rss/{key}.xml", "sourceName": name,
             "sourceGroup": "本機UI驗收", "sourceComment": "本機合成RSS；不連線外部網站。",
             "enabled": True, "singleUrl": False, "articleStyle": 0,
             "ruleContent": "#content@html", "enableJs": False, "customOrder": order}
            for order, (key, name) in enumerate([
                ("a", "本機訂閱A"), ("b", "本機訂閱B"), ("c", "本機訂閱C・故障隔離")])]


def html_page(title, body):
    return "<!doctype html><html lang='zh-Hant'><head><meta charset='utf-8'><title>" + escape(title) + "</title></head><body>" + body + "</body></html>"


def listing(key):
    return (f"<div class='book'><a class='name' href='/{key}/book'>{escape(BOOK_NAME)}</a>"
            f"<span class='author'>{escape(AUTHOR)}</span><span class='kind'>都市・完結</span>"
            "<p class='intro'>本機合成小說，用來核對正文補全、跨源發現與閱讀流程。</p></div>")


def toc_entries(key):
    entries = []
    for index, title in enumerate(CHAPTER_TITLES):
        if key == "b" and index == 0:
            entries += [(title + "（上）", f"/{key}/chapter/0-upper"), (title + "（下）", f"/{key}/chapter/0-lower")]
        else:
            entries.append((title, f"/{key}/chapter/{index}"))
    return entries


def chapter_parts(key, chapter, part=None):
    paragraphs = CHAPTERS[chapter]
    if key == "a":
        if chapter == 0:
            return paragraphs[:6]  # Tail truncation.
        if chapter == 1:
            return paragraphs[:3] + paragraphs[5:]  # Two internal paragraphs absent.
        if chapter == 2:
            return paragraphs[3:]  # Missing beginning, valid same-chapter title.
    if key == "b" and chapter == 0 and part:
        return paragraphs[:6] if part == "upper" else paragraphs[6:]
    return paragraphs


ARTICLES = {
    "shared": ("本機訂閱：河谷新書架", "Mon, 26 Oct 2026 09:00:00 +0800",
               "河谷圖書館今天把旅行手記移到靠窗的新書架，方便讀者在自然光下閱讀。"),
    "a": ("本機訂閱：上午到書提醒", "Mon, 26 Oct 2026 08:00:00 +0800",
          "這是一則合成的到書提醒，用來核對訂閱單源清單與文章返回位置。"),
    "b": ("本機訂閱：午後閱讀角", "Mon, 26 Oct 2026 10:00:00 +0800",
          "這是一則合成的閱讀角消息，用來核對跨源時間排序與重複文章合併。"),
}


def rss_xml(base, key):
    items = []
    for article in ("shared", key):
        title, date, body = ARTICLES[article]
        url = f"{base}/articles/{article}"
        items.append(f"<item><title>{escape(title)}</title><link>{escape(url)}</link>"
                     f"<guid isPermaLink='true'>{escape(url)}</guid><pubDate>{date}</pubDate>"
                     f"<description><![CDATA[<p>{body}</p>]]></description></item>")
    return ("<?xml version='1.0' encoding='UTF-8'?><rss version='2.0'><channel>"
            f"<title>本機訂閱{key.upper()}</title><link>{escape(base)}</link>"
            "<description>純本機合成驗收資料</description>" + "".join(items) + "</channel></rss>")


def route(path, query, base):
    """Pure routing function, also available for syntax/fixture checks without listening."""
    if path == "/sources.json":
        return 200, "application/json", json_text(book_sources(base))
    if path == "/rss-sources.json":
        sources = rss_sources(base)
        if query.get("web", ["0"])[0] == "1":
            sources[-1]["singleUrl"] = True
        return 200, "application/json", json_text(sources)
    if path == "/health":
        return 200, "text/plain", "fixture-ready"
    if path == "/expected.json":
        return 200, "application/json", json_text({"book": BOOK_NAME, "author": AUTHOR,
            "chapters": [{"title": title, "completeText": "\n".join(CHAPTERS[i]),
                          "missingFromA": CHAPTERS[i][6] if i == 0 else CHAPTERS[i][3] if i == 1 else CHAPTERS[i][0] if i == 2 else None}
                         for i, title in enumerate(CHAPTER_TITLES)],
            "aggregateArticleOrder": [ARTICLES[key][0] for key in ("b", "shared", "a")]})
    if path.startswith("/c/") or path == "/rss/c.xml":
        return 503, "text/plain", "Synthetic source failure for isolation verification."
    match = re.fullmatch(r"/(a|b)/(search|explore|book|toc)", path)
    if match:
        key, kind = match.groups()
        if kind == "search":
            wanted = query.get("key", [""])[0]
            body = listing(key) if not wanted or wanted in BOOK_NAME else "<p>沒有相符書籍</p>"
        elif kind == "explore":
            body = listing(key)
        elif kind == "book":
            body = (f"<h1 id='name'>{escape(BOOK_NAME)}</h1><p id='author'>{escape(AUTHOR)}</p>"
                    "<p id='intro'>自製的四章本機驗收小說，沒有第三方正文。</p><p id='kind'>都市・完結</p>"
                    f"<a id='toc' href='/{key}/toc'>查看目錄</a>")
        else:
            body = "".join(f"<p><a class='chapter' href='{url}'>{escape(title)}</a></p>" for title, url in toc_entries(key))
        return 200, "text/html", html_page(BOOK_NAME, body)
    match = re.fullmatch(r"/(a|b)/chapter/([0-3])(?:-(upper|lower))?", path)
    if match:
        key, chapter, part = match.groups()
        index = int(chapter)
        if part and (key != "b" or index != 0):
            return 404, "text/plain", "No such synthetic split part."
        title = CHAPTER_TITLES[index] + ("（上）" if part == "upper" else "（下）" if part == "lower" else "")
        content = "".join(f"<p>{escape(p)}</p>" for p in chapter_parts(key, index, part))
        body = f"<h1>{escape(title)}</h1><div id='content'>{content}</div><a href='/{key}/toc'>目錄</a>"
        return 200, "text/html", html_page(title, body)
    match = re.fullmatch(r"/rss/(a|b)\.xml", path)
    if match:
        return 200, "application/rss+xml", rss_xml(base, match.group(1))
    match = re.fullmatch(r"/articles/(shared|a|b)", path)
    if match:
        title, date, body = ARTICLES[match.group(1)]
        return 200, "text/html", html_page(title, f"<h1>{escape(title)}</h1><time>{date}</time><div id='content'><p>{escape(body)}</p></div>")
    return 404, "text/plain", "Synthetic fixture route not found."


def make_handler(base):
    counts = Counter()
    lock = Lock()

    class Handler(BaseHTTPRequestHandler):
        def do_GET(self):
            parsed = urlsplit(self.path)
            if parsed.path == "/requests.json":
                with lock:
                    status, mime, body = 200, "application/json", json_text(dict(counts))
            else:
                with lock:
                    counts[parsed.path] += 1
                status, mime, body = route(parsed.path, parse_qs(parsed.query), base)
            encoded = body.encode("utf-8")
            self.send_response(status)
            self.send_header("Content-Type", mime + "; charset=utf-8")
            self.send_header("Content-Length", str(len(encoded)))
            self.send_header("Cache-Control", "no-store")
            self.end_headers()
            self.wfile.write(encoded)

        def log_message(self, *_):
            pass  # Keep stdout limited to the two import URLs.

    return Handler


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--port", type=int, default=8765)
    args = parser.parse_args()
    if not 1 <= args.port <= 65535:
        parser.error("--port must be between 1 and 65535")
    base = f"http://10.0.2.2:{args.port}"
    server = ThreadingHTTPServer(("127.0.0.1", args.port), make_handler(base))
    server.daemon_threads = True
    print(base + "/sources.json", flush=True)
    print(base + "/rss-sources.json", flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close()


if __name__ == "__main__":
    main()
