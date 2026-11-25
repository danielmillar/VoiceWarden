package dev.voicewarden.core.audio;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OpusFramePoolTest {

    @Test
    void returnsNullAndCountsWhenExhausted() {
        OpusFramePool pool = new OpusFramePool(4, 1276);
        List<PooledOpusFrame> held = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            held.add(assertNotNullFrame(pool.acquire()));
        }
        assertNull(pool.acquire());
        assertNull(pool.acquire());
        assertEquals(2, pool.exhaustedCount());
        pool.release(held.remove(0));
        assertNotNull(pool.acquire());
    }

    @Test
    void releaseClearsReferencesAndFramesAreReused() {
        OpusFramePool pool = new OpusFramePool(1, 1276);
        PooledOpusFrame frame = pool.acquire();
        frame.fillOpus(UUID.randomUUID(), new byte[] {1, 2, 3}, 0, 3, 99L);
        assertEquals(FrameKind.OPUS, frame.kind());
        pool.release(frame);
        assertNull(frame.playerId());
        assertNull(frame.kind());
        assertEquals(0, frame.opusLength());
        assertSame(frame, pool.acquire());
    }

    @Test
    void pcmBufferIsReusedAcrossFills() {
        PooledOpusFrame frame = new PooledOpusFrame(1276);
        UUID player = UUID.randomUUID();
        frame.fillPcm48k(player, new short[960], 0, 960, 1L);
        short[] first = frame.pcm48kData();
        frame.fillPcm48k(player, new short[2880], 0, 2880, 2L);
        assertSame(first, frame.pcm48kData());
        frame.fillSpeakingStopped(player, 3L);
        assertEquals(FrameKind.SPEAKING_STOPPED, frame.kind());
        assertEquals(true, frame.voiceStopped());
    }

    private static PooledOpusFrame assertNotNullFrame(PooledOpusFrame frame) {
        assertNotNull(frame);
        return frame;
    }
}
