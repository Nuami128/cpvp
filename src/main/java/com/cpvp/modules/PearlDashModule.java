package com.cpvp.modules;

import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.item.Items;
import net.minecraft.util.Hand;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.Heightmap;

/**
 * PearlDash:
 * - On trigger, finds the lowest reachable ground in a 90° forward FOV and throws a pearl there.
 */
public class PearlDashModule {

    private static final int MAX_RANGE = 32;
    private static final int FOV_HALF_DEGREES = 45;

    private boolean enabled = false;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean value) {
        enabled = value;
    }

    public void trigger(MinecraftClient client) {
        if (client.player == null || client.world == null || client.interactionManager == null) {
            return;
        }

        ClientPlayerEntity player = client.player;
        int pearlSlot = findHotbar(player, Items.ENDER_PEARL);
        if (pearlSlot == -1) {
            return;
        }

        Vec3d eye = player.getEyePos();
        float yaw = player.getYaw();

        BlockPos bestPos = null;
        int bestY = Integer.MAX_VALUE;

        for (int dx = -MAX_RANGE; dx <= MAX_RANGE; dx++) {
            for (int dz = -MAX_RANGE; dz <= MAX_RANGE; dz++) {
                BlockPos column = BlockPos.ofFloored(eye.x + dx, eye.y, eye.z + dz);
                double angle = yawToPos(yaw, eye, Vec3d.ofCenter(column));
                if (Math.abs(angle) > FOV_HALF_DEGREES) {
                    continue;
                }

                int topY = client.world.getTopY(Heightmap.Type.MOTION_BLOCKING_NO_LEAVES, column.getX(), column.getZ());
                for (int y = client.world.getBottomY() + 1; y < topY - 1; y++) {
                    BlockPos floor = new BlockPos(column.getX(), y, column.getZ());
                    if (!isPearlStandTarget(client, floor)) {
                        continue;
                    }
                    if (bestPos == null || floor.getY() < bestY) {
                        bestPos = floor;
                        bestY = floor.getY();
                    }
                    break;
                }
            }
        }

        if (bestPos == null) {
            return;
        }

        Vec3d target = Vec3d.ofCenter(bestPos).add(0.0, 1.2, 0.0);
        snapLookAt(player, target);
        player.getInventory().selectedSlot = pearlSlot;
        client.interactionManager.interactItem(player, Hand.MAIN_HAND);
    }

    private boolean isPearlStandTarget(MinecraftClient client, BlockPos floor) {
        BlockState floorState = client.world.getBlockState(floor);
        if (floorState.isAir()) {
            return false;
        }
        return client.world.getBlockState(floor.up()).isAir() && client.world.getBlockState(floor.up(2)).isAir();
    }

    private double yawToPos(float playerYaw, Vec3d eye, Vec3d target) {
        Vec3d d = target.subtract(eye);
        float targetYaw = (float) (Math.toDegrees(Math.atan2(d.z, d.x)) - 90.0);
        float diff = targetYaw - playerYaw;
        while (diff > 180.0f) {
            diff -= 360.0f;
        }
        while (diff < -180.0f) {
            diff += 360.0f;
        }
        return diff;
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

    private int findHotbar(ClientPlayerEntity player, net.minecraft.item.Item item) {
        for (int i = 0; i < 9; i++) {
            if (player.getInventory().getStack(i).isOf(item)) {
                return i;
            }
        }
        return -1;
    }
}

