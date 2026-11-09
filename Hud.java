package com.ledgerplus;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderTickCounter;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

public final class Hud {
    private record Row(String label, String value, int color) {}

    private Hud() {}

    private static int tone(double v, Config c) {
        return v > 0 ? c.positive() : v < 0 ? c.negative() : c.text();
    }

    public static void render(DrawContext ctx, RenderTickCounter tick) {
        Config c = LedgerPlus.CONFIG;
        MinecraftClient mc = MinecraftClient.getInstance();
        if (c == null || !c.hudEnabled || mc.options.hudHidden || mc.player == null) return;

        Ledger L = LedgerPlus.LEDGER;
        TextRenderer tr = mc.textRenderer;
        Ledger.Session s = L.active();

        List<Row> rows = new ArrayList<>();
        if (c.hudShowSessionNet) {
            rows.add(s == null
                    ? new Row("Session", "idle", c.dim())
                    : new Row("Session", Fmt.signed(s.net()), tone(s.net(), c)));
        }
        if (c.hudShowInOut && s != null) {
            rows.add(new Row("In", Fmt.money(s.received), c.positive()));
            rows.add(new Row("Out", Fmt.money(s.sent), c.negative()));
        }
        if (c.hudShowAllTime) {
            double n = L.allReceived() - L.allSent();
            rows.add(new Row("All-time", Fmt.signed(n), tone(n, c)));
        }
        if (c.hudShowLast && !L.recent.isEmpty()) {
            Ledger.Tx t = L.recent.peekFirst();
            rows.add(new Row("Last", (t.incoming ? "+" : "-") + Fmt.money(t.amount) + " " + t.player,
                    t.incoming ? c.positive() : c.negative()));
        }
        if (c.hudTopPlayers > 0 && s != null) {
            List<Map.Entry<String, Ledger.PlayerStat>> top = new ArrayList<>(s.players.entrySet());
            top.sort(Comparator.comparingDouble((Map.Entry<String, Ledger.PlayerStat> e) -> Math.abs(e.getValue().net())).reversed());
            for (int i = 0; i < Math.min(c.hudTopPlayers, top.size()); i++) {
                double n = top.get(i).getValue().net();
                rows.add(new Row(top.get(i).getKey(), Fmt.signed(n), tone(n, c)));
            }
        }

        String timer = (s != null && c.hudShowTimer) ? Fmt.duration(s.duration()) : null;

        final int pad = 5, lineH = 10;
        int labelW = 0, valueW = 0;
        for (Row r : rows) {
            labelW = Math.max(labelW, tr.getWidth(r.label()));
            valueW = Math.max(valueW, tr.getWidth(r.value()));
        }
        int titleW = tr.getWidth("LEDGER+") + (timer == null ? 0 : 10 + tr.getWidth(timer));
        int inner = Math.max(titleW, labelW + 12 + valueW);
        int w = inner + pad * 2 + 2;
        int h = pad * 2 + lineH * (rows.size() + 1) + 2;

        float sc = c.hudScale;
        int sw = ctx.getScaledWindowWidth(), sh = ctx.getScaledWindowHeight();
        boolean right = c.hudAnchor.endsWith("RIGHT");
        boolean bottom = c.hudAnchor.startsWith("BOTTOM");
        float x = right ? sw - c.hudX - w * sc : c.hudX;
        float y = bottom ? sh - c.hudY - h * sc : c.hudY;

        ctx.getMatrices().pushMatrix();
        ctx.getMatrices().translate(x, y);
        ctx.getMatrices().scale(sc, sc);

        if (c.hudBackground) ctx.fill(0, 0, w, h, Fmt.alpha(c.panel(), c.hudBackgroundOpacity));
        ctx.fill(0, 0, 2, h, c.accent());

        int tx = 2 + pad;
        ctx.drawText(tr, "LEDGER", tx, pad, c.accent(), true);
        ctx.drawText(tr, "+", tx + tr.getWidth("LEDGER"), pad, c.text(), true);
        if (timer != null) ctx.drawText(tr, timer, w - pad - tr.getWidth(timer), pad, c.dim(), true);

        int y0 = pad + lineH + 2;
        for (Row r : rows) {
            ctx.drawText(tr, r.label(), tx, y0, c.dim(), true);
            ctx.drawText(tr, r.value(), w - pad - tr.getWidth(r.value()), y0, r.color(), true);
            y0 += lineH;
        }
        ctx.getMatrices().popMatrix();
    }
}
