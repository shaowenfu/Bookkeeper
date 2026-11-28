# iOS 版：云打包到 TestFlight

iOS 版和 Android 版各自独立、纯本地存储，功能保持一致（改一边要同步改另一边）。代码在 `ios/`，
工程由 [XcodeGen](https://github.com/yonaskolb/XcodeGen) 从 `ios/project.yml` 生成，不提交 `.xcodeproj`。

## 怎么用

1. 打开“快捷指令”App → 右上角 + → 添加“截屏” → 再添加“截图记账 · 识别截图记账”，起名“截图记账”。
2. 设置 → 辅助功能 → 触控 → 轻点背面 → 轻点两下 → 选“截图记账”。
3. 付完款停在成功页，轻点两下手机背面。几秒后弹通知，可以直接撤销。

App 不需要常驻后台：每次轻点，系统运行快捷指令，把截图交给 App 在后台识别。
后台只有几十秒，识别超过 25 秒就先存着，下次打开 App 时自动补上。
相册里的截图、账单长图可以在 App 首页“导入截图”，也可以在快捷指令里用“导入图片记账”。

## 一次性配置（大约 20 分钟）

需要付费的 Apple Developer Program（每年 99 美元）。TestFlight 内部测试不需要审核。

1. **注册 Bundle ID**：developer.apple.com → Certificates, Identifiers & Profiles → Identifiers → + →
   App IDs → App → Bundle ID 选 Explicit，填 `com.shaowenfu.bookkeeper`。不用勾选任何 Capability。
   想用别的 ID，就在 GitHub 仓库 Settings → Secrets and variables → Actions → Variables 里加 `IOS_BUNDLE_ID`。
2. **建 App 记录**：appstoreconnect.apple.com → App → + → 新建 App。平台 iOS，名称随意（App Store 里要唯一，
   比如“截图记账 SF”，不会公开），主要语言简体中文，Bundle ID 选上一步的，SKU 随便填。
3. **生成 API 密钥**：App Store Connect → 用户和访问 → 集成 → App Store Connect API → 团队密钥 → +。
   访问权限选 **管理（Admin）**：云端自动签名要用它创建分发证书和描述文件。
   记下 **Issuer ID** 和 **Key ID**，下载 `AuthKey_XXXXXX.p8`（只能下载一次）。
4. **Team ID**：developer.apple.com → Account → Membership details → Team ID（10 位）。
5. **填 GitHub Secrets**：仓库 Settings → Secrets and variables → Actions → New repository secret：

   | 名称 | 内容 |
   |---|---|
   | `APPLE_TEAM_ID` | Team ID |
   | `ASC_KEY_ID` | Key ID |
   | `ASC_ISSUER_ID` | Issuer ID |
   | `ASC_KEY_P8` | `.p8` 文件的全部内容（用文本编辑器打开整段复制，包括 BEGIN/END 两行） |

6. **触发构建**：往仓库推一次 `ios/` 下的改动，或者在 Actions → iOS → Run workflow（合并到 main 后才有这个按钮）。
   跑完后 App Store Connect 处理 5–15 分钟。
7. **装到手机**：App Store Connect → 你的 App → TestFlight → 内部测试 → + 新建群组，把自己的 Apple ID 加进去，
   勾选刚上传的构建。iPhone 上装 TestFlight App，用同一个 Apple ID 登录就能安装。
   TestFlight 构建 90 天过期，期间推送新版会自动出现在 TestFlight 里。

没配 Secrets 时，CI 只为模拟器编译，用来检查代码。

## 常见问题

- **Archive 报 “No Accounts” / “No profiles”**：API 密钥权限不是 Admin，或 Bundle ID 没注册。
- **上传报 “No suitable application records”**：第 2 步的 App 记录没建，或 Bundle ID 不一致。
- **上传报构建号重复**：构建号用的是 Actions 运行编号，一般不会重复；真遇到了就再跑一次。
