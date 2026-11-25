package io.legado.app.model.cloud

/** Generated from server/shared/content-quality-v1.json by compile-content-quality.mjs. */
internal object CloudContentQualityRules {
    const val VERSION = "reader-content-quality-2"
    const val MIGRATION_MINIMUM = 80
    const val SHORT_TEMPLATE_LIMIT = 1200
    const val TEMPLATE_LINE_LIMIT = 300
    val patterns = listOf(
        "^(?:获取正文失败|獲取正文失敗|加载正文失败|載入正文失敗|获取内容失败|獲取內容失敗|加载失败|載入失敗|無法載入正文|无法加载正文)(?:[：:，,。.!！\\s】\\]]|\$)",
        "^(?:原书源|原書源|书源|書源|正文|章节|章節)(?:内容|內容|响应|回應)?(?:获取|獲取|加载|載入|读取|讀取|请求|請求)?(?:失败|失敗|错误|錯誤|超时|逾時)",
        "^(?:本章|本章节|本章節|此章|此章节|此章節|当前章节|當前章節)(?:内容|內容)?(?:为|為|是|属于|屬於)?(?:vip|付费|付費|收费|收費|锁定|鎖定|未解锁|未解鎖)(?:章节|章節|内容|內容|[，,。\\s]|\$)",
        "^(?:请|請)(?:您)?(?:先)?(?:登录|登入|注册|註冊)(?:后|後|以|来|來|再)?(?:查看|阅读|閱讀|觀看|访问|訪問|解锁|解鎖)",
        "^(?:请|請)(?:您)?(?:先)?(?:订阅|訂閱|购买|購買|付费|付費|充值|解锁|解鎖)(?:本章|此章|章节|章節|后|後|以|再)",
        "^(?:您|你)(?:还|還)?(?:未|尚未|没有|沒有)(?:登录|登入|购买|購買|订阅|訂閱)(?:本章|此章|章节|章節|[，,。\\s]|\$)",
        "^(?:请|請)(?:先)?(?:完成|通过|通過|进行|進行)?(?:人机验证|人機驗證|安全验证|安全驗證|验证码|驗證碼)",
        "^(?:访问|訪問|操作|请求|請求)(?:过于|過於|太过|太過)?频繁|^(?:访问|訪問|操作|请求|請求)(?:過於|太過)?頻繁",
        "^(?:access denied|forbidden|just a moment(?:\\.{3}|…)?|checking your browser|enable javascript(?: and cookies)?|this chapter is (?:locked|paid|premium)|please (?:log in|sign in|subscribe|purchase|unlock)(?: to| before| this chapter|\$)|you (?:must|need to) (?:log in|sign in|subscribe)|error(?:[ :：]|\$))",
        "^(?:(?:正在)?(?:加载|加載|载入|載入)中?|loading)\\s*(?:\\.{1,6}|…{1,2}|。{1,3})?\$"
    ).map { Regex(it, RegexOption.IGNORE_CASE) }
}
