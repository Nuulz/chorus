package dev.nuulz.chorus;

import java.util.Collections;
import java.util.Properties;
import java.util.Set;
import java.util.WeakHashMap;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.creativetab.v1.CreativeModeTabEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.fabric.api.object.builder.v1.block.entity.FabricBlockEntityTypeBuilder;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.core.Registry;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.permissions.Permissions;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.CreativeModeTabs;
import net.minecraft.world.item.Item;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Chorus only orchestrates: the browser is pane, the world/players/roles are whatever server you're on
 * (a jukz world is a normal server whose host owns it). The screen's block entity holds the one true
 * playback state and vanilla block-entity sync carries it to everyone, late joiners included.
 */
public final class Chorus implements ModInitializer {
    public static final String ID = "chorus";
    public static final Logger LOG = LoggerFactory.getLogger(ID);

    public static final Identifier SCREEN_ID = Identifier.fromNamespaceAndPath(ID, "screen");
    public static final ScreenBlock SCREEN = new ScreenBlock(BlockBehaviour.Properties.of()
        .strength(1.5f).lightLevel(s -> 3).setId(ResourceKey.create(Registries.BLOCK, SCREEN_ID)));
    public static final BlockEntityType<ScreenBlockEntity> SCREEN_ENTITY =
        FabricBlockEntityTypeBuilder.create(ScreenBlockEntity::new, SCREEN).build();

    /** Screens loaded on the server, for the per-world limit. */
    static final Set<ScreenBlockEntity> LOADED = Collections.newSetFromMap(new WeakHashMap<>());

    public static UrlPolicy urls;
    private static Props cfg;

    /** The "any link" switch, from this machine's settings screen (a host's server is the same machine). */
    public static void allowAnyLink(boolean on) {
        cfg.set("allow_any_link", on);
        urls = new UrlPolicy(cfg.str("allowed_hosts"), on);
    }
    public static int maxScreens;
    public static boolean anyoneControls;
    public static long maxMediaBytes;

    @Override
    public void onInitialize() {
        Properties d = new Properties();
        d.setProperty("max_screens", "6");
        d.setProperty("allowed_hosts", UrlPolicy.DEFAULT_HOSTS);
        d.setProperty("anyone_can_control", "false");
        d.setProperty("max_video_mb", "4096");
        d.setProperty("allow_any_link", "false");
        cfg = new Props(FabricLoader.getInstance().getConfigDir().resolve("chorus-server.properties"), d);
        urls = new UrlPolicy(cfg.str("allowed_hosts"), cfg.flag("allow_any_link"));
        maxScreens = cfg.num("max_screens", 1, 64);
        anyoneControls = cfg.flag("anyone_can_control");
        maxMediaBytes = cfg.num("max_video_mb", 1, 65536) * 1024L * 1024L;

        Registry.register(BuiltInRegistries.BLOCK, SCREEN_ID, SCREEN);
        Item item = new BlockItem(SCREEN, new Item.Properties().useBlockDescriptionPrefix()
            .setId(ResourceKey.create(Registries.ITEM, SCREEN_ID)));
        Registry.register(BuiltInRegistries.ITEM, SCREEN_ID, item);
        Registry.register(BuiltInRegistries.BLOCK_ENTITY_TYPE, SCREEN_ID, SCREEN_ENTITY);
        CreativeModeTabEvents.modifyOutputEvent(CreativeModeTabs.FUNCTIONAL_BLOCKS).register(out -> out.accept(item));

        PayloadTypeRegistry.serverboundPlay().register(ControlPayload.TYPE, ControlPayload.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(ControlPayload.TYPE, (p, ctx) -> control(ctx.player(), p));

        PayloadTypeRegistry.serverboundPlay().register(Media.Begin.TYPE, Media.Begin.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(Media.Piece.TYPE, Media.Piece.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(Media.Fetch.TYPE, Media.Fetch.CODEC);
        PayloadTypeRegistry.clientboundPlay().register(Media.Ack.TYPE, Media.Ack.CODEC);
        PayloadTypeRegistry.clientboundPlay().registerLarge(Media.Chunk.TYPE, Media.Chunk.CODEC, Media.CHUNK + 1024);
        ServerPlayNetworking.registerGlobalReceiver(Media.Begin.TYPE, (p, ctx) -> MediaStore.begin(ctx.player(), p));
        ServerPlayNetworking.registerGlobalReceiver(Media.Piece.TYPE, (p, ctx) -> MediaStore.piece(ctx.player(), p));
        ServerPlayNetworking.registerGlobalReceiver(Media.Fetch.TYPE, (p, ctx) -> MediaStore.fetch(ctx.player(), p));
        ServerTickEvents.END_SERVER_TICK.register(MediaStore::tick);

        PayloadTypeRegistry.serverboundPlay().register(Cast.Begin.TYPE, Cast.Begin.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(Cast.Piece.TYPE, Cast.Piece.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(Cast.End.TYPE, Cast.End.CODEC);
        PayloadTypeRegistry.serverboundPlay().register(Cast.Watch.TYPE, Cast.Watch.CODEC);
        PayloadTypeRegistry.clientboundPlay().registerLarge(Cast.Bytes.TYPE, Cast.Bytes.CODEC, Cast.RELAY_PIECE + 1024);
        ServerPlayNetworking.registerGlobalReceiver(Cast.Begin.TYPE, (p, ctx) -> CastRelay.begin(ctx.player(), p));
        ServerPlayNetworking.registerGlobalReceiver(Cast.Piece.TYPE, (p, ctx) -> CastRelay.piece(ctx.player(), p));
        ServerPlayNetworking.registerGlobalReceiver(Cast.End.TYPE, (p, ctx) -> CastRelay.end(ctx.player()));
        ServerPlayNetworking.registerGlobalReceiver(Cast.Watch.TYPE, (p, ctx) -> CastRelay.watch(ctx.player(), p));
        ServerPlayConnectionEvents.DISCONNECT.register((h, server) -> {
            MediaStore.abort(h.getPlayer());
            CastRelay.left(h.getPlayer());
        });
    }

    /** Who may drive a screen: whoever placed it, the world's owner (a jukz host) and operators. */
    public static boolean canControl(ServerPlayer player, ScreenBlockEntity screen) {
        if (anyoneControls || player.getUUID().toString().equals(screen.owner)) return true;
        var server = player.level().getServer();
        return server.isSingleplayerOwner(player.nameAndId())
            || player.permissions().hasPermission(Permissions.COMMANDS_GAMEMASTER);
    }

    private static void control(ServerPlayer player, ControlPayload p) {
        if (player.distanceToSqr(net.minecraft.world.phys.Vec3.atCenterOf(p.pos())) > 16 * 16) return;
        if (!(player.level().getBlockEntity(p.pos()) instanceof ScreenBlockEntity screen)) return;
        if (!canControl(player, screen)) {
            player.sendSystemMessage(Component.translatable("chorus.no_permission"));
            return;
        }
        switch (p.action()) {
            case ControlPayload.SET_URL -> {
                boolean stored = Media.isMedia(p.url())
                    && java.nio.file.Files.isRegularFile(MediaStore.dir(player).resolve(Media.name(p.url())));
                if (!stored && !urls.allows(p.url())) {
                    player.sendSystemMessage(Component.translatable("chorus.url_blocked"));
                    return;
                }
                screen.load(p.url());
            }
            case ControlPayload.PLAY -> screen.play(true);
            case ControlPayload.PAUSE -> screen.play(false);
            case ControlPayload.SEEK -> screen.seek(p.ms());
            case ControlPayload.STOP -> screen.load("");
            default -> { }
        }
    }
}
