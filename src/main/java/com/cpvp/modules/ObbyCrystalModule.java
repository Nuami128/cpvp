package com.cpvp.modules;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.Entity;
import net.minecraft.entity.decoration.EndCrystalEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Items;
import net.minecraft.util.Hand;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;

import java.util.Comparator;
import java.util.List;

/**
 * ObbyCrystal (X):
 * - Targets closest player in 90° FOV.
 * - Knockback sword tap -> place obsidian near target -> place/detonate up to 2 crystals per obsidian.
 * - Adds extra upper obsidian when target is floating ("butterfly").
 */
public class ObbyCrystalModule {

    private static final double TARGET_RANGE = 7.0;
    private static final double FOV_HALF_DEGREES = 45.0;
    private static final int MAX_CRYSTALS_PER_OBBY = 2;
    private static final int ACTION_DELAY_TICKS = 0;

    private enum State {
        HIT, PLACE_OBBY, PLACE_CRYSTAL, DETONATE
    }

    private boolean enabled = false;
    private int waitTicks = 0;
    private State state = State.HIT;
    private BlockPos activeObbyPos;
    private int crystalsUsedOnObby = 0;

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
        if (waitTicks > 0) {
            waitTicks--;
            return;
        }

        ClientPlayerEntity self = client.player;
        ClientWorld world = client.world;
        PlayerEntity target = findTarget(self, world);
        if (target == null) {
            return;
        }

        switch (state) {
            case HIT -> {
                hitTargetWithKnockbackSword(client, self, target);
                keepDoubleHand(self);
                state = State.PLACE_OBBY;
                waitTicks = ACTION_DELAY_TICKS;
            }
            case PLACE_OBBY -> {
                BlockPos obby = findBestObbyPos(client, target);
                if (obby == null) {
                    return;
                }
                if (!placeObsidian(client, self, obby)) {
                    return;
                }
                activeObbyPos = obby;
                crystalsUsedOnObby = 0;
                placeButterflyObbyIfFloating(client, self, target);
                state = State.PLACE_CRYSTAL;
                waitTicks = ACTION_DELAY_TICKS;
            }
            case PLACE_CRYSTAL -> {
                if (activeObbyPos == null) {
                    state = State.PLACE_OBBY;
                    return;
                }
                if (!placeCrystal(client, self, activeObbyPos)) {
                    state = State.PLACE_OBBY;
                    return;
                }
                state = State.DETONATE;
                waitTicks = ACTION_DELAY_TICKS;
            }
            case DETONATE -> {
                if (detonateNearestCrystal(client, self, activeObbyPos)) {
                    crystalsUsedOnObby++;
                }
                keepDoubleHand(self);
                if (crystalsUsedOnObby < MAX_CRYSTALS_PER_OBBY) {
                    state = State.PLACE_CRYSTAL;
                } else {
                    state = State.PLACE_OBBY;
                }
                waitTicks = ACTION_DELAY_TICKS;
            }
        }
    }

    private PlayerEntity findTarget(ClientPlayerEntity self, ClientWorld world) {
        return world.getPlayers().stream()
                .filter(p -> p != self && !p.isDead() && p.distanceTo(self) <= TARGET_RANGE)
                .filter(p -> Math.abs(yawDiff(self, new Vec3d(p.getX(), p.getY(), p.getZ()))) <= FOV_HALF_DEGREES)
                .min(Comparator.comparingDouble(self::distanceTo))
                .orElse(null);
    }

    private float yawDiff(ClientPlayerEntity self, Vec3d target) {
        Vec3d d = target.subtract(self.getEyePos());
        float targetYaw = (float) (Math.toDegrees(Math.atan2(d.z, d.x)) - 90.0);
        float diff = targetYaw - self.getYaw();
        while (diff > 180.0f) diff -= 360.0f;
        while (diff < -180.0f) diff += 360.0f;
        return diff;
    }

    private void hitTargetWithKnockbackSword(MinecraftClient client, ClientPlayerEntity self, PlayerEntity target) {
        int swordSlot = findKnockbackSword(self);
        if (swordSlot != -1) {
            self.getInventory().selectedSlot = swordSlot;
        }
        snapLookAt(self, target.getEyePos());
        client.interactionManager.attackEntity(self, target);
        self.swingHand(Hand.MAIN_HAND);
    }

    private int findKnockbackSword(ClientPlayerEntity self) {
        for (int i = 0; i < 9; i++) {
            var stack = self.getInventory().getStack(i);
            if (!stack.isOf(Items.NETHERITE_SWORD) && !stack.isOf(Items.DIAMOND_SWORD)
                    && !stack.isOf(Items.IRON_SWORD) && !stack.isOf(Items.STONE_SWORD)
                    && !stack.isOf(Items.WOODEN_SWORD) && !stack.isOf(Items.GOLDEN_SWORD)) {
                continue;
            }
            if (stack.getEnchantments().toString().contains("knockback")) {
                return i;
            }
        }
        return -1;
    }

    private BlockPos findBestObbyPos(MinecraftClient client, PlayerEntity target) {
        BlockPos base = BlockPos.ofFloored(target.getX(), target.getY() - 0.1, target.getZ());
        Direction back = target.getHorizontalFacing().getOpposite();
        BlockPos[] candidates = new BlockPos[] {
                base.offset(back.rotateYClockwise()),
                base.offset(back.rotateYCounterclockwise()),
                base.offset(back)
        };

        for (BlockPos c : candidates) {
            if (canPlaceObsidian(client, c)) {
                return c;
            }
        }
        for (Direction d : Direction.Type.HORIZONTAL) {
            BlockPos c = base.offset(d);
            if (canPlaceObsidian(client, c)) {
                return c;
            }
        }
        return null;
    }

    private boolean placeObsidian(MinecraftClient client, ClientPlayerEntity self, BlockPos pos) {
        int obbySlot = findHotbar(self, Items.OBSIDIAN);
        if (obbySlot == -1) {
            return false;
        }
        BlockPos support = pos.down();
        if (client.world.getBlockState(support).isAir()) {
            return false;
        }

        self.getInventory().selectedSlot = obbySlot;
        snapLookAt(self, Vec3d.ofCenter(support).add(0.0, 0.5, 0.0));
        BlockHitResult hit = new BlockHitResult(Vec3d.ofCenter(support).add(0.0, 0.5, 0.0), Direction.UP, support, false);
        client.interactionManager.interactBlock(self, Hand.MAIN_HAND, hit);
        return client.world.getBlockState(pos).isOf(Blocks.OBSIDIAN);
    }

    private void placeButterflyObbyIfFloating(MinecraftClient client, ClientPlayerEntity self, PlayerEntity target) {
        BlockPos feet = BlockPos.ofFloored(target.getX(), target.getY() - 0.1, target.getZ());
        if (!client.world.getBlockState(feet.down()).isAir()) {
            return;
        }
        BlockPos up1 = feet.up(2);
        BlockPos up2 = feet.up(3);
        if (canPlaceObsidian(client, up1)) {
            placeObsidian(client, self, up1);
        }
        if (canPlaceObsidian(client, up2)) {
            placeObsidian(client, self, up2);
        }
    }

    private boolean placeCrystal(MinecraftClient client, ClientPlayerEntity self, BlockPos obbyPos) {
        if (!client.world.getBlockState(obbyPos).isOf(Blocks.OBSIDIAN) && !client.world.getBlockState(obbyPos).isOf(Blocks.BEDROCK)) {
            return false;
        }
        BlockPos above = obbyPos.up();
        if (!client.world.getBlockState(above).isAir() || !client.world.getBlockState(above.up()).isAir()) {
            return false;
        }
        int crystalSlot = findHotbar(self, Items.END_CRYSTAL);
        if (crystalSlot == -1) {
            return false;
        }
        self.getInventory().selectedSlot = crystalSlot;
        snapLookAt(self, Vec3d.ofCenter(obbyPos).add(0.0, 0.5, 0.0));
        BlockHitResult hit = new BlockHitResult(Vec3d.ofCenter(obbyPos).add(0.0, 0.5, 0.0), Direction.UP, obbyPos, false);
        client.interactionManager.interactBlock(self, Hand.MAIN_HAND, hit);
        return true;
    }

    private boolean detonateNearestCrystal(MinecraftClient client, ClientPlayerEntity self, BlockPos aroundObby) {
        if (aroundObby == null) {
            return false;
        }
        Box box = new Box(aroundObby).expand(2.5, 2.5, 2.5);
        List<EndCrystalEntity> crystals = client.world.getEntitiesByClass(EndCrystalEntity.class, box, Entity::isAlive);
        if (crystals.isEmpty()) {
            return false;
        }
        EndCrystalEntity crystal = crystals.stream().min(Comparator.comparingDouble(self::distanceTo)).orElse(null);
        if (crystal == null) {
            return false;
        }
        int swordSlot = findKnockbackSword(self);
        if (swordSlot != -1) {
            self.getInventory().selectedSlot = swordSlot;
        }
        snapLookAt(self, new Vec3d(crystal.getX(), crystal.getY(), crystal.getZ()));
        client.interactionManager.attackEntity(self, crystal);
        self.swingHand(Hand.MAIN_HAND);
        return true;
    }

    private boolean canPlaceObsidian(MinecraftClient client, BlockPos pos) {
        BlockState at = client.world.getBlockState(pos);
        if (!at.isAir()) {
            return false;
        }
        BlockPos support = pos.down();
        return !client.world.getBlockState(support).isAir();
    }

    private void keepDoubleHand(ClientPlayerEntity self) {
        int totemSlot = findHotbar(self, Items.TOTEM_OF_UNDYING);
        if (totemSlot != -1 && self.getOffHandStack().isOf(Items.TOTEM_OF_UNDYING)) {
            self.getInventory().selectedSlot = totemSlot;
        }
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

    private void reset() {
        waitTicks = 0;
        state = State.HIT;
        activeObbyPos = null;
        crystalsUsedOnObby = 0;
    }
}

