# PixivAdvanceSearch

适用于 [Pixiv](https://play.google.com/store/apps/details?id=jp.pxv.android) (jp.pxv.android) 的 LSPosed 模块。

> **注意**：本模块基于 Vibe Coding 开发，代码质量参差不齐，请谨慎使用。

### 功能

- **热门排序无限试用** — 热门度排序搜索的 7 天试用永不过期。通过 Hook 试用天数计数器和 AB 实验开关实现，不是伪装高级会员（服务端数据不受影响）。

- **PID 查询** — 主界面 Toolbar 注入「PID」按钮，输入 ID 直接跳转到对应插画/小说/用户页面。

- **作品详情查看** — 长按作品 → 菜单新增「查看详情」。弹窗显示作品名称、作者名称、作品 PID、用户 PID，支持长按复制。

### 截图

![第一张](images/first.jpg) ![第二张](images/second.jpg)

### 工作原理

| 功能 | Hook 目标 | 搜索方式 |
|---|---|---|
| 试用天数 | `um9.w()` | DexKit: `core_local_preference_key_first_launch_time_millis` + `86400000L` |
| AB 实验 | `i23.a()` | DexKit: 字符串 `"] cannot be converted to a boolean."` |
| PID 按钮 | `MainActivity` Toolbar | View 树遍历查找 Toolbar |
| 查看详情 | `te.create()` | DexKit: `"layout_inflater"` → `pe` → `addInvoke` → `te` → 0 参非 void 方法 |

所有 Hook 优先使用 DexKit 字节码搜索定位目标方法，失败后回退直接类名。

### 构建

```bash
./gradlew assembleDebug
```

需要 `compileSdk 36`。

### 安装

1. 安装 APK
2. 在 LSPosed 中启用模块，作用域选择 `jp.pxv.android`（或使用静态作用域）
3. 强制停止 Pixiv

### 支持版本

- Pixiv: 基于 **6.183.0** 开发与测试（DexKit 字节码搜索支持跨版本兼容）
- LSPosed API: **101**

### 许可证

[Apache-2.0](LICENSE)