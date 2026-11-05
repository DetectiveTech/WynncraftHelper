package com.detectivetech.wynncrafthelper.api;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Detects Wynncraft's global XP events (such as a Double XP Weekend) from the official
 * publisher API.
 *
 * <p>Wynntils only reports the bombs of the current world, so a server-wide event cannot be
 * read through the bomb bridge. The publisher articles expose a start and end date per event,
 * which lets the planner decide whether the event is currently running without user input.</p>
 *
 * <p>The API is polled in the background and the result is cached for a few minutes. The
 * interface is intentionally non-blocking: {@link #doubleXpActive()} always returns the last
 * known value and {@link #refreshAsync()} starts a fetch only when the cache is stale.</p>
 */
public final class WynncraftEvents {
	private static final String EVENTS_URL = "https://api.wynncraft.com/v3/publisher/articles/list/event";
	private static final String USER_AGENT = "WynncraftHelper/1.0.0";
	/** The article list is cached by the API for 2 minutes; 5 minutes is plenty. */
	private static final long CACHE_TTL_MS = 5L * 60L * 1000L;
	private static final int MAX_PAGES = 3;

	private static final Gson GSON = new Gson();
	private static final HttpClient HTTP = HttpClient.newBuilder()
		.connectTimeout(Duration.ofSeconds(10))
		.build();
	private static final ExecutorService EXECUTOR = Executors.newSingleThreadExecutor(runnable -> {
		Thread thread = new Thread(runnable, "WynncraftHelper-EventCheck");
		thread.setDaemon(true);
		return thread;
	});

	private static volatile boolean doubleXpActive;
	private static volatile long lastCheckAt;
	private static volatile boolean fetching;

	private WynncraftEvents() {
	}

	/** @return true when a Double XP event is currently running according to the last check. */
	public static boolean doubleXpActive() {
		return doubleXpActive;
	}

	/**
	 * Starts a background refresh when the cached result is older than
	 * {@link #CACHE_TTL_MS}. Safe to call every tick; concurrent calls are coalesced.
	 */
	public static void refreshAsync() {
		long now = System.currentTimeMillis();
		if (fetching || now - lastCheckAt < CACHE_TTL_MS) {
			return;
		}
		fetching = true;
		EXECUTOR.submit(() -> {
			try {
				boolean active = fetchDoubleXpActive();
				doubleXpActive = active;
			} catch (InterruptedException error) {
				Thread.currentThread().interrupt();
			} catch (Exception error) {
				// Keep the previous value on a transient API failure.
			} finally {
				lastCheckAt = System.currentTimeMillis();
				fetching = false;
			}
		});
	}

	private static boolean fetchDoubleXpActive() throws Exception {
		OffsetDateTime now = OffsetDateTime.now();
		for (int page = 1; page <= MAX_PAGES; page++) {
			JsonObject root = fetchPage(page);
			if (root == null) {
				return false;
			}
			JsonObject results = root.getAsJsonObject("results");
			if (results != null) {
				for (String key : results.keySet()) {
					JsonElement element = results.get(key);
					if (element == null || !element.isJsonObject()) {
						continue;
					}
					if (articleActive(element.getAsJsonObject(), now)) {
						return true;
					}
				}
			}
			if (!hasNextPage(root)) {
				break;
			}
		}
		return false;
	}

	private static JsonObject fetchPage(int page) throws Exception {
		HttpRequest request = HttpRequest.newBuilder()
			.uri(URI.create(EVENTS_URL + "?page=" + page))
			.timeout(Duration.ofSeconds(20))
			.header("User-Agent", USER_AGENT)
			.header("Accept", "application/json")
			.GET()
			.build();
		HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString());
		if (response.statusCode() < 200 || response.statusCode() >= 300) {
			return null;
		}
		JsonElement parsed = GSON.fromJson(response.body(), JsonElement.class);
		return parsed != null && parsed.isJsonObject() ? parsed.getAsJsonObject() : null;
	}

	private static boolean hasNextPage(JsonObject root) {
		JsonObject controller = root.getAsJsonObject("controller");
		if (controller == null) {
			return false;
		}
		JsonElement next = controller.get("next");
		return next != null && !next.isJsonNull();
	}

	private static boolean articleActive(JsonObject article, OffsetDateTime now) {
		JsonElement visible = article.get("visible");
		if (visible != null && visible.isJsonPrimitive() && !visible.getAsBoolean()) {
			return false;
		}
		OffsetDateTime start = parseDate(article.get("start_date"));
		OffsetDateTime end = parseDate(article.get("end_date"));
		if (start != null && now.isBefore(start)) {
			return false;
		}
		if (end != null && now.isAfter(end)) {
			return false;
		}
		String text = (string(article.get("title")) + " " + string(article.get("recap")))
			.toLowerCase(Locale.ROOT);
		return text.contains("double xp")
			|| text.contains("double experience")
			|| text.contains("2x xp")
			|| text.contains("2x experience");
	}

	private static OffsetDateTime parseDate(JsonElement element) {
		if (element == null || element.isJsonNull()) {
			return null;
		}
		try {
			return OffsetDateTime.parse(element.getAsString());
		} catch (DateTimeParseException | IllegalStateException ignored) {
			return null;
		}
	}

	private static String string(JsonElement element) {
		if (element == null || element.isJsonNull()) {
			return "";
		}
		try {
			return element.getAsString();
		} catch (IllegalStateException ignored) {
			return "";
		}
	}
}
