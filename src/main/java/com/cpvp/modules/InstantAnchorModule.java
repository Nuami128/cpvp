package com.cpvp.modules;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.item.Items;
import net.minecraft.state.property.Properties;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

/**
 * Instant Anchor:
 * - Detects real user anchor placement input.
 * - Fast deterministic sequence with charge verification/retry.
 */
public class InstantAnchorModule {

    private static final int POST_PLACE_WAIT_TICKS = 1;
    private static final int ACTION_COOLDOWN_TICKS = 1; // was 0, needs at least 1 tick between actions
    private static final int MAX_CHARGE_RETRIES = 4;
    private static final int DETONATE_DELAY_TICKS = 3;

    private boolean enabled = false;
    private boolean running = false;

    private int step = 0;
    private int waitTicks = 0;
    private int chargeRetries = 0;

    private BlockPos anchorPos;
    private BlockHitResult anchorHit;
    private Direction anchorFace = Direction.UP;
    private boolean triggerRunning = false;
    private BlockPos triggerAnchorPos;
    private Direction triggerFace = Direction.UP;
    private int triggerWaitTicks = 0;
    private int triggerStep = 0;

    private boolean prevUsePressed = false;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean value) {
        enabled = value;
        if (!enabled) {
            reset();
        }
    }

    public void onTick(MinecraftClient client) {
        if (!enabled || client.player == null || client.world == null || client.interactionManager == null) {
            return;
        }

        ClientPlayerEntity player = client.player;
        boolean usePressed = client.options.useKey.isPressed();
        boolean holdingAnchor = player.getMainHandStack().isOf(Items.RESPAWN_ANCHOR);

        if (triggerRunning) {
            tickTriggerBot(client, player);
            prevUsePressed = usePressed;
            return;
        }

        if (!running && usePressed && !prevUsePressed && client.crosshairTarget instanceof BlockHitResult hit
                && hit.getType() == HitResult.Type.BLOCK) {
            BlockPos lookedPos = hit.getBlockPos();
            BlockState lookedState = client.world.getBlockState(lookedPos);

            // Triggerbot: if we're directly looking at an anchor, handle it immediately.
            if (lookedState.isOf(Blocks.RESPAWN_ANCHOR)) {
                startTriggerBot(lookedPos, hit.getSide());
                prevUsePressed = usePressed;
                return;
            }

            if (holdingAnchor) {
                anchorPos = lookedPos.offset(hit.getSide());
                anchorFace = getFaceTowardPlayer(player, anchorPos);
                anchorHit = buildAnchorHit(anchorPos, anchorFace);
                running = true;
                step = 0;
                waitTicks = POST_PLACE_WAIT_TICKS;
                chargeRetries = 0;
            }
        }

        prevUsePressed = usePressed;

        if (running) {
            tickSequence(client, player);
        }
    }

    private void tickSequence(MinecraftClient client, ClientPlayerEntity player) {
        if (waitTicks > 0) {
            waitTicks--;
            return;
        }

        if (anchorPos == null || client.world == null || !client.world.getBlockState(anchorPos).isOf(Blocks.RESPAWN_ANCHOR)) {
            reset();
            return;
        }

        switch (step) {
            case 0 -> {
                int glowstoneSlot = findHotbar(player, Items.GLOWSTONE);
                if (glowstoneSlot == -1) {
                    reset();
                    return;
                }
                player.getInventory().selectedSlot = glowstoneSlot;
                step = 1;
                waitTicks = ACTION_COOLDOWN_TICKS;
            }
            case 1 -> {
                if (!tryInteractAnchor(client, player, anchorPos, anchorFace)) {
                    reset();
                    return;
                }
                step = 2;
                waitTicks = 1; // wait a tick before checking charge
            }
            case 2 -> {
                if (!hasAnchorCharge(client, anchorPos)) {
                    if (chargeRetries++ < MAX_CHARGE_RETRIES) {
                        step = 1;
                        waitTicks = 1; // was 0 — must wait at least 1 tick before retrying
                        return;
                    }
                    reset();
                    return;
                }

                int totemSlot = findHotbar(player, Items.TOTEM_OF_UNDYING);
                int detonateSlot = totemSlot != -1 ? totemSlot : findDetonateHotbarSlot(player);
                if (detonateSlot != -1) {
                    player.getInventory().selectedSlot = detonateSlot;
                } else {
                    reset();
                    return;
                }
                step = 3;
                waitTicks = DETONATE_DELAY_TICKS;
            }
            case 3 -> {
                tryInteractAnchor(client, player, anchorPos, Direction.UP);
                reset();
            }
            default -> reset();
        }
    }

    // Returns true and sends interact + swing (swing is required so Grim's Post check
    // sees a properly closed packet sequence).
    private boolean tryInteractAnchor(MinecraftClient client, ClientPlayerEntity player, BlockPos pos, Direction preferredFace) {
        BlockHitResult preferred = buildAnchorHit(pos, preferredFace);
        client.interactionManager.interactBlock(player, Hand.MAIN_HAND, preferred);
        player.swingHand(Hand.MAIN_HAND); // fixes Post flag
        return true;
    }

    private void startTriggerBot(BlockPos anchorBlockPos, Direction face) {
        triggerRunning = true;
        triggerAnchorPos = anchorBlockPos;
        triggerFace = face == null ? Direction.UP : face;
        triggerWaitTicks = 0;
        triggerStep = 0;
    }

    private void tickTriggerBot(MinecraftClient client, ClientPlayerEntity player) {
        if (!triggerRunning || triggerAnchorPos == null || client.world == null) {
            resetTriggerBot();
            return;
        }

        if (triggerWaitTicks > 0) {
            triggerWaitTicks--;
            return;
        }

        BlockState state = client.world.getBlockState(triggerAnchorPos);
        if (!state.isOf(Blocks.RESPAWN_ANCHOR)) {
            resetTriggerBot();
            return;
        }

        if (triggerStep == 0 && !hasAnchorCharge(client, triggerAnchorPos)) {
            int glowstoneSlot = findHotbar(player, Items.GLOWSTONE);
            if (glowstoneSlot == -1) {
                resetTriggerBot();
                return;
            }
            player.getInventory().selectedSlot = glowstoneSlot;
            tryInteractAnchor(client, player, triggerAnchorPos, triggerFace);
        }

        if (triggerStep == 0) {
            triggerStep = 1;
            triggerWaitTicks = DETONATE_DELAY_TICKS;
            return;
        }

        if (!hasAnchorCharge(client, triggerAnchorPos)) {
            resetTriggerBot();
            return;
        }

        int detonateSlot = findDetonateHotbarSlot(player);
        if (detonateSlot == -1) {
            resetTriggerBot();
            return;
        }
        player.getInventory().selectedSlot = detonateSlot;
        tryInteractAnchor(client, player, triggerAnchorPos, Direction.UP);
        resetTriggerBot();
    }

    private int findDetonateHotbarSlot(ClientPlayerEntity player) {
        int totemSlot = findHotbar(player, Items.TOTEM_OF_UNDYING);
        if (totemSlot != -1) {
            return totemSlot;
        }
        for (int i = 0; i < 9; i++) {
            if (!player.getInventory().getStack(i).isOf(Items.GLOWSTONE)
                    && !player.getInventory().getStack(i).isOf(Items.RESPAWN_ANCHOR)) {
                return i;
            }
        }
        return -1;
    }

    private void resetTriggerBot() {
        triggerRunning = false;
        triggerAnchorPos = null;
        triggerFace = Direction.UP;
        triggerWaitTicks = 0;
        triggerStep = 0;
    }

    private BlockHitResult buildAnchorHit(BlockPos pos, Direction face) {
        Vec3d vec = Vec3d.ofCenter(pos).add(Vec3d.of(face.getVector()).multiply(0.5));
        return new BlockHitResult(vec, face, pos, false);
    }

    private Direction getFaceTowardPlayer(ClientPlayerEntity player, BlockPos pos) {
        Vec3d diff = player.getEyePos().subtract(Vec3d.ofCenter(pos));
        if (Math.abs(diff.x) > Math.abs(diff.z)) {
            return diff.x > 0 ? Direction.EAST : Direction.WEST;
        }
        return diff.z > 0 ? Direction.SOUTH : Direction.NORTH;
    }

    private boolean hasAnchorCharge(MinecraftClient client, BlockPos pos) {
        if (client.world == null) {
            return false;
        }
        BlockState state = client.world.getBlockState(pos);
        return state.isOf(Blocks.RESPAWN_ANCHOR) && state.contains(Properties.CHARGES)
                && state.get(Properties.CHARGES) > 0;
    }

    private int findHotbar(ClientPlayerEntity player, net.minecraft.item.Item item) {
        for (int i = 0; i < 9; i++) {
            if (player.getInventory().getStack(i).isOf(item)) {
                return i;
            }
        }
        return -1;
    }

    private void reset() {
        running = false;
        step = 0;
        waitTicks = 0;
        chargeRetries = 0;
        anchorPos = null;
        anchorHit = null;
        anchorFace = Direction.UP;
        resetTriggerBot();
    }
}
