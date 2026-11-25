package dev.voicewarden.core.ipc;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.voicewarden.api.ConfirmationState;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.Channels;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

class IpcFrameCodecTest {

    private static final UUID PLAYER = UUID.fromString("0f8fad5b-d9cb-469f-a165-70867728950e");

    static Stream<IpcMessage> everyMessage() {
        return Stream.of(
                new IpcMessage.AuthRequest(IpcAuthToken.generate()),
                new IpcMessage.AuthOk("1.2.3", "ENGLISH_ACCURATE"),
                new IpcMessage.AuthFail("invalid auth token"),
                new IpcMessage.Ping(Long.MIN_VALUE),
                new IpcMessage.Pong(0x0123_4567_89AB_CDEFL),
                new IpcMessage.AudioLiveChunk(PLAYER, 42L, 7, true, ramp(3_200)),
                new IpcMessage.AudioLiveChunk(PLAYER, -1L, 0, false, new short[0]),
                new IpcMessage.AudioFinal(PLAYER, 43L, true, ramp(16_000)),
                new IpcMessage.AudioFinal(PLAYER, 44L, false, ramp(5)),
                new IpcMessage.VerifyRequest(PLAYER, 45L, "you absolute 🤬 — naïve", ramp(8_000)),
                new IpcMessage.DropUtterance(PLAYER, 46L),
                new IpcMessage.TranscriptResult(PLAYER, 47L, true, false, true, ConfirmationState.CONFIRMED,
                        "größte Übung 漢字", "de", 312),
                new IpcMessage.TranscriptResult(PLAYER, 48L, false, true, false, ConfirmationState.UNCHECKED, "", "", 0),
                new IpcMessage.TranscriptResult(PLAYER, 49L, true, true, true, ConfirmationState.UNCONFIRMED,
                        "hello", "en", Integer.MAX_VALUE),
                new IpcMessage.EngineStatusRequest(),
                new IpcMessage.EngineStatusResponse(1L, 2L, 3L, 4L, 5L, 6, "MULTILANG", true),
                new IpcMessage.EngineStatusResponse(0L, 0L, 0L, 0L, Long.MAX_VALUE, 0, "", false),
                new IpcMessage.Shutdown());
    }

    @Test
    void coversEveryMessageType() {
        Set<IpcMessageType> covered = everyMessage().map(IpcMessage::type).collect(Collectors.toSet());
        assertEquals(EnumSet.allOf(IpcMessageType.class), covered);
    }

    @ParameterizedTest
    @MethodSource("everyMessage")
    void roundTripsThroughChannel(IpcMessage message) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        IpcFrameCodec.writeFrame(Channels.newChannel(out), message);
        IpcMessage decoded = IpcFrameCodec.readFrame(Channels.newChannel(new ByteArrayInputStream(out.toByteArray())));
        assertEquals(message, decoded);
        assertEquals(message.hashCode(), decoded.hashCode());
    }

    @ParameterizedTest
    @MethodSource("everyMessage")
    void roundTripsThroughStream(IpcMessage message) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        IpcFrameCodec.writeFrame(out, message);
        assertEquals(message, IpcFrameCodec.readFrame(new ByteArrayInputStream(out.toByteArray())));
    }

    @Test
    void readsBackToBackFramesThenCleanEof() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        List<IpcMessage> messages = everyMessage().toList();
        for (IpcMessage message : messages) {
            IpcFrameCodec.writeFrame(out, message);
        }
        ByteArrayInputStream in = new ByteArrayInputStream(out.toByteArray());
        for (IpcMessage message : messages) {
            assertEquals(message, IpcFrameCodec.readFrame(in));
        }
        assertThrows(EOFException.class, () -> IpcFrameCodec.readFrame(in));
    }

    @Test
    void roundTripsLargePcmBuffer() throws IOException {
        short[] pcm = new short[16_000 * 60];
        for (int i = 0; i < pcm.length; i++) {
            pcm[i] = (short) (i * 7919);
        }
        IpcMessage.AudioFinal message = new IpcMessage.AudioFinal(PLAYER, 1L, true, pcm);
        ByteBuffer frame = IpcFrameCodec.encode(message);
        assertEquals(IpcFrameCodec.HEADER_BYTES + 16 + 8 + 1 + 4 + pcm.length * 2, frame.remaining());
        IpcMessage.AudioFinal decoded = (IpcMessage.AudioFinal) IpcFrameCodec.readFrame(
                new ByteArrayInputStream(frame.array()));
        assertArrayEquals(pcm, decoded.pcm16());
    }

    @Test
    void writesBigEndianHeaderAndLittleEndianPcm() throws IOException {
        IpcMessage.AudioLiveChunk chunk = new IpcMessage.AudioLiveChunk(
                new UUID(0x0102030405060708L, 0x090A0B0C0D0E0F10L), 0x1112131415161718L, 0x191A1B1C, true,
                new short[] {0x0201, (short) 0xFFFE});
        ByteBuffer frame = IpcFrameCodec.encode(chunk);
        byte[] bytes = new byte[frame.remaining()];
        frame.get(bytes);
        ByteBuffer be = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN);
        int payloadLength = 16 + 8 + 4 + 1 + 4 + 4;
        assertEquals(payloadLength, be.getInt(0));
        assertEquals(0x10, bytes[4]);
        assertEquals(0x0102030405060708L, be.getLong(5));
        assertEquals(0x090A0B0C0D0E0F10L, be.getLong(13));
        assertEquals(0x1112131415161718L, be.getLong(21));
        assertEquals(0x191A1B1C, be.getInt(29));
        assertEquals(IpcFrameCodec.FLAG_END_OF_UTTERANCE, bytes[33]);
        assertEquals(2, be.getInt(34));
        assertArrayEquals(new byte[] {0x01, 0x02, (byte) 0xFE, (byte) 0xFF}, Arrays.copyOfRange(bytes, 38, 42));
    }

    @Test
    void mapsConfirmationStateToFixedWireValues() throws IpcProtocolException {
        assertEquals(0, IpcFrameCodec.encodeConfirmation(ConfirmationState.UNCHECKED));
        assertEquals(1, IpcFrameCodec.encodeConfirmation(ConfirmationState.CONFIRMED));
        assertEquals(2, IpcFrameCodec.encodeConfirmation(ConfirmationState.UNCONFIRMED));
        for (ConfirmationState state : ConfirmationState.values()) {
            assertEquals(state, IpcFrameCodec.decodeConfirmation(IpcFrameCodec.encodeConfirmation(state)));
        }
        assertThrows(IpcProtocolException.class, () -> IpcFrameCodec.decodeConfirmation((byte) 3));
    }

    @Test
    void rejectsOversizedLengthBeforeAllocating() {
        byte[] header = ByteBuffer.allocate(5).putInt(IpcFrameCodec.MAX_PAYLOAD_BYTES + 1).put((byte) 0x04).array();
        IpcProtocolException e = assertThrows(IpcProtocolException.class,
                () -> IpcFrameCodec.readFrame(new ByteArrayInputStream(header)));
        assertTrue(e.getMessage().contains("outside"));
        byte[] negative = ByteBuffer.allocate(5).putInt(-1).put((byte) 0x04).array();
        assertThrows(IpcProtocolException.class,
                () -> IpcFrameCodec.readFrame(Channels.newChannel(new ByteArrayInputStream(negative))));
    }

    @Test
    void refusesToEncodeOversizedFrame() {
        short[] pcm = new short[IpcFrameCodec.MAX_PAYLOAD_BYTES / 2];
        assertThrows(IpcProtocolException.class,
                () -> IpcFrameCodec.encode(new IpcMessage.AudioFinal(PLAYER, 1L, false, pcm)));
        short[] fits = new short[IpcFrameCodec.MAX_SAMPLES_PER_FRAME];
        assertDoesNotThrow(() -> IpcFrameCodec.encode(new IpcMessage.AudioFinal(PLAYER, 1L, false, fits)));
    }

    @Test
    void rejectsUnknownMessageType() {
        byte[] frame = ByteBuffer.allocate(5).putInt(0).put((byte) 0x55).array();
        IpcProtocolException e = assertThrows(IpcProtocolException.class,
                () -> IpcFrameCodec.readFrame(new ByteArrayInputStream(frame)));
        assertTrue(e.getMessage().contains("0x55"));
    }

    @Test
    void reportsTruncationMidFrameAsProtocolError() throws IOException {
        byte[] full = frameBytes(new IpcMessage.Ping(9L));
        for (int cut = 1; cut < full.length; cut++) {
            byte[] truncated = Arrays.copyOf(full, cut);
            assertThrows(IpcProtocolException.class,
                    () -> IpcFrameCodec.readFrame(new ByteArrayInputStream(truncated)), "cut at " + cut);
        }
        assertThrows(EOFException.class, () -> IpcFrameCodec.readFrame(new ByteArrayInputStream(new byte[0])));
    }

    @Test
    void rejectsPayloadShorterThanItsFields() {
        ByteBuffer payload = ByteBuffer.allocate(4).putInt(7).flip();
        assertThrows(IpcProtocolException.class, () -> IpcFrameCodec.decode(IpcMessageType.PING, payload));
    }

    @Test
    void rejectsTrailingBytes() {
        ByteBuffer payload = ByteBuffer.allocate(9).putLong(1L).put((byte) 0).flip();
        IpcProtocolException e = assertThrows(IpcProtocolException.class,
                () -> IpcFrameCodec.decode(IpcMessageType.PONG, payload));
        assertTrue(e.getMessage().contains("trailing"));
    }

    @Test
    void rejectsSampleCountThatDisagreesWithPayload() {
        ByteBuffer payload = ByteBuffer.allocate(16 + 8 + 1 + 4 + 4)
                .putLong(1L).putLong(2L).putLong(3L).put((byte) 0).putInt(3).putInt(0).flip();
        assertThrows(IpcProtocolException.class, () -> IpcFrameCodec.decode(IpcMessageType.AUDIO_FINAL, payload));
        ByteBuffer negative = ByteBuffer.allocate(16 + 8 + 1 + 4)
                .putLong(1L).putLong(2L).putLong(3L).put((byte) 0).putInt(Integer.MIN_VALUE).flip();
        assertThrows(IpcProtocolException.class, () -> IpcFrameCodec.decode(IpcMessageType.AUDIO_FINAL, negative));
    }

    @Test
    void rejectsStringLengthPastEndOfFrame() {
        ByteBuffer payload = ByteBuffer.allocate(8).putInt(100).putInt(0).flip();
        assertThrows(IpcProtocolException.class, () -> IpcFrameCodec.decode(IpcMessageType.AUTH_FAIL, payload));
    }

    @Test
    void rejectsMalformedUtf8() {
        ByteBuffer payload = ByteBuffer.allocate(6).putInt(2).put((byte) 0xC3).put((byte) 0x28).flip();
        assertThrows(IpcProtocolException.class, () -> IpcFrameCodec.decode(IpcMessageType.AUTH_FAIL, payload));
    }

    @Test
    void rejectsUndefinedFlagBits() {
        ByteBuffer payload = ByteBuffer.allocate(16 + 8 + 1 + 4)
                .putLong(1L).putLong(2L).putLong(3L).put((byte) 0x02).putInt(0).flip();
        assertThrows(IpcProtocolException.class, () -> IpcFrameCodec.decode(IpcMessageType.AUDIO_FINAL, payload));
    }

    @Test
    void rejectsWrongTokenLength() {
        ByteBuffer payload = ByteBuffer.allocate(31).flip().limit(31);
        assertThrows(IpcProtocolException.class, () -> IpcFrameCodec.decode(IpcMessageType.AUTH_REQ, payload));
    }

    @Test
    void encodesUtf8StringsByByteLength() throws IOException {
        String text = "日本語";
        ByteBuffer frame = IpcFrameCodec.encode(new IpcMessage.AuthFail(text));
        assertEquals(4 + text.getBytes(StandardCharsets.UTF_8).length, frame.getInt(0));
    }

    private static byte[] frameBytes(IpcMessage message) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        IpcFrameCodec.writeFrame(out, message);
        return out.toByteArray();
    }

    static short[] ramp(int samples) {
        short[] pcm = new short[samples];
        for (int i = 0; i < samples; i++) {
            pcm[i] = (short) ((i * 37) - 16_000);
        }
        return pcm;
    }
}
