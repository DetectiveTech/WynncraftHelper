package com.detectivetech.wynncrafthelper.api;

import java.util.Map;

public class CharacterData {
	public String username;
	public String characterUuid;
	public String characterType;
	public int level;
	public long xp;
	public int xpPercent;
	public int totalLevel;
	public Map<String, ProfessionInfo> professions;

	public static class ProfessionInfo {
		public int level;
		public int xpPercent;
	}
}
