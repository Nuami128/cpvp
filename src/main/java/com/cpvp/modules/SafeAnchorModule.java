package com.cpvp.modules;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.state.property.Properties;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.item.Items;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

/**
 * Safe Anchor:
 * - One-key sequence with controlled snap points.
 * - Places anchor, places a safety glowstone in front, charges, snaps down, then looks up and detonates.
 */
public class SafeAnchorModule {

    private static final int STEP_COOLDOWN_TICKS = 0;
    private static final int MAX_CHARGE_RETRIES = 4;
    private static final int DETONATE_DELAY_TICKS = 3;

    private boolean enabled = false;
    private boolean running = false;

    private int step = 0;
    private int waitTicks = 0;
    private int chargeRetries = 0;

    private BlockPos anchorPos;
    private BlockPos safetyBlockPos;
    private BlockHitResult placeAnchorHit;
    private Direction frontFace;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean value) {
        enabled = value;
        if (!enabled) {
            reset();
        }
    }

    public void trigger(MinecraftClient client) {
        if (!enabled || running || client.player == null || !(client.crosshairTarget instanceof BlockHitResult hit)
                || hit.getType() != HitResult.Type.BLOCK) {
            return;
        }

        BlockPos lookedPos = hit.getBlockPos();
        boolean lookingAtAnchor = client.world != null && client.world.getBlockState(lookedPos).isOf(Blocks.RESPAWN_ANCHOR);
        anchorPos = lookingAtAnchor ? lookedPos : lookedPos.offset(hit.getSide());
        placeAnchorHit = hit;
        frontFace = getFrontFace(client.player, anchorPos);
        safetyBlockPos = anchorPos.offset(frontFace);

        running = true;
        step = lookingAtAnchor ? 3 : 0;
        waitTicks = 0;
        chargeRetries = 0;
    }

    public void onTick(MinecraftClient client) {
        if (!enabled || !running || client.player == null || client.interactionManager == null || client.world == null) {
            return;
        }

        if (waitTicks > 0) {
            waitTicks--;
            return;
        }

        ClientPlayerEntity player = client.player;
        switch (step) {
            case 0 -> {
                int anchorSlot = findHotbar(player, Items.RESPAWN_ANCHOR);
                if (anchorSlot == -1) {
                    reset();
                    return;
                }
                player.getInventory().selectedSlot = anchorSlot;
                advance();
            }
            case 1 -> {
                client.interactionManager.interactBlock(player, Hand.MAIN_HAND, placeAnchorHit);
                advance();
            }
            case 2 -> {
                if (!client.world.getBlockState(anchorPos).isOf(Blocks.RESPAWN_ANCHOR)) {
                    reset();
                    return;
                }
                int glowstoneSlot = findHotbar(player, Items.GLOWSTONE);
                if (glowstoneSlot == -1) {
                    reset();
                    return;
                }
                player.getInventory().selectedSlot = glowstoneSlot;
                advance();
            }
            case 3 -> {
                // Charge only if needed.
                if (!isAnchorChargedNow(client, anchorPos)) {
                    tryInteractAnchor(client, player, anchorPos, frontFace);
                }

                if (!isAnchorChargedNow(client, anchorPos)) {
                    if (chargeRetries++ < MAX_CHARGE_RETRIES) {
                        waitTicks = 0;
                        return;
                    }
                    reset();
                    return;
                }
                advance();
            }
            case 4 -> {
                // Place glowstone block in front after charging and before detonation.
                placeSafetyGlowstone(client, player);
                advance();
            }
            case 5 -> {
                int detonateSlot = findDetonateHotbarSlot(player);
                if (detonateSlot != -1) {
                    player.getInventory().selectedSlot = detonateSlot;
                } else {
                    reset();
                    return;
                }
                step++;
                waitTicks = DETONATE_DELAY_TICKS;
            }
            case 6 -> {
                // Snap down at the safety block area.
                if (safetyBlockPos == null && anchorPos != null && frontFace != null) {
                    safetyBlockPos = anchorPos.offset(frontFace);
                }
                if (safetyBlockPos == null) {
                    reset();
                    return;
                }
                BlockPos frontDown = safetyBlockPos.down();
                snapLookAt(player, Vec3d.ofCenter(frontDown));
                advance();
            }
            case 7 -> {
                // Then verify charge once more, snap up and detonate.
                if (!isAnchorChargedNow(client, anchorPos)) {
                    if (chargeRetries++ < MAX_CHARGE_RETRIES) {
                        step = 3;
                        waitTicks = 0;
                        return;
                    }
                    reset();
                    return;
                }

                Vec3d upTarget = Vec3d.ofCenter(anchorPos).add(0.0, 0.7, 0.0);
                snapLookAt(player, upTarget);

                tryInteractAnchor(client, player, anchorPos, Direction.UP);
                reset();
            }
            default -> reset();
        }

        if (!running) {
            return;
        }
    }


    private void tryInteractAnchor(MinecraftClient client, ClientPlayerEntity player, BlockPos pos, Direction face) {
        BlockHitResult hit = buildAnchorHit(pos, face);
        client.interactionManager.interactBlock(player, Hand.MAIN_HAND, hit);
    }

    private BlockHitResult buildAnchorHit(BlockPos pos, Direction face) {
        Vec3d vec = Vec3d.ofCenter(pos).add(Vec3d.of(face.getVector()).multiply(0.5));
        return new BlockHitResult(vec, face, pos, false);
    }

    private void placeSafetyGlowstone(MinecraftClient client, ClientPlayerEntity player) {
        if (client.world == null || safetyBlockPos == null) {
            return;
        }

        int glowstoneSlot = findHotbar(player, Items.GLOWSTONE);
        if (glowstoneSlot == -1) {
            return;
        }
        player.getInventory().selectedSlot = glowstoneSlot;

        BlockState atSafety = client.world.getBlockState(safetyBlockPos);
        if (!atSafety.isAir()) {
            return;
        }

        BlockPos supportBasePos = safetyBlockPos.down();
        BlockState supportState = client.world.getBlockState(supportBasePos);
        if (supportState.isAir()) {
            return;
        }

        BlockHitResult safetyPlaceHit = new BlockHitResult(
                Vec3d.ofCenter(supportBasePos).add(0.0, 0.5, 0.0),
                Direction.UP,
                supportBasePos,
                false
        );
        client.interactionManager.interactBlock(player, Hand.MAIN_HAND, safetyPlaceHit);
    }


    private boolean isAnchorChargedNow(MinecraftClient client, BlockPos pos) {
        if (client.world == null) {
            return false;
        }
        BlockState state = client.world.getBlockState(pos);
        return state.isOf(Blocks.RESPAWN_ANCHOR) && state.contains(Properties.CHARGES)
                && state.get(Properties.CHARGES) > 0;
    }

    private void snapLookAt(ClientPlayerEntity player, Vec3d target) {
        Vec3d eye = player.getEyePos();
        Vec3d delta = target.subtract(eye);
        double horizontal = Math.sqrt(delta.x * delta.x + delta.z * delta.z);
        float yaw = (float) (Math.toDegrees(Math.atan2(delta.z, delta.x)) - 90.0);
        float pitch = (float) (-Math.toDegrees(Math.atan2(delta.y, horizontal)));
        player.setYaw(yaw);
        player.setPitch(pitch);
    }

    private Direction getFrontFace(ClientPlayerEntity player, BlockPos targetPos) {
        Vec3d diff = new Vec3d(player.getX(), player.getY(), player.getZ()).subtract(Vec3d.ofCenter(targetPos));
        if (Math.abs(diff.x) > Math.abs(diff.z)) {
            return diff.x > 0 ? Direction.EAST : Direction.WEST;
        }
        return diff.z > 0 ? Direction.SOUTH : Direction.NORTH;
    }

    private int findHotbar(ClientPlayerEntity player, net.minecraft.item.Item item) {
        for (int i = 0; i < 9; i++) {
            if (player.getInventory().getStack(i).isOf(item)) {
                return i;
            }
        }
        return -1;
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

    private void advance() {
        step++;
        waitTicks = STEP_COOLDOWN_TICKS;
    }

    private void reset() {
        running = false;
        step = 0;
        waitTicks = 0;
        chargeRetries = 0;
        anchorPos = null;
        safetyBlockPos = null;
        placeAnchorHit = null;
        frontFace = null;
    }
}

