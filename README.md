# Bookkeeper

只为自己定制的 Android 记账系统（设计文档见项目资料）。

当前阶段：**M0′ 读屏探针**。M0 通知采样发现常驻后台在 vivo 上不可靠，改为付款后主动触发识别：
无障碍按钮 / 快速设置磁贴读当前屏幕（节点文字 + 截图），或分享截图导入，再用 DeepSeek 两条路径（文字、看图）识别并对比。

- 探针使用说明：[docs/M0-probe.md](docs/M0-probe.md)
- M0 通知采样（已结束）：[docs/M0.md](docs/M0.md)，分析脚本 [tools/analyze_delay.py](tools/analyze_delay.py)

## 快速构建

```bash
./gradlew assembleDebug        # 需要 JDK 17+ 和 Android SDK
# → app/build/outputs/apk/debug/app-debug.apk
```

不想装 Android SDK：每次 push 后在 GitHub **Actions → Build APK → Artifacts** 下载 APK。

技术栈：Kotlin、Jetpack Compose、Room；minSdk 30（Android 11），侧载安装。
