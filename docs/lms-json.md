# LMS-compatible JSON

All files are JSON Lines: one object per line, UTF-8, no wrapping array.

## Merged — `<namespace>/<namespace>_all.json`

Every exported entry of the namespace in a single file: items first, then entities, then effects, then fluids. Each line keeps the fields of its own file above and adds `kind` = `item` / `entity` / `effect` / `fluid`. The split files stay unchanged.

## Items — `<namespace>/<namespace>_items.json`

| Field | Type | Notes |
|---|---|---|
| `name` | string | `zh_cn`，缺失时回退 `en_us`；英文名在 `englishName` |
| `englishName` | string | `en_us` |
| `registerName` | string | `namespace:path` |
| `type` | string | `Block` or `Item` |
| `maxStacksSize` | int | default stack size |
| `maxDurability` | int | 0 if not damageable |
| `CreativeTabName` | string | display name, `未知` if unknown |
| `OredictList` | string | `[tag1,tag2]` string form, same as LMS |
| `smallIcon` | string | base64 PNG 32×32; empty in JSON-only mode |
| `largeIcon` | string | base64 PNG 128×128; empty in JSON-only mode |

## Entities — `<namespace>/<namespace>_entities.json`

| Field | Type | Notes |
|---|---|---|
| `name` | string | `zh_cn`，缺失时回退 `en_us` |
| `Englishname` | string | capital `E`, same as LMS |
| `mod` | string | namespace |
| `registerName` | string | `namespace:path` |
| `Icon` | string | base64 PNG 128×128 |
| `IconSmall` | string | base64 PNG 32×32 |
| `iconSource` | string | `render` / empty |
| `noVisual` | bool | true when nothing rendered |
| `suspect` | bool | true when quality check failed |
| `suspectReason` | string | only when `suspect` |
| `renderer` | string | renderer class, when known |

## Effects — `<namespace>/<namespace>_effects.json` (this project's extension)

| Field | Type | Notes |
|---|---|---|
| `name` | string | `zh_cn`，缺失时回退 `en_us`；都没有写空串 |
| `Englishname` | string | `en_us` |
| `mod` | string | namespace |
| `registerName` | string | `namespace:path` |
| `category` | string | site-facing `BUFF` / `DEBUFF` |
| `effectCategory` | string | registry `BENEFICIAL` / `HARMFUL` / `NEUTRAL` |
| `color` | int | effect color |
| `instant` | bool | instant effect |
| `Icon` | string | base64 PNG 128×128 |
| `IconSmall` | string | base64 PNG 32×32 |
| `iconSource` | string | always `sprite` |
| `iconMode` | string | always `nearest` |
| `iconColorCount` | int | actual colors in 128 icon |
| `iconExpectedColorCount` | int | colors in source texture |
| `nameSource` | string | `zh_cn` / `en_us` / `none` |
| `warnings` | array | currently empty |

Mapping to site import:

```text
name         → name
Englishname  → ename
registerName → regname
category     → category
(static)     → type: "BUFF/DEBUFF"
IconSmall    → icon32
Icon         → icon128
```

## Fluids — `<namespace>/<namespace>_fluids.json` (this project's extension)

只导出 source 流体（`fluid.isSource(defaultFluidState())` 为真的条目）。

| Field | Type | Notes |
|---|---|---|
| `name` | string | `zh_cn`，缺失时回退 `en_us`，再退回注册名转写 |
| `Englishname` | string | `en_us`，缺失时按注册名转写 |
| `mod` | string | namespace |
| `registerName` | string | `namespace:path` |
| `color` | int | 平台给出的 ARGB 着色，`-1` 表示未知 |
| `bucket` | string | 对应桶物品 id，无桶为空串 |
| `stillTexture` | string | 静止纹理 id（如 `block/water_still`），无则空串 |
| `flowingTexture` | string | 流动纹理 id（立方体侧面），无则空串 |
| `Icon` | string | base64 PNG 128×128，立体方块（顶面静止贴图 + 侧面流动贴图） |
| `IconSmall` | string | base64 PNG 32×32 |
| `iconSource` | string | `cube`（立体方块）/ `texture` / `bucket` / 空；立体渲染失败时退回平面图 |
| `iconMode` | string | always `nearest` |
| `IconStill` | string | base64 PNG 128×128，保留的平面图（静止贴图优先，无贴图退回桶） |
| `IconStillSmall` | string | base64 PNG 32×32 |
| `stillIconSource` | string | 平面图来源：`texture` / `bucket` / 空 |
