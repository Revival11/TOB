package com.trustofbetray.server;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

public class ServerConfig {
    public String host = "0.0.0.0";
    public int port = 12123;
    public String publicAddress = "";
    public int maxPlayers = 15;
    public int maxPartySize = 5;
    public int botFillTimeoutSeconds = 300;
    public boolean fillBotsOnStart = true;
    public boolean printClientIp = true;

    private static final String DEFAULT_YML =
        "# TrustOfBetray Dedicated Online Server Configuration\n" +
        "# Compatible with Pterodactyl Panel & Local Hosting (default 0.0.0.0:12123)\n\n" +
        "server:\n" +
        "  host: \"0.0.0.0\"\n" +
        "  port: 12123\n" +
        "  public_address: \"\"\n\n" +
        "match:\n" +
        "  max_players: 15\n" +
        "  max_party_size: 5\n" +
        "  bot_fill_timeout_seconds: 300\n" +
        "  fill_bots_on_start: true\n\n" +
        "logging:\n" +
        "  print_client_ip: true\n";

    public static ServerConfig load(File configFile) {
        ServerConfig cfg = new ServerConfig();
        try {
            if (!configFile.exists()) {
                Files.write(configFile.toPath(), DEFAULT_YML.getBytes(StandardCharsets.UTF_8));
                System.out.println("[CONFIG] Created default server.yml");
            }
            List<String> lines = Files.readAllLines(configFile.toPath(), StandardCharsets.UTF_8);
            for (String rawLine : lines) {
                String clean = rawLine.split("#")[0].trim();
                if (clean.isEmpty() || !clean.contains(":")) continue;
                int idx = clean.indexOf(':');
                String key = clean.substring(0, idx).trim();
                String val = clean.substring(idx + 1).trim().replaceAll("^[\"']|[\"']$", "");
                switch (key) {
                    case "host":
                        if (!val.isEmpty()) cfg.host = val;
                        break;
                    case "port":
                        cfg.port = parseIntSafe(val, cfg.port);
                        break;
                    case "public_address":
                        cfg.publicAddress = val;
                        break;
                    case "max_players":
                        cfg.maxPlayers = Math.max(2, parseIntSafe(val, cfg.maxPlayers));
                        break;
                    case "max_party_size":
                        cfg.maxPartySize = Math.max(1, parseIntSafe(val, cfg.maxPartySize));
                        break;
                    case "bot_fill_timeout_seconds":
                    case "matchmaking_timeout_seconds":
                        cfg.botFillTimeoutSeconds = Math.max(10, parseIntSafe(val, cfg.botFillTimeoutSeconds));
                        break;
                    case "fill_bots_on_start":
                        cfg.fillBotsOnStart = Boolean.parseBoolean(val);
                        break;
                    case "print_client_ip":
                        cfg.printClientIp = Boolean.parseBoolean(val);
                        break;
                    default:
                        break;
                }
            }
        } catch (IOException e) {
            System.out.println("[CONFIG] Warning reading server.yml: " + e.getMessage());
        }

        String envPort = System.getenv("SERVER_PORT");
        if (envPort == null || envPort.trim().isEmpty()) {
            envPort = System.getenv("PORT");
        }
        if (envPort != null && !envPort.trim().isEmpty()) {
            int p = parseIntSafe(envPort.trim(), -1);
            if (p > 0) cfg.port = p;
        }
        return cfg;
    }

    private static int parseIntSafe(String s, int fallback) {
        try {
            return Integer.parseInt(s);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
