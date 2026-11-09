package com.detectivetech.wynncrafthelper.api;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.detectivetech.wynncrafthelper.config.WynnPaths;
import com.detectivetech.wynncrafthelper.util.MaterialNames;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class WynncraftApiClient {
	private static final Logger LOGGER = LoggerFactory.getLogger("WynncraftHelper");
	private static final String BASE_URL = "https://api.wynncraft.com/v3";
	private static final String WHOAMI_URL = BASE_URL + "/player/whoami";
	private static final String CHARACTERS_URL = BASE_URL + "/player/%s/characters";
	private static final String RECIPES_URL = BASE_URL + "/item/recipe/database?page=";
	private static final String ITEMS_URL = BASE_URL + "/item/database?page=";

	/**
	 * Baseline ingredient database behind https://wynnbuilder.github.io/ingredients/.
	 * The official API item scan is the primary source; the WynnBuilder baseline is merged
	 * in so every listed ingredient is available to the planner and the search, even when
	 * the API database lags behind the wiki.
	 */
	private static final String WYNNBUILDER_BASE_URL = "https://wynnbuilder.github.io";
	private static final String WYNBBUILDER_INGREDIENTS_URL = WYNNBUILDER_BASE_URL + "/data/baseline/compressed/ingreds_compress";

	private static final Gson GSON = new Gson();
	private static final HttpClient HTTP = HttpClient.newBuilder()
		.connectTimeout(Duration.ofSeconds(10))
		.build();
	private static final ExecutorService PAGE_POOL = Executors.newFixedThreadPool(4, runnable -> {
		Thread thread = new Thread(runnable, "WynncraftHelper-RecipeFetch");
		thread.setDaemon(true);
		return thread;
	});

	/** Background thread for the long ingredient scan, so it survives closing the screen. */
	private static final ExecutorService INGREDIENT_EXECUTOR = Executors.newSingleThreadExecutor(runnable -> {
		Thread thread = new Thread(runnable, "WynncraftHelper-IngredientScan");
		thread.setDaemon(true);
		return thread;
	});

	/** How long the on-disk recipe database is considered fresh. */
	private static final long RECIPE_CACHE_TTL_MS = 7L * 24L * 60L * 60L * 1000L;
	private static final String RECIPE_CACHE_FILE = "wynncrafthelper-recipes-cache.json";

	private static final String INGREDIENT_CACHE_FILE = "wynncrafthelper-ingredients-cache.json";
	/**
	 * Minimum spacing between item-database requests. Authenticated tokens get a
	 * 120 req/min bucket, so 560 ms keeps the 339-page scan at ~107 req/min.
	 * Guests only get 50 req/min, so without a token the scan slows to 1250 ms
	 * (~48 req/min) instead of tripping the rate limiter.
	 */
	private static final long AUTH_ITEM_REQUEST_INTERVAL_MS = 560L;
	private static final long GUEST_ITEM_REQUEST_INTERVAL_MS = 1250L;
	private static final AtomicLong NEXT_ITEM_REQUEST_AT = new AtomicLong();

	public interface ProgressListener {
		void onProgress(int loadedPages, int totalPages);
	}

	/**
	 * One or more Wynncraft API tokens (config keys {@code token} or {@code tokens}).
	 * Requests rotate through them, so a second token can share the download.
	 */
	private final List<String> tokens;
	private final AtomicInteger tokenCursor = new AtomicInteger();
	private final long itemRequestIntervalMs;

	public WynncraftApiClient() {
		this.tokens = loadTokens();
		this.itemRequestIntervalMs = tokens.isEmpty()
			? GUEST_ITEM_REQUEST_INTERVAL_MS
			: AUTH_ITEM_REQUEST_INTERVAL_MS;
	}

	/**
	 * Shuts down the shared background thread pools. Call once on client stop so the
	 * daemon threads do not linger across world changes or restarts.
	 */
	public static void shutdown() {
		PAGE_POOL.shutdownNow();
		INGREDIENT_EXECUTOR.shutdownNow();
		try {
			PAGE_POOL.awaitTermination(500, java.util.concurrent.TimeUnit.MILLISECONDS);
		} catch (InterruptedException ignored) {
			Thread.currentThread().interrupt();
		}
		try {
			INGREDIENT_EXECUTOR.awaitTermination(500, java.util.concurrent.TimeUnit.MILLISECONDS);
		} catch (InterruptedException ignored) {
			Thread.currentThread().interrupt();
		}
	}

	private List<String> loadTokens() {
		List<String> result = new ArrayList<>();
		try {
			Path path = WynnPaths.resolve("wynncrafthelper.json");
			if (!Files.exists(path)) {
				return result;
			}
			String json = Files.readString(path, StandardCharsets.UTF_8);
			JsonObject obj = JsonParser.parseString(json).getAsJsonObject();
			addToken(result, obj.get("token"));
			JsonElement list = obj.get("tokens");
			if (list != null && list.isJsonArray()) {
				for (JsonElement entry : list.getAsJsonArray()) {
					addToken(result, entry);
				}
			}
		} catch (Exception ignored) {
			// no token: the API still works with the stricter guest pacing
		}
		return result;
	}

	private static void addToken(List<String> tokens, JsonElement element) {
		if (element == null || !element.isJsonPrimitive()) {
			return;
		}
		String value = element.getAsString().trim();
		if (!value.isEmpty() && !tokens.contains(value)) {
			tokens.add(value);
		}
	}

	public String getToken() {
		return tokens.isEmpty() ? "" : tokens.get(0);
	}

	/** Rotates through the configured tokens; falls back to an empty bearer for guests. */
	private String nextToken() {
		if (tokens.isEmpty()) {
			return "";
		}
		int index = Math.floorMod(tokenCursor.getAndIncrement(), tokens.size());
		return tokens.get(index);
	}

	public CompletableFuture<CharacterData> fetchCharacter() {
		return CompletableFuture.supplyAsync(this::doFetchCharacter);
	}

	private CharacterData doFetchCharacter() {
		try {
			JsonObject whoami = sendGet(WHOAMI_URL, true);
			String username = null;
			for (Map.Entry<String, JsonElement> entry : whoami.entrySet()) {
				if (entry.getValue().isJsonObject()) {
					JsonObject info = entry.getValue().getAsJsonObject();
					if (info.has("username") && username == null) {
						username = info.get("username").getAsString();
					}
				}
			}
			if (username == null) {
				LOGGER.error("WynncraftHelper: could not get username from whoami");
				return null;
			}

			// Get active character UUID from player profile
			String activeUuid = null;
			try {
				JsonObject profile = sendGet(String.format("https://api.wynncraft.com/v3/player/%s", username), false);
				if (profile.has("activeCharacter") && !profile.get("activeCharacter").isJsonNull()) {
					activeUuid = profile.get("activeCharacter").getAsString();
				}
			} catch (Exception e) {
				LOGGER.warn("WynncraftHelper: could not get active character: {}", e.getMessage());
			}

			JsonObject characters = sendGet(String.format(CHARACTERS_URL, username), false);

			// Find best character UUID
			String bestUuid = null;
			int bestLevel = 0;
			for (Map.Entry<String, JsonElement> entry : characters.entrySet()) {
				if (!entry.getValue().isJsonObject()) {
					continue;
				}
				JsonObject c = entry.getValue().getAsJsonObject();
				int level = c.has("level") ? c.get("level").getAsInt() : 1;
				if (activeUuid != null && entry.getKey().equals(activeUuid)) {
					bestUuid = entry.getKey();
					break;
				}
				if (bestUuid == null || level > bestLevel) {
					bestUuid = entry.getKey();
					bestLevel = level;
				}
			}

			if (bestUuid == null) {
				LOGGER.error("WynncraftHelper: no characters found");
				return null;
			}

			// Fetch individual character to get professions
			JsonObject c = sendGet(String.format("https://api.wynncraft.com/v3/player/%s/characters/%s", username, bestUuid), false);

			CharacterData cData = new CharacterData();
			cData.username = username;
			cData.characterUuid = bestUuid;
			cData.characterType = c.has("type") ? c.get("type").getAsString() : "UNKNOWN";
			cData.level = c.has("level") ? c.get("level").getAsInt() : 1;
			cData.xp = c.has("xp") ? c.get("xp").getAsLong() : 0L;
			cData.xpPercent = c.has("xpPercent") ? c.get("xpPercent").getAsInt() : 0;
			cData.totalLevel = c.has("totalLevel") ? c.get("totalLevel").getAsInt() : 0;

			// Parse professions
			cData.professions = new java.util.LinkedHashMap<>();
			if (c.has("professions") && c.get("professions").isJsonObject()) {
				JsonObject profs = c.getAsJsonObject("professions");
				for (Map.Entry<String, JsonElement> profEntry : profs.entrySet()) {
					if (!profEntry.getValue().isJsonObject()) {
						continue;
					}
					JsonObject prof = profEntry.getValue().getAsJsonObject();
					CharacterData.ProfessionInfo info = new CharacterData.ProfessionInfo();
					info.level = prof.has("level") ? prof.get("level").getAsInt() : 0;
					info.xpPercent = prof.has("xpPercent") ? prof.get("xpPercent").getAsInt() : 0;
					cData.professions.put(profEntry.getKey().toLowerCase(), info);
				}
			}

			LOGGER.info("WynncraftHelper: fetched character {} with {} professions", cData.characterType, cData.professions.size());
			return cData;
		} catch (Exception e) {
			LOGGER.error("WynncraftHelper: fetchCharacter failed: {}", e.getMessage());
			return null;
		}
	}
	public CompletableFuture<List<Recipe>> fetchRecipes() {
		return fetchRecipes(null);
	}

	public CompletableFuture<List<Recipe>> fetchRecipes(ProgressListener listener) {
		return CompletableFuture.supplyAsync(() -> doFetchRecipes(listener));
	}

	private List<Recipe> doFetchRecipes(ProgressListener listener) {
		List<Recipe> cached = loadRecipeCache();
		if (cached != null) {
			if (listener != null) {
				listener.onProgress(0, 0);
			}
			return cached;
		}

		try {
			JsonObject firstPage = sendGet(RECIPES_URL + 1, false);
			int pages = readPageCount(firstPage);
			int expected = readTotalCount(firstPage);
			List<Recipe> recipes = new ArrayList<>(parseRecipes(firstPage));

			AtomicInteger completed = new AtomicInteger(1);
			if (listener != null) {
				listener.onProgress(completed.get(), pages);
			}

			List<Integer> pageNumbers = new ArrayList<>();
			List<CompletableFuture<List<Recipe>>> futures = new ArrayList<>();
			for (int page = 2; page <= pages; page++) {
				final int pageNumber = page;
				pageNumbers.add(pageNumber);
				futures.add(CompletableFuture.supplyAsync(() -> {
					try {
						return parseRecipes(sendGet(RECIPES_URL + pageNumber, false));
					} catch (InterruptedException interrupted) {
						Thread.currentThread().interrupt();
						throw new CompletionException(interrupted);
					} catch (IOException io) {
						throw new CompletionException(io);
					} finally {
						if (listener != null) {
							listener.onProgress(completed.incrementAndGet(), pages);
						}
					}
				}, PAGE_POOL));
			}

			// Pages that failed (rate limit or timeout) are retried once, sequentially.
			List<Integer> failedPages = new ArrayList<>();
			for (int index = 0; index < futures.size(); index++) {
				try {
					recipes.addAll(futures.get(index).join());
				} catch (CompletionException failure) {
					failedPages.add(pageNumbers.get(index));
				}
			}
			for (int page : failedPages) {
				recipes.addAll(parseRecipes(sendGet(RECIPES_URL + page, false)));
			}

			// Never cache a partial database: wrong suggestions for a week are worse than an error.
			if (expected > 0 && recipes.size() < expected) {
				throw new IOException("Recipe database incomplete (" + recipes.size() + " of " + expected + " recipes)");
			}
			saveRecipeCache(recipes);
			return recipes;
		} catch (InterruptedException interrupted) {
			Thread.currentThread().interrupt();
			throw new CompletionException(interrupted);
		} catch (IOException io) {
			throw new CompletionException(io);
		}
	}

	public CompletableFuture<List<Ingredient>> fetchIngredients() {
		return fetchIngredients(null);
	}

	public CompletableFuture<List<Ingredient>> fetchWynnbuilderIngredients() {
		return CompletableFuture.supplyAsync(() -> doFetchWynnbuilderIngredients(), INGREDIENT_EXECUTOR);
	}

	/**
	 * Scans the whole item database and keeps only ingredients. The scan is paced so it
	 * never trips the 120 req/min bucket. Progress is written to disk every few pages, so
	 * an interrupted scan resumes where it stopped instead of starting over.
	 */
	public CompletableFuture<List<Ingredient>> fetchIngredients(ProgressListener listener) {
		return CompletableFuture.supplyAsync(() -> doFetchIngredients(listener), INGREDIENT_EXECUTOR);
	}

	/** Complete, fresh cache if there is one, otherwise {@code null}. */
	public List<Ingredient> cachedIngredients() {
		IngredientCache cache = readIngredientCache();
		if (cache == null || !cache.complete || cache.ingredients == null || cache.ingredients.isEmpty()) {
			return null;
		}
		return cache.ingredients;
	}

	private List<Ingredient> doFetchIngredients(ProgressListener listener) {
		IngredientCache cache = readIngredientCache();
		if (cache != null && cache.savedAt > 0
			&& System.currentTimeMillis() - cache.savedAt > RECIPE_CACHE_TTL_MS) {
			cache = null;
		}

		if (cache != null && cache.complete && cache.ingredients != null) {
			if (listener != null) {
				listener.onProgress(cache.donePages == null ? 0 : cache.donePages.size(), cache.pages);
			}
			return cache.ingredients;
		}

		List<Ingredient> ingredients = cache != null && cache.ingredients != null
			? new ArrayList<>(cache.ingredients) : new ArrayList<>();
		Set<Integer> donePages = cache != null && cache.donePages != null
			? new HashSet<>(cache.donePages) : new HashSet<>();
		int pages = cache != null ? cache.pages : 0;
		int expected = cache != null ? cache.expected : 0;
		int items = cache != null ? cache.items : 0;

		try {
			if (pages <= 0) {
				JsonObject firstPage = sendGetPaced(ITEMS_URL + 1, false);
				pages = readPageCount(firstPage);
				expected = readTotalCount(firstPage);
				items += parseItemPage(firstPage, ingredients);
				donePages.add(1);
				saveIngredientCache(ingredients, donePages, pages, items, expected, false);
			}
			report(listener, donePages.size(), pages);

			for (int page = 1; page <= pages; page++) {
				if (donePages.contains(page)) {
					continue;
				}
				items += parseItemPage(sendGetPaced(ITEMS_URL + page, false), ingredients);
				donePages.add(page);
				if (donePages.size() % 5 == 0 || donePages.size() == pages) {
					saveIngredientCache(ingredients, donePages, pages, items, expected, false);
				}
				report(listener, donePages.size(), pages);
			}

			if (expected > 0 && items < expected) {
				saveIngredientCache(ingredients, donePages, pages, items, expected, false);
				throw new IOException("Item database incomplete (" + items + " of " + expected + " items)");
			}
			// Merge in the WynnBuilder baseline (https://wynnbuilder.github.io/ingredients/) so
			// the planner covers every ingredient listed there, not only the API subset.
			List<Ingredient> wynnbuilderIngredients = doFetchWynnbuilderIngredients();
			if (wynnbuilderIngredients != null) {
				for (Ingredient candidate : wynnbuilderIngredients) {
					if (candidate == null || candidate.name == null) {
						continue;
					}
					boolean known = false;
					for (Ingredient existing : ingredients) {
						if (existing != null && existing.name != null
							&& existing.name.equalsIgnoreCase(candidate.name)) {
							known = true;
							break;
						}
					}
					if (!known) {
						LOGGER.info("WynncraftHelper: adding WynnBuilder ingredient {}", candidate.name);
						ingredients.add(candidate);
					}
				}
			}
			saveIngredientCache(ingredients, donePages, pages, items, expected, true);
			return ingredients;
		} catch (InterruptedException interrupted) {
			saveIngredientCache(ingredients, donePages, pages, items, expected, false);
			Thread.currentThread().interrupt();
			throw new CompletionException(interrupted);
		} catch (IOException io) {
			saveIngredientCache(ingredients, donePages, pages, items, expected, false);
			throw new CompletionException(io);
		}
	}

	private static void report(ProgressListener listener, int done, int total) {
		if (listener != null) {
			listener.onProgress(done, total);
		}
	}

	private int parseItemPage(JsonObject response, List<Ingredient> out) {
		JsonElement results = response.get("results");
		if (results == null || !results.isJsonArray()) {
			return 0;
		}
		int items = 0;
		for (JsonElement element : results.getAsJsonArray()) {
			items++;
			if (!element.isJsonObject()) {
				continue;
			}
			JsonObject item = element.getAsJsonObject();
			if (!item.has("type") || !"ingredient".equals(item.get("type").getAsString())) {
				continue;
			}
			Ingredient ingredient = new Ingredient();
			ingredient.name = item.has("displayName") ? item.get("displayName").getAsString() : "Unknown ingredient";
			ingredient.internalName = item.has("internalName") ? item.get("internalName").getAsString() : ingredient.name;
			ingredient.tier = item.has("tier") ? item.get("tier").getAsString() : "TIER_0";
			ingredient.level = 1;
			ingredient.skills = new ArrayList<>();
			if (item.has("requirements") && item.get("requirements").isJsonObject()) {
				JsonObject requirements = item.getAsJsonObject("requirements");
				if (requirements.has("level")) {
					ingredient.level = requirements.get("level").getAsInt();
				}
				if (requirements.has("skills") && requirements.get("skills").isJsonArray()) {
					for (JsonElement skill : requirements.getAsJsonArray("skills")) {
						ingredient.skills.add(skill.getAsString());
					}
				}
			}
			out.add(ingredient);
		}
		return items;
	}

	/** Waits for this thread's slot before issuing an item-database request. */
	private JsonObject sendGetPaced(String url, boolean post) throws IOException, InterruptedException {
		long now = System.currentTimeMillis();
		long slot = NEXT_ITEM_REQUEST_AT.updateAndGet(previous -> Math.max(previous, now) + itemRequestIntervalMs);
		long wait = slot - now;
		if (wait > 0) {
			Thread.sleep(wait);
		}
		return sendGet(url, post);
	}

	private Path ingredientCachePath() {
		return WynnPaths.resolve(INGREDIENT_CACHE_FILE);
	}

	private List<Ingredient> loadIngredientCache() {
		IngredientCache cache = readIngredientCache();
		if (cache == null || cache.ingredients == null || cache.ingredients.isEmpty()) {
			return null;
		}
		return cache.ingredients;
	}

	private IngredientCache readIngredientCache() {
		try {
			Path path = ingredientCachePath();
			if (!Files.exists(path)) {
				return null;
			}
			IngredientCache cache = GSON.fromJson(Files.readString(path, StandardCharsets.UTF_8), IngredientCache.class);
			if (cache == null || cache.ingredients == null || cache.ingredients.isEmpty()) {
				return null;
			}
			if (cache.savedAt <= 0 || System.currentTimeMillis() - cache.savedAt > RECIPE_CACHE_TTL_MS) {
				return null;
			}
			return cache;
		} catch (Exception ignored) {
			return null;
		}
	}

	private void saveIngredientCache(List<Ingredient> ingredients, Set<Integer> donePages, int pages,
									 int items, int expected, boolean complete) {
		try {
			IngredientCache cache = new IngredientCache();
			cache.savedAt = System.currentTimeMillis();
			cache.complete = complete;
			cache.pages = pages;
			cache.items = items;
			cache.expected = expected;
			cache.donePages = new ArrayList<>(donePages);
			cache.ingredients = ingredients;
			Files.writeString(ingredientCachePath(), GSON.toJson(cache), StandardCharsets.UTF_8);
		} catch (Exception ignored) {
			// caching is best-effort
		}
	}

	public void invalidateIngredientCache() {
		try {
			Files.deleteIfExists(ingredientCachePath());
		} catch (Exception ignored) {
			// cache removal is best-effort
		}
	}

	private int readPageCount(JsonObject response) {
		JsonElement controller = response.get("controller");
		if (controller != null && controller.isJsonObject()) {
			JsonObject obj = controller.getAsJsonObject();
			if (obj.has("pages")) {
				try {
					return Math.max(1, obj.get("pages").getAsInt());
				} catch (Exception ignored) {
					// fall through
				}
			}
		}
		return 1;
	}

	private int readTotalCount(JsonObject response) {
		JsonElement controller = response.get("controller");
		if (controller != null && controller.isJsonObject()) {
			JsonObject obj = controller.getAsJsonObject();
			if (obj.has("count")) {
				try {
					return Math.max(0, obj.get("count").getAsInt());
				} catch (Exception ignored) {
					// fall through
				}
			}
		}
		return 0;
	}

	private List<Recipe> parseRecipes(JsonObject response) {
		List<Recipe> parsed = new ArrayList<>();
		JsonElement results = response.get("results");
		if (results == null || !results.isJsonArray()) {
			return parsed;
		}
		for (JsonElement element : results.getAsJsonArray()) {
			Recipe recipe = GSON.fromJson(element, Recipe.class);
			if (recipe != null && recipe.internalName != null && recipe.level != null) {
				canonicalizeMaterials(recipe);
				parsed.add(recipe);
			}
		}
		return parsed;
	}

	/**
	 * The recipe API still uses the pre-1.19 internal names ({@code "Refined Dernic Meat"})
	 * while the item the player holds is {@code "Dernic Meat"}. Strip that legacy prefix so
	 * the planner displays and calculates with the in-game name.
	 */
	private static void canonicalizeMaterials(Recipe recipe) {
		if (recipe == null || recipe.materials == null) {
			return;
		}
		for (RecipeMaterial material : recipe.materials) {
			if (material != null && material.item != null) {
				material.item = MaterialNames.canonical(material.item);
			}
		}
	}

	private Path recipeCachePath() {
		return WynnPaths.resolve(RECIPE_CACHE_FILE);
	}

	private List<Recipe> loadRecipeCache() {
		try {
			Path path = recipeCachePath();
			if (!Files.exists(path)) {
				return null;
			}
			String json = Files.readString(path, StandardCharsets.UTF_8);
			RecipeCache cache = GSON.fromJson(json, RecipeCache.class);
			if (cache == null || cache.recipes == null || cache.recipes.isEmpty()) {
				return null;
			}
			if (cache.savedAt <= 0 || System.currentTimeMillis() - cache.savedAt > RECIPE_CACHE_TTL_MS) {
				return null;
			}
			for (Recipe recipe : cache.recipes) {
				canonicalizeMaterials(recipe);
			}
			return cache.recipes;
		} catch (Exception ignored) {
			return null;
		}
	}

	public void invalidateRecipeCache() {
		try {
			Files.deleteIfExists(recipeCachePath());
		} catch (Exception ignored) {
			// cache removal is best-effort
		}
	}

	private void saveRecipeCache(List<Recipe> recipes) {
		try {
			RecipeCache cache = new RecipeCache();
			cache.savedAt = System.currentTimeMillis();
			cache.recipes = recipes;
			Files.writeString(recipeCachePath(), GSON.toJson(cache), StandardCharsets.UTF_8);
		} catch (Exception ignored) {
			// caching is best-effort
		}
	}

	private JsonObject sendGet(String url, boolean post) throws IOException, InterruptedException {
		IOException last = new IOException("API request failed");
		for (int attempt = 0; attempt < 3; attempt++) {
			HttpResponse<String> response;
			try {
				response = HTTP.send(buildRequest(url, post), HttpResponse.BodyHandlers.ofString());
			} catch (IOException network) {
				last = network;
				if (attempt < 2) {
					Thread.sleep(backoffMillis(null, attempt));
				}
				continue;
			}

			int status = response.statusCode();
			if (status == 200) {
				noteRateLimit(response);
				return JsonParser.parseString(response.body()).getAsJsonObject();
			}

			String body = response.body();
			last = new IOException("API error " + status
				+ (body == null || body.isEmpty() ? "" : ": " + shorten(body)));
			if (!isRetryable(status)) {
				throw last;
			}
			deferRequests(response);
			if (attempt < 2) {
				Thread.sleep(backoffMillis(response, attempt));
			}
		}
		throw last;
	}

	/** Same as {@link #sendGet(String, boolean)} but returns the raw JSON element, since the
	 *  WynnBuilder baseline is served as a top-level JSON array instead of a paged object. */
	private JsonElement sendGetElement(String url, boolean post) throws IOException, InterruptedException {
		IOException last = new IOException("API request failed");
		for (int attempt = 0; attempt < 3; attempt++) {
			HttpResponse<String> response;
			try {
				response = HTTP.send(buildRequest(url, post), HttpResponse.BodyHandlers.ofString());
			} catch (IOException network) {
				last = network;
				if (attempt < 2) {
					Thread.sleep(backoffMillis(null, attempt));
				}
				continue;
			}
			int status = response.statusCode();
			if (status == 200) {
				return JsonParser.parseString(response.body());
			}
			String body = response.body();
			last = new IOException("API error " + status
				+ (body == null || body.isEmpty() ? "" : ": " + shorten(body)));
			if (!isRetryable(status)) {
				throw last;
			}
			deferRequests(response);
			if (attempt < 2) {
				Thread.sleep(backoffMillis(response, attempt));
			}
		}
		throw last;
	}

	/**
	 * Downloads the WynnBuilder ingredient baseline and converts it to the mod's ingredient
	 * model. Returns {@code null} when the source is unreachable so the API scan can continue.
	 */
	private List<Ingredient> doFetchWynnbuilderIngredients() {
		try {
			JsonElement element = sendGetElement(WYNBBUILDER_INGREDIENTS_URL, false);
			return parseWynnbuilderIngredients(element);
		} catch (Exception ignored) {
			LOGGER.warn("WynncraftHelper: could not load the WynnBuilder ingredient database", ignored);
			return null;
		}
	}

	private List<Ingredient> parseWynnbuilderIngredients(JsonElement element) {
		List<Ingredient> result = new ArrayList<>();
		if (element == null || !element.isJsonArray()) {
			return result;
		}
		for (JsonElement entry : element.getAsJsonArray()) {
			if (!entry.isJsonObject()) {
				continue;
			}
			JsonObject item = entry.getAsJsonObject();
			if (!item.has("type") || !"ingredient".equals(item.get("type").getAsString())) {
				continue;
			}
			Ingredient ingredient = new Ingredient();
			ingredient.name = item.has("displayName") && item.get("displayName").isJsonPrimitive()
				? item.get("displayName").getAsString()
				: (item.has("name") && item.get("name").isJsonPrimitive() ? item.get("name").getAsString() : "Unknown ingredient");
			ingredient.internalName = ingredient.name;
			ingredient.tier = item.has("tier") && item.get("tier").isJsonPrimitive()
				? "TIER_" + item.get("tier").getAsInt()
				: "TIER_0";
			ingredient.level = item.has("lvl") ? item.get("lvl").getAsInt() : 1;
			ingredient.skills = new ArrayList<>();
			if (item.has("skills") && item.get("skills").isJsonArray()) {
				for (JsonElement skill : item.get("skills").getAsJsonArray()) {
					if (skill.isJsonPrimitive() && skill.getAsString() != null) {
						ingredient.skills.add(skill.getAsString());
					}
				}
			}
			result.add(ingredient);
		}
		return result;
	}

	private HttpRequest buildRequest(String url, boolean post) {
		HttpRequest.Builder builder = HttpRequest.newBuilder()
			.uri(URI.create(url))
			.timeout(Duration.ofSeconds(20))
			.header("Authorization", "Bearer " + nextToken())
			.header("User-Agent", "WynncraftHelper/1.0.0");
		if (post) {
			builder.POST(HttpRequest.BodyPublishers.noBody());
		} else {
			builder.GET();
		}
		return builder.build();
	}

	private static boolean isRetryable(int status) {
		return status == 429 || status == 500 || status == 502 || status == 503 || status == 504;
	}

	/**
	 * Pushes the next paced request back when the server tells us to slow down, using
	 * either {@code Retry-After} or the {@code RateLimit-Reset} bucket hint.
	 */
	private static void deferRequests(HttpResponse<?> response) {
		long delayMs = Math.max(retryAfterMillis(response), rateLimitResetMillis(response));
		if (delayMs > 0) {
			long resumeAt = System.currentTimeMillis() + delayMs;
			NEXT_ITEM_REQUEST_AT.accumulateAndGet(resumeAt, Math::max);
		}
	}

	/** Avoids spending the last request of a bucket; the next paced call waits for reset. */
	private static void noteRateLimit(HttpResponse<?> response) {
		String remaining = header(response, "ratelimit-remaining");
		if (remaining == null) {
			return;
		}
		try {
			if (Integer.parseInt(remaining.trim()) <= 1) {
				deferRequests(response);
			}
		} catch (NumberFormatException ignored) {
			// ignore malformed headers
		}
	}

	private static long retryAfterMillis(HttpResponse<?> response) {
		String value = header(response, "retry-after");
		if (value == null) {
			return 0L;
		}
		try {
			return Math.max(0L, Long.parseLong(value.trim()) * 1000L);
		} catch (NumberFormatException ignored) {
			return 0L;
		}
	}

	private static long rateLimitResetMillis(HttpResponse<?> response) {
		String value = header(response, "ratelimit-reset");
		if (value == null) {
			return 0L;
		}
		try {
			return Math.max(0L, Long.parseLong(value.trim()) * 1000L);
		} catch (NumberFormatException ignored) {
			return 0L;
		}
	}

	private static String header(HttpResponse<?> response, String name) {
		return response == null ? null : response.headers().firstValue(name).orElse(null);
	}

	/**
	 * Waits on a 429 using the server's {@code ratelimit-reset} hint. The scan runs on
	 * background threads, so a longer cap is fine and keeps the client polite.
	 */
	private static long backoffMillis(HttpResponse<?> response, int attempt) {
		long wait = 700L * (1L << Math.min(attempt, 3));
		wait = Math.max(wait, rateLimitResetMillis(response));
		return Math.min(wait, 30000L);
	}

	private static String shorten(String body) {
		String cleaned = body.replaceAll("\\s+", " ").trim();
		return cleaned.length() <= 120 ? cleaned : cleaned.substring(0, 120) + "…";
	}

	private static class RecipeCache {
		long savedAt;
		List<Recipe> recipes;
	}

	private static class IngredientCache {
		long savedAt;
		boolean complete;
		int pages;
		int items;
		int expected;
		List<Integer> donePages;
		List<Ingredient> ingredients;
	}
}
