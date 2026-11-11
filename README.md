# SpiderHost（可行性验证 / Step 0）

目标：验证能否在 **魅族 20 Pro（arm64，Android 13/14）** 上，用一个自己的 APK 直接加载 TVBox / OK影视 的 **type 3 Java spider jar**（带 Guard native .so 的那种），并成功跑出 `homeContent` 和 `searchContent` 的结果。

跑通之后，下一步才是在 `127.0.0.1` 上提供 Jellyfin 风格的 API 给 SenPlayer。**这一步还没有任何 Jellyfin 接口。**

> 本仓库不包含任何第三方 jar、配置文件或配置地址。配置 URL 只在手机上手动输入，只保存在本机 App 数据里。

## 这个 spike 测什么

点 **Run** 后按顺序执行，每一步都写日志（屏幕 + 日志文件）：

1. 下载配置 URL（支持普通 JSON、带 `//` 注释的 JSON、`**` 后接 base64 的“图片包裹”格式）。
2. 按站点 key 找到站点，只接受 `type: 3` 且 `api` 为 `csp_Xxx` 的站点。
3. 解析 spider 地址：站点自己的 `jar` 优先，否则用根 `spider`；支持 `url;md5;xxxx`、`;pk;` 后缀、`./` `../` 相对路径、`//` 开头。
4. 下载 jar 到 `cache/jar/`，有 md5 就校验（不匹配只警告、仍继续，与 FongMi / Kototoro 行为一致），然后设为只读（Android 14 不允许加载可写的 dex）。
5. **静态检查 jar**：
   - 列出 jar 引用、但自己不带、宿主也没有的类 → 这就是会出 `NoClassDefFoundError` 的清单；
   - 判断是否“protected Init”（Init 里有 `Process.killProcess` / `System.exit`，且白名单里没有本包名）。
6. 列出 jar 内所有 `.so`，按设备 ABI 解到 `files/so/<abi>/`，作为 `DexClassLoader` 的 native 搜索路径。
7. `DexClassLoader` 加载，设置 `com.github.catvod.Proxy` 端口为 9978。
8. 调用 Init：
   - protected jar：**不调** `Init.init`，而是 `Init.get()` → 把 Context 写进实例字段 → 调 jar 自带的 `exeLibStub()`（由它自己解包/加载 Guard native）→ 可选 `replaceCloudDiskNames()`、`startGoProxy(Context)`（Kototoro 的做法）；
   - 普通 jar：`Init.init(Context)`。
9. 实例化 `com.github.catvod.spider.Xxx`，`init(context, ext)`。
10. `homeContent(true)`，再 `searchContent("繁花", false)`，原样显示返回内容（前 4000 字）。

所有 spider 调用都在单独线程，带超时（默认 30 秒，可改）。异常会打完整堆栈，并归类为：缺类 / native 库问题（`UnsatisfiedLinkError`）/ 宿主 API 签名不匹配 / 其它。

**进程被杀**：jar 杀进程时 App 无法当场记录。所以每一步开始前会写一个“进行中”标记；下次打开 App 时如果标记还在，屏幕顶部会显示“上次运行在第几步时进程消失”，Android 11+ 还会附上系统的 `ApplicationExitInfo`（退出原因）。

## 本地 HTTP 服务（127.0.0.1:9978）

App 启动即监听，只绑定本机回环地址：

- `GET /health` → `{"ok":true,"port":9978,"siteKey":...,"spiderLoaded":...}`
- `/proxy?...` → CatVod 风格转发：带 `do=` 参数时调 `spider.proxyLocal(params)`，否则调 jar 内的静态 `com.github.catvod.spider.Proxy.proxy(params)`。返回值按 `Object[]{状态码, Content-Type, body(InputStream/byte[]/String), headers}` 转成 HTTP 响应。

用 `adb forward tcp:9978 tcp:9978` 后可在电脑上 `curl http://127.0.0.1:9978/health` 检查。

## 使用方法

1. 推送到 GitHub 后，Actions 里的 **build** 工作流会自动编译，下载产物 `spiderhost-debug-apk`。
2. 魅族上安装 APK（debug 签名，需要允许安装未知来源）。
3. 填写：配置 URL、站点 key（如 `csp_` 站点在配置中的 `key`）、搜索词。
4. 点 **Run**。看屏幕输出，或点 **分享日志** 发出来。
5. 若进程直接退出：重新打开 App，看顶部的“上次运行没有正常结束”报告。

三个实验开关（默认都关）：

| 开关 | 作用 |
|---|---|
| 伪装包名为 com.github.tvbox.osc | 传给 jar 的 Context 的 `getPackageName()` 返回 TVBox 包名，用来验证“白名单杀进程”是不是唯一阻碍。只改包名，不伪装签名。 |
| 预先 System.load 解出的 .so | 在 jar 自己加载前先把 .so 加载进来。可能触发 Guard 的反调试，仅用于排查 `UnsatisfiedLinkError`。 |
| 强制标准 Init.init | 对 protected jar 也直接调 `Init.init`。预期会被杀进程，用来确认判断是否准确。 |

日志文件：`/data/data/com.oksen.spiderhost/files/logs/spiderhost.log`，以及（若系统允许）`Android/data/com.oksen.spiderhost/files/spiderhost.log`。

## 宿主提供的类

jar 加载时父加载器优先，以下类由本 APK 提供：

- `com.github.catvod.crawler.Spider`（全部标准方法：`init`、`homeContent`、`homeVideoContent`、`categoryContent`、`detailContent`、`searchContent` 两个重载、`playerContent`、`liveContent`、`isVideoFormat`、`manualVideoCheck`、`proxy`、`proxyLocal`、`action`、`destroy`、`safeDns`、`client`）、`SpiderDebug`、`SpiderNull`
- `com.github.catvod.debug.MainActivity`（桩，含混淆后的静态方法名）
- `com.github.catvod.Init`、`com.github.catvod.Proxy`
- `com.github.catvod.utils.{Path, Util, Json, Asset}`、`com.github.catvod.net.OkHttp`（精简子集）
- `com.whl.quickjs.android.QuickJSLoader`、`com.whl.quickjs.wrapper.{JSObject, JSCallFunction}`（桩，**不是**真的 QuickJS）
- 依赖库：OkHttp 4.12.0、Gson 2.11.0、Jsoup 1.17.2、androidx.collection、HiddenApiBypass 6.1、NanoHTTPD 2.3.1

## 已知未知 / 风险

- **身份校验**：Kototoro 为了让 Guard jar 正常工作，做了完整的“宿主身份桥接”（伪装包名 + 签名 + ApplicationInfo + PackageManager，见其 `com.github.tvbox.osc.base.App`）。本 spike 只做了可选的包名伪装，没有签名伪装。如果 jar 在 native 层校验签名，很可能在这一步失败或被杀。
- **`com.github.tvbox.osc.base.App` 等宿主类**：部分 jar 直接引用 TVBox 宿主的 App 类。本 spike 不提供；第 5 步的“宿主缺失类”清单会告诉我们具体缺什么。
- **QuickJS**：只有桩类。依赖真实 JS 引擎的 spider 会失败。真实库（`wang.harlon.quickjs:wrapper-android`，推测为同一包名的原始实现）未引入：它不包含 jar 期望的混淆方法名，而且会与桩类冲突。
- **加密的 dex**：Guard jar 的真实代码可能在 `assets` 里加密，由 `exeLibStub()` 运行时解出；第 5 步的静态扫描只覆盖外层 dex。
- **OkHttp 版本**：用的是 4.12.0（Kototoro 用 5.x）。若 jar 依赖 5.x 独有 API，会出现 `NoSuchMethodError`。
- **配置格式**：AES 加密配置（`2423` 开头）、`img+` 形式的 spider 未实现，会明确报错。
- **魅族 Flyme**：后台管理可能杀进程；测试时让 App 保持前台。
- **端口 9978**：与 TVBox/影视类 App 默认端口相同，同机运行时可能冲突（日志会显示启动失败）。
- **合规**：spider 抓取的内容版权情况未知，仅限个人技术验证。

## 本地编译

仓库没有提交 `gradle-wrapper.jar`（二进制）。本地已装 Gradle 8.7+ 时：

```bash
gradle wrapper --gradle-version 8.7   # 可选：生成 wrapper
gradle assembleDebug                  # 需要 JDK 17 和 Android SDK (platform 34)
```

## 许可与致谢

部分代码改编自 [Kototoro](https://github.com/kototoro-app/Kototoro)（Apache-2.0），详见 `NOTICE` 及各文件头注释。
