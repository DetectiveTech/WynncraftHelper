package com.detectivetech.wynncrafthelper.util;

import java.util.Locale;

/**
 * Shared material-name handling. Wynncraft's API still returns the legacy internal name
 * {@code "Refined Dernic Meat"} while the item in game (and its display name) is simply
 * {@code "Dernic Meat"}, so every display and every inventory comparison goes through this
 * class. Refining was removed from Wynncraft in 1.19; the modern ratio is therefore 1:1.
 */
public final class MaterialNames {
	private MaterialNames() {
	}

	/** Removes Minecraft formatting codes ({@code §a}, {@code §x§F§F...}) from text. */
	public static String stripFormatting(String text) {
		if (text == null) {
			return "";
		}
		return text.replaceAll("\u00a7.", "").replaceAll("&[0-9a-fk-orA-FK-OR]", "");
	}

	/** User-facing name: drops the legacy {@code "Refined "} prefix and formatting codes. */
	public static String canonical(String name) {
		String cleaned = stripFormatting(name).replaceAll("\\s+", " ").trim();
		if (cleaned.regionMatches(true, 0, "refined ", 0, "refined ".length())) {
			cleaned = cleaned.substring("refined ".length()).trim();
		}
		return cleaned;
	}

	/**
	 * Lowercase, colour-code-free, special-character-free form used for matching. Both
	 * {@code "Refined Dernic Meat"} and {@code "Dernic Meat"} normalize to
	 * {@code "dernic meat"}.
	 */
	public static String normalize(String name) {
		return canonical(name).toLowerCase(Locale.ROOT)
			.replaceAll("[^a-z0-9]+", " ")
			.trim();
	}

	/**
	 * Case-insensitive substring match in both directions, so a required name is found
	 * even when the inventory item has extra text (tier stars, custom suffix, ...).
	 */
	public static boolean matches(String required, String candidate) {
		String left = normalize(required);
		String right = normalize(candidate);
		if (left.isEmpty() || right.isEmpty()) {
			return false;
		}
		return left.equals(right) || right.contains(left) || left.contains(right);
	}
}
