package com.ledgerplus;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;

/** Flat, theme-aware button. */
public class FlatButton extends ButtonWidget {
    public boolean selected;

    public FlatButton(int x, int y, int w, int h, Text msg, PressAction action) {
        super(x, y, w, h, msg, action, DEFAULT_NARRATION_SUPPLIER);
    }

    @Override
    public void renderWidget(DrawContext ctx, int mouseX, int mouseY, float delta) {
        Config c = LedgerPlus.CONFIG;
        boolean hov = isHovered() && active;
        int x = getX(), y = getY(), w = getWidth(), h = getHeight();
        ctx.fill(x, y, x + w, y + h, (selected || hov) ? c.cardHi() : c.card());
        if (selected) ctx.fill(x, y + h - 2, x + w, y + h, c.accent());
        else if (hov) ctx.fill(x, y + h - 1, x + w, y + h, Fmt.mix(c.accent(), c.panel(), 0.4f));

        TextRenderer tr = MinecraftClient.getInstance().textRenderer;
        int col = !active ? c.dim() : (selected || hov) ? c.text() : Fmt.mix(c.text(), c.panel(), 0.15f);
        int tw = tr.getWidth(getMessage());
        ctx.drawText(tr, getMessage(), x + (w - tw) / 2, y + (h - 8) / 2, col, false);
    }
}
