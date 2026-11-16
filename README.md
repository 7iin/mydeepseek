# myDeepSeek

> 一个为自己定制的 Android 端 DeepSeek 客户端。Jetpack Compose 原生实现，release 经 R8 压缩后约 4 MB。

**中文 | [English](README.en.md)**

![Version](https://img.shields.io/badge/version-1.2.1-blue)
[![License](https://img.shields.io/badge/license-MIT-green)](LICENSE)

## 这是什么

myDeepSeek 是从 [SimpleChat](https://github.com/Pokanshuu/Simple-Chat) fork 出来的个人定制版：
保留了它"轻、快、离线优先、无账号体系"的底子，在三个地方按自己的使用习惯做了改造 ——
**图片预览、历史按月归档、WebDAV 云同步**，并顺手修掉了原代码里几处会真正影响数据安全的问题
（详见 [CODE_REVIEW.md](CODE_REVIEW.md)）。

适合的场景：

- 在日常对话里发图（截图、照片），希望图片像官方客户端那样**独立成块**，而不是把气泡撑变形
- 会话写到几百条，需要一个能一眼扫到"上个月那个方案"的历史列表
- 手机 + 平板两台设备，想用自己的网盘（坚果云 / Nextcloud）同步，而不是注册第三方账号

## 这次定制改了什么

| 改动 | 说明 |
|---|---|
| **图片预览独立成块** | 用户消息里的图片改为气泡**上方的固定正方形预览**（104dp、`Crop`、最多三张一排），点击整屏查看，长按出消息菜单；纯图片消息不再画一个空气泡。原先是把图片按原比例塞进气泡，竖图会把气泡拉成一条，文字被挤到最底下只剩一行。 |
| **历史按月分组** | 抽屉从「今天 / 昨天 / 7 天内 / 更早」改成「置顶 + 按月」，标题形如 `2026年11月`（英文 `November 2026`）。原来的分桶在长期使用后会把九成会话压进「更早」，等于没分组。 |
| **WebDAV 同步（流式）** | 设置 → 云端同步。载荷是**逐行 JSON**（一行一个会话），图片按内容哈希单独上传到 `media/`，读写全程流式、不把整个库读进内存；按会话的 `updatedAt` 做 last-write-wins 合并。支持「启动时自动同步」、手动同步、测试连接。密码与 API Key 一样只存 Keystore 密文。 |
| **修复：发送的图片没有落盘** | 拍照 / 相册的图片原先以 base64 内联进 `messages.attachments`，一张 3MB 的照片就是一行 4MB 的 TEXT，超过 Android `CursorWindow` 的 2MB 上限 → 该会话读不出来、图片被兜底逻辑截断后消失。现在发送路径与导入路径一样先落盘再写库，并登记到 `attachment_refs`。 |
| **修复：删消息不回收图片文件** | 单条删除消息时，被它独占的图片文件与引用登记会一并清掉，不再默默占着存储。 |
| **修复：主线程做 Keystore 解密** | 设置流改为在 IO 上读取，配置变化时不再在组合线程里过 Binder 解密。 |
| **修复：多图查看起始页错位** | 落盘图片的 `data` 是空串，原先只按「文件名 + data」定位当前图，两张同名图会翻错页；现在连 `path` 一起比。 |
| 其它 | 应用名改为 myDeepSeek；README 重写；新增 GitHub Actions 构建流水线；新增同步合并逻辑的单元测试。 |

## 特性

沿袭上游，仍然保留：

- **多服务端**：任意 OpenAI 兼容端点，内置 DeepSeek 与 OpenCode Go 配置；DeepSeek 官方端点可走 **Anthropic 兼容**扩展通道（智能搜索 / 链接阅读 / 正文引用 / 时间地点提醒）。
- **流式输出**：SSE 逐字渲染，Markdown 实时渲染，定稿前后版式不跳。
- **思考区**：`reasoning_content` 折叠展示，只计真实思考时长。
- **继续生成**：被停止或被输出上限截断的回复可以接着写。
- **数学公式 / Mermaid**：Compose 原生渲染公式；Mermaid 随包离线渲染，可切回源码。
- **消息树**：`parentId` 组织分支，"重新生成"不删历史；可切换版本、会话间分叉。
- **上下文管理**：CJK 加权 token 估算、输入栏占用圆环、滚动压缩。
- **附件**：拍照 / 相册 / 文件三个入口，均无需额外权限；支持图片、纯文本、docx、PDF。
- **数据出口**：JSON 备份、ZIP 归档（备份 + 每个会话的 Markdown 文稿）、Markdown 导出；可导入本应用备份、Chatbox 备份、DeepSeek 官方导出。
- **外观 / 语言**：深色模式、主题色、字号倍率、中英双语。

新增：

- **云端同步（WebDAV）**：见下文「云端同步」。
- 历史抽屉按月分组。

## 快速开始

### 环境要求

- **JDK 21**（Android Studio 自带的 JBR 21 正好；17 能编译，但跑不了单元测试 ——
  `highlights` 依赖是 Java 21 字节码）
- Android SDK platform 37、build-tools 37.0.0
- Gradle 由 wrapper 自动下载

### 本地构建

```bash
git clone <你的仓库地址> myDeepSeek
cd myDeepSeek
./gradlew assembleDebug          # 产物：app/build/outputs/apk/debug/
```

Windows（PowerShell）：

```powershell
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat assembleDebug
```

跑单元测试：

```bash
./gradlew testDebugUnitTest
```

## 用 GitHub Actions 构建 APK

仓库里已经放好 [`.github/workflows/android.yml`](.github/workflows/android.yml)：

- 触发条件：推到 `main`、开 PR、打 `v*` 标签、或手动 `Run workflow`
- 流程：装 JDK 17 + Android SDK → 还原签名密钥 → 单元测试 → `assembleRelease` → 上传 APK 产物
- 打 `v1.2.0` 这类标签时会自动创建 Release 并把 APK 挂上去

产物在 Actions 运行详情页底部的 **Artifacts** 里（`myDeepSeek-apk-<构建序号>`）。

> 流水线里的 SDK 版本（`platforms;android-37`、`build-tools;37.0.0`）必须和
> `app/build.gradle.kts` 的 `compileSdk` / `buildToolsVersion` 保持一致。升级 AGP 时两处一起改。

## 配置正式签名密钥

**为什么必须做**：Android 只允许「同一包名 + 同一签名」的 APK 覆盖安装。用 debug 签名发的包，
将来换成正式签名时用户必须卸载重装，**应用数据会一起消失**。所以第一次发布前就把密钥定下来，
并把它备份好 —— 密钥丢了就再也发不出同一个应用的更新。

### 1. 生成密钥（一次性，只做一次）

在任意目录执行（`keytool` 在 JDK 的 `bin` 下）：

```bash
keytool -genkeypair -v \
  -keystore mydeepseek-release.jks \
  -alias mydeepseek \
  -keyalg RSA -keysize 4096 -validity 10000
```

会依次问你：密钥库口令、姓名与组织（可随便填）、以及最后的确认。
记下 **keystore 口令**、**alias（`mydeepseek`）**、**key 口令**（通常与 keystore 口令相同）。

> 建议把 `mydeepseek-release.jks` 放到**仓库之外**的加密备份里（密码管理器 / 私有网盘），
> 并在密码管理器里同时记下三个口令。这个文件一旦丢失，无法补救。
>
> 另外：下面生成的 `keystore.b64` 是同一个文件的**文本形态**（只受口令保护），
> 同样不能提交、不能外传。贴进 GitHub Secret 之后，本地那份建议删掉。

### 2. 转成 base64（给 GitHub Secrets 用）

macOS / Linux：

```bash
base64 -i mydeepseek-release.jks | tr -d '\n' > keystore.b64
```

Windows PowerShell：

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes("mydeepseek-release.jks")) | Set-Content keystore.b64 -NoNewline
```

### 3. 写进 GitHub Secrets

仓库 → **Settings → Secrets and variables → Actions → New repository secret**，添加四个：

| Secret 名称 | 值 |
|---|---|
| `KEYSTORE_BASE64` | 上一步 `keystore.b64` 的**全部内容**（一长串，不要换行） |
| `KEYSTORE_PASSWORD` | keystore 口令 |
| `KEY_ALIAS` | `mydeepseek` |
| `KEY_PASSWORD` | key 口令 |

添加完之后，下一次构建会自动生成 `keystore/release.jks` 与 `keystore.properties`，用正式密钥签名。
**没配这四个 Secret 时流水线不会失败**，会打一个 debug 签名的 release 包并在日志里给出警告 ——
方便 fork 之后先跑通流程。

### 4. 本地构建正式包（可选）

在项目根目录建 `keystore.properties`（已在 `.gitignore` 里，不会入库）：

```properties
storeFile=keystore/mydeepseek-release.jks
storePassword=你的keystore口令
keyAlias=mydeepseek
keyPassword=你的key口令
```

把 `.jks` 放到 `keystore/` 目录下，然后：

```bash
./gradlew assembleRelease
```

### 5. 校验签名

```bash
# 用 Android SDK 的 apksigner
$ANDROID_HOME/build-tools/37.0.0/apksigner verify --print-certs app/build/outputs/apk/release/app-release.apk

# 或者直接看密钥库
keytool -list -v -keystore mydeepseek-release.jks -alias mydeepseek
```

两次输出的 SHA-256 指纹一致，就说明这个包确实由你的密钥签名。

## 云端同步（WebDAV）

设置 → 账户 → **云端同步**，填四项：

| 项 | 说明 |
|---|---|
| 服务器地址 | 例如 `https://dav.jianguoyun.com/dav/` |
| 账号 | 网盘账号 |
| 应用密码 | 网盘的「应用密码」，**不是**登录密码 |
| 远程文件 | 默认 `myDeepSeek/backup.json`；父目录不存在会自动创建 |

### 坚果云

1. 网页登录坚果云 → 右上角账户信息 → **安全选项** → 「第三方应用管理」→ 添加应用 → 得到一串应用密码
2. 服务器地址填 `https://dav.jianguoyun.com/dav/`
3. 账号填注册邮箱，密码填刚生成的应用密码
4. 回来点「测试连接」，通过后点「立即同步」

### Nextcloud / 群晖 / 其它

- Nextcloud：`https://<你的域名>/remote.php/dav/files/<用户名>/`
- 群晖 WebDAV Server：`https://<地址>:5006/`（依套件设置而定）
- 只要服务端支持 WebDAV + Basic 认证即可；客户端只发 `PUT` / `GET` / `MKCOL` / `PROPFIND`
- **只支持 `https://`**：Android 9 起默认禁止明文 HTTP，本应用没有开 `usesCleartextTraffic`。
  局域网自建（群晖、树莓派）请配好证书，或前面挂一层 https 反代。

### 网盘上的文件长什么样

```
myDeepSeek/
├─ sync.jsonl          第 1 行是头（格式 / 版本 / 预设），之后每行一个会话
└─ media/
   ├─ 3f2a…c1.jpg      图片按内容哈希命名（sha256 前 32 位）
   └─ 8b9d…4e.png
```

**图片不进 JSON**：一张 3MB 的照片 base64 之后是 4MB，塞进 JSON 会让单行变成几兆，
读写都得整段进内存 —— 这正是之前同步 OOM 的原因。现在 JSON 里只留文件名，
字节单独上传；文件名就是内容哈希，所以"本地有同名文件"就等于"内容一致"，天然去重。

### 同步是怎么做的

1. **流式下载**远端 `sync.jsonl` 到缓存文件（边读边写，不整包进内存）
2. **逐行**读：远端 `updatedAt` 更新的会话写进一个临时文件，同时把缺的图片拉下来；
   本地没有这张图就拉一次，名字对得上就不重复传
3. 先删掉要被覆盖的本地会话（草稿会保留并写回），再**逐条流式导入**
4. 反方向同理：逐会话读库 → 逐行写载荷文件 → 先补传缺的图片 → 流式上传载荷

内存峰值只跟**最大的那一个会话**有关，与库有多大无关。

### 限制（务必知道）

- **删除不传播**：A 设备删掉的会话，会在 B 设备下次同步时被重新带回来（除非两台都删过）。
  做对需要墓碑记录，代价是给消息表加一套"已删除但保留"的状态，对个人使用不划算。
- **删除也不回收网盘上的图片**：图片是内容寻址的独立文件，删会话不会动 `media/`。
  好处是不会误删别的会话还在用的图；代价是网盘会留一些孤儿文件（每张几 MB 以内），
  需要时手动清理即可。
- **原件缺失的图片不会同步**：如果本地图片文件被清掉（清应用数据、换机没带文件），
  那条附件会以"无图"的形式同步过去，不会把坏数据传上去。
- **同一时刻只跑一次**：自动同步与手动点击会互斥，不会并发写。
- **网盘上的是明文**：`sync.jsonl` 是可直接读的 JSON，图片也是原图。如果介意，
  请只用自己信任的网盘，或不要开启自动同步。

## 数据与隐私

- API Key 与 WebDAV 密码以 Android Keystore 的 AES-256-GCM 保存密文，密钥按 UID 隔离，仓库不含任何密钥。
- 对话请求从设备直连所配置的端点，无第三方中转。
- 同步只在你配置的网盘与设备之间发生，本应用不提供任何中转服务。
- 数据库迁移使用显式 `Migration`，不会 `fallbackToDestructiveMigration` —— 升级不会静默清库。

## 技术栈

| 项 | 版本 |
|---|---|
| Gradle | 9.7.1 |
| AGP | 9.4.1（内置 Kotlin） |
| Kotlin | 2.4.10 |
| Compose BOM | 2026.09.00 |
| compileSdk / targetSdk / minSdk | 37 / 37 / 26 |
| Room / OkHttp / kotlinx.serialization | 2.8.5 / 5.5.0 / 1.11.0 |

完整依赖见 [`gradle/libs.versions.toml`](gradle/libs.versions.toml)。

## 项目结构

```text
app/src/main/java/com/simplechat/app/
├─ data/          仓库层：会话粘合、备份导入导出、附件处理、Keystore 加解密、token 估算
│  ├─ import/     Chatbox / DeepSeek 备份、docx 抽文本、图片尺寸
│  └─ sync/       WebDAV 同步：客户端（PUT/GET/MKCOL/PROPFIND）+ 合并决策（纯函数，有单测）
├─ db/            Room：实体、DAO、显式迁移、消息树纯函数
├─ net/           DTO、SSE 客户端与行解析、Provider 与模型能力表
└─ ui/
   ├─ chat/       对话页：消息列表（含图片预览）、输入栏、各面板、编辑弹窗
   ├─ history/    会话抽屉（按月分组）与全文搜索
   ├─ settings/   设置页（API 配置、数据管理、云端同步、外观、关于）
   ├─ markdown/   Markdown / 公式 / Mermaid 渲染
   ├─ common/     控件与视觉 token
   └─ theme/      颜色、字体、形状
```

## 已知限制与后续

- 同步不支持删除传播、也不回收网盘上的孤儿图片（见上）
- 「从设置返回对话」不会自动触发同步；只有手动按钮与启动时自动同步
- 备份导入的「覆盖同名」模式不会回收被替换会话的图片文件（已知磁盘泄漏，量很小）
- 「导出所有数据 / 导入」仍是**整份 JSON**（带 base64 图片），几个 GB 的库会有 OOM 风险；
  同步已经改流式，导出还没（那是另一条链，改动面更大）
- 数据库 `conversations.provider/model` 是只写不读的死列，删列会触发外键级联风险，暂留

## 代码审查

见 [`CODE_REVIEW.md`](CODE_REVIEW.md)：按严重程度列出发现的问题、已修复项与保留的风险点。

## 许可证与致谢

基于 MIT 许可证开源（见 [LICENSE](LICENSE)）。

上游项目：[Pokanshuu/Simple-Chat](https://github.com/Pokanshuu/Simple-Chat)。
本仓库是它的个人定制分支，不是上游官方版本；问题请提到本仓库。
