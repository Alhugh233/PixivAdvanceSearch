# PixivAdvanceSearch

[中文](README_zh.md)

LSPosed module for [Pixiv](https://play.google.com/store/apps/details?id=jp.pxv.android) (jp.pxv.android).

### Features

- **Unlimited popular-sort search trial** — the 7-day trial for sorting search results by popularity never expires. Works by hooking the trial-days counter and AB-test gate, not by faking premium (server-side data is unaffected).

- **PID lookup** — a "PID" button injected into the main toolbar. Enter an illust/novel/user ID to jump directly to the corresponding detail page.

- **Work detail viewer** — long-press any work thumbnail to see an extra "View Details" option in the context menu. Displays work title, author name, work PID, and user PID in a selectable dialog.

### How it works

| Feature | Hook target | Discovery method |
|---|---|---|
| Trial days | `um9.w()` | DexKit: `core_local_preference_key_first_launch_time_millis` + `86400000L` |
| AB test | `i23.a()` | DexKit: string `"] cannot be converted to a boolean."` |
| PID button | `MainActivity` toolbar | View-tree walk for Toolbar class |
| Work detail | `te.create()` | DexKit: `"layout_inflater"` → `pe` → `addInvoke` → `te` → 0-arg non-void methods |

All hooks use DexKit bytecode search at runtime with fallback to direct class names.

### Build

```bash
./gradlew assembleDebug
```

Requires `compileSdk 36`.

### Install

1. Install the APK
2. Enable in LSPosed → scope `jp.pxv.android` (or use static scope)
3. Force-stop Pixiv

### Supported versions

Developed and tested on Pixiv **6.183.0**. DexKit-based discovery enables cross-version compatibility as long as the target method structure remains similar.

### License

[Apache-2.0](LICENSE)