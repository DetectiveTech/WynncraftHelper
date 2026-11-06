# Wynncraft Helper

A client-side Fabric mod for Minecraft 1.21.11 that plans Wynncraft profession crafting, tracks XP/materials, detects bombs and Double XP events, and provides a pinned HUD tracker with live inventory and pouch scanning.

**Author:** DetectiveTech  
**Minecraft:** 1.21.11  
**Mod loader:** Fabric  
**Requires:** Wynntils

## Download

Compiled jar: see the [Releases](https://github.com/DetectiveTech/WynncraftHelper/releases) page.

Install both jars into your `mods` folder:
- `WynncraftHelper-1.0.0.jar`
- `wynntils-4.2.16-fabric+MC-1.21.11.jar`

## Features

- All 8 crafting professions (switchable in the UI).
- Calculates crafts needed, materials, and XP per craft.
- Applies XP Bomb / Speed Bomb and Double XP events automatically.
- Pinned HUD overlay tracking a crafting goal with live inventory and pouch counts.
- Learns real XP per craft from your gameplay for accurate "crafts remaining".
- Manual item linking and debug commands.

## Main Menu (H key)

- Select profession, level, progress %, target level.
- Shows crafts needed, materials, XP per craft.
- WORLD BONUS indicator shows 2x XP / Speed status.
- "Pin to HUD" button in recipe breakdown pins a goal to the screen.

## HUD Overlay

- Pin a recipe goal from the Recipe Breakdown window.
- Shows profession, level -> target, crafts remaining, base materials, ingredients.
- Counts are `[Current / Required]`, green when complete.
- Scans main inventory and Ingredient Pouch automatically.
- `X` key toggles visibility (configurable in Controls).
- Drag to move, corner/edge handles to resize when chat (T) or a GUI is open.
- Position and size are saved across restarts.
- Corner/edge handles allow resizing.

## Commands

- `/craftplan debug` — prints held item info.
- `/craftplan register <material> [ratio]` — links held item to a material.
- `/craftplan unregister <material>` — removes a link.
- `/craftplan list` — shows all links.
- `/craftplan measure start` / `/craftplan time on` — start measuring XP.
- `/craftplan measure stop` / `/craftplan time off` — stop and save average XP.
- `/craftplan measure status` — shows measurement progress.

## Config Files

Location: `.../config/`

- `wynncrafthelper-professions.json` — levels, progress, targets, HUD, links.
- `wynncrafthelper-recipes-cache.json` — recipe database (auto).
- `wynncrafthelper-ingredients-cache.json` — ingredient database (auto).
- `wynncrafthelper.json` — API token (optional).
- `wynncrafthelper-xp-cache.json` — learned XP (auto).

## API Token (optional)

Get it from your Wynncraft profile on the website: log in, open account settings, find "API" / "Personal API Token", generate and copy. Format:

```json
{
  "token": "YOUR_TOKEN_HERE"
}
```

The mod works without a token but downloads are slower.

## Controls

- `H` — Open Wynncraft Helper
- `X` — Toggle HUD (change in Options -> Controls -> Miscellaneous)

## Notes

- Crafting XP is random in Wynncraft, so "Crafts Remaining" is an estimate.
- For best accuracy, craft 5-10 items with the pinned recipe to build an average.
- The HUD counts crafts to your TARGET level. Set Target to 106 for one level only.
- If an item shows 0 / required in the HUD, use `/craftplan debug` and `/craftplan register`.

## 🔧 Hotfix 1.0.0 - Bug Fixes Release

This hotfix release resolves critical issues in the Wynncraft Helper mod:

- `/craftplan measure` Listener Leaks — Fixed duplicate event registration, clean world-change unsubscription
- Item Tier Differentiation (T1/T2/T3) — Fixed T1/T2/T3 tracking, each tier now shows its own progress line
- HUD Crafting-Only Filter — Strictly ignores gathering professions (Mining/Fishing/Farming/Woodcutting)
- Debounce — Max 1 update per 500ms to eliminate packet spam and audio overlap

See [HOTFIX.md](HOTFIX.md) for full details.
