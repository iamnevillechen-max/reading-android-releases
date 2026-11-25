分類只維護 `taxonomy.json` 這一份：固定 11 個大類，未知標籤歸「其他」；男頻、女頻、完結、連載、排行、新書、熱門是獨立篩選條件。服務端保存完整原始來源／路由，API 回傳穩定 category ID 與 filter ID，不按網站原標籤新增頁籤。

`classify(label, group=..., source_id=..., category_override=...)` 先採明確修正及指定來源的標籤修正，再判斷原始標籤及分組。`source_overrides` 格式是 `{完整來源ID: {原標籤: 大類ID}}`；只允許現有大類 ID。來源 ID 不去查詢參數、不改 fragment、不降成 hostname。標籤匹配做 NFKC／大小寫正規化，英文以完整單詞匹配。

在專案根目錄執行：`python3 -m unittest discover -s server/tests -v`。
