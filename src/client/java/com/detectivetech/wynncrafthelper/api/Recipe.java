package com.detectivetech.wynncrafthelper.api;

import java.util.ArrayList;
import java.util.List;

public class Recipe {
	public String internalName;
	public String type;
	public String skill;
	public IntegerRange level;
	public List<RecipeMaterial> materials = new ArrayList<>();
	public int xp;
}
