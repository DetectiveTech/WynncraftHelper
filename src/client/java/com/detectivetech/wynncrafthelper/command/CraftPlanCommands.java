package com.detectivetech.wynncrafthelper.command;

import com.detectivetech.wynncrafthelper.api.WynncraftEvents;
import com.detectivetech.wynncrafthelper.compat.WynntilsBridge;
import com.detectivetech.wynncrafthelper.config.ManualItem;
import com.detectivetech.wynncrafthelper.config.ProfessionConfig;
import com.detectivetech.wynncrafthelper.config.ProfessionXp;
import com.detectivetech.wynncrafthelper.config.WynnPaths;
import com.detectivetech.wynncrafthelper.hud.HudGoal;
import com.detectivetech.wynncrafthelper.hud.HudState;
import com.detectivetech.wynncrafthelper.util.MaterialNames;
import com.detectivetech.wynncrafthelper.xp.EmpiricalXp;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Client-side {@code /craftplan} commands.
 *
 * <ul>
 *   <li>{@code /craftplan register <material> [tier]} links the held item to a recipe
 *       material. Optional {@code tier} (1, 2, or 3) separates material tiers that share one
 *       display name but have different tooltips (e.g. "Dernic Meat 1", "Dernic Meat 2").</li>
 *   <li>{@code /craftplan unregister <material>} removes the link.</li>
 *   <li>{@code /craftplan debug} prints the held item's display name, internal ID, NBT and
 *       the profession tier read from its tooltip (via Wynntils).</li>
 *   <li>{@code /craftplan list} shows all links.</li>
 *   <li>{@code /craftplan reload} forces the HUD to refresh immediately.</li>
 *   <li>{@code /craftplan measure start} / {@code measure on} / {@code time on} - starts measuring XP.</li>
 *   <li>{@code /craftplan measure stop} / {@code measure off} / {@code time off} - stops and saves average XP.</li>
 *   <li>{@code /craftplan measure status} - shows measurement progress plus estimated crafts/ingredients.</li>
 * </ul>
 */
public final class CraftPlanCommands {
	private static final Logger LOGGER = LoggerFactory.getLogger("WynncraftHelper");

	private CraftPlanCommands() {
	}

	public static void register() {
		ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> dispatcher.register(
			ClientCommandManager.literal("craftplan")
				.then(ClientCommandManager.literal("register")
					.then(ClientCommandManager.argument("args", StringArgumentType.greedyString())
						.executes(CraftPlanCommands::registerItem)))
				.then(ClientCommandManager.literal("unregister")
					.then(ClientCommandManager.argument("material", StringArgumentType.greedyString())
						.executes(CraftPlanCommands::unregisterItem)))
				.then(ClientCommandManager.literal("debug")
					.executes(CraftPlanCommands::debug))
				.then(ClientCommandManager.literal("list")
					.executes(CraftPlanCommands::list))
				.then(ClientCommandManager.literal("reload")
					.executes(CraftPlanCommands::reload))
				.then(ClientCommandManager.literal("craftuntil")
					.then(ClientCommandManager.argument("amount", StringArgumentType.greedyString())
						.executes(CraftPlanCommands::craftUntilStart)))
				.then(ClientCommandManager.literal("export")
					.executes(CraftPlanCommands::exportXpCache))
				.then(ClientCommandManager.literal("import")
					.executes(CraftPlanCommands::importXpCache))
				.then(ClientCommandManager.literal("advise")
					.executes(CraftPlanCommands::adviseIngredient))
				.then(ClientCommandManager.literal("autopin")
					.executes(CraftPlanCommands::autoPinBestRecipe))
				.then(ClientCommandManager.literal("measure")
					.then(ClientCommandManager.literal("start")
						.executes(CraftPlanCommands::measureStart))
					.then(ClientCommandManager.literal("stop")
						.executes(CraftPlanCommands::measureStop))
					.then(ClientCommandManager.literal("status")
						.executes(CraftPlanCommands::measureStatus))
					.then(ClientCommandManager.literal("on")
						.executes(CraftPlanCommands::measureStart))
					.then(ClientCommandManager.literal("off")
						.executes(CraftPlanCommands::measureStop)))
				.then(ClientCommandManager.literal("time")
					.then(ClientCommandManager.literal("on")
						.executes(CraftPlanCommands::measureStart))
					.then(ClientCommandManager.literal("off")
						.executes(CraftPlanCommands::measureStop)))
		));
	}

	private static int registerItem(CommandContext<FabricClientCommandSource> context) {
		FabricClientCommandSource source = context.getSource();
		ItemStack stack = heldItem(source);
		if (stack.isEmpty()) {
			source.sendError(Component.literal("Hold the item you want to link first."));
			return 0;
		}
		String input = unquote(StringArgumentType.getString(context, "args"));
		if (input.isEmpty()) {
			source.sendError(Component.literal("Usage: /craftplan register <material> [tier]"));
			return 0;
		}
		// A trailing tier number (1, 2 or 3) separates material tiers that share one
		// display name but have different tooltips (e.g. "Dernic Meat 1", "Dernic Meat 2").
		// Everything before the trailing number is the material name, so names with spaces
		// work without quotes. The raw:refined ratio is always 1:1 in modern Wynncraft.
		int tier = 0;
		String material = input;
		int split = input.lastIndexOf(' ');
		if (split > 0) {
			String tail = input.substring(split + 1).trim();
			if (tail.matches("^[123]$")) {
				tier = Integer.parseInt(tail);
				material = input.substring(0, split).trim();
			}
		}
		if (material.isEmpty()) {
			source.sendError(Component.literal("Usage: /craftplan register <material> [tier]"));
			return 0;
		}

		String tierKey = tier > 0 ? " t" + tier : "";

		ManualItem item = new ManualItem();
		item.displayName = MaterialNames.canonical(stack.getHoverName().getString());
		item.itemId = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
		item.components = truncate(String.valueOf(stack.getComponentsPatch()), 300);
		item.rawPerUnit = 1; // modern Wynncraft is 1:1; tier is separate from ratio
		// Store with tier key suffix so each tier gets its own HUD row.
		String canonicalMat = MaterialNames.canonical(material);
		String fullKey = canonicalMat + tierKey;
		ProfessionConfig.setManualItem(fullKey, item);

		String linked = MaterialNames.canonical(material) + tierKey;
		String message = "Linked \"" + linked + "\" to held item \"" + item.displayName + "\" (" + item.itemId + ")";
		if (tier > 0) {
			message = message + " [Tier " + tier + "]";
		}
		source.sendFeedback(Component.literal(message));
		LOGGER.info("WynncraftHelper: {}", message);
		return 1;
	}

	private static int unregisterItem(CommandContext<FabricClientCommandSource> context) {
		FabricClientCommandSource source = context.getSource();
		String material = MaterialNames.canonical(unquote(StringArgumentType.getString(context, "material")));
		if (material.isEmpty()) {
			source.sendError(Component.literal("Usage: /craftplan unregister <material>"));
			return 0;
		}
		// Support unregistering with tier: "Dernic Meat 1", "Dernic Meat 2", "Dernic Meat 3"
		String tierSuffix = "";
		if (material.matches(".* t[123]$")) {
			String[] parts = material.split(" t");
			tierSuffix = " t" + parts[parts.length - 1];
			material = parts[0];
		}
		String fullKey = material + tierSuffix;
		if (ProfessionConfig.removeManualItem(fullKey)) {
			source.sendFeedback(Component.literal("Removed manual link for \"" + fullKey + "\"."));
			return 1;
		}
		source.sendError(Component.literal("No manual link registered for \"" + fullKey + "\"."));
		return 0;
	}

	private static int debug(CommandContext<FabricClientCommandSource> context) {
		FabricClientCommandSource source = context.getSource();
		ItemStack stack = heldItem(source);
		if (stack.isEmpty()) {
			source.sendError(Component.literal("Hold the item you want to inspect first."));
			return 0;
		}
		String rawName = stack.getHoverName().getString();
		String display = MaterialNames.stripFormatting(rawName);
		String canonical = MaterialNames.canonical(rawName);
		String normalized = MaterialNames.normalize(rawName);
		String itemId = BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
		String components = truncate(String.valueOf(stack.getComponentsPatch()), 600);

		source.sendFeedback(Component.literal("Held item: " + display));
		source.sendFeedback(Component.literal("Internal ID: " + itemId));
		source.sendFeedback(Component.literal("Wynntils tier: T" + WynntilsBridge.itemTier(stack)));
		source.sendFeedback(Component.literal("Canonical name: " + canonical));
		source.sendFeedback(Component.literal("Normalized match key: " + normalized));
		source.sendFeedback(Component.literal("NBT/components: " + components));

		ManualItem link = ProfessionConfig.manualItem(canonical);
		if (link != null) {
			source.sendFeedback(Component.literal("Manual link: " + link.displayName
				+ (link.ratio() > 1 ? " (" + link.ratio() + " held = 1 unit)" : "")));
		}

		LOGGER.info("WynncraftHelper debug: display={} canonical={} normalized={} id={} components={}",
			display, canonical, normalized, itemId, components);
		return 1;
	}

	private static int list(CommandContext<FabricClientCommandSource> context) {
		FabricClientCommandSource source = context.getSource();
		Map<String, ManualItem> links = ProfessionConfig.manualItems();
		if (links.isEmpty()) {
			source.sendFeedback(Component.literal("No manual item links registered."));
			return 1;
		}
		source.sendFeedback(Component.literal("Manual item links (" + links.size() + "):"));
		int shown = 0;
		for (Map.Entry<String, ManualItem> entry : links.entrySet()) {
			if (shown >= 20) {
				source.sendFeedback(Component.literal("... and " + (links.size() - shown) + " more"));
				break;
			}
			ManualItem item = entry.getValue();
			String line = entry.getKey() + " -> " + (item == null ? "?" : item.displayName);
			if (item != null && item.ratio() > 1) {
				line = line + " (" + item.ratio() + " held = 1 unit)";
			}
			source.sendFeedback(Component.literal(line));
			shown++;
		}
		return 1;
	}

	private static int reload(CommandContext<FabricClientCommandSource> context) {
		FabricClientCommandSource source = context.getSource();
		HudState.INSTANCE.forceRefresh(Minecraft.getInstance());
		source.sendFeedback(Component.literal("HUD refreshed. Config is read automatically from disk on every access."));
		return 1;
	}

	/**
	 * Starts a "craft until X" session. Tracks crafted items via inventory changes and
	 * notifies the player when the target amount is reached.
	 */
	private static int craftUntilStart(CommandContext<FabricClientCommandSource> context) {
		FabricClientCommandSource source = context.getSource();
		String input = unquote(StringArgumentType.getString(context, "amount"));
		if (input.isEmpty()) {
			source.sendError(Component.literal("Usage: /craftplan craftuntil <amount>"));
			return 0;
		}
		int target;
		try {
			target = Integer.parseInt(input.trim());
		} catch (NumberFormatException e) {
			source.sendError(Component.literal("Usage: /craftplan craftuntil <amount> (must be a number)"));
			return 0;
		}
		if (target <= 0) {
			source.sendError(Component.literal("Usage: /craftplan craftuntil <amount> (must be > 0)"));
			return 0;
		}
		EmpiricalXp.startCraftUntil(target);
		source.sendFeedback(Component.literal("Craft until " + target + " started. Craft items to track progress."));
		return 1;
	}

	/** Exports the learned XP cache to a timestamped file in config/WynnHelper/. */
	private static int exportXpCache(CommandContext<FabricClientCommandSource> context) {
		FabricClientCommandSource source = context.getSource();
		Minecraft mc = Minecraft.getInstance();
		try {
			Path src = WynnPaths.resolve("wynncrafthelper-xp-cache.json");
			if (!Files.exists(src)) {
				source.sendFeedback(Component.literal("No XP cache to export."));
				return 1;
			}
			String timestamp = new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(new Date());
			Path dst = WynnPaths.resolve("xp-export-" + timestamp + ".json");
			Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING);
			source.sendFeedback(Component.literal("Exported XP cache to " + dst.getFileName()));
		} catch (Exception e) {
			source.sendError(Component.literal("Export failed: " + e.getMessage()));
			LOGGER.warn("WynncraftHelper: export failed", e);
		}
		return 1;
	}

	/** Imports the learned XP cache from a file in config/WynnHelper/. */
	private static int importXpCache(CommandContext<FabricClientCommandSource> context) {
		FabricClientCommandSource source = context.getSource();
		String input = unquote(StringArgumentType.getString(context, "args"));
		if (input.isEmpty()) {
			source.sendError(Component.literal("Usage: /craftplan import <filename>"));
			return 0;
		}
		try {
			Path src = WynnPaths.resolve(input.trim());
			if (!Files.exists(src)) {
				source.sendError(Component.literal("File not found: " + input));
				return 0;
			}
			Path dst = WynnPaths.resolve("wynncrafthelper-xp-cache.json");
			Files.copy(src, dst, StandardCopyOption.REPLACE_EXISTING);
			// Reload cache
			EmpiricalXp.reloadCache();
			source.sendFeedback(Component.literal("Imported XP cache from " + input));
		} catch (Exception e) {
			source.sendError(Component.literal("Import failed: " + e.getMessage()));
			LOGGER.warn("WynncraftHelper: import failed", e);
		}
		return 1;
	}

	/** Advises the best ingredient tier to use based on available inventory. */
	private static int adviseIngredient(CommandContext<FabricClientCommandSource> context) {
		FabricClientCommandSource source = context.getSource();
		Minecraft mc = Minecraft.getInstance();
		if (mc == null || mc.player == null) {
			source.sendError(Component.literal("Player not available."));
			return 0;
		}
		HudGoal goal = HudState.INSTANCE.goal();
		if (goal == null) {
			source.sendFeedback(Component.literal("No goal pinned. Pin a recipe first."));
			return 1;
		}
		// Scan inventory for ingredients
		Inventory inventory = mc.player.getInventory();
		Map<String, Integer> ingredientCounts = new HashMap<>();
		for (ItemStack stack : inventory.getNonEquipmentItems()) {
			if (stack == null || stack.isEmpty()) {
				continue;
			}
			String name = MaterialNames.normalize(stack.getHoverName().getString());
			if (name.isEmpty()) {
				continue;
			}
			ingredientCounts.merge(name, stack.getCount(), Integer::sum);
		}
		ItemStack offhand = mc.player.getOffhandItem();
		if (offhand != null && !offhand.isEmpty()) {
			String name = MaterialNames.normalize(offhand.getHoverName().getString());
			if (!name.isEmpty()) {
				ingredientCounts.merge(name, offhand.getCount(), Integer::sum);
			}
		}

		StringBuilder sb = new StringBuilder("Ingredient advice for ");
		sb.append(goal.skill).append(" (level ").append(goal.recipeLevel).append("):\n");
		boolean found = false;
		for (int tier = 3; tier >= 1; tier--) {
			for (Map.Entry<String, Integer> entry : ingredientCounts.entrySet()) {
				String name = entry.getKey();
				if (name.contains("tier") || name.contains("t" + tier) || tier == 3 && name.contains("exquisite")
					|| tier == 2 && name.contains("refined") || tier == 1 && name.contains("basic")) {
					sb.append("  T").append(tier).append(": ").append(name).append(" x").append(entry.getValue()).append("\n");
					found = true;
				}
			}
		}
		if (!found) {
			sb.append("  No tiered ingredients found in inventory.");
		}
		sb.append("\nHigher tier ingredients give more XP bonus but require higher ingredient level.");
		source.sendFeedback(Component.literal(sb.toString()));
		return 1;
	}

	/** Auto-pins the best recipe for the current profession level. */
	private static int autoPinBestRecipe(CommandContext<FabricClientCommandSource> context) {
		FabricClientCommandSource source = context.getSource();
		Minecraft mc = Minecraft.getInstance();
		if (mc == null || mc.player == null) {
			source.sendError(Component.literal("Player not available."));
			return 0;
		}
		if (mc.screen == null || !mc.screen.getClass().getName().endsWith("WynncraftHelperScreen")) {
			source.sendFeedback(Component.literal("Open the Wynncraft Helper screen (H key), select a recipe, then click PIN TO HUD."));
			return 1;
		}
		source.sendFeedback(Component.literal("Select a recipe in the breakdown and click PIN TO HUD to pin it with tier selection."));
		return 1;
	}
	private static String estimateRemaining() {
		HudGoal goal = HudState.INSTANCE.goal();
		if (goal == null) {
			return "No goal pinned. Pin a recipe first, then measure.";
		}

		String skill = goal.skill;
		int currentLevel = WynntilsBridge.professionLevel(skill);
		if (currentLevel <= 0) {
			currentLevel = ProfessionConfig.level(skill);
		}
		double progress = WynntilsBridge.professionProgressPercent(skill);
		if (progress < 0) {
			progress = ProfessionConfig.progress(skill);
		}

		int target = Math.max(currentLevel, goal.targetLevel);
		long remainingXp = 0L;
		for (int lvl = currentLevel; lvl < target && lvl < ProfessionXp.MAX_LEVEL; lvl++) {
			long needed = ProfessionXp.xpToNext(lvl);
			remainingXp += lvl == currentLevel
				? Math.round(needed * (100.0 - progress) / 100.0)
				: needed;
		}

		String signature = EmpiricalXp.signatureFor(goal);
		Double empirical = signature == null ? null : EmpiricalXp.lookup(signature);
		boolean worldBonus = ProfessionConfig.doubleXpWeekend()
			|| WynncraftEvents.doubleXpActive();
		Boolean xpBomb = WynntilsBridge.professionXpBombActive();
		worldBonus = worldBonus || Boolean.TRUE.equals(xpBomb);
		double world = worldBonus ? 2.0 : 1.0;

		double xpPerCraft;
		if (empirical != null && empirical > 0) {
			xpPerCraft = empirical;
		} else {
			double ingredient = goal.ingredientMultiplier > 0 ? goal.ingredientMultiplier : 1.0;
			xpPerCraft = Math.max(0, goal.baseXp) * world * ingredient;
		}

		long crafts = xpPerCraft <= 0 ? 0 : (long) Math.ceil((double) remainingXp / xpPerCraft);

		StringBuilder sb = new StringBuilder();
		sb.append("Estimate: ")
		  .append(crafts)
		  .append(" crafts to reach level ")
		  .append(target)
		  .append(" (").append(remainingXp).append(" XP remaining, ")
		  .append((long) xpPerCraft).append(" XP/craft, source=")
		  .append(empirical != null ? "measured" : "formula").append(")");

		if (goal.perCraftMaterials != null && !goal.perCraftMaterials.isEmpty()) {
			sb.append("\nMaterials needed:");
			for (Map.Entry<String, Integer> entry : goal.perCraftMaterials.entrySet()) {
				int amount = entry.getValue() == null ? 0 : entry.getValue();
				sb.append("\n  ").append(entry.getKey()).append(": ").append((long) amount * crafts);
			}
		}
		if (goal.perCraftIngredients != null && !goal.perCraftIngredients.isEmpty()) {
			sb.append("\nIngredients needed:");
			for (Map.Entry<String, Integer> entry : goal.perCraftIngredients.entrySet()) {
				int amount = entry.getValue() == null ? 0 : entry.getValue();
				sb.append("\n  ").append(entry.getKey()).append(": ").append((long) amount * crafts);
			}
		}
		return sb.toString();
	}

	private static int measureStart(CommandContext<FabricClientCommandSource> context) {
		FabricClientCommandSource source = context.getSource();
		if (!EmpiricalXp.startMeasure()) {
			source.sendError(Component.literal("Pin a recipe to the HUD first."));
			return 0;
		}
		source.sendFeedback(Component.literal("Measurement started. Craft some items, then use /craftplan measure stop (or /craftplan time off)."));
		return 1;
	}

	private static int measureStop(CommandContext<FabricClientCommandSource> context) {
		FabricClientCommandSource source = context.getSource();
		if (!EmpiricalXp.isMeasuring()) {
			source.sendError(Component.literal("Not currently measuring."));
			return 0;
		}
		int count = EmpiricalXp.measureCount();
		double total = EmpiricalXp.measureTotalXp();
		EmpiricalXp.stopMeasure();
		if (count > 0) {
			source.sendFeedback(Component.literal("Measured " + count + " crafts, average "
				+ (long) (total / count) + " XP. Saved to cache."));
		} else {
			source.sendFeedback(Component.literal("No crafts recorded during measurement."));
		}
		String result = EmpiricalXp.lastMeasureResult();
		if (result != null) {
			source.sendFeedback(Component.literal(result));
		}
		source.sendFeedback(Component.literal(estimateRemaining()));
		return 1;
	}

	private static int measureStatus(CommandContext<FabricClientCommandSource> context) {
		FabricClientCommandSource source = context.getSource();
		if (EmpiricalXp.isMeasuring()) {
			source.sendFeedback(Component.literal("Measuring: " + EmpiricalXp.measureCount()
				+ " crafts so far, total " + (long) EmpiricalXp.measureTotalXp() + " XP."));
		} else {
			String result = EmpiricalXp.lastMeasureResult();
			source.sendFeedback(Component.literal(result == null ? "Not measuring." : result));
			source.sendFeedback(Component.literal(estimateRemaining()));
		}
		return 1;
	}

	private static ItemStack heldItem(FabricClientCommandSource source) {
		if (source.getPlayer() == null) {
			return ItemStack.EMPTY;
		}
		return source.getPlayer().getMainHandItem();
	}

	private static String unquote(String value) {
		String text = value == null ? "" : value.trim();
		if (text.length() >= 2) {
			boolean doubleQuoted = text.startsWith("\"") && text.endsWith("\"");
			boolean singleQuoted = text.startsWith("'") && text.endsWith("'");
			if (doubleQuoted || singleQuoted) {
				text = text.substring(1, text.length() - 1).trim();
			}
		}
		return text;
	}

	private static String truncate(String value, int max) {
		if (value == null) {
			return "";
		}
		return value.length() <= max ? value : value.substring(0, max) + "...";
	}
}