package com.detectivetech.wynncrafthelper.xp;

import com.detectivetech.wynncrafthelper.api.WynncraftEvents;
import com.detectivetech.wynncrafthelper.compat.WynntilsBridge;
import com.detectivetech.wynncrafthelper.config.ProfessionConfig;
import com.detectivetech.wynncrafthelper.config.WynnPaths;
import com.detectivetech.wynncrafthelper.hud.HudGoal;
import com.detectivetech.wynncrafthelper.hud.HudState;
import com.detectivetech.wynncrafthelper.util.MaterialNames;
import com.detectivetech.wynncrafthelper.xp.CraftSessionLogger;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Learns the real XP per craft from observed inventory deltas and Wynntils XP gain events.
 * The learned values are persisted to disk and used by the HUD when available.
 */
public final class EmpiricalXp {
	private static final Logger LOGGER = LoggerFactory.getLogger("WynncraftHelper");
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final String FILE_NAME = "wynncrafthelper-xp-cache.json";
	private static final String SESSION_LOG_FILE = "crafting-sessions.log";
	/** Max entries in the learned-XP cache. Oldest entries are evicted when full. */
	private static final int MAX_CACHE_ENTRIES = 500;

	private static final Object LOCK = new Object();
	private static Map<String, Double> cache = new LinkedHashMap<>();
	private static boolean loaded;

	private static boolean isMeasuringEnabled;
	private static double measureTotalXp;
	private static int measureCount;
	private static String measureSignature;
	private static String lastMeasureResult;
	private static Map<String, Integer> lastSnapshot;
	private static Map<String, Integer> pendingSnapshot;
	private static boolean craftPending; // set by tick() when inventory changes; consumed by onXpGain() or stopMeasure()
	/** "Craft until X" mode state. */
	private static boolean craftUntilEnabled = false;
	private static int craftUntilTarget = 0;
	private static int craftUntilCrafted = 0;
	private static boolean craftUntilPending = false;

	private EmpiricalXp() {
	}

	public static void tick(Minecraft client) {
		if (client == null || client.player == null) {
			synchronized (LOCK) {
				lastSnapshot = null;
				pendingSnapshot = null;
				craftPending = false;
			}
			return;
		}
		ensureLoaded();
		Map<String, Integer> current = HudState.INSTANCE.inventorySnapshot(client);
		synchronized (LOCK) {
			if (lastSnapshot == null) {
				lastSnapshot = new HashMap<>(current);
				return;
			}
			if (!current.equals(lastSnapshot)) {
				pendingSnapshot = new HashMap<>(lastSnapshot);
				lastSnapshot = new HashMap<>(current);
				// Measurement fallback: mark that a craft was detected via inventory change.
				// onXpGain() consumes this flag and records the craft with Wynntils XP.
				// If Wynntils never fires, stopMeasure() records it with estimated XP.
				if (isMeasuringEnabled) {
					craftPending = true;
				}
				// Craft-until: count crafts via inventory changes.
				if (craftUntilEnabled) {
					onInventoryChangeForCraftUntil(client);
				}
			}
		}
	}

	/**
	 * Fallback called only from {@link #stopMeasure()} when Wynntils never fired an XP
	 * event for a craft that tick() detected via inventory change.
	 */
	private static void recordPendingCraft() {
		synchronized (LOCK) {
			if (!isMeasuringEnabled || !craftPending) {
				return;
			}
			craftPending = false;
			pendingSnapshot = null;
		}

		measureCount++;
		double estimatedXp = estimateBaseXp();
		synchronized (LOCK) {
			measureTotalXp += estimatedXp;
		}

		LOGGER.info("EmpiricalXp: measure craft {} gained {} (inventory fallback, no wynntils xp)", measureCount, (long) estimatedXp);
	}

	private static double estimateBaseXp() {
		HudGoal goal = HudState.INSTANCE.goal();
		if (goal != null && goal.baseXp > 0) {
			boolean worldBonus = ProfessionConfig.doubleXpWeekend()
				|| WynncraftEvents.doubleXpActive();
			Boolean xpBomb = WynntilsBridge.professionXpBombActive();
			worldBonus = worldBonus || Boolean.TRUE.equals(xpBomb);
			double world = worldBonus ? 2.0 : 1.0;
			double ingredient = goal.ingredientMultiplier > 0 ? goal.ingredientMultiplier : 1.0;
			return Math.max(0, goal.baseXp) * world * ingredient;
		}
		return 0.0;
	}

	public static void onXpGain(Minecraft client) {
		try {
			if (client == null || client.player == null) {
				return;
			}
			ensureLoaded();
			double gained = WynntilsBridge.professionXpGain();
			LOGGER.info("EmpiricalXp: onXpGain called, gained={}", gained);

			synchronized (LOCK) {
				if (isMeasuringEnabled) {
					if (craftPending) {
						// tick() already detected this craft via inventory change.
						// Use Wynntils' accurate XP value instead of the estimate.
						measureTotalXp += gained;
						craftPending = false;
						pendingSnapshot = null;
						LOGGER.info("EmpiricalXp: measure craft {} gained {} (wynntils xp)", measureCount, gained);
						logSessionCraft(gained);
						if (client.player != null) {
							client.player.displayClientMessage(Component.literal(
									"Measuring: craft " + measureCount + " gained " + (long) gained + " XP."), false);
						}
					} else {
						measureTotalXp += gained;
						measureCount++;
						logSessionCraft(gained);
						Map<String, Integer> current = HudState.INSTANCE.inventorySnapshot(client);
						lastSnapshot = new HashMap<>(current);
						pendingSnapshot = null;
						LOGGER.info("EmpiricalXp: measure craft {} gained {}", measureCount, gained);
						if (client.player != null) {
							client.player.displayClientMessage(Component.literal(
									"Measuring: craft " + measureCount + " gained " + (long) gained + " XP."), false);
						}
					}
					return;
				}
			}

			if (gained <= 0) {
				return;
			}

			Map<String, Integer> current = HudState.INSTANCE.inventorySnapshot(client);
			Map<String, Integer> before;
			synchronized (LOCK) {
				before = pendingSnapshot != null ? pendingSnapshot : lastSnapshot;
				pendingSnapshot = null;
				lastSnapshot = new HashMap<>(current);
			}

			double baseXp = gained;

			HudGoal goal = HudState.INSTANCE.goal();
			if (goal != null) {
				String goalSignature = signatureFor(goal);
				if (goalSignature != null) {
					synchronized (LOCK) {
						Double previous = cache.get(goalSignature);
						double updated = (previous == null) ? baseXp : (previous * 0.7 + baseXp * 0.3);
						cachePutEvict(goalSignature, updated);
						LOGGER.info("EmpiricalXp: learned {} XP (avg) for pinned goal {}", updated, goalSignature);
					}
				}
			}

			Map<String, Integer> consumed = (before == null) ? new HashMap<>() : delta(before, current);
			if (!consumed.isEmpty()) {
				String consumedSignature = signature(consumed);
				synchronized (LOCK) {
					Double previous = cache.get(consumedSignature);
					double updated = (previous == null) ? baseXp : (previous * 0.7 + baseXp * 0.3);
					cachePutEvict(consumedSignature, updated);
					LOGGER.info("EmpiricalXp: learned {} XP (avg) for consumed {}", updated, consumedSignature);
				}
			}
			logSessionCraft(gained, goal, consumed);
			save();
			if (client.player != null) {
				client.player.displayClientMessage(Component.literal(
					"WynncraftHelper learned " + (long) baseXp + " XP for your pinned recipe (avg updating)."), false);
			}
		} catch (Exception error) {
			LOGGER.warn("EmpiricalXp: onXpGain failed", error);
		}
	}

	/**
	 * Logs a confirmed craft to the session file. Used by the measuring paths where
	 * ingredient consumption isn't available.
	 */
	private static void logSessionCraft(double gained) {
		HudGoal goal = HudState.INSTANCE.goal();
		String skill = goal != null ? goal.skill : ProfessionConfig.selected();
		String recipeName = goal != null ? goal.recipeName : null;
		int level = ProfessionConfig.level(skill);
		CraftSessionLogger.logCraft(skill, recipeName, (long) gained, level, level, null);
	}

	/**
	 * Logs a confirmed craft to the session file, including ingredient consumption.
	 */
	private static void logSessionCraft(double gained, HudGoal goal, Map<String, Integer> consumed) {
		String skill = goal != null ? goal.skill : ProfessionConfig.selected();
		String recipeName = goal != null ? goal.recipeName : null;
		int level = ProfessionConfig.level(skill);
		CraftSessionLogger.logCraft(skill, recipeName, (long) gained, level, level, consumed);
	}

	/**
	 * Puts a value into the cache, evicting the oldest entry when over capacity.
	 * Must be called while holding {@link #LOCK}.
	 */
	private static void cachePutEvict(String key, double value) {
		cache.put(key, value);
		while (cache.size() > MAX_CACHE_ENTRIES) {
			Map.Entry<String, Double> oldest = cache.entrySet().iterator().next();
			if (oldest != null) {
				cache.remove(oldest.getKey());
			} else {
				break;
			}
		}
	}

	public static boolean startMeasure() {
		ensureLoaded();
		HudGoal goal = HudState.INSTANCE.goal();
		if (goal == null) {
			return false;
		}
		synchronized (LOCK) {
			isMeasuringEnabled = true;
			measureTotalXp = 0;
			measureCount = 0;
			measureSignature = signatureFor(goal);
			lastMeasureResult = null;
			craftPending = false;
		}
		logSessionStart(goal);
		return true;
	}

	public static boolean stopMeasure() {
		synchronized (LOCK) {
			if (!isMeasuringEnabled) {
				return false;
			}
			isMeasuringEnabled = false;
		}
		// Record any craft that tick() detected but Wynntils never reported XP for.
		recordPendingCraft();
		synchronized (LOCK) {
			if (measureCount > 0 && measureSignature != null) {
				double avg = measureTotalXp / measureCount;
				cachePutEvict(measureSignature, avg);
				save();
				lastMeasureResult = "Measured " + measureCount + " crafts, average " + (long) avg + " XP. Saved.";
			} else {
				lastMeasureResult = "No crafts recorded during measurement.";
			}
			measureTotalXp = 0;
			measureCount = 0;
			measureSignature = null;
			craftPending = false;
		}
		logSessionStop();
		return true;
	}

	/**
	 * Starts a "craft until X" session. Tracks crafted items via inventory changes detected
	 * in {@link #tick(Minecraft)} and notifies the player when the target is reached.
	 */
	public static void startCraftUntil(int target) {
		synchronized (LOCK) {
			craftUntilEnabled = true;
			craftUntilTarget = target;
			craftUntilCrafted = 0;
			craftUntilPending = false;
		}
	}

	/**
	 * Call from {@link #tick(Minecraft)} when an inventory change is detected while
	 * craft-until is active. Increments the crafted count and notifies when the target is
	 * reached.
	 */
	public static void onInventoryChangeForCraftUntil(Minecraft client) {
		synchronized (LOCK) {
			if (!craftUntilEnabled || craftUntilPending) {
				return;
			}
			craftUntilPending = true;
			craftUntilCrafted++;
			int crafted = craftUntilCrafted;
			int target = craftUntilTarget;
			boolean done = crafted >= target;
			if (done) {
				craftUntilEnabled = false;
			}
		}
		if (client != null && client.player != null && craftUntilCrafted >= craftUntilTarget) {
			client.player.displayClientMessage(Component.literal(
				"WynncraftHelper: Craft until " + craftUntilTarget + " complete! Crafted " + craftUntilCrafted + " items."), false);
		}
	}

	public static boolean isMeasuring() {
		synchronized (LOCK) {
			return isMeasuringEnabled;
		}
	}

	public static int measureCount() {
		synchronized (LOCK) {
			return measureCount;
		}
	}

	public static double measureTotalXp() {
		synchronized (LOCK) {
			return measureTotalXp;
		}
	}

	public static String lastMeasureResult() {
		synchronized (LOCK) {
			return lastMeasureResult;
		}
	}

	public static Double lookup(String signature) {
		if (signature == null || signature.isBlank()) {
			return null;
		}
		ensureLoaded();
		synchronized (LOCK) {
			return cache.get(signature);
		}
	}

	/** Reloads the XP cache from disk. Used after importing a cache file. */
	public static void reloadCache() {
		loaded = false;
		ensureLoaded();
	}

	public static String signatureFor(HudGoal goal) {
		if (goal == null) {
			return null;
		}
		Map<String, Integer> planned = new HashMap<>();
		if (goal.perCraftMaterials != null) {
			for (Map.Entry<String, Integer> entry : goal.perCraftMaterials.entrySet()) {
				int amount = entry.getValue() == null ? 0 : entry.getValue();
				planned.merge(MaterialNames.canonical(entry.getKey()), amount, Integer::sum);
			}
		}
		if (goal.perCraftIngredients != null) {
			for (Map.Entry<String, Integer> entry : goal.perCraftIngredients.entrySet()) {
				int amount = entry.getValue() == null ? 0 : entry.getValue();
				planned.merge(MaterialNames.canonical(entry.getKey()), amount, Integer::sum);
			}
		}
		if (planned.isEmpty()) {
			return null;
		}
		return signature(planned);
	}

	private static Map<String, Integer> delta(Map<String, Integer> before, Map<String, Integer> after) {
		Map<String, Integer> consumed = new HashMap<>();
		for (Map.Entry<String, Integer> entry : before.entrySet()) {
			int beforeCount = entry.getValue() == null ? 0 : entry.getValue();
			int afterCount = after.getOrDefault(entry.getKey(), 0);
			int used = beforeCount - afterCount;
			if (used > 0) {
				consumed.put(entry.getKey(), used);
			}
		}
		return consumed;
	}

	private static String signature(Map<String, Integer> consumed) {
		List<String> parts = new ArrayList<>();
		for (Map.Entry<String, Integer> entry : consumed.entrySet()) {
			if (entry.getValue() != null && entry.getValue() > 0) {
				parts.add(entry.getKey() + " x" + entry.getValue());
			}
		}
		Collections.sort(parts);
		return String.join(" | ", parts);
	}

	private static void ensureLoaded() {
		if (loaded) {
			return;
		}
		loaded = true;
		try {
			Path path = WynnPaths.resolve(FILE_NAME);
			if (Files.exists(path)) {
				Map<?, ?> loadedMap = GSON.fromJson(Files.readString(path, StandardCharsets.UTF_8), Map.class);
				if (loadedMap != null) {
					synchronized (LOCK) {
						for (Map.Entry<?, ?> entry : loadedMap.entrySet()) {
							if (entry.getKey() instanceof String key && entry.getValue() instanceof Number value) {
								cache.put(key, value.doubleValue());
							}
						}
						while (cache.size() > MAX_CACHE_ENTRIES) {
							Map.Entry<String, Double> oldest = cache.entrySet().iterator().next();
							if (oldest != null) {
								cache.remove(oldest.getKey());
							} else {
								break;
							}
						}
					}
				}
			}
		} catch (Exception ignored) {
			// use empty cache
		}
	}

	private static void save() {
		try {
			Path path = WynnPaths.resolve(FILE_NAME);
			Map<String, Double> snapshot;
			synchronized (LOCK) {
				snapshot = new LinkedHashMap<>(cache);
			}
			Files.writeString(path, GSON.toJson(snapshot), StandardCharsets.UTF_8);
		} catch (Exception ignored) {
			// saving is best-effort
		}
	}

	private static void logSessionStart(HudGoal goal) {
		try {
			Path path = WynnPaths.resolve(SESSION_LOG_FILE);
			String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(new Date());
			String line = timestamp + " START " + (goal.skill == null ? "?" : goal.skill)
				+ " targetLv=" + goal.targetLevel
				+ " baseXp=" + goal.baseXp
				+ " tier=" + goal.tier + "\n";
			Files.writeString(path, line, StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
		} catch (Exception ignored) {
			// logging is best-effort
		}
	}

	private static void logSessionStop() {
		try {
			Path path = WynnPaths.resolve(SESSION_LOG_FILE);
			String timestamp = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(new Date());
			synchronized (LOCK) {
				String line = timestamp + " STOP crafts=" + measureCount
					+ " totalXp=" + (long) measureTotalXp
					+ " avgXp=" + (measureCount > 0 ? (long) (measureTotalXp / measureCount) : 0)
					+ " result=" + (lastMeasureResult == null ? "?" : lastMeasureResult) + "\n";
				Files.writeString(path, line, StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
			}
		} catch (Exception ignored) {
			// logging is best-effort
		}
	}
}
