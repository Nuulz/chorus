package dev.nuulz.chorus;

import java.util.UUID;
import net.minecraft.core.BlockPos;
import net.minecraft.core.UUIDUtil;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;

/**
 * Live screen sharing: a player's window, encoded as live WebM on their machine, relayed by the
 * server to whoever has a screen showing "chorus-cast:<their uuid>". The stream is cut into WebM
 * elements: the header ("init") once, then clusters; a cluster whose first video frame is a keyframe
 * is a join point, where someone arriving late can start.
 */
public final class Cast {
    public static final String SCHEME = "chorus-cast:";
    public static final int INIT = 0, CLUSTER = 1, JOIN = 2;
    public static final int RELAY_PIECE = 256 * 1024;

    public static boolean isCast(String url) {
        if (!url.startsWith(SCHEME)) return false;
        try { UUID.fromString(url.substring(SCHEME.length())); return true; }
        catch (IllegalArgumentException e) { return false; }
    }

    public static UUID caster(String url) { return UUID.fromString(url.substring(SCHEME.length())); }

    /** Start showing my cast on the screen at [pos]. */
    public record Begin(BlockPos pos) implements CustomPacketPayload {
        public static final Type<Begin> TYPE = Media.id("cast_begin");
        public static final StreamCodec<RegistryFriendlyByteBuf, Begin> CODEC = StreamCodec.composite(
            BlockPos.STREAM_CODEC, Begin::pos, Begin::new);
        @Override public Type<Begin> type() { return TYPE; }
    }

    /** Part of one stream element, in order; [last] closes the element. */
    public record Piece(int kind, boolean last, byte[] data) implements CustomPacketPayload {
        public static final Type<Piece> TYPE = Media.id("cast_piece");
        public static final StreamCodec<RegistryFriendlyByteBuf, Piece> CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_INT, Piece::kind, ByteBufCodecs.BOOL, Piece::last,
            ByteBufCodecs.byteArray(Media.UPLOAD_PIECE), Piece::data, Piece::new);
        @Override public Type<Piece> type() { return TYPE; }
    }

    public record End() implements CustomPacketPayload {
        public static final Type<End> TYPE = Media.id("cast_end");
        public static final StreamCodec<RegistryFriendlyByteBuf, End> CODEC = StreamCodec.unit(new End());
        @Override public Type<End> type() { return TYPE; }
    }

    /** A viewer starts or stops receiving [caster]'s stream. */
    public record Watch(UUID caster, boolean on) implements CustomPacketPayload {
        public static final Type<Watch> TYPE = Media.id("cast_watch");
        public static final StreamCodec<RegistryFriendlyByteBuf, Watch> CODEC = StreamCodec.composite(
            UUIDUtil.STREAM_CODEC, Watch::caster, ByteBufCodecs.BOOL, Watch::on, Watch::new);
        @Override public Type<Watch> type() { return TYPE; }
    }

    /** Server → viewer: part of an element of [caster]'s stream; kind -1 means the cast ended. */
    public record Bytes(UUID caster, int kind, boolean last, byte[] data) implements CustomPacketPayload {
        public static final Type<Bytes> TYPE = Media.id("cast_bytes");
        public static final StreamCodec<RegistryFriendlyByteBuf, Bytes> CODEC = StreamCodec.composite(
            UUIDUtil.STREAM_CODEC, Bytes::caster, ByteBufCodecs.VAR_INT, Bytes::kind, ByteBufCodecs.BOOL, Bytes::last,
            ByteBufCodecs.byteArray(RELAY_PIECE), Bytes::data, Bytes::new);
        @Override public Type<Bytes> type() { return TYPE; }
    }

    private Cast() {}
}
