本機 UI 驗收資料，僅供獨立測試安裝；不會自行啟動、導入或修改 App。

在專案根目錄執行 `python3 tools/fixture-server.py`，預設只監聽 `127.0.0.1:8765`；可用 `--port` 更改。Android Emulator 使用以下地址：

- 書源：`http://10.0.2.2:8765/sources.json`
- 訂閱源：`http://10.0.2.2:8765/rss-sources.json`
- 完整正文及預期排序：`http://10.0.2.2:8765/expected.json`
- 請求次數：`http://10.0.2.2:8765/requests.json`

透過 App 原有導入流程，使用 `legado://import/bookSource?src=http://10.0.2.2:8765/sources.json` 及 `legado://import/rssSource?src=http://10.0.2.2:8765/rss-sources.json`。

《河谷來信・本機驗收》有四章，作者「測試寫手甲」。A 源第一章缺尾、第二章缺中間兩段、第三章缺開頭；B 源正文完整，第一章拆為相鄰上下章，因此後續目錄索引偏移。A／B 發現分類分別為「都市」／「都市生活」。C 源固定返回 503，供失敗隔離驗收。三源同屬本機主機，不能作獨立來源投票。

兩個 RSS 各有一篇獨有文章及同一篇共用文章；聚合應留下三篇，依時間排列為「午後閱讀角」、「河谷新書架」、「上午到書提醒」。第三個訂閱源返回 503。所有正文和消息均為自製合成資料；服務不會連線外部網站。以 Ctrl-C 停止服務。

來源設定回返驗證：`rss-sources.json?web=1` 只把故障 C 源改為網頁入口；全部選取重新導入後，聚合頁應自動重驗並把網頁入口數由 4 改成 5。再導入不帶參數的版本回復。
# 獨立 Android 測試裝置

`python3 tools/android-test-device.py start` 建立本專案專用 API35 ARM64 模擬器，使用 `emulator-5580`。若該連線不是本專案持有，工具會停止，不接管其他任務的裝置，也不操作實體手機。

安裝待測 APK 後、第一次啟動前，執行 `python3 tools/android-test-device.py prepare`，準備空白自寫測試環境，避免首次預設資料／提示干擾固定案例。若已有閱讀資料庫，工具會拒絕覆寫。原生案例會自行建立及清理自己的資料，內置書源補入另由專門案例核對。

完成後執行 `python3 tools/android-test-device.py stop`。工具先核對裝置名稱及持有記錄，停止該模擬器並只刪除其 AVD 及 ini；保留 APK、原始碼、測試證據及共用 SDK。機器需已安裝 `android-35/default/arm64-v8a` 映像；可用 `ANDROID_SDK_ROOT`／`READER_ADB` 指定既有工具，不自動下載依賴或重啟共用 adb server。

