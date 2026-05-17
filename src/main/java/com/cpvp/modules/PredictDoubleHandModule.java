package com.cpvp.modules;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.decoration.EndCrystalEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.screen.slot.SlotActionType;

/**
 * Predict Double Hand:
 * - Defensive pre-switch when dangerous crystal scenarios are detected.
 * - Keeps player actions unrestricted by preferring slot-9 prep, and only touching offhand when required.
 */
public class PredictDoubleHandModule {

    private static final int HOTBAR_TOTEM_SLOT = 8;
    private static final int OFFHAND_SWAP_BUTTON = 40;
    private static final double OPPONENT_RANGE = 7.5;
    private static final float LOW_HEALTH_THRESHOLD = 11.0f;
    private static final long ACTION_COOLDOWN_MS = 250L;

    private boolean enabled = false;
    private long lastActionAt = 0L;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean value) {
        enabled = value;
    }

    public void onTick(MinecraftClient client) {
        if (!enabled || client.player == null || client.world == null || client.interactionManager == null) {
            return;
        }

        long now = System.currentTimeMillis();
        if (now - lastActionAt < ACTION_COOLDOWN_MS) {
            return;
        }

        ClientPlayerEntity self = client.player;
        ClientWorld world = client.world;

        boolean crystalUnderSelf = isCrystalUnderPlayer(self, world);
        boolean opponentPressure = hasAggressiveOpponentNearby(self, world);
        boolean healthLow = self.getHealth() + self.getAbsorptionAmount() <= LOW_HEALTH_THRESHOLD;

        if (!crystalUnderSelf && !opponentPressure && !healthLow) {
            return;
        }

        // Prep slot 9 first (minimal behavior change).
        if (!self.getInventory().getStack(HOTBAR_TOTEM_SLOT).isOf(Items.TOTEM_OF_UNDYING)) {
            int source = findTotem(self, HOTBAR_TOTEM_SLOT);
            if (source != -1) {
                swapToHotbar(client, self, source, HOTBAR_TOTEM_SLOT);
                lastActionAt = now;
                return;
            }
        }

        // If danger is immediate (crystal under self), enforce offhand safety.
        if (crystalUnderSelf && !self.getOffHandStack().isOf(Items.TOTEM_OF_UNDYING)) {
            int source = findTotem(self, HOTBAR_TOTEM_SLOT);
            if (source == -1) {
                source = findTotem(self, -1);
            }
            if (source != -1) {
                swapToOffhand(client, self, source);
                lastActionAt = now;
                if (self.getInventory().getStack(HOTBAR_TOTEM_SLOT).isOf(Items.TOTEM_OF_UNDYING)) {
                    self.getInventory().selectedSlot = HOTBAR_TOTEM_SLOT;
                }
                return;
            }
            self.getInventory().selectedSlot = HOTBAR_TOTEM_SLOT;
            lastActionAt = now;
        } else if (crystalUnderSelf && self.getInventory().getStack(HOTBAR_TOTEM_SLOT).isOf(Items.TOTEM_OF_UNDYING)) {
            self.getInventory().selectedSlot = HOTBAR_TOTEM_SLOT;
        }
    }

    private boolean hasAggressiveOpponentNearby(ClientPlayerEntity self, ClientWorld world) {
        for (PlayerEntity player : world.getPlayers()) {
            if (player == self || player.isDead() || player.distanceTo(self) > OPPONENT_RANGE) {
                continue;
            }
            boolean aggressiveItem = player.getMainHandStack().isOf(Items.END_CRYSTAL)
                    || player.getMainHandStack().isOf(Items.NETHERITE_SWORD)
                    || player.getMainHandStack().isOf(Items.DIAMOND_SWORD)
                    || player.getMainHandStack().isOf(Items.NETHERITE_AXE)
                    || player.getMainHandStack().isOf(Items.DIAMOND_AXE);
            if (aggressiveItem) {
                return true;
            }
        }
        return false;
    }

    private boolean isCrystalUnderPlayer(ClientPlayerEntity self, ClientWorld world) {
        return world.getEntitiesByClass(EndCrystalEntity.class, self.getBoundingBox().expand(1.2, 2.5, 1.2),
                        crystal -> crystal.squaredDistanceTo(self) <= 6.25
                                && crystal.getY() <= self.getY() + 0.35).stream()
                .findAny()
                .isPresent();
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

    private void swapToHotbar(MinecraftClient client, ClientPlayerEntity player, int inventorySlot, int hotbarSlot) {
        int syncId = player.playerScreenHandler.syncId;
        int screenSlot = toScreenSlot(inventorySlot);
        client.interactionManager.clickSlot(syncId, screenSlot, hotbarSlot, SlotActionType.SWAP, player);
    }

    private void swapToOffhand(MinecraftClient client, ClientPlayerEntity player, int inventorySlot) {
        int syncId = player.playerScreenHandler.syncId;
        int screenSlot = toScreenSlot(inventorySlot);
        client.interactionManager.clickSlot(syncId, screenSlot, OFFHAND_SWAP_BUTTON, SlotActionType.SWAP, player);
    }

    private int toScreenSlot(int invSlot) {
        return invSlot < 9 ? invSlot + 36 : invSlot;
    }
}

