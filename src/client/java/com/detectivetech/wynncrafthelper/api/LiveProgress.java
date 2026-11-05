package com.detectivetech.wynncrafthelper.api;

import java.util.ArrayList;
import java.util.List;

/**
 * Lets another mod push real-time profession progress updates into the helper, so the
 * planner can refresh from a live source (such as the Wynntils mod) instead of polling the
 * Wynncraft API on a timer.
 */
public interface LiveProgress {
	void onProgress(String skill, int level, int progressPercent, int targetLevel);

	void onWorldBonus(String skill, boolean doubleXpWeekend, boolean xpBomb, boolean professionSpeed,
		boolean doubleXpEvent);

	void onCraftXp(String skill, long gainedXp);

	final class Bus implements AutoCloseable {
		static final List<LiveProgress> LISTENERS = new ArrayList<>();

		private Bus() {
		}

		@Override
		public void close() {
			synchronized (LISTENERS) {
				LISTENERS.clear();
			}
		}

		public static void register(LiveProgress listener) {
			if (listener == null) {
				return;
			}
			synchronized (LISTENERS) {
				LISTENERS.add(listener);
			}
		}

		public static void unregister(LiveProgress listener) {
			if (listener == null) {
				return;
			}
			synchronized (LISTENERS) {
				LISTENERS.remove(listener);
			}
		}

		public static void emit(String skill, int level, int progressPercent, int targetLevel) {
			List<LiveProgress> snapshot;
			synchronized (LISTENERS) {
				snapshot = new ArrayList<>(LISTENERS);
			}
			for (LiveProgress listener : snapshot) {
				listener.onProgress(skill, level, progressPercent, targetLevel);
			}
		}

		public static void emitWorldBonus(String skill, boolean doubleXpWeekend, boolean xpBomb,
			boolean professionSpeed, boolean doubleXpEvent) {
			List<LiveProgress> snapshot;
			synchronized (LISTENERS) {
				snapshot = new ArrayList<>(LISTENERS);
			}
			for (LiveProgress listener : snapshot) {
				listener.onWorldBonus(skill, doubleXpWeekend, xpBomb, professionSpeed, doubleXpEvent);
			}
		}

		public static void emitCraftXp(String skill, long gainedXp) {
			List<LiveProgress> snapshot;
			synchronized (LISTENERS) {
				snapshot = new ArrayList<>(LISTENERS);
			}
			for (LiveProgress listener : snapshot) {
				listener.onCraftXp(skill, gainedXp);
			}
		}
	}
}
