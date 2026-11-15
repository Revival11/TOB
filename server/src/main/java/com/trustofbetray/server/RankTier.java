package com.trustofbetray.server;

public class RankTier {
    public final int tier;
    public final String name;
    public final String sub;
    public final int minRp;

    public RankTier(int tier, String name, String sub, int minRp) {
        this.tier = tier;
        this.name = name;
        this.sub = sub;
        this.minRp = minRp;
    }

    public static final RankTier[] TIERS = new RankTier[] {
        new RankTier(1, "ROOKIE",    "Bronze",       0),
        new RankTier(2, "NOMAD",     "Silver",       200),
        new RankTier(3, "VANGUARD",  "Steel Blue",   400),
        new RankTier(4, "ELITE",     "Gold",         600),
        new RankTier(5, "CHAMPION",  "Royal Gold",   800),
        new RankTier(6, "SOVEREIGN", "Royal Purple", 1000),
        new RankTier(7, "ASCENDANT", "Mystic Cyan",  1250),
        new RankTier(8, "CELESTIAL", "Celestial",    1500),
        new RankTier(9, "ETERNAL",   "Eternal Gold", 1800)
    };

    public static final String[] BOT_NAMES = new String[] {
        "Aldric", "Lyra", "Kaelen", "Vesper", "Thorne",
        "Sylvi", "Garrick", "Nyx", "Rowan", "Orin",
        "Draven", "Seraphina", "Varian", "Isolde", "Caelum"
    };

    public static RankTier getByRp(int rp) {
        int cleanRp = Math.max(0, rp);
        RankTier chosen = TIERS[0];
        for (RankTier t : TIERS) {
            if (cleanRp >= t.minRp) {
                chosen = t;
            }
        }
        return chosen;
    }
}
