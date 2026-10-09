package dev.nuulz.chorus;

import com.mojang.serialization.MapCodec;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.StateDefinition;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.level.block.state.properties.EnumProperty;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.BlockHitResult;

/** The screen's base. The picture is drawn by the client above it, facing [FACING]. */
public class ScreenBlock extends BaseEntityBlock {
    public static final EnumProperty<Direction> FACING = BlockStateProperties.HORIZONTAL_FACING;
    public static final MapCodec<ScreenBlock> CODEC = simpleCodec(ScreenBlock::new);
    /** Opens the control panel; set by the client entrypoint, absent on dedicated servers. */
    public static java.util.function.Consumer<ScreenBlockEntity> openControls = s -> { };

    public ScreenBlock(Properties props) {
        super(props);
        registerDefaultState(stateDefinition.any().setValue(FACING, Direction.NORTH));
    }

    @Override protected MapCodec<? extends BaseEntityBlock> codec() { return CODEC; }

    @Override
    protected void createBlockStateDefinition(StateDefinition.Builder<Block, BlockState> b) {
        b.add(FACING);
    }

    @Override
    public BlockState getStateForPlacement(BlockPlaceContext ctx) {
        Level level = ctx.getLevel();
        if (!level.isClientSide()) {
            Chorus.LOADED.removeIf(BlockEntity::isRemoved);
            if (Chorus.LOADED.size() >= Chorus.maxScreens) {
                if (ctx.getPlayer() != null)
                    ctx.getPlayer().sendSystemMessage(Component.translatable("chorus.too_many", Chorus.maxScreens));
                return null;
            }
        }
        return defaultBlockState().setValue(FACING, ctx.getHorizontalDirection().getOpposite());
    }

    @Override
    public void setPlacedBy(Level level, BlockPos pos, BlockState state, LivingEntity by, ItemStack stack) {
        if (by instanceof Player p && level.getBlockEntity(pos) instanceof ScreenBlockEntity s) {
            s.owner = p.getUUID().toString();
            s.setChanged();
        }
    }

    @Override
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
        if (level.isClientSide() && level.getBlockEntity(pos) instanceof ScreenBlockEntity s) openControls.accept(s);
        return InteractionResult.SUCCESS;
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new ScreenBlockEntity(pos, state);
    }
}
