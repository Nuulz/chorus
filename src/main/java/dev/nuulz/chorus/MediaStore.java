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
import java.util.List;
import net.minecraft.core.BlockPos;
import net.minecraft.server.MinecraftServer;
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
        return dir(p.level().getServer());
    }

    static Path dir(MinecraftServer server) {
        return server.getWorldPath(LevelResource.ROOT).resolve("chorus-media");
    }

    /** Videos no loaded screen has shown for this long are deleted. */
    static final long KEEP_MS = Long.getLong("chorus.keepMs", 5 * 60_000);
    static final int SWEEP_TICKS = Integer.getInteger("chorus.sweepTicks", 1200);
    private static final Map<String, Long> lastUse = new ConcurrentHashMap<>();
    private static int ticks;

    /** Once a minute: forget videos nobody is showing, and stop screens whose video is gone. */
    static void tick(MinecraftServer server) {
        if (++ticks % SWEEP_TICKS != 0) return;
        Path dir = dir(server);
        if (!Files.isDirectory(dir)) return;
        long now = System.currentTimeMillis();
        Chorus.LOADED.removeIf(net.minecraft.world.level.block.entity.BlockEntity::isRemoved);
        java.util.Set<String> used = new java.util.HashSet<>();
        for (ScreenBlockEntity s : List.copyOf(Chorus.LOADED)) {
            if (!Media.isMedia(s.url)) continue;
            if (Files.isRegularFile(dir.resolve(Media.name(s.url)))) used.add(Media.name(s.url));
            else s.load("");
        }
        java.util.Set<Path> uploading = new java.util.HashSet<>();
        for (Upload u : uploads.values()) { used.add(u.name); uploading.add(u.temp); }
        try (var files = Files.list(dir)) {
            for (Path f : files.toList()) {
                String name = f.getFileName().toString();
                if (name.endsWith(".part")) {
                    if (!uploading.contains(f) && now - Files.getLastModifiedTime(f).toMillis() > KEEP_MS) Files.deleteIfExists(f);
                    continue;
                }
                if (!Media.NAME.matcher(name).matches()) continue;
                if (used.contains(name)) { lastUse.put(name, now); continue; }
                if (now - lastUse.computeIfAbsent(name, n -> now) > KEEP_MS) {
                    Files.deleteIfExists(f);
                    lastUse.remove(name);
                    Chorus.LOG.info("chorus: deleted unused video {}", name);
                }
            }
        } catch (IOException e) {
            Chorus.LOG.warn("chorus: couldn't clean {} ({})", dir, e.toString());
        }
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
