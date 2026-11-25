# 新增综合小说来源：17K、晋江

规则文件：`tools/source-packs/verified-fiction-more.json`。范围为官方网页公开免费章节；不是全书免费源，不含小说正文。

| 来源 | 网页核验样本 | 目录限制 |
| --- | --- | --- |
| 17K | 《万古第一神》，风青阳；公开搜索、详情、目录和第1章正常返回 | 只收没有 VIP 子标记的章节；样本 89 个免费条目、7,775 个 VIP 条目排除。正文还须满足网页 BOOK 配置 `vip: 0` |
| 晋江 | 《开端》，祈祷君；公开搜索、详情、目录和第1章正常返回 | 只收章节表中真实公开 `href`，不用 VIP 的 `rel` 或购买地址；样本15个免费章节 |

17K 的免费目录包含上架感言等通知，第1章按标题选取。晋江按照官方网页 GBK 编码搜索关键词；UTF-8 会被误解为另一关键词。晋江正文仅取 `paragraph_comment_content`，不把书架弹窗、作话、评论或版权栏当正文。

两项目前均未启用未经核验的发现分类。实际 App 搜索、目录和正文解析以主任务的原生测试记录为准；网页可达不等于 App 已通过。正文检查只保存长度和 SHA-256，没有公开复制全文。

普通公开来源依据：

- [17K 官方搜索](https://search.17k.com/search.xhtml?c.st=0&c.q=%E4%B8%87%E5%8F%A4%E7%AC%AC%E4%B8%80%E7%A5%9E)
- [17K 作品详情](https://www.17k.com/book/3006464.html)
- [17K 官方目录](https://www.17k.com/list/3006464.html)
- [晋江官方作品详情及目录](https://www.jjwxc.net/onebook.php?novelid=4292425&chapterid=0)
- [晋江公开第一章](https://www.jjwxc.net/onebook.php?novelid=4292425&chapterid=1)

导入前核对同网址旧规则，保留旧规则和阅读资料。源站免费范围、排版或搜索编码发生变化时，需重新核验。
