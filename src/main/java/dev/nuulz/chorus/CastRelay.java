package dev.nuulz.chorus;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

/** The server side of {@link Cast}: keeps each stream's header and recent clusters, and fans them out. */
final class CastRelay {
    private static final int MAX_BUFFERED = 24 * 1024 * 1024;

    private static final class Stream {
        byte[] init;
        final List<byte[]> clusters = new ArrayList<>(); // from the latest join point on
        final Set<UUID> watchers = new HashSet<>();
        final ByteArrayOutputStream building = new ByteArrayOutputStream();
        int buffered;
    }

    private static final Map<UUID, Stream> streams = new ConcurrentHashMap<>();

    static void begin(ServerPlayer player, Cast.Begin b) {
        if (!(player.level().getBlockEntity(b.pos()) instanceof ScreenBlockEntity s) || !Chorus.canControl(player, s)) {
            player.sendSystemMessage(Component.translatable("chorus.no_permission"));
            return;
        }
        streams.computeIfAbsent(player.getUUID(), u -> new Stream());
        s.load(Cast.SCHEME + player.getUUID());
    }

    static void piece(ServerPlayer player, Cast.Piece p) {
        Stream st = streams.get(player.getUUID());
        if (st == null) return;
        if (st.building.size() + p.data().length > MAX_BUFFERED) { st.building.reset(); return; }
        st.building.writeBytes(p.data());
        if (!p.last()) return;
        byte[] element = st.building.toByteArray();
        st.building.reset();
        if (p.kind() == Cast.INIT) {
            st.init = element;
            st.clusters.clear();
            st.buffered = 0;
        } else {
            if (p.kind() == Cast.JOIN) { st.clusters.clear(); st.buffered = 0; }
            if (st.buffered + element.length > MAX_BUFFERED) return; // no join point for too long; drop
            st.clusters.add(element);
            st.buffered += element.length;
        }
        var server = player.level().getServer();
        for (UUID w : st.watchers) {
            ServerPlayer viewer = server.getPlayerList().getPlayer(w);
            if (viewer != null) send(viewer, player.getUUID(), p.kind(), element);
        }
    }

    static void watch(ServerPlayer viewer, Cast.Watch w) {
        Stream st = streams.get(w.caster());
        if (st == null) {
            if (w.on()) ServerPlayNetworking.send(viewer, new Cast.Bytes(w.caster(), -1, true, new byte[0]));
            return;
        }
        if (!w.on()) { st.watchers.remove(viewer.getUUID()); return; }
        if (!st.watchers.add(viewer.getUUID())) return;
        if (st.init == null) return;
        send(viewer, w.caster(), Cast.INIT, st.init);
        for (int i = 0; i < st.clusters.size(); i++)
            send(viewer, w.caster(), i == 0 ? Cast.JOIN : Cast.CLUSTER, st.clusters.get(i));
    }

    static void end(ServerPlayer player) {
        Stream st = streams.remove(player.getUUID());
        if (st == null) return;
        var server = player.level().getServer();
        for (UUID w : st.watchers) {
            ServerPlayer viewer = server.getPlayerList().getPlayer(w);
            if (viewer != null) ServerPlayNetworking.send(viewer, new Cast.Bytes(player.getUUID(), -1, true, new byte[0]));
        }
        String url = Cast.SCHEME + player.getUUID();
        for (ScreenBlockEntity s : List.copyOf(Chorus.LOADED)) if (url.equals(s.url)) s.load("");
    }

    static void left(ServerPlayer player) {
        end(player);
        for (Stream st : streams.values()) st.watchers.remove(player.getUUID());
    }

    private static void send(ServerPlayer to, UUID caster, int kind, byte[] element) {
        for (int at = 0; at < element.length || at == 0; at += Cast.RELAY_PIECE) {
            int n = Math.min(Cast.RELAY_PIECE, element.length - at);
            byte[] part = java.util.Arrays.copyOfRange(element, at, at + n);
            ServerPlayNetworking.send(to, new Cast.Bytes(caster, kind, at + n >= element.length, part));
            if (element.length == 0) break;
        }
    }

    private CastRelay() {}
}
