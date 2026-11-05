package com.detectivetech.wynncrafthelper.screen;
import com.detectivetech.wynncrafthelper.WynncraftHelperClient;
import com.detectivetech.wynncrafthelper.api.CharacterData;
import com.detectivetech.wynncrafthelper.api.Ingredient;
import com.detectivetech.wynncrafthelper.api.IngredientStore;
import com.detectivetech.wynncrafthelper.api.Recipe;
import com.detectivetech.wynncrafthelper.api.RecipeMaterial;
import com.detectivetech.wynncrafthelper.api.WynncraftApiClient;
import com.detectivetech.wynncrafthelper.api.WynncraftEvents;
import com.detectivetech.wynncrafthelper.compat.WynntilsBridge;
import com.detectivetech.wynncrafthelper.config.ProfessionConfig;
import com.detectivetech.wynncrafthelper.config.ProfessionXp;
import com.detectivetech.wynncrafthelper.hud.HudGoal;
import com.detectivetech.wynncrafthelper.hud.HudState;
import com.detectivetech.wynncrafthelper.util.MaterialNames;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
public class WynncraftHelperScreen extends Screen {
	private static final Logger LOGGER = LoggerFactory.getLogger("WynncraftHelper");
	// ---------------------------------------------------------------- layout
	private static final int PANEL_W = 540;
	/** Tall enough for the content plus the footer without overlapping the buttons. */
	private static final int PANEL_H = 470;
	private static final int HEADER_H = 48;
	private static final int FOOTER_H = 40;
	private static final int PAD = 16;
	private static final int LEFT_W = 250;
	private static final int COL_GAP = 20;
	private static final int RIGHT_W = PANEL_W - PAD * 2 - LEFT_W - COL_GAP;
	private static final int BODY_TOP = HEADER_H + 12;
	private static final int CARD_H = 74;
	private static final int CARD_GAP = 8;
	// ---------------------------------------------------------------- ingredient model
	/** Ingredient slots per craft. */
	private static final int INGREDIENT_SLOTS = 6;
	/**
	 * Relative XP one ingredient adds for each tier (T0..T3). Community measurements:
	 * x2 / x2.5 / x3.5 / x5 for a single ingredient, i.e. bonuses of 1 / 1.5 / 2.5 / 4.
	 * The bonus is additionally scaled by ingredientLevel / recipeLevel, because the game
	 * rewards ingredients close to the recipe level and barely rewards low-level ones.
	 */
	private static final double[] INGREDIENT_TIER_WEIGHT = {1.0, 1.5, 2.5, 4.0};
	// ---------------------------------------------------------------- colors
	private static final int C_BG = 0xFF12151A;
	private static final int C_HEADER = 0xFF171B21;
	private static final int C_BORDER = 0xFF2B313B;
	private static final int C_CARD = 0xFF1A1F26;
	private static final int C_CARD_BORDER = 0xFF272E38;
	private static final int C_DIVIDER = 0xFF232932;
	private static final int C_TEXT = 0xFFF2F5F8;
	private static final int C_MUTED = 0xFF8A93A0;
	private static final int C_LABEL = 0xFF6E7A88;
	private static final int C_ACCENT = 0xFF57D98A;
	private static final int C_INGREDIENT = 0xFF7FB6FF;
	private static final int C_GOLD = 0xFFFFC24B;
	private static final int C_SILVER = 0xFFC3CAD4;
	private static final int C_BRONZE = 0xFFD08A5A;
	private static final int C_ERROR = 0xFFFF6B6B;
	// ---------------------------------------------------------------- cache
	private static final long CHARACTER_TTL_MS = 2L * 60L * 1000L;
	private static final long RECIPE_MEM_TTL_MS = 10L * 60L * 1000L;
	private static CharacterData cachedData;
	private static long cachedDataAt;
	private static List<Recipe> cachedRecipes;
	private static long cachedRecipesAt;
	private static final DecimalFormat NUMBER = new DecimalFormat("#,##0", DecimalFormatSymbols.getInstance(Locale.ROOT));
	private static final DecimalFormat NUMBER_ONE = new DecimalFormat("#,##0.#", DecimalFormatSymbols.getInstance(Locale.ROOT));
	private final WynncraftApiClient api = new WynncraftApiClient();
	private CharacterData data;
	private List<Recipe> recipes = List.of();
	private List<Ingredient> ingredients = List.of();
	private String selectedSkill = "jeweling";
	private int professionLevel = 1;
	private int progressPercent;
	private int targetLevel = 2;
	private final List<CraftOption> options = new ArrayList<>();
	private final List<String> craftableTypes = new ArrayList<>();
	private final Map<String, Recipe> craftableTypeRecipe = new LinkedHashMap<>();
	private String selectedCraftType;
	private final Map<String, Long> planMaterials = new LinkedHashMap<>();
	private final Map<String, Ingredient> bestIngredientCache = new HashMap<>();
	private long planCrafts;
	private long planBaseCrafts;
	private long planMaterialCount;
	private long planIngredientCount;
	private long planXp;
	private double currentXpPerCraft;
	private double currentIngredientMultiplier = 1.0;
	private String currentIngredientName;
	private long liveCraftXp;
	private boolean doubleXpWeekend;
	private boolean xpBomb;
	private boolean professionSpeed;
	private boolean doubleXpEvent;
	private boolean showMaterials;
	private boolean updatingInput;
	private int ingredientPreset;
	private boolean loading = true;
	private boolean recipesDone;
	private int pagesDone;
	private int pagesTotal;
	private boolean ingredientsLoading;
	private int ingredientPagesDone;
	private int ingredientPagesTotal;
	private String error;
	private String statusNote;
	private int panelX;
	private int panelY;
	private Button professionButton;
	private Button levelMinus;
	private Button levelPlus;
	private Button progressMinus;
	private Button progressPlus;
	private Button targetMinus;
	private Button targetPlus;
	private Button ingredientButton;
	private Button viewToggle;
	private EditBox levelInput;
	private EditBox progressInput;
	private EditBox ingredientSearch;
	private Button autoIngredientButton;
	private final List<Ingredient> searchResults = new ArrayList<>();
	private boolean searchOpen;
	private String searchMessage;
	private String searchHint;
	private Ingredient pinned;
	private String pinnedName;
	// ---------------------------------------------------------------- recipe breakdown overlay
	private CraftOption detailOption;
	private List<BreakdownLevel> detailLevels = List.of();
	private final Ingredient[] detailTierIngredients = new Ingredient[4];
	private final double[] detailTierMultipliers = new double[4];
	private final long[] detailTierCrafts = new long[4];
	private int detailTierTopY; private int craftableTypeFirstY;
	private int detailScroll;
	private int detailMaxScroll;
	private int detailLevelAreaTop;
	private int detailLevelAreaBottom;
	private Button reloadButton;
	private Button weekendButton;
	private int pinButtonX;
	private int pinButtonY;
	private int pinButtonW;
	private int pinButtonH;
	public WynncraftHelperScreen(Minecraft client) {
		super(Component.literal("Wynncraft Helper"));
	}
	@Override
	protected void init() {
		computeLayout();
		// Prefer live Wynntils tracking over the saved static values when the menu opens.
		String liveSkill = ProfessionConfig.selected();
		int liveLevel = WynntilsBridge.professionLevel(liveSkill);
		double liveProgress = WynntilsBridge.professionProgressPercent(liveSkill);
		if (liveLevel > 0) {
			ProfessionConfig.setLevel(liveSkill, liveLevel);
		}
		if (liveProgress >= 0) {
			ProfessionConfig.setProgress(liveSkill,
				(int) Math.max(0, Math.min(99, Math.round(liveProgress))));
		}
		doubleXpWeekend = ProfessionConfig.doubleXpWeekend();
		Boolean liveXpBomb = WynntilsBridge.professionXpBombActive();
		xpBomb = liveXpBomb != null ? liveXpBomb : ProfessionConfig.xpBomb();
		professionSpeed = WynntilsBridge.professionSpeedBombActive() == Boolean.TRUE;
		WynncraftEvents.refreshAsync();
		doubleXpEvent = WynncraftEvents.doubleXpActive();
		ingredientPreset = ProfessionConfig.ingredientPreset();
		buildWidgets();
		long now = System.currentTimeMillis();
		if (cachedRecipes != null && now - cachedRecipesAt < RECIPE_MEM_TTL_MS) {
			recipes = cachedRecipes;
			recipesDone = true;
			loading = false;
			statusNote = NUMBER.format(recipes.size()) + " recipes cached";
			if (cachedData != null && now - cachedDataAt < CHARACTER_TTL_MS) {
				data = cachedData;
				syncProfessionsFromApi(cachedData);
			} else {
				// Fetch character data even when recipes are cached
				api.fetchCharacter().thenAcceptAsync(character -> {
					if (character != null) {
						data = character;
						cachedData = character;
						cachedDataAt = System.currentTimeMillis();
						syncProfessionsFromApi(character);
					}
				}, this.screenExecutor);
			}
			recompute();
			syncIngredients();
			updateWidgetStates();
		} else {
			startFetch();
		}
		syncIngredients();
		LOGGER.info("WynncraftHelperScreen init: panel={}x{} at {},{} (screen {}x{})",
			PANEL_W, PANEL_H, panelX, panelY, this.width, this.height);
	}
	private void computeLayout() {
		int width = Math.min(PANEL_W, this.width - 8);
		panelX = Math.max(2, (this.width - width) / 2);
		panelY = Math.max(2, (this.height - PANEL_H) / 2);
	}
	private void buildWidgets() {
		int left = panelX + PAD;
		int top = panelY + BODY_TOP;
		professionButton = Button.builder(Component.literal(professionLabel()), button -> {
			ProfessionConfig.cycleSelected(1);
			refreshProfession();
		}).pos(left, top).size(LEFT_W, 20).build();
		addRenderableWidget(professionButton);
		levelInput = new EditBox(this.font, left + 130, top + 22, 50, 20, Component.literal("Level"));
		levelInput.setMaxLength(3);
		levelInput.setValue(String.valueOf(professionLevel));
		levelInput.setResponder(text -> {
			if (updatingInput) {
				return;
			}
			try {
				int newLevel = Integer.parseInt(text.trim());
				if (newLevel < professionLevel) {
					newLevel = professionLevel; // don't allow going down
				}
				if (newLevel > ProfessionXp.MAX_LEVEL) {
					newLevel = ProfessionXp.MAX_LEVEL;
				}
				ProfessionConfig.setLevel(selectedSkill, newLevel);
				refreshProfession();
			} catch (NumberFormatException ignored) {
				// invalid input, ignore
			}
		});
		addRenderableWidget(levelInput);
		levelMinus = Button.builder(Component.literal("-"), button -> { }).pos(left + 196, top + 22).size(20, 20).build();
		levelMinus.active = false;
		addRenderableWidget(levelMinus);
		levelPlus = Button.builder(Component.literal("+"), button -> {
			ProfessionConfig.setLevel(selectedSkill, professionLevel + 1);
			refreshProfession();
		}).pos(left + 226, top + 22).size(20, 20).build();
		addRenderableWidget(levelPlus);
		progressInput = new EditBox(this.font, left + 130, top + 44, 50, 20, Component.literal("Progress"));
		progressInput.setMaxLength(2);
		progressInput.setValue(String.valueOf(progressPercent));
		progressInput.setResponder(text -> {
			if (updatingInput) {
				return;
			}
			try {
				int newProgress = Integer.parseInt(text.trim());
				if (newProgress < 0) {
					newProgress = 0;
				}
				if (newProgress > 99) {
					newProgress = 99;
				}
				ProfessionConfig.setProgress(selectedSkill, newProgress);
				refreshProfession();
			} catch (NumberFormatException ignored) {
				// invalid input, ignore
			}
		});
		addRenderableWidget(progressInput);
		progressMinus = Button.builder(Component.literal("-"), button -> {
			ProfessionConfig.setProgress(selectedSkill, progressPercent - 5);
			refreshProfession();
		}).pos(left + 196, top + 44).size(20, 20).build();
		addRenderableWidget(progressMinus);
		progressPlus = Button.builder(Component.literal("+"), button -> {
			ProfessionConfig.setProgress(selectedSkill, progressPercent + 5);
			refreshProfession();
		}).pos(left + 226, top + 44).size(20, 20).build();
		addRenderableWidget(progressPlus);
		targetMinus = Button.builder(Component.literal("-"), button -> changeTarget(-1))
			.pos(left + 196, top + 66).size(20, 20).build();
		addRenderableWidget(targetMinus);
		targetPlus = Button.builder(Component.literal("+"), button -> changeTarget(1))
			.pos(left + 226, top + 66).size(20, 20).build();
		addRenderableWidget(targetPlus);
		// Double XP Weekend is a global event, so it cannot be read from a bomb. Keep a
		// one-click toggle here; it persists in the config and feeds the same 2x XP path
		// as the Profession XP Bomb.
		weekendButton = Button.builder(Component.literal(weekendLabel()), button -> {
			doubleXpWeekend = !doubleXpWeekend;
			ProfessionConfig.setDoubleXpWeekend(doubleXpWeekend);
			// A measurement from the previous XP state no longer applies.
			liveCraftXp = 0;
			button.setMessage(Component.literal(weekendLabel()));
			refreshProfession();
		}).pos(left, top + 220).size(LEFT_W, 18).build();
		addRenderableWidget(weekendButton);
		ingredientSearch = new EditBox(this.font, left, top + 264, LEFT_W - 52, 20,
			Component.literal("Search ingredient"));
		ingredientSearch.setHint(Component.literal("Search ingredient…"));
		ingredientSearch.setMaxLength(40);
		ingredientSearch.setResponder(this::updateIngredientSearch);
		addRenderableWidget(ingredientSearch);
		autoIngredientButton = Button.builder(Component.literal("Auto"), button -> clearPinnedIngredient())
			.pos(left + LEFT_W - 48, top + 264).size(48, 20).build();
		addRenderableWidget(autoIngredientButton);
		ingredientButton = Button.builder(Component.literal(ingredientLabel()), button -> {
			ingredientPreset = (ingredientPreset + 1) % 5;
			ProfessionConfig.setIngredientPreset(ingredientPreset);
			refreshLabels();
			recompute();
		}).pos(left, top + 286).size(LEFT_W, 20).build();
		addRenderableWidget(ingredientButton);
		int right = panelX + PAD + LEFT_W + COL_GAP;
		viewToggle = Button.builder(Component.literal(showMaterials ? "Crafts" : "Materials"), button -> {
			showMaterials = !showMaterials;
			button.setMessage(Component.literal(showMaterials ? "Crafts" : "Materials"));
		}).pos(right + RIGHT_W - 92, top + 2).size(92, 18).build();
		addRenderableWidget(viewToggle);
		int buttonY = panelY + PANEL_H - 30;
		reloadButton = Button.builder(Component.literal("Reload"), button -> reload())
			.pos(panelX + PANEL_W - PAD - 64 - 8 - 84, buttonY)
			.size(84, 20)
			.build();
		addRenderableWidget(reloadButton);
		Button closeButton = Button.builder(Component.literal("Close"), button -> onClose())
			.pos(panelX + PANEL_W - PAD - 64, buttonY)
			.size(64, 20)
			.build();
		addRenderableWidget(closeButton);
		updateWidgetStates();
	}
	private void changeLevel(int delta) {
		// Don't allow going below the current level
		int newLevel = professionLevel + delta;
		if (newLevel < professionLevel) {
			newLevel = professionLevel;
		}
		ProfessionConfig.setLevel(selectedSkill, newLevel);
		refreshProfession();
	}
	private void changeProgress(int delta) {
		ProfessionConfig.setProgress(selectedSkill, progressPercent + delta);
		refreshProfession();
	}
	private void changeTarget(int delta) {
		ProfessionConfig.setTarget(selectedSkill, targetLevel + delta);
		refreshProfession();
	}
	private void refreshProfession() {
		searchOpen = false;
		searchResults.clear();
		searchMessage = null;
		searchHint = null;
		closeBreakdown();
		recompute();
		refreshLabels();
		updateWidgetStates();
	}
	/**
	 * Applies a real-time profession progress push from a live source (e.g. the WynUtils
	 * mod) instead of the periodic Wynncraft API refresh. Called on the render/update thread
	 * by the live progress bus when another mod reports a new level, progress or target.
	 */
	public void onLiveProgress(String skill, int level, int progressPercent, int targetLevel) {
		if (skill == null || !skill.equalsIgnoreCase(selectedSkill)) {
			return;
		}
		int newLevel = level > 0 ? Math.min(level, ProfessionXp.MAX_LEVEL) : professionLevel;
		int newProgress = Math.max(0, Math.min(progressPercent, 99));
		int newTarget = targetLevel > 0 ? Math.min(targetLevel, ProfessionXp.MAX_LEVEL) : professionLevel + 1;
		if (newLevel == professionLevel && newProgress == progressPercent && newTarget == targetLevel) {
			return;
		}
		ProfessionConfig.setLevel(selectedSkill, newLevel);
		ProfessionConfig.setProgress(selectedSkill, newProgress);
		ProfessionConfig.setTarget(selectedSkill, newTarget);
		refreshProfession();
	}
	/**
	 * Applies a real-time world bonus push (double XP weekend / XP bomb) for the currently
	 * selected skill, as reported by a live source such as the Wynntils mod, then refreshes the
	 * craft plan so XP estimates pick up the new multiplier.
	 */
	public void onWorldBonus(String skill, boolean doubleXpWeekend, boolean xpBomb, boolean professionSpeed,
		boolean doubleXpEvent) {
		if (skill != null && !skill.equalsIgnoreCase(selectedSkill)) {
			return;
		}
		boolean xpStateChanged = this.doubleXpWeekend != doubleXpWeekend || this.xpBomb != xpBomb
			|| this.doubleXpEvent != doubleXpEvent;
		ProfessionConfig.setDoubleXpWeekend(doubleXpWeekend);
		ProfessionConfig.setXpBomb(xpBomb);
		this.doubleXpWeekend = doubleXpWeekend;
		this.xpBomb = xpBomb;
		this.professionSpeed = professionSpeed;
		this.doubleXpEvent = doubleXpEvent;
		if (xpStateChanged) {
			// The last measured craft XP already included the old world multipliers.
			liveCraftXp = 0;
		}
		refreshProfession();
	}
	/**
	 * Applies a real-time gained-XP push from a live source (e.g. the Wynntils mod reporting
	 * how much XP a craft actually yielded) and refreshes the plan, so the craft counts use the
	 * real per-craft XP instead of the heuristic estimate when a live value is available.
	 */
	public void onCraftXp(String skill, long gainedXp) {
		if (skill == null || !skill.equalsIgnoreCase(selectedSkill)) {
			return;
		}
		if (gainedXp <= 0) {
			return;
		}
		liveCraftXp = gainedXp;
		refreshProfession();
	}

	public void applyWynntilsProgress(String skill, int level, int progressPercent) {
		if (skill == null || !skill.equalsIgnoreCase(selectedSkill)) {
			return;
		}
		if (level > 0) {
			ProfessionConfig.setLevel(skill, level);
		}
		ProfessionConfig.setProgress(skill, Math.max(0, Math.min(progressPercent, 99)));
		refreshProfession();
	}

	public void setLiveCraftXp(long gainedXp) {
		if (gainedXp > 0) {
			liveCraftXp = gainedXp;
			refreshProfession();
		}
	}

	/**
	 * Re-evaluates the craft plan after the player changed world (for example entered an XP
	 * boss arena or toggled an XP bomb elsewhere), so the estimated craft counts reflect the
	 * current world bonus state.
	 */
	public void refreshForWorldChange() {
		boolean nextDoubleXp = ProfessionConfig.doubleXpWeekend();
		Boolean liveXpBomb = WynntilsBridge.professionXpBombActive();
		boolean nextXpBomb = liveXpBomb != null ? liveXpBomb : ProfessionConfig.xpBomb();
		WynncraftEvents.refreshAsync();
		boolean nextDoubleXpEvent = WynncraftEvents.doubleXpActive();
		boolean xpStateChanged = this.doubleXpWeekend != nextDoubleXp || this.xpBomb != nextXpBomb
			|| this.doubleXpEvent != nextDoubleXpEvent;
		doubleXpWeekend = nextDoubleXp;
		xpBomb = nextXpBomb;
		doubleXpEvent = nextDoubleXpEvent;
		professionSpeed = WynntilsBridge.professionSpeedBombActive() == Boolean.TRUE;
		if (xpStateChanged) {
			liveCraftXp = 0;
		}
		refreshProfession();
	}
	private void refreshLabels() {
		if (professionButton != null) {
			professionButton.setMessage(Component.literal(professionLabel()));
		}
		if (ingredientButton != null) {
			ingredientButton.setMessage(Component.literal(ingredientLabel()));
		}
		if (weekendButton != null) {
			weekendButton.setMessage(Component.literal(weekendLabel()));
		}
		if (levelInput != null) {
			updatingInput = true;
			levelInput.setValue(String.valueOf(professionLevel));
			updatingInput = false;
		}
		if (progressInput != null) {
			updatingInput = true;
			progressInput.setValue(String.valueOf(progressPercent));
			updatingInput = false;
		}
	}
	private String professionLabel() {
		String skill = ProfessionConfig.selected();
		return "<  " + ProfessionConfig.displayName(skill) + "  >";
	}
	private String weekendLabel() {
		return "Double XP Weekend: " + (doubleXpWeekend ? "ON" : "OFF");
	}
	private void updateWidgetStates() {
		boolean ready = !recipes.isEmpty();
		if (professionButton != null) {
			professionButton.active = ready;
		}
		Button[] steppers = {levelMinus, levelPlus, progressMinus, progressPlus, targetMinus, targetPlus};
		for (Button stepper : steppers) {
			if (stepper != null) {
				stepper.active = ready;
			}
		}
		// Disable levelMinus (can't go below current level), keep levelPlus for when user levels up
		if (levelMinus != null) {
			levelMinus.active = false;
		}
		if (levelPlus != null) {
			levelPlus.active = ready && professionLevel < ProfessionXp.MAX_LEVEL;
		}
		if (targetMinus != null) {
			targetMinus.active = ready && targetLevel > professionLevel + 1;
		}
		if (targetPlus != null) {
			targetPlus.active = ready && targetLevel < ProfessionXp.MAX_LEVEL;
		}
		if (ingredientButton != null) {
			ingredientButton.active = ready && !ingredientsLoading && pinned == null;
		}
		if (ingredientSearch != null) {
			ingredientSearch.setEditable(ready && !ingredients.isEmpty());
			ingredientSearch.active = ready && !ingredients.isEmpty();
		}
		if (levelInput != null) {
			levelInput.setEditable(ready);
		}
		if (progressInput != null) {
			progressInput.setEditable(ready);
		}
		if (viewToggle != null) {
			viewToggle.active = ready;
		}
		if (reloadButton != null) {
			reloadButton.active = !loading;
		}
	}
	private void startFetch() {
		loading = true;
		error = null;
		recipesDone = false;
		pagesDone = 0;
		pagesTotal = 0;
		api.fetchCharacter().thenAcceptAsync(character -> {
			if (character != null) {
				data = character;
				cachedData = character;
				cachedDataAt = System.currentTimeMillis();
				syncProfessionsFromApi(character);
			}
		}, this.screenExecutor);
		api.fetchRecipes((loaded, total) -> {
			if (total > 0) {
				this.screenExecutor.execute(() -> {
					pagesDone = loaded;
					pagesTotal = total;
				});
			}
		}).thenAcceptAsync(recipeList -> {
			recipesDone = true;
			loading = false;
			if (recipeList == null || recipeList.isEmpty()) {
				error = "Could not load the Wynncraft recipe database.";
			} else {
				recipes = recipeList;
				cachedRecipes = recipeList;
				cachedRecipesAt = System.currentTimeMillis();
				statusNote = NUMBER.format(recipeList.size()) + " recipes cached";
				recompute();
				syncIngredients();
			}
			updateWidgetStates();
		}, this.screenExecutor).exceptionally(throwable -> {
			this.screenExecutor.execute(() -> {
				recipesDone = true;
				loading = false;
				error = "Recipe request failed: " + rootMessage(throwable);
				updateWidgetStates();
			});
			return null;
		});
	}
	/**
	 * Mirrors the background ingredient scan. The scan itself lives in
	 * {@link IngredientStore}, so it keeps running while the screen is closed and resumes
	 * from its partial cache after a restart.
	 */
	private void syncIngredients() {
		IngredientStore.ensureLoaded();
		List<Ingredient> latest = IngredientStore.ingredients();
		if (latest != ingredients) {
			ingredients = latest;
			if (!recipes.isEmpty()) {
				recompute();
				refreshLabels();
				updateWidgetStates();
			}
		}
		ingredientsLoading = IngredientStore.loading();
		ingredientPagesDone = IngredientStore.donePages();
		ingredientPagesTotal = IngredientStore.totalPages();
		if (IngredientStore.error() != null && statusNote == null) {
			statusNote = "ingredients: " + IngredientStore.error();
		}
	}
	private static String rootMessage(Throwable throwable) {
		Throwable current = throwable;
		while (current.getCause() != null) {
			current = current.getCause();
		}
		String message = current.getMessage();
		return message == null ? current.getClass().getSimpleName() : message;
	}
	private void reload() {
		cachedData = null;
		cachedDataAt = 0L;
		cachedRecipes = null;
		cachedRecipesAt = 0L;
		data = null;
		recipes = List.of();
		options.clear();
		planMaterials.clear();
		IngredientStore.retry();
		api.invalidateRecipeCache();
		startFetch();
	}
	// ---------------------------------------------------------------- ingredients
	private int ingredientCap() {
		return Math.max(0, ingredientPreset - 1);
	}
	private int slotCount() {
		return pinned != null || ingredientPreset > 0 ? INGREDIENT_SLOTS : 0;
	}
	private static double tierWeight(int tier) {
		return INGREDIENT_TIER_WEIGHT[Math.max(0, Math.min(3, tier))];
	}
	/**
	 * Ingredient XP scales with the ingredient level compared to the recipe level:
	 * a level 114 ingredient in a level 115 recipe gives almost the full bonus, a
	 * level 10 ingredient gives almost nothing.
	 */
	private static double levelScale(int ingredientLevel, int recipeLevel) {
		if (recipeLevel <= 0) {
			return 0.0;
		}
		return Math.max(0.0, Math.min(1.0, ingredientLevel / (double) recipeLevel));
	}
	/** Resolves the ingredient pinned for the selected profession, if any. */
	private void resolvePinned() {
		pinnedName = ProfessionConfig.pinnedIngredient(selectedSkill);
		pinned = null;
		if (pinnedName == null) {
			return;
		}
		for (Ingredient ingredient : ingredients) {
			if (ingredient == null || ingredient.name == null) {
				continue;
			}
			if (pinnedName.equalsIgnoreCase(ingredient.name)
				|| (ingredient.internalName != null && pinnedName.equalsIgnoreCase(ingredient.internalName))) {
				pinned = ingredient;
				pinnedName = ingredient.name;
				return;
			}
		}
	}
	private void syncProfessionsFromApi(CharacterData character) {
		if (character == null) {
			LOGGER.error("WynncraftHelper: syncProfessionsFromApi called with null character");
			return;
		}
		if (character.professions == null) {
			LOGGER.error("WynncraftHelper: character.professions is null");
			return;
		}
		LOGGER.info("WynncraftHelper: syncing professions from API: {}", character.professions);
		boolean changed = false;
		for (String skill : ProfessionConfig.CRAFTS) {
			CharacterData.ProfessionInfo info = character.professions.get(skill);
			if (info == null) {
				LOGGER.warn("WynncraftHelper: no API data for {}", skill);
				continue;
			}
			// Always sync from API (API is source of truth)
			int storedLevel = ProfessionConfig.level(skill);
			if (info.level != storedLevel) {
				ProfessionConfig.setLevel(skill, info.level);
				changed = true;
				LOGGER.info("WynncraftHelper: {} level {} -> {}", skill, storedLevel, info.level);
			}
			// Always sync progress % from API
			int apiProgress = info.xpPercent;
			if (apiProgress >= 0 && apiProgress <= 99 && apiProgress != ProfessionConfig.progress(skill)) {
				ProfessionConfig.setProgress(skill, apiProgress);
				changed = true;
				LOGGER.info("WynncraftHelper: {} progress {} -> {}", skill, ProfessionConfig.progress(skill), apiProgress);
			}
		}
		if (changed) {
			recompute();
			refreshLabels();
			updateWidgetStates();
		} else {
			LOGGER.info("WynncraftHelper: no profession changes from API");
		}
	}
	/**
	 * Ingredient actually used for a recipe level: the pinned one when it is usable
	 * there, otherwise the automatic best pick.
	 */
	private Ingredient ingredientFor(String skill, int level) {
		return ingredientFor(skill, level, ingredientCap());
	}
	private Ingredient ingredientFor(String skill, int level, int tierCap) {
		// A pinned ingredient is only valid while it is usable for the profession AND
		// its level is below the recipe level. The game rejects higher-level
		// ingredients, so the planner must not count them either.
		if (pinned != null && pinned.usableFor(skill) && pinned.level < level) {
			return pinned;
		}
		return autoIngredient(skill, level, tierCap);
	}
	/** Automatic best ingredient, ignoring any pin. Used by the recipe breakdown tiers. */
	private Ingredient autoIngredient(String skill, int level, int tierCap) {
		return autoIngredient(skill, level, tierCap, true);
	}
	private Ingredient autoIngredient(String skill, int level, int tierCap, boolean requirePreset) {
		if ((requirePreset && ingredientPreset <= 0) || ingredients.isEmpty()) {
			return null;
		}
		Ingredient best = null;
		double bestScore = -1.0;
		for (Ingredient ingredient : ingredients) {
			if (ingredient == null || ingredient.name == null) {
				continue;
			}
			if (ingredient.tierIndex() > tierCap) {
				continue;
			}
			if (ingredient.level >= level) {
				continue;
			}
			if (!ingredient.usableFor(skill)) {
				continue;
			}
			double score = tierWeight(ingredient.tierIndex()) * levelScale(ingredient.level, level);
			if (score > bestScore || (score == bestScore && best != null && ingredient.level > best.level)) {
				bestScore = score;
				best = ingredient;
			}
		}
		return best;
	}
	/**
	 * Best ingredient for a profession at a given recipe level: highest tier, then the
	 * highest level that is still usable (ingredients must be below the recipe level).
	 */
	private Ingredient bestIngredient(String skill, int level) {
		if (ingredientPreset <= 0 || ingredients.isEmpty()) {
			return null;
		}
		String key = skill + "#" + level + "#" + ingredientPreset;
		Ingredient cached = bestIngredientCache.get(key);
		if (cached != null) {
			return cached;
		}
		Ingredient best = autoIngredient(skill, level, ingredientCap());
		bestIngredientCache.put(key, best);
		return best;
	}
	/** Total XP multiplier from six copies of {@code ingredient} in a level-{@code level} recipe. */
	private double ingredientMultiplier(Ingredient ingredient, int level) {
		if (ingredient == null) {
			return 1.0;
		}
		if (pinned == null && ingredientPreset <= 0) {
			return 1.0;
		}
		return 1.0 + slotCount() * tierWeight(ingredient.tierIndex()) * levelScale(ingredient.level, level);
	}
	private String ingredientLabel() {
		if (pinned != null) {
			return "Pinned: " + pinned.name + "  T" + pinned.tierIndex() + " · lv " + pinned.level;
		}
		if (ingredientPreset <= 0) {
			return "Ingredients: none  (x1)";
		}
		return "Ingredients: auto, up to T" + ingredientCap();
	}
	// ---------------------------------------------------------------- ingredient search
	private static final int SEARCH_ROW_H = 16;
	private static final int SEARCH_MAX_ROWS = 14;
	/** Rows start 62px below the column top in both drawing and hit-testing. */
	private static final int SEARCH_RESULTS_OFFSET = 62;
	/**
	 * Normalises a typed query. Category words are stripped so a player can type the
	 * name they see on the wiki ("Jewelcrafting Ingredients", "Alchemy materials")
	 * without it being treated as a literal ingredient name.
	 */
	private static String normalizeSearch(String text) {
		String query = text == null ? "" : text.trim().toLowerCase(Locale.ROOT);
		query = query.replace('-', ' ').replace('_', ' ');
		query = query.replaceAll("\\b(?:ingredients?|materials?|items?|list|all)\\b", " ");
		return query.replaceAll("\\s+", " ").trim();
	}
	/** True when the query names a profession/category rather than an item. */
	private static boolean isCategoryQuery(String normalized) {
		if (normalized.isEmpty()) {
			return false;
		}
		return ProfessionConfig.CRAFTS.contains(Ingredient.canonicalSkill(normalized));
	}
	private void updateIngredientSearch(String text) {
		searchResults.clear();
		searchMessage = null;
		searchHint = null;
		String normalized = normalizeSearch(text);
		if (normalized.isEmpty() || ingredients.isEmpty()) {
			searchOpen = false;
			return;
		}
		boolean categoryQuery = isCategoryQuery(normalized);
		String category = Ingredient.canonicalSkill(normalized);
		List<Ingredient> matches = new ArrayList<>();
		if (categoryQuery) {
			for (Ingredient ingredient : ingredients) {
				if (ingredient != null && ingredient.name != null && ingredient.usableFor(category)) {
					matches.add(ingredient);
				}
			}
		} else {
			String[] tokens = normalized.split(" ");
			for (Ingredient ingredient : ingredients) {
				if (ingredient == null || ingredient.name == null) {
					continue;
				}
				String haystack = ingredient.searchText();
				boolean allTokens = true;
				for (String token : tokens) {
					if (!haystack.contains(token)) {
						allTokens = false;
						break;
					}
				}
				if (allTokens) {
					matches.add(ingredient);
				}
			}
		}
		// A category query that produced nothing falls back to a literal name search,
		// so an item that happens to be called like a profession is still reachable.
		if (matches.isEmpty() && categoryQuery) {
			String[] tokens = normalized.split(" ");
			for (Ingredient ingredient : ingredients) {
				if (ingredient == null || ingredient.name == null) {
					continue;
				}
				String haystack = ingredient.searchText();
				boolean allTokens = true;
				for (String token : tokens) {
					if (!haystack.contains(token)) {
						allTokens = false;
						break;
					}
				}
				if (allTokens) {
					matches.add(ingredient);
				}
			}
			categoryQuery = false;
			searchHint = null;
		}
		if (matches.isEmpty()) {
			String material = materialHint(normalized);
			searchMessage = material != null ? material
				: "No ingredient matches \"" + trim(queryText(), 30) + "\"";
			searchOpen = true;
			return;
		}
		final boolean categorySort = categoryQuery;
		matches.sort((a, b) -> {
			// Usable ingredients first.
			boolean aUsable = a.usableFor(selectedSkill);
			boolean bUsable = b.usableFor(selectedSkill);
			if (aUsable != bUsable) {
				return aUsable ? -1 : 1;
			}
			if (!categorySort) {
				// Prefer an exact/prefix match over a substring match so the item the
				// player typed is not pushed out of the 14 visible rows.
				String nameA = a.name == null ? "" : a.name.toLowerCase(Locale.ROOT);
				String nameB = b.name == null ? "" : b.name.toLowerCase(Locale.ROOT);
				boolean aExact = nameA.equals(normalized);
				boolean bExact = nameB.equals(normalized);
				if (aExact != bExact) {
					return aExact ? -1 : 1;
				}
				boolean aPrefix = nameA.startsWith(normalized);
				boolean bPrefix = nameB.startsWith(normalized);
				if (aPrefix != bPrefix) {
					return aPrefix ? -1 : 1;
				}
			}
			int tier = Integer.compare(b.tierIndex(), a.tierIndex());
			if (tier != 0) {
				return tier;
			}
			int level = Integer.compare(b.level, a.level);
			if (level != 0) {
				return level;
			}
			return a.name.compareToIgnoreCase(b.name);
		});
		int totalMatches = matches.size();
		while (matches.size() > SEARCH_MAX_ROWS) {
			matches.remove(matches.size() - 1);
		}
		searchResults.addAll(matches);
		if (categoryQuery) {
			searchHint = matches.size() < totalMatches
				? "Showing " + matches.size() + " of " + totalMatches + " "
					+ ProfessionConfig.displayName(category) + " ingredients"
				: "Showing all " + totalMatches + " "
					+ ProfessionConfig.displayName(category) + " ingredients";
		}
		searchOpen = true;
	}
	/**
	 * When a query matches a recipe input (e.g. "Dernic Oil") rather than an XP ingredient,
	 * explain the distinction instead of just saying "no matches".
	 */
	private String materialHint(String normalized) {
		if (normalized.isEmpty()) {
			return null;
		}
		for (Recipe recipe : recipes) {
			if (recipe == null || recipe.materials == null) {
				continue;
			}
			for (RecipeMaterial material : recipe.materials) {
				if (material == null || material.item == null) {
					continue;
				}
				String item = MaterialNames.normalize(material.item);
				if (item.contains(normalized) || normalized.contains(item)) {
					return "\"" + MaterialNames.canonical(material.item)
						+ "\" is a crafting material, not an XP ingredient";
				}
			}
		}
		return null;
	}
	private void selectIngredient(Ingredient ingredient) {
		if (ingredient == null) {
			return;
		}
		if (!ingredient.usableFor(selectedSkill)) {
			searchMessage = "\"" + ingredient.name + "\" is not usable for "
				+ ProfessionConfig.displayName(selectedSkill);
			searchOpen = true;
			return;
		}
		ProfessionConfig.setPinnedIngredient(selectedSkill, ingredient.name);
		searchOpen = false;
		searchResults.clear();
		searchMessage = null;
		searchHint = null;
		if (ingredientSearch != null) {
			ingredientSearch.setValue("");
			ingredientSearch.setFocused(false);
		}
		this.setFocused(null);
		recompute();
		refreshLabels();
		updateWidgetStates();
	}
	private void clearPinnedIngredient() {
		ProfessionConfig.setPinnedIngredient(selectedSkill, null);
		searchOpen = false;
		searchResults.clear();
		searchMessage = null;
		searchHint = null;
		recompute();
		refreshLabels();
		updateWidgetStates();
	}
	private int searchX() {
		return panelX + PAD + LEFT_W + COL_GAP;
	}
	/** Absolute Y of the first result row. Drawing and hit-testing both use this. */
	private int searchResultsTop() {
		return panelY + BODY_TOP + SEARCH_RESULTS_OFFSET;
	}
	private void drawSearchResults(GuiGraphics context, int x, int y, int mouseX, int mouseY) {
		label(context, searchHint != null ? "CATEGORY MATCHES" : "INGREDIENT MATCHES", x, y + 24);
		if (searchResults.isEmpty()) {
			String message = searchMessage == null
				? "No ingredient matches \"" + trim(queryText(), 30) + "\""
				: searchMessage;
			context.drawString(this.font, trim(message, RIGHT_W), x, y + 40, C_LABEL, false);
			return;
		}
		String hint = searchHint != null
			? searchHint
			: searchResults.size() + " for \"" + trim(queryText(), 24) + "\"";
		context.drawString(this.font, trim(hint, RIGHT_W), x, y + 36, C_TEXT, false);
		context.drawString(this.font, "✗ = not usable for this profession", x, y + 48, C_LABEL, false);
		int rowY = y + SEARCH_RESULTS_OFFSET;
		for (int index = 0; index < searchResults.size(); index++) {
			Ingredient ingredient = searchResults.get(index);
			boolean usable = ingredient.usableFor(selectedSkill);
			boolean hovered = mouseX >= x && mouseX < x + RIGHT_W && mouseY >= rowY && mouseY < rowY + SEARCH_ROW_H;
			if (hovered && usable) {
				context.fill(x, rowY, x + RIGHT_W, rowY + SEARCH_ROW_H, 0xFF1E2631);
			}
			int color;
			if (!usable) {
				color = C_MUTED; // grayed out for unusable
			} else {
				color = ingredient.tierIndex() >= 3 ? C_GOLD
					: ingredient.tierIndex() == 2 ? C_INGREDIENT : ingredient.tierIndex() == 1 ? C_SILVER : C_MUTED;
			}
			String tag = "T" + ingredient.tierIndex() + " · " + ingredient.level;
			if (!usable) {
				tag = tag + "  ✗";
			}
			int tagWidth = this.font.width(tag);
			context.drawString(this.font, trim(ingredient.name, RIGHT_W - tagWidth - 20), x, rowY + 4, color, false);
			context.drawString(this.font, tag, x + RIGHT_W - tagWidth, rowY + 4, usable ? C_LABEL : C_ERROR, false);
			rowY += SEARCH_ROW_H;
		}
		context.drawString(this.font, "click an entry to pin it  ·  Enter picks the first usable result",
			x, rowY + 6, C_LABEL, false);
	}
	private String queryText() {
		return ingredientSearch == null ? "" : ingredientSearch.getValue().trim();
	}
	private int searchResultAt(double mouseX, double mouseY) {
		if (!searchOpen || searchResults.isEmpty()) {
			return -1;
		}
		int x = searchX();
		int y = searchResultsTop();
		if (mouseX < x || mouseX >= x + RIGHT_W || mouseY < y || mouseY >= y + searchResults.size() * SEARCH_ROW_H) {
			return -1;
		}
		return (int) ((mouseY - y) / SEARCH_ROW_H);
	}
	@Override
	public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
		double mouseX = event.x();
		double mouseY = event.y();
		// The breakdown is modal: it must consume clicks before the widget layer.
		if (detailOption != null) {
			if (pinButtonContains(mouseX, mouseY)) {
				togglePinDetail();
				return true;
			}
			int tierClick = tierRowAt(mouseX, mouseY);
			if (tierClick >= 1) {
				selectTier(tierClick);
				return true;
			}
			if (!detailContains(mouseX, mouseY) || detailCloseContains(mouseX, mouseY)) {
				closeBreakdown();
			}
			return true;
		}
		int typeIndex = craftableTypeAt(mouseX, mouseY);
		if (typeIndex >= 0 && typeIndex < craftableTypes.size()) {
			selectedCraftType = craftableTypes.get(typeIndex);
			Recipe recipe = craftableTypeRecipe.get(selectedCraftType);
			if (recipe != null) {
				CraftOption opt = null;
				for (CraftOption o : options) {
					if (o.recipe == recipe) { opt = o; break; }
				}
				if (opt == null && !options.isEmpty()) opt = options.get(0);
				if (opt != null) openBreakdown(opt);
			}
			return true;
		}
		if (showMaterials && !searchOpen) {
			String material = materialAt(mouseX, mouseY);
			if (material != null) {
				CraftOption option = optionForMaterial(material);
				if (option != null) {
					openBreakdown(option);
					return true;
				}
			}
		}
		int index = searchResultAt(mouseX, mouseY);
		if (index >= 0 && index < searchResults.size()) {
			Ingredient selected = searchResults.get(index);
			selectIngredient(selected);
			return true;
		}
		if (searchOpen && ingredientSearch != null && ingredientSearch.isMouseOver(mouseX, mouseY)
			&& !queryText().isEmpty()) {
			// re-open the result column without needing to retype the query
			updateIngredientSearch(ingredientSearch.getValue());
		} else if (searchOpen && (ingredientSearch == null || !ingredientSearch.isMouseOver(mouseX, mouseY))) {
			// clicking elsewhere brings the crafts/materials view back
			searchOpen = false;
		}
		return super.mouseClicked(event, doubleClick);
	}
	@Override
	public boolean keyPressed(KeyEvent event) {
		if (detailOption != null) {
			if (event.key() == GLFW.GLFW_KEY_ESCAPE) {
				closeBreakdown();
			}
			return true; // swallow typing while the modal is open
		}
		if (event.key() == GLFW.GLFW_KEY_ESCAPE && searchOpen) {
			searchOpen = false;
			return true;
		}
		if (event.key() == GLFW.GLFW_KEY_ENTER && searchOpen) {
			for (Ingredient ingredient : searchResults) {
				if (ingredient.usableFor(selectedSkill)) {
					selectIngredient(ingredient);
					return true;
				}
			}
			return true;
		}
		return super.keyPressed(event);
	}
	// ---------------------------------------------------------------- recipe breakdown
	private int craftableTypeAt(double mouseX, double mouseY) {
		if (showMaterials || searchOpen || craftableTypes.isEmpty()) {
			return -1;
		}
		int x = panelX + PAD + LEFT_W + COL_GAP;
		int firstY = craftableTypeFirstY;
		for (int index = 0; index < craftableTypes.size(); index++) {
			int rowY = firstY + index * 22;
			if (mouseX >= x && mouseX < x + RIGHT_W && mouseY >= rowY && mouseY < rowY + 20) {
				return index;
			}
		}
		return -1;
	}

	private int cardAt(double mouseX, double mouseY) {
		if (showMaterials || searchOpen || options.isEmpty()) {
			return -1;
		}
		// The Crafts/Materials toggle overlaps the top of the first card by a few
		// pixels; let the widget handle those clicks.
		if (viewToggle != null
			&& mouseX >= viewToggle.getX() && mouseX < viewToggle.getX() + viewToggle.getWidth()
			&& mouseY >= viewToggle.getY() && mouseY < viewToggle.getY() + viewToggle.getHeight()) {
			return -1;
		}
		int x = panelX + PAD + LEFT_W + COL_GAP;
		int firstY = panelY + BODY_TOP + 16;
		for (int index = 0; index < options.size() && index < 3; index++) {
			int cardY = firstY + index * (CARD_H + CARD_GAP);
			if (mouseX >= x && mouseX < x + RIGHT_W && mouseY >= cardY && mouseY < cardY + CARD_H) {
				return index;
			}
		}
		return -1;
	}
	private List<Map.Entry<String, Long>> sortedPlanMaterials() {
		List<Map.Entry<String, Long>> entries = new ArrayList<>(planMaterials.entrySet());
		entries.sort((a, b) -> Long.compare(b.getValue(), a.getValue()));
		return entries;
	}
	/** Returns the material name under the mouse in the Materials tab, or null. */
	private String materialAt(double mouseX, double mouseY) {
		if (!showMaterials || searchOpen) {
			return null;
		}
		List<Map.Entry<String, Long>> entries = sortedPlanMaterials();
		int x = panelX + PAD + LEFT_W + COL_GAP;
		int firstY = panelY + BODY_TOP + 18 - 3;
		int shown = Math.min(10, entries.size());
		for (int index = 0; index < shown; index++) {
			int rowY = firstY + index * 19;
			if (mouseX >= x && mouseX < x + RIGHT_W && mouseY >= rowY && mouseY < rowY + 18) {
				return entries.get(index).getKey();
			}
		}
		return null;
	}
	/** Finds a craft option (or the best matching recipe) that consumes {@code material}. */
	private CraftOption optionForMaterial(String material) {
		if (material == null) {
			return null;
		}
		for (CraftOption option : options) {
			if (option == null || option.recipe == null || option.recipe.materials == null) {
				continue;
			}
			for (RecipeMaterial item : option.recipe.materials) {
				if (item != null && item.item != null
					&& MaterialNames.canonical(item.item).equalsIgnoreCase(MaterialNames.canonical(material))) {
					return option;
				}
			}
		}
		Recipe best = null;
		for (Recipe recipe : recipes) {
			if (recipe == null || recipe.level == null || recipe.materials == null || recipe.xp <= 0) {
				continue;
			}
			if (recipe.skill == null || !recipe.skill.equalsIgnoreCase(selectedSkill)) {
				continue;
			}
			if (recipe.level.minimum > professionLevel + 1) {
				continue;
			}
			for (RecipeMaterial item : recipe.materials) {
				if (item != null && item.item != null
					&& MaterialNames.canonical(item.item).equalsIgnoreCase(MaterialNames.canonical(material))) {
					if (best == null || recipe.xp > best.xp) {
						best = recipe;
					}
					break;
				}
			}
		}
		if (best == null) {
			return null;
		}
		int level = Math.max(best.level.minimum, professionLevel);
		Ingredient ingredient = ingredientFor(selectedSkill, level);
		double multiplier = ingredientMultiplier(ingredient, level);
		long crafts = ceilDiv(ProfessionXp.xpToNext(level), best.xp * worldMultiplier() * multiplier);
		return new CraftOption(best, crafts, resourcesFor(best, crafts), crafts * slotCount(),
			ingredient, multiplier);
	}
	private int tierRowAt(double mouseX, double mouseY) {
		if (detailOption == null || detailTierTopY == 0) {
			return -1;
		}
		int x = detailX();
		int y = detailY();
		int w = detailW();
		int h = detailH();
		if (mouseX < x || mouseX >= x + w || mouseY < y || mouseY >= y + h) {
			return -1;
		}
		int firstRowY = detailTierTopY + 20;
		for (int tier = 1; tier <= 3; tier++) {
			int rowY = firstRowY + (tier - 1) * 30;
			if (mouseY >= rowY - 3 && mouseY < rowY + 27) {
				return tier;
			}
		}
		return -1;
	}
	@Override
	public boolean mouseScrolled(double mouseX, double mouseY, double scrollX, double scrollY) {
		if (detailOption != null && detailContains(mouseX, mouseY)) {
			if (mouseY >= detailLevelAreaTop && mouseY <= detailLevelAreaBottom) {
				detailScroll -= (int) (scrollY * 16);
				if (detailScroll < 0) {
					detailScroll = 0;
				}
				if (detailScroll > detailMaxScroll) {
					detailScroll = detailMaxScroll;
				}
				return true;
			}
		}
		return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY);
	}

	private void selectTier(int tier) {
		// Tier rows are T1/T2/T3 ingredients; map to the global ingredient preset
		// (preset 2 = up to T1, 3 = up to T2, 4 = up to T3) so the whole plan
		// and the tier preview switch to that tier's data.
		int newPreset = Math.max(1, Math.min(4, tier + 1));
		ingredientPreset = newPreset;
		ProfessionConfig.setIngredientPreset(newPreset);
		refreshLabels();
		recompute();
		if (detailOption != null) {
			openBreakdown(detailOption);
		}
	}
	private void openBreakdown(CraftOption option) {
		if (option == null || option.recipe == null || option.recipe.level == null) {
			return;
		}
		detailOption = option;
		detailLevels = buildBreakdownLevels(option);
		detailScroll = 0;
		int recipeLevel = option.recipe.level.minimum;
		for (int tier = 1; tier <= 3; tier++) {
			// A specific ingredient the player chose drives the tier suggestions; the tier only
			// selects the weight (T1/T2/T3) of the ingredient applied on top of the chosen one,
			// while the recipe's base materials for that tier are what get consumed.
			Ingredient chosen = (pinned != null && pinned.usableFor(selectedSkill) && pinned.level < recipeLevel)
				? pinned
				: null;
			Ingredient candidate = chosen;
			if (candidate == null) {
				// Show the auto candidate even when ingredients are currently off,
				// so the breakdown doubles as a preview of what enabling them buys.
				candidate = autoIngredient(selectedSkill, recipeLevel, tier, false);
			}
			detailTierIngredients[tier] = candidate;
			double tierMultiplier = chosen != null
				? 1.0 + slotCount() * tierWeight(tier) * levelScale(chosen.level, recipeLevel)
				: displayMultiplier(candidate, recipeLevel);
			detailTierMultipliers[tier] = tierMultiplier;
			long tierCrafts = ceilDiv(breakdownXp(recipeLevel, chosen), option.recipe.xp * worldMultiplier() * tierMultiplier);
			detailTierCrafts[tier] = tierCrafts;
		}
		searchOpen = false;
		searchResults.clear();
		if (ingredientSearch != null) {
			ingredientSearch.setFocused(false);
		}
		this.setFocused(null);
	}
	private void closeBreakdown() {
		detailOption = null;
		detailLevels = List.of();
		for (int tier = 1; tier <= 3; tier++) {
			detailTierIngredients[tier] = null;
			detailTierMultipliers[tier] = 1.0;
			detailTierCrafts[tier] = 0;
		}
	}
	private int detailX() {
		return panelX - 35;
	}
	private int detailY() {
		return panelY + 20;
	}
	private int detailW() {
		return PANEL_W + 70;
	}
	private int detailH() {
		return PANEL_H - 40;
	}
	private boolean detailContains(double mouseX, double mouseY) {
		return mouseX >= detailX() && mouseX < detailX() + detailW()
			&& mouseY >= detailY() && mouseY < detailY() + detailH();
	}
	private boolean detailCloseContains(double mouseX, double mouseY) {
		int closeX = detailX() + detailW() - 30;
		int closeY = detailY() + 10;
		return mouseX >= closeX && mouseX < closeX + 18
			&& mouseY >= closeY && mouseY < closeY + 18;
	}
	/** Per-level material/craft breakdown for the clicked recipe (up to three levels). */
	private List<BreakdownLevel> buildBreakdownLevels(CraftOption option) {
		List<BreakdownLevel> result = new ArrayList<>();
		Recipe recipe = option.recipe;
		if (recipe == null || recipe.level == null) {
			return result;
		}
		int min = recipe.level.minimum;
		int max = Math.max(min, recipe.level.maximum);
		// Walk every level the player still needs, up to their chosen target, using
		// the best recipe for each level (not just the clicked one's range).
		int from = Math.max(min, professionLevel);
		int to = Math.min(ProfessionXp.MAX_LEVEL, targetLevel > 0 ? targetLevel - 1 : max);
		if (to < from) {
			from = Math.max(min, professionLevel);
			to = Math.min(ProfessionXp.MAX_LEVEL, professionLevel + 4);
		}
		int shown = 0;
		for (int level = from; level <= to && shown < 60; level++, shown++) {
			long xp = level == professionLevel
				? Math.round((double) ProfessionXp.xpToNext(level) * (100 - progressPercent) / 100.0)
				: ProfessionXp.xpToNext(level);
			if (xp <= 0) {
				xp = ProfessionXp.xpToNext(Math.max(1, level));
			}
			Recipe levelRecipe = null;
			List<Recipe> best = topRecipes(selectedSkill, level, 1);
			if (!best.isEmpty()) {
				levelRecipe = best.get(0);
			}
			if (levelRecipe == null) {
				levelRecipe = recipe;
			}
			Ingredient ingredient = ingredientFor(selectedSkill, level);
			double multiplier = ingredientMultiplier(ingredient, level);
			long crafts = ceilDiv(xp, levelRecipe.xp * worldMultiplier() * multiplier);
			if (crafts <= 0) {
				crafts = option.crafts;
			}
			Map<String, Long> materials = new LinkedHashMap<>();
			for (RecipeMaterial material : levelRecipe.materials) {
				materials.merge(MaterialNames.canonical(material.item),
					scaledTotal(material.amount, crafts), Long::sum);
			}
			result.add(new BreakdownLevel(level, crafts, materials));
		}
		if (result.isEmpty()) {
			Map<String, Long> materials = new LinkedHashMap<>();
			for (RecipeMaterial material : recipe.materials) {
				materials.merge(MaterialNames.canonical(material.item),
					scaledTotal(material.amount, option.crafts), Long::sum);
			}
			result.add(new BreakdownLevel(min, option.crafts, materials));
		}
		return result;
	}
	/** XP multiplier shown in the tier preview; independent of whether slots are enabled. */
	private double displayMultiplier(Ingredient ingredient, int level) {
		if (ingredient == null) {
			return 1.0;
		}
		int slots = (pinned != null || ingredientPreset > 0) ? INGREDIENT_SLOTS : 0;
		return 1.0 + slots * tierWeight(ingredient.tierIndex()) * levelScale(ingredient.level, level);
	}
	/** XP awarded for a level band, honouring the player's own progress. */
	private long breakdownXp(int level, Ingredient ingredient) {
		long xp = level == professionLevel
			? Math.round((double) ProfessionXp.xpToNext(level) * (100 - progressPercent) / 100.0)
			: ProfessionXp.xpToNext(level);
		return xp > 0 ? xp : ProfessionXp.xpToNext(Math.max(1, level));
	}
	private void drawRecipeBreakdown(GuiGraphics context, int mouseX, int mouseY) {
		int x = detailX();
		int y = detailY();
		int w = detailW();
		int h = detailH();
		// Modal scrim + card. Drawn after super.render(), so widgets stay underneath.
		context.fill(0, 0, this.width, this.height, 0xB4000000);
		context.fill(x + 6, y + 8, x + w + 6, y + h + 8, 0x60000000);
		context.fill(x, y, x + w, y + h, C_BG);
		context.fill(x, y, x + w, y + 56, C_HEADER);
		context.fill(x, y, x + w, y + 2, C_ACCENT);
		context.fill(x, y, x + w, y + 1, C_BORDER);
		context.fill(x, y + h - 1, x + w, y + h, C_BORDER);
		context.fill(x, y, x + 1, y + h, C_BORDER);
		context.fill(x + w - 1, y, x + w, y + h, C_BORDER);
		Recipe recipe = detailOption.recipe;
		context.drawString(this.font, "RECIPE BREAKDOWN", x + 16, y + 10, C_LABEL, false);
		context.drawString(this.font, trim(recipe.internalName, w - 90), x + 16, y + 24, C_TEXT, true);
		String meta = ProfessionConfig.displayName(recipe.skill) + "  ·  Lv "
			+ recipe.level.minimum + "–" + recipe.level.maximum;
		context.drawString(this.font, meta, x + 16, y + 38, C_MUTED, false);
		double perCraft = recipe.xp * worldMultiplier() * detailOption.multiplier;
		String xpLine = fmtXp(perCraft) + " XP/craft  ·  " + fmt(detailOption.crafts)
			+ " crafts  ·  " + fmtXp(perCraft * detailOption.crafts) + " XP";
		context.drawString(this.font, trim(xpLine, w - 32), x + 16, y + 50, C_MUTED, false);
		int closeX = x + w - 30;
		int closeY = y + 10;
		boolean closeHover = mousesOver(mouseX, mouseY, closeX, closeY, 18, 18);
		context.fill(closeX, closeY, closeX + 18, closeY + 18, closeHover ? C_CARD_BORDER : C_CARD);
		context.drawCenteredString(this.font, "✕", closeX + 9, closeY + 5, closeHover ? C_TEXT : C_MUTED);
		divider(context, x + 16, y + 62, w - 32);
		// --- required materials -------------------------------------------------
		label(context, "REQUIRED MATERIALS  ·  PER CRAFT / FOR THIS PLAN", x + 16, y + 70);
		int rowY = y + 84;
		int shownMaterials = Math.min(recipe.materials.size(), 4);
		for (int index = 0; index < shownMaterials; index++) {
			RecipeMaterial material = recipe.materials.get(index);
			long perCraftAmount = scaledPerCraft(material.amount);
			long total = scaledTotal(material.amount, detailOption.crafts);
			String amount = "x" + fmt(perCraftAmount) + "  /  x" + fmt(total);
			int amountWidth = this.font.width(amount);
			context.drawString(this.font, trim(MaterialNames.canonical(material.item), w - 32 - amountWidth - 12),
				x + 16, rowY, C_TEXT, false);
			context.drawString(this.font, amount, x + w - 16 - amountWidth, rowY, C_GOLD, false);
			rowY += 14;
		}
		if (recipe.materials.isEmpty()) {
			context.drawString(this.font, "No materials listed", x + 16, rowY, C_LABEL, false);
			rowY += 14;
		} else if (recipe.materials.size() > shownMaterials) {
			context.drawString(this.font, "+" + (recipe.materials.size() - shownMaterials) + " more materials",
				x + 16, rowY, C_LABEL, false);
			rowY += 14;
		}
		// --- level breakdown (scrollable) --------------------------------------
		int levelTop = Math.max(y + 150, rowY + 8);
		divider(context, x + 16, levelTop - 6, w - 32);
		label(context, "LEVEL BREAKDOWN  ·  CRAFTS AND MATERIALS PER LEVEL", x + 16, levelTop + 2);
		int levelAreaTop = levelTop + 18;
		int tierTop = Math.max(y + 246, levelTop + 80);
		int levelAreaBottom = tierTop - 6;
		int levelAreaHeight = Math.max(16, levelAreaBottom - levelAreaTop);
		int totalLevelHeight = detailLevels.size() * 16;
		detailMaxScroll = Math.max(0, totalLevelHeight - levelAreaHeight);
		detailScroll = Math.max(0, Math.min(detailScroll, detailMaxScroll));
		detailLevelAreaTop = levelAreaTop;
		detailLevelAreaBottom = levelAreaBottom;

		context.enableScissor(x + 16, levelAreaTop, x + w - 12, levelAreaBottom);
		int drawY = levelAreaTop - detailScroll;
		for (int index = 0; index < detailLevels.size(); index++) {
			BreakdownLevel level = detailLevels.get(index);
			int ly = drawY + index * 16;
			if (ly + 16 < levelAreaTop || ly > levelAreaBottom) {
				continue;
			}
			context.drawString(this.font, "Level " + (index + 1) + "  (Lv " + level.level + ")",
				x + 16, ly, C_TEXT, false);
			String crafts = fmt(level.crafts) + " crafts";
			context.drawString(this.font, crafts, x + 126, ly, C_ACCENT, false);
			String mats = materialsLine(level.materials);
			context.drawString(this.font, trim(mats, w - 232), x + 232, ly, C_MUTED, false);
		}
		if (detailLevels.isEmpty()) {
			context.drawString(this.font, "No level data", x + 16, levelAreaTop, C_LABEL, false);
		}
		context.disableScissor();

		if (detailMaxScroll > 0) {
			int barX = x + w - 12;
			context.fill(barX, levelAreaTop, barX + 4, levelAreaBottom, C_DIVIDER);
			int thumbH = Math.max(8, levelAreaHeight * levelAreaHeight / Math.max(1, totalLevelHeight));
			int thumbY = levelAreaTop + (levelAreaHeight - thumbH) * detailScroll / Math.max(1, detailMaxScroll);
			context.fill(barX, thumbY, barX + 4, thumbY + thumbH, C_ACCENT);
		}
		// --- tiered ingredient preview -----------------------------------------
		// tierTop already fixed above
		detailTierTopY = tierTop;
		divider(context, x + 16, tierTop - 6, w - 32);
		label(context, "INGREDIENT TIERS  ·  OPTIONAL XP PER 6 SLOTS", x + 16, tierTop + 2);
		int tierRowY = tierTop + 20;
		for (int tier = 1; tier <= 3; tier++) {
			Ingredient ingredient = detailTierIngredients[tier];
			boolean pinnedHere = pinned != null && ingredient != null
				&& pinned.name.equalsIgnoreCase(ingredient.name);
			int tierColor = tier == 1 ? C_SILVER : tier == 2 ? C_INGREDIENT : C_GOLD;
			boolean tierHover = mouseX >= x + 12 && mouseX < x + w - 12 && mouseY >= tierRowY - 3 && mouseY < tierRowY + 27;
			if (ingredientPreset == tier + 1) {
				context.fill(x + 12, tierRowY - 3, x + w - 12, tierRowY + 27, 0xFF2A3544);
			}
			if (tierHover) {
				context.fill(x + 12, tierRowY - 3, x + w - 12, tierRowY + 27, 0xFF1E2631);
			}
			context.drawString(this.font, "Level " + tier + " · T" + tier,
				x + 16, tierRowY, tierColor, true);
			if (ingredient == null) {
				context.drawString(this.font, "no usable ingredient for this recipe level",
					x + 96, tierRowY, C_LABEL, false);
			} else {
				long crafts = detailTierCrafts[tier];
				String amount = slotCount() > 0
					? fmt((long) slotCount() * crafts) + " needed"
					: "ingredients off";
				int amountWidth = this.font.width(amount);
				// Keep the ingredient name clear of the right-aligned amount, then put the
				// craft count on its own line below it instead of over the name.
				String line = (pinnedHere ? "★ " : "") + ingredient.name + "  (lv " + ingredient.level + ")";
				int nameWidth = Math.max(40, w - 96 - 16 - amountWidth - 12);
				context.drawString(this.font, trim(line, nameWidth), x + 96, tierRowY,
					pinnedHere ? C_ACCENT : C_TEXT, false);
				context.drawString(this.font, amount, x + w - 16 - amountWidth,
					tierRowY, C_LABEL, false);
				String craftsText = fmt(crafts) + " crafts";
				context.drawString(this.font, craftsText, x + 96, tierRowY + 11, C_ACCENT, false);
				if (slotCount() > 0) {
					String mult = "+x" + fmtMult(detailTierMultipliers[tier]) + " XP";
					context.drawString(this.font, mult, x + w - 16 - this.font.width(mult),
						tierRowY + 11, C_ACCENT, false);
				}
			}
			tierRowY += 30;
		}
		divider(context, x + 16, y + h - 26, w - 32);
		int pinW = 104;
		int pinH = 16;
		int px = x + w - 16 - pinW;
		int py = y + h - 23;
		pinButtonX = px;
		pinButtonY = py;
		pinButtonW = pinW;
		pinButtonH = pinH;
		boolean pinnedHere = HudState.INSTANCE.isPinned(recipe.internalName);
		boolean pinHover = mousesOver(mouseX, mouseY, px, py, pinW, pinH);
		context.fill(px, py, px + pinW, py + pinH, pinHover ? C_CARD_BORDER : C_CARD);
		context.fill(px, py, px + pinW, py + 1, pinnedHere ? C_ACCENT : C_GOLD);
		context.drawCenteredString(this.font, pinnedHere ? "UNPIN HUD" : "PIN TO HUD",
			px + pinW / 2, py + 4, pinnedHere ? C_ACCENT : C_GOLD);
		int hintWidth = Math.max(0, w - 32 - pinW - 12);
		context.drawString(this.font,
			trim("Click outside or Esc to close  ·  X toggles the HUD", hintWidth),
			x + 16, y + h - 16, C_LABEL, false);
	}

	private boolean pinButtonContains(double mouseX, double mouseY) {
		return pinButtonW > 0 && mousesOver(mouseX, mouseY, pinButtonX, pinButtonY, pinButtonW, pinButtonH);
	}

	private void togglePinDetail() {
		if (detailOption == null || detailOption.recipe == null) {
			return;
		}
		if (HudState.INSTANCE.isPinned(detailOption.recipe.internalName)) {
			HudState.INSTANCE.unpin();
			return;
		}
		HudState.INSTANCE.pin(buildHudGoal(detailOption));
	}

	/** Captures the inspected recipe as a live HUD goal. */
	private HudGoal buildHudGoal(CraftOption option) {
		HudGoal goal = new HudGoal();
		goal.skill = option.recipe.skill;
		goal.recipeName = option.recipe.internalName;
		goal.recipeType = option.recipe.type;
		goal.recipeLevel = option.recipe.level == null ? professionLevel : option.recipe.level.minimum;
		goal.targetLevel = Math.max(professionLevel + 1, targetLevel);
		goal.baseXp = option.recipe.xp;
		goal.ingredientMultiplier = option.multiplier > 0 ? option.multiplier : 1.0;
		if (option.recipe.materials != null) {
			for (RecipeMaterial material : option.recipe.materials) {
				String name = MaterialNames.canonical(material.item);
				goal.perCraftMaterials.merge(name, material.amount, Integer::sum);
			}
		}
		if (option.ingredient != null && option.ingredient.name != null) {
			int slots = slotCount();
			if (slots > 0) {
				goal.perCraftIngredients.put(option.ingredient.name, slots);
			}
		}
		return goal;
	}
	private boolean mousesOver(double mouseX, double mouseY, int x, int y, int w, int h) {
		return mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
	}
	// ---------------------------------------------------------------- math
	private void recompute() {
		options.clear();
		planMaterials.clear();
		bestIngredientCache.clear();
		planCrafts = 0;
		planBaseCrafts = 0;
		planMaterialCount = 0;
		planIngredientCount = 0;
		planXp = 0;
		currentXpPerCraft = 0;
		currentIngredientMultiplier = 1.0;
		currentIngredientName = null;
		selectedSkill = ProfessionConfig.selected();
		professionLevel = ProfessionConfig.level(selectedSkill);
		progressPercent = ProfessionConfig.progress(selectedSkill);
		targetLevel = ProfessionConfig.target(selectedSkill);
		resolvePinned();
		if (recipes.isEmpty()) {
			return;
		}
		double world = worldMultiplier();
		long xpForCurrentLevel = ProfessionXp.xpToNext(professionLevel);
		long remainingAtCurrent = Math.round((double) xpForCurrentLevel * (100 - progressPercent) / 100.0);
		Ingredient currentIngredient = ingredientFor(selectedSkill, professionLevel);
		currentIngredientMultiplier = ingredientMultiplier(currentIngredient, professionLevel);
		currentIngredientName = currentIngredient == null ? null : currentIngredient.name;
		int cardLevel = targetLevel > professionLevel ? targetLevel - 1 : professionLevel;
		if (cardLevel < professionLevel) {
			cardLevel = professionLevel;
		}
		long cardRemaining = (cardLevel == professionLevel) ? remainingAtCurrent : ProfessionXp.xpToNext(cardLevel);
		// ingredientFor() validates that a pinned ingredient is below the recipe level,
		// so a pin that is too high falls back to the automatic tier pick instead.
		// Build the list of craftable item types (Bow, Wand, Relic, ...) for this skill
		// at the player's current level, so the player picks what to craft instead of
		// being shown an arbitrary "top 3" that could include uncraftable recipes.
		craftableTypes.clear();
		craftableTypeRecipe.clear();
		options.clear();
		java.util.LinkedHashSet<String> seenTypes = new java.util.LinkedHashSet<>();
		for (Recipe recipe : recipes) {
			if (recipe == null || recipe.level == null || recipe.xp <= 0) {
				continue;
			}
			if (recipe.skill == null || !recipe.skill.equalsIgnoreCase(selectedSkill)) {
				continue;
			}
			if (recipe.type == null || recipe.type.isBlank()) {
				continue;
			}
			if (recipe.level.minimum > professionLevel) {
				continue; // only craftable now
			}
			String key = recipe.type.toLowerCase(java.util.Locale.ROOT);
			Recipe prev = craftableTypeRecipe.get(key);
			if (prev == null || recipe.level.minimum > prev.level.minimum) {
				craftableTypeRecipe.put(key, recipe);
			}
			seenTypes.add(key);
		}
		for (String t : seenTypes) {
			craftableTypes.add(t);
		}
		for (String t : craftableTypes) {
			Recipe recipe = craftableTypeRecipe.get(t);
			if (recipe == null) {
				continue;
			}
			int level = Math.min(recipe.level.minimum, professionLevel);
			if (level < recipe.level.minimum) {
				level = recipe.level.minimum;
			}
			Ingredient ing = ingredientFor(selectedSkill, level);
			double mult = ingredientMultiplier(ing, level);
			double perCraft = liveCraftXp > 0 ? liveCraftXp : recipe.xp * world * mult;
			long crafts = ceilDiv(cardRemaining, perCraft);
			options.add(new CraftOption(recipe, crafts, resourcesFor(recipe, crafts),
				crafts * slotCount(), ing, mult));
		}
		// Compute crafts only for the next level band, starting from the player's current
		// level and progress. This avoids showing "all crafts from level 0" when the player
		// is already at level 105+.
		for (int level = professionLevel; level < targetLevel; level++) {
			long levelXp = ProfessionXp.xpToNext(level);
			long remaining = level == professionLevel ? remainingAtCurrent : levelXp;
			if (remaining <= 0) {
				continue;
			}
			planXp += remaining;
			List<Recipe> best = topRecipes(selectedSkill, level, 1);
			if (best.isEmpty()) {
				continue;
			}
			Recipe recipe = best.get(0);
			Ingredient ingredient = ingredientFor(selectedSkill, level);
			double ingredientMultiplier = ingredientMultiplier(ingredient, level);
			long crafts = ceilDiv(remaining, liveCraftXp > 0 ? liveCraftXp : recipe.xp * world * ingredientMultiplier);
			long baseCrafts = ceilDiv(remaining, recipe.xp * world);
			planCrafts += crafts;
			planBaseCrafts += baseCrafts;
			if (ingredient != null) {
				long ingredientAmount = (long) slotCount() * crafts;
				planIngredientCount += ingredientAmount;
				planMaterials.merge(ingredient.name, ingredientAmount, Long::sum);
			}
			for (RecipeMaterial material : recipe.materials) {
				planMaterials.merge(MaterialNames.canonical(material.item),
					scaledTotal(material.amount, crafts), Long::sum);
			}
		}
		for (Map.Entry<String, Long> entry : planMaterials.entrySet()) {
			planMaterialCount += entry.getValue();
		}
		planMaterialCount -= planIngredientCount;
		if (!options.isEmpty()) {
			currentXpPerCraft = liveCraftXp > 0 ? liveCraftXp : options.get(0).recipe.xp * world * currentIngredientMultiplier;
		}
		// Keep an open breakdown in sync when prices/ingredients finish loading.
		if (detailOption != null) {
			CraftOption refreshed = null;
			for (CraftOption option : options) {
				if (option.recipe != null && detailOption.recipe != null
					&& option.recipe.internalName != null
					&& option.recipe.internalName.equals(detailOption.recipe.internalName)) {
					refreshed = option;
					break;
				}
			}
			if (refreshed != null) {
				openBreakdown(refreshed);
			} else {
				closeBreakdown();
			}
		}
	}
	private List<Recipe> topRecipes(String skill, int level, int limit) {
		List<Recipe> band = new ArrayList<>();
		List<Recipe> fallback = new ArrayList<>();
		for (Recipe recipe : recipes) {
			if (recipe == null || recipe.level == null || recipe.xp <= 0) {
				continue;
			}
			if (recipe.skill == null || !recipe.skill.equalsIgnoreCase(skill)) {
				continue;
			}
			if (recipe.level.minimum > level) {
				continue;
			}
			fallback.add(recipe);
			if (recipe.level.minimum >= level - 20) {
				band.add(recipe);
			}
		}
		List<Recipe> candidates = band.isEmpty() ? fallback : band;
		candidates.sort((a, b) -> Integer.compare(b.xp, a.xp));
		return candidates.size() > limit ? new ArrayList<>(candidates.subList(0, limit)) : candidates;
	}
	private static long ceilDiv(long value, double divisor) {
		if (divisor <= 0) {
			return 0;
		}
		return (long) Math.ceil(value / divisor);
	}
	private long resourcesFor(Recipe recipe, long crafts) {
		long total = 0;
		for (RecipeMaterial material : recipe.materials) {
			total += scaledTotal(material.amount, crafts);
		}
		return total;
	}

	/** Base materials per craft after the Profession Speed Bomb. */
	private long scaledPerCraft(int amount) {
		return (long) Math.ceil(amount * materialMultiplier());
	}

	/** Base materials for the whole plan; this is where crafts x0.5 and speed x0.5 stack. */
	private long scaledTotal(int amount, long crafts) {
		return (long) Math.ceil((double) amount * crafts * materialMultiplier());
	}
	private double worldMultiplier() {
		// A Double XP Weekend, an API-detected Double XP event and the Profession XP Bomb all
		// apply the same 2x XP bonus. They do not stack with each other: the craft reduction
		// is capped at a single 2x (crafts / 2).
		return (doubleXpWeekend || xpBomb || doubleXpEvent) ? 2.0 : 1.0;
	}
	/** The Profession Speed Bomb halves the base materials needed, the XP ingredients stay untouched. */
	private double materialMultiplier() {
		return professionSpeed ? 0.5 : 1.0;
	}
	// ---------------------------------------------------------------- render
	@Override
	public void render(GuiGraphics context, int mouseX, int mouseY, float delta) {
		super.render(context, mouseX, mouseY, delta);
		// Overlay after the widget layer so buttons/EditBoxes can never draw on top
		// of the modal breakdown.
		if (detailOption != null) {
			drawRecipeBreakdown(context, mouseX, mouseY);
		}
	}
	@Override
	public void renderBackground(GuiGraphics context, int mouseX, int mouseY, float delta) {
		syncIngredients();
		context.fill(0, 0, this.width, this.height, 0xB4000000);
		drawPanel(context, mouseX, mouseY);
	}
	private void drawPanel(GuiGraphics context, int mouseX, int mouseY) {
		int x = panelX;
		int y = panelY;
		context.fill(x + 6, y + 8, x + PANEL_W + 6, y + PANEL_H + 8, 0x50000000);
		context.fill(x, y, x + PANEL_W, y + PANEL_H, C_BG);
		context.fill(x, y, x + PANEL_W, y + HEADER_H, C_HEADER);
		context.fill(x, y, x + PANEL_W, y + 2, C_ACCENT);
		context.fill(x, y, x + PANEL_W, y + 1, C_BORDER);
		context.fill(x, y + PANEL_H - 1, x + PANEL_W, y + PANEL_H, C_BORDER);
		context.fill(x, y, x + 1, y + PANEL_H, C_BORDER);
		context.fill(x + PANEL_W - 1, y, x + PANEL_W, y + PANEL_H, C_BORDER);
		context.drawString(this.font, "Wynncraft Helper", x + PAD, y + 11, C_TEXT, true);
		context.drawString(this.font, "Crafting profession planner", x + PAD, y + 25, C_LABEL, false);
		String userLine = data != null && data.username != null ? data.username : "authenticated";
		if (data != null) {
			userLine = userLine + "  ·  " + data.characterType + " " + data.level;
		}
		context.drawString(this.font, userLine, x + PANEL_W - PAD - this.font.width(userLine), y + 17, C_MUTED, false);
		if (loading && recipes.isEmpty()) {
			drawLoading(context, x, y);
			drawFooter(context, x, y);
			return;
		}
		if (error != null && recipes.isEmpty()) {
			drawError(context, x, y);
			drawFooter(context, x, y);
			return;
		}
		drawLeftColumn(context, x, y);
		drawRightColumn(context, x, y, mouseX, mouseY);
		drawFooter(context, x, y);
	}
	private void drawLoading(GuiGraphics context, int x, int y) {
		int centerX = x + PANEL_W / 2;
		int centerY = y + PANEL_H / 2 - 24;
		String dots = ".".repeat(1 + (int) ((System.currentTimeMillis() / 400L) % 3L));
		context.drawCenteredString(this.font, "Loading Wynncraft data" + dots, centerX, centerY, C_TEXT);
		if (pagesTotal > 0) {
			context.drawCenteredString(this.font, "Recipe database  " + pagesDone + " / " + pagesTotal,
				centerX, centerY + 18, C_MUTED);
			int barWidth = 220;
			int barX = centerX - barWidth / 2;
			int barY = centerY + 34;
			context.fill(barX, barY, barX + barWidth, barY + 4, C_BORDER);
			float progress = Math.min(1.0f, pagesDone / (float) pagesTotal);
			context.fill(barX, barY, barX + Math.round(barWidth * progress), barY + 4, C_ACCENT);
		} else {
			context.drawCenteredString(this.font, "Contacting the Wynncraft API", centerX, centerY + 18, C_MUTED);
		}
		context.drawCenteredString(this.font, "The recipe database is downloaded once and cached on disk.",
			centerX, centerY + 56, C_LABEL);
	}
	private void drawError(GuiGraphics context, int x, int y) {
		int centerX = x + PANEL_W / 2;
		int centerY = y + PANEL_H / 2 - 18;
		context.drawCenteredString(this.font, "Could not load data", centerX, centerY, C_ERROR);
		String message = error == null ? "Unknown error" : trim(error, PANEL_W - 48);
		context.drawCenteredString(this.font, message, centerX, centerY + 16, C_MUTED);
		context.drawCenteredString(this.font, "Press Reload to try again", centerX, centerY + 36, C_LABEL);
	}
	private void drawLeftColumn(GuiGraphics context, int x, int y) {
		int left = x + PAD;
		int top = y + BODY_TOP;
		context.drawString(this.font, "Level " + professionLevel, left, top + 28, C_TEXT, false);
		context.drawString(this.font, "Progress " + progressPercent + "%", left, top + 50, C_TEXT, false);
		context.drawString(this.font, "Target " + targetLevel, left, top + 72, C_TEXT, false);
		divider(context, left, top + 92, LEFT_W);
		label(context, "LEVEL PLAN", left, top + 98);
		context.drawString(this.font, "Level " + professionLevel + " -> " + targetLevel + "  ·  "
			+ fmt(planXp) + " XP", left, top + 110, C_MUTED, false);
		context.drawString(this.font, "Crafts needed", left, top + 124, C_MUTED, false);
		context.drawString(this.font, fmt(planCrafts), left, top + 135, C_ACCENT, true);
		if (pinned != null && planCrafts > 0 && planCrafts < planBaseCrafts) {
			context.drawString(this.font, "without ingredients: " + fmt(planBaseCrafts) + " crafts",
				left, top + 152, C_LABEL, false);
		} else if (ingredientPreset <= 0 && pinned == null) {
			context.drawString(this.font, "add ingredients for much more XP",
				left, top + 152, C_GOLD, false);
		} else if (ingredientsLoading) {
			context.drawString(this.font, "loading ingredient data…",
				left, top + 152, C_LABEL, false);
		} else if (pinned != null) {
			context.drawString(this.font, "pinned · " + fmtMult(currentIngredientMultiplier) + "x XP",
				left, top + 152, C_INGREDIENT, false);
		} else {
			context.drawString(this.font, "no usable ingredient for this level",
				left, top + 152, C_LABEL, false);
		}
		String materialsLine = "Materials: " + fmt(planMaterialCount);
		if (planIngredientCount > 0) {
			materialsLine = materialsLine + "  ·  ingredients: " + fmt(planIngredientCount);
		}
		context.drawString(this.font, trim(materialsLine, LEFT_W), left, top + 164, C_TEXT, false);
		String perCraft = "XP per craft: " + fmtXp(currentXpPerCraft) + "  ·  base "
			+ (options.isEmpty() ? "0" : fmt(Math.round(options.get(0).recipe.xp * worldMultiplier())));
		context.drawString(this.font, trim(perCraft, LEFT_W), left, top + 176, C_MUTED, false);
		divider(context, left, top + 200, LEFT_W);
		// WORLD BONUS indicator: "WORLD BONUS: 2x XP / Speed", colored per active state.
		// Weekend and the XP bomb are both 2x sources, so they share the XP segment.
		boolean xpBonus = doubleXpWeekend || xpBomb || doubleXpEvent;
		String wbPrefix = "WORLD BONUS: ";
		String wbXp = "2x XP";
		String wbSep = " / ";
		String wbSpeed = "Speed";
		String wbTag = bonusTag(doubleXpEvent, doubleXpWeekend, xpBomb);
		// The left column is narrow: shorten the source tag before it can overflow.
		if (this.font.width(wbPrefix + wbXp + wbTag + wbSep + wbSpeed) > LEFT_W) {
			wbTag = shortBonusTag(doubleXpEvent, doubleXpWeekend, xpBomb);
		}
		if (this.font.width(wbPrefix + wbXp + wbTag + wbSep + wbSpeed) > LEFT_W) {
			wbTag = "";
		}
		boolean wbBoth = xpBonus && professionSpeed;
		int wbGray = C_LABEL;
		int wbOrange = C_GOLD;
		int wbColorInactive = wbGray;
		int wbColorActive = wbOrange;
		int wbPrefixColor = wbBoth ? wbColorActive : wbColorInactive;
		int wbXpColor = xpBonus ? wbColorActive : wbColorInactive;
		int wbSepColor = wbBoth ? wbColorActive : wbColorInactive;
		int wbSpeedColor = professionSpeed ? wbColorActive : wbColorInactive;
		int wbX = left;
		int wbY = top + 206;
		context.drawString(this.font, wbPrefix, wbX, wbY, wbPrefixColor, false);
		wbX += this.font.width(wbPrefix);
		context.drawString(this.font, wbXp, wbX, wbY, wbXpColor, false);
		wbX += this.font.width(wbXp);
		if (!wbTag.isEmpty()) {
			context.drawString(this.font, wbTag, wbX, wbY, wbColorActive, false);
			wbX += this.font.width(wbTag);
		}
		context.drawString(this.font, wbSep, wbX, wbY, wbSepColor, false);
		wbX += this.font.width(wbSep);
		context.drawString(this.font, wbSpeed, wbX, wbY, wbSpeedColor, false);
		context.drawString(this.font, "XP sources share one 2x cap", left, top + 240, C_LABEL, false);
	}

	/** Full WORLD BONUS source tag, e.g. {@code " (Event+Weekend+XP Bomb)"}. */
	private static String bonusTag(boolean doubleXpEvent, boolean doubleXpWeekend, boolean xpBomb) {
		StringBuilder tag = new StringBuilder();
		if (doubleXpEvent) {
			tag.append("Event");
		}
		if (doubleXpWeekend) {
			appendPart(tag, "Weekend");
		}
		if (xpBomb) {
			appendPart(tag, "XP Bomb");
		}
		return tag.length() == 0 ? "" : " (" + tag + ")";
	}

	/** Compact tag used when the full one would not fit the left column. */
	private static String shortBonusTag(boolean doubleXpEvent, boolean doubleXpWeekend, boolean xpBomb) {
		if (doubleXpEvent) {
			return " (Event)";
		}
		if (doubleXpWeekend) {
			return " (Weekend)";
		}
		if (xpBomb) {
			return " (XP)";
		}
		return "";
	}

	private static void appendPart(StringBuilder tag, String part) {
		if (tag.length() > 0) {
			tag.append('+');
		}
		tag.append(part);
	}
	private void drawRightColumn(GuiGraphics context, int x, int y, int mouseX, int mouseY) {
		int right = x + PAD + LEFT_W + COL_GAP;
		int top = y + BODY_TOP;
		if (searchOpen) {
			drawSearchResults(context, right, top, mouseX, mouseY);
			return;
		}
		label(context, showMaterials ? "PLAN MATERIALS" : "CRAFTABLE ITEMS", right, top);
		if (showMaterials) {
			drawMaterials(context, right, top, mouseX, mouseY);
			return;
		}
		if (!showMaterials) {
			craftableTypeFirstY = top + 16;
			if (craftableTypes.isEmpty()) {
				context.drawString(this.font, "No craftable items at this level", right, craftableTypeFirstY, C_LABEL, false);
			} else {
				int rowY = craftableTypeFirstY;
				for (int index = 0; index < craftableTypes.size(); index++) {
					String type = craftableTypes.get(index);
					Recipe recipe = craftableTypeRecipe.get(type);
					boolean hovered = mouseX >= right && mouseX < right + RIGHT_W
						&& mouseY >= rowY && mouseY < rowY + 20;
					if (hovered) {
						context.fill(right, rowY, right + RIGHT_W, rowY + 20, 0xFF1E2631);
					}
					if (selectedCraftType != null && selectedCraftType.equalsIgnoreCase(type)) {
						context.fill(right, rowY, right + 3, rowY + 20, C_ACCENT);
					}
					String name = type.substring(0, 1).toUpperCase(java.util.Locale.ROOT) + type.substring(1);
					context.drawString(this.font, name, right + 8, rowY + 4, C_TEXT, true);
					if (recipe != null) {
						String meta = "Lv " + recipe.level.minimum + "-" + recipe.level.maximum
							+ "  \u00b7  " + fmt(recipe.xp) + " XP";
						context.drawString(this.font, trim(meta, RIGHT_W - 8), right + 8, rowY + 14, C_MUTED, false);
					}
					rowY += 22;
				}
			}
		}
	}
	private void drawMaterials(GuiGraphics context, int x, int y, int mouseX, int mouseY) {
		List<Map.Entry<String, Long>> entries = sortedPlanMaterials();
		if (entries.isEmpty()) {
			context.drawString(this.font, "No materials calculated.", x, y + 20, C_LABEL, false);
			return;
		}
		int rowY = y + 18;
		int shown = Math.min(10, entries.size());
		for (int index = 0; index < shown; index++) {
			Map.Entry<String, Long> entry = entries.get(index);
			boolean isIngredient = false;
			for (CraftOption option : options) {
				if (option.ingredient != null && option.ingredient.name.equals(entry.getKey())) {
					isIngredient = true;
					break;
				}
			}
			int color = isIngredient ? C_INGREDIENT : C_TEXT;
			boolean hovered = mouseX >= x && mouseX < x + RIGHT_W
				&& mouseY >= rowY - 3 && mouseY < rowY + 15;
			if (hovered) {
				context.fill(x, rowY - 3, x + RIGHT_W, rowY + 15, 0xFF1E2631);
			}
			context.fill(x, rowY - 3, x + RIGHT_W, rowY - 2, C_DIVIDER);
			String amount = "x" + fmt(entry.getValue());
			int amountWidth = this.font.width(amount);
			context.drawString(this.font, trim(entry.getKey(), RIGHT_W - amountWidth - 10), x, rowY, color, false);
			context.drawString(this.font, amount, x + RIGHT_W - amountWidth, rowY,
				isIngredient ? C_INGREDIENT : C_ACCENT, false);
			rowY += 19;
		}
		if (entries.size() > shown) {
			context.drawString(this.font, "+" + (entries.size() - shown) + " more materials", x, rowY, C_LABEL, false);
			rowY += 14;
		}
		context.drawString(this.font, "click a material for its recipe breakdown", x, rowY + 20, C_LABEL, false);
	}
	private void drawCard(GuiGraphics context, int x, int y, int width, int height, int rank,
						  CraftOption option, int mouseX, int mouseY) {
		int rankColor = rank == 1 ? C_GOLD : rank == 2 ? C_SILVER : C_BRONZE;
		boolean hovered = mouseX >= x && mouseX < x + width && mouseY >= y && mouseY < y + height;
		context.fill(x, y, x + width, y + height, C_CARD);
		if (hovered) {
			context.fill(x, y, x + width, y + height, 0x1AFFFFFF);
		}
		context.fill(x, y, x + 3, y + height, rankColor);
		context.fill(x, y, x + width, y + 1, C_CARD_BORDER);
		context.fill(x, y + height - 1, x + width, y + height, C_CARD_BORDER);
		context.fill(x + width - 1, y, x + width, y + height, C_CARD_BORDER);
		context.drawString(this.font, String.valueOf(rank), x + 12, y + 10, rankColor, true);
		context.drawString(this.font, trim(option.recipe.internalName, width - 36), x + 24, y + 10, C_TEXT, true);
		double perCraft = option.recipe.xp * worldMultiplier() * option.multiplier;
		String xpLine = fmtXp(perCraft) + " XP/craft  ·  " + fmt(option.crafts) + " crafts";
		context.drawString(this.font, trim(xpLine, width - 24), x + 12, y + 24, C_MUTED, false);
		context.drawString(this.font, trim(materialsLine(option.recipe), width - 24), x + 12, y + 37, C_LABEL, false);
		String ingredientLine;
		if (option.ingredient == null) {
			ingredientLine = "no ingredients";
		} else {
			ingredientLine = slotCount() + "x " + option.ingredient.name + "  (T" + option.ingredient.tierIndex()
				+ " · lv " + option.ingredient.level + ")  +x" + fmtMult(option.multiplier);
		}
		context.drawString(this.font, trim(ingredientLine, width - 24), x + 12, y + 50,
			option.ingredient == null ? C_LABEL : C_INGREDIENT, false);
		String totalLine = "Total: " + fmt(option.resources) + " materials";
		if (option.ingredients > 0) {
			totalLine = totalLine + "  ·  " + fmt(option.ingredients) + " ingredients";
		}
		context.drawString(this.font, trim(totalLine, width - 24), x + 12, y + 62, C_MUTED, false);
	}
	private void drawEmptyCard(GuiGraphics context, int x, int y, int width, int height, int rank) {
		context.fill(x, y, x + width, y + height, 0xFF161A20);
		context.fill(x, y, x + width, y + 1, C_CARD_BORDER);
		context.fill(x, y + height - 1, x + width, y + height, C_CARD_BORDER);
		context.drawString(this.font, rank + ".  No craftable recipe in range", x + 12, y + 10, C_LABEL, false);
	}
	private void drawFooter(GuiGraphics context, int x, int y) {
		divider(context, x + PAD, y + PANEL_H - FOOTER_H, PANEL_W - PAD * 2);
		String status;
		int color = C_MUTED;
		if (error != null) {
			status = error;
			color = C_ERROR;
		} else if (loading) {
			status = pagesTotal > 0
				? "Fetching recipes " + pagesDone + "/" + pagesTotal + "…"
				: "Loading…";
		} else if (ingredientsLoading) {
			status = ProfessionConfig.displayName(selectedSkill) + " " + professionLevel
				+ "  ·  fetching ingredients "
				+ (ingredientPagesTotal > 0 ? ingredientPagesDone + "/" + ingredientPagesTotal : "…");
		} else {
			status = ProfessionConfig.displayName(selectedSkill) + " " + professionLevel
				+ "  ·  x" + fmtMult(worldMultiplier()) + " world"
				+ "  ·  ing x" + fmtMult(currentIngredientMultiplier)
				+ "  ·  " + (statusNote == null ? "Ready" : statusNote);
		}
		int maxWidth = PANEL_W - PAD * 2 - 170;
		context.drawString(this.font, trim(status, maxWidth), x + PAD, y + PANEL_H - 26, color, false);
	}
	// ---------------------------------------------------------------- helpers
	private void label(GuiGraphics context, String text, int x, int y) {
		context.drawString(this.font, text, x, y, C_LABEL, false);
	}
	private void divider(GuiGraphics context, int x, int y, int width) {
		context.fill(x, y, x + width, y + 1, C_DIVIDER);
	}
	private String materialsLine(Recipe recipe) {
		StringBuilder builder = new StringBuilder();
		for (RecipeMaterial material : recipe.materials) {
			if (builder.length() > 0) {
				builder.append("  ·  ");
			}
			builder.append(MaterialNames.canonical(material.item)).append(" x").append(scaledPerCraft(material.amount));
		}
		return builder.length() == 0 ? "No materials listed" : builder.toString();
	}
	private String materialsLine(Map<String, Long> materials) {
		if (materials == null || materials.isEmpty()) {
			return "No materials listed";
		}
		StringBuilder builder = new StringBuilder();
		int shown = 0;
		for (Map.Entry<String, Long> entry : materials.entrySet()) {
			if (shown >= 3) {
				builder.append("  ·  +").append(materials.size() - shown).append(" more");
				break;
			}
			if (builder.length() > 0) {
				builder.append("  ·  ");
			}
			builder.append(MaterialNames.canonical(entry.getKey())).append(" x").append(fmt(entry.getValue()));
			shown++;
		}
		return builder.toString();
	}
	private String trim(String text, int maxWidth) {
		if (text == null) {
			return "";
		}
		if (this.font.width(text) <= maxWidth) {
			return text;
		}
		String ellipsis = "…";
		int available = maxWidth - this.font.width(ellipsis);
		StringBuilder builder = new StringBuilder();
		for (int index = 0; index < text.length(); index++) {
			char character = text.charAt(index);
			if (this.font.width(builder.toString() + character) > available) {
				break;
			}
			builder.append(character);
		}
		return builder.append(ellipsis).toString();
	}
	private String fmt(long value) {
		return NUMBER.format(value);
	}
	private String fmtXp(double value) {
		if (Math.abs(value - Math.rint(value)) < 0.01) {
			return NUMBER.format(Math.round(value));
		}
		return NUMBER_ONE.format(value);
	}
	private String fmtMult(double value) {
		if (Math.abs(value - Math.rint(value)) < 0.01) {
			return String.valueOf((int) Math.rint(value));
		}
		return String.format(Locale.ROOT, "%.1f", value);
	}
	@Override
	public boolean shouldCloseOnEsc() {
		return true;
	}
	@Override
	public void onClose() {
		this.minecraft.setScreen(null);
		if (WynncraftHelperClient.screen == this) {
			WynncraftHelperClient.screen = null;
		}
	}
	private static final class CraftOption {
		private final Recipe recipe;
		private final long crafts;
		private final long resources;
		private final long ingredients;
		private final Ingredient ingredient;
		private final double multiplier;
		private CraftOption(Recipe recipe, long crafts, long resources, long ingredients,
							Ingredient ingredient, double multiplier) {
			this.recipe = recipe;
			this.crafts = crafts;
			this.resources = resources;
			this.ingredients = ingredients;
			this.ingredient = ingredient;
			this.multiplier = multiplier;
		}
	}
	/** One level band of the clicked recipe: how many crafts and which materials it needs. */
	private static final class BreakdownLevel {
		private final int level;
		private final long crafts;
		private final Map<String, Long> materials;
		private BreakdownLevel(int level, long crafts, Map<String, Long> materials) {
			this.level = level;
			this.crafts = crafts;
			this.materials = materials;
		}
	}
}
