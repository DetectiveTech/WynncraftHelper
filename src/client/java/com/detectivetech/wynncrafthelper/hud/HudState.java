package com.detectivetech.wynncrafthelper.hud;

import com.detectivetech.wynncrafthelper.api.WynncraftEvents;
import com.detectivetech.wynncrafthelper.compat.WynntilsBridge;
import com.detectivetech.wynncrafthelper.config.ManualItem;
import com.detectivetech.wynncrafthelper.config.ProfessionConfig;
import com.detectivetech.wynncrafthelper.config.ProfessionXp;
import com.detectivetech.wynncrafthelper.util.MaterialNames;
import com.detectivetech.wynncrafthelper.xp.EmpiricalXp;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Renders and manages the pinned crafting HUD. It is independent of the planner screen so
 * the goal keeps updating while the player is crafting in the world.
 */
public final class HudState {
	public static final HudState INSTANCE = new HudState();

	private static final int width = 300;
	private static final int MIN_W = 180;
	private static final int MAX_W = 520;
	private static final int HANDLE = 9;
	private static final int PAD = 8;
	private static final int LINE = 11;
	private static final long SCAN_INTERVAL_MS = 500L;

	private static final int C_BG = 0xE612151A;
	private static final int C_BORDER = 0xFF3A4351;
	private static final int C_DRAG = 0xFFFFC24B;
	private static final int C_DIVIDER = 0xFF232932;
	private static final int C_TEXT = 0xFFF2F5F8;
	private static final int C_MUTED = 0xFF8A93A0;
	private static final int C_LABEL = 0xFF6E7A88;
	private static final int C_GOLD = 0xFFFFC24B;
	private static final int C_GREEN = 0xFF57D98A;

	private HudGoal goal;
	private boolean visible;
	private double hudX;
	private double hudY;
	private int hudW;
	private int hudH;

	private List<ItemCount> inventoryCounts = List.of();
	private List<ItemCount> pouchCounts = List.of();
	private long lastScanAt;
	private boolean xpBomb;
	private boolean speedBomb;
	private boolean doubleXpEvent;

	private boolean dragging;
	private boolean resizing;
	private int resizeEdges;
	private int resizeStartX;
	private int resizeStartY;
	private int resizeStartW;
	private int resizeStartH;
	private double resizeMouseStartX;
	private double resizeMouseStartY;
	private int dragX;
	private int dragY;
	private int dragOffsetX;
	private int dragOffsetY;

	private int lastX;
	private int lastY;
	private int lastW;
	private int lastH;
	private int lastMeasuredHeight;
	private long lastRenderAt;
	private int lastCrafts;
	private int lastLevel;
	private int lastTarget;
	private double lastProgress;

	private HudState() {
		goal = ProfessionConfig.pinnedHudGoal();
		visible = ProfessionConfig.hudVisible();
		hudX = ProfessionConfig.hudX();
		hudY = ProfessionConfig.hudY();
		hudW = Math.max(0, ProfessionConfig.hudWidth());
		hudH = Math.max(0, ProfessionConfig.hudHeight());
	}

	public HudGoal goal() {
		return goal;
	}

	public String pinnedSkill() {
		return goal == null ? null : goal.skill;
	}

	public boolean isPinned(String recipeName) {
		return goal != null && recipeName != null && goal.recipeName != null
			&& goal.recipeName.equalsIgnoreCase(recipeName);
	}

	public void pin(HudGoal newGoal) {
		if (newGoal == null) {
			return;
		}
		goal = newGoal;
		visible = true;
		lastCrafts = 0;
		lastLevel = 0;
		lastTarget = 0;
		lastProgress = 0;
		ProfessionConfig.setPinnedHudGoal(newGoal);
		ProfessionConfig.setHudVisible(true);
	}

	public void unpin() {
		goal = null;
		lastCrafts = 0;
		lastLevel = 0;
		lastTarget = 0;
		lastProgress = 0;
		ProfessionConfig.setPinnedHudGoal(null);
	}

	public void toggleVisible() {
		visible = !visible;
		ProfessionConfig.setHudVisible(visible);
	}

	public void resetPouch() {
		pouchCounts = List.of();
		lastScanAt = 0L;
	}

	public Map<String, Integer> combinedSnapshot() {
		Map<String, Integer> result = new HashMap<>();
		for (ItemCount item : inventoryCounts) {
			result.merge(item.normalizedName, item.count, Integer::sum);
		}
		for (ItemCount item : pouchCounts) {
			result.merge(item.normalizedName, item.count, Integer::sum);
		}
		return result;
	}

	public void scanInventoryOnly(Minecraft client) {
		if (client == null || client.player == null) {
			return;
		}
		List<ItemCount> counts = new ArrayList<>();
		Inventory inventory = client.player.getInventory();
		ItemStack offhand = client.player.getOffhandItem();
		boolean offhandCounted = false;
		for (ItemStack stack : inventory.getNonEquipmentItems()) {
			if (stack == offhand) {
				offhandCounted = true;
			}
			addStack(counts, stack);
		}
		if (!offhandCounted) {
			addStack(counts, offhand);
		}
		inventoryCounts = counts;
		if (client.screen instanceof AbstractContainerScreen<?> container) {
			String title = MaterialNames.normalize(container.getTitle().getString());
			if (title.contains("pouch") || title.contains("ingredient")) {
				List<ItemCount> pouch = new ArrayList<>();
				for (Slot slot : container.getMenu().slots) {
					if (slot.container == inventory) {
						continue;
					}
					addStack(pouch, slot.getItem());
				}
				pouchCounts = pouch;
			}
		}
	}

	public void forceRefresh(Minecraft client) {
		lastScanAt = 0L;
		refresh(client);
	}

	/** Keeps the saved profession state fresh for the pinned skill. */
	public void syncConfigFromWynntils() {
		if (goal == null || goal.skill == null) {
			return;
		}
		int level = WynntilsBridge.professionLevel(goal.skill);
		double progress = WynntilsBridge.professionProgressPercent(goal.skill);
		if (level > 0 && level != ProfessionConfig.level(goal.skill)) {
			ProfessionConfig.setLevel(goal.skill, level);
		}
		if (progress >= 0) {
			int rounded = (int) Math.max(0, Math.min(99, Math.round(progress)));
			if (rounded != ProfessionConfig.progress(goal.skill)) {
				ProfessionConfig.setProgress(goal.skill, rounded);
			}
		}
	}

	/** HUD element render callback (in-world, no GUI open). */
	public void renderHud(GuiGraphics graphics) {
		Minecraft client = Minecraft.getInstance();
		if (client.player == null || client.screen != null) {
			return;
		}
		render(graphics, client, false);
	}

	/** Drawn above GUI screens so the border and drag handle stay visible. */
	public void renderOnScreen(GuiGraphics graphics, int mouseX, int mouseY) {
		Minecraft client = Minecraft.getInstance();
		if (client.player == null || client.screen == null) {
			return;
		}
		if (client.screen.getClass().getName().endsWith("WynncraftHelperScreen")) {
			return;
		}
		render(graphics, client, true);
	}

	private void render(GuiGraphics graphics, Minecraft client, boolean interactive) {
		if (!visible || goal == null || goal.skill == null) {
			lastW = 0;
			lastH = 0;
			return;
		}
		refresh(client);

		int level = currentLevel(client);
		double progress = currentProgress(client);
		int target = Math.max(level, goal.targetLevel);
		long remainingXp = remainingXp(level, progress, target);
		double xpPerCraft = xpPerCraft();
		long crafts = xpPerCraft <= 0 ? 0 : (long) Math.ceil(remainingXp / xpPerCraft);
		if (remainingXp <= 0) {
			crafts = 0;
		}
		long now = System.currentTimeMillis();
		if (lastRenderAt == 0 || now - lastRenderAt > 2000L) {
			lastCrafts = (int) Math.min(Integer.MAX_VALUE, crafts);
		} else if (lastCrafts > 0 && lastProgress != progress && crafts < lastCrafts - 1) {
			crafts = Math.max(0, lastCrafts - 1);
		}
		lastCrafts = (int) Math.min(Integer.MAX_VALUE, crafts);
		lastLevel = level;
		lastTarget = target;
		lastProgress = progress;
		lastRenderAt = now;

		Font font = client.font;
		int screenW = client.getWindow().getGuiScaledWidth();
		int screenH = client.getWindow().getGuiScaledHeight();

		List<Row> materials = buildRows(goal.perCraftMaterials, crafts, true);
		List<Row> ingredients = buildRows(goal.perCraftIngredients, crafts, false);

		// Compact column layout: names on the left, counts in a right-aligned column
		// with a fixed gap, instead of stretching the counts to the far panel edge.
		int maxNameWidth = 0;
		int maxCountWidth = 0;
		for (Row row : materials) {
			maxNameWidth = Math.max(maxNameWidth, font.width(row.name));
			maxCountWidth = Math.max(maxCountWidth, font.width(fmt(row.current) + " / " + fmt(row.required)));
		}
		for (Row row : ingredients) {
			maxNameWidth = Math.max(maxNameWidth, font.width(row.name));
			maxCountWidth = Math.max(maxCountWidth, font.width(fmt(row.current) + " / " + fmt(row.required)));
		}
		int titleWidth = font.width(ProfessionConfig.displayName(goal.skill) + "  Lv " + level + " -> " + target);
		int statusWidth = font.width(crafts <= 0 ? "GOAL COMPLETE" : fmt(crafts) + " Crafts Remaining");
		maxNameWidth = Math.max(maxNameWidth, Math.max(titleWidth, statusWidth) - maxCountWidth);
		int contentWidth = Math.max(120, maxNameWidth + 12 + maxCountWidth);
		int measuredHeight = measureHeight(materials, ingredients);
		int height = hudH > 0 ? Math.max(measuredHeight, hudH) : measuredHeight;

		int width = hudW > 0 ? hudW : Math.max(MIN_W, contentWidth + PAD * 2);
		width = clamp(width, Math.max(MIN_W, contentWidth + PAD * 2), Math.max(MIN_W, screenW - 10));
		int x;
		int y;
		if (dragging) {
			x = dragX;
			y = dragY;
		} else if (resizing) {
			x = (int) Math.round(hudX);
			y = (int) Math.round(hudY);
		} else {
			x = (int) Math.round(hudX < 0 ? screenW - width - 6 : hudX);
			y = (int) Math.round(hudY < 0 ? 6 : hudY);
		}
		x = clamp(x, 0, Math.max(0, screenW - width));
		y = clamp(y, 0, Math.max(0, screenH - height));

		// Panel and (when the cursor is unlocked) a visible drag border.
		graphics.fill(x, y, x + width, y + height, C_BG);
		graphics.fill(x, y, x + width, y + 1, C_DRAG);
		graphics.fill(x, y + height - 1, x + width, y + height, C_BORDER);
		graphics.fill(x, y, x + 1, y + height, interactive ? C_DRAG : C_BORDER);
		graphics.fill(x + width - 1, y, x + width, y + height, interactive ? C_DRAG : C_BORDER);
		if (interactive) {
			// Small grip in the top-right corner: the whole panel can be dragged from here.
			int gx = x + width - 18;
			int gy = y + 5;
			for (int line = 0; line < 3; line++) {
				graphics.fill(gx, gy + line * 3, gx + 10, gy + line * 3 + 1, C_DRAG);
			}
		}

		if (interactive) {
			// Subtle resize handles on the corners and edges.
			int hx = x - 3;
			int hy = y - 3;
			int hw = width + 6;
			int hh = height + 6;
			int hs = 6;
			// corners
			graphics.fill(x - 2, y - 2, x - 2 + hs, y - 2 + hs, C_DRAG);
			graphics.fill(x + width - hs + 2, y - 2, x + width + 2, y - 2 + hs, C_DRAG);
			graphics.fill(x - 2, y + height - hs + 2, x - 2 + hs, y + height + 2, C_DRAG);
			graphics.fill(x + width - hs + 2, y + height - hs + 2, x + width + 2, y + height + 2, C_DRAG);
			// edge midpoints
			graphics.fill(x + width / 2 - 3, y - 4, x + width / 2 + 3, y + 2, C_DRAG);
			graphics.fill(x + width / 2 - 3, y + height - 2, x + width / 2 + 3, y + height + 4, C_DRAG);
			graphics.fill(x - 4, y + height / 2 - 3, x + 2, y + height / 2 + 3, C_DRAG);
			graphics.fill(x + width - 2, y + height / 2 - 3, x + width + 4, y + height / 2 + 3, C_DRAG);
		}

		int cy = y + 7;
		String title = ProfessionConfig.displayName(goal.skill) + "  Lv " + level + " -> " + target;
		graphics.drawString(font, trim(font, title, width - PAD * 2 - 16), x + PAD, cy, C_TEXT, false);
		cy += LINE;

		String status = crafts <= 0 ? "GOAL COMPLETE" : fmt(crafts) + " Crafts Remaining";
		graphics.drawString(font, status, x + PAD, cy, crafts <= 0 ? C_GREEN : C_GOLD, false);
		cy += LINE;

		graphics.fill(x + PAD, cy + 1, x + width - PAD, cy + 2, C_DIVIDER);
		cy += 7;

		if (!materials.isEmpty()) {
			graphics.drawString(font, "BASE MATERIALS", x + PAD, cy, C_LABEL, false);
			cy += LINE;
			for (Row row : materials) {
				drawRow(graphics, font, x, cy, row, width);
				cy += LINE;
			}
			cy += 4;
		}
		if (!ingredients.isEmpty()) {
			graphics.drawString(font, "INGREDIENTS", x + PAD, cy, C_LABEL, false);
			cy += LINE;
			for (Row row : ingredients) {
				drawRow(graphics, font, x, cy, row, width);
				cy += LINE;
			}
			cy += 4;
		}
		if (materials.isEmpty() && ingredients.isEmpty()) {
			graphics.drawString(font, "No tracked materials", x + PAD, cy, C_MUTED, false);
		}

		lastX = x;
		lastY = y;
		lastW = width;
		lastH = height;
		lastMeasuredHeight = measuredHeight;
	}

	private void drawRow(GuiGraphics graphics, Font font, int x, int y, Row row, int panelWidth) {
		boolean complete = row.current >= row.required;
		int color = complete ? C_GREEN : C_TEXT;
		String counts = fmt(row.current) + " / " + fmt(row.required);
		int countsWidth = font.width(counts);
		String name = trim(font, row.name, panelWidth - PAD * 2 - countsWidth - 12);
		graphics.drawString(font, name, x + PAD, y, color, false);
		graphics.drawString(font, counts, x + panelWidth - PAD - countsWidth, y,
			complete ? C_GREEN : C_MUTED, false);
	}

	private List<Row> buildRows(Map<String, Integer> perCraft, long crafts, boolean speedScaled) {
		List<Row> rows = new ArrayList<>();
		if (perCraft == null) {
			return rows;
		}
		double materialMultiplier = speedScaled && speedBomb ? 0.5 : 1.0;
		// Goals saved before the "Refined" prefix was removed may still carry both names;
		// merge them into one canonical row so the panel never shows duplicates.
		Map<String, Integer> merged = new LinkedHashMap<>();
		for (Map.Entry<String, Integer> entry : perCraft.entrySet()) {
			int amount = entry.getValue() == null ? 0 : entry.getValue();
			merged.merge(MaterialNames.canonical(entry.getKey()), amount, Integer::sum);
		}
		for (Map.Entry<String, Integer> entry : merged.entrySet()) {
			long required = (long) Math.ceil(entry.getValue() * (double) crafts * materialMultiplier);
			rows.add(new Row(entry.getKey(), currentCount(entry.getKey()), required));
		}
		return rows;
	}

	private int measureHeight(List<Row> materials, List<Row> ingredients) {
		int height = 7 + LINE + LINE + 7;
		if (!materials.isEmpty()) {
			height += LINE + materials.size() * LINE + 4;
		}
		if (!ingredients.isEmpty()) {
			height += LINE + ingredients.size() * LINE + 4;
		}
		return height + 4;
	}

	private int currentLevel(Minecraft client) {
		int live = WynntilsBridge.professionLevel(goal.skill);
		if (live > 0) {
			return Math.min(live, ProfessionXp.MAX_LEVEL);
		}
		return ProfessionConfig.level(goal.skill);
	}

	private double currentProgress(Minecraft client) {
		double live = WynntilsBridge.professionProgressPercent(goal.skill);
		if (live >= 0) {
			return Math.max(0.0, Math.min(99.999, live));
		}
		return ProfessionConfig.progress(goal.skill);
	}

	private long remainingXp(int level, double progress, int target) {
		if (level >= target || level >= ProfessionXp.MAX_LEVEL) {
			return 0L;
		}
		long total = 0L;
		for (int current = level; current < target && current < ProfessionXp.MAX_LEVEL; current++) {
			long needed = ProfessionXp.xpToNext(current);
			total += current == level ? Math.round(needed * (100.0 - progress) / 100.0) : needed;
		}
		return total;
	}

	private double xpPerCraft() {
		String signature = EmpiricalXp.signatureFor(goal);
		Double empirical = signature == null ? null : EmpiricalXp.lookup(signature);
		double world = (ProfessionConfig.doubleXpWeekend() || xpBomb || doubleXpEvent) ? 2.0 : 1.0;
		if (empirical != null && empirical > 0) {
			return empirical;
		}
		double ingredient = goal.ingredientMultiplier > 0 ? goal.ingredientMultiplier : 1.0;
		return Math.max(0, goal.baseXp) * world * ingredient;
	}

	private void refresh(Minecraft client) {
		long now = System.currentTimeMillis();
		if (now - lastScanAt < SCAN_INTERVAL_MS) {
			return;
		}
		lastScanAt = now;

		Boolean xp = WynntilsBridge.professionXpBombActive();
		Boolean speed = WynntilsBridge.professionSpeedBombActive();
		xpBomb = xp != null ? xp : ProfessionConfig.xpBomb();
		speedBomb = Boolean.TRUE.equals(speed);
		doubleXpEvent = WynncraftEvents.doubleXpActive();
		WynncraftEvents.refreshAsync();

		List<ItemCount> counts = new ArrayList<>();
		if (client.player != null) {
			Inventory inventory = client.player.getInventory();
			ItemStack offhand = client.player.getOffhandItem();
			boolean offhandCounted = false;
			for (ItemStack stack : inventory.getNonEquipmentItems()) {
				if (stack == offhand) {
					offhandCounted = true;
				}
				addStack(counts, stack);
			}
			if (!offhandCounted) {
				addStack(counts, offhand);
			}
			inventoryCounts = counts;

			// The Ingredient Pouch is a normal container screen. Cache its contents so the
			// counts stay available after the pouch is closed.
			if (client.screen instanceof AbstractContainerScreen<?> container) {
				String title = MaterialNames.normalize(container.getTitle().getString());
				if (title.contains("pouch") || title.contains("ingredient")) {
					List<ItemCount> pouch = new ArrayList<>();
					for (Slot slot : container.getMenu().slots) {
						if (slot.container == inventory) {
							continue;
						}
						addStack(pouch, slot.getItem());
					}
					pouchCounts = pouch;
				}
			}
		}
	}

	private int currentCount(String name) {
		String canonical = MaterialNames.canonical(name);
		if (canonical.isEmpty()) {
			return 0;
		}
		// A player-registered link always wins over the automatic name match.
		ManualItem manual = ProfessionConfig.manualItem(canonical);
		int total = 0;
		for (ItemCount item : inventoryCounts) {
			if (matches(manual, canonical, item)) {
				total += item.count;
			}
		}
		for (ItemCount item : pouchCounts) {
			if (matches(manual, canonical, item)) {
				total += item.count;
			}
		}
		if (manual != null && manual.ratio() > 1) {
			total /= manual.ratio();
		}
		return total;
	}

	private static boolean matches(ManualItem manual, String canonical, ItemCount item) {
		if (manual != null) {
			return manual.matches(item.normalizedName);
		}
		return MaterialNames.matches(canonical, item.normalizedName);
	}

	private static void addStack(List<ItemCount> counts, ItemStack stack) {
		if (stack == null || stack.isEmpty()) {
			return;
		}
		String name = MaterialNames.stripFormatting(stack.getHoverName().getString());
		String normalized = MaterialNames.normalize(name);
		if (normalized.isEmpty()) {
			return;
		}
		String itemId = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
		for (ItemCount existing : counts) {
			if (existing.normalizedName.equals(normalized) && existing.itemId.equals(itemId)) {
				existing.count += stack.getCount();
				return;
			}
		}
		counts.add(new ItemCount(normalized, itemId, stack.getCount()));
	}

	// ---------------------------------------------------------------- dragging

	public boolean mouseClicked(MouseButtonEvent event) {
		if (!visible || goal == null || event.button() != 0 || lastW <= 0) {
			return false;
		}
		Minecraft client = Minecraft.getInstance();
		if (client.screen == null
			|| client.screen.getClass().getName().endsWith("WynncraftHelperScreen")) {
			return false;
		}
		int edges = edgesAt(lastX, lastY, lastW, lastH, event.x(), event.y());
		if (edges != 0) {
			resizing = true;
			resizeEdges = edges;
			resizeStartX = lastX;
			resizeStartY = lastY;
			resizeStartW = lastW;
			resizeStartH = lastH;
			resizeMouseStartX = event.x();
			resizeMouseStartY = event.y();
			return true;
		}
		if (event.x() < lastX || event.x() >= lastX + lastW
			|| event.y() < lastY || event.y() >= lastY + lastH) {
			return false;
		}
		dragging = true;
		dragX = lastX;
		dragY = lastY;
		dragOffsetX = (int) Math.round(event.x()) - lastX;
		dragOffsetY = (int) Math.round(event.y()) - lastY;
		return true;
	}

	public boolean mouseDragged(MouseButtonEvent event) {
		if (resizing) {
			Minecraft client = Minecraft.getInstance();
			int screenW = client.getWindow().getGuiScaledWidth();
			int screenH = client.getWindow().getGuiScaledHeight();
			int newX = resizeStartX;
			int newY = resizeStartY;
			int newW = resizeStartW;
			int newH = resizeStartH;
			double dx = event.x() - resizeMouseStartX;
			double dy = event.y() - resizeMouseStartY;
			if ((resizeEdges & 2) != 0) {
				newW = (int) Math.round(resizeStartW + dx);
			}
			if ((resizeEdges & 1) != 0) {
				newW = (int) Math.round(resizeStartW - dx);
				newX = (int) Math.round(resizeStartX + dx);
			}
			if ((resizeEdges & 8) != 0) {
				newH = (int) Math.round(resizeStartH + dy);
			}
			if ((resizeEdges & 4) != 0) {
				newH = (int) Math.round(resizeStartH - dy);
				newY = (int) Math.round(resizeStartY + dy);
			}
			int minH = Math.max(60, lastMeasuredHeight > 0 ? lastMeasuredHeight : 60);
			newW = clamp(newW, MIN_W, Math.max(MIN_W, screenW - 10));
			newH = clamp(newH, minH, Math.max(minH, screenH - 10));
			if ((resizeEdges & 1) != 0) {
				newX = resizeStartX + resizeStartW - newW;
			}
			if ((resizeEdges & 4) != 0) {
				newY = resizeStartY + resizeStartH - newH;
			}
			newX = clamp(newX, 0, Math.max(0, screenW - newW));
			newY = clamp(newY, 0, Math.max(0, screenH - newH));
			hudW = newW;
			hudH = newH;
			hudX = newX;
			hudY = newY;
			return true;
		}
		if (!dragging) {
			return false;
		}
		Minecraft client = Minecraft.getInstance();
		int screenW = client.getWindow().getGuiScaledWidth();
		int screenH = client.getWindow().getGuiScaledHeight();
		dragX = clamp((int) Math.round(event.x()) - dragOffsetX, 0, Math.max(0, screenW - Math.max(1, lastW)));
		dragY = clamp((int) Math.round(event.y()) - dragOffsetY, 0, Math.max(0, screenH - Math.max(1, lastH)));
		return true;
	}

	public boolean mouseReleased(MouseButtonEvent event) {
		if (resizing) {
			resizing = false;
			resizeEdges = 0;
			hudX = clamp((int) Math.round(hudX), 0, Integer.MAX_VALUE);
			hudY = clamp((int) Math.round(hudY), 0, Integer.MAX_VALUE);
			ProfessionConfig.setHudPosition(hudX, hudY);
			ProfessionConfig.setHudSize(hudW, hudH);
			return true;
		}
		if (!dragging) {
			return false;
		}
		dragging = false;
		hudX = dragX;
		hudY = dragY;
		ProfessionConfig.setHudPosition(hudX, hudY);
		ProfessionConfig.setHudSize(hudW, hudH);
		return true;
	}

	private static int edgesAt(int x, int y, int w, int h, double mx, double my) {
		int edges = 0;
		boolean verticalBand = my >= y - HANDLE && my <= y + h + HANDLE;
		boolean horizontalBand = mx >= x - HANDLE && mx <= x + w + HANDLE;
		if (verticalBand && mx >= x - HANDLE && mx <= x + HANDLE) {
			edges |= 1;
		}
		if (verticalBand && mx >= x + w - HANDLE && mx <= x + w + HANDLE) {
			edges |= 2;
		}
		if (horizontalBand && my >= y - HANDLE && my <= y + HANDLE) {
			edges |= 4;
		}
		if (horizontalBand && my >= y + h - HANDLE && my <= y + h + HANDLE) {
			edges |= 8;
		}
		return edges;
	}

	private static int clamp(int value, int min, int max) {
		return Math.max(min, Math.min(max, value));
	}

	private static String fmt(long value) {
		return String.format(Locale.ROOT, "%,d", value);
	}

	private static String trim(Font font, String text, int maxWidth) {
		if (text == null) {
			return "";
		}
		if (font.width(text) <= maxWidth) {
			return text;
		}
		String ellipsis = "...";
		int available = Math.max(0, maxWidth - font.width(ellipsis));
		int end = text.length();
		while (end > 0 && font.width(text.substring(0, end)) > available) {
			end--;
		}
		return text.substring(0, end) + ellipsis;
	}

	private static final class ItemCount {
		private final String normalizedName;
		private final String itemId;
		private int count;

		private ItemCount(String normalizedName, String itemId, int count) {
			this.normalizedName = normalizedName;
			this.itemId = itemId;
			this.count = count;
		}
	}

	private static final class Row {
		private final String name;
		private final int current;
		private final long required;

		private Row(String name, int current, long required) {
			this.name = name;
			this.current = current;
			this.required = required;
		}
	}
}
