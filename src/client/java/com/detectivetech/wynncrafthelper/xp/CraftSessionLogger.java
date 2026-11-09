package com.detectivetech.wynncrafthelper.xp;

import com.detectivetech.wynncrafthelper.config.WynnPaths;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Logs every observed craft to {@code config/WynnHelper/craft-sessions.json} and
 * provides per-hour summary statistics (crafts/hour, XP/hour).
 *
 * <p>Each entry records: timestamp, skill, recipe name, XP gained, level before/after,
 * and the ingredients consumed. Entries are capped at 1000 (oldest dropped) to keep the
 * file size bounded.</p>
 */
public final class CraftSessionLogger {
	private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
	private static final String FILE_NAME = "craft-sessions.json";
	/** Single background writer so per-craft session logging never blocks the render thread. */
	private static final java.util.concurrent.ExecutorService IO_WRITER =
		java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
			Thread t = new Thread(r, "WynncraftHelper-SessionIO");
			t.setDaemon(true);
			return t;
		});

	private static List<Entry> entries = new ArrayList<>();
	private static boolean loaded = false;

	private CraftSessionLogger() {
	}

	public static void ensureLoaded() {
		if (loaded) {
			return;
		}
		loaded = true;
		try {
			Path path = WynnPaths.resolve(FILE_NAME);
			if (Files.exists(path)) {
				Data data = GSON.fromJson(Files.readString(path, StandardCharsets.UTF_8), Data.class);
				if (data != null && data.entries != null) {
					entries = data.entries;
				}
			}
		} catch (Exception ignored) {
			// start with an empty session log
		}
	}

	private static void save() {
		IO_WRITER.submit(() -> {
			try {
				Path path = WynnPaths.resolve(FILE_NAME);
				Data data = new Data();
				data.entries = entries;
				Files.writeString(path, GSON.toJson(data), StandardCharsets.UTF_8);
			} catch (Exception ignored) {
				// saving is best-effort
			}
		});
	}

	/**
	 * Records a single observed craft. Call from wherever craft XP gains are confirmed.
	 *
	 * @param skill             profession being trained
	 * @param recipeName        internal name of the recipe crafted (may be null)
	 * @param xpGained          XP awarded by Wynntils for this craft
	 * @param levelBefore       profession level before the craft
	 * @param levelAfter        profession level after the craft (same as before if unknown)
	 * @param ingredientsConsumed item name -> count consumed, or null
	 */
	public static void logCraft(String skill, String recipeName, long xpGained,
	                            int levelBefore, int levelAfter,
	                            Map<String, Integer> ingredientsConsumed) {
		ensureLoaded();
		Entry entry = new Entry();
		entry.timestamp = System.currentTimeMillis();
		entry.skill = skill;
		entry.recipeName = recipeName;
		entry.xpGained = xpGained;
		entry.levelBefore = levelBefore;
		entry.levelAfter = levelAfter;
		if (ingredientsConsumed != null) {
			entry.ingredientsConsumed.putAll(ingredientsConsumed);
		}
		entries.add(entry);
		// Cap the log so the JSON file doesn't grow without bound.
		while (entries.size() > 1000) {
			entries.remove(0);
		}
		save();
	}

	public static List<Entry> getEntries() {
		ensureLoaded();
		return new ArrayList<>(entries);
	}

	public static int totalCrafts() {
		ensureLoaded();
		return entries.size();
	}

	public static long totalXp() {
		ensureLoaded();
		long total = 0;
		for (Entry e : entries) {
			total += e.xpGained;
		}
		return total;
	}

	/** Crafts per hour across all logged entries (minimum 1 hour span to avoid divide-by-zero). */
	public static double craftsPerHour() {
		ensureLoaded();
		if (entries.isEmpty()) {
			return 0.0;
		}
		long span = entries.get(entries.size() - 1).timestamp - entries.get(0).timestamp;
		long hours = span / (1000L * 60L * 60L);
		if (hours <= 0) {
			hours = 1;
		}
		return (double) entries.size() / hours;
	}

	/** XP per hour across all logged entries. */
	public static double xpPerHour() {
		ensureLoaded();
		if (entries.isEmpty()) {
			return 0.0;
		}
		long totalXp = 0;
		for (Entry e : entries) {
			totalXp += e.xpGained;
		}
		long span = entries.get(entries.size() - 1).timestamp - entries.get(0).timestamp;
		long hours = span / (1000L * 60L * 60L);
		if (hours <= 0) {
			hours = 1;
		}
		return (double) totalXp / hours;
	}

	/** One observed craft. */
	public static final class Entry {
		public long timestamp;
		public String skill;
		public String recipeName;
		public long xpGained;
		public int levelBefore;
		public int levelAfter;
		public Map<String, Integer> ingredientsConsumed = new LinkedHashMap<>();
	}

	private static final class Data {
		List<Entry> entries = new ArrayList<>();
	}
}
