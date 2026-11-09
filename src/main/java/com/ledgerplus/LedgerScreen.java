package com.ledgerplus;

import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.IntSupplier;
import java.util.function.Supplier;

public class LedgerScreen extends Screen {
    private enum Tab {
        OVERVIEW("Overview"), PLAYERS("Players"), HISTORY("History"), SETTINGS("Settings");
        final String label;
        Tab(String l) { label = l; }
    }

    private enum Sub {
        HUD("HUD"), COLORS("Colors"), TRACKING("Tracking");
        final String label;
        Sub(String l) { label = l; }
    }

    private record Label(int x, int y, Supplier<String> text, IntSupplier color) {}
    private record Swatch(int x, int y, IntSupplier color) {}

    private static final double[] PRESETS = {0, 10_000, 100_000, 1_000_000, 10_000_000, 100_000_000, 1_000_000_000};
    private static final String[] SORTS = {"Net", "Volume", "Trades", "Name", "Rakeback"};
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss").withZone(ZoneId.systemDefault());
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("MMM d, HH:mm").withZone(ZoneId.systemDefault());

    // static so the screen reopens where you left it
    private static Tab tab = Tab.OVERVIEW;
    private static Sub sub = Sub.HUD;
    private static boolean allTime;
    private static int sort;

    private final List<Label> labels = new ArrayList<>();
    private final List<int[]> plates = new ArrayList<>();
    private final List<Swatch> swatches = new ArrayList<>();

    private int scroll, maxScroll, historyPage;
    private boolean confirmClear;
    private String status = "";
    private int px, py, pw, ph, cx, cy, cw, ch;

    public LedgerScreen() {
        super(Text.literal("Ledger+"));
    }

    private Config cfg() { return LedgerPlus.CONFIG; }
    private Ledger led() { return LedgerPlus.LEDGER; }

    @Override
    public boolean shouldPause() { return false; }

    // ------------------------------------------------------------ layout

    @Override
    protected void init() {
        labels.clear();
        plates.clear();
        swatches.clear();
        pw = Math.min(width - 16, 480);
        ph = Math.min(height - 16, 310);
        px = (width - pw) / 2;
        py = (height - ph) / 2;
        cx = px + 10;
        cy = py + 38;
        cw = pw - 20;
        ch = ph - 38 - 10;

        int tw = 62, gap = 4, n = Tab.values().length;
        int tx = px + pw - 10 - (n * (tw + gap) - gap);
        for (Tab t : Tab.values()) {
            FlatButton b = btn(tx, py + 6, tw, 18, t.label, () -> {
                tab = t; scroll = 0; historyPage = 0; confirmClear = false; status = "";
                clearAndInit();
            });
            b.selected = (t == tab);
            tx += tw + gap;
        }

        switch (tab) {
            case OVERVIEW -> initOverview();
            case PLAYERS -> initPlayers();
            case HISTORY -> initHistory();
            case SETTINGS -> initSettings();
        }
    }

    private FlatButton btn(int x, int y, int w, int h, String text, Runnable action) {
        FlatButton b = new FlatButton(x, y, w, h, Text.literal(text), bb -> action.run());
        addDrawableChild(b);
        return b;
    }

    // ------------------------------------------------------------ overview

    private void initOverview() {
        boolean live = led().active() != null;
        btn(cx, cy + ch - 20, 110, 20, live ? "End session" : "Start session", () -> {
            if (led().active() != null) led().end(); else led().start(null);
            led().dirty = true;
            clearAndInit();
        });
        btn(cx + 116, cy + ch - 20, 110, 20, "HUD settings", () -> {
            tab = Tab.SETTINGS; sub = Sub.HUD; clearAndInit();
        });
    }

    private void drawOverview(DrawContext ctx) {
        Ledger L = led();
        Config c = cfg();
        TextRenderer tr = textRenderer;
        Ledger.Session live = L.active();
        Ledger.Session show = live != null ? live : L.lastSession();

        String head = live != null ? "LIVE SESSION  -  " + live.name
                : show != null ? "LAST SESSION  -  " + show.name : "NO SESSIONS YET - they start automatically on your first /pay";
        ctx.drawText(tr, head, cx, cy, live != null ? c.accent() : c.dim(), false);

        double net = show == null ? 0 : show.net();
        ctx.getMatrices().pushMatrix();
        ctx.getMatrices().translate(cx, cy + 13);
        ctx.getMatrices().scale(2f, 2f);
        ctx.drawText(tr, Fmt.signed(net), 0, 0, net > 0 ? c.positive() : net < 0 ? c.negative() : c.text(), true);
        ctx.getMatrices().popMatrix();

        String[] names = {"Received", "Sent", "Transactions", live != null ? "Duration" : "All-time net"};
        double all = L.allReceived() - L.allSent();
        String[] vals = {
                show == null ? "-" : Fmt.money(show.received),
                show == null ? "-" : Fmt.money(show.sent),
                show == null ? "-" : String.valueOf(show.count),
                live != null ? Fmt.duration(live.duration()) : Fmt.signed(all)};
        int[] tones = {c.positive(), c.negative(), c.text(), live != null ? c.text() : (all >= 0 ? c.positive() : c.negative())};
        int gap = 6, cardW = (cw - gap * 3) / 4, cardY = cy + 38, cardH = 32;
        for (int i = 0; i < 4; i++) {
            int x = cx + i * (cardW + gap);
            ctx.fill(x, cardY, x + cardW, cardY + cardH, c.card());
            ctx.fill(x, cardY, x + 2, cardY + cardH, tones[i]);
            ctx.drawText(tr, names[i], x + 8, cardY + 6, c.dim(), false);
            ctx.drawText(tr, vals[i], x + 8, cardY + 18, tones[i], false);
        }

        int listY = cardY + cardH + 10;
        ctx.drawText(tr, "RECENT ACTIVITY", cx, listY, c.dim(), false);
        listY += 13;
        int fit = Math.max(0, (cy + ch - 26 - listY) / 11);
        int i = 0;
        for (Ledger.Tx t : L.recent) {
            if (i >= fit) break;
            int y = listY + i * 11;
            String line = (t.incoming ? "+" : "-") + Fmt.money(t.amount) + (t.incoming ? "  from " : "  to ") + t.player;
            ctx.drawText(tr, TIME.format(Instant.ofEpochMilli(t.time)), cx, y, c.dim(), false);
            ctx.drawText(tr, line, cx + 54, y, t.incoming ? c.positive() : c.negative(), false);
            i++;
        }
        if (L.recent.isEmpty()) ctx.drawText(tr, "Nothing yet this launch.", cx, listY, c.dim(), false);
    }

    // ------------------------------------------------------------ players

    private void initPlayers() {
        btn(cx, cy, 110, 18, allTime ? "Scope: All time" : "Scope: Session", () -> {
            allTime = !allTime; scroll = 0; clearAndInit();
        });
        btn(cx + 116, cy, 110, 18, "Sort: " + SORTS[sort], () -> {
            sort = (sort + 1) % SORTS.length; clearAndInit();
        });
    }

    private List<Map.Entry<String, Ledger.PlayerStat>> playerList() {
        Map<String, Ledger.PlayerStat> m;
        if (allTime) m = led().allPlayers();
        else {
            Ledger.Session s = led().active() != null ? led().active() : led().lastSession();
            m = s == null ? Map.of() : s.players;
        }
        List<Map.Entry<String, Ledger.PlayerStat>> list = new ArrayList<>(m.entrySet());
        Comparator<Map.Entry<String, Ledger.PlayerStat>> cmp = switch (sort) {
            case 0 -> Comparator.comparingDouble(e -> -e.getValue().net());
            case 1 -> Comparator.comparingDouble(e -> -e.getValue().volume());
            case 2 -> Comparator.comparingInt(e -> -e.getValue().count());
            case 3 -> Comparator.comparing(e -> e.getKey().toLowerCase());
            default -> Comparator.comparingDouble(e -> -(e.getValue().sent * cfg().rakebackFor(e.getKey())));
        };
        list.sort(cmp);
        return list;
    }

    private void right(DrawContext ctx, String s, int xr, int y, int color) {
        ctx.drawText(textRenderer, s, xr - textRenderer.getWidth(s), y, color, false);
    }

    private void drawPlayers(DrawContext ctx) {
        Config c = cfg();
        TextRenderer tr = textRenderer;
        int rc = cx + (int) (cw * 0.50), sc = cx + (int) (cw * 0.66), nc = cx + (int) (cw * 0.83), rb = cx + cw - 4;
        int hy = cy + 24;
        ctx.drawText(tr, "PLAYER", cx + 4, hy, c.dim(), false);
        right(ctx, "RECEIVED", rc, hy, c.dim());
        right(ctx, "SENT", sc, hy, c.dim());
        right(ctx, "NET", nc, hy, c.dim());
        right(ctx, "RAKEBACK", rb, hy, c.dim());
        ctx.fill(cx, hy + 11, cx + cw, hy + 12, c.cardHi());

        int top = cy + 38, viewH = ch - 38, rowH = 13;
        List<Map.Entry<String, Ledger.PlayerStat>> list = playerList();
        maxScroll = Math.max(0, list.size() * rowH - viewH);
        scroll = Math.max(0, Math.min(scroll, maxScroll));

        ctx.enableScissor(cx, top, cx + cw, top + viewH);
        int y = top - scroll;
        int idx = 0;
        for (Map.Entry<String, Ledger.PlayerStat> e : list) {
            Ledger.PlayerStat p = e.getValue();
            if (y + rowH >= top && y <= top + viewH) {
                if (idx % 2 == 0) ctx.fill(cx, y, cx + cw, y + rowH, c.card());
                ctx.drawText(tr, e.getKey(), cx + 4, y + 3, c.text(), false);
                right(ctx, Fmt.money(p.received), rc, y + 3, c.positive());
                right(ctx, Fmt.money(p.sent), sc, y + 3, c.negative());
                right(ctx, Fmt.signed(p.net()), nc, y + 3, p.net() > 0 ? c.positive() : p.net() < 0 ? c.negative() : c.text());
                double rbv = p.sent * c.rakebackFor(e.getKey()) / 100.0;
                right(ctx, rbv > 0 ? Fmt.money(rbv) : "-", rb, y + 3, c.accent());
            }
            y += rowH;
            idx++;
        }
        ctx.disableScissor();
        if (list.isEmpty()) ctx.drawText(tr, "No players yet.", cx + 4, top + 4, c.dim(), false);
    }

    // ------------------------------------------------------------ history

    private int perPage() { return Math.max(1, (ch - 28) / 26); }

    private List<Ledger.Session> historyRows() {
        List<Ledger.Session> rev = new ArrayList<>(led().sessions);
        Collections.reverse(rev);
        return rev;
    }

    private int pageCount() {
        return Math.max(1, (led().sessions.size() + perPage() - 1) / perPage());
    }

    private void changePage(int d) {
        historyPage = Math.max(0, Math.min(pageCount() - 1, historyPage + d));
        clearAndInit();
    }

    private void initHistory() {
        historyPage = Math.min(historyPage, pageCount() - 1);
        List<Ledger.Session> rows = historyRows();
        int start = historyPage * perPage();
        for (int i = 0; i < perPage() && start + i < rows.size(); i++) {
            int id = rows.get(start + i).id;
            btn(cx + cw - 22, cy + i * 26, 22, 24, "x", () -> {
                led().delete(id);
                clearAndInit();
            });
        }
        int by = cy + ch - 20;
        btn(cx, by, 24, 20, "<", () -> changePage(-1));
        btn(cx + 28, by, 24, 20, ">", () -> changePage(1));
        btn(cx + cw - 214, by, 100, 20, "Export CSV", () -> {
            try {
                status = "Saved " + led().exportCsv().getFileName();
            } catch (Exception e) {
                status = "Export failed: " + e.getMessage();
            }
        });
        btn(cx + cw - 110, by, 110, 20, confirmClear ? "Click to confirm" : "Clear all data", () -> {
            if (confirmClear) { led().clearAll(); confirmClear = false; status = "All data cleared."; }
            else confirmClear = true;
            clearAndInit();
        });
    }

    private void drawHistory(DrawContext ctx) {
        Config c = cfg();
        TextRenderer tr = textRenderer;
        List<Ledger.Session> rows = historyRows();
        int start = historyPage * perPage();
        for (int i = 0; i < perPage() && start + i < rows.size(); i++) {
            Ledger.Session s = rows.get(start + i);
            int y = cy + i * 26;
            ctx.fill(cx, y, cx + cw - 26, y + 24, c.card());
            ctx.fill(cx, y, cx + 2, y + 24, s.end == 0 ? c.accent() : c.cardHi());
            ctx.drawText(tr, "#" + s.id + "  " + s.name + (s.end == 0 ? "  (live)" : ""), cx + 8, y + 4, c.text(), false);
            right(ctx, Fmt.signed(s.net()), cx + cw - 32, y + 4, s.net() > 0 ? c.positive() : s.net() < 0 ? c.negative() : c.text());
            ctx.drawText(tr, DATE.format(Instant.ofEpochMilli(s.start)) + "  -  " + Fmt.duration(s.duration())
                    + "  -  " + s.count + " tx  -  in " + Fmt.money(s.received) + "  out " + Fmt.money(s.sent), cx + 8, y + 14, c.dim(), false);
        }
        if (rows.isEmpty()) ctx.drawText(tr, "No sessions recorded yet.", cx, cy + 4, c.dim(), false);
        ctx.drawText(tr, "Page " + (historyPage + 1) + "/" + pageCount(), cx + 58, cy + ch - 14, c.dim(), false);
        if (!status.isEmpty()) ctx.drawText(tr, status, cx + 120, cy + ch - 34, c.accent(), false);
    }

    // ------------------------------------------------------------ settings

    private int colW() { return (cw - 8) / 2; }
    private int gx(int i) { return cx + (i % 2) * (colW() + 8); }
    private int gy(int i) { return cy + 26 + (i / 2) * 24; }

    private void toggle(int x, int y, int w, String label, BooleanSupplier get, Consumer<Boolean> set) {
        FlatButton[] ref = new FlatButton[1];
        ref[0] = btn(x, y, w, 20, label + ": " + (get.getAsBoolean() ? "ON" : "OFF"), () -> {
            set.accept(!get.getAsBoolean());
            cfg().save();
            ref[0].setMessage(Text.literal(label + ": " + (get.getAsBoolean() ? "ON" : "OFF")));
        });
    }

    private void cycle(int x, int y, int w, String label, Supplier<String> value, Runnable next) {
        FlatButton[] ref = new FlatButton[1];
        ref[0] = btn(x, y, w, 20, label + ": " + value.get(), () -> {
            next.run();
            cfg().save();
            ref[0].setMessage(Text.literal(label + ": " + value.get()));
        });
    }

    private void stepper(int x, int y, int w, String label, Supplier<String> value, Runnable dec, Runnable inc) {
        plates.add(new int[]{x, y, w, 20});
        labels.add(new Label(x + 6, y + 6, () -> label + ": " + value.get(), () -> cfg().text()));
        btn(x + w - 40, y + 1, 18, 18, "-", () -> { dec.run(); cfg().save(); });
        btn(x + w - 20, y + 1, 18, 18, "+", () -> { inc.run(); cfg().save(); });
    }

    private void colorRow(int x, int y, int w, String label, Supplier<String> get, Consumer<String> set) {
        plates.add(new int[]{x, y, w, 22});
        labels.add(new Label(x + 8, y + 7, () -> label, () -> cfg().text()));
        TextFieldWidget f = new TextFieldWidget(textRenderer, x + w - 104, y + 2, 78, 18, Text.literal(label));
        f.setMaxLength(7);
        f.setText(get.get());
        f.setChangedListener(s -> {
            if (Fmt.hex(s) != null) {
                set.accept(s.startsWith("#") ? s : "#" + s);
                cfg().save();
            }
        });
        addDrawableChild(f);
        swatches.add(new Swatch(x + w - 20, y + 3, () -> cfg().col(get.get(), 0xFF000000)));
    }

    private static String pretty(String anchor) {
        String s = anchor.toLowerCase().replace('_', ' ');
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    private void initSettings() {
        int sx = cx;
        for (Sub s : Sub.values()) {
            FlatButton b = btn(sx, cy, 84, 18, s.label, () -> { sub = s; clearAndInit(); });
            b.selected = (s == sub);
            sx += 88;
        }
        int w = colW();
        switch (sub) {
            case HUD -> {
                toggle(gx(0), gy(0), w, "HUD", () -> cfg().hudEnabled, v -> cfg().hudEnabled = v);
                cycle(gx(1), gy(1), w, "Anchor", () -> pretty(cfg().hudAnchor), () -> {
                    int i = Arrays.asList(Config.ANCHORS).indexOf(cfg().hudAnchor);
                    cfg().hudAnchor = Config.ANCHORS[(i + 1) % Config.ANCHORS.length];
                });
                stepper(gx(2), gy(2), w, "Offset X", () -> String.valueOf(cfg().hudX),
                        () -> cfg().hudX = Math.max(0, cfg().hudX - 4), () -> cfg().hudX = Math.min(800, cfg().hudX + 4));
                stepper(gx(3), gy(3), w, "Offset Y", () -> String.valueOf(cfg().hudY),
                        () -> cfg().hudY = Math.max(0, cfg().hudY - 4), () -> cfg().hudY = Math.min(800, cfg().hudY + 4));
                stepper(gx(4), gy(4), w, "Scale", () -> String.format("%.1fx", cfg().hudScale),
                        () -> cfg().hudScale = Math.max(0.5f, Math.round((cfg().hudScale - 0.1f) * 10) / 10f),
                        () -> cfg().hudScale = Math.min(3f, Math.round((cfg().hudScale + 0.1f) * 10) / 10f));
                toggle(gx(5), gy(5), w, "Background", () -> cfg().hudBackground, v -> cfg().hudBackground = v);
                stepper(gx(6), gy(6), w, "BG opacity", () -> cfg().hudBackgroundOpacity + "%",
                        () -> cfg().hudBackgroundOpacity = Math.max(0, cfg().hudBackgroundOpacity - 5),
                        () -> cfg().hudBackgroundOpacity = Math.min(100, cfg().hudBackgroundOpacity + 5));
                toggle(gx(7), gy(7), w, "Timer", () -> cfg().hudShowTimer, v -> cfg().hudShowTimer = v);
                toggle(gx(8), gy(8), w, "Session net", () -> cfg().hudShowSessionNet, v -> cfg().hudShowSessionNet = v);
                toggle(gx(9), gy(9), w, "In / Out", () -> cfg().hudShowInOut, v -> cfg().hudShowInOut = v);
                toggle(gx(10), gy(10), w, "All-time net", () -> cfg().hudShowAllTime, v -> cfg().hudShowAllTime = v);
                toggle(gx(11), gy(11), w, "Last payment", () -> cfg().hudShowLast, v -> cfg().hudShowLast = v);
                stepper(gx(12), gy(12), w, "Top players", () -> String.valueOf(cfg().hudTopPlayers),
                        () -> cfg().hudTopPlayers = Math.max(0, cfg().hudTopPlayers - 1),
                        () -> cfg().hudTopPlayers = Math.min(8, cfg().hudTopPlayers + 1));
            }
            case COLORS -> {
                int fw = cw;
                int y0 = cy + 26;
                colorRow(cx, y0, fw, "Accent", () -> cfg().accentColor, v -> cfg().accentColor = v);
                colorRow(cx, y0 + 26, fw, "Positive (received)", () -> cfg().positiveColor, v -> cfg().positiveColor = v);
                colorRow(cx, y0 + 52, fw, "Negative (sent)", () -> cfg().negativeColor, v -> cfg().negativeColor = v);
                colorRow(cx, y0 + 78, fw, "Text", () -> cfg().textColor, v -> cfg().textColor = v);
                colorRow(cx, y0 + 104, fw, "Panel / background", () -> cfg().panelColor, v -> cfg().panelColor = v);
                labels.add(new Label(cx, y0 + 134, () -> "Type a #RRGGBB hex code - changes apply live.", () -> cfg().dim()));
            }
            case TRACKING -> {
                toggle(gx(0), gy(0), w, "Auto-start session", () -> cfg().autoStartSession, v -> cfg().autoStartSession = v);
                toggle(gx(1), gy(1), w, "Short numbers (1.5K)", () -> cfg().shortNumbers, v -> cfg().shortNumbers = v);
                stepper(gx(2), gy(2), w, "Idle auto-end (min)", () -> cfg().autoEndIdleMinutes == 0 ? "off" : String.valueOf(cfg().autoEndIdleMinutes),
                        () -> cfg().autoEndIdleMinutes = Math.max(0, cfg().autoEndIdleMinutes - 5),
                        () -> cfg().autoEndIdleMinutes = Math.min(240, cfg().autoEndIdleMinutes + 5));
                cycle(gx(3), gy(3), w, "Confirm /pay over", () -> cfg().confirmThreshold <= 0 ? "Off" : Fmt.money(cfg().confirmThreshold, true), () -> {
                    int idx = 0;
                    for (int i = 0; i < PRESETS.length; i++) if (PRESETS[i] == cfg().confirmThreshold) idx = i;
                    cfg().confirmThreshold = PRESETS[(idx + 1) % PRESETS.length];
                });
                stepper(gx(4), gy(4), w, "Default rakeback", () -> String.format("%.1f%%", cfg().defaultRakebackPercent),
                        () -> cfg().defaultRakebackPercent = Math.max(0, Math.round((cfg().defaultRakebackPercent - 0.5) * 10) / 10.0),
                        () -> cfg().defaultRakebackPercent = Math.min(50, Math.round((cfg().defaultRakebackPercent + 0.5) * 10) / 10.0));
                labels.add(new Label(cx, gy(6), () -> "Per-player rakeback, ignored players and chat regexes live in", () -> cfg().dim()));
                labels.add(new Label(cx, gy(6) + 11, () -> "config/ledgerplus.json - edit it, then run /ledger reload.", () -> cfg().dim()));
            }
        }
    }

    // ------------------------------------------------------------ render

    private void drawPanel(DrawContext ctx) {
        Config c = cfg();
        TextRenderer tr = textRenderer;
        ctx.fill(px - 1, py - 1, px + pw + 1, py + ph + 1, Fmt.mix(c.panel(), c.accent(), 0.35f));
        ctx.fill(px, py, px + pw, py + ph, c.panel());
        ctx.fill(px, py, px + pw, py + 30, Fmt.mix(c.panel(), 0xFF000000, 0.25f));
        ctx.fill(px, py + 30, px + pw, py + 31, Fmt.mix(c.panel(), c.accent(), 0.5f));
        ctx.drawText(tr, "LEDGER", px + 10, py + 11, c.accent(), true);
        ctx.drawText(tr, "+", px + 10 + tr.getWidth("LEDGER"), py + 11, c.text(), true);

        for (int[] p : plates) ctx.fill(p[0], p[1], p[0] + p[2], p[1] + p[3], c.card());
        switch (tab) {
            case OVERVIEW -> drawOverview(ctx);
            case PLAYERS -> drawPlayers(ctx);
            case HISTORY -> drawHistory(ctx);
            case SETTINGS -> { }
        }
        for (Label l : labels) ctx.drawText(tr, l.text().get(), l.x(), l.y(), l.color().getAsInt(), false);
        for (Swatch s : swatches) {
            ctx.fill(s.x() - 1, s.y() - 1, s.x() + 17, s.y() + 17, c.cardHi());
            ctx.fill(s.x(), s.y(), s.x() + 16, s.y() + 16, s.color().getAsInt());
        }
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        drawPanel(ctx);                       // panel first, widgets on top
        super.render(ctx, mouseX, mouseY, delta);
    }

    /** No vanilla dim/blur: the live HUD stays visible behind the panel while you tweak it. */
    @Override
    public void renderBackground(DrawContext ctx, int mouseX, int mouseY, float delta) { }

    @Override
    public boolean mouseScrolled(double mx, double my, double h, double v) {
        if (tab == Tab.PLAYERS) {
            scroll = (int) Math.max(0, Math.min(maxScroll, scroll - v * 26));
            return true;
        }
        if (tab == Tab.HISTORY) {
            changePage(v < 0 ? 1 : -1);
            return true;
        }
        return super.mouseScrolled(mx, my, h, v);
    }
}
