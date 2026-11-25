# 阅读雲端書源服務

獨立 Cloudflare Worker 入口、SQLite-backed Durable Object 執行環境及既有 D1。書源規則和抓取在服務端處理，Android 保存薄來源識別、書架、進度、歷史及有界閱讀快取。維持既有來源 ID、書籍 URL 及章節 URL；公開原始碼不得包含手機匯出的私人規則、登入資料或服務 token。

## 執行入口

`src/runtime-entry.ts` 匯出輕量 Gateway 與 `ReaderSourceRuntime` 類別。Gateway 共用原來的 token／Origin 檢查，拒絕未授權請求後，將原 Request body 及 Response stream 直接交由 `SOURCE_RUNTIME` binding；不在普通 Worker 解析大 JSON、來源頁面或正文。完整 `createApp`、來源核驗、快取、回應序列化及排程維護都在同一個固定 DO identity 內執行。原網址、API、token、D1 和 cron 契約保持。

Wrangler 的 `new_sqlite_classes` migration 建立這個 SQLite-backed DO 類別。來源、原始 JSON／SHA、健康狀態及維護預算仍放原 D1；程式沒有讀寫 DO storage／SQL，也沒有 alarms、WebSockets 或持續背景 timer。D1 的 `migrations/*.sql` 仍按既有程序套用。部署前後須分別核對 Gateway 及 DO 的真實 CPU／outcome；本機測試與設定準備不等於已部署。

DO lifecycle migration 之後不能直接 rollback 到未匯出該類別的舊版本。緊急接線回退採 forward deploy：沿用相同設定、binding 和 migration，只把 main 暫指向 `src/direct-entry.ts`；它仍匯出 `ReaderSourceRuntime`，default 則直接執行現行 `createApp`。完整目錄及正文契約仍在，D1不回復／刪除。先 dry-run 確認 class export，再部署；此 fallback 重新受到普通 Worker 10ms CPU限制，只作接線故障回退。參考[官方 rollback 限制](https://developers.cloudflare.com/workers/versions-and-deployments/rollbacks/#bindings)。

## 開發與核驗

```sh
npm ci --no-audit --no-fund
npm run compile:sources
npm run typecheck
npm test
```

`scripts/compile-sources.mjs` 只編譯本專案已核驗公開書源內的靜態程式。任意匯入規則不會取得動態執行程式權限。未支援的規則仍完整保存，狀態為 `unsupported`，不列入發現。伺服器部署／secret 設定由正式維護工具完成；測試通過不代表已在 Cloudflare 發布或手機完成遷移。

## 身份與資料範圍

D1 binding 為 `DB`，三個獨立 secret：`ADMIN_TOKEN`、`READ_TOKEN`、`MIGRATION_TOKEN`。token 至少32字元，透過 `Authorization: Bearer …` 傳送。只讀 token 可搜尋及閱讀；遷移 token 另可匯入並啟動真實來源核驗；原規則匯出只允許管理 token。沒有把共用管理秘密寫入 APK。瀏覽器跨來源請求不開放，原生 Android 不需要 CORS。

未登入只可 `GET /health`，固定回 `ok`，不公開來源或服務設定。其餘成功回應為 `{ "data": … }`；一般錯誤為 `{ "error": { "code": …, "message": … } }`，不含內部 stack、原規則或憑據。只有管理員主動核驗可讀取有長度上限的維護診斷；遷移與只讀 token 不會收到該資料。沒有請求／書名／閱讀歷史日誌。

## 擁有人維護入口

在專案根目錄使用 `python3 tools/cloud-admin.py status` 檢查服務，`seed-public` 僅匯入專案內置公開規則。`init-secrets --wrangler /實際安裝位置/wrangler.js` 透過標準輸入配置本服務三個 secret；該命令只應在建立或明確輪替服務憑據時使用。管理工具從 macOS Keychain 的專用項目讀取憑據，不把 token 印到終端、命令參數、原始碼或交付檔。服務網址來自本機私有 `.toolchain/cloud-deployment.json`，部署與手機遷移狀態須查當次核驗證據。

服務維護以修訂原來源 ID 的規則、重新匯入及真實核驗為準。匯出原規則使用管理員專用 `/v1/source-export`；匯出內容與手機原始存檔都屬私人維護資料，不放公開源碼包。服務端變更後，手機只同步健康來源的薄資料，無需把完整來源規則重新下載到手機。

## API

- `GET /v1/status`：版本、有限來源統計、固定分類定義及不含私人來源識別的維護摘要；`features.contentContractVersion` 及 `features.tocInstallContract` 公告目前完整正文及安全目錄安裝契約。
- `POST /v1/import`：`{kind:"book"|"rss", sources:[{id,json,sha256}]}`。`json` 為完整原始 JSON **字串**，SHA256 對其原樣 UTF-8 位元組計算。每批1至50條、總請求最多1MiB、單條原字串最多768KiB UTF-8。同批 ID 不可重複，必須與原規則 `bookSourceUrl`／`sourceUrl` 精確相同；舊有空白或控制字元 ID 仍能原樣存檔，但不執行。以 JSON1 固定兩條 SQL 儲存及讀回，有實際改動的批次另用一條 SQL 失效統計快取，不按來源數增加 SQL 次數；逐條 exact 字串及雜湊比對後才返回回條。
- `GET /v1/source-receipt?kind=book|rss&id=…`：重新計算資料庫原字串 SHA256，再回 `{id,sha256,stored:true,supported,status,capabilities,webUrl}`。`webUrl` 僅在靜態網站入口核驗成功時提供。手機只有存檔、支援及健康狀態與雜湊核對成立後才能採用相應雲端入口；未核驗的已引用規則仍需保護。
- `POST /v1/source-receipts`：只讀批次查詢，三種 token 均可用。`{kind:"book"|"rss",ids:[…]}` 每批1至100個不重複的精確字串 ID，保留空字串、空白及控制字元，不接受略去 kind。一次 JSON1／來源主鍵讀取並逐條重算資料庫原字串 SHA256，按請求順序回 `{receipts:[…]}`；已存項目同單筆回條，未知項目為 `{id,stored:false}`，不回原規則。任一原件雜湊不符回 `STORAGE_MISMATCH`，不發出假成功回條。`GET /v1/status` 的 `features.batchedSourceReceipts=true` 表示支援；客戶端只為不存在或雜湊不同的來源上傳原規則，避免遷移重試重傳整份存檔。舊版單筆介面不變。
- `GET /v1/source-export?kind=book|rss&id=…`：管理 token 專用，回 exact `json`、SHA256及回條，供維護或還原。
- `GET /v1/sources?kind=book|rss&cursor=…`：每頁100條薄資料與 `nextCursor`；不返回原始規則。日常同步指定 `active=true`，SQL只選健康且啟用來源，不下載整個隔離清單。
- `POST /v1/verify`：`{kind,sourceId,key?,bookUrl?}`。書源須實際搜尋→詳情→目錄→可讀正文；RSS feed須實際文章→可讀內文。`singleUrl` 靜態網站入口以受限公開 GET、成功狀態及 HTML 類型核驗，另對有界 HTML 的標題、主要標題及明確短錯誤頁訊號檢查，拒絕 HTTP 200 的人機驗證、拒絕存取、soft-404、域名停放或停站頁；普通新聞的登入導覽、CAPTCHA 元件或程式／文章提到相關詞語不直接判壞。回 `verificationKind:"web"`／`stages.website` 仍只代表該次 HTML 入口可達且未命中明確壞頁，不代表完整網站可用，也不能當作文章或正文鏈路通過。網站入口不執行原來源的 JS、注入或登入程式，不繞過存取限制；不支援的動態入口仍存檔隔離。不能傳入健康旗標冒充驗證。失敗維持 `pending`，未支援維持 `unsupported`。原規則改動後重新核驗，先前健康狀態失效。
- `GET /v1/catalogue?kind=book|rss&cursor=…`：僅健康且啟用的來源，每頁100來源。回 `{revision,categories,routes,webEntries,checkedSources,totalSources,complete,nextCursor}`。網站入口獨立為 `{sourceId,name,url,categoryId,categoryLabel,fingerprint}`，不混入可解析文章的 `routes`。分類只使用 `server/catalogue/taxonomy.json` 的11大類；同來源、同大類只返回一條代表路由，完整原路由留在資料庫。
- `POST /v1/category-override`：`{kind:"book"|"rss",sourceId,fromCategoryId,categoryId}`，只允許管理／遷移 token 修正健康來源的既有類別，兩個分類 ID 都須來自固定11類。修正套用該來源目前所選大類的全部原路由，保存原始分類對應並在日後重核驗套回；不改原 JSON、SHA256或健康結果。回 `{sourceId,fromCategoryId,categoryId,changed,revision}`。重試相同修正不重寫；每次實際修正增加整數 revision，手機可確實失效分類快取。
- `POST /v1/books/search`：`{sourceId,key,page?}` → `{books}`。
- `POST /v1/books/explore`：`{sourceId,url,page?}` → `{books}`。URL可為已核驗原路由，或 `reader-cloud://category/<categoryId|all>`。虛擬分類按來源全部同類原路由輪換，先讀完各路由第一頁，再取各路由第二頁，避免只顯示第一個子分類。
- `POST /v1/books/info`：`{sourceId,book}` → `{book}`。
- `POST /v1/books/toc`：`{sourceId,book,tocInstallContract:"reader-toc-identity-1"}` → `{book,chapters,complete:true,contractVersion,sourceId,bookUrl}`。未協商的舊client回409 `CLIENT_UPDATE_REQUIRED`，避免補回前置章節後舊numeric位置跳章。客戶端須驗證完整回覆，再依同章身份交易安裝；不能把舊伺服器忽略新欄位的回覆當成功。
- `POST /v1/books/content`：`{sourceId,book,chapter,nextChapterUrl?}` → `{content,book?,complete:true,contractVersion,sourceId,bookUrl,chapterUrl,bodySha256}`。身份欄位精確對應請求，SHA256為完整文字UTF-8原樣位元組；未完成、超限或後頁失敗不回部分成功。目錄或正文返回的 `book.variable` 必須傳給後續請求，供來源自身的公開目錄狀態使用。`nextChapterUrl` 只限制停止邊界，不授予讀取其他章的權限。
- `POST /v1/rss/articles`：`{sourceId,url?,page?}` → `{articles,nextUrl}`。
- `POST /v1/rss/content`：`{sourceId,article}` → `{content}`。
- `POST /v1/maintenance/run`：管理 token 專用，主動執行一輪與排程相同的有界維護，回處理／成功／失敗／延後數量及安全錯誤碼；不回來源原文、搜尋字詞或書籍 URL。

`/v1/search|explore|info|toc|content` 與 `/v1/books/…` 為相同路由的別名。服務不接收書架分組、閱讀進度、閱讀歷史或 ReadConfig 作同步用途。

## 自動維護

先套用所有 `migrations/*.sql`，再部署相應版本。排程需要部署設定提供 `*/30 * * * *`，並明確設定 `MAINTENANCE_ENABLED="true"`；未啟用時 scheduled handler 不處理來源。`MAINTENANCE_DAILY_WRITES` 可降至0，最高固定5000，不能用設定提高上限。

每輪最多兩個來源，優先留一個名額給距上次核驗至少6小時的健康來源或待重試來源，另一個名額留給尚待核驗的支援來源。持久化最後嘗試時間及10分鐘 lease，避免卡在第一個壞來源或重疊執行。6小時是可再驗的門檻，不承諾六小時覆蓋全部來源；30分鐘兩個名額每日最多96次嘗試。成功的搜尋字詞、書籍及章節樣本保存在私人 D1 維護欄位，後續優先重用；已知公開來源另有固定公開樣本，避免用不相干書名誤判。未知樣本仍須實際通過完整鏈路才恢復健康。

重查失敗即撤下雲端分類入口並退避，之後仍可自動試探恢復；不自動編造規則、不執行未知 JavaScript，也不刪除原始規則及雜湊。清理每天只移除少量過期快取、失去對應原件的衍生路由及過舊維護摘要。未變的原規則匯入及分類路由不重寫。每日先持久預留寫入預算，估計包含來源、路由及其索引、小表和清理；預算不足時延後，維持原件。

來源統計只保存 book／rss 兩個快取記錄，真實批次變更或健康成員改變才失效；相同健康來源續驗、連續已隔離失敗及一般讀章不使每次分類刷新重新掃描整份存檔。版本號使用持久整數，不依賴秒級時間。網站重驗若明確回傳 `WEBSITE_BLOCKED`、`WEBSITE_NOT_FOUND`、`WEBSITE_CLOSED` 或 `WEBSITE_PARKED`，第一次就撤下既有健康入口並退避，不等待一般閱讀的連續兩次失敗；原件及雜湊仍保留，之後可重新核驗恢復。維護候選以支援且啟用的 partial index 篩選，仍須監察實際 D1 讀量；免費帳戶的其他服務會共用額度，這個服務的預算不等於帳戶總用量隔離。

## 免費方案邊界

普通 Workers Free 入口維持10ms CPU界限，重解析交由 SQLite-backed Durable Object。DO 官方表列預設每次請求30秒 CPU，本設定沒有 `cpu_ms` override。Free SQLite DO 包含每日100,000次請求與13,000 GB-s；超額操作會失敗。依據：[DO limits](https://developers.cloudflare.com/durable-objects/platform/limits/)、[DO pricing](https://developers.cloudflare.com/durable-objects/platform/pricing/)。來源操作仍須逐段真實部署核驗，包含完整回應序列化及真正 scheduled event；本機 fixture 通過不能代表所有來源已恢復。來源抓取逾時與主機白名單在 engine 限定，連續兩次一般閱讀失敗退避5分鐘；排程再驗失敗立即隔離。結果快取每項最多256KB，每來源最多16項、全服務最多512項、TTL5至15分鐘，資料庫不可用時不以快取失敗丟棄已取得的合法正文。每輪後台來源處理及清理數量有上限，未新增付費服務。

部署使用既有 Free 帳戶的 Worker、SQLite-backed DO 與原 D1；不依賴額外付費 VPS、容器、Browser Run、Workers AI、R2、Queues 或其他計費產品。來源是否能在免費額度內穩定運行以實際部署核驗為準；不因宣稱「免費」而切換方案、提高 CPU 額度或另行開通付費資源。相同原規則及能力的匯入重試只讀回核對，不更新資料列或索引，避免重試再次消耗大批 D1 寫入額度。GPL 授權副本位於 `server/LICENSE`，交付包同時包含 Android 與服務端對應源碼、分類定義、依賴鎖檔及必要構建腳本。
