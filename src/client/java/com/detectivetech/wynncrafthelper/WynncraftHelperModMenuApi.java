package com.detectivetech.wynncrafthelper;

import com.detectivetech.wynncrafthelper.screen.WynncraftHelperScreen;
import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;

public class WynncraftHelperModMenuApi implements ModMenuApi {
	@Override
	public ConfigScreenFactory<?> getModConfigScreenFactory() {
		return new ConfigScreenFactory<WynncraftHelperScreen>() {
			@Override
			public WynncraftHelperScreen create(Screen screen) {
				return new WynncraftHelperScreen(Minecraft.getInstance());
			}
		};
	}
}
