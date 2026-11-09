package com.ledgerplus;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class Ledger {
    public static class Tx {
        public long time;
        public String player;
        public double amount;
        public boolean incoming;
    }

    public static class PlayerStat {
        public double received, sent;
        public int in, out;
        public double net() { return received - sent; }
        public double volume() { return received + sent; }
        public int count() { return in + out; }
    }

    public static class Session {
        public int id;
        public String name;
        public long start, end, lastTx;
        public double received, sent;
        public int count;
        public Map<String, PlayerStat> players = new LinkedHashMap<>();
        public double net() { return received - sent; }
        public long duration() { return (end == 0 ? System.currentTimeMillis() : end) - start; }
    }

    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("ledgerplus-data.json");

    public List<Session> sessions = new ArrayList<>();
    public int nextId = 1;
    public transient boolean dirty;
    public transient Deque<Tx> recent = new ArrayDeque<>();

    public static Ledger load() {
        if (Files.exists(FILE)) {
            try (Reader r = Files.newBufferedReader(FILE)) {
                Ledger l = GSON.fromJson(r, Ledger.class);
                if (l != null) {
                    if (l.sessions == null) l.sessions = new ArrayList<>();
                    l.recent = new ArrayDeque<>();
                    return l;
                }
            } catch (Exception e) {
                LedgerPlus.LOG.warn("Could not read ledgerplus-data.json, starting fresh", e);
            }
        }
        return new Ledger();
    }

    public void save() {
        try {
            Files.writeString(FILE, GSON.toJson(this));
            dirty = false;
        } catch (IOException e) {
            LedgerPlus.LOG.warn("Could not save ledgerplus-data.json", e);
        }
    }

    public void saveIfDirty() { if (dirty) save(); }

    public Session active() {
        for (int i = sessions.size() - 1; i >= 0; i--) {
            if (sessions.get(i).end == 0) return sessions.get(i);
        }
        return null;
    }

    public Session lastSession() {
        return sessions.isEmpty() ? null : sessions.get(sessions.size() - 1);
    }

    public Session start(String name) {
        end();
        Session s = new Session();
        s.id = nextId++;
        s.name = (name == null || name.isBlank()) ? "Session " + s.id : name;
        s.start = System.currentTimeMillis();
        sessions.add(s);
        dirty = true;
        return s;
    }

    public void end() {
        Session s = active();
        if (s != null) {
            s.end = System.currentTimeMillis();
            dirty = true;
        }
    }

    public void delete(int id) {
        sessions.removeIf(s -> s.id == id);
        dirty = true;
    }

    public void clearAll() {
        sessions.clear();
        recent.clear();
        dirty = true;
    }

    public void record(String player, double amount, boolean incoming) {
        Config c = LedgerPlus.CONFIG;
        for (String p : c.ignoredPlayers) if (p.equalsIgnoreCase(player)) return;
        Session s = active();
        if (s == null) {
            if (!c.autoStartSession) return;
            s = start(null);
        }
        long now = System.currentTimeMillis();
        s.lastTx = now;
        s.count++;
        PlayerStat ps = s.players.computeIfAbsent(player, k -> new PlayerStat());
        if (incoming) { s.received += amount; ps.received += amount; ps.in++; }
        else { s.sent += amount; ps.sent += amount; ps.out++; }

        Tx t = new Tx();
        t.time = now; t.player = player; t.amount = amount; t.incoming = incoming;
        recent.addFirst(t);
        while (recent.size() > 100) recent.removeLast();
        dirty = true;
    }

    public Map<String, PlayerStat> allPlayers() {
        Map<String, PlayerStat> m = new HashMap<>();
        for (Session s : sessions) {
            for (Map.Entry<String, PlayerStat> e : s.players.entrySet()) {
                PlayerStat t = m.computeIfAbsent(e.getKey(), k -> new PlayerStat());
                PlayerStat p = e.getValue();
                t.received += p.received; t.sent += p.sent; t.in += p.in; t.out += p.out;
            }
        }
        return m;
    }

    public double allReceived() { double v = 0; for (Session s : sessions) v += s.received; return v; }
    public double allSent() { double v = 0; for (Session s : sessions) v += s.sent; return v; }

    public Path exportCsv() throws IOException {
        Path out = FabricLoader.getInstance().getConfigDir().resolve("ledgerplus-export.csv");
        StringBuilder sb = new StringBuilder("session_id,name,start,end,received,sent,net,transactions\n");
        for (Session s : sessions) {
            sb.append(s.id).append(',').append('"').append(s.name.replace("\"", "'")).append('"').append(',')
              .append(s.start).append(',').append(s.end).append(',')
              .append(s.received).append(',').append(s.sent).append(',').append(s.net()).append(',')
              .append(s.count).append('\n');
        }
        sb.append("\nplayer,received,sent,net,in_count,out_count\n");
        for (Map.Entry<String, PlayerStat> e : allPlayers().entrySet()) {
            PlayerStat p = e.getValue();
            sb.append(e.getKey()).append(',').append(p.received).append(',').append(p.sent).append(',')
              .append(p.net()).append(',').append(p.in).append(',').append(p.out).append('\n');
        }
        Files.writeString(out, sb.toString());
        return out;
    }
}
