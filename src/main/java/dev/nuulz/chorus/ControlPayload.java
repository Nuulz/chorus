package dev.nuulz.chorus;

import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.codec.ByteBufCodecs;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.Identifier;

/** A player asking the server to change a screen. The server decides; clients only follow its state. */
public record ControlPayload(BlockPos pos, int action, String url, long ms) implements CustomPacketPayload {
    public static final int SET_URL = 0, PLAY = 1, PAUSE = 2, SEEK = 3, STOP = 4, SIZE = 5;

    public static final Type<ControlPayload> TYPE = new Type<>(Identifier.fromNamespaceAndPath(Chorus.ID, "control"));
    public static final StreamCodec<RegistryFriendlyByteBuf, ControlPayload> CODEC = StreamCodec.composite(
        BlockPos.STREAM_CODEC, ControlPayload::pos,
        ByteBufCodecs.VAR_INT, ControlPayload::action,
        ByteBufCodecs.stringUtf8(UrlPolicy.MAX_LENGTH), ControlPayload::url,
        ByteBufCodecs.VAR_LONG, ControlPayload::ms,
        ControlPayload::new);

    @Override public Type<? extends CustomPacketPayload> type() { return TYPE; }
}
