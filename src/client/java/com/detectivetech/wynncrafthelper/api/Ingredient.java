package com.detectivetech.wynncrafthelper.api;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * A crafting ingredient as exposed by {@code /v3/item/database}.
 * Ingredients raise the XP of a craft; the amount depends on the tier (stars)
 * and on the ingredient level relative to the recipe level.
 */
public class Ingredient {
	/**
	 * Player-facing names for professions differ from the internal API ids.
	 * Accepting these aliases makes searches such as "Jewelcrafting Ingredients"
	 * and "Alchemy" work instead of silently returning no results.
	 */
	private static final Map<String, String> SKILL_ALIASES = Map.ofEntries(
		Map.entry("jewelcrafting", "jeweling"),
		Map.entry("jewelcraft", "jeweling"),
		Map.entry("jewelry", "jeweling"),
		Map.entry("jewellery", "jeweling"),
		Map.entry("jeweler", "jeweling"),
		Map.entry("jeweller", "jeweling"),
		Map.entry("alchemy", "alchemism"),
		Map.entry("alchemist", "alchemism"),
		Map.entry("armor", "armouring"),
		Map.entry("armorsmithing", "armouring"),
		Map.entry("armoursmithing", "armouring"),
		Map.entry("weaponsmith", "weaponsmithing"),
		Map.entry("woodwork", "woodworking"),
		Map.entry("tailor", "tailoring"),
		Map.entry("cook", "cooking"),
		Map.entry("scribe", "scribing")
	);

	public String name;
	/** API id, e.g. {@code Refined Dernic Oil}; material names in recipes use this form. */
	public String internalName;
	public String tier;
	public int level;
	public List<String> skills;

	public int tierIndex() {
		if (tier == null) {
			return 0;
		}
		switch (tier) {
			case "TIER_1":
				return 1;
			case "TIER_2":
				return 2;
			case "TIER_3":
				return 3;
			default:
				return 0;
		}
	}

	/** Maps user-facing profession names onto the ids used by the API/recipe data. */
	public static String canonicalSkill(String skill) {
		if (skill == null) {
			return "";
		}
		String value = skill.trim().toLowerCase(Locale.ROOT)
			.replace('-', ' ')
			.replace('_', ' ');
		value = value.replaceAll("\\s+", " ");
		String alias = SKILL_ALIASES.get(value);
		return alias == null ? value : alias;
	}

	public boolean usableFor(String skill) {
		String wanted = canonicalSkill(skill);
		if (skills == null || skills.isEmpty()) {
			return true;
		}
		for (String value : skills) {
			if (value != null && canonicalSkill(value).equals(wanted)) {
				return true;
			}
		}
		return false;
	}

	/** Text used by the search box: display name, internal name and profession ids. */
	public String searchText() {
		StringBuilder builder = new StringBuilder();
		if (name != null) {
			builder.append(name).append(' ');
		}
		if (internalName != null) {
			builder.append(internalName).append(' ');
		}
		if (skills != null) {
			for (String skill : skills) {
				if (skill != null) {
					builder.append(skill).append(' ');
				}
			}
		}
		return builder.toString().toLowerCase(Locale.ROOT);
	}
}
