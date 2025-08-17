package jmr.simpleoreminer;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.player.PlayerBlockBreakEvents;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.enchantment.EnchantmentHelper;
import net.minecraft.enchantment.Enchantments;
import net.minecraft.entity.ExperienceOrbEntity;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.tag.TagKey;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.ActionResult;
import net.minecraft.util.Hand;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

public class som implements ModInitializer {
    public static final Logger LOGGER = LoggerFactory.getLogger("simpleoreminer");


    private static final Map<UUID, Boolean> svmEnabled = new HashMap<>();
    private static final Map<UUID, Long> lastToggleAt = new HashMap<>();
    private static final long toggleCooldownMs = 250;
    TagKey<Item> PICKAXES = TagKey.of(RegistryKeys.ITEM, Identifier.of("minecraft", "pickaxes"));

    private static final Set<Block> ORE_BLOCKS = new HashSet<>(Arrays.asList(
        // Overworld + Deepslate
        Blocks.IRON_ORE, Blocks.DEEPSLATE_IRON_ORE,
        Blocks.COAL_ORE, Blocks.DEEPSLATE_COAL_ORE,
        Blocks.GOLD_ORE, Blocks.DEEPSLATE_GOLD_ORE,
        Blocks.DIAMOND_ORE, Blocks.DEEPSLATE_DIAMOND_ORE,
        Blocks.EMERALD_ORE, Blocks.DEEPSLATE_EMERALD_ORE,
        Blocks.REDSTONE_ORE, Blocks.DEEPSLATE_REDSTONE_ORE,
        Blocks.LAPIS_ORE, Blocks.DEEPSLATE_LAPIS_ORE,
        Blocks.COPPER_ORE, Blocks.DEEPSLATE_COPPER_ORE,
        // Nether
        Blocks.NETHER_QUARTZ_ORE,
        Blocks.NETHER_GOLD_ORE,
        Blocks.ANCIENT_DEBRIS
    ));

    private static final Direction[] NEIGHBOR_DIRECTIONS = new Direction[]{
        Direction.NORTH, Direction.SOUTH, Direction.EAST,
        Direction.WEST, Direction.UP, Direction.DOWN
    };

    @Override
    public void onInitialize() {
        LOGGER.info("Registering som!");
        PlayerBlockBreakEvents.BEFORE.register(this::onBlockBreak);
        UseItemCallback.EVENT.register(this::toggleSVM);
    }

    private ActionResult toggleSVM(PlayerEntity player, World world, Hand hand) {
        if (world.isClient) {
            return ActionResult.PASS;
        }

        if (hand != Hand.MAIN_HAND) {
            return ActionResult.PASS;
        }

        if (!player.getMainHandStack().isIn(PICKAXES)) {
            return ActionResult.PASS;
        }

        if (!player.isSneaking()) {
            return ActionResult.PASS;
        }

        long now = System.currentTimeMillis();
        long last = lastToggleAt.getOrDefault(player.getUuid(), 0L);
        if (now - last < toggleCooldownMs) {
            return ActionResult.SUCCESS;
        }

        lastToggleAt.put(player.getUuid(), now);
        boolean newState = toggleEnabled(player);
        player.sendMessage(newState ? Text.translatable("text.som.on") : Text.translatable("text.som.off"), true);

        return ActionResult.PASS;
    }

    private boolean toggleEnabled(PlayerEntity player) {
        UUID id = player.getUuid();
        boolean next = !svmEnabled.getOrDefault(id, true);
        svmEnabled.put(id, next);
        return next;
    }

    private boolean isEnabled(PlayerEntity player) {
        return svmEnabled.getOrDefault(player.getUuid(), true);
    }

    private boolean onBlockBreak(World world, PlayerEntity player, BlockPos pos, BlockState state, @Nullable BlockEntity blockEntity) {
        if (world.isClient) return true;

        if (!isOreBlock(state.getBlock())) return true;

        if (!isEnabled(player)) return true;

        if (player.isCreative()) {
            return true;
        }

        if (!player.canHarvest(state)) {
            return true;
        }

        ItemStack tool = player.getMainHandStack();

        int maxBreaks = !tool.isDamageable() ? Integer.MAX_VALUE : Math.max(0, tool.getMaxDamage() - tool.getDamage());

        List<BlockPos> toBreak = findConnectedOres(world, pos, state.getBlock(), maxBreaks);

        if(toBreak.size() == 1) {
            return true;
        }

        int broken = 0;
        for (BlockPos orePos : toBreak) {
            if (broken >= maxBreaks) break;
            if (breakBlockWithFortuneAndXP(world, orePos, player, tool)) {
                damageTool(player, tool);
                broken++;
            }
        }
        return false;
    }


    private boolean isOreBlock(Block block) {
        return ORE_BLOCKS.contains(block);
    }

    private List<BlockPos> findConnectedOres(World world, BlockPos startPos, Block targetBlock, int maxBlocks) {
        LinkedHashSet<BlockPos> visited = new LinkedHashSet<>();
        Queue<BlockPos> queue = new ArrayDeque<>();
        queue.add(startPos);

        while (!queue.isEmpty() && visited.size() < maxBlocks) {
            BlockPos curr = queue.poll();
            if (visited.add(curr)) {
                for (Direction dir : NEIGHBOR_DIRECTIONS) {
                    if (visited.size() >= maxBlocks) break;

                    BlockPos neighbor = curr.offset(dir);
                    if (!world.isInBuildLimit(neighbor)) continue;

                    if (world.getBlockState(neighbor).getBlock() == targetBlock && !visited.contains(neighbor)) {
                        queue.add(neighbor);
                    }
                }
            }
        }
        return new ArrayList<>(visited);
    }

    private boolean breakBlockWithFortuneAndXP(World world, BlockPos pos, PlayerEntity player, ItemStack tool) {
        BlockState state = world.getBlockState(pos);
        if (state.isAir()) return false;

        if (player.isCreative()) {
            return world.breakBlock(pos, false, player);
        }

        List<ItemStack> drops = Block.getDroppedStacks(state, (ServerWorld) world, pos, null, player, tool);
        boolean blockRemoved = world.breakBlock(pos, false, player);

        if (!blockRemoved) return false;

        for (ItemStack drop : drops) {
            Block.dropStack(world, pos, drop);
        }

        int exp = getDroppedExperience(state, world, pos, tool);
        if (exp > 0 && world instanceof ServerWorld serverWorld) {
            serverWorld.spawnEntity(new ExperienceOrbEntity(serverWorld,
                    pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, exp));
        }
        return true;
    }

    private int getDroppedExperience(BlockState state, World world, BlockPos pos, ItemStack tool) {

        if (tool.hasEnchantments() && (EnchantmentHelper.getLevel(world.getRegistryManager().getOrThrow(RegistryKeys.ENCHANTMENT).getOrThrow(Enchantments.SILK_TOUCH), tool) > 0)) {
            return 0;
        }

        Block block = state.getBlock();

        if (block == Blocks.COAL_ORE || block == Blocks.DEEPSLATE_COAL_ORE) {
            return world.getRandom().nextInt(3); // 0 - 2
        }
        if (block == Blocks.DIAMOND_ORE || block == Blocks.DEEPSLATE_DIAMOND_ORE) {
            return world.getRandom().nextInt(5) + 3; // 3 - 7
        }
        if (block == Blocks.EMERALD_ORE || block == Blocks.DEEPSLATE_EMERALD_ORE) {
            return world.getRandom().nextInt(5) + 3; // 3 - 7
        }
        if (block == Blocks.LAPIS_ORE || block == Blocks.DEEPSLATE_LAPIS_ORE) {
            return world.getRandom().nextInt(4) + 2; // 2 - 5
        }
        if (block == Blocks.NETHER_QUARTZ_ORE) {
            return world.getRandom().nextInt(4) + 2; // 2 - 5
        }
        if (block == Blocks.NETHER_GOLD_ORE) {
            return world.getRandom().nextInt(2); // 0 - 1
        }
        if (block == Blocks.REDSTONE_ORE || block == Blocks.DEEPSLATE_REDSTONE_ORE) {
            return world.getRandom().nextInt(5) + 1; // 1 - 5
        }
        return 0;
    }

    private void damageTool(PlayerEntity player, ItemStack tool) {
        if (player.isCreative()) return;
        if (tool.isDamageable()) {
            tool.damage(1, player, EquipmentSlot.MAINHAND);
        }
    }
}