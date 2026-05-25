package jmr.simpleoreminer;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.tags.TagKey;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

public class som implements ModInitializer {
    public static final Logger LOGGER = LoggerFactory.getLogger("simpleoreminer");

    private static final Map<UUID, Boolean> svmEnabled = new HashMap<>();
    private static final Map<UUID, Long> lastToggleAt = new HashMap<>();
    private static final long TOGGLE_COOLDOWN_MS = 250;

    private final TagKey<Item> PICKAXES = TagKey.create(
            Registries.ITEM,
            Identifier.fromNamespaceAndPath("minecraft", "pickaxes")
    );

    private final TagKey<Block> ORES = TagKey.create(Registries.BLOCK, Identifier.fromNamespaceAndPath("c", "ores"));

    private static final Direction[] NEIGHBOR_DIRECTIONS = {
            Direction.NORTH, Direction.SOUTH, Direction.EAST,
            Direction.WEST, Direction.UP, Direction.DOWN
    };

    @Override
    public void onInitialize() {
        LOGGER.info("Registering som!");
        PlayerBlockBreakEvents.BEFORE.register(this::onBlockBreak);
        UseItemCallback.EVENT.register(this::toggleSVM);

        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            UUID id = handler.player.getUUID();
            svmEnabled.remove(id);
            lastToggleAt.remove(id);
        });
    }

    private InteractionResult toggleSVM(Player player, Level level, InteractionHand hand) {
        if (level.isClientSide()) return InteractionResult.PASS;
        if (hand != InteractionHand.MAIN_HAND) return InteractionResult.PASS;
        if (!player.getItemInHand(InteractionHand.MAIN_HAND).is(PICKAXES)) return InteractionResult.PASS;
        if (!player.isCrouching()) return InteractionResult.PASS;

        long now = System.currentTimeMillis();
        if (now - lastToggleAt.getOrDefault(player.getUUID(), 0L) < TOGGLE_COOLDOWN_MS) {
            return InteractionResult.SUCCESS;
        }

        lastToggleAt.put(player.getUUID(), now);
        boolean newState = toggleEnabled(player);
        player.sendOverlayMessage(
                newState ? Component.translatable("text.som.on")
                        : Component.translatable("text.som.off")
        );

        return InteractionResult.PASS;
    }

    private boolean toggleEnabled(Player player) {
        UUID id = player.getUUID();
        boolean next = !svmEnabled.getOrDefault(id, true);
        svmEnabled.put(id, next);
        return next;
    }

    private boolean isEnabled(Player player) {
        return svmEnabled.getOrDefault(player.getUUID(), true);
    }

    private boolean onBlockBreak(Level level, Player player, BlockPos pos,
                                 BlockState state, @Nullable BlockEntity blockEntity) {
        if (level.isClientSide()) return true;
        if (!state.is(ORES)) return true;
        if (!isEnabled(player)) return true;
        if (player.isCreative()) return true;
        if (!player.hasCorrectToolForDrops(state)) return true;

        ItemStack tool = player.getItemInHand(InteractionHand.MAIN_HAND);

        int maxBreaks = tool.isDamageableItem()
                ? Math.max(0, tool.getMaxDamage() - tool.getDamageValue())
                : Integer.MAX_VALUE;

        if (maxBreaks == 0) return true;

        List<BlockPos> toBreak = findConnectedOres(level, pos, state.getBlock(), maxBreaks);

        if (toBreak.size() <= 1) return true;

        for (BlockPos orePos : toBreak) {
            if (breakBlock(level, orePos, player, tool)) {
                damageTool(player, tool);
            }
        }
        return false;
    }

    private List<BlockPos> findConnectedOres(Level world, BlockPos startPos,
                                             Block targetBlock, int maxBlocks) {
        LinkedHashSet<BlockPos> visited = new LinkedHashSet<>();
        Queue<BlockPos> queue = new ArrayDeque<>();
        queue.add(startPos);

        while (!queue.isEmpty() && visited.size() < maxBlocks) {
            BlockPos curr = queue.poll();
            if (visited.add(curr)) {
                for (Direction dir : NEIGHBOR_DIRECTIONS) {
                    if (visited.size() >= maxBlocks) break;

                    BlockPos neighbor = curr.offset(dir.getUnitVec3i());
                    if (!world.isInWorldBounds(neighbor)) continue;

                    if (world.getBlockState(neighbor).getBlock() == targetBlock
                            && !visited.contains(neighbor)) {
                        queue.add(neighbor);
                    }
                }
            }
        }
        return new ArrayList<>(visited);
    }

    private boolean breakBlock(Level level, BlockPos pos, Player player, ItemStack tool) {
        BlockState state = level.getBlockState(pos);
        if (state.isAir()) return false;

        BlockEntity be = level.getBlockEntity(pos);

        boolean removed = level.destroyBlock(pos, false, player);
        if (!removed) return false;

        Block.dropResources(state, level, pos, be, player, tool);

        return true;
    }

    private void damageTool(Player player, ItemStack tool) {
        if (player.isCreative()) return;
        if (tool.isDamageableItem()) {
            tool.hurtAndBreak(1, player, InteractionHand.MAIN_HAND);
        }
    }
}