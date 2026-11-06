package com.detectivetech.wynncrafthelper.config;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.detectivetech.wynncrafthelper.hud.HudGoal;
import com.detectivetech.wynncrafthelper.util.MaterialNames;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Profession levels are not exposed by the Wynncraft API, so they are entered by the player
 * and persisted next to the token file.
 */
public final class ProfessionConfig {
	public static final List<String> CRAFTS = List.of(
		"armouring",
		"weaponsmithing",
		"tailoring",
		"woodworking",
		"jeweling",
		"cooking",
		"scribing",
		"alchemism"
	);

	private static final List<String> GATHERING = List.of(
		"mining",
		"fishing",
		"farming",
		"woodcutting"
	);

	private static final Map<String, String> DISPLAY_NAMES = new LinkedHashMap<>();

	static {
		DISPLAY_NAMES.put("armouring", "Armouring");
		DISPLAY_NAMES.put("weaponsmithing", "Weaponsmithing");
		DISPLAY_NAMES.put("tailoring", "Tailoring");
		DISPLAY_NAMES.put("woodworking", "Woodworking");
		DISPLAY_NAMES.put("jeweling", "Jeweling");
		DISPLAY_NAMES.put("cooking", "Cooking");
		DISPLAY_NAMES.put("scribing", "Scribing");
		DISPLAY_NAMES.put("alchemism", "Alchemism");
	}

	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final String FILE_NAME = "wynncrafthelper-professions.json";

	private static Data data = load();

	private ProfessionConfig() {
	}

	public static String displayName(String skill) {
		return DISPLAY_NAMES.getOrDefault(skill, skill);
	}

	public static String selected() {
		String selected = data.selected;
		if (selected == null || !CRAFTS.contains(selected)) {
			selected = "jeweling";
			data.selected = selected;
		}
		return selected;
	}

	/**
	 * Returns true if the given skill is a gathering profession (Mining, Fishing, Farming, Woodcutting).
	 * HUD updates should be skipped for gathering professions.
	 */
	public static boolean isGatheringProfession(String skill) {
		return GATHERING.contains(skill);
	}

	public static void cycleSelected(int delta) {
		int index = CRAFTS.indexOf(selected());
		int next = Math.floorMod(index + delta, CRAFTS.size());
		data.selected = CRAFTS.get(next);
		save();
	}

	public static int level(String skill) {
		return clamp(data.levels.getOrDefault(skill, 1), 1, ProfessionXp.MAX_LEVEL);
	}

	public static void setLevel(String skill, int value) {
		int clamped = clamp(value, 1, ProfessionXp.MAX_LEVEL);
		data.levels.put(skill, clamped);
		if (target(skill) <= clamped) {
			data.targets.put(skill, Math.min(ProfessionXp.MAX_LEVEL, clamped + 1));
		}
		save();
	}

	/**
	 * Progress (in percent) through the current profession level.
	 */
	public static int progress(String skill) {
		return clamp(data.progress.getOrDefault(skill, 0), 0, 99);
	}

	public static void setProgress(String skill, int value) {
		data.progress.put(skill, clamp(value, 0, 99));
		save();
	}

	public static int target(String skill) {
		int level = level(skill);
		int target = data.targets.getOrDefault(skill, Math.min(ProfessionXp.MAX_LEVEL, level + 1));
		target = clamp(target, level + 1, ProfessionXp.MAX_LEVEL);
		if (level >= ProfessionXp.MAX_LEVEL) {
			target = ProfessionXp.MAX_LEVEL;
		}
		return target;
	}

	public static void setTarget(String skill, int value) {
		int level = level(skill);
		if (level >= ProfessionXp.MAX_LEVEL) {
			data.targets.put(skill, ProfessionXp.MAX_LEVEL);
		} else {
			data.targets.put(skill, clamp(value, level + 1, ProfessionXp.MAX_LEVEL));
		}
		save();
	}

	public static boolean doubleXpWeekend() {
		return data.doubleXpWeekend;
	}

	public static void setDoubleXpWeekend(boolean value) {
		data.doubleXpWeekend = value;
		save();
	}

	public static boolean xpBomb() {
		return data.xpBomb;
	}

	public static void setXpBomb(boolean value) {
		data.xpBomb = value;
		save();
	}

	/**
	 * Ingredient preset used for the XP estimate.
	 * 0 = no ingredients, 1..4 = six ingredients of tier 0..3.
	 */
	public static int ingredientPreset() {
		return clamp(data.ingredientPreset == null ? 3 : data.ingredientPreset, 0, 4);
	}

	public static void setIngredientPreset(int value) {
		data.ingredientPreset = clamp(value, 0, 4);
		save();
	}

	/**
	 * Ingredient pinned by the player for a profession (chosen from the search box).
	 * Returns {@code null} when the player lets the mod auto-pick.
	 */
	public static String pinnedIngredient(String skill) {
		return data.pinnedIngredients.get(skill);
	}

	public static void setPinnedIngredient(String skill, String name) {
		if (name == null || name.isBlank()) {
			data.pinnedIngredients.remove(skill);
		} else {
			data.pinnedIngredients.put(skill, name);
		}
		save();
	}

	// ---------------------------------------------------------------- manual item links

	/** All player-registered material links, keyed by the canonical material name. */
	public static Map<String, ManualItem> manualItems() {
		return data.manualItems;
	}

	/** The manual link for a recipe material, or {@code null} when none was registered. */
	public static ManualItem manualItem(String material) {
		if (material == null) {
			return null;
		}
		return data.manualItems.get(MaterialNames.canonical(material));
	}

	public static void setManualItem(String material, ManualItem item) {
		String key = MaterialNames.canonical(material);
		if (key.isEmpty() || item == null) {
			return;
		}
		if (item.displayName == null || item.displayName.isBlank()) {
			item.displayName = key;
		}
		item.rawPerUnit = Math.max(1, item.rawPerUnit);
		data.manualItems.put(key, item);
		save();
	}

	public static boolean removeManualItem(String material) {
		String key = MaterialNames.canonical(material);
		if (key.isEmpty()) {
			return false;
		}
		boolean removed = data.manualItems.remove(key) != null;
		if (removed) {
			save();
		}
		return removed;
	}

	// ---------------------------------------------------------------- HUD

	public static HudGoal pinnedHudGoal() {
		return data.pinnedHudGoal;
	}

	public static void setPinnedHudGoal(HudGoal goal) {
		data.pinnedHudGoal = goal;
		save();
	}

	public static double hudX() {
		return data.hudX == null ? -1.0 : data.hudX;
	}

	public static double hudY() {
		return data.hudY == null ? -1.0 : data.hudY;
	}

	public static void setHudPosition(double x, double y) {
		data.hudX = x;
		data.hudY = y;
		save();
	}

	public static boolean hudVisible() {
		return data.hudVisible == null || data.hudVisible;
	}

	public static int hudWidth() {
		return data.hudWidth == null ? 0 : Math.max(0, data.hudWidth);
	}

	public static void setHudWidth(int value) {
		data.hudWidth = Math.max(0, value);
		save();
	}

	public static int hudHeight() {
		return data.hudHeight == null ? 0 : Math.max(0, data.hudHeight);
	}

	public static void setHudHeight(int value) {
		data.hudHeight = Math.max(0, value);
		save();
	}

	public static void setHudSize(int width, int height) {
		setHudWidth(width);
		setHudHeight(height);
	}

	public static void setHudVisible(boolean visible) {
		data.hudVisible = visible;
		save();
	}

	private static Data load() {
		Data defaults = defaults();
		try {
			Path path = path();
			if (!Files.exists(path)) {
				return defaults;
			}
			Data loaded = GSON.fromJson(Files.readString(path, StandardCharsets.UTF_8), Data.class);
			if (loaded == null) {
				return defaults;
			}
			if (loaded.selected != null && CRAFTS.contains(loaded.selected)) {
				defaults.selected = loaded.selected;
			}
			merge(defaults.levels, loaded.levels);
			merge(defaults.progress, loaded.progress);
			merge(defaults.targets, loaded.targets);
			if (loaded.pinnedIngredients != null) {
				for (String skill : CRAFTS) {
					String name = loaded.pinnedIngredients.get(skill);
					if (name != null && !name.isBlank()) {
						defaults.pinnedIngredients.put(skill, name);
					}
				}
			}
			defaults.doubleXpWeekend = loaded.doubleXpWeekend;
			defaults.xpBomb = loaded.xpBomb;
			if (loaded.ingredientPreset != null) {
				defaults.ingredientPreset = clamp(loaded.ingredientPreset, 0, 4);
			}
			defaults.pinnedHudGoal = loaded.pinnedHudGoal;
			if (loaded.hudX != null) {
				defaults.hudX = loaded.hudX;
			}
			if (loaded.hudY != null) {
				defaults.hudY = loaded.hudY;
			}
			defaults.hudVisible = loaded.hudVisible == null ? Boolean.TRUE : loaded.hudVisible;
			if (loaded.hudWidth != null) {
				defaults.hudWidth = Math.max(0, loaded.hudWidth);
			}
			if (loaded.hudHeight != null) {
				defaults.hudHeight = Math.max(0, loaded.hudHeight);
			}
			if (loaded.manualItems != null) {
				for (Map.Entry<String, ManualItem> entry : loaded.manualItems.entrySet()) {
					String key = MaterialNames.canonical(entry.getKey());
					ManualItem value = entry.getValue();
					if (!key.isEmpty() && value != null) {
						if (value.displayName == null || value.displayName.isBlank()) {
							value.displayName = key;
						}
						value.rawPerUnit = Math.max(1, value.rawPerUnit);
						defaults.manualItems.put(key, value);
					}
				}
			}
		} catch (Exception ignored) {
			// use defaults
		}
		return defaults;
	}

	private static void merge(Map<String, Integer> target, Map<String, Integer> source) {
		if (source == null) {
			return;
		}
		for (String skill : CRAFTS) {
			Integer value = source.get(skill);
			if (value != null) {
				target.put(skill, value);
			}
		}
	}

	private static Data defaults() {
		Data defaults = new Data();
		int[] levels = {110, 108, 110, 105, 115, 105, 105, 105};
		for (int index = 0; index < CRAFTS.size(); index++) {
			String skill = CRAFTS.get(index);
			int level = levels[index];
			defaults.levels.put(skill, level);
			defaults.progress.put(skill, 0);
			defaults.targets.put(skill, Math.min(ProfessionXp.MAX_LEVEL, level + 1));
		}
		return defaults;
	}

	private static void save() {
		try {
			Files.writeString(path(), GSON.toJson(data), StandardCharsets.UTF_8);
		} catch (Exception ignored) {
			// saving is best-effort
		}
	}

	private static Path path() {
		return WynnPaths.resolve(FILE_NAME);
	}

	private static int clamp(int value, int min, int max) {
		return Math.max(min, Math.min(max, value));
	}

	private static class Data {
		String selected = "jeweling";
		Map<String, Integer> levels = new LinkedHashMap<>();
		Map<String, Integer> progress = new LinkedHashMap<>();
		Map<String, Integer> targets = new LinkedHashMap<>();
		boolean doubleXpWeekend;
		boolean xpBomb;
		Integer ingredientPreset = 3;
		Map<String, String> pinnedIngredients = new LinkedHashMap<>();
		HudGoal pinnedHudGoal;
		Double hudX;
		Double hudY;
		Boolean hudVisible = Boolean.TRUE;
		Integer hudWidth;
		Integer hudHeight;
		Map<String, ManualItem> manualItems = new LinkedHashMap<>();
	}
}
