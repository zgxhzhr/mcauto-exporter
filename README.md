# Meow Archive Exporter

Minecraft **1.19.3 / Fabric 与 Forge** 客户端模组：在游戏内导出物品、方块、实体、BUFF/DEBUFF 的图标与数据，JSON 字段沿用 LetMeSeeSee（LMS）的 mcmod 导出格式。

MIT License。

## 功能

- 物品 / 方块（BlockItem）图标：32×32、128×128，GUI 内真实渲染
- 实体图标：128×128 离屏渲染，失败/空白会标记
- 技术性实体（中英文名都缺失，如内部辅助实体）直接跳过，不写入 JSON
- BUFF / DEBUFF 图标：直接读取 `textures/mob_effect/<id>.png` 原图，NEAREST 放大，并校验颜色数
- 物品 `<ns>/<ns>_items.json`、实体 `<ns>/<ns>_entities.json` 与 LMS 字段一致
- 效果 `<ns>/<ns>_effects.json` 为本仓库扩展，字段见源码注释
- 合并文件 `<ns>/<ns>_all.json` 包含该命名空间全部条目，每条附加 `kind`
- 预览页 `<ns>/preview.html`：单文件、图标内嵌，可筛选/搜索，点卡片复制注册名
- 失败、无可见模型与可疑条目写入 `failures.txt`，`status.json` 同步列出注册名与原因
- 进度、日志、错误都在界面内显示

## 使用

1. 把 jar 放进 `mods/`。Fabric 端需要 Fabric API，Forge 端无需额外前置。
2. 进入一个世界。
3. 输入 `/meowarchive` 或手持喵呜机右键打开导出器。
4. 左侧点选命名空间；右侧选择模式、类型、尺寸。
5. 点「开始导出」。

### 四种模式

| 模式 | 行为 |
|---|---|
| 完整导出（JSON + 图标） | 渲染图标、写 `<namespace>/icons` PNG，并写出 JSON |
| 只导出 JSON | 不渲染，图标字段为空，只写 JSON |
| 只导出图标 | 只写 `<namespace>/icons` PNG，不写 JSON |
| 导出单个图标 | 打开条目搜索列表，选一个物品/实体/效果，只导它的图 |

### 输出

默认在 `.minecraft/meowarchive-export/`，可在 `config/meowarchive.json` 改 `outputDir`。

```text
meowarchive-export/
├─ status.json
├─ failures.txt                  失败/无可见模型/可疑明细，无异常时不生成
└─ <namespace>/
   ├─ <namespace>_items.json     物品，JSONL，LMS 字段
   ├─ <namespace>_entities.json  实体，JSONL，LMS 字段
   ├─ <namespace>_effects.json   效果，JSONL，扩展字段
   ├─ <namespace>_all.json       合并全部条目，JSONL，含 kind 字段
   ├─ preview.html               单文件预览页，图标内嵌
   └─ icons/<path>_32.png
      icons/<path>_128.png
```

## 构建

需要 JDK 17。仓库采用 MultiLoader 结构，已实现 Fabric 与 Forge 客户端。

```bash
./gradlew :fabric:build
./gradlew :forge:build
```

产物分别在 `fabric/build/libs/` 与 `forge/build/libs/`。

开发客户端：

```bash
./gradlew :fabric:runClient
./gradlew :forge:runClient
```

## 项目结构

```text
common/       与加载器无关的导出逻辑（扫描、渲染、LMS 写出、界面）
fabric/       Fabric 入口、命令、平台服务实现
forge/        Forge 入口、命令、平台服务实现
```

## 已知限制

- 支持 1.19.3 的 Fabric 与 Forge 客户端。
- 实体渲染器如果依赖乘客、坐骑、特殊 AI 状态，可能渲染不出来，会在数据里标记。
- 物品图标取默认 ItemStack，不导出 NBT 变体。
- 配方、附魔暂不导出。

## 许可

- 本仓库代码：MIT。
- 物品贴图 `assets/meowarchive/textures/item/miao_wu_ji.png` 为项目作者自制素材，随本仓库以 MIT 授权。
- 图标导出的「一帧一个 + GUI 阶段离屏 FBO + 像素读回」做法来自作者自己的 ZHR_SuperDBG，按 MIT 重新授权。
- LMS（LGPL-3.0）、IRR（GPL-3.0）仅参考公开输出格式与行为，未复制代码。
- Fabric API 为运行时依赖，不打包。