package io.github.maxlananas.hauteur.chunk;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.geysermc.mcprotocollib.protocol.codec.MinecraftTypes;
import org.geysermc.mcprotocollib.protocol.data.game.chunk.BitStorage;
import org.geysermc.mcprotocollib.protocol.data.game.chunk.ChunkSection;
import org.geysermc.mcprotocollib.protocol.data.game.chunk.DataPalette;
import org.geysermc.mcprotocollib.protocol.data.game.chunk.palette.GlobalPalette;
import org.geysermc.mcprotocollib.protocol.data.game.chunk.palette.ListPalette;
import org.geysermc.mcprotocollib.protocol.data.game.chunk.palette.MapPalette;
import org.geysermc.mcprotocollib.protocol.data.game.chunk.palette.Palette;
import org.geysermc.mcprotocollib.protocol.data.game.chunk.palette.PaletteType;
import org.geysermc.mcprotocollib.protocol.data.game.chunk.palette.SingletonPalette;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Sections encoded by the same mcprotocollib Geyser decodes with, so a wire-format change in the target Geyser
 * build fails here instead of on a live server. Hand-built payloads cannot catch that: they share the codec's
 * own assumptions.
 */
class SectionCodecWireTest {
    private static final int BLOCK_GLOBAL_BITS = 15;
    private static final int BIOME_GLOBAL_BITS = 7;

    /** Distinct block/biome counts that force singleton, list, map and global palettes for both types. */
    private static final int[][] SHAPES = {{1, 1}, {10, 2}, {100, 6}, {1000, 20}, {3, 1}, {1, 20}};

    /** A palette shaped the way a server writes it: the smallest palette kind and bit width that fit. */
    static DataPalette palette(PaletteType type, int distinct, int globalBits, Random r) {
        if (distinct == 1) {
            return DataPalette.create(new SingletonPalette(0), null, type, globalBits);
        }
        int bits = Math.max(type.getMinBitsPerEntry(), 32 - Integer.numberOfLeadingZeros(distinct - 1));
        Palette p;
        if (bits > type.getMaxBitsPerEntry()) {
            bits = globalBits;
            p = new GlobalPalette();
        } else if (bits <= type.getMinBitsPerEntry()) {
            p = new ListPalette(bits);
        } else {
            p = new MapPalette(bits);
        }
        BitStorage storage = new BitStorage(bits, type.getStorageSize());
        for (int i = 0; i < type.getStorageSize(); i++) {
            storage.set(i, p.stateToId(1 + r.nextInt(distinct)));
        }
        return DataPalette.create(p, storage, type, globalBits);
    }

    static byte[] encode(int distinctBlocks, int distinctBiomes, long seed) {
        Random r = new Random(seed);
        ChunkSection s = new ChunkSection(distinctBlocks == 1 ? 0 : 4096, 0,
            palette(PaletteType.BLOCK_STATE, distinctBlocks, BLOCK_GLOBAL_BITS, r),
            palette(PaletteType.BIOME, distinctBiomes, BIOME_GLOBAL_BITS, r));
        ByteBuf buf = Unpooled.buffer();
        MinecraftTypes.writeChunkSection(buf, s);
        byte[] out = new byte[buf.readableBytes()];
        buf.readBytes(out);
        return out;
    }

    @Test
    void sectionLengthMatchesTheRealEncoder() {
        for (int i = 0; i < SHAPES.length; i++) {
            byte[] section = encode(SHAPES[i][0], SHAPES[i][1], i);
            assertEquals(section.length, SectionCodec.sectionLength(section, 0), "shape " + i);
        }
    }

    @Test
    void shiftedResliceDecodesWithGeysersReader() throws Exception {
        List<byte[]> sections = new ArrayList<>();
        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        for (int i = 0; i < 24; i++) {
            byte[] s = encode(SHAPES[i % SHAPES.length][0], SHAPES[i % SHAPES.length][1], 100 + i);
            sections.add(s);
            payload.write(s);
        }
        int shift = 5;
        SectionCodec.Result r = SectionCodec.resliceTolerant(payload.toByteArray(), 24, shift, 24);
        assertFalse(r.anomaly());
        assertEquals(24, r.sectionsParsed());

        ByteBuf in = Unpooled.wrappedBuffer(r.data());
        for (int w = 0; w < 24; w++) {
            int start = in.readerIndex();
            MinecraftTypes.readChunkSection(in, BLOCK_GLOBAL_BITS, BIOME_GLOBAL_BITS);
            byte[] got = new byte[in.readerIndex() - start];
            in.getBytes(start, got);
            byte[] want = w + shift < 24 ? sections.get(w + shift) : SectionCodec.EMPTY_SECTION;
            assertArrayEquals(want, got, "window section " + w);
        }
        assertEquals(0, in.readableBytes());
    }

    @Test
    void negativeShiftMovesSectionsUpAndFillsTheBottomWithAir() throws Exception {
        List<byte[]> sections = new ArrayList<>();
        ByteArrayOutputStream payload = new ByteArrayOutputStream();
        for (int i = 0; i < 24; i++) {
            byte[] s = encode(SHAPES[i % SHAPES.length][0], SHAPES[i % SHAPES.length][1], 300 + i);
            sections.add(s);
            payload.write(s);
        }
        int shift = -5; // window section w shows real section w - 5
        SectionCodec.Result r = SectionCodec.resliceTolerant(payload.toByteArray(), 24, shift, 24);
        assertFalse(r.anomaly());

        ByteBuf in = Unpooled.wrappedBuffer(r.data());
        for (int w = 0; w < 24; w++) {
            int start = in.readerIndex();
            MinecraftTypes.readChunkSection(in, BLOCK_GLOBAL_BITS, BIOME_GLOBAL_BITS);
            byte[] got = new byte[in.readerIndex() - start];
            in.getBytes(start, got);
            byte[] want = w + shift >= 0 ? sections.get(w + shift) : SectionCodec.EMPTY_SECTION;
            assertArrayEquals(want, got, "window section " + w);
        }
        assertEquals(0, in.readableBytes());
    }
}
