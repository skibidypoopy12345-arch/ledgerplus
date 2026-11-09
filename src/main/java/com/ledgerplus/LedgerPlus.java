package com.ledgerplus;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.message.v1.ClientReceiveMessageEvents;
import net.fabricmc.fabric.api.client.message.v1.ClientSendMessageEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.fabricmc.fabric.api.client.rendering.v1.hud.VanillaHudElements;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

public class LedgerPlus implements ClientModInitializer {
    public static final String ID = "ledgerplus";
    public static final Logger LOG = LoggerFactory.getLogger(ID);

    public static Config CONFIG;
    public static Ledger LEDGER;
    public static KeyBinding OPEN_KEY;

    private static Pattern recvPattern, sentPattern, cmdPattern;
    private static boolean bypass;
    private static String pendingCommand;
    private static int saveTimer;

    @Override
    public void onInitializeClient() {
        CONFIG = Config.load();
        LEDGER = Ledger.load();
        compilePatterns();

        OPEN_KEY = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.ledgerplus.open", InputUtil.Type.KEYSYM, GLFW.GLFW_KEY_RIGHT_SHIFT,
                KeyBinding.Category.create(Identifier.of(ID, "main"))));

        HudElementRegistry.attachElementAfter(VanillaHudElements.CHAT, Identifier.of(ID, "hud"), Hud::render);

        ClientReceiveMessageEvents.GAME.register((message, overlay) -> {
            if (!overlay) handleChat(message.getString());
        });

        ClientSendMessageEvents.ALLOW_COMMAND.register(LedgerPlus::allowCommand);

        ClientTickEvents.END_CLIENT_TICK.register(mc -> {
            while (OPEN_KEY.wasPressed()) {
                if (mc.currentScreen == null) mc.setScreen(new LedgerScreen());
            }
            Ledger.Session s = LEDGER.active();
            if (s != null && CONFIG.autoEndIdleMinutes > 0) {
                long last = Math.max(s.lastTx, s.start);
                if (System.currentTimeMillis() - last > CONFIG.autoEndIdleMinutes * 60_000L) {
                    LEDGER.end();
                    say(Text.literal("Session ended after " + CONFIG.autoEndIdleMinutes + " idle minutes.").formatted(Formatting.GRAY));
                }
            }
            if (++saveTimer >= 1200) {
                saveTimer = 0;
                LEDGER.saveIfDirty();
            }
        });

        ClientLifecycleEvents.CLIENT_STOPPING.register(mc -> LEDGER.save());

        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> dispatcher.register(
                ClientCommandManager.literal("ledger")
                        .executes(c -> { openScreen(); return 1; })
                        .then(ClientCommandManager.literal("start").executes(c -> {
                            LEDGER.start(null);
                            say(Text.literal("Session started."));
                            return 1;
                        }))
                        .then(ClientCommandManager.literal("end").executes(c -> {
                            LEDGER.end();
                            say(Text.literal("Session ended."));
                            return 1;
                        }))
                        .then(ClientCommandManager.literal("reload").executes(c -> {
                            CONFIG = Config.load();
                            compilePatterns();
                            say(Text.literal("Config reloaded."));
                            return 1;
                        }))
                        .then(ClientCommandManager.literal("confirm").executes(c -> {
                            confirmPending();
                            return 1;
                        }))
                        .then(ClientCommandManager.literal("cancel").executes(c -> {
                            pendingCommand = null;
                            say(Text.literal("Cancelled."));
                            return 1;
                        }))));
    }

    private static void openScreen() {
        MinecraftClient mc = MinecraftClient.getInstance();
        mc.send(() -> mc.setScreen(new LedgerScreen()));
    }

    public static void compilePatterns() {
        recvPattern = compile(CONFIG.receivedRegex, new Config().receivedRegex);
        sentPattern = compile(CONFIG.sentRegex, new Config().sentRegex);
        cmdPattern = compile(CONFIG.payCommandRegex, new Config().payCommandRegex);
    }

    private static Pattern compile(String regex, String fallback) {
        try {
            return Pattern.compile(regex, Pattern.CASE_INSENSITIVE);
        } catch (PatternSyntaxException e) {
            LOG.warn("Bad regex in ledgerplus.json, using default: {}", regex);
            return Pattern.compile(fallback, Pattern.CASE_INSENSITIVE);
        }
    }

    private static void handleChat(String raw) {
        String s = raw.replaceAll("\u00A7.", "").trim();
        try {
            Matcher m = recvPattern.matcher(s);
            if (m.find()) {
                LEDGER.record(m.group(1), Fmt.parseAmount(m.group(2), m.group(3)), true);
                return;
            }
            m = sentPattern.matcher(s);
            if (m.find()) {
                LEDGER.record(m.group(1), Fmt.parseAmount(m.group(2), m.group(3)), false);
            }
        } catch (RuntimeException e) {
            LOG.debug("Could not parse chat line: {}", s, e);
        }
    }

    private static boolean allowCommand(String command) {
        if (bypass || CONFIG.confirmThreshold <= 0) return true;
        Matcher m = cmdPattern.matcher(command.trim());
        if (!m.find()) return true;
        double amount;
        try {
            amount = Fmt.parseAmount(m.group(2), m.group(3));
        } catch (RuntimeException e) {
            return true;
        }
        if (amount < CONFIG.confirmThreshold) return true;

        pendingCommand = command;
        Text yes = Text.literal("[Confirm]").styled(st -> st.withColor(Formatting.GREEN)
                .withClickEvent(new ClickEvent.RunCommand("ledger confirm")));
        Text no = Text.literal("[Cancel]").styled(st -> st.withColor(Formatting.RED)
                .withClickEvent(new ClickEvent.RunCommand("ledger cancel")));
        say(Text.literal("Large payment of " + Fmt.money(amount, true) + " to " + m.group(1) + ". ")
                .formatted(Formatting.YELLOW).append(yes).append(Text.literal(" ")).append(no));
        return false;
    }

    private static void confirmPending() {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (pendingCommand == null || mc.getNetworkHandler() == null) {
            say(Text.literal("Nothing to confirm."));
            return;
        }
        String cmd = pendingCommand;
        pendingCommand = null;
        bypass = true;
        try {
            mc.getNetworkHandler().sendChatCommand(cmd);
        } finally {
            bypass = false;
        }
    }

    public static void say(Text body) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.inGameHud != null) {
            mc.inGameHud.getChatHud().addMessage(Text.literal("[Ledger+] ").formatted(Formatting.AQUA).append(body));
        }
    }
}
