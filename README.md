# PixivAdvanceSearch

适用于 [Pixiv](https://play.google.com/store/apps/details?id=jp.pxv.android) (jp.pxv.android) 的 LSPosed 模块。

> **注意**：本模块基于 Vibe Coding 开发，代码质量参差不齐，请谨慎使用。

### 功能

- **热门排序无限试用** — 热门度排序搜索的 7 天试用永不过期。

- **PID 查询** — 搜索页顶栏新增「PID」分段，进入独立 PID 搜索页，输入 ID 直接跳转到对应插画/小说/用户页面。

- **作品详情查看** — 长按作品 → 菜单新增「查看详情」。弹窗显示作品名称、作者名称、作品 PID、用户 PID，支持长按复制。

- **显示设置** — 侧边栏新增「显示设置」选项，点击打开网页版浏览与显示设置。

- **多语言** — 跟随 Pixiv 当前语言，内置 English / 简体中文 / 日本語。

### 截图

![第一张](images/first.jpg) ![第二张](images/second.jpg)

### 工作原理

| 功能 | Hook 目标 | 搜索方式 |
|---|---|---|
| 试用天数 | `um9.w()` | DexKit: `core_local_preference_key_first_launch_time_millis` + `86400000L` |
| AB 实验 | `i23.a()` | DexKit: 字符串 `"] cannot be converted to a boolean."` |
| PID 查询 | 搜索页 `SegmentedLayout` 顶栏 | 直接类名（DexKit fallback）；Hook `setOnSelectSegmentListener`，追加第 4 个分段并注入 PID 页面 |
| 显示设置 | `MainActivity` 侧边栏 | 查找 `NavigationView`，添加菜单项并设置点击监听 |
| 查看详情 | 对话框 builder 的 `create()` | DexKit: `"layout_inflater"` → builder 参数类 → `addInvoke` → builder → `create()` |

所有 Hook 优先使用 DexKit 字节码搜索定位目标方法，失败后回退直接类名。

### 构建

```bash
./gradlew assembleDebug
```

需要 `compileSdk 37` / `buildTools 37.0.0`（AGP 9.1.1 / Gradle 9.3.1 / JDK 21）。

### 安装

1. 安装 APK
2. 在 LSPosed 中启用模块，作用域选择 `jp.pxv.android`（或使用静态作用域）
3. 强制停止 Pixiv

### 支持版本

- Pixiv: 基于 **6.204.0** 开发与测试（理论全版本通杀）
- Android: **9.0+**（API 28+）
- LSPosed API: **102**

### 第三方开源

- [libxposed](https://github.com/libxposed/api) — Apache-2.0
- [DexKit](https://github.com/LuckyPray/DexKit) — Apache-2.0（`Core/` 目录为 LGPL-3.0）
- [AndroidHiddenApiBypass](https://github.com/LSPosed/AndroidHiddenApiBypass) — Apache-2.0

完整声明见 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。

### 许可证

[Apache-2.0](LICENSE)
