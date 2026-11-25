package io.legado.app.model.multisource.core

/** Recognise standalone website notices without interpreting words from the surrounding story. */
object ChapterContentPolicy {
    private val navigation = Regex("^(上一[章頁页]|下一[章頁页]|返回[目目录錄]+|[目目录錄]+)$")
    private val failure = Regex("^(?:加载正文失败|載入正文失敗|获取正文失败|獲取正文失敗|获取内容失败|獲取內容失敗|" +
        "本章(?:节|節)?(?:暂无|暫無|没有|沒有|无|無)内容|本章(?:节|節)?(?:暂无|暫無|没有|沒有|无|無)內容|" +
        "章节不存在|章節不存在|内容不存在|內容不存在|页面不存在|頁面不存在|404(?:\\s+not found)?|" +
        "请先登录后阅读|請先登入後閱讀|access denied|just a moment)(?:[：:，,。.!！\\s].*)?$", RegexOption.IGNORE_CASE)
    private val errorDetail = Regex("(?:HTTP\\s+[45]\\d\\d(?:\\s+.*)?|暂未找到可用的同章正文.*|無法取得正文.*)", RegexOption.IGNORE_CASE)
    private val notice = Regex(
        "(?:(?:(?:本章(?:节|節)?|此章(?:节|節)?)(?:(?:为|為|是)(?:VIP|付[费費]|收[费費])(?:章[节節])?)?[，,。\\s]*)?" +
            "(?:[请請](?:先)?(?:[订訂][阅閱]|[购購][买買]|登[录錄入])(?:[本此]章)?(?:[后後]|以)(?:[继繼][续續])?[阅閱][读讀]|" +
            "付[费費][后後][阅閱][读讀]|(?:[试試][读讀])(?:部分)?(?:已)?[结結]束)|" +
            "(?:本章(?:节|節)?|章[节節]|本段|正文)(?:[内內]容)?(?:缺失|[遗遺]失|[暂暫][无無][内內]容)|" +
            "(?:正文|本段[内內]容)(?:加[载載]|[载載]入)失[败敗](?:[，,。\\s]*[请請](?:刷新|稍[后後])重[试試])?|" +
            "(?:加[载載]|[载載]入)正文失[败敗](?:[，,。\\s]*[请請](?:刷新|稍[后後])重[试試])?|" +
            "preview ends here|subscribe to (?:continue reading|read the full chapter)|�{3,})[。.!！]?",
        RegexOption.IGNORE_CASE
    )

    fun isRepairNotice(line: String): Boolean {
        if (line.length > 180) return false
        val trimmed = line.trim()
        val first = trimmed.firstOrNull() ?: return false
        return first in "本此章请請付试試正加载載pPsS�" && notice.matches(trimmed)
    }

    fun hasRepairNotice(text: String): Boolean = text.lineSequence().any(::isRepairNotice)

    fun missing(text: String, title: String): Boolean {
        val lines = text.lineSequence().map { line ->
            line.filterNot { Character.getType(it) == Character.FORMAT.toInt() }.trim()
        }.filter { it.isNotEmpty() }.filterNot { it == title.trim() || navigation.matches(it) }.take(7).toList()
        if (lines.isEmpty()) return true
        if (text.length >= 1_200 || lines.size > 6) return false
        return (isRepairNotice(lines.first()) || failure.matches(lines.first())) &&
            lines.drop(1).all { isRepairNotice(it) || failure.matches(it) || errorDetail.matches(it) }
    }
}
