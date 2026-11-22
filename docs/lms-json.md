# LMS-compatible JSON

All files are JSON Lines: one object per line, UTF-8, no wrapping array.

## Merged — `<namespace>/<namespace>_all.json`

Every exported entry of the namespace in a single file: items first, then entities, then effects. Each line keeps the fields of its own file above and adds `kind` = `item` / `entity` / `effect`. The split files stay unchanged.

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
