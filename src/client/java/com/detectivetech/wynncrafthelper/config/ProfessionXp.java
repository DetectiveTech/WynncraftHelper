package com.detectivetech.wynncrafthelper.config;

/**
 * Profession XP requirements (XP needed to advance from a level to the next one).
 * Values from the official Wynncraft profession XP chart (levels 1-131).
 */
public final class ProfessionXp {
	public static final int MAX_LEVEL = 132;

	private static final long[] XP_TO_NEXT = new long[MAX_LEVEL + 1];

	static {
		long[] values = {
			30L, 35L, 42L, 48L, 56L, 64L, 74L, 84L, 96L, 109L,
			123L, 140L, 158L, 178L, 200L, 225L, 253L, 284L, 319L, 358L,
			401L, 449L, 502L, 562L, 629L, 703L, 786L, 878L, 981L, 1096L,
			1224L, 1367L, 1526L, 1704L, 1901L, 2122L, 2368L, 2643L, 2948L, 3289L,
			3670L, 4094L, 4567L, 5094L, 5682L, 6337L, 7068L, 7882L, 8791L, 9804L,
			10933L, 12193L, 13597L, 15162L, 16908L, 18855L, 21025L, 23445L, 26143L, 29151L,
			32506L, 36246L, 40416L, 45066L, 50250L, 56031L, 62477L, 69664L, 77677L, 86612L,
			96574L, 107682L, 120068L, 133877L, 149275L, 166444L, 185587L, 206932L, 230731L, 257267L,
			286855L, 319845L, 356629L, 397643L, 443374L, 494364L, 551218L, 614610L, 685292L, 764103L,
			851977L, 949956L, 1059203L, 1181014L, 1316832L, 1468270L, 1637123L, 1825394L, 2035317L, 2269380L,
			2530361L, 2821354L, 3145812L, 3507582L, 3910956L, 4360718L, 4862203L, 5421358L, 6044816L, 6739972L,
			7515071L, 8379306L, 9342928L, 10417367L, 11615366L, 12951135L, 14440517L, 16101179L, 17952817L, 20017392L,
			22319395L, 24886127L, 27748034L, 30939059L, 34497053L, 38464216L, 42887603L, 47819680L, 53318945L, 59450625L,
			66287449L
		};
		System.arraycopy(values, 0, XP_TO_NEXT, 1, values.length);
	}

	private ProfessionXp() {
	}

	/**
	 * XP required to go from {@code level} to {@code level + 1}.
	 */
	public static long xpToNext(int level) {
		if (level < 1 || level >= MAX_LEVEL) {
			return 0L;
		}
		return XP_TO_NEXT[level];
	}
}
