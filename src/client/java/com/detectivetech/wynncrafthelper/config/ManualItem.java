package com.detectivetech.wynncrafthelper.config;

import com.detectivetech.wynncrafthelper.util.MaterialNames;

/**
 * A player-registered link between a recipe material and the item they actually hold.
 * Used when the automatic name match fails (custom names, renamed items, resource packs).
 */
public class ManualItem {
	/** Display name of the held item, with colour codes and the legacy prefix removed. */
	public String displayName;
	/** Registry id of the held item, kept for debugging / future disambiguation. */
	public String itemId;
	/** Component (NBT) snapshot of the held item, kept for debugging. */
	public String components;
	/** Raw items needed for one required unit. 1 = same item, 3 = three raw per refined. */
	public int rawPerUnit = 1;

	public boolean matches(String candidateName) {
		if (displayName == null || displayName.isBlank()) {
			return false;
		}
		return MaterialNames.matches(displayName, candidateName);
	}

	public int ratio() {
		return Math.max(1, rawPerUnit);
	}
}
