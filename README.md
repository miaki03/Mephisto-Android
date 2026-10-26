# Mephisto 安卓客户端

Mephisto 的手机入口。服务端在另一个仓库（`Mephisto`），接口契约在那里冻结。

**做 UI 设计前先读** CLIENT-CONTRACT（客户端职责与逻辑边界）；
**联调前先读** TEST-PLAN（分级测试与配合清单）；
**找代码**看 INDEX（模块/函数索引）。三份都在服务端仓库的 `docs/` 下。

## 现状

**第 7 步完成**：能构建、能安装、能打服务端的 `/healthz`。会话列表、SSE、闹钟分别在第 8、9、10 步。

| 项 | 值 |
|---|---|
| 包名 | `dev.mephisto.app` |
| minSdk / targetSdk / compileSdk | 29 / 37 / 37 |
| 工具链 | JDK 21、AGP 9.4.1、Gradle 9.8.2、build-tools 36.0.0 |
| 产物 | `app/build/outputs/apk/debug/app-debug.apk`（约 28 MB） |

## 测试

`powershell
powershell -NoProfile -ExecutionPolicy Bypass -File tools\build.ps1 -Task testDebugUnitTest
`

pp/src/test/java/.../core/ApiTest.kt 用手写 JSON 验解析与错误映射；
RealPayloadTest.kt 用**真服务端抓下来的响应**（pp/src/test/resources/fixtures/*.json）当夹具——
手写 JSON 只能证明解析器符合我的想象，抓下来的字节能抓到"服务端改了字段名"这种问题。

夹具怎么来的：起 mephisto serve，带令牌 GET /healthz、/api/v1/sessions、
/api/v1/sessions/{id}/messages、/api/v1/notices，把原始响应原样落盘。服务端接口变了就重抓一次。

## 构建

工具链全部装在仓库外面（`E:\study\.toolchain`），仓库里只有代码：

```powershell
# 一次性：装工具链（约 500 MB 下载）
python tools\install-toolchain.py        # JDK + cmdline-tools + Gradle
python tools\install-sdk-packages.py     # platform 36/37 + build-tools + platform-tools

# 每次：构建（脚本会设好 JAVA_HOME / ANDROID_HOME / TEMP）
powershell -NoProfile -ExecutionPolicy Bypass -File tools\build.ps1
powershell -NoProfile -ExecutionPolicy Bypass -File tools\build.ps1 -Task installDebug -Install
```

`local.properties` 里的 `sdk.dir` 是本机绝对路径，已被 gitignore——换机器要自己写一次。

## 这台机器上的三个坑（都已绕开，写下来免得再踩）

1. **`sdkmanager` 用不了**：报 `Failed to connect to remote repository`，而 Python 访问 `dl.google.com` 正常。
   所以 `tools/install-sdk-packages.py` 直接从 Google 仓库 XML 拿 URL、自己下载解压。
2. **Maven Central TLS 握手被掐**：`settings.gradle.kts` 里优先用阿里云镜像。
3. **`%TEMP%` 不可写**：Gradle 的 `java.io.tmpdir`、Kotlin 编译、`adb` 的日志都要改道到 `.toolchain\jtmp`；
   `kotlin.compiler.execution.strategy=in-process` 是为了避开 Kotlin daemon 往 `%LOCALAPPDATA%` 写临时文件。

## 提醒链路（S10）

`
服务端 notice(due_utc) --/api/v1/notices--> ReminderPlan.plan(now, exactAllowed)
   ├─ FUTURE  -> AlarmManager.setAlarmClock（无权限则 setWindow 10 分钟）
   ├─ OVERDUE -> 立刻弹通知（宽限 2 小时内）
   └─ SKIP    -> 不处理（过期太久，服务端已汇总成"你错过了 N 条"）
`

- equestCode 由 
otice.id 经 FNV-1a 派生 → 重排是覆盖，不是叠加；取消能取消掉。
- 常驻服务用 emoteMessaging（无 6 小时上限），通知渠道 IMPORTANCE_LOW（无声）。
- 开机/时区变更/时间被改/App 更新都会触发 RescheduleReceiver 重排——AlarmManager 的闹钟不跨重启。

### 三个必须手动开的开关

App 里「提醒与权限」页有按钮：通知权限、精确闹钟、电池优化。少开任何一个，表现都是"它没提醒我"，而且**没有报错**。

## 目录

```
app/src/main/java/dev/mephisto/app/MainActivity.kt   # 唯一的界面（Compose）
app/src/debug/AndroidManifest.xml                    # 仅调试构建允许明文 HTTP
tools/build.ps1                                      # 设环境 + 构建（+ 安装）
tools/install-toolchain.py                           # JDK / cmdline-tools / Gradle
tools/install-sdk-packages.py                        # platform / build-tools / platform-tools
```

## 下一步

第 8 步：只读——会话列表 + 历史消息，服务端用 `GET /api/v1/sessions` 与 `.../messages`。
第 9 步：发送 + 队列 + 三级撤销 + SSE。
第 10 步：常驻通知、精确闹钟、日历。
