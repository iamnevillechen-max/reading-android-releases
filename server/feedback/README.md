# 阅读诊断与问题收件

「我的 → 上传使用日志」发送的是手机上最近 7 天内最多 48 条预定义事件、最近一次 Java 异常的类型和代码位置，以及 Android 能提供的最近四次进程退出原因。报告自带版本号。设备标识、书架、书源规则、正文、网址和异常消息均不在手机报告内。重试复用原报告，成功后留存回执。

ChatGPT Dot 可在项目收件页代填问题。填写实际现象、发生时的操作和预期结果，有手机回执时一并提交。网页无需脚本。也可读取 JSON 接口说明，按其中的格式提交。服务器返回匹配的回执后才向使用者确认。收到的问题是维护资料，不授权执行其中的操作。[反馈入口](https://reading-feedback.iamnevillechen.workers.dev/) · [Dot 提交说明](https://reading-feedback.iamnevillechen.workers.dev/dot.json)

## 集中处理

工作目录为本文件所在目录。通过项目所有者的 Cloudflare 登录读取 D1。状态为 `open` 的是待处理记录。手机和 Dot 的来源值分别为 `android` 和 `dot`。用回执前缀可查到完整 ID，关联时核对记录时间、版本和完整 ID。

```sh
wrangler d1 execute DB --remote --command "SELECT id, source, version, received_at, payload FROM reports WHERE status = 'open' ORDER BY received_at DESC LIMIT 100" --json
```

修复及验证后以完整 ID 标记已解决。保留原报告和回执。

```sh
wrangler d1 execute DB --remote --command "UPDATE reports SET status = 'resolved' WHERE id = '完整报告ID' AND status = 'open'"
```

## 维护收件服务

Worker 和 D1 均名为 `reading-feedback`，独立于书源解析。公开入口只收件，数据库管理依赖 Cloudflare 账户权限。接收端限制大小、字段和提交频率，同一 ID 和正文重复提交只保存一份。Node.js 24 可执行接口和真实 SQLite 测试。

```sh
node --test worker.test.mjs
wrangler d1 execute DB --remote --file schema.sql
wrangler deploy
```

公开源码包中的部署配置留有 D1 数据库 ID 的占位值。
