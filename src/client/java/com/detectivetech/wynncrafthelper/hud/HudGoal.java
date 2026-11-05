package com.detectivetech.wynncrafthelper.hud;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A crafting goal pinned to the on-screen HUD. It stores the recipe and the plan shape at
 * pin time; the remaining crafts/materials are recalculated live from the player's current
 * profession level and the active world bonuses.
 */
public class HudGoal {
	/** Profession id used by the planner, e.g. {@code cooking}. */
	public String skill;
	/** Recipe id, used to tell whether this exact recipe is already pinned. */
	public String recipeName;
	/** Recipe type, e.g. {@code Bow}. */
	public String recipeType;
	/** Minimum level of the recipe. */
	public int recipeLevel;
	/** Level the player wants to reach. */
	public int targetLevel;
	/** Base recipe XP per craft, before world/ingredient multipliers. */
	public int baseXp;
	/** Ingredient XP multiplier captured from the planner at pin time. */
	public double ingredientMultiplier = 1.0;
	/** Raw base materials consumed per craft, before the Speed Bomb. */
	public Map<String, Integer> perCraftMaterials = new LinkedHashMap<>();
	/** Ingredient name to slots consumed per craft. */
	public Map<String, Integer> perCraftIngredients = new LinkedHashMap<>();
}
