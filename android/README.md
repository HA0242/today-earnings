# 今天挣了多少钱 · Android 版

macOS 版的安卓移植：按月薪和上班时间段，实时算出“现在这一秒你挣了多少钱”，并按自然月记录工作日、请假（含半天假）和加班。完全离线，不申请任何权限，数据只存在本机。

需求文档见项目里的《今天挣了多少钱 · 产品需求文档（安卓 APK 版）》。

## 和 macOS 版的主要区别

- 新月份的工作日一律从周一至周五开始，不再复制上个月的日期（解决大小周错位）。
- 日历可以翻到下个月提前调整大小周，更远的未来月份不开放。
- 新增半天假；选“扣钱”时扣日薪的一半。
- 请假只能记在工作日；休息日不显示请假选项。
- 上班时间段结束时间必须晚于开始时间，不支持夜班。
- 加班可选倍率（1 / 1.5 / 2 / 3 倍）。
- 首次使用若不是月初，会问“本月已挣”从 1 日算还是从今天算。
- 新增数据导出 / 导入（JSON），换手机或更新前请先导出。
- 暂无小组件和常驻通知。

## 怎么拿到 APK（不需要安装 Android Studio）

用 GitHub Actions 自动构建：

1. 把项目推送到 GitHub（`main` 分支，改动了 `android/` 目录会自动触发）；也可以在仓库的 **Actions → Build Android APK → Run workflow** 手动触发。
2. 构建完成后，进入该次运行页面，在底部 **Artifacts** 里下载 `today-earnings-apk`，解压得到 `today-earnings.apk`。
3. 想让朋友直接下载：给提交打一个 `android-v1.0.0` 这样的标签并推送，APK 会自动发布到仓库的 Releases 页面。

   ```bash
   git tag android-v1.0.0
   git push origin android-v1.0.0
   ```

### 配置发布签名（强烈建议，只需一次）

覆盖安装更新要求每次都用同一个签名密钥。没有配置时会用临时的 debug 签名，下次构建的 APK 无法覆盖安装，必须先卸载，数据会丢。

在仓库 **Settings → Secrets and variables → Actions** 新建 4 个 Secret，值见项目文件夹里的 `android-signing/签名信息-请妥善保管.txt`：

| 名称 | 内容 |
| --- | --- |
| `KEYSTORE_BASE64` | `keystore_base64.txt` 的全部内容 |
| `KEYSTORE_PASSWORD` | 密钥库密码 |
| `KEY_ALIAS` | `today-earnings` |
| `KEY_PASSWORD` | 密钥密码 |

`android-signing/` 已写入 `.gitignore`，不会被提交。请把这个文件夹另外备份一份。

## 安装

1. 把 `today-earnings.apk` 发到手机（微信、AirDrop 不行就用数据线或网盘）。
2. 打开后按提示允许“安装未知应用”。若出现 Play Protect 提示，选“仍要安装”。
3. 更新时直接安装新 APK 即可覆盖，数据保留（前提是签名一致）。更新前仍建议先在 设置 → 数据备份 里导出一份。

## 本地开发（可选）

需要 JDK 17 和 Android SDK（装 Android Studio 最省事）：

```bash
cd android
./gradlew testDebugUnitTest   # 跑收入计算的单元测试
./gradlew assembleDebug       # 生成 debug APK
```

核心计算逻辑在 `app/src/main/java/local/ha/todayearnings/core/`，不依赖安卓，对应需求文档第 3 节；测试用例对应第 8.2 节验收用例。

## 目录

```
android/
  app/src/main/java/local/ha/todayearnings/
    core/    数据模型与收入计算（纯 Kotlin）
    data/    本地存储（filesDir/ledger.json）与备份编解码
    ui/      Compose 页面：首页、日历、统计、设置
    MainActivity.kt
  app/src/test/   单元测试
.github/workflows/android-apk.yml   自动构建
```
