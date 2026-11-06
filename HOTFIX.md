# 🔧 Hotfix 1.0.0 — Bug Fixes Release

## Summary

This hotfix release resolves critical issues in the Wynncraft Helper mod affecting event listener management, tier differentiation, HUD behavior, and performance.

---

## 🐛 Fixed Issues

### 1. `/craftplan measure` Listener Leaks & Stutters
**Severity:** High  
**Symptom:** Toggling `/craftplan measure on/off` caused duplicate listener registration, spamming chat/logs and causing micro-freezes.

**Fix:**
- Listener registered once during mod initialization
- Boolean flag `isMeasuringEnabled` controls measurement state inside listener
- Clean unsubscription/reset on world change
- Removed redundant tick-event re-registration

---

### 2. Item Tier Differentiation (T1/T2/T3) Fixed
**Severity:** High  
**Symptom:** Registering `/craftplan register Dernic Meat 1/2/3` treated all tiers as the same item, combining totals.

**Fix:**
- Items stored keyed by `canonical_name tTier`
- `HudState.buildRows()` preserves tier-differentiated keys
- `HudState.currentCount()` checks for tier-specific manual links

**Result:** Each tier (T1/T2/T3) now displays its own dedicated progress line.

---

### 3. HUD Restricted to Crafting Only
**Severity:** Medium  
**Symptom:** HUD counters jumped during Mining/Fishing/Farming/Woodcutting; packet spam caused audio overlap and screen freezes.

**Fix:**
- Strict filtering: skip HUD updates for gathering professions
- Debounce: **500ms maximum** 1 update per 500ms

---

## 📦 Build Information

- **Version:** 1.0.0 (Hotfix Release)
- **Jar Size:** 127,321 bytes
- **Status:** ✅ Build Successful
- **Minecraft:** 1.21.11
- **Mod Loader:** Fabric

---

## 🚀 Installation

1. Copy `WynncraftHelper-1.0.0.jar` → `mods` folder
2. Copy `wynntils-4.2.16-fabric+MC-1.21.11.jar` → `mods` folder
3. Restart Minecraft fully
4. Configure as needed

---

## 📋 Usage Notes

- `/craftplan register <material> <tier>` → Track T1/T2/T3 separately
- `/craftplan measure start` / `measure stop` → Measure XP per craft
- HUD updates max once per 500ms
- HUD ignores gathering professions entirely

---

## 🔗 Repository

- **GitHub:** https://github.com/DetectiveTech/WynncraftHelper
- **Author:** DetectiveTech
