package com.detectivetech.wynncrafthelper.compat;

import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Optional;
import java.util.Set;

/**
 * Read-only bridge into Wynntils. Wynntils is a runtime-only companion mod, so it is not a
 * build dependency; the handful of fields/methods we need are looked up reflectively and
 * cached once. Nothing here changes Wynntils state or sends anything.
 */
public final class WynntilsBridge {
	private static final Logger LOGGER = LoggerFactory.getLogger("WynncraftHelper");

	private static final int UNRESOLVED = 0;
	private static final int READY = 1;
	private static final int UNAVAILABLE = 2;

	private static int state = UNRESOLVED;
	private static boolean primed;
	private static Object bombModel;
	private static Object professionXpBomb;
	private static Object professionSpeedBomb;
	private static Method isBombActive;
	private static Method getBombBells;
	private static Method bombTypeOf;
	private static Method bombIsActive;
	private static Method bombServer;
	private static Method getCurrentWorldName;
	private static Field infoBarField;
	private static Object infoBar;
	private static Method infoBarIsActive;
	private static Method infoBarGetEvent;
	private static Method bossBarName;
	private static Object professionModel;
	private static Method professionLevelOf;
	private static Method professionXpOf;
	private static boolean listenersRegistered;
	private static java.util.function.Consumer<Object> professionXpGainHandler;
	private static Object professionXpGainEvent;
	private static Method parseProfessionTier;
	private static boolean tierParserResolved;
	private static Object itemModel;
	private static Method getWynnItem;

	private WynntilsBridge() {
	}

	public static Boolean professionXpBombActive() {
		return bombActiveFor(professionXpBomb);
	}

	public static Boolean professionSpeedBombActive() {
		return bombActiveFor(professionSpeedBomb);
	}

	private static Boolean bombActiveFor(Object bomb) {
		if (!resolve()) {
			return null;
		}

		boolean readable = false;
		try {
			Object active = isBombActive.invoke(bombModel, bomb);
			readable = true;
			if (Boolean.TRUE.equals(active)) {
				primed = true;
				return Boolean.TRUE;
			}
		} catch (ReflectiveOperationException | RuntimeException error) {
			LOGGER.debug("WynncraftHelper: Wynntils isBombActive failed", error);
		}

		// The InfoBar lists every bomb on the current world, even when Wynntils' parsed
		// bomb map only kept one of them (happens with dual XP + Speed bombs).
		try {
			if (bombActiveInInfoBar(bomb)) {
				primed = true;
				return Boolean.TRUE;
			}
			readable = true;
		} catch (ReflectiveOperationException | RuntimeException error) {
			LOGGER.debug("WynncraftHelper: Wynntils info bar read failed", error);
		}

		try {
			if (bombActiveInCurrentWorld(bomb)) {
				primed = true;
				return Boolean.TRUE;
			}
			readable = true;
		} catch (ReflectiveOperationException | RuntimeException error) {
			LOGGER.debug("WynncraftHelper: Wynntils bomb bell read failed", error);
		}

		// Never cache "inactive" before the first complete read: another bomb may still be
		// primed, and the caller checks both bombs on the same tick.
		if (readable && !primed) {
			primed = true;
			return null;
		}
		primed = true;
		return readable ? Boolean.FALSE : null;
	}

	/**
	 * Reads the Wynntils info boss bar text ("Profession XP from …", "Profession Speed from …").
	 * This is the same data Wynntils uses to populate its bomb map, but reading the rendered
	 * text catches the case where a combined "XP / Speed" bar line was not parsed into a type.
	 */
	private static boolean bombActiveInInfoBar(Object bomb) throws ReflectiveOperationException {
		if (infoBarField == null) {
			return false;
		}
		if (infoBar == null) {
			infoBar = infoBarField.get(null);
		}
		if (infoBar == null) {
			return false;
		}
		if (infoBarIsActive == null) {
			infoBarIsActive = optionalMethod(infoBar.getClass(), "isActive");
		}
		if (infoBarIsActive == null || !Boolean.TRUE.equals(infoBarIsActive.invoke(infoBar))) {
			return false;
		}
		if (infoBarGetEvent == null) {
			infoBarGetEvent = optionalMethod(infoBar.getClass(), "getEvent");
		}
		if (infoBarGetEvent == null) {
			return false;
		}
		Object event = infoBarGetEvent.invoke(infoBar);
		if (event == null) {
			return false;
		}
		if (bossBarName == null) {
			bossBarName = nameMethod(event.getClass());
		}
		if (bossBarName == null) {
			return false;
		}
		Object name = bossBarName.invoke(event);
		String text = componentText(name);
		if (text.isBlank()) {
			return false;
		}
		return mentionsBomb(text, bomb);
	}

	private static boolean mentionsBomb(String barText, Object bomb) {
		String text = barText.toLowerCase(java.util.Locale.ROOT);
		if (bomb == professionSpeedBomb) {
			return text.contains("profession speed") || text.contains("speed bomb");
		}
		if (bomb == professionXpBomb) {
			return text.contains("profession xp")
				|| text.contains("profession experience")
				|| text.contains("xp bomb");
		}
		return false;
	}

	/** Locates BossEvent#getName across both dev (Mojang) and runtime (intermediary) names. */
	private static Method nameMethod(Class<?> eventClass) {
		for (Method method : eventClass.getMethods()) {
			if (method.getParameterCount() != 0) {
				continue;
			}
			String returnType = method.getReturnType().getName();
			if (returnType.endsWith("Component") || returnType.equals("net.minecraft.class_2561")) {
				return method;
			}
		}
		return null;
	}

	/** Component#getString is renamed at runtime, so resolve it from the Component interface. */
	private static String componentText(Object component) {
		if (component == null) {
			return "";
		}
		Method getString = componentStringMethod(component.getClass());
		if (getString != null) {
			try {
				Object value = getString.invoke(component);
				if (value instanceof String string) {
					return string;
				}
			} catch (ReflectiveOperationException | RuntimeException ignored) {
				// fall through
			}
		}
		for (Method method : component.getClass().getMethods()) {
			if (method.getParameterCount() != 0 || method.getReturnType() != String.class
				|| method.getName().equals("toString")) {
				continue;
			}
			try {
				Object value = method.invoke(component);
				if (value instanceof String string && !string.isBlank()) {
					return string;
				}
			} catch (ReflectiveOperationException | RuntimeException ignored) {
				// try the next candidate
			}
		}
		return "";
	}

	private static Method componentStringMethod(Class<?> componentClass) {
		// Dev (Mojang mappings) name.
		try {
			return componentClass.getMethod("getString");
		} catch (NoSuchMethodException ignored) {
			// runtime (intermediary) name; look it up on the interface
		}
		for (String interfaceName : new String[] {"net.minecraft.class_2561", "net.minecraft.network.chat.Component"}) {
			try {
				Class<?> type = Class.forName(interfaceName);
				for (Method method : type.getMethods()) {
					if (method.getParameterCount() == 0 && method.getReturnType() == String.class
						&& !method.getName().equals("toString")) {
						return method;
					}
				}
			} catch (ClassNotFoundException | RuntimeException ignored) {
				// try the next name
			}
		}
		return null;
	}

	private static Field optionalField(Class<?> type, String name) {
		try {
			Field field = type.getDeclaredField(name);
			field.setAccessible(true);
			return field;
		} catch (NoSuchFieldException | RuntimeException ignored) {
			return null;
		}
	}

	private static boolean bombActiveInCurrentWorld(Object bomb) throws ReflectiveOperationException {
		if (getBombBells == null || getCurrentWorldName == null
			|| bombTypeOf == null || bombIsActive == null || bombServer == null) {
			return false;
		}
		Object worldState = worldStateModel();
		if (worldState == null) {
			return false;
		}
		Object worldName = getCurrentWorldName.invoke(worldState);
		if (!(worldName instanceof String world) || world.isBlank()) {
			return false;
		}
		Object bells = getBombBells.invoke(bombModel);
		if (!(bells instanceof Set<?> set)) {
			return false;
		}
		for (Object info : set) {
			if (info == null) {
				continue;
			}
			Object type = bombTypeOf.invoke(info);
			if (type != bomb) {
				continue;
			}
			if (!Boolean.TRUE.equals(bombIsActive.invoke(info))) {
				continue;
			}
			Object server = bombServer.invoke(info);
			if (server instanceof String name && sameWorld(world, name)) {
				return true;
			}
		}
		return false;
	}

	private static boolean sameWorld(String current, String bombServer) {
		return normalizeWorld(current).equals(normalizeWorld(bombServer));
	}

	private static String normalizeWorld(String world) {
		return world == null ? "" : world.replace(" ", "").trim().toUpperCase(java.util.Locale.ROOT);
	}

	private static Object worldStateModel() {
		try {
			Class<?> models = Class.forName("com.wynntils.core.components.Models");
			Field field = models.getField("WorldState");
			return field.get(null);
		} catch (ReflectiveOperationException | RuntimeException ignored) {
			return null;
		}
	}

	private static boolean resolve() {
		if (state == READY) {
			return true;
		}
		if (state == UNAVAILABLE) {
			return false;
		}

		try {
			Class<?> models = Class.forName("com.wynntils.core.components.Models");
			Object model = models.getField("Bomb").get(null);
			if (model == null) {
				return false;
			}

			Class<?> bombTypeClass = Class.forName("com.wynntils.models.worlds.type.BombType");
			professionXpBomb = enumConstant(bombTypeClass, "PROFESSION_XP");
			professionSpeedBomb = enumConstant(bombTypeClass, "PROFESSION_SPEED");
			isBombActive = model.getClass().getMethod("isBombActive", bombTypeClass);
			getBombBells = optionalMethod(model.getClass(), "getBombBells");
			// The InfoBar boss bar always reflects the bombs on the current world. Its text is
			// the most reliable source when Wynntils only recorded one of several bombs.
			infoBarField = optionalField(model.getClass(), "InfoBar");

			try {
				Class<?> infoClass = Class.forName("com.wynntils.models.worlds.type.BombInfo");
				bombTypeOf = optionalMethod(infoClass, "bomb");
				bombIsActive = optionalMethod(infoClass, "isActive");
				bombServer = optionalMethod(infoClass, "server");
			} catch (ClassNotFoundException ignored) {
				// info-bar check alone is enough
			}

			Object worldState = models.getField("WorldState").get(null);
			if (worldState != null) {
				getCurrentWorldName = optionalMethod(worldState.getClass(), "getCurrentWorldName");
			}

			bombModel = model;
			state = READY;
			LOGGER.info("WynncraftHelper: linked to Wynntils bomb state");
			resolveProfession();
			return true;
		} catch (ClassNotFoundException notInstalled) {
			state = UNAVAILABLE;
			LOGGER.info("WynncraftHelper: Wynntils not detected; world bonus stays on the saved value");
			return false;
		} catch (ReflectiveOperationException | RuntimeException error) {
			LOGGER.warn("WynncraftHelper: could not read the Wynntils bomb model", error);
			state = UNAVAILABLE;
			return false;
		}
	}

	private static Method optionalMethod(Class<?> type, String name) {
		try {
			return type.getMethod(name);
		} catch (NoSuchMethodException ignored) {
			return null;
		}
	}

	@SuppressWarnings({"unchecked", "rawtypes"})
	private static Object enumConstant(Class<?> enumType, String name) {
		return Enum.valueOf((Class) enumType, name);
	}

	private static void resolveProfession() {
		try {
			Object model = professionModel();
			if (model == null) {
				return;
			}
			professionModel = model;
			professionLevelOf = model.getClass().getMethod(
				"getLevel", Class.forName("com.wynntils.models.profession.type.ProfessionType"));
			professionXpOf = model.getClass().getMethod(
				"getXP", Class.forName("com.wynntils.models.profession.type.ProfessionType"));
			LOGGER.info("WynncraftHelper: linked to Wynntils profession state");
		} catch (ReflectiveOperationException | RuntimeException error) {
			LOGGER.warn("WynncraftHelper: could not read the Wynntils profession model", error);
		}
	}

	public static Object professionModel() {
		if (professionModel != null) {
			return professionModel;
		}
		try {
			Class<?> models = Class.forName("com.wynntils.core.components.Models");
			professionModel = models.getField("Profession").get(null);
		} catch (ReflectiveOperationException | RuntimeException ignored) {
			return null;
		}
		return professionModel;
	}

	public static int professionLevel(String skill) {
		try {
			Object type = enumConstant(Class.forName("com.wynntils.models.profession.type.ProfessionType"), skillToProfessionTypeName(skill));
			if (professionModel == null) {
				return 0;
			}
			return (Integer) professionLevelOf.invoke(professionModel, type);
		} catch (ReflectiveOperationException | RuntimeException error) {
			return 0;
		}
	}

	public static double professionProgressPercent(String skill) {
		try {
			Object type = enumConstant(Class.forName("com.wynntils.models.profession.type.ProfessionType"), skillToProfessionTypeName(skill));
			if (professionModel == null) {
				return -1.0;
			}
			Object capped = professionXpOf.invoke(professionModel, type);
			if (capped == null) {
				return -1.0;
			}
			Method cappedPct = capped.getClass().getMethod("getPercentage");
			double current = ((Number) capped.getClass().getMethod("current").invoke(capped)).doubleValue();
			double max = ((Number) capped.getClass().getMethod("max").invoke(capped)).doubleValue();
			return max > 0 ? (current / max) * 100.0 : 0.0;
		} catch (ReflectiveOperationException | RuntimeException error) {
			return -1.0;
		}
	}

	/**
	 * Reads the profession tier (1-3) that Wynncraft writes into the item's tooltip lore.
	 * Materials and ingredients share one display name across tiers, so the tooltip is the
	 * only reliable way to tell a T1 "Dernic Meat" from a T2/T3 one. Returns {@code 0} when
	 * the item has no tier or Wynntils is not installed.
	 */
	public static int itemTier(ItemStack stack) {
		if (stack == null || stack.isEmpty()) {
			return 0;
		}
		int parsed = parsedTier(stack);
		if (parsed >= 0) {
			return parsed;
		}
		return wynnItemTier(stack);
	}

	/** Uses Wynntils' own tooltip parser (the same one its item annotators use). */
	private static int parsedTier(ItemStack stack) {
		if (!tierParserResolved) {
			tierParserResolved = true;
			try {
				Class<?> parser = Class.forName("com.wynntils.models.wynnitem.parsing.WynnItemParser");
				for (Method method : parser.getMethods()) {
					if (method.getName().equals("parseProfessionTier") && method.getParameterCount() == 1) {
						parseProfessionTier = method;
						break;
					}
				}
			} catch (ClassNotFoundException | RuntimeException ignored) {
				// Wynntils not installed; the HUD keeps working without tier separation
			}
		}
		if (parseProfessionTier == null) {
			return -1;
		}
		try {
			Object value = parseProfessionTier.invoke(null, stack);
			return value instanceof Number number ? number.intValue() : -1;
		} catch (ReflectiveOperationException | RuntimeException error) {
			return -1;
		}
	}

	/** Falls back to the parsed {@code MaterialItem}/{@code IngredientItem} tier. */
	private static int wynnItemTier(ItemStack stack) {
		resolveItemModel();
		if (itemModel == null || getWynnItem == null) {
			return 0;
		}
		try {
			Object result = getWynnItem.invoke(itemModel, stack);
			Object item = result instanceof Optional<?> optional ? optional.orElse(null) : null;
			if (item == null) {
				return 0;
			}
			String type = item.getClass().getName();
			if (!type.endsWith(".MaterialItem") && !type.endsWith(".IngredientItem")) {
				return 0;
			}
			Object value = item.getClass().getMethod("getQualityTier").invoke(item);
			return value instanceof Number number ? number.intValue() : 0;
		} catch (ReflectiveOperationException | RuntimeException error) {
			return 0;
		}
	}

	private static void resolveItemModel() {
		if (itemModel != null) {
			return;
		}
		try {
			Class<?> models = Class.forName("com.wynntils.core.components.Models");
			Object model = models.getField("Item").get(null);
			if (model == null) {
				return;
			}
			for (Method method : model.getClass().getMethods()) {
				if (method.getName().equals("getWynnItem") && method.getParameterCount() == 1) {
					getWynnItem = method;
					itemModel = model;
					return;
				}
			}
		} catch (ReflectiveOperationException | RuntimeException ignored) {
			// Wynntils not installed or the model moved; tooltip parsing is the primary source
		}
	}

	public static double professionXpGain() {
		try {
			if (professionXpGainEvent == null) {
				return -1.0;
			}
			Object gained = professionXpGainEvent.getClass().getMethod("getGainedXpRaw").invoke(professionXpGainEvent);
			return ((Number) gained).doubleValue();
		} catch (ReflectiveOperationException | RuntimeException error) {
			return -1.0;
		}
	}

	public static boolean registerProfessionXpGain(java.util.function.Consumer<Object> handler) {
		if (handler != null) {
			professionXpGainHandler = handler;
		}
		if (listenersRegistered) {
			return true;
		}
		if (professionXpGainHandler == null) {
			return false;
		}
		try {
			Class<?> wynntilsMod = Class.forName("com.wynntils.core.WynntilsMod");
			Field field = wynntilsMod.getDeclaredField("eventBus");
			field.setAccessible(true);
			Object bus = field.get(null);
			if (bus == null) {
				return false; // Wynntils not initialized yet; caller should retry
			}
			Class<?> eventClass = Class.forName("com.wynntils.models.profession.event.ProfessionXpGainEvent");
			Class<?> consumerIface = Class.forName("java.util.function.Consumer");
			Object proxy = java.lang.reflect.Proxy.newProxyInstance(
				consumerIface.getClassLoader(),
				new Class<?>[] { consumerIface },
				(listenerProxy, method, args) -> {
					try {
						if (method.getName().equals("accept") && args != null && args.length > 0) {
							professionXpGainEvent = args[0];
							java.util.function.Consumer<Object> handlerRef = professionXpGainHandler;
							if (handlerRef != null) {
								handlerRef.accept(args[0]);
							}
						}
					} catch (Throwable ignored) {
						// never let a listener crash the client
					}
					return null;
				});
			Method add = bus.getClass().getMethod("addListener", Class.class, java.util.function.Consumer.class);
			add.invoke(bus, eventClass, proxy);
			listenersRegistered = true;
			LOGGER.info("WynncraftHelper: registered Wynntils profession XP gain listener");
			return true;
		} catch (ReflectiveOperationException | RuntimeException error) {
			LOGGER.debug("WynncraftHelper: could not register Wynntils XP gain listener yet", error);
			return false;
		}
	}

	public static boolean isProfessionXpGainRegistered() {
		return listenersRegistered;
	}

	/** Unregisters the profession XP gain listener. Call on mod shutdown to avoid leaks. */
	public static boolean unregisterProfessionXpGain() {
		if (!listenersRegistered) {
			return true;
		}
		try {
			Class<?> wynntilsMod = Class.forName("com.wynntils.core.WynntilsMod");
			Field field = wynntilsMod.getDeclaredField("eventBus");
			field.setAccessible(true);
			Object bus = field.get(null);
			if (bus == null) {
				return false;
			}
			Class<?> eventClass = Class.forName("com.wynntils.models.profession.event.ProfessionXpGainEvent");
			Method remove = bus.getClass().getMethod("removeListener", Class.class, java.util.function.Consumer.class);
			// We can't easily remove the proxy, but we can null the handler so it does nothing.
			professionXpGainHandler = null;
			listenersRegistered = false;
			LOGGER.info("WynncraftHelper: unregistered Wynntils profession XP gain listener");
			return true;
		} catch (ReflectiveOperationException | RuntimeException error) {
			LOGGER.debug("WynncraftHelper: could not unregister Wynntils XP gain listener", error);
			return false;
		}
	}

	private static final java.util.Map<String, String> PROFESSION_TYPE_NAMES = new java.util.LinkedHashMap<>();

	static {
		PROFESSION_TYPE_NAMES.put("armouring", "ARMOURING");
		PROFESSION_TYPE_NAMES.put("weaponsmithing", "WEAPONSMITHING");
		PROFESSION_TYPE_NAMES.put("tailoring", "TAILORING");
		PROFESSION_TYPE_NAMES.put("woodworking", "WOODWORKING");
		PROFESSION_TYPE_NAMES.put("jeweling", "JEWELING");
		PROFESSION_TYPE_NAMES.put("cooking", "COOKING");
		PROFESSION_TYPE_NAMES.put("scribing", "SCRIBING");
		PROFESSION_TYPE_NAMES.put("alchemism", "ALCHEMYM");
	}

	private static String skillToProfessionTypeName(String skill) {
		return PROFESSION_TYPE_NAMES.getOrDefault(skill, "JEWELING");
	}
}
