package com.trustofbetray.server;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * TrustOfBetray Dedicated Online Server (Java Edition - Zero External Dependencies)
 * - Builds into `target/server.jar` via Maven (`mvn package`)
 * - Supports Pterodactyl (`SERVER_PORT` / `PORT` env) and Local (`0.0.0.0:12123`)
 * - Server-Side Unique Player UUID & Persistent Storage (`players_db.json`)
 * - 9-Tier Rank System (Rookie -> Eternal) with Rank-Based Matchmaking & Estimated Wait (`est. MM.SS`)
 * - 5-Player Max Party Rooms + Friends Search/Add/Invite
 * - 15-Player Max In-Game Matches (Independent Multi-Room Execution + Auto Bot Fill at 5 Minutes / 300s)
 * - Console Commands: > force-start | > restart | > stop | > status
 */
public class TrustOfBetrayServer {

    public static class PartyRoom {
        public final int id;
        public volatile String leaderUuid;
        public final List<ClientSession> members = new CopyOnWriteArrayList<>();
        public volatile boolean inQueue = false;
        public volatile int elapsedSeconds = 0;
        public volatile int avgRp = 100;
        public volatile int avgTier = 1;

        public PartyRoom(int id, ClientSession leader) {
            this.id = id;
            this.leaderUuid = leader.uuid;
            this.members.add(leader);
            recomputeRank();
        }

        public void recomputeRank() {
            if (members.isEmpty()) return;
            int sum = 0;
            for (ClientSession m : members) {
                sum += (m.profile != null ? m.profile.rp : 100);
            }
            this.avgRp = Math.round((float) sum / members.size());
            this.avgTier = RankTier.getByRp(this.avgRp).tier;
        }
    }

    public static class MatchRoom {
        public final int id;
        public volatile String status = "in_progress";
        public final Map<Integer, ClientSession> clients = new ConcurrentHashMap<>();

        public MatchRoom(int id) {
            this.id = id;
        }
    }

    private final File baseDir;
    private volatile ServerConfig config;
    private volatile PlayerStorage storage;
    private volatile ServerSocket serverSocket;
    private volatile ScheduledExecutorService scheduler;
    private volatile boolean running = false;

    private final AtomicInteger nextClientId = new AtomicInteger(1);
    private final AtomicInteger nextPartyId = new AtomicInteger(1);
    private final AtomicInteger nextMatchId = new AtomicInteger(1);

    private final Map<Integer, ClientSession> clients = new ConcurrentHashMap<>();
    private final Map<String, ClientSession> clientsByUuid = new ConcurrentHashMap<>();
    private final Map<Integer, PartyRoom> parties = new ConcurrentHashMap<>();
    private final Map<Integer, MatchRoom> matchRooms = new ConcurrentHashMap<>();

    public TrustOfBetrayServer(File baseDir) {
        this.baseDir = baseDir;
    }

    public synchronized void startServer() {
        config = ServerConfig.load(new File(baseDir, "server.yml"));
        storage = new PlayerStorage(new File(baseDir, "players_db.json"));
        running = true;

        scheduler = Executors.newSingleThreadScheduledExecutor();
        scheduler.scheduleAtFixedRate(this::tickMatchmakingQueue, 1, 1, TimeUnit.SECONDS);

        Thread acceptThread = new Thread(this::acceptLoop, "TOB-AcceptThread");
        acceptThread.setDaemon(true);
        acceptThread.start();
    }

    private void acceptLoop() {
        try {
            serverSocket = new ServerSocket();
            serverSocket.setReuseAddress(true);
            serverSocket.bind(new InetSocketAddress(config.host, config.port));

            System.out.println("====================================================");
            System.out.println("  TrustOfBetray Online Java Server Started!");
            System.out.println("  Listening on : " + config.host + ":" + config.port);
            if (config.publicAddress != null && !config.publicAddress.isEmpty()) {
                System.out.println("  Public Addr  : " + config.publicAddress);
            }
            printLocalNetworkIps(config.port);
            System.out.println("  Match Size   : " + config.maxPlayers + " players/match (Party Max: " +
                config.maxPartySize + ", Bot Fill @ " + config.botFillTimeoutSeconds + "s)");
            System.out.println("  Rank Tiers   : 9 Tiers (Rookie -> Eternal) + Java UUID Storage");
            System.out.println("  Commands     : force-start | restart | stop | status");
            System.out.println("====================================================");
            System.out.print("> ");
            System.out.flush();

            while (running && !serverSocket.isClosed()) {
                Socket sock = serverSocket.accept();
                sock.setTcpNoDelay(true);
                String rawIp = sock.getInetAddress() != null ? sock.getInetAddress().getHostAddress() : "unknown";
                int cid = nextClientId.getAndIncrement();
                ClientSession session = new ClientSession(cid, sock, rawIp, this);
                Thread t = new Thread(session::runLoop, "TOB-Client-" + cid);
                t.setDaemon(true);
                t.start();
            }
        } catch (IOException e) {
            if (running) {
                System.out.println("[SERVER] Bind/Accept error: " + e.getMessage());
            }
        }
    }

    private void printLocalNetworkIps(int port) {
        try {
            Enumeration<NetworkInterface> nets = NetworkInterface.getNetworkInterfaces();
            while (nets.hasMoreElements()) {
                NetworkInterface net = nets.nextElement();
                if (!net.isUp() || net.isLoopback()) continue;
                Enumeration<InetAddress> addrs = net.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress addr = addrs.nextElement();
                    if (addr instanceof Inet4Address && !addr.isLoopbackAddress()) {
                        System.out.println("  Local/Net IP : " + addr.getHostAddress() + ":" + port);
                    }
                }
            }
        } catch (Exception ignored) {
        }
    }

    public String buildHttpStatusJson() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("game", "TrustOfBetray");
        m.put("engine", "Java");
        m.put("online_players", clients.size());
        m.put("active_parties", parties.size());
        m.put("active_matches", matchRooms.size());
        m.put("max_players", config.maxPlayers);
        m.put("max_party_size", config.maxPartySize);
        m.put("bot_fill_timeout_seconds", config.botFillTimeoutSeconds);
        return MiniJson.stringify(m);
    }

    public void onClientConnected(ClientSession client) {
        clients.put(client.id, client);
        if (config.printClientIp) {
            System.out.println("[CONNECT] Client IP: " + client.ip + " -> Assigned Session #" + client.id);
        }
    }

    public void onClientDisconnected(ClientSession client) {
        if (clients.remove(client.id) == null) return;
        if (!client.uuid.isEmpty()) {
            clientsByUuid.remove(client.uuid, client);
        }
        removeClientFromParty(client);
        if (client.matchId > 0) {
            MatchRoom mRoom = matchRooms.get(client.matchId);
            if (mRoom != null) {
                mRoom.clients.remove(client.id);
                if (mRoom.clients.isEmpty()) {
                    matchRooms.remove(mRoom.id);
                    System.out.println("[MATCH #" + mRoom.id + "] Closed (empty).");
                }
            }
        }
        if (config.printClientIp) {
            System.out.println("[DISCONNECT] " + client.name + " [" + (client.uuid.isEmpty() ? "-" : client.uuid) + "] (" + client.ip + ") left.");
        }
    }

    private List<Object> buildFriendsPayload(PlayerStorage.PlayerProfile prof) {
        List<Object> list = new ArrayList<>();
        if (prof == null) return list;
        for (String fUuid : prof.friends) {
            PlayerStorage.PlayerProfile fProf = storage.getByUuid(fUuid);
            if (fProf == null) continue;
            ClientSession onlineClient = clientsByUuid.get(fUuid);
            RankTier r = RankTier.getByRp(fProf.rp);
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("uuid", fProf.uuid);
            item.put("name", fProf.name);
            item.put("rp", fProf.rp);
            item.put("rank_tier", r.tier);
            item.put("rank_name", r.name);
            item.put("online", onlineClient != null);
            item.put("in_match", onlineClient != null && onlineClient.matchId > 0);
            list.add(item);
        }
        return list;
    }

    private PartyRoom createPartyForClient(ClientSession client) {
        PartyRoom party = new PartyRoom(nextPartyId.getAndIncrement(), client);
        parties.put(party.id, party);
        client.partyId = party.id;
        return party;
    }

    private void broadcastPartyState(PartyRoom party) {
        if (party == null) return;
        party.recomputeRank();
        List<Object> slots = new ArrayList<>();
        for (ClientSession m : party.members) {
            RankTier r = RankTier.getByRp(m.profile != null ? m.profile.rp : 100);
            Map<String, Object> slot = new LinkedHashMap<>();
            slot.put("id", m.id);
            slot.put("uuid", m.uuid);
            slot.put("name", m.name);
            slot.put("rp", m.profile != null ? m.profile.rp : 100);
            slot.put("rank_tier", r.tier);
            slot.put("rank_name", r.name);
            slot.put("rank_sub", r.sub);
            slot.put("is_leader", m.uuid.equals(party.leaderUuid));
            slots.add(slot);
        }
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("type", "party_update");
        msg.put("party_id", party.id);
        msg.put("leader_uuid", party.leaderUuid);
        msg.put("max_party_size", config.maxPartySize);
        msg.put("in_queue", party.inQueue);
        msg.put("members", slots);
        for (ClientSession m : party.members) {
            m.sendJson(msg);
        }
    }

    private void removeClientFromParty(ClientSession client) {
        if (client.partyId <= 0) return;
        PartyRoom party = parties.get(client.partyId);
        client.partyId = 0;
        if (party == null) return;
        party.members.remove(client);
        if (party.inQueue) {
            party.inQueue = false;
            party.elapsedSeconds = 0;
            Map<String, Object> cancelMsg = new LinkedHashMap<>();
            cancelMsg.put("type", "queue_cancelled");
            cancelMsg.put("reason", client.name + " left party");
            for (ClientSession m : party.members) {
                m.sendJson(cancelMsg);
            }
        }
        if (party.members.isEmpty()) {
            parties.remove(party.id);
        } else {
            if (client.uuid.equals(party.leaderUuid)) {
                party.leaderUuid = party.members.get(0).uuid;
            }
            broadcastPartyState(party);
        }
    }

    private int computeEstimatedWaitSeconds(PartyRoom party) {
        int similarOnline = 0;
        int queuedCount = 0;
        for (ClientSession c : clients.values()) {
            if (c.matchId > 0 || c.profile == null) continue;
            int t = RankTier.getByRp(c.profile.rp).tier;
            if (Math.abs(t - party.avgTier) <= 2) {
                similarOnline++;
                PartyRoom p = parties.get(c.partyId);
                if (p != null && p.inQueue) queuedCount++;
            }
        }
        int needed = Math.max(0, config.maxPlayers - queuedCount);
        if (needed == 0) return 3;
        if (similarOnline >= config.maxPlayers) return 12;
        if (similarOnline >= 6) return 25;
        if (similarOnline >= 2) return 45;
        return 75;
    }

    private synchronized void launchMatchFromParties(List<PartyRoom> selectedParties, String reason) {
        List<ClientSession> humanClients = new ArrayList<>();
        for (PartyRoom p : selectedParties) {
            p.inQueue = false;
            p.elapsedSeconds = 0;
            humanClients.addAll(p.members);
        }
        if (humanClients.isEmpty()) return;

        int matchId = nextMatchId.getAndIncrement();
        MatchRoom matchRoom = new MatchRoom(matchId);
        matchRooms.put(matchId, matchRoom);

        List<Object> finalRoster = new ArrayList<>();
        for (ClientSession c : humanClients) {
            c.matchId = matchId;
            matchRoom.clients.put(c.id, c);
            int rp = c.profile != null ? c.profile.rp : 100;
            RankTier r = RankTier.getByRp(rp);
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("id", c.id);
            entry.put("uuid", c.uuid);
            entry.put("name", c.name);
            entry.put("rank_tier", r.tier);
            entry.put("rank_name", r.name);
            entry.put("rp", rp);
            entry.put("party_id", c.partyId);
            entry.put("is_bot", false);
            entry.put("peer_id", c.id);
            finalRoster.add(entry);
        }

        int avgRp = selectedParties.get(0).avgRp;
        RankTier botRank = RankTier.getByRp(avgRp);
        if (config.fillBotsOnStart) {
            int needed = Math.max(0, config.maxPlayers - finalRoster.size());
            for (int i = 0; i < needed; i++) {
                Map<String, Object> bot = new LinkedHashMap<>();
                bot.put("id", 100 + i + 1);
                bot.put("uuid", "BOT-" + (100 + i + 1));
                bot.put("name", RankTier.BOT_NAMES[i % RankTier.BOT_NAMES.length]);
                bot.put("rank_tier", botRank.tier);
                bot.put("rank_name", botRank.name);
                bot.put("rp", avgRp);
                bot.put("party_id", 0);
                bot.put("is_bot", true);
                bot.put("peer_id", 0);
                finalRoster.add(bot);
            }
        }

        int botCount = finalRoster.size() - humanClients.size();
        System.out.println("[MATCH #" + matchId + "] Started (" + reason + ") -> " +
            humanClients.size() + " human(s) + " + botCount + " bot(s) = " +
            finalRoster.size() + " players! (Active matches: " + matchRooms.size() + ")");

        for (PartyRoom p : selectedParties) {
            broadcastPartyState(p);
        }
        Map<String, Object> startMsg = new LinkedHashMap<>();
        startMsg.put("type", "start_match");
        startMsg.put("room_id", matchId);
        startMsg.put("max_players", config.maxPlayers);
        startMsg.put("roster", finalRoster);
        for (ClientSession c : humanClients) {
            c.sendJson(startMsg);
        }
    }

    private void tickMatchmakingQueue() {
        try {
            List<PartyRoom> queuedParties = new ArrayList<>();
            for (PartyRoom p : parties.values()) {
                if (p.inQueue && !p.members.isEmpty()) {
                    p.recomputeRank();
                    p.elapsedSeconds += 1;
                    queuedParties.add(p);
                }
            }
            if (queuedParties.isEmpty()) return;

            queuedParties.sort(Comparator.comparingInt((PartyRoom p) -> p.elapsedSeconds).reversed());

            for (PartyRoom p : queuedParties) {
                int estSec = computeEstimatedWaitSeconds(p);
                int compatiblePlayers = 0;
                int tol = 1 + (p.elapsedSeconds / 20);
                for (PartyRoom other : queuedParties) {
                    if (Math.abs(other.avgTier - p.avgTier) <= tol) {
                        compatiblePlayers += other.members.size();
                    }
                }
                Map<String, Object> statusMsg = new LinkedHashMap<>();
                statusMsg.put("type", "queue_status");
                statusMsg.put("elapsed_seconds", p.elapsedSeconds);
                statusMsg.put("estimated_seconds", estSec);
                statusMsg.put("queued_players", compatiblePlayers);
                statusMsg.put("max_players", config.maxPlayers);
                statusMsg.put("bot_fill_timeout", config.botFillTimeoutSeconds);
                for (ClientSession m : p.members) {
                    m.sendJson(statusMsg);
                }
            }

            Set<Integer> usedPartyIds = new HashSet<>();
            for (PartyRoom anchor : queuedParties) {
                if (usedPartyIds.contains(anchor.id)) continue;
                int tol = 1 + (anchor.elapsedSeconds / 20);
                List<PartyRoom> group = new ArrayList<>();
                group.add(anchor);
                int count = anchor.members.size();

                for (PartyRoom cand : queuedParties) {
                    if (cand.id == anchor.id || usedPartyIds.contains(cand.id)) continue;
                    if (Math.abs(cand.avgTier - anchor.avgTier) <= tol) {
                        if (count + cand.members.size() <= config.maxPlayers) {
                            group.add(cand);
                            count += cand.members.size();
                            if (count == config.maxPlayers) break;
                        }
                    }
                }

                if (count >= config.maxPlayers) {
                    for (PartyRoom g : group) usedPartyIds.add(g.id);
                    launchMatchFromParties(group, "15/15 RANK MATCH FOUND");
                } else if (anchor.elapsedSeconds >= config.botFillTimeoutSeconds) {
                    for (PartyRoom g : group) usedPartyIds.add(g.id);
                    launchMatchFromParties(group, "5-MIN QUEUE TIMEOUT (" + config.botFillTimeoutSeconds + "s) - BOT FILL");
                }
            }
        } catch (Exception e) {
            System.out.println("[QUEUE] Error in matchmaking ticker: " + e.getMessage());
        }
    }

    public void handleClientMessage(ClientSession client, Map<String, Object> msg) {
        String type = String.valueOf(msg.getOrDefault("type", ""));

        switch (type) {
            case "join":
            case "auth": {
                String reqUuid = strVal(msg.get("uuid"));
                String reqName = strVal(msg.get("name"));
                PlayerStorage.PlayerProfile prof = storage.getOrCreateProfile(reqUuid, reqName);
                client.uuid = prof.uuid;
                client.name = prof.name;
                client.profile = prof;
                clientsByUuid.put(prof.uuid, client);

                if (client.partyId <= 0 || !parties.containsKey(client.partyId)) {
                    createPartyForClient(client);
                }
                PartyRoom party = parties.get(client.partyId);

                RankTier r = RankTier.getByRp(prof.rp);
                System.out.println("[AUTH] Player \"" + client.name + "\" [" + client.uuid + "] (" + r.name + " - " + prof.rp + " RP) from " + client.ip);

                Map<String, Object> welcome = new LinkedHashMap<>();
                welcome.put("type", "welcome");
                welcome.put("player_id", client.id);
                welcome.put("max_players", config.maxPlayers);
                welcome.put("max_party_size", config.maxPartySize);
                welcome.put("profile", prof.toClientMap());
                welcome.put("friends", buildFriendsPayload(prof));
                client.sendJson(welcome);
                broadcastPartyState(party);
                break;
            }
            case "update_name": {
                String newName = strVal(msg.get("name")).trim();
                if (newName.length() > 20) newName = newName.substring(0, 20);
                if (!newName.isEmpty() && client.profile != null) {
                    client.name = newName;
                    client.profile.name = newName;
                    storage.save();
                    Map<String, Object> resp = new LinkedHashMap<>();
                    resp.put("type", "profile_updated");
                    resp.put("profile", client.profile.toClientMap());
                    client.sendJson(resp);
                    PartyRoom party = parties.get(client.partyId);
                    if (party != null) broadcastPartyState(party);
                }
                break;
            }
            case "search_player": {
                String q = strVal(msg.get("query")).trim().toLowerCase();
                List<Object> results = new ArrayList<>();
                if (!q.isEmpty()) {
                    for (PlayerStorage.PlayerProfile p : storage.getAllProfiles()) {
                        if (p.uuid.equalsIgnoreCase(client.uuid)) continue;
                        if (p.uuid.toLowerCase().contains(q) || p.name.toLowerCase().contains(q)) {
                            RankTier r = RankTier.getByRp(p.rp);
                            Map<String, Object> item = new LinkedHashMap<>();
                            item.put("uuid", p.uuid);
                            item.put("name", p.name);
                            item.put("rp", p.rp);
                            item.put("rank_tier", r.tier);
                            item.put("rank_name", r.name);
                            item.put("online", clientsByUuid.containsKey(p.uuid));
                            results.add(item);
                            if (results.size() >= 12) break;
                        }
                    }
                }
                Map<String, Object> resp = new LinkedHashMap<>();
                resp.put("type", "search_results");
                resp.put("results", results);
                client.sendJson(resp);
                break;
            }
            case "add_friend": {
                if (client.profile == null) break;
                String q = strVal(msg.getOrDefault("target_uuid", msg.get("query")));
                PlayerStorage.PlayerProfile targetProf = storage.findByUuidOrName(q, client.uuid);
                if (targetProf == null) {
                    sendToast(client, "Player UUID/Name not found");
                    break;
                }
                storage.linkMutualFriends(client.profile, targetProf);

                Map<String, Object> fMsg = new LinkedHashMap<>();
                fMsg.put("type", "friends_updated");
                fMsg.put("friends", buildFriendsPayload(client.profile));
                fMsg.put("message", "Added " + targetProf.name + " (" + targetProf.uuid + ")");
                client.sendJson(fMsg);

                ClientSession targetClient = clientsByUuid.get(targetProf.uuid);
                if (targetClient != null) {
                    Map<String, Object> tfMsg = new LinkedHashMap<>();
                    tfMsg.put("type", "friends_updated");
                    tfMsg.put("friends", buildFriendsPayload(targetProf));
                    tfMsg.put("message", client.name + " added you as a friend!");
                    targetClient.sendJson(tfMsg);
                }
                break;
            }
            case "refresh_friends": {
                if (client.profile != null) {
                    Map<String, Object> fMsg = new LinkedHashMap<>();
                    fMsg.put("type", "friends_updated");
                    fMsg.put("friends", buildFriendsPayload(client.profile));
                    client.sendJson(fMsg);
                }
                break;
            }
            case "invite_friend": {
                String targetUuid = strVal(msg.get("target_uuid")).trim().toUpperCase();
                ClientSession targetClient = clientsByUuid.get(targetUuid);
                PartyRoom party = parties.get(client.partyId);
                if (party == null) break;
                if (party.members.size() >= config.maxPartySize) {
                    sendToast(client, "Party is full (5/5)");
                    break;
                }
                if (targetClient == null) {
                    sendToast(client, "Player is currently offline");
                    break;
                }
                RankTier r = RankTier.getByRp(client.profile != null ? client.profile.rp : 100);
                Map<String, Object> inv = new LinkedHashMap<>();
                inv.put("type", "party_invite");
                inv.put("party_id", party.id);
                inv.put("from_uuid", client.uuid);
                inv.put("from_name", client.name);
                inv.put("from_rank_tier", r.tier);
                inv.put("from_rank_name", r.name);
                targetClient.sendJson(inv);
                sendToast(client, "Invite sent to " + targetClient.name);
                break;
            }
            case "respond_invite": {
                int partyId = intVal(msg.get("party_id"), 0);
                boolean accept = Boolean.TRUE.equals(msg.get("accept"));
                if (!accept) break;
                PartyRoom targetParty = parties.get(partyId);
                if (targetParty == null || targetParty.members.size() >= config.maxPartySize) {
                    sendToast(client, "Party unavailable or full (5/5)");
                    break;
                }
                if (targetParty.inQueue) {
                    sendToast(client, "Party is already in matchmaking");
                    break;
                }
                removeClientFromParty(client);
                targetParty.members.add(client);
                client.partyId = targetParty.id;
                broadcastPartyState(targetParty);
                break;
            }
            case "leave_party": {
                removeClientFromParty(client);
                PartyRoom newParty = createPartyForClient(client);
                broadcastPartyState(newParty);
                break;
            }
            case "start_queue":
            case "request_start": {
                PartyRoom party = parties.get(client.partyId);
                if (party == null || party.inQueue) break;
                party.recomputeRank();
                party.inQueue = true;
                party.elapsedSeconds = 0;
                System.out.println("[QUEUE] Party #" + party.id + " (" + party.members.size() + "p, Avg Rank: " +
                    RankTier.getByRp(party.avgRp).name + ") entered matchmaking.");
                broadcastPartyState(party);

                Map<String, Object> qStatus = new LinkedHashMap<>();
                qStatus.put("type", "queue_status");
                qStatus.put("elapsed_seconds", 0);
                qStatus.put("estimated_seconds", computeEstimatedWaitSeconds(party));
                qStatus.put("queued_players", party.members.size());
                qStatus.put("max_players", config.maxPlayers);
                qStatus.put("bot_fill_timeout", config.botFillTimeoutSeconds);
                client.sendJson(qStatus);
                break;
            }
            case "cancel_queue": {
                PartyRoom party = parties.get(client.partyId);
                if (party == null || !party.inQueue) break;
                party.inQueue = false;
                party.elapsedSeconds = 0;
                System.out.println("[QUEUE] Party #" + party.id + " cancelled matchmaking.");
                broadcastPartyState(party);
                Map<String, Object> cancelMsg = new LinkedHashMap<>();
                cancelMsg.put("type", "queue_cancelled");
                cancelMsg.put("reason", "Cancelled");
                for (ClientSession m : party.members) {
                    m.sendJson(cancelMsg);
                }
                break;
            }
            case "chat": {
                String text = strVal(msg.get("text")).trim();
                if (text.length() > 140) text = text.substring(0, 140);
                if (text.isEmpty()) break;
                String scope = strVal(msg.getOrDefault("scope", "auto"));
                RankTier r = RankTier.getByRp(client.profile != null ? client.profile.rp : 100);
                Map<String, Object> chatPacket = new LinkedHashMap<>();
                chatPacket.put("type", "chat");
                chatPacket.put("sender_id", client.id);
                chatPacket.put("sender_uuid", client.uuid);
                chatPacket.put("sender_name", client.name);
                chatPacket.put("sender_rank_tier", r.tier);
                chatPacket.put("sender_rank_name", r.name);
                chatPacket.put("scope", client.matchId > 0 ? "match" : "party");
                chatPacket.put("text", text);

                if (client.matchId > 0 && !"party".equals(scope)) {
                    MatchRoom mRoom = matchRooms.get(client.matchId);
                    if (mRoom != null) {
                        for (ClientSession c : mRoom.clients.values()) {
                            c.sendJson(chatPacket);
                        }
                    }
                } else {
                    PartyRoom party = parties.get(client.partyId);
                    if (party != null) {
                        for (ClientSession m : party.members) {
                            m.sendJson(chatPacket);
                        }
                    }
                }
                break;
            }
            case "match_result": {
                if (client.profile == null) break;
                boolean won = Boolean.TRUE.equals(msg.get("won"));
                int kills = Math.max(0, intVal(msg.get("kills"), 0));
                int placement = Math.max(1, intVal(msg.get("placement"), won ? 1 : 8));
                int rpDelta;
                if (won) {
                    rpDelta = 45 + kills * 8;
                    client.profile.wins += 1;
                } else if (placement <= 5) {
                    rpDelta = 18 + kills * 6;
                } else if (placement <= 10) {
                    rpDelta = 4 + kills * 5;
                } else {
                    rpDelta = -12 + kills * 4;
                }
                client.profile.kills += kills;
                client.profile.matches += 1;
                client.profile.rp = Math.max(0, client.profile.rp + rpDelta);
                storage.save();

                if (client.matchId > 0) {
                    MatchRoom mRoom = matchRooms.get(client.matchId);
                    if (mRoom != null) {
                        mRoom.clients.remove(client.id);
                        if (mRoom.clients.isEmpty()) matchRooms.remove(mRoom.id);
                    }
                    client.matchId = 0;
                }

                Map<String, Object> profMsg = new LinkedHashMap<>();
                profMsg.put("type", "profile_updated");
                profMsg.put("rp_delta", rpDelta);
                profMsg.put("profile", client.profile.toClientMap());
                client.sendJson(profMsg);

                PartyRoom party = parties.get(client.partyId);
                if (party != null) broadcastPartyState(party);
                break;
            }
            case "state":
            case "action": {
                if (client.matchId > 0) {
                    MatchRoom mRoom = matchRooms.get(client.matchId);
                    if (mRoom != null) {
                        msg.put("id", client.id);
                        for (ClientSession c : mRoom.clients.values()) {
                            if (c.id != client.id) {
                                c.sendJson(msg);
                            }
                        }
                    }
                }
                break;
            }
            default:
                break;
        }
    }

    private void sendToast(ClientSession client, String message) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("type", "toast");
        m.put("message", message);
        client.sendJson(m);
    }

    public synchronized void shutdownServer(String reason) {
        running = false;
        if (scheduler != null) {
            scheduler.shutdownNow();
            scheduler = null;
        }
        Map<String, Object> err = new LinkedHashMap<>();
        err.put("type", "error");
        err.put("message", reason);
        for (ClientSession c : clients.values()) {
            try {
                c.sendJson(err);
                c.close();
            } catch (Exception ignored) {}
        }
        clients.clear();
        clientsByUuid.clear();
        parties.clear();
        matchRooms.clear();
        if (serverSocket != null) {
            try {
                serverSocket.close();
            } catch (IOException ignored) {}
            serverSocket = null;
        }
    }

    public void runConsoleLoop() {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(System.in))) {
            String line;
            while ((line = reader.readLine()) != null) {
                String cmd = line.trim().toLowerCase();
                if ("force-start".equals(cmd)) {
                    List<PartyRoom> candidates = new ArrayList<>();
                    for (PartyRoom p : parties.values()) {
                        if (p.inQueue && !p.members.isEmpty()) candidates.add(p);
                    }
                    if (candidates.isEmpty()) {
                        for (PartyRoom p : parties.values()) {
                            if (!p.members.isEmpty() && p.members.get(0).matchId == 0) candidates.add(p);
                        }
                    }
                    if (candidates.isEmpty()) {
                        System.out.println("[MATCH] No online parties available to force-start.");
                    } else {
                        launchMatchFromParties(candidates.subList(0, Math.min(3, candidates.size())), "ADMIN FORCE-START");
                    }
                } else if ("status".equals(cmd)) {
                    System.out.println("[STATUS] Online Players: " + clients.size() +
                        " | Parties: " + parties.size() + " | Active Matches: " + matchRooms.size());
                    for (PartyRoom p : parties.values()) {
                        System.out.println("  - Party #" + p.id + ": " + p.members.size() + "/" + config.maxPartySize +
                            " player(s) | AvgRP: " + p.avgRp + " | Queue: " + (p.inQueue ? p.elapsedSeconds + "s" : "Lobby"));
                    }
                    for (MatchRoom m : matchRooms.values()) {
                        System.out.println("  - Match #" + m.id + ": " + m.status + " | " + m.clients.size() + " human(s)");
                    }
                } else if ("restart".equals(cmd)) {
                    System.out.println("[SERVER] Restarting server...");
                    shutdownServer("Server Restarting");
                    startServer();
                    continue;
                } else if ("stop".equals(cmd)) {
                    System.out.println("[SERVER] Stopping server...");
                    shutdownServer("Server Stopped");
                    System.exit(0);
                    return;
                } else if (!cmd.isEmpty()) {
                    System.out.println("[CMD] Unknown command. Available: force-start | restart | stop | status");
                }
                System.out.print("> ");
                System.out.flush();
            }
        } catch (IOException ignored) {
        }
    }

    private static String strVal(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    private static int intVal(Object o, int fallback) {
        if (o instanceof Number) return ((Number) o).intValue();
        if (o != null) {
            try { return Integer.parseInt(String.valueOf(o)); } catch (NumberFormatException ignored) {}
        }
        return fallback;
    }

    public static void main(String[] args) {
        TrustOfBetrayServer srv = new TrustOfBetrayServer(new File("."));
        srv.startServer();
        srv.runConsoleLoop();
    }
}
