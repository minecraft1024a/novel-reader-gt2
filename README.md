# GT2 腕上小说阅读器

本项目采用“自有手表应用 + GT2 私有蓝牙 RFCOMM 文件通道”的传输方案。手机应用把 `.txt`、`.md` 或 `.markdown` 文件通过 HiChain Lite 认证和 `svc 0x28` 文件状态机发送，手表端在 `internal://app/` 接收后移动到 `internal://app/imported/`，书架按文件列表读取。传输链路不调用运动健康 WearKit、Wear Engine 服务，也不依赖运动健康合作方白名单。

## 为什么不走 WearKit

本项目的本地逆向分析已确认：运动健康 `WEAR_KIT_SERVICE` 会检查调用 UID、包名、签名 SHA-256、本地授权和 AGC scope。把客户端类和分片协议反编译出来，不能让一个新包通过服务端白名单；修改运动健康再重签也不适合作为通用 APK 方案。

同一分析还确认，官方 Wear Engine 服务对普通第三方也会在 scope/auth 阶段拒绝（错误码 8/9）。因此 Android 端不再调用 `HiWear`，改为移植直连蓝牙协议：BR/EDR RFCOMM、HiChain Lite 认证、`svc 0x28` 文件上传。

官方 API 依据：

- [Huawei App-to-App Message Communications](https://developer.huawei.com/consumer/en/doc/connectivity-guides/phone-send-message-0000001051059209/)：用于核对被白名单限制的官方 P2P 文件接口。
- [Huawei Lite Wearable send-message](https://developer.huawei.com/consumer/cn/doc/connectivity-Guides/send-message-0000001052460491/)：用于核对 Lite Wearable 的 `FeatureAbility` 消息接收和生命周期。

这两个接口说明了官方通道的行为，但不是当前 APK 的默认传输依赖。当前 APK 只要求系统蓝牙配对和手表端 HAP。

## 工程布局

- `apps/novel-reader/gt2/`：Lite Wearable HML/CSS/JavaScript 项目。阅读器沿用 `docs/hmlitewearable_files` 的列表、文件和分段阅读模式，单次读取约 450 字节；字体、配色、自动翻页和触控动作保存在 `internal://app/kvstore/`。
- `apps/novel-reader/android/`：Flutter Material 3 手机项目。选择文本文件后调用 `MainActivity.kt` 的 RFCOMM 桥接，并保存最近发送记录和设备 MAC。
- `.github/workflows/android.yml`：GitHub Actions 使用 Flutter 3.24.5 构建 release APK。
- `compiler/linux/bin/gt2-build.js`：本机 GT2 编译器。

## 构建

GT2 在开发机上已用本地编译器构建；编译器和逆向输入不随公开仓库上传：

```bash
node compiler/linux/bin/gt2-build.js apps/novel-reader/gt2 -o apps/novel-reader/gt2/novel-reader.hap --build /tmp/novel-reader-build
```

产物是 `apps/novel-reader/gt2/novel-reader.hap`。当前电脑没有 Flutter，因此 Android APK 不在本地构建；提交后由 GitHub Actions 执行 `flutter pub get` 和 `flutter build apk --release`。

## 设备和交互边界

手表代码按 Lite Wearable API 6、圆屏安全区域和低 JS heap 编写：不把整本书载入内存，只保留当前页和少量 UTF-8 边界缓冲。书架启用原生 `list` 的 rotation focus，适配旋钮滚动。

SDK 中没有可移植的物理侧键回调定义，系统侧键也可能被系统保留。因此设置页提供可配置的左/右轻触、左/右长按和四向滑动动作；它们映射到上一页、下一页、自动翻页、工具栏和返回书架。真实设备若暴露额外按键事件，可在同一 `keyMap` 上接入，不改变阅读逻辑。

## 当前验证边界

开发过程中使用的电脑端 GT2 私有蓝牙 RFCOMM 工具已验证音乐和应用文件上传；Android 端移植同一底层协议。`file_type=7` 指向自建 Lite Wearable 应用的路由是逆向推论，尚未在真实 GT2 上验收。因此 HAP 编译已通过，但“小说文件最终落入 `imported/`”仍需一次真机发送确认。

公开仓库只包含应用源代码、构建工作流和本 README，不包含本地逆向材料或 APK 样本。
