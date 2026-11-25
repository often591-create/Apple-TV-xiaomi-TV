# 电视遥控器 (TV Remote)

Android 手机遥控 Apple TV 和小米 / Google TV 的小工具。

- **Apple TV**：使用 Companion 协议（iPhone 控制中心遥控器同款），首次配对输入电视上的 4 位 PIN。
- **小米 TV Box S 等 Google TV / Android TV**：使用 Google 官方 Android TV Remote v2 协议，首次配对输入电视上的 6 位码。

协议实现参考开源项目 [pyatv](https://github.com/postlund/pyatv) 和 [androidtvremote2](https://github.com/tronikos/androidtvremote2)。

APK 由 GitHub Actions 自动编译，发布在 `apk` 分支。

`devtest/` 里是在电脑上用模拟电视测试协议的代码（不参与 APK 编译）。
