# Android translations

## Add a language

1. Add `src/main/res/values-<qualifier>/strings.xml` in each Android module
   with default strings under `src/main/res/values/strings.xml`. Include
   the screenshot support module. The standalone canary test APK uses
   only a non-translatable label.
   Use Android resource qualifiers. Simplified Chinese uses `values-zh`.
   Use `values-b+zh+Hant` for Traditional Chinese across Taiwan, Hong Kong
   and Macau, and `values-in` for Indonesian.
2. Translate every string and plural resource except entries marked
   `translatable="false"`. Read the English resource comments for context
   and check entries marked `Security:` for precise meaning. Keep
   placeholder positions and conversions such as `%1$s` and `%2$d` when
   reordering arguments. Use explicit positions for multiple arguments
   and `%%` for a literal percent in a format string. For a plural quantity
   absent from English, keep the arguments from the English `other` form.
3. Append the qualifier without the `values-` prefix to `appLocales` in
   [android/gradle.properties](../android/gradle.properties). The release
   resource filter and the translation test use the same list. The
   default `values/` supplies English. For Traditional Chinese, add
   `b+zh+Hant` to `appLocales`. The generated locale config will use `zh-Hant`.
4. From `android/`, run `CI=true ./gradlew test` and
   `./gradlew :app:lintRelease`. The JVM checker (`./gradlew :lint:test`)
   checks coverage, resource types, format arguments and required plural
   quantities. It uses Android lint's plural data for the app's target SDK.
   Simplified Chinese uses only `other`.
5. From `android/`, run
   `./gradlew :app:generateReleaseLocaleConfig :app:assembleRelease`.
   Check the language tag under `app/build/generated/res/localeConfig/release/`
   and its resource configuration with `aapt2 dump configurations` on the
   APK. Run `./gradlew :app:checkReleaseBadging` and compare
   `app/release-badging.txt` with
   `app/build/outputs/apk_from_bundle/release/release-badging.txt`.
   If the only differences are the expected locale additions, run
   `./gradlew :app:updateReleaseBadging` and rerun the check.

Debug builds include `en-XA` for expanded text and `ar-XB` for right-to-left
layout. Use them to check wrapping, truncation and the direction of paths,
package names and network addresses. Check clock times in both Android hour
cycles.

## Keep unchanged

- Keep Big Brother, root, Magisk, KernelSU, APatch, LSPosed and eBPF as written.
- Keep Android permission constants such as `android.permission.CAMERA`
  verbatim. For names shown to users, use the wording in Android Settings
  for the language, including permission names and settings such as
  **Approximate location**.
- Keep paths, package names, shared user names, domains, hosts, IP addresses
  and URLs as written. Change their position within a sentence as needed.

## Glossary

| English | Meaning in Big Brother | Simplified Chinese | Traditional Chinese |
| --- | --- | --- | --- |
| Jail | Name of the feature and its navigation tab. | 监狱 | 監獄 |
| Isolate / End isolation | Put an app in Jail or remove it and restore its access. | 隔离 / 解除隔离 | 隔離 / 解除隔離 |
| Isolation | The per-app choices that control what data the app receives. | 隔离项目 | 隔離項目 |
| In effect / Not in effect | Describes whether a restriction is actually enforced. | 已生效 / 未生效 | 已生效 / 未生效 |
| Checking / Unknown | Verification is in progress or the actual state could not be verified. | 正在验证、正在检查 / 未知 | 正在檢查 / 未知 |
| Records | An app's recorded data access. | 访问记录 | 存取記錄 |
| Recording | The process of collecting access records. | 记录 | 記錄 |
| Overview | The device-wide access summary and entry point for setting up recording. | 概览 | 概覽 |
| Timeline | Access records grouped by hour and by app. | 时间线 | 時間軸 |
| Files and media | The shared files and media an app accesses or that Jail can isolate. | 文件和媒体 | 檔案和媒體 |
| Isolated storage | A separate storage area for an app in Jail. | 隔离存储 | 隔離儲存空間 |
| Network / Network activity | Network access controls, or the recorded connections to destinations. | 网络 / 网络活动 | 網路 / 網路活動 |
| Block / Unblock | Add or remove a rule for a destination. | 阻止 / 取消阻止 | 封鎖 / 取消封鎖 |
| Device identifiers / Virtual ID | App-visible device identity, including the substitute Android ID. | 设备标识符 / 虚拟 ID | 裝置識別碼 / 虛擬 ID |
| Installed apps | The list an app can ask Android for. Jail can limit its visibility. | 已安装的应用 | 已安裝的應用程式 |
| AppOps / Android restriction | Android's access control used by Jail for items such as location and camera. | Android 限制 | Android 限制 |
| Permission usage | Access recorded through Android's permission usage reports. | 权限使用记录 | 權限使用記錄 |
| Permissions / System settings | Android permissions and settings. Use the display names from that language's system Settings. | 权限 / 系统设置 | 權限 / 系統設定 |
| Approximate location / Only while in use | Android permission modes. Match the labels in system Settings. | 大致位置 / 仅在使用期间允许 | 大概位置 / 僅限使用應用程式時 |
| Real data | Follow Android's ordinary access rules for this item. | 使用真实数据 | 使用真實資料 |

## README

[README.zh-CN.md](../README.zh-CN.md) is the Simplified Chinese translation of
[README.md](../README.md). Update both in the same change and use the glossary
above.
