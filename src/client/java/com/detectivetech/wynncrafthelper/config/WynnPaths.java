package com.detectivetech.wynncrafthelper.config;

import net.fabricmc.loader.api.FabricLoader;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Central location for all WynncraftHelper data files.
 * Everything is stored under {@code config/WynnHelper/} so the mod's configs,
 * caches, and learned data stay in one separate folder instead of mixing with
 * other mods' files directly in {@code config/}.
 */
public final class WynnPaths {
	private static final Path CONFIG_DIR = FabricLoader.getInstance().getConfigDir();
	private static final Path MOD_DIR = CONFIG_DIR.resolve("WynnHelper");

	private WynnPaths() {
	}

	/**
	 * Returns the {@code config/WynnHelper} directory, creating it on first access.
	 */
	public static Path modDir() {
		try {
			Files.createDirectories(MOD_DIR);
		} catch (Exception ignored) {
			// best-effort; file operations will report their own errors
		}
		return MOD_DIR;
	}

	/** Resolves a file name under {@code config/WynnHelper/}. */
	public static Path resolve(String fileName) {
		return modDir().resolve(fileName);
	}
}
