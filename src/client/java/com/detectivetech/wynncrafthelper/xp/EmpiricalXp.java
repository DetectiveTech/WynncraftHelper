package com.detectivetech.wynncrafthelper.xp;

import com.detectivetech.wynncrafthelper.api.WynncraftEvents;
import com.detectivetech.wynncrafthelper.compat.WynntilsBridge;
import com.detectivetech.wynncrafthelper.config.ProfessionConfig;
import com.detectivetech.wynncrafthelper.config.WynnPaths;
import com.detectivetech.wynncrafthelper.hud.HudGoal;
import com.detectivetech.wynncrafthelper.hud.HudState;
import com.detectivetech.wynncrafthelper.util.MaterialNames;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
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

	private EmpiricalXp() {
	}

	public static void tick(Minecraft client) {
		if (client == null || client.player == null) {
			lastSnapshot = null;
			pendingSnapshot = null;
			return;
		}
		ensureLoaded();
		Map<String, Integer> current = HudState.INSTANCE.inventorySnapshot(client);
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
		}
	}

	/**
	 * Fallback called only from {@link #stopMeasure()} when Wynntils never fired an XP
	 * event for a craft that tick() detected via inventory change.
	 */
	private static void recordPendingCraft() {
		if (!isMeasuringEnabled || !craftPending) {
			return;
		}
		craftPending = false;
		pendingSnapshot = null;

		measureCount++;
		double estimatedXp = estimateBaseXp();
		measureTotalXp += estimatedXp;

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

			if (isMeasuringEnabled) {
				if (craftPending) {
					// tick() already detected this craft via inventory change.
					// Use Wynntils' accurate XP value instead of the estimate.
					measureTotalXp += gained;
					craftPending = false;
					pendingSnapshot = null;
					LOGGER.info("EmpiricalXp: measure craft {} gained {} (wynntils xp)", measureCount, gained);
					if (client.player != null) {
						client.player.displayClientMessage(Component.literal(
								"Measuring: craft " + measureCount + " gained " + (long) gained + " XP."), false);
					}
				} else {
					measureTotalXp += gained;
					measureCount++;
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

			if (gained <= 0) {
				return;
			}

			Map<String, Integer> current = HudState.INSTANCE.inventorySnapshot(client);
			Map<String, Integer> before = pendingSnapshot != null ? pendingSnapshot : lastSnapshot;
			pendingSnapshot = null;
			lastSnapshot = new HashMap<>(current);

			double baseXp = gained;

			HudGoal goal = HudState.INSTANCE.goal();
			if (goal != null) {
				String goalSignature = signatureFor(goal);
				if (goalSignature != null) {
					Double previous = cache.get(goalSignature);
					double updated = (previous == null) ? baseXp : (previous * 0.7 + baseXp * 0.3);
					cache.put(goalSignature, updated);
					LOGGER.info("EmpiricalXp: learned {} XP (avg) for pinned goal {}", updated, goalSignature);
				}
			}

			Map<String, Integer> consumed = (before == null) ? new HashMap<>() : delta(before, current);
			if (!consumed.isEmpty()) {
				String consumedSignature = signature(consumed);
				Double previous = cache.get(consumedSignature);
				double updated = (previous == null) ? baseXp : (previous * 0.7 + baseXp * 0.3);
				cache.put(consumedSignature, updated);
				LOGGER.info("EmpiricalXp: learned {} XP (avg) for consumed {}", updated, consumedSignature);
			}
			save();
			if (client.player != null) {
				client.player.displayClientMessage(Component.literal(
					"WynncraftHelper learned " + (long) baseXp + " XP for your pinned recipe (avg updating)."), false);
			}
		} catch (Exception error) {
			LOGGER.warn("EmpiricalXp: onXpGain failed", error);
		}
	}

	public static boolean startMeasure() {
		ensureLoaded();
		HudGoal goal = HudState.INSTANCE.goal();
		if (goal == null) {
			return false;
		}
		isMeasuringEnabled = true;
		measureTotalXp = 0;
		measureCount = 0;
		measureSignature = signatureFor(goal);
		lastMeasureResult = null;
		craftPending = false;
		return true;
	}

	public static boolean stopMeasure() {
		if (!isMeasuringEnabled) {
			return false;
		}
		isMeasuringEnabled = false;
		// Record any craft that tick() detected but Wynntils never reported XP for.
		recordPendingCraft();
		if (measureCount > 0 && measureSignature != null) {
			double avg = measureTotalXp / measureCount;
			cache.put(measureSignature, avg);
			save();
			lastMeasureResult = "Measured " + measureCount + " crafts, average " + (long) avg + " XP. Saved.";
		} else {
			lastMeasureResult = "No crafts recorded during measurement.";
		}
		measureTotalXp = 0;
		measureCount = 0;
		measureSignature = null;
		craftPending = false;
		return true;
	}

	public static boolean isMeasuring() {
		return isMeasuringEnabled;
	}

	public static int measureCount() {
		return measureCount;
	}

	public static double measureTotalXp() {
		return measureTotalXp;
	}

	public static String lastMeasureResult() {
		return lastMeasureResult;
	}

	public static Double lookup(String signature) {
		if (signature == null || signature.isBlank()) {
			return null;
		}
		ensureLoaded();
		return cache.get(signature);
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

	private static double currentWorldMultiplier() {
		boolean xpBomb = false;
		Boolean live = WynntilsBridge.professionXpBombActive();
		if (live != null) {
			xpBomb = live;
		} else {
			xpBomb = com.detectivetech.wynncrafthelper.config.ProfessionConfig.xpBomb();
		}
		boolean event = com.detectivetech.wynncrafthelper.api.WynncraftEvents.doubleXpActive();
		boolean weekend = com.detectivetech.wynncrafthelper.config.ProfessionConfig.doubleXpWeekend();
		return (xpBomb || event || weekend) ? 2.0 : 1.0;
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
					for (Map.Entry<?, ?> entry : loadedMap.entrySet()) {
						if (entry.getKey() instanceof String key && entry.getValue() instanceof Number value) {
							cache.put(key, value.doubleValue());
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
			Files.writeString(path, GSON.toJson(cache), StandardCharsets.UTF_8);
		} catch (Exception ignored) {
			// saving is best-effort
		}
	}
}
