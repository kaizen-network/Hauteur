package io.github.maxlananas.hauteur.pipeline;

import io.github.maxlananas.hauteur.session.HauteurSession;
import org.cloudburstmc.math.vector.Vector3f;

/**
 * The slice of a Geyser session that the pipeline uses. Production code adapts a {@code GeyserSession}
 * ({@code session.GeyserPlayerSession}); tests substitute a fake, so the pipeline can run without a Geyser
 * runtime.
 */
public interface PlayerSession {
    String bedrockUsername();

    boolean isSpawned();

    /** Whether the player's entity exists yet. Position accessors are only valid when this is true. */
    boolean hasPlayerEntity();

    /** Position of the player entity in the frame the client currently sees. Only valid with an entity. */
    Vector3f playerPosition();

    float playerYaw();

    float playerPitch();

    void sendMessage(String message);

    /** Block state id at a position in the client frame, as Geyser's world manager reports it. */
    int blockAt(int x, int y, int z);

    /**
     * The window for a dimension type id from a login/respawn packet, read from the registries the server sent
     * during configuration; null if it cannot be resolved. Unlike Geyser's chunk cache, this does not wait for
     * Geyser's own login/respawn translator, which runs on another thread after this pipeline sees the packet.
     */
    HauteurSession.WindowConfig windowForDimension(int dimensionId, int maxOffsetOverride);
}
