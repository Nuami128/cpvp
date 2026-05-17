package com.cpvp;

import com.cpvp.modules.AutoTotemModule;
import com.cpvp.modules.InstantAnchorModule;
import com.cpvp.modules.ObbyCrystalModule;
import com.cpvp.modules.PearlDashModule;
import com.cpvp.modules.PredictDoubleHandModule;
import com.cpvp.modules.SafeAnchorModule;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class CpvpClient implements ClientModInitializer {

    public static final Logger LOGGER = LoggerFactory.getLogger("cpvptier2");

    public static final AutoTotemModule AUTO_TOTEM = new AutoTotemModule();
    public static final InstantAnchorModule INSTANT_ANCHOR = new InstantAnchorModule();
    public static final SafeAnchorModule SAFE_ANCHOR = new SafeAnchorModule();
    public static final PredictDoubleHandModule PREDICT = new PredictDoubleHandModule();
    public static final ObbyCrystalModule OBBY_CRYSTAL = new ObbyCrystalModule();
    public static final PearlDashModule PEARL_DASH = new PearlDashModule();

    public static KeyBinding autoTotemKey;
    public static KeyBinding safeAnchorKey;
    public static KeyBinding masterToggleKey;
    public static KeyBinding instantAnchorKey;
    public static KeyBinding obbyCrystalKey;
    public static KeyBinding pearlDashKey;

    private static final KeyBinding.Category CPVP_CATEGORY =
            KeyBinding.Category.create(Identifier.of("cpvptier2", "main"));

    private static boolean allEnabled = false;
    private static boolean prevSafePressed = false;
    private static boolean prevPearlPressed = false;

    @Override
    public void onInitializeClient() {
        autoTotemKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.cpvptier2.autototem",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_Y,
                CPVP_CATEGORY
        ));

        safeAnchorKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.cpvptier2.safeanchor",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_V,
                CPVP_CATEGORY
        ));

        masterToggleKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.cpvptier2.master",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_B,
                CPVP_CATEGORY
        ));

        instantAnchorKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.cpvptier2.instantanchor",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_G,
                CPVP_CATEGORY
        ));

        obbyCrystalKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.cpvptier2.obbycrystal",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_X,
                CPVP_CATEGORY
        ));

        pearlDashKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.cpvptier2.pearldash",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_J,
                CPVP_CATEGORY
        ));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (client.player == null || client.world == null) {
                return;
            }

            while (masterToggleKey.wasPressed()) {
                allEnabled = !allEnabled;
                AUTO_TOTEM.setEnabled(allEnabled);
                INSTANT_ANCHOR.setEnabled(allEnabled);
                SAFE_ANCHOR.setEnabled(allEnabled);
                PREDICT.setEnabled(allEnabled);
                OBBY_CRYSTAL.setEnabled(allEnabled);
                PEARL_DASH.setEnabled(allEnabled);

                client.player.sendMessage(
                        Text.literal("§6cPvP Tier 2 §r" + (allEnabled ? "§aEnabled" : "§cDisabled")),
                        true
                );
            }

            while (autoTotemKey.wasPressed()) {
                AUTO_TOTEM.toggle();
                client.player.sendMessage(
                        Text.literal("§6AutoTotem §r" + (AUTO_TOTEM.isEnabled() ? "§aEnabled" : "§cDisabled")),
                        true
                );
            }

            while (instantAnchorKey.wasPressed()) {
                INSTANT_ANCHOR.setEnabled(!INSTANT_ANCHOR.isEnabled());
                client.player.sendMessage(
                        Text.literal("§6InstantAnchor §r" + (INSTANT_ANCHOR.isEnabled() ? "§aEnabled" : "§cDisabled")),
                        true
                );
            }

            while (obbyCrystalKey.wasPressed()) {
                OBBY_CRYSTAL.setEnabled(!OBBY_CRYSTAL.isEnabled());
                client.player.sendMessage(
                        Text.literal("§6ObbyCrystal §r" + (OBBY_CRYSTAL.isEnabled() ? "§aEnabled" : "§cDisabled")),
                        true
                );
            }

            boolean safePressed = safeAnchorKey.isPressed();
            if (safePressed && !prevSafePressed) {
                SAFE_ANCHOR.trigger(client);
            }
            prevSafePressed = safePressed;

            AUTO_TOTEM.onTick(client);
            INSTANT_ANCHOR.onTick(client);
            SAFE_ANCHOR.onTick(client);
            PREDICT.onTick(client);
            OBBY_CRYSTAL.onTick(client);
            boolean pearlPressed = pearlDashKey.isPressed();
            if (pearlPressed && !prevPearlPressed) {
                PEARL_DASH.trigger(client);
            }
            prevPearlPressed = pearlPressed;
        });

        LOGGER.info("[cPvP Tier 2] Loaded. B=master, Y=autototem, G=instant anchor, V=safe anchor trigger, X=obby crystal, J=pearl dash.");
    }
}

