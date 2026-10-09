package dev.nuulz.chorus;

import java.util.regex.Pattern;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/**
 * Videos from someone's own computer. They travel inside the Minecraft connection (so a jukz relay
 * carries them too): uploaded to the server in small pieces, stored in the world folder by content
 * hash, and pulled by each client in chunks on demand. A screen plays one as "chorus-media:<sha>.<ext>".
 */
public final class Media {
    public static final String SCHEME = "chorus-media:";
    /** sha256 + an extension Chromium can play without proprietary codecs. */
    public static final Pattern NAME = Pattern.compile("[0-9a-f]{64}\\.(webm|mkv|mp4)");
    public static final int UPLOAD_PIECE = 30_000; // client → server payloads are capped at 32 KiB
    public static final int CHUNK = 256 * 1024;

    public static boolean isMedia(String url) {
        return url.startsWith(SCHEME) && NAME.matcher(url.substring(SCHEME.length())).matches();
    }

    public static String name(String url) { return url.substring(SCHEME.length()); }

    static <T extends CustomPacketPayload> CustomPacketPayload.Type<T> id(String path) {
        return new CustomPacketPayload.Type<>(Identifier.fromNamespaceAndPath(Chorus.ID, path));
    }

    /** Start an upload for the screen at [pos]. */
    public record Begin(BlockPos pos, String name, long size) implements CustomPacketPayload {
        public static final Type<Begin> TYPE = Media.id("upload_begin");
        public static final StreamCodec<RegistryFriendlyByteBuf, Begin> CODEC = StreamCodec.composite(
            BlockPos.STREAM_CODEC, Begin::pos, ByteBufCodecs.stringUtf8(80), Begin::name,
            ByteBufCodecs.VAR_LONG, Begin::size, Begin::new);
        @Override public Type<Begin> type() { return TYPE; }
    }

    /** The next piece of the current upload, in order. */
    public record Piece(byte[] data) implements CustomPacketPayload {
        public static final Type<Piece> TYPE = Media.id("upload_piece");
        public static final StreamCodec<RegistryFriendlyByteBuf, Piece> CODEC = StreamCodec.composite(
            ByteBufCodecs.byteArray(UPLOAD_PIECE), Piece::data, Piece::new);
        @Override public Type<Piece> type() { return TYPE; }
    }

    /** Server → uploader: bytes stored so far, or a final state. */
    public record Ack(long received, int state, String message) implements CustomPacketPayload {
        public static final int PROGRESS = 0, DONE = 1, FAILED = 2;
        public static final Type<Ack> TYPE = Media.id("upload_ack");
        public static final StreamCodec<RegistryFriendlyByteBuf, Ack> CODEC = StreamCodec.composite(
            ByteBufCodecs.VAR_LONG, Ack::received, ByteBufCodecs.VAR_INT, Ack::state,
            ByteBufCodecs.stringUtf8(200), Ack::message, Ack::new);
        @Override public Type<Ack> type() { return TYPE; }
    }

    /** Client asks for chunk [index] of a stored video. */
    public record Fetch(String name, int index) implements CustomPacketPayload {
        public static final Type<Fetch> TYPE = Media.id("fetch");
        public static final StreamCodec<RegistryFriendlyByteBuf, Fetch> CODEC = StreamCodec.composite(
            ByteBufCodecs.stringUtf8(80), Fetch::name, ByteBufCodecs.VAR_INT, Fetch::index, Fetch::new);
        @Override public Type<Fetch> type() { return TYPE; }
    }

    /** One chunk, with the file's total size; empty data and size -1 if the server doesn't have it. */
    public record Chunk(String name, int index, long size, byte[] data) implements CustomPacketPayload {
        public static final Type<Chunk> TYPE = Media.id("chunk");
        public static final StreamCodec<RegistryFriendlyByteBuf, Chunk> CODEC = StreamCodec.composite(
            ByteBufCodecs.stringUtf8(80), Chunk::name, ByteBufCodecs.VAR_INT, Chunk::index,
            ByteBufCodecs.VAR_LONG, Chunk::size, ByteBufCodecs.byteArray(CHUNK), Chunk::data, Chunk::new);
        @Override public Type<Chunk> type() { return TYPE; }
    }

    private Media() {}
}
