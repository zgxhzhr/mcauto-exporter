# mcauto-exporter 架构与功能设计

> 目标版本：Minecraft 1.21.1 / Fabric
> 许可证：MIT（仅覆盖本仓库原创代码）
> 仓库：https://github.com/zgxhzhr/mcauto-exporter
> 状态：设计稿，先不写代码

---

## 1. 为什么要有这个工具

现有工具各自缺一块：

| 工具 | 版本 / 加载器 | 能做什么 | 问题 |
|---|---|---|---|
| LetMeSeeSee（LMS） | 1.21.1 NeoForge | 物品 + 实体，站方格式 | 只覆盖 NeoForge，LGPLv3 |
| IconRenderer（IRR） | 1.20.1 Fabric 等 | 物品 + 实体，自触发 | GPL-3.0，1.21.1 Fabric 没有可用构建 |
| ZHR_SuperDBG 图标导出 | 1.20.1 Forge | 物品 32/128，一帧一个 | 只有 1.20.1 Forge，输出是通用 icons.json，不是站方格式 |
| mcauto 流水线 | 外部 Python + Java agent | 启动、进世界、收产物 | Fabric 运行时是 intermediary，agent 反射全部失效；只能靠模组自己触发 |

所以 1.21.1 Fabric 这一格是空的，而且现有实现要么是 copyleft，要么把「人用」和「agent 用」做成了两套东西。

mcauto-exporter 的定位：

1. **一个客户端模组**，不依赖外部 Java agent、不反射游戏类；
2. **人类能用**：游戏内命令 + 简单界面，选命名空间、勾类型、看进度；
3. **agent 能用**：一个 job 文件进，一个 status/events/manifest 出，哨兵文件与退出码稳定；
4. **MIT**：核心与渲染原创；ZHR_SuperDBG 的图标导出逻辑为自有代码，确认可按 MIT 重新授权；LMS / IRR 只做行为与 API 参考，不复制代码；
5. **JSON 直接沿用 LMS**：物品 `<ns>.json`、实体 `<ns>_entity.json` 的字段与 JSONL 形状不改，现有下游脚本不用换解析；
6. **图标以游戏内结果为准**：物品/方块/实体走真实渲染，BUFF/DEBUFF 走原始贴图逐像素导出，避免「放大模糊」。

非目标：不做服务端导出、不做自动上传百科、不做页面正文生成、不做通用调试器。

---

## 2. 总体架构

```text
┌──────────────────────────── mcauto-exporter (Fabric client mod) ────────────────────────────┐
│                                                                                             │
│  control/                          core/                              render/                │
│  ┌──────────────────────┐         ┌──────────────────────────┐       ┌───────────────────┐  │
│  │ JobLoader            │         │ ExportJob / ExportPlan   │       │ RenderScheduler   │  │
│  │  -Dmcauto.job        │────────▶│ ExportState (状态机)      │──────▶│ 每帧一个 target    │  │
│  │ config/*.json        │         │ EventLog / StatusWriter  │       │ ItemRendererPort  │  │
│  │ /mcauto 命令          │         │ Sentinel / ExitCode      │       │ EntityRendererPort│  │
│  │ GUI Screen           │         │ TargetDeduper / Cache    │       │ SpriteExtractor   │  │
│  └──────────────────────┘         └──────────────────────────┘       │ QualityProbe      │  │
│             │                                ▲                       └─────────┬─────────┘  │
│             ▼                                │                                 │            │
│  scan/                               writer/                                    ▼            │
│  ┌──────────────────────┐         ┌──────────────────────────┐       ┌───────────────────┐  │
│  │ ItemScanner          │         │ LmsJsonWriter            │       │ OffscreenFbo      │  │
│  │ BlockItemScanner     │────────▶│  <ns>.json               │       │ 32 / 128 PNG      │  │
│  │ EntityScanner        │         │  <ns>_entity.json        │       │ 原子落盘           │  │
│  │ EffectScanner        │         │  <ns>_effect.json        │       │ overrides         │  │
│  │ LanguageIndex        │         │ manifest / status        │       └───────────────────┘  │
│  └──────────────────────┘         └──────────────────────────┘                              │
│                                                                                             │
└─────────────────────────────────────────────────────────────────────────────────────────────┘
                    ▲ job.json / JVM args                         │ status.json / events.jsonl
                    │                                              ▼
              external agent（启动器 / 编排脚本）              产物目录（LMS 字段 JSONL + PNG）
```

分层原则：

- **core 不依赖 Minecraft**：job、状态机、事件、质量指标、原子写、LMS JSON 序列化都可以纯 JVM 单测；
- **render 与 scan 只依赖 Minecraft 客户端 API**，不依赖具体加载器；
- **platform/fabric-1.21.1 只做接线**：ClientModInitializer、事件注册、命令、界面；
- 以后加 1.20.1 Fabric 或 1.21.1 NeoForge，只新增 platform 子模块，core 与 writer 不动。

建议的 Gradle 结构：

```text
mcauto-exporter/
├─ LICENSE                     MIT
├─ NOTICE                      第三方依赖与行为参考声明
├─ README.md                   人类使用
├─ AGENTS.md                   agent 契约（job/status/哨兵/退出码）
├─ docs/
│  ├─ architecture.md
│  ├─ job-schema.md
│  └─ lms-json.md              物品 / 实体 / 效果的字段说明
├─ core/                       纯 Java：job、plan、state、event、quality、io、LMS JSON writer
├─ platform-fabric-1.21.1/     Fabric 入口、事件、命令、GUI
└─ fixtures/                   回归用的小样例与 golden 数据
```

---

## 3. 运行时流程

### 3.1 状态机

```text
INIT
  → JOB_READY         读到 job 或命令
  → WAITING_WORLD     等 client.player / client.world
  → WARMING           等 N tick，丢弃第一帧渲染
  → SCANNING          枚举注册表、解析语言、生成计划
  → RENDERING         渲染线程逐帧消费
  → FLUSHING          写 LMS JSON / manifest / status
  → DONE
       ↘ FAILED / CANCELLED / TIMEOUT
```

每个状态迁移都写一条 `events.jsonl`，同时更新 `status.json`。

### 3.2 渲染主循环

1. `ClientTickEvents.END_CLIENT_TICK`：只推进状态机，不碰 GL。
2. `HudRenderCallback`（GUI 阶段末尾）：从队列取 **一个** target，执行渲染并读回像素。
3. 渲染完成：转 PNG、算质量指标、原子写盘、更新计数。
4. 队列空：flush，写哨兵，按配置退出游戏。

为什么必须在 GUI 阶段：ZHR_SuperDBG 实测过，在命令回调或 `WorldRenderEvents` 里读回是全透明；GUI 阶段才有正确的正交投影和渲染状态。这个坑直接继承，不重新踩。

### 3.3 每帧只做一个的理由

- 读回 `glReadPixels` 会阻塞 GPU；批量做会让客户端看起来卡死；
- 单帧单目标便于取消、便于进度、便于定位是哪一个 target 崩；
- 1000 个物品在 60 FPS 下约 17 秒，可接受；确需加速可配置 `maxPerFrame`，默认 1。

---

## 4. 图标策略（这次的重点）

### 4.1 分类型取图

| 类型 | 主路径 | 兜底 | 说明 |
|---|---|---|---|
| 物品 | `DrawContext.drawItem` 离屏渲染 32 / 128 | 模型 layer0 贴图静态导出 | 保留附魔、tint、3D 模型、BEWLR |
| 方块（BlockItem） | 同上，物品栏 3D 等轴测 | 方块模型静态渲染 | 站方要求立体图标，不能交单面贴图 |
| 实体 | 离屏 `EntityRenderDispatcher` 渲染 128 | overrides / 标 `noVisual` | 需要真实世界与实体实例；小图标可选 32 |
| BUFF/DEBUFF | 直接读 `assets/<ns>/textures/mob_effect/<id>.png` 原始像素，NEAREST 到 32 / 128 | 从图集 sprite 取原始像素，再 NEAREST | **禁止**把 16×16 平滑放大 |
| 附魔/其他 | 后续扩展 | — | v1 不做 |

### 4.2 BUFF/DEBUFF 的硬规则

19281 被退回的原因就是效果图标用了平滑插值。设计上把它固化成不变量：

1. 效果图标**不走渲染管线**，走资源文件；
2. 原图小于目标尺寸时用 `NEAREST`，大于目标尺寸时用 `LANCZOS`；
3. 写出前做颜色数校验：`128` 图的颜色数必须等于原图颜色数（允许 0 差异）；
4. 颜色数超阈值直接标 `suspectReason: "sprite-upscale-blur"`，并在 status 里计数，agent 可以据此失败退出；
5. `icon32` 与 `icon128` 都从原图各自生成，不从 32 再放大。

### 4.3 物品图标的清晰度

- 默认走游戏内渲染，保持「游戏里看到的样子」；
- 对 `item/generated`、单 layer、无 tint 的纯平面物品，可配置 `spriteItems: auto`，直接贴图 NEAREST，边缘更硬；
- 带 tint、多层、3D 模型、BEWLR 的物品一律走渲染；
- 渲染结果做锐度检测，边缘过渡色异常时标 `suspect`，不静默交付。

### 4.4 质量指标

每个目标都产出：

```json
{
  "blank": false,
  "coverage": 0.41,
  "colorCount": 37,
  "bboxFill": 0.63,
  "aspect": 1.0,
  "sharpness": 0.82,
  "suspect": false,
  "suspectReasons": []
}
```

用途：

- 空白、全屏单色、扁平四边形、颜色数异常 → 自动标记；
- 实体按 `flat-quad` 自动换 90° 再渲一次；
- 下游按 `suspect` 过滤，人工只补真正可疑的；
- 回归测试直接断言「效果图颜色数 = 原图颜色数」。

---

## 5. 数据模型

### 5.1 物品与实体沿用 LMS，效果自定义扩展

`format: "lms"` 的含义：物品、实体与 LMS 的「按 mcmod 格式导出」逐字段一致；LMS 没有效果导出，效果文件是本仓库自己补的扩展，但字段命名尽量与实体文件同构，下游不用为它单独写一套解析。

- 物品：`<namespace>.json`，JSONL，每条一个物品对象，按 `registerName` 排序；
- 实体：`<namespace>_entity.json`，JSONL，字段与 LMS 的 `McAutoBridge` 完全一致；
- 效果：`<namespace>_effect.json`，JSONL，自定义字段，见下方；
- 图标：base64 内嵌在条目里，同时落一份 PNG 到 `_data/`，方便人看和补图。

内部可以有 Java 记录类（`ItemRecord`、`EntityRecord`、`EffectRecord`）方便单测，但它不是对外契约；写盘时直接序列化成 LMS 字段或效果扩展字段。

#### 物品 `<namespace>.json`

字段与 LMS `itemDataMcMod` 一致：

```json
{"name":"红宝石","englishName":"Ruby","registerName":"example:ruby","type":"Item","maxStacksSize":64,"maxDurability":0,"CreativeTabName":"Example","OredictList":"[c:gems]","smallIcon":"<base64 32×32>","largeIcon":"<base64 128×128>"}
```

- `type` 取 `Block` / `Item`，BlockItem 为 `Block`；
- `OredictList` 保持 LMS 的字符串形式，不是数组；
- `CreativeTabName` 取物品所在创造标签页的显示名，找不到写 `未知`；
- `smallIcon` / `largeIcon` 分别来自 32 / 128 渲染结果，不从 32 放大到 128。

#### 实体 `<namespace>_entity.json`

字段与 LMS 的 `McAutoBridge` 一致：

```json
{"name":"绵羊骑士","Englishname":"Sheep Knight","mod":"example","registerName":"example:sheep_knight","Icon":"<base64 128×128>","IconSmall":"<base64 32×32>","iconSource":"render","noVisual":false,"suspect":false,"iconCoverage":0.33,"iconColorStd":42.1,"iconAspect":1.0,"iconFill":0.63,"iconSizeRatio":0.71,"renderer":"SheepKnightRenderer"}
```

- `Englishname` 的 `E` 大写，按 LMS 原样保留；
- 无可见模型时 `Icon` / `IconSmall` 为空串、`noVisual: true`；
- 可疑时带 `suspect: true`、`suspectReason`；
- `iconSource` 取 `render` / `override` / `cache`。

#### 效果 `<namespace>_effect.json`（本仓库扩展）

LMS 没有效果导出。这里不发明一套完全独立的格式，而是借实体文件的形状：`Icon` / `IconSmall` / `Englishname` 三个命名保持一致，新增效果专属字段。设计目标是「站点导入时只做改名，不做语义判断」。

```json
{
  "name": "破伤风",
  "Englishname": "Tetanus",
  "mod": "sheeps_tools",
  "registerName": "sheeps_tools:tetanus",
  "category": "DEBUFF",
  "effectCategory": "HARMFUL",
  "color": 7829367,
  "instant": false,
  "Icon": "<base64 128×128>",
  "IconSmall": "<base64 32×32>",
  "iconSource": "sprite",
  "iconMode": "nearest",
  "iconColorCount": 10,
  "iconExpectedColorCount": 10,
  "nameSource": "zh_cn",
  "warnings": []
}
```

字段说明：

| 字段 | 类型 | 必填 | 说明 |
|---|---|---|---|
| `name` | string | 是 | `zh_cn` 名称；没有中文时写空串，不拿英文顶替 |
| `Englishname` | string | 是 | `en_us` 名称；与实体文件一致，`E` 大写 |
| `mod` | string | 是 | 命名空间 |
| `registerName` | string | 是 | `namespace:path` |
| `category` | string | 是 | 站点口径：`BUFF` / `DEBUFF` |
| `effectCategory` | string | 是 | 注册表口径：`BENEFICIAL` / `HARMFUL` / `NEUTRAL` |
| `color` | int | 是 | 效果颜色，注册表原值 |
| `instant` | bool | 是 | 是否为瞬间效果 |
| `Icon` | string | 是 | 128×128 base64；缺失时为空串 |
| `IconSmall` | string | 是 | 32×32 base64；缺失时为空串 |
| `iconSource` | string | 是 | 固定 `sprite`，表示走原图导出，不是渲染 |
| `iconMode` | string | 是 | `nearest`；放大方式留痕，便于验收 |
| `iconColorCount` | int | 是 | 128 图实际颜色数 |
| `iconExpectedColorCount` | int | 是 | 原图颜色数；与上一项不等即判模糊 |
| `nameSource` | string | 是 | `zh_cn` / `en_us` / `none`，告诉下游该用哪个名字 |
| `warnings` | array | 是 | 空数组或字符串列表 |

`category` 的映射规则：`BENEFICIAL` → `BUFF`，`HARMFUL` → `DEBUFF`，`NEUTRAL` 默认 `BUFF`，可在配置里改成 `DEBUFF` 或 `skip`。这样导出器只负责事实，站点分类策略不写死。

下游要站点导入格式时，只做字段改名：

```text
name        → name
Englishname → ename（name 为空时，按百科规则把英文放到 name、ename 置空）
registerName→ regname
category    → category
（固定）     → type: "BUFF/DEBUFF"
IconSmall   → icon32
Icon        → icon128
```

`html`、`remark` 不在导出器里生成，仍由页面写作流程负责。

语言：默认抓 `zh_cn`、`en_us`，可配置数组；缺失时 `name` 或 `Englishname` 写空串，并在同一行补 `nameSource: "en_us"` 之类的标记，方便下游按百科规则处理「模组无中文名」。

### 5.2 产物目录

```text
<output>/
├─ <namespace>.json              物品，JSONL，LMS 字段
├─ <namespace>_entity.json       实体，JSONL，LMS 字段
├─ <namespace>_effect.json       效果，JSONL，本仓库扩展
├─ _data/<namespace>/<path>.png  与 base64 对应的 PNG，方便目视
├─ _icons/<namespace>/<path>.png 人工覆盖图，LMS 同名约定
├─ manifest.json                 总清单（模组、哈希、耗时、数量、异常）
├─ status.json                   最后一次状态（人看也行，agent 必读）
└─ events.jsonl                  全过程事件流
```

落盘规则：先写 `<file>.tmp`，`fsync` 后原子重命名；同目录写入互不覆盖；中断后不留下半截 JSON。

---

## 6. 人类使用设计

### 6.1 命令

v0.1 已实现：

```text
/mcauto            打开导出界面
```

后续补齐：

```text
/mcauto export <namespace> [items] [entities] [effects] [sizes=32,128]
/mcauto cancel
/mcauto status
/mcauto open
```

- 客户端命令，走 Fabric 的客户端命令注册，不影响服务器；
- `export` 立刻返回，进度在 HUD 角落显示；
- 完成后聊天栏给产物路径、成功/失败/可疑数量；
- `cancel` 只清队列，不删已写产物。

### 6.2 界面

v0.1 已实现一个足够详细的单页界面，不做多层菜单：

**左侧：命名空间**

- 列出所有含物品 / 实体 / 效果的模组命名空间；
- 显示模组名与 namespace id；
- 点击切换选中，支持多选，滚轮翻页；
- 默认选中第一个命名空间。

**右上：导出模式**

一个按钮循环切换三种整批模式：

1. 完整导出（JSON + 图标）：渲染并写 `_data` PNG，最后写 LMS JSON；
2. 只导出 JSON：不渲染，`smallIcon` / `largeIcon` 等字段为空；
3. 只导出图标：只写 `_data` PNG，不写 JSON。

**右中：类型与尺寸**

- 物品 / 方块（合并为一个开关，方块走 BlockItem 渲染）；
- 实体；
- 效果；
- 32×32、128×128 两个尺寸可分别开关；
- 类型和尺寸都至少选一项，否则开始按钮给出提示。

**单个图标**

「导出单个图标…」进入条目选择页：

- 顶部搜索框，按中文名、英文名、注册名过滤；
- 列表显示 `[物品]/[实体]/[效果]`、名称、注册名；
- 点击任意条目，立即只导出该条目的图标，尺寸沿用主界面的勾选；
- 完成后回到主界面，进度区显示结果。

**进度与结果**

- 运行中显示当前条目、进度条、`已完成 / 总数`、最后四条日志；
- 开始按钮在运行期间禁用，避免重复任务；
- 完成后显示成功、失败、无可见模型、可疑数量；
- 「打开目录」直接打开输出根目录；
- 错误信息显示在按钮上方，不弹独立对话框。

**底部**

- 输出路径常驻显示；
- 开始导出 / 打开目录 / 关闭。

### 6.3 配置

`config/mcauto-exporter.json`，命令行/gui/job 的优先级：job > 命令 > gui > 配置文件 > 默认值。

```json
{
  "outputDir": "mcauto-export",
  "sizes": [32, 128],
  "languages": ["zh_cn", "en_us"],
  "targets": ["items", "blocks", "entities", "effects"],
  "iconMode": { "items": "render", "effects": "sprite" },
  "spriteItems": "auto",
  "embedIcons": false,
  "cache": true,
  "maxPerFrame": 1,
  "warmupTicks": 40,
  "worldTimeoutSeconds": 120,
  "exitAfterExport": false,
  "format": "lms",
  "effectNeutral": "BUFF"
}
```

---

## 7. Agent 使用设计

### 7.1 原则

- **不反射游戏类**：Fabric 运行时是 intermediary，外部 agent 按 mojmap 反射必坏；
- **不点 GUI**：agent 只负责起进程、等哨兵、读文件、杀进程；
- **文件即协议**：job 进，status/events/manifest 出；
- **可重入**：同一 job 重复跑，已完成的 target 可跳过；
- **失败可诊断**：退出码 + `status.json.error` + `events.jsonl` 最后一条。

### 7.2 job.json

```json
{
  "schema": 1,
  "jobId": "20261120-19281-effects",
  "namespaces": ["sheeps_tools"],
  "targets": ["effects"],
  "sizes": [32, 128],
  "output": "D:/export/19281",
  "format": "lms",
  "embedIcons": false,
  "overrides": "D:/export/overrides",
  "cache": true,
  "exitAfterExport": true,
  "timeoutSeconds": 300
}
```

启动方式：JVM 参数 `-Dmcauto.job=<绝对路径>`。没有 job 时模组什么都不做，当普通模组加载。

### 7.3 状态与事件

`status.json`：

```json
{
  "schema": 1,
  "jobId": "20261120-19281-effects",
  "state": "RENDERING",
  "startedAt": "2026-11-20T12:00:00Z",
  "updatedAt": "2026-11-20T12:00:12Z",
  "counts": { "total": 12, "done": 7, "failed": 0, "skipped": 0, "suspect": 0 },
  "current": { "kind": "effect", "id": "sheeps_tools:tetanus" },
  "output": "D:/export/19281"
}
```

`events.jsonl` 每行：

```json
{"ts":"...","event":"target.done","kind":"effect","id":"sheeps_tools:tetanus","ms":41,"suspect":false}
```

### 7.4 哨兵与退出码

- 成功：写 `_mcauto_done`（内容含 jobId、耗时、数量），进程退出码 0；
- 失败：写 `_mcauto_error.txt`（异常栈 + 最后事件），退出码非 0；
- 兼容旧流水线：保留 `_mcauto_done` / `_mcauto_error.txt` 文件名；
- 退出码建议：0 成功 / 2 job 非法 / 3 等世界超时 / 4 部分失败 / 5 致命异常。

### 7.5 agent 侧职责边界

| 模组负责 | agent 负责 |
|---|---|
| 读 job、扫描、渲染、写产物、写状态 | 准备客户端、拼 mods、生成/选择世界、启动 JVM |
| 世界就绪检测与超时 | 超时后杀进程、收集日志 |
| 图标质量判定与可疑标记 | 把可疑条目交给人工/下一轮 |
| 原子写与可重入 | 产物归档、上传、校验哈希 |

世界准备建议沿用现有 mcauto 流水线：官方服务端生成超平坦存档，客户端用 `--quickPlaySingleplayer` 直接进；1.21.1 支持该参数。没有世界时物品/效果理论上可导，但实体必须世界，v1 统一要求世界，减少分支。

---

## 8. 关键实现点（1.21.1 Fabric）

| 事项 | 方案 |
|---|---|
| 入口 | `ClientModInitializer`，`fabric.mod.json` 设 `"environment": "client"` |
| 事件 | `ClientTickEvents.END_CLIENT_TICK` 推进状态；`HudRenderCallback` 执行渲染；`ClientLifecycleEvents.CLIENT_STOPPING` 收尾 |
| 命令 | Fabric 客户端命令注册（`ClientCommandRegistrationCallback`） |
| 离屏缓冲 | `SimpleFramebuffer(size, size, true)`，带深度；渲染后恢复主帧缓冲 |
| 投影 | 渲染前设 `size×size` 正交投影，结束后恢复 GUI 投影；这是 ZHR_SuperDBG 已踩过的坑 |
| 物品 | `DrawContext.drawItem(stack, -8, -8)`，矩阵平移到中心、缩放 `size/16` |
| 实体 | `EntityType.create(client.world)` 造实例，设位置/朝向，`EntityRenderDispatcher.render(...)`，光照给最大亮度 |
| 效果 | 资源管理器直接读 `textures/mob_effect/<id>.png`，不经过图集缩放 |
| 语言 | 读 `lang/zh_cn.json`、`lang/en_us.json`，合并后按 descriptionId 取 |
| 命名空间 | `FabricLoader.getModContainer(ns)` 取模组信息与 jar 路径；注册表按 namespace 过滤 |
| 无 Mixin | v1 尽量零 Mixin；效果图集兜底若必须访问 sprite 内部，再用最小 Mixin 并单独说明 |
| Java | 21；Fabric Loader 0.16+；依赖 Fabric API（Apache-2.0，不打包） |

---

## 9. 质量与异常处理

| 场景 | 行为 |
|---|---|
| 物品渲染全透明 | 标 `blank`，尝试 sprite 兜底；仍空则 `noVisual`，不写空图 |
| 实体渲染全透明 | 标 `noVisual`，通知下游不要用 |
| 实体画成扁平四边形 | 自动换 90° 重渲；仍可疑则 `suspect` |
| 实体渲染器依赖乘客/坐骑/状态 | 记录 renderer 类名与 `suspectReason`，允许 overrides 覆盖 |
| 效果贴图缺失 | 先找图集 sprite；再缺则用原版 missing texture 并写明原因 |
| 效果图颜色数异常 | 判 `sprite-upscale-blur`，本次 job 可配置为直接失败 |
| 方块只有平面贴图 | 不允许；必须走 BlockItem 渲染 |
| 输出目录不可写 | job 预检失败，退出码 2，不启动渲染 |
| 世界超时 | 退出码 3，`status.state=TIMEOUT` |
| 渲染中途异常 | 单条失败不影响队列；累计后 `PARTIAL`，退出码 4 |

---

## 10. 缓存与覆盖

- **缓存键**：`sha256(模组 jar) + 导出器版本 + target id + kind + size + iconMode`；
- 命中缓存直接复制 PNG 与质量记录，不重新渲染；
- 缓存目录 `<output>/.cache/`，可 `--no-cache`；
- **overrides** 优先级最高，沿用 LMS 的 `_icons` 约定：`_icons/<namespace>/<path>.png`（128）、`<path>_32.png`（可选）；效果的 kind 目录同理；
- manifest 记录 `iconSource: render | sprite | override | cache`，人工补图后可追溯。

---

## 11. 测试与验收

### 11.1 单元测试（不启动游戏）

- job schema 校验、优先级合并、非法路径；
- 状态机迁移与事件序列；
- 原子写与中断恢复；
- 质量指标（空白、颜色数、锐度）；
- LMS JSON 字段名与顺序，逐字对比 LMS 样例；缺名回退。

### 11.2 集成测试（本机 GPU）

- `runClient` + `-Dmcauto.job=fixtures/job-effects.json`；
- 固定样本：16×16 效果贴图 12 张，断言 128 图颜色数等于原图；
- 固定样本：BlockItem、平面物品、3D 物品、实体各 1 个，做感知哈希对比；
- 固定样本：缺失贴图、全透明实体、扁平实体，断言 `suspect` / `noVisual` 正确。

### 11.3 验收口径

1. 人类：进世界，`/mcauto export <ns>`，看到进度，完成后能直接拿站方 JSON；
2. agent：起客户端 → 等 `_mcauto_done` → 读 `status.json` → 产物齐全 → 退出码 0；
3. 效果图：颜色数与原图一致，无平滑过渡；
4. 方块：立体图标；实体：128 渲染图；无图实体明确标记；
5. `audit` 类下游检查零阻断。

---

## 12. 许可证与合规

- 本仓库原创代码 MIT；
- **ZHR_SuperDBG 为作者自有项目，相关图标导出代码按 MIT 重新授权**；1.21.1 Fabric 版本按新 API 重写，NOTICE 里注明来源与授权；
- 不复制 LMS（LGPLv3）、IRR（GPL-3.0）的任何代码；只参考公开行为、输出格式与 API；
- Fabric API 作为依赖，不打包；
- 不附带原版材质、模组 jar、导出产物；
- README 写清「上游参考」与「未复用代码」，避免许可争议。

---

## 13. 里程碑

| 阶段 | 内容 | 完成标准 |
|---|---|---|
| M0 | 仓库骨架、MIT、CI 编译、job/status schema | 空 job 能跑通状态机 |
| M1 | 物品与方块图标（32/128）、语言、manifest | 一个模组物品全量出图 |
| M2 | 效果图标原图 NEAREST + 颜色数校验 | 19281 的 12 张效果图回归通过 |
| M3 | 实体渲染、warmup、suspect 指标、overrides | 实体图与 LMS 结果目视一致 |
| M4 | LMS JSON writer、原子写、缓存 | 产物可直接给现有批量脚本 |
| M5 | agent 契约、哨兵、退出码、可重入 | 外部编排一次跑通 |
| M6 | 游戏内命令与 GUI、配置 | 人类不看文档可用 |
| M7 | 发布 1.0.0、README/AGENTS、Release jar | 第三方可复现构建 |

v1.1 再考虑：配方导出、附魔、创造模式标签页变体、多语言扩展。
v2 再考虑：1.20.1 Fabric、1.21.1 NeoForge 的 platform 子模块。

---

## 14. 已确认与剩余待定

已确认：

- 物品、实体沿用 LMS，逐字段一致；效果用本仓库自定义的 `<ns>_effect.json`，字段见 §5.1。
- SuperDBG 的图标导出逻辑为自有代码，按 MIT 重新授权；1.21.1 需要按 Fabric API 重写，但不再有许可障碍。

剩余待定：

1. **版本范围**：v1 只做 1.21.1 Fabric，还是同时铺 1.20.1 Fabric？建议只做 1.21.1，先把契约和回归打稳。
2. **效果图标默认策略**：建议所有效果一律 sprite 原图，不做渲染；这样从根上避免模糊。
3. **默认输出目录**：建议 `.mcauto/`（游戏目录下），agent 用 job 覆盖。
4. **v1 是否要配方导出**：建议不做，配方继续由现有脚本从 jar 解析；导出器专注图标与注册数据。
