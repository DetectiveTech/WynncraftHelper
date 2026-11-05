package com.detectivetech.wynncrafthelper.api;

import java.util.List;

/**
 * Single background owner of the ingredient database.
 *
 * <p>The item-database scan takes around three minutes, so it must not be tied to a
 * screen instance: closing the planner keeps the scan running, and if the game is closed
 * mid-scan the API resumes from the partially written cache file on the next try.</p>
 */
public final class IngredientStore {
	private static final WynncraftApiClient API = new WynncraftApiClient();

	private static volatile List<Ingredient> ingredients = List.of();
	private static volatile boolean started;
	private static volatile boolean loading;
	private static volatile boolean complete;
	private static volatile int donePages;
	private static volatile int totalPages;
	private static volatile String error;

	private IngredientStore() {
	}

	/** Starts the scan on first use; later calls are no-ops. */
	public static synchronized void ensureLoaded() {
		if (started) {
			return;
		}
		started = true;

		List<Ingredient> cached = API.cachedIngredients();
		if (cached != null && !cached.isEmpty()) {
			ingredients = List.copyOf(cached);
			complete = true;
			return;
		}

		loading = true;
		API.fetchIngredients((done, total) -> {
			donePages = done;
			totalPages = total;
		}).whenComplete((list, failure) -> {
			loading = false;
			if (failure != null || list == null || list.isEmpty()) {
				error = rootMessage(failure);
			} else {
				ingredients = List.copyOf(list);
				complete = true;
				donePages = totalPages;
			}
		});
	}

	/** Restarts after a failure; a partial cache continues where it stopped. */
	public static synchronized void retry() {
		if (loading) {
			return;
		}
		if (complete) {
			return;
		}
		started = false;
		error = null;
		ensureLoaded();
	}

	public static List<Ingredient> ingredients() {
		return ingredients;
	}

	public static boolean loading() {
		return loading;
	}

	public static boolean complete() {
		return complete;
	}

	public static int donePages() {
		return donePages;
	}

	public static int totalPages() {
		return totalPages;
	}

	public static String error() {
		return error;
	}

	private static String rootMessage(Throwable throwable) {
		Throwable current = throwable;
		while (current != null && current.getCause() != null) {
			current = current.getCause();
		}
		if (current == null) {
			return "unknown error";
		}
		String message = current.getMessage();
		return message == null ? current.getClass().getSimpleName() : message;
	}
}
