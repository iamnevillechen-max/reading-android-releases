package io.legado.app.model.webBook

import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.rule.ExploreRule

/** Reviewed, local execution overlay. Never persist this clone or alter the archived source. */
object LocalSourceExecutionRepair {
    const val HEIYAN_SOURCE_ID = "https://www.heiyan.com/#reader-verified-20261026"

    fun forExecution(source: BookSource): BookSource {
        if (source.bookSourceUrl != HEIYAN_SOURCE_ID || source.bookSourceType != 0 ||
            source.ruleExplore != originalHeiyanExplore) return source
        // Typed rule equality compares every parsed field, regardless of JSON key order.
        // All identity, user settings and other rule objects remain on the original values.
        return source.copy(ruleExplore = repairedHeiyanExplore())
    }

    private val originalHeiyanExplore = ExploreRule(
        bookList = "@js:\nvar query = /[?&]reader_query=([^&]*)/.exec(String(baseUrl));\nvar key = query ? decodeURIComponent(query[1].replace(/\\+/g, \" \")) : \"\";\nkey = String(java.t2s(key)).trim();\nvar links = java.getElements(\"a[href~=/book/[0-9]+\$]\");\nvar books = []; var seen = {};\nfor (var i = 0; i < links.size(); i++) {\n  var item = links.get(i); var name = String(item.text()).trim(); var url = String(item.attr(\"href\"));\n  if (name !== \"\" && !seen[url] && (key === \"\" || String(java.t2s(name)).indexOf(key) >= 0)) {\n    books.push(item); seen[url] = true;\n  }\n}\nbooks;",
        name = "text",
        bookUrl = "href"
    )

    // Native Jsoup first()/closest() can be null; retain the reviewed empty-metadata behavior.
    private fun repairedHeiyanExplore() = ExploreRule(
        bookList = "@js:\nvar query = /[?&]reader_query=([^&]*)/.exec(String(baseUrl));\nvar key = query ? decodeURIComponent(query[1].replace(/\\+/g, \" \")) : \"\";\nkey = String(java.t2s(key)).trim();\nvar links = java.getElements(\"a[href~=/book/[0-9]+\$]\");\nvar books = []; var seen = {};\nfor (var i = 0; i < links.size(); i++) {\n  var item = links.get(i);\n  // Image links can contain malformed attribute residue. Use the real visible\n  // title link, including homepage headings, result-table titles and rankings.\n  if (item.select(\"img\").size() !== 0) continue;\n  var name = String(item.text()).trim();\n  var url = String(item.attr(\"href\"));\n  if (name !== \"\" && !seen[url] && (key === \"\" || String(java.t2s(name)).indexOf(key) >= 0)) {\n    var card = item.closest(\"dl,li,tr\");\n    var cardLinks = card ? card.select(\"a[href~=/book/[0-9]+\$]\") : null;\n    var oneBook = !!card;\n    for (var j = 0; cardLinks && j < cardLinks.size(); j++) {\n      if (String(cardLinks.get(j).attr(\"href\")) !== url) oneBook = false;\n    }\n    books.push({name:name, bookUrl:url,\n      author:oneBook && card.select(\"a[href~=/people/[0-9]+\$]\").size() > 0 ? String(card.select(\"a[href~=/people/[0-9]+\$]\").first().text()).trim() : \"\",\n      coverUrl:oneBook && card.select(\"h3\").size() !== 0 && card.select(\"img\").size() > 0 ? String(card.select(\"img\").first().attr(\"src\")) : \"\",\n      kind:oneBook && card.select(\".flex a[href~=/all/]\").size() > 0 ? String(card.select(\".flex a[href~=/all/]\").first().text()).trim() : \"\"});\n    seen[url] = true;\n  }\n}\nbooks;",
        // AnalyzeRule reads Rhino NativeObject rows by bare key, not JSONPath.
        name = "name",
        author = "author",
        bookUrl = "bookUrl",
        coverUrl = "coverUrl",
        kind = "kind"
    )
}
