package dev.nuulz.chorus;

import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundBlockEntityDataPacket;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.storage.ValueInput;
import net.minecraft.world.level.storage.ValueOutput;

/**
 * The authoritative playback state. Time is anchored to the world's game time (ticks, which every
 * client receives from the server), so position = baseMs + elapsed ticks while playing: nobody's
 * local clock or buffering decides where the video is, and a late joiner computes the same spot.
 */
public class ScreenBlockEntity extends BlockEntity {
    public String url = "";
    public boolean playing;
    public long baseMs;
    public long baseTick;
    public String owner = "";
    /** The picture's width in blocks; the height follows the 16:9 shape. The resolution doesn't change. */
    public int size = 3;
    public static final int MAX_SIZE = 24;
    /** Bumped on every change, so clients know to resync instead of waiting for drift. */
    public int revision;

    /** Client-side hook so the renderer/manager can track loaded screens; null on servers. */
    public static java.util.function.Consumer<ScreenBlockEntity> clientLoaded;
    public static java.util.function.Consumer<ScreenBlockEntity> clientUnloaded;

    public ScreenBlockEntity(BlockPos pos, BlockState state) {
        super(Chorus.SCREEN_ENTITY, pos, state);
    }

    public long positionMs(long gameTime) {
        return playing ? baseMs + Math.max(0, gameTime - baseTick) * 50 : baseMs;
    }

    void load(String newUrl) {
        url = newUrl;
        playing = !newUrl.isEmpty();
        baseMs = 0;
        changed();
    }

    void play(boolean on) {
        if (url.isEmpty() || on == playing) return;
        baseMs = positionMs(now());
        playing = on;
        changed();
    }

    void setSize(int blocks) {
        size = Math.clamp(blocks, 1, MAX_SIZE);
        changed();
    }

    void seek(long ms) {
        if (url.isEmpty()) return;
        baseMs = Math.max(0, ms);
        changed();
    }

    private long now() { return level.getGameTime(); }

    private void changed() {
        baseTick = now();
        revision++;
        setChanged();
        level.sendBlockUpdated(worldPosition, getBlockState(), getBlockState(), Block.UPDATE_CLIENTS);
    }

    @Override
    protected void saveAdditional(ValueOutput out) {
        out.putString("url", url);
        out.putBoolean("playing", playing);
        out.putLong("base_ms", baseMs);
        out.putLong("base_tick", baseTick);
        out.putString("owner", owner);
        out.putInt("size", size);
        out.putInt("revision", revision);
    }

    @Override
    protected void loadAdditional(ValueInput in) {
        url = in.getStringOr("url", "");
        playing = in.getBooleanOr("playing", false);
        baseMs = in.getLongOr("base_ms", 0);
        baseTick = in.getLongOr("base_tick", 0);
        owner = in.getStringOr("owner", "");
        size = Math.clamp(in.getIntOr("size", 3), 1, MAX_SIZE);
        revision = in.getIntOr("revision", 0);
    }

    @Override
    public Packet<ClientGamePacketListener> getUpdatePacket() {
        return ClientboundBlockEntityDataPacket.create(this);
    }

    @Override
    public CompoundTag getUpdateTag(HolderLookup.Provider registries) {
        return saveWithoutMetadata(registries);
    }

    @Override
    public void clearRemoved() {
        super.clearRemoved();
        if (level == null) return;
        if (level.isClientSide()) { if (clientLoaded != null) clientLoaded.accept(this); }
        else Chorus.LOADED.add(this);
    }

    @Override
    public void setRemoved() {
        super.setRemoved();
        if (level == null) return;
        if (level.isClientSide()) { if (clientUnloaded != null) clientUnloaded.accept(this); }
        else Chorus.LOADED.remove(this);
    }
}
