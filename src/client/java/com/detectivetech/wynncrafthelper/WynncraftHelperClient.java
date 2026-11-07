package com.detectivetech.wynncrafthelper;

import com.detectivetech.wynncrafthelper.api.LiveProgress;
import com.detectivetech.wynncrafthelper.api.WynncraftEvents;
import com.detectivetech.wynncrafthelper.compat.WynntilsBridge;
import com.detectivetech.wynncrafthelper.command.CraftPlanCommands;
import com.detectivetech.wynncrafthelper.config.ProfessionConfig;
import com.detectivetech.wynncrafthelper.hud.HudState;
import com.detectivetech.wynncrafthelper.screen.WynncraftHelperScreen;
import com.detectivetech.wynncrafthelper.xp.EmpiricalXp;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientWorldEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientWorldEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.ScreenMouseEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class WynncraftHelperClient implements ClientModInitializer {
	public static final KeyMapping OPEN_KEYBIND = KeyBindingHelper.registerKeyBinding(new KeyMapping(
		"key.wynncrafthelper.open",
		GLFW.GLFW_KEY_H,
		KeyMapping.Category.MISC
	));
	public static final KeyMapping TOGGLE_HUD_KEYBIND = KeyBindingHelper.registerKeyBinding(new KeyMapping(
		"key.wynncrafthelper.togglehud",
		GLFW.GLFW_KEY_X,
		KeyMapping.Category.MISC
	));
	public static final KeyMapping TOGGLE_MEASURE_KEYBIND = KeyBindingHelper.registerKeyBinding(new KeyMapping(
		"key.wynncrafthelper.togglemeasure",
		GLFW.GLFW_KEY_M,
		KeyMapping.Category.MISC
	));

	private static final Logger LOGGER = LoggerFactory.getLogger("WynncraftHelper");
	private boolean wasKeyDown = false;
	private int bombPollTicks;
	public static WynncraftHelperScreen screen;
	private static LiveProgress liveListener;

	@Override
	public void onInitializeClient() {
		CraftPlanCommands.register();
		// The crafting HUD is drawn by the vanilla HUD pass while no screen is open, and again
		// above any open screen so the drag border stays visible over chat and containers.
		HudElementRegistry.addLast(Identifier.fromNamespaceAndPath("wynncrafthelper", "craft_hud"),
			(graphics, deltaTracker) -> HudState.INSTANCE.renderHud(graphics));
		ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
			ScreenEvents.afterRender(screen).register((current, graphics, mouseX, mouseY, tickDelta) ->
				HudState.INSTANCE.renderOnScreen(graphics, mouseX, mouseY));
			ScreenMouseEvents.allowMouseClick(screen).register((current, event) ->
				!HudState.INSTANCE.mouseClicked(event));
			ScreenMouseEvents.allowMouseDrag(screen).register((current, event, dragX, dragY) ->
				!HudState.INSTANCE.mouseDragged(event));
			ScreenMouseEvents.allowMouseRelease(screen).register((current, event) ->
				!HudState.INSTANCE.mouseReleased(event));
		});

		liveListener = new LiveProgress() {
			@Override
			public void onProgress(String skill, int level, int progressPercent, int targetLevel) {
				if (screen != null) {
					screen.onLiveProgress(skill, level, progressPercent, targetLevel);
				}
			}

			@Override
			public void onWorldBonus(String skill, boolean doubleXpWeekend, boolean xpBomb, boolean professionSpeed,
				boolean doubleXpEvent) {
				if (screen != null) {
					screen.onWorldBonus(skill, doubleXpWeekend, xpBomb, professionSpeed, doubleXpEvent);
				} else {
					ProfessionConfig.setDoubleXpWeekend(doubleXpWeekend);
					ProfessionConfig.setXpBomb(xpBomb);
				}
			}

			@Override
			public void onCraftXp(String skill, long gainedXp) {
				if (screen != null) {
					screen.onCraftXp(skill, gainedXp);
				}
			}
		};
		LiveProgress.Bus.register(liveListener);

		// Fetch the global event list once at startup so the first planner open already
		// knows about a running Double XP event.
		WynncraftEvents.refreshAsync();

		java.util.function.Consumer<Object> xpGainHandler = event -> {
			EmpiricalXp.onXpGain(Minecraft.getInstance());
			syncFromWynntilsXpGain();
		};
		WynntilsBridge.registerProfessionXpGain(xpGainHandler);

		// Refresh the open planner whenever the client changes world (e.g. entering an XP boss
		// arena or loading a new dimension) so craft estimates pick up the new world state.
		ClientWorldEvents.AFTER_CLIENT_WORLD_CHANGE.register((client, world) -> {
			syncBombBonus();
			HudState.INSTANCE.resetPouch();
			EmpiricalXp.stopMeasure();
			if (WynncraftHelperClient.screen != null) {
				WynncraftHelperClient.screen.refreshForWorldChange();
			}
		});

		ClientTickEvents.END_CLIENT_TICK.register(client -> {
			EmpiricalXp.tick(client);
			while (TOGGLE_HUD_KEYBIND.consumeClick()) {
				HudState.INSTANCE.toggleVisible();
			}
			while (TOGGLE_MEASURE_KEYBIND.consumeClick()) {
				if (EmpiricalXp.isMeasuring()) {
					EmpiricalXp.stopMeasure();
					client.player.displayClientMessage(Component.literal("WynncraftHelper: measurement stopped."), false);
				} else if (HudState.INSTANCE.goal() != null) {
					EmpiricalXp.startMeasure();
					client.player.displayClientMessage(Component.literal("WynncraftHelper: measurement started."), false);
				} else {
					client.player.displayClientMessage(Component.literal("WynncraftHelper: pin a recipe first."), false);
				}
			}
			boolean down = OPEN_KEYBIND.isDown();
			if (down && !wasKeyDown) {
				LOGGER.info("WynncraftHelper: Open key (H) pressed, opening screen");
				screen = new WynncraftHelperScreen(client);
				client.setScreen(screen);
				syncBombBonus();
			}
			wasKeyDown = down;
			if (++bombPollTicks >= 20) {
				bombPollTicks = 0;
				syncBombBonus();
			}
		});

		ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
			LiveProgress.Bus.unregister(liveListener);
			liveListener = null;
			screen = null;
			WynntilsBridge.unregisterProfessionXpGain();
		});
	}

	private boolean wasXpBomb;
	private boolean wasProfessionSpeed;
	private boolean wasDoubleXpEvent;

	private void syncBombBonus() {
		// Keep the cached event state fresh; this only starts a request once the cache expires.
		WynncraftEvents.refreshAsync();
		Boolean active = WynntilsBridge.professionXpBombActive();
		Boolean speed = WynntilsBridge.professionSpeedBombActive();
		boolean xpBombNow = active != null ? active : wasXpBomb;
		boolean professionSpeedNow = speed != null ? speed : wasProfessionSpeed;
		boolean doubleXpEventNow = WynncraftEvents.doubleXpActive();
		if (xpBombNow == wasXpBomb && professionSpeedNow == wasProfessionSpeed
			&& doubleXpEventNow == wasDoubleXpEvent) {
			return;
		}
		wasXpBomb = xpBombNow;
		wasProfessionSpeed = professionSpeedNow;
		wasDoubleXpEvent = doubleXpEventNow;
		LOGGER.info("WynncraftHelper: world bonus xpBomb={} speed={} doubleXpEvent={}",
			xpBombNow, professionSpeedNow, doubleXpEventNow);
		// Notify the player of bomb state changes
		StringBuilder msg = new StringBuilder("WynncraftHelper: ");
		if (xpBombNow != wasXpBomb) {
			msg.append("XP Bomb ").append(xpBombNow ? "ACTIVE" : "inactive").append("; ");
		}
		if (professionSpeedNow != wasProfessionSpeed) {
			msg.append("Speed Bomb ").append(professionSpeedNow ? "ACTIVE" : "inactive").append("; ");
		}
		if (doubleXpEventNow != wasDoubleXpEvent) {
			msg.append("Double XP Event ").append(doubleXpEventNow ? "ACTIVE" : "inactive").append("; ");
		}
		if (msg.length() > "WynncraftHelper: ".length() + 2) {
			Minecraft mc = Minecraft.getInstance();
			if (mc != null && mc.player != null) {
				mc.player.displayClientMessage(Component.literal(msg.toString()), false);
			}
		}
		LiveProgress.Bus.emitWorldBonus(null, ProfessionConfig.doubleXpWeekend(), xpBombNow,
			professionSpeedNow, doubleXpEventNow);
	}

	private void syncFromWynntilsXpGain() {
		String skill = ProfessionConfig.selected();
		int level = WynntilsBridge.professionLevel(skill);
		double progress = WynntilsBridge.professionProgressPercent(skill);
		double gained = WynntilsBridge.professionXpGain();
		if (level <= 0 && (progress < 0) && gained < 0) {
			return;
		}
		LOGGER.info("WynncraftHelper: Wynntils profession state {} progress {} gained {}", level, progress, gained);
		if (screen != null) {
			screen.setLiveCraftXp((long) Math.max(0, gained));
			screen.applyWynntilsProgress(skill, level, (int) Math.round(progress));
		} else {
			ProfessionConfig.setLevel(skill, level);
			ProfessionConfig.setProgress(skill, (int) Math.max(0, Math.min((int) Math.round(progress), 99)));
		}
		HudState.INSTANCE.syncConfigFromWynntils();
	}
}