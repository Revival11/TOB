package com.trustofbetray.server;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Persistent Player Profile, Rank & Friend Storage System (`players_db.json`).
 * Generates unique server-side Player IDs (`TOB-XXXX-XXXX`) and stores RP, Wins, Kills, Matches, and Friends.
 */
public class PlayerStorage {

    public static class PlayerProfile {
        public String uuid;
        public String name;
        public int rp = 100;
        public int wins = 0;
        public int kills = 0;
        public int matches = 0;
        public final List<String> friends = new ArrayList<>();

        public Map<String, Object> toClientMap() {
            RankTier r = RankTier.getByRp(rp);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("uuid", uuid);
            m.put("name", name);
            m.put("rp", rp);
            m.put("rank_tier", r.tier);
            m.put("rank_name", r.name);
            m.put("rank_sub", r.sub);
            m.put("wins", wins);
            m.put("kills", kills);
            m.put("matches", matches);
            return m;
        }

        public Map<String, Object> toStorageMap() {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("uuid", uuid);
            m.put("name", name);
            m.put("rp", rp);
            m.put("wins", wins);
            m.put("kills", kills);
            m.put("matches", matches);
            m.put("friends", new ArrayList<>(friends));
            return m;
        }
    }

    private final File dbFile;
    private final Map<String, PlayerProfile> profiles = new ConcurrentHashMap<>();
    private final SecureRandom random = new SecureRandom();
    private static final String HEX = "0123456789ABCDEF";

    public PlayerStorage(File dbFile) {
        this.dbFile = dbFile;
        load();
    }

    @SuppressWarnings("unchecked")
    public synchronized void load() {
        profiles.clear();
        if (!dbFile.exists()) return;
        try {
            String raw = new String(Files.readAllBytes(dbFile.toPath()), StandardCharsets.UTF_8);
            Map<String, Object> root = MiniJson.parseObject(raw);
            if (root == null) return;
            for (Map.Entry<String, Object> entry : root.entrySet()) {
                if (!(entry.getValue() instanceof Map)) continue;
                Map<String, Object> m = (Map<String, Object>) entry.getValue();
                PlayerProfile p = new PlayerProfile();
                p.uuid = String.valueOf(m.getOrDefault("uuid", entry.getKey())).toUpperCase();
                p.name = String.valueOf(m.getOrDefault("name", "Ranger"));
                p.rp = toInt(m.get("rp"), 100);
                p.wins = toInt(m.get("wins"), 0);
                p.kills = toInt(m.get("kills"), 0);
                p.matches = toInt(m.get("matches"), 0);
                Object fObj = m.get("friends");
                if (fObj instanceof List) {
                    for (Object item : (List<Object>) fObj) {
                        String fu = String.valueOf(item).trim().toUpperCase();
                        if (!fu.isEmpty() && !p.friends.contains(fu)) {
                            p.friends.add(fu);
                        }
                    }
                }
                profiles.put(p.uuid, p);
            }
        } catch ( Exception e) {
            System.out.println("[STORAGE] Warning loading players_db.json: " + e.getMessage());
        }
    }

    public synchronized void save() {
        try {
            Map<String, Object> root = new LinkedHashMap<>();
            for (Map.Entry<String, PlayerProfile> e : profiles.entrySet()) {
                root.put(e.getKey(), e.getValue().toStorageMap());
            }
            String json = MiniJson.stringify(root);
            File tmp = new File(dbFile.getParentFile(), dbFile.getName() + ".tmp");
            Files.write(tmp.toPath(), json.getBytes(StandardCharsets.UTF_8));
            Files.move(tmp.toPath(), dbFile.toPath(), StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            System.out.println("[STORAGE] Error saving players_db.json: " + e.getMessage());
        }
    }

    public synchronized String generateUniqueUuid() {
        while (true) {
            StringBuilder sb = new StringBuilder("TOB-");
            for (int i = 0; i < 4; i++) sb.append(HEX.charAt(random.nextInt(16)));
            sb.append('-');
            for (int i = 0; i < 4; i++) sb.append(HEX.charAt(random.nextInt(16)));
            String candidate = sb.toString();
            if (!profiles.containsKey(candidate)) {
                return candidate;
            }
        }
    }

    public synchronized PlayerProfile getOrCreateProfile(String reqUuid, String reqName) {
        String cleanName = (reqName == null ? "" : reqName.trim());
        if (cleanName.length() > 20) cleanName = cleanName.substring(0, 20);
        if (cleanName.isEmpty()) cleanName = "Ranger";

        String uuid = (reqUuid == null ? "" : reqUuid.trim().toUpperCase());
        PlayerProfile prof = profiles.get(uuid);
        if (prof == null) {
            if (!uuid.startsWith("TOB-") || uuid.length() < 10) {
                uuid = generateUniqueUuid();
            }
            prof = profiles.get(uuid);
            if (prof == null) {
                prof = new PlayerProfile();
                prof.uuid = uuid;
                prof.name = cleanName;
                prof.rp = 100;
                profiles.put(uuid, prof);
                save();
            }
        }
        if (!cleanName.equals(prof.name)) {
            prof.name = cleanName;
            save();
        }
        return prof;
    }

    public PlayerProfile getByUuid(String uuid) {
        if (uuid == null) return null;
        return profiles.get(uuid.trim().toUpperCase());
    }

    public Collection<PlayerProfile> getAllProfiles() {
        return profiles.values();
    }

    public synchronized PlayerProfile findByUuidOrName(String query, String excludeUuid) {
        if (query == null) return null;
        String q = query.trim().toLowerCase();
        if (q.isEmpty()) return null;
        for (PlayerProfile p : profiles.values()) {
            if (p.uuid.equalsIgnoreCase(excludeUuid)) continue;
            if (p.uuid.toLowerCase().equals(q) || p.name.toLowerCase().equals(q)) {
                return p;
            }
        }
        return null;
    }

    public synchronized void linkMutualFriends(PlayerProfile a, PlayerProfile b) {
        if (a == null || b == null) return;
        if (!a.friends.contains(b.uuid)) a.friends.add(b.uuid);
        if (!b.friends.contains(a.uuid)) b.friends.add(a.uuid);
        save();
    }

    private static int toInt(Object o, int fallback) {
        if (o instanceof Number) return ((Number) o).intValue();
        if (o != null) {
            try { return Integer.parseInt(String.valueOf(o)); } catch (NumberFormatException ignored) {}
        }
        return fallback;
    }
}
