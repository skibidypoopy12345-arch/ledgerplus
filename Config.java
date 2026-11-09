package com.ledgerplus;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.io.Reader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Everything here is editable in-game (Settings tab) or in config/ledgerplus.json. */
public class Config {
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Path FILE = FabricLoader.getInstance().getConfigDir().resolve("ledgerplus.json");
    public static final String[] ANCHORS = {"TOP_LEFT", "TOP_RIGHT", "BOTTOM_LEFT", "BOTTOM_RIGHT"};

    // ---- HUD ----
    public boolean hudEnabled = true;
    public String hudAnchor = "TOP_LEFT";
    public int hudX = 6;
    public int hudY = 6;
    public float hudScale = 1.0f;
    public boolean hudBackground = true;
    public int hudBackgroundOpacity = 65;
    public boolean hudShowTimer = true;
    public boolean hudShowSessionNet = true;
    public boolean hudShowInOut = true;
    public boolean hudShowAllTime = false;
    public boolean hudShowLast = true;
    public int hudTopPlayers = 3;

    // ---- Theme (#RRGGBB) ----
    public String accentColor = "#5BC0FF";
    public String positiveColor = "#4ADE80";
    public String negativeColor = "#F87171";
    public String textColor = "#E5E7EB";
    public String panelColor = "#111827";

    // ---- Tracking ----
    public boolean shortNumbers = true;
    public boolean autoStartSession = true;
    public int autoEndIdleMinutes = 0;          // 0 = never auto-end
    public double confirmThreshold = 0;         // 0 = off; otherwise ask before /pay >= this
    public double defaultRakebackPercent = 0;   // % of what you SENT to a player
    public Map<String, Double> rakebackOverrides = new HashMap<>();
    public List<String> ignoredPlayers = new ArrayList<>();

    // ---- Chat patterns (group 1 = player, 2 = number, 3 = k/m/b/t suffix) ----
    public String receivedRegex = "^(?:\\[.*?\\]\\s*)?(\\w{3,16}) paid you \\$?([\\d,]+(?:\\.\\d+)?)\\s*([kKmMbBtT]?)";
    public String sentRegex = "^You paid (\\w{3,16}) \\$?([\\d,]+(?:\\.\\d+)?)\\s*([kKmMbBtT]?)";
    /** Matches the command you type, WITHOUT the leading slash. */
    public String payCommandRegex = "^pay\\s+(\\w{3,16})\\s+\\$?([\\d,]+(?:\\.\\d+)?)\\s*([kKmMbBtT]?)\\s*$";

    public static Config load() {
        Config c = null;
        if (Files.exists(FILE)) {
            try (Reader r = Files.newBufferedReader(FILE)) {
                c = GSON.fromJson(r, Config.class);
            } catch (Exception e) {
                LedgerPlus.LOG.warn("Could not read ledgerplus.json, using defaults", e);
            }
        }
        if (c == null) c = new Config();
        if (c.rakebackOverrides == null) c.rakebackOverrides = new HashMap<>();
        if (c.ignoredPlayers == null) c.ignoredPlayers = new ArrayList<>();
        c.save();
        return c;
    }

    public void save() {
        try {
            Files.writeString(FILE, GSON.toJson(this));
        } catch (IOException e) {
            LedgerPlus.LOG.warn("Could not save ledgerplus.json", e);
        }
    }

    public double rakebackFor(String player) {
        for (Map.Entry<String, Double> e : rakebackOverrides.entrySet()) {
            if (e.getKey().equalsIgnoreCase(player)) return e.getValue();
        }
        return defaultRakebackPercent;
    }

    // ---- theme helpers ----
    public int col(String hex, int fallback) {
        Integer v = Fmt.hex(hex);
        return v == null ? fallback : v;
    }
    public int accent() { return col(accentColor, 0xFF5BC0FF); }
    public int positive() { return col(positiveColor, 0xFF4ADE80); }
    public int negative() { return col(negativeColor, 0xFFF87171); }
    public int text() { return col(textColor, 0xFFE5E7EB); }
    public int panel() { return col(panelColor, 0xFF111827); }
    public int dim() { return Fmt.mix(text(), panel(), 0.5f); }
    public int card() { return Fmt.mix(panel(), 0xFFFFFFFF, 0.07f); }
    public int cardHi() { return Fmt.mix(panel(), 0xFFFFFFFF, 0.16f); }
}
