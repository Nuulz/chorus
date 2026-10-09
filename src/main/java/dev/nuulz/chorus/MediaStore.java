package dev.nuulz.chorus;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.storage.LevelResource;

/** The server side of {@link Media}: receives uploads into the world folder and hands out chunks. */
final class MediaStore {
    /** Acknowledge every this many pieces; the uploader keeps at most a few acks' worth in flight. */
    static final int ACK_EVERY = 32;

    private static final class Upload {
        BlockPos pos; String name; long size, received; FileChannel out; Path temp; MessageDigest sha; int pieces;
    }

    private static final Map<UUID, Upload> uploads = new ConcurrentHashMap<>();

    static Path dir(ServerPlayer p) {
        return p.level().getServer().getWorldPath(LevelResource.ROOT).resolve("chorus-media");
    }

    static void begin(ServerPlayer player, Media.Begin b) {
        abort(player);
        String failure = null;
        if (!Media.NAME.matcher(b.name()).matches()) failure = "bad name";
        else if (b.size() <= 0 || b.size() > Chorus.maxMediaBytes) failure = "too big";
        else if (!(player.level().getBlockEntity(b.pos()) instanceof ScreenBlockEntity s) || !Chorus.canControl(player, s))
            failure = "no permission";
        if (failure != null) { ack(player, 0, Media.Ack.FAILED, failure); return; }
        try {
            Path dir = dir(player);
            Files.createDirectories(dir);
            if (Files.exists(dir.resolve(b.name()))) { // someone already uploaded this exact file
                ack(player, b.size(), Media.Ack.DONE, "");
                play(player, b.pos(), b.name());
                return;
            }
            Upload u = new Upload();
            u.pos = b.pos(); u.name = b.name(); u.size = b.size();
            u.temp = dir.resolve(b.name() + "." + player.getUUID() + ".part");
            u.out = FileChannel.open(u.temp, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            u.sha = MessageDigest.getInstance("SHA-256");
            uploads.put(player.getUUID(), u);
            ack(player, 0, Media.Ack.PROGRESS, "");
        } catch (Exception e) {
            ack(player, 0, Media.Ack.FAILED, e.toString());
        }
    }

    static void piece(ServerPlayer player, Media.Piece p) {
        Upload u = uploads.get(player.getUUID());
        if (u == null) return;
        try {
            if (u.received + p.data().length > u.size) throw new IOException("more data than announced");
            u.out.write(ByteBuffer.wrap(p.data()));
            u.sha.update(p.data());
            u.received += p.data().length;
            if (u.received < u.size) {
                if (++u.pieces % ACK_EVERY == 0) ack(player, u.received, Media.Ack.PROGRESS, "");
                return;
            }
            uploads.remove(player.getUUID());
            u.out.close();
            String sha = HexFormat.of().formatHex(u.sha.digest());
            if (!u.name.startsWith(sha + ".")) {
                Files.deleteIfExists(u.temp);
                ack(player, u.received, Media.Ack.FAILED, "checksum mismatch");
                return;
            }
            Files.move(u.temp, u.temp.resolveSibling(u.name), StandardCopyOption.REPLACE_EXISTING);
            ack(player, u.received, Media.Ack.DONE, "");
            play(player, u.pos, u.name);
        } catch (IOException e) {
            abort(player);
            ack(player, u.received, Media.Ack.FAILED, e.toString());
        }
    }

    static void fetch(ServerPlayer player, Media.Fetch f) {
        Path file = dir(player).resolve(f.name());
        if (!Media.NAME.matcher(f.name()).matches() || f.index() < 0 || !Files.isRegularFile(file)) {
            ServerPlayNetworking.send(player, new Media.Chunk(f.name(), f.index(), -1, new byte[0]));
            return;
        }
        try (FileChannel in = FileChannel.open(file)) {
            long size = in.size(), at = (long) f.index() * Media.CHUNK;
            if (at >= size) return;
            ByteBuffer buf = ByteBuffer.allocate((int) Math.min(Media.CHUNK, size - at));
            while (buf.hasRemaining() && in.read(buf, at + buf.position()) > 0) { }
            ServerPlayNetworking.send(player, new Media.Chunk(f.name(), f.index(), size, buf.array()));
        } catch (IOException e) {
            Chorus.LOG.warn("chorus: couldn't read {} ({})", file, e.toString());
        }
    }

    static void abort(ServerPlayer player) {
        Upload u = uploads.remove(player.getUUID());
        if (u == null) return;
        try { u.out.close(); Files.deleteIfExists(u.temp); } catch (IOException ignored) { }
    }

    private static void play(ServerPlayer player, BlockPos pos, String name) {
        if (player.level().getBlockEntity(pos) instanceof ScreenBlockEntity s && Chorus.canControl(player, s))
            s.load(Media.SCHEME + name);
    }

    private static void ack(ServerPlayer player, long received, int state, String message) {
        ServerPlayNetworking.send(player, new Media.Ack(received, state, message));
    }

    private MediaStore() {}
}
