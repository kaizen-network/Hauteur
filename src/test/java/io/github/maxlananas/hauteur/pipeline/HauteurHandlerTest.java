package io.github.maxlananas.hauteur.pipeline;

import io.github.maxlananas.hauteur.HauteurConfig;
import io.github.maxlananas.hauteur.session.HauteurSession;
import io.github.maxlananas.hauteur.translate.CommandYRewrite;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.embedded.EmbeddedChannel;
import org.cloudburstmc.math.vector.Vector3d;
import org.cloudburstmc.math.vector.Vector3f;
import org.cloudburstmc.math.vector.Vector3i;
import org.geysermc.mcprotocollib.protocol.data.game.entity.object.Direction;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.PlayerAction;
import org.geysermc.mcprotocollib.protocol.data.game.level.LightUpdateData;
import org.geysermc.mcprotocollib.protocol.data.game.level.block.BlockEntityInfo;
import net.kyori.adventure.key.Key;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.GameMode;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.PlayerSpawnInfo;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundLoginPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundRespawnPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.ClientboundTeleportEntityPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.player.ClientboundPlayerPositionPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundBlockUpdatePacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundLevelChunkWithLightPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.level.ServerboundAcceptTeleportationPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundMovePlayerPosPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundPlayerActionPacket;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end checks of {@link HauteurHandler} on an {@link EmbeddedChannel}. The pipeline is laid out as in
 * production: {@code hauteur} sits before Geyser's manager handler. Server packets enter through
 * {@code writeInbound}; packets the handler forwards to Geyser come out of {@code readInbound}; client
 * packets enter through {@code writeOutbound} and reach the server through {@code readOutbound}.
 *
 * <p>The dimension is a legacy-window case: Java [-64, 320) against a client window [0, 256). The window
 * is needed, and the largest useful offset is 64.</p>
 */
class HauteurHandlerTest {

    private static final String MANAGER = "manager";
    private static final int PLAYER_ID = 42;
    /** Real Y above the top client edge margin (256 - 64 = 192): triggers a switch to offset 64. */
    private static final double HIGH_REAL_Y = 200;
    private static final int TARGET_OFFSET = 64;
    /**
     * Freeze length in the test clock. Netty's EmbeddedChannel runs every scheduled task that is due on each
     * write, so a zero-length freeze would end inside the switch that started it. The clock is frozen (see
     * Rig) and advanced explicitly, so the freeze lasts exactly as long as the test says.
     */
    private static final long FREEZE_MS = 500;

    /** Fixed-state player: the test controls what the pipeline can observe about the Geyser session. */
    private static final class FakeSession implements PlayerSession {
        boolean spawned = true;
        boolean hasEntity = true;
        final List<String> messages = new ArrayList<>();

        @Override
        public String bedrockUsername() {
            return "Tester";
        }

        @Override
        public boolean isSpawned() {
            return spawned;
        }

        @Override
        public boolean hasPlayerEntity() {
            return hasEntity;
        }

        @Override
        public Vector3f playerPosition() {
            return Vector3f.from(0, HIGH_REAL_Y, 0);
        }

        @Override
        public float playerYaw() {
            return 90f;
        }

        @Override
        public float playerPitch() {
            return 5f;
        }

        @Override
        public void sendMessage(String message) {
            messages.add(message);
        }

        @Override
        public int blockAt(int x, int y, int z) {
            return 0;
        }

        /** Window the registries report for any dimension id; null = unresolvable. */
        HauteurSession.WindowConfig spawnWindow;

        @Override
        public HauteurSession.WindowConfig windowForDimension(int dimensionId, int maxOffsetOverride) {
            return spawnWindow;
        }
    }

    private static final class RecordingDiagnostics implements Diagnostics {
        final List<String> infos = new ArrayList<>();
        final List<String> errors = new ArrayList<>();

        @Override
        public void info(String message) {
            infos.add(message);
        }

        @Override
        public void error(String message, Throwable cause) {
            errors.add(message);
        }
    }

    private static final class FakeSettings implements PipelineSettings {
        final HauteurConfig config = HauteurConfig.loadDefault();
        boolean running = true;

        FakeSettings() {
            config.freezeMs = FREEZE_MS;
        }

        @Override
        public boolean running() {
            return running;
        }

        @Override
        public HauteurConfig config() {
            return config;
        }

        @Override
        public CommandYRewrite.Config commandConfig() {
            return new CommandYRewrite.Config(false, Set.of());
        }
    }

    /** One wired pipeline plus the handles the scenarios inspect. */
    private static final class Rig {
        final EmbeddedChannel channel = new EmbeddedChannel();
        final HauteurSession state;
        final FakeSession session = new FakeSession();
        final FakeSettings settings = new FakeSettings();
        final RecordingDiagnostics diagnostics = new RecordingDiagnostics();
        final HauteurHandler handler;
        private final HauteurSession.WindowConfig window;

        Rig(HauteurSession.WindowConfig window) {
            this.window = window;
            this.state = new HauteurSession(() -> this.window, 64, 1 << 20);
            this.state.setPlayerJavaId(PLAYER_ID);
            channel.freezeTime(); // scheduled tasks run only when endFreeze() advances the clock
            channel.pipeline().addLast(MANAGER, new ChannelDuplexHandler());
            ChannelHandlerContext managerContext = channel.pipeline().context(MANAGER);
            this.handler = new HauteurHandler(session, state, settings, diagnostics, managerContext);
            channel.pipeline().addFirst(HauteurHandler.HANDLER_NAME, handler);
        }

        /** Client-to-server movement at the given client-space Y, as the Bedrock client sends it. */
        ServerboundMovePlayerPosPacket move(double clientY) {
            return new ServerboundMovePlayerPosPacket(true, false, 0.5, clientY, 0.5);
        }

        /** Sends a client movement through the pipeline; returns what reached the server, if anything. */
        Object sendMove(ServerboundMovePlayerPosPacket packet) {
            channel.writeOutbound(packet);
            return channel.readOutbound();
        }

        /** Ends the freeze: advances the test clock past it and runs the scheduled unfreeze. */
        void endFreeze() {
            channel.advanceTimeBy(FREEZE_MS, TimeUnit.MILLISECONDS);
            channel.runScheduledPendingTasks();
        }

        /** Drains every packet the handler forwarded to Geyser. */
        List<Object> drainInbound() {
            List<Object> out = new ArrayList<>();
            Object o;
            while ((o = channel.readInbound()) != null) {
                out.add(o);
            }
            return out;
        }
    }

    private static HauteurSession.WindowConfig windowNeeded() {
        // Java [-64, 320) against the legacy client window [0, 256): needed, maxOffset 64.
        return HauteurSession.WindowConfig.of(-64, 320, 0, 256, 0);
    }

    private static HauteurSession.WindowConfig windowNotNeeded() {
        // Java [-64, 320) against [0, 320) with no room for offsets: windowing is not needed.
        return HauteurSession.WindowConfig.of(-64, 320, 0, 320, 0);
    }

    private static ClientboundLevelChunkWithLightPacket chunk(int x, int z) {
        return new ClientboundLevelChunkWithLightPacket(x, z, new byte[64], Map.of(), new BlockEntityInfo[0],
            new LightUpdateData(new BitSet(), new BitSet(), new BitSet(), new BitSet(), List.of(), List.of()));
    }

    @Test
    void movementPassesUnchangedAtOffsetZero() {
        Rig rig = new Rig(windowNeeded());
        // Mid-window, clear of both switch margins (the world floor -64 is below the client floor, so a position near
        // the client floor would now switch down).
        ServerboundMovePlayerPosPacket low = rig.move(128);

        assertSame(low, rig.sendMove(low));
        assertEquals(0, rig.state.offset());
        assertTrue(rig.drainInbound().isEmpty());
    }

    @Test
    void highMovementSwitchesWindowAndSnapsClientToItsClientY() {
        Rig rig = new Rig(windowNeeded());
        ServerboundMovePlayerPosPacket trigger = rig.move(HIGH_REAL_Y);

        // The triggering packet was sent in real space (offset 0 when written), so it passes unchanged.
        assertSame(trigger, rig.sendMove(trigger));
        assertEquals(TARGET_OFFSET, rig.state.offset());
        assertTrue(rig.state.frozen(), "the switch freezes movement until the freeze ends");
        assertEquals(1, rig.state.total(HauteurSession.Stat.WINDOW_SWITCHES));

        List<Object> injected = rig.drainInbound();
        assertEquals(1, injected.size(), "one snap, no chunk to replay");
        var snap = (ClientboundTeleportEntityPacket) injected.get(0);
        assertEquals(PLAYER_ID, snap.getId());
        assertEquals(HIGH_REAL_Y - TARGET_OFFSET, snap.getPosition().getY(), 1e-9,
            "snap is placed at clientY = realY - offset");
    }

    @Test
    void movementHeldAcrossSwitchIsReplayedInTheNewFrame() {
        Rig rig = new Rig(windowNeeded());
        rig.sendMove(rig.move(HIGH_REAL_Y));
        rig.drainInbound();

        // The client moved after the snap, so its Y is in the new frame (136 + 1 = 137). The packet must
        // be held while the freeze lasts, not sent.
        assertNull(rig.sendMove(rig.move(137)));

        rig.endFreeze(); // held packets are flushed
        assertFalse(rig.state.frozen());
        var replayed = (ServerboundMovePlayerPosPacket) rig.channel.readOutbound();
        assertEquals(HIGH_REAL_Y + 1, replayed.getY(), 1e-9,
            "the held packet is replayed at its real Y (137 + offset 64)");
    }

    private static ServerboundPlayerActionPacket dig(int x, int y, int z) {
        return new ServerboundPlayerActionPacket(PlayerAction.START_DIGGING, Vector3i.from(x, y, z), Direction.UP, 0);
    }

    /** Client Y 100 is visible in both candidate frames of the window (previous 0, current 64). */
    private static final int DIG_Y = 100;

    @Test
    void droppedDestroysAreRevertedAfterTheFreezeUpToTheLimit() {
        Rig rig = new Rig(windowNeeded());
        rig.settings.config.freezeHoldActions = false; // dropped, so each one must be recorded for the re-check
        rig.sendMove(rig.move(HIGH_REAL_Y));
        rig.drainInbound();
        int dugs = 10;
        for (int x = 0; x < dugs; x++) {
            rig.channel.writeOutbound(dig(x, DIG_Y, 0));
        }
        assertNull(rig.channel.readOutbound(), "a dropped destroy is not forwarded");
        rig.endFreeze();
        Set<Integer> reverted = new HashSet<>();
        for (Object o : rig.drainInbound()) {
            if (o instanceof ClientboundBlockUpdatePacket update) {
                reverted.add(update.getEntry().getPosition().getX());
            }
        }
        assertEquals(dugs, reverted.size(), "every dropped destroy is re-checked, not only the first eight");
        assertEquals(0, rig.state.sinceReset(HauteurSession.Stat.GHOST_OVERFLOWS));
    }

    @Test
    void destroysBeyondTheRevertListAreCountedNotSilentlyLost() {
        Rig rig = new Rig(windowNeeded());
        rig.settings.config.freezeHoldActions = false;
        rig.sendMove(rig.move(HIGH_REAL_Y));
        rig.drainInbound();
        int dugs = HauteurSession.GHOST_REVERT_LIMIT + 44;
        for (int x = 0; x < dugs; x++) {
            rig.channel.writeOutbound(dig(x, DIG_Y, 0));
        }
        assertEquals(HauteurSession.GHOST_REVERT_LIMIT, rig.state.ghostRevertCount());
        assertEquals(44, rig.state.sinceReset(HauteurSession.Stat.GHOST_OVERFLOWS));
        // The same block dug again while the freeze lasts is one entry, not an overflow.
        rig.channel.writeOutbound(dig(0, DIG_Y, 0));
        assertEquals(44, rig.state.sinceReset(HauteurSession.Stat.GHOST_OVERFLOWS));
    }

    @Test
    void detachReturnsClientToRealSpaceReplayingChunksBeforeTheSnap() {
        Rig rig = new Rig(windowNeeded());
        rig.channel.writeInbound(chunk(0, 0)); // cached: windowing applies
        assertEquals(1, rig.drainInbound().size());

        rig.sendMove(rig.move(HIGH_REAL_Y));
        rig.drainInbound(); // switch replay + snap at the shifted frame
        rig.endFreeze();
        assertEquals(TARGET_OFFSET, rig.state.offset());

        rig.channel.pipeline().remove(rig.handler);

        assertEquals(0, rig.state.offset());
        assertFalse(rig.state.attached());
        List<Object> onDetach = rig.drainInbound();
        assertEquals(2, onDetach.size(), "one chunk replay, then one snap");
        var replayedChunk = (ClientboundLevelChunkWithLightPacket) onDetach.get(0);
        assertEquals(0, replayedChunk.getX());
        assertEquals(0, replayedChunk.getZ());
        var snap = (ClientboundTeleportEntityPacket) onDetach.get(1);
        assertEquals(PLAYER_ID, snap.getId());
        assertEquals(HIGH_REAL_Y, snap.getPosition().getY(), 1e-9, "snap returns to the last real Y at offset 0");
    }

    @Test
    void detachOnClosedChannelSendsNothing() {
        Rig rig = new Rig(windowNeeded());
        rig.sendMove(rig.move(HIGH_REAL_Y));
        rig.drainInbound();
        rig.endFreeze();

        // Closing deregisters the channel, which removes the handler: handlerRemoved runs on a dead channel.
        rig.channel.close();

        assertFalse(rig.state.attached(), "the handler was detached by the close");
        assertTrue(rig.drainInbound().isEmpty(), "a closed channel has no client to correct");
        assertTrue(rig.diagnostics.errors.isEmpty());
    }

    @Test
    void noSwitchWhenWindowingIsNotNeeded() {
        Rig rig = new Rig(windowNotNeeded());
        ServerboundMovePlayerPosPacket high = rig.move(HIGH_REAL_Y);

        assertSame(high, rig.sendMove(high));
        assertEquals(0, rig.state.offset());
        assertEquals(0, rig.state.total(HauteurSession.Stat.WINDOW_SWITCHES));
        assertTrue(rig.drainInbound().isEmpty());
    }

    @Test
    void noSwitchWhenExtensionIsNotRunning() {
        Rig rig = new Rig(windowNeeded());
        rig.settings.running = false;
        ServerboundMovePlayerPosPacket high = rig.move(HIGH_REAL_Y);

        assertSame(high, rig.sendMove(high));
        assertEquals(0, rig.state.offset());
        assertTrue(rig.drainInbound().isEmpty());
    }

    @Test
    void noSwitchWithoutAPlayerEntity() {
        Rig rig = new Rig(windowNeeded());
        rig.session.hasEntity = false;
        ServerboundMovePlayerPosPacket high = rig.move(HIGH_REAL_Y);

        assertSame(high, rig.sendMove(high));
        assertEquals(0, rig.state.offset(), "no entity, so no snap and no switch");
        assertTrue(rig.drainInbound().isEmpty());
    }

    @Test
    void nonFiniteClientYIsDroppedAndNeverBecomesTheAnchor() {
        Rig rig = new Rig(windowNeeded());

        assertNull(rig.sendMove(rig.move(Double.NaN)), "no real Y can be derived, so nothing is sent");
        assertNull(rig.sendMove(rig.move(Double.POSITIVE_INFINITY)));
        assertEquals(0, rig.state.offset());
        assertEquals(0.0, rig.state.lastSeenRealY(), "the anchor is unchanged");
        assertEquals(0, rig.state.total(HauteurSession.Stat.WINDOW_SWITCHES));
        assertTrue(rig.drainInbound().isEmpty());
    }

    @Test
    void forceWindowAcceptsOnlyPositionsInsideTheDimension() {
        Rig rig = new Rig(windowNeeded());

        rig.handler.forceWindow(Double.NaN);
        rig.handler.forceWindow(Double.POSITIVE_INFINITY);
        rig.handler.forceWindow(400); // above javaMaxY (320)
        assertEquals(0, rig.state.offset(), "invalid targets never move the window or snap the client");
        assertTrue(rig.drainInbound().isEmpty());

        // Positive control: a valid target does move the window, so the rejections above are meaningful.
        rig.handler.forceWindow(HIGH_REAL_Y);
        assertEquals(TARGET_OFFSET, rig.state.offset());
        assertEquals(1, rig.state.total(HauteurSession.Stat.WINDOW_SWITCHES));
    }

    @Test
    void switchWithoutSpawnedClientDoesNotSnap() {
        Rig rig = new Rig(windowNeeded());
        rig.session.spawned = false;

        rig.sendMove(rig.move(HIGH_REAL_Y));
        rig.endFreeze();

        assertEquals(TARGET_OFFSET, rig.state.offset(), "the window still switches");
        assertTrue(rig.drainInbound().isEmpty(), "but a not-yet-spawned client is not snapped");
    }

    @Test
    void teleportAcceptSentDuringTheFreezeUsesItsTeleportsFrame() {
        Rig rig = new Rig(windowNeeded());
        rig.channel.writeInbound(new ClientboundPlayerPositionPacket(77, Vector3d.from(0.5, HIGH_REAL_Y, 0.5),
            Vector3d.ZERO, 0f, 0f, List.of()));
        assertEquals(TARGET_OFFSET, rig.state.offset());
        assertTrue(rig.state.frozen());
        var teleport = (ClientboundPlayerPositionPacket) rig.drainInbound().stream()
            .filter(ClientboundPlayerPositionPacket.class::isInstance).findFirst().orElseThrow();
        assertEquals(HIGH_REAL_Y - TARGET_OFFSET, teleport.getPosition().getY(), 1e-9);

        // Geyser echoes the window-space position it was given; the server must get the real one back.
        rig.channel.writeOutbound(new ServerboundAcceptTeleportationPacket(77, 0.5, teleport.getPosition().getY(),
            0.5, 0f, 0f));
        var accept = (ServerboundAcceptTeleportationPacket) rig.channel.readOutbound();
        assertEquals(HIGH_REAL_Y, accept.getY(), 1e-9);
    }

    @Test
    void respawnTakesTheNewWorldsWindowBeforeGeysersChunkCacheCatchesUp() {
        // Geyser's chunk cache still describes the old, short world: its respawn translator runs on another thread.
        Rig rig = new Rig(windowNotNeeded());
        // The respawn packet itself names a tall dimension.
        rig.session.spawnWindow = windowNeeded();
        rig.channel.writeInbound(new ClientboundRespawnPacket(new PlayerSpawnInfo(0, Key.key("minecraft:overworld"),
            0L, GameMode.SURVIVAL, GameMode.SURVIVAL, false, false, null, 0, 63), false, false));
        rig.drainInbound();

        // First position in the new world, high up: it must switch, not adopt the stale short-world bounds.
        rig.channel.writeInbound(new ClientboundPlayerPositionPacket(5, Vector3d.from(0.5, HIGH_REAL_Y, 0.5),
            Vector3d.ZERO, 0f, 0f, List.of()));
        assertEquals(TARGET_OFFSET, rig.state.offset());
        assertEquals(1, rig.state.total(HauteurSession.Stat.WINDOW_SWITCHES));
    }

    /** Everest: real [-2032, 2032) against the overworld client window [-64, 320). */
    private static HauteurSession.WindowConfig everest() {
        return HauteurSession.WindowConfig.of(-2032, 2032, -64, 384, 0);
    }

    private static ClientboundPlayerPositionPacket teleport(int id, double realY) {
        return new ClientboundPlayerPositionPacket(id, Vector3d.from(0.5, realY, 0.5), Vector3d.ZERO, 0f, 0f, List.of());
    }

    @Test
    void deepTeleportUsesANegativeOffsetAndTranslatesBothWays() {
        Rig rig = new Rig(everest());
        rig.channel.writeInbound(teleport(3, -1284));
        // centre -1284 - 128 = -1412, snapped to -1408: the spawn shows at client Y 124
        assertEquals(-1408, rig.state.offset());
        var shown = (ClientboundPlayerPositionPacket) rig.drainInbound().stream()
            .filter(ClientboundPlayerPositionPacket.class::isInstance).findFirst().orElseThrow();
        assertEquals(124, shown.getPosition().getY(), 1e-9);
        rig.endFreeze();

        // client-frame packets go back to real space
        var moved = (ServerboundMovePlayerPosPacket) rig.sendMove(rig.move(124.5));
        assertEquals(-1283.5, moved.getY(), 1e-9);
        rig.channel.writeOutbound(dig(1, 120, 1));
        var dug = (ServerboundPlayerActionPacket) rig.channel.readOutbound();
        assertEquals(-1288, dug.getPosition().getY());
    }

    @Test
    void climbingFromBelowZeroToTheSummitCrossesIntoPositiveOffsets() {
        Rig rig = new Rig(everest());
        rig.channel.writeInbound(teleport(3, -1284));
        rig.endFreeze();
        rig.drainInbound();
        rig.channel.writeInbound(teleport(4, 2000));
        // centre 2000 - 128 = 1872, clamped to the useful maximum 2032 + 64 - 384 = 1712
        assertEquals(1712, rig.state.offset());
        var shown = (ClientboundPlayerPositionPacket) rig.drainInbound().stream()
            .filter(ClientboundPlayerPositionPacket.class::isInstance).findFirst().orElseThrow();
        assertEquals(288, shown.getPosition().getY(), 1e-9);
    }

    @Test
    void loginTakesTheNewWorldsWindowBeforeGeysersChunkCacheCatchesUp() {
        // Same race as the respawn case, through the login packet a server switch sends.
        Rig rig = new Rig(windowNotNeeded());
        rig.session.spawnWindow = windowNeeded();
        rig.channel.writeInbound(new ClientboundLoginPacket(PLAYER_ID, false, new Key[] {Key.key("minecraft:overworld")},
            20, 10, 10, false, true, false, new PlayerSpawnInfo(0, Key.key("minecraft:overworld"), 0L, GameMode.SURVIVAL,
            GameMode.SURVIVAL, false, false, null, 0, 63), false, false));
        rig.drainInbound();
        rig.channel.writeInbound(teleport(5, HIGH_REAL_Y));
        assertEquals(TARGET_OFFSET, rig.state.offset());
    }
}
