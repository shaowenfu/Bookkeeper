# Bookkeeper

只为自己定制的 Android 记账系统（设计文档见项目资料）。

当前阶段：**M1 截图一键记账**。付完款长按两个音量键，截屏交给 DeepSeek 看图识别，自动入账并去重；也可以分享截图或账单长图导入。
没有常驻后台服务（M0 通知采样证明常驻在 vivo 上不可靠）。

- 使用说明：[docs/M1.md](docs/M1.md)
- M0 通知采样（已结束）：[docs/M0.md](docs/M0.md)，分析脚本 [tools/analyze_delay.py](tools/analyze_delay.py)

## 快速构建

```bash
./gradlew assembleDebug        # 需要 JDK 17+ 和 Android SDK
# → app/build/outputs/apk/debug/app-debug.apk
```

不想装 Android SDK：每次 push 后在 GitHub **Actions → Build APK → Artifacts** 下载 APK。

技术栈：Kotlin、Jetpack Compose、Room、WorkManager；minSdk 30（Android 11），侧载安装。
