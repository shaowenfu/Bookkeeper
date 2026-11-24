# Bookkeeper

只为自己定制的 Android 实时记账系统（设计文档见项目资料）。

当前阶段：**M0 通知采样** —— 只记日志的通知监听器，收集 7 个目标 App 的真实通知样本，并统计中行通知相对平台通知的延迟。

- 使用说明、构建 / 安装、导出与分析：[docs/M0.md](docs/M0.md)
- 延迟分析脚本：[tools/analyze_delay.py](tools/analyze_delay.py)

## 快速构建

```bash
./gradlew assembleDebug        # 需要 JDK 17+ 和 Android SDK
# → app/build/outputs/apk/debug/app-debug.apk
```

不想装 Android SDK：每次 push 后在 GitHub **Actions → Build APK → Artifacts** 下载 APK。

技术栈：Kotlin、Jetpack Compose、Room、WorkManager；minSdk 30（Android 11），侧载安装。
