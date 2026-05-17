package com.cpvp.modules;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ingame.InventoryScreen;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.slot.SlotActionType;

import java.util.concurrent.ThreadLocalRandom;

/**
 * AutoTotem:
 * - Uses inventory SWAP actions with human-like timing envelopes.
 * - Replaces totems after pop without impossible low outliers.
 * - Avoids interacting while closing inventory and only closes screens opened by this module.
 */
public class AutoTotemModule {

    private static final int HOTBAR_TOTEM_SLOT = 8;
    private static final int OFFHAND_SWAP_BUTTON = 40;

    private static final long RETOTEM_MIN_MS = 95L;
    private static final long RETOTEM_MAX_MS = 255L;
    private static final double RETOTEM_MEAN_MS = 148.0;
    private static final double RETOTEM_STDDEV_MS = 32.0;

    private static final long SLOT9_MIN_MS = 120L;
    private static final long SLOT9_MAX_MS = 290L;
    private static final double SLOT9_MEAN_MS = 178.0;
    private static final double SLOT9_STDDEV_MS = 38.0;

    private static final long INVENTORY_OPEN_MIN_MS = 55L;
    private static final long INVENTORY_OPEN_MAX_MS = 120L;
    private static final double INVENTORY_OPEN_MEAN_MS = 83.0;
    private static final double INVENTORY_OPEN_STDDEV_MS = 14.0;

    private static final long INVENTORY_CLOSE_MIN_MS = 70L;
    private static final long INVENTORY_CLOSE_MAX_MS = 170L;
    private static final double INVENTORY_CLOSE_MEAN_MS = 112.0;
    private static final double INVENTORY_CLOSE_STDDEV_MS = 21.0;

    private boolean enabled = false;
    private boolean openedInventoryByModule = false;
    private boolean prevOffhandWasTotem = false;

    private boolean pendingOffhand = false;
    private boolean pendingSlot9 = false;

    private long nextActionAt = 0L;
    private long closeAfterAt = 0L;


    public boolean isEnabled() {
        return enabled;
    }

    public void toggle() {
        setEnabled(!enabled);
    }

    public void setEnabled(boolean value) {
        enabled = value;
        if (!enabled) {
            resetState();
        }
    }

    public void onTick(MinecraftClient client) {
        if (!enabled || client.player == null || client.interactionManager == null) {
            return;
        }

        ClientPlayerEntity player = client.player;
        long now = System.currentTimeMillis();

        boolean offhandHasTotem = player.getOffHandStack().isOf(Items.TOTEM_OF_UNDYING);
        boolean slot9HasTotem = player.getInventory().getStack(HOTBAR_TOTEM_SLOT).isOf(Items.TOTEM_OF_UNDYING);

        boolean offhandPopped = prevOffhandWasTotem && !offhandHasTotem;
        prevOffhandWasTotem = offhandHasTotem;

        if (offhandPopped) {
            equipMainHandTotem(player);
            pendingOffhand = true;
            scheduleRetotem(now);
        }

        if (!slot9HasTotem) {
            pendingSlot9 = true;
            if (nextActionAt == 0L) {
                scheduleSlot9(now);
            }
        }

        boolean hasAnyTotem = findTotem(player, -1) != -1;
        if (!hasAnyTotem) {
            pendingOffhand = false;
            pendingSlot9 = false;
            tickCloseIfOwned(client, now);
            return;
        }

        if (!pendingOffhand && !pendingSlot9) {
            tickCloseIfOwned(client, now);
            return;
        }

        boolean invOpen = client.currentScreen instanceof InventoryScreen;
        if (!invOpen) {
            openInventory(client, player, now);
            return;
        }

        if (now < nextActionAt) {
            return;
        }

        // One action per timing window.
        if (pendingOffhand && !offhandHasTotem) {
            int slot = findTotem(player, -1);
            if (slot != -1) {
                swapToOffhand(client, player, slot);
            }
            pendingOffhand = false;
            scheduleRetotem(now);
            scheduleClose(now);
            return;
        }

        slot9HasTotem = player.getInventory().getStack(HOTBAR_TOTEM_SLOT).isOf(Items.TOTEM_OF_UNDYING);
        if (pendingSlot9 && !slot9HasTotem) {
            int slot = findTotem(player, HOTBAR_TOTEM_SLOT);
            if (slot != -1) {
                swapToHotbar(client, player, slot, HOTBAR_TOTEM_SLOT);
            }
            pendingSlot9 = false;
            scheduleSlot9(now);
            scheduleClose(now);
            return;
        }

        pendingOffhand = false;
        pendingSlot9 = false;
        keepDoubleHandIfPossible(player);
        tickCloseIfOwned(client, now);
    }

    private void openInventory(MinecraftClient client, ClientPlayerEntity player, long now) {
        client.execute(() -> client.setScreen(new InventoryScreen(player)));
        openedInventoryByModule = true;
        nextActionAt = now + sampleDelay(INVENTORY_OPEN_MEAN_MS, INVENTORY_OPEN_STDDEV_MS, INVENTORY_OPEN_MIN_MS, INVENTORY_OPEN_MAX_MS);
    }

    private void tickCloseIfOwned(MinecraftClient client, long now) {
        if (!openedInventoryByModule) {
            return;
        }
        if (closeAfterAt == 0L) {
            closeAfterAt = now + sampleDelay(INVENTORY_CLOSE_MEAN_MS, INVENTORY_CLOSE_STDDEV_MS, INVENTORY_CLOSE_MIN_MS, INVENTORY_CLOSE_MAX_MS);
            return;
        }
        if (now < closeAfterAt) {
            return;
        }
        if (client.currentScreen instanceof InventoryScreen) {
            client.execute(() -> client.setScreen(null));
        }
        openedInventoryByModule = false;
        closeAfterAt = 0L;
    }

    private void scheduleRetotem(long now) {
        nextActionAt = now + sampleDelay(RETOTEM_MEAN_MS, RETOTEM_STDDEV_MS, RETOTEM_MIN_MS, RETOTEM_MAX_MS);
    }

    private void scheduleSlot9(long now) {
        nextActionAt = now + sampleDelay(SLOT9_MEAN_MS, SLOT9_STDDEV_MS, SLOT9_MIN_MS, SLOT9_MAX_MS);
    }

    private void scheduleClose(long now) {
        closeAfterAt = now + sampleDelay(INVENTORY_CLOSE_MEAN_MS, INVENTORY_CLOSE_STDDEV_MS, INVENTORY_CLOSE_MIN_MS, INVENTORY_CLOSE_MAX_MS);
    }

    private long sampleDelay(double mean, double stddev, long min, long max) {
        double sampled = mean + ThreadLocalRandom.current().nextGaussian() * stddev;
        long clamped = (long) Math.max(min, Math.min(max, sampled));
        return Math.max(1L, clamped);
    }

    private int findTotem(ClientPlayerEntity player, int excludeSlot) {
        for (int slot = 0; slot < 36; slot++) {
            if (slot == excludeSlot) {
                continue;
            }
            ItemStack stack = player.getInventory().getStack(slot);
            if (stack.isOf(Items.TOTEM_OF_UNDYING)) {
                return slot;
            }
        }
        return -1;
    }

    private void swapToOffhand(MinecraftClient client, ClientPlayerEntity player, int inventorySlot) {
        int syncId = player.playerScreenHandler.syncId;
        int screenSlot = toScreenSlot(inventorySlot);
        client.interactionManager.clickSlot(syncId, screenSlot, OFFHAND_SWAP_BUTTON, SlotActionType.SWAP, player);
    }

    private void swapToHotbar(MinecraftClient client, ClientPlayerEntity player, int inventorySlot, int hotbarSlot) {
        int syncId = player.playerScreenHandler.syncId;
        int screenSlot = toScreenSlot(inventorySlot);
        client.interactionManager.clickSlot(syncId, screenSlot, hotbarSlot, SlotActionType.SWAP, player);
    }

    private int toScreenSlot(int invSlot) {
        return invSlot < 9 ? invSlot + 36 : invSlot;
    }

    private void resetState() {
        openedInventoryByModule = false;
        prevOffhandWasTotem = false;
        pendingOffhand = false;
        pendingSlot9 = false;
        nextActionAt = 0L;
        closeAfterAt = 0L;
    }

    private void equipMainHandTotem(ClientPlayerEntity player) {
        if (player.getInventory().getStack(HOTBAR_TOTEM_SLOT).isOf(Items.TOTEM_OF_UNDYING)) {
            player.getInventory().selectedSlot = HOTBAR_TOTEM_SLOT;
        }
    }

    private void keepDoubleHandIfPossible(ClientPlayerEntity player) {
        boolean offhandHasTotem = player.getOffHandStack().isOf(Items.TOTEM_OF_UNDYING);
        boolean slot9HasTotem = player.getInventory().getStack(HOTBAR_TOTEM_SLOT).isOf(Items.TOTEM_OF_UNDYING);
        if (offhandHasTotem && slot9HasTotem) {
            player.getInventory().selectedSlot = HOTBAR_TOTEM_SLOT;
        }
    }
}

