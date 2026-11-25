package dev.voicewarden.core.ipc;

import dev.voicewarden.api.ConfirmationState;
import dev.voicewarden.core.ipc.IpcMessage.AudioFinal;
import dev.voicewarden.core.ipc.IpcMessage.AudioLiveChunk;
import dev.voicewarden.core.ipc.IpcMessage.AuthFail;
import dev.voicewarden.core.ipc.IpcMessage.AuthOk;
import dev.voicewarden.core.ipc.IpcMessage.AuthRequest;
import dev.voicewarden.core.ipc.IpcMessage.DropUtterance;
import dev.voicewarden.core.ipc.IpcMessage.EngineStatusRequest;
import dev.voicewarden.core.ipc.IpcMessage.EngineStatusResponse;
import dev.voicewarden.core.ipc.IpcMessage.Ping;
import dev.voicewarden.core.ipc.IpcMessage.Pong;
import dev.voicewarden.core.ipc.IpcMessage.Shutdown;
import dev.voicewarden.core.ipc.IpcMessage.TranscriptResult;
import dev.voicewarden.core.ipc.IpcMessage.VerifyRequest;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.ReadableByteChannel;
import java.nio.channels.WritableByteChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

public final class IpcFrameCodec {

    public static final int HEADER_BYTES = 5;
    public static final int MAX_PAYLOAD_BYTES = 8 * 1024 * 1024;
    public static final int MAX_SAMPLES_PER_FRAME = (MAX_PAYLOAD_BYTES - 64) / 2;

    public static final int FLAG_END_OF_UTTERANCE = 0x01;
    public static final int FLAG_REQUEST_DOUBLE_CHECK = 0x01;
    public static final int FLAG_IS_FINAL = 0x01;
    public static final int FLAG_IS_LIVE = 0x02;
    public static final int FLAG_IS_VERIFICATION_RESULT = 0x04;

    public static final byte CONFIRMATION_UNCHECKED = 0;
    public static final byte CONFIRMATION_CONFIRMED = 1;
    public static final byte CONFIRMATION_UNCONFIRMED = 2;

    private static final int UUID_BYTES = 16;
    private static final int STRING_PREFIX_BYTES = 4;

    private IpcFrameCodec() {
    }

    public static ByteBuffer encode(IpcMessage message) throws IpcProtocolException {
        byte[][] strings = stringsOf(message);
        long payloadSize = payloadSize(message, strings);
        if (payloadSize > MAX_PAYLOAD_BYTES) {
            throw new IpcProtocolException(message.type() + " payload of " + payloadSize
                    + " bytes exceeds the " + MAX_PAYLOAD_BYTES + " byte frame limit");
        }
        ByteBuffer buf = ByteBuffer.allocate(HEADER_BYTES + (int) payloadSize).order(ByteOrder.BIG_ENDIAN);
        buf.putInt((int) payloadSize);
        buf.put(message.type().id());
        writePayload(buf, message, strings);
        return buf.flip();
    }

    public static void writeFrame(WritableByteChannel channel, IpcMessage message) throws IOException {
        ByteBuffer frame = encode(message);
        while (frame.hasRemaining()) {
            channel.write(frame);
        }
    }

    public static void writeFrame(OutputStream out, IpcMessage message) throws IOException {
        ByteBuffer frame = encode(message);
        out.write(frame.array(), frame.arrayOffset() + frame.position(), frame.remaining());
        out.flush();
    }

    public static IpcMessage readFrame(ReadableByteChannel channel) throws IOException {
        ByteBuffer header = ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.BIG_ENDIAN);
        readFully(channel, header, true);
        int length = header.getInt(0);
        IpcMessageType type = checkHeader(length, header.get(4));
        ByteBuffer payload = ByteBuffer.allocate(length).order(ByteOrder.BIG_ENDIAN);
        readFully(channel, payload, false);
        return decode(type, payload.flip());
    }

    public static IpcMessage readFrame(InputStream in) throws IOException {
        byte[] header = new byte[HEADER_BYTES];
        readFully(in, header, true);
        ByteBuffer headerBuf = ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN);
        int length = headerBuf.getInt(0);
        IpcMessageType type = checkHeader(length, headerBuf.get(4));
        byte[] payload = new byte[length];
        readFully(in, payload, false);
        return decode(type, ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN));
    }

    public static IpcMessage decode(IpcMessageType type, ByteBuffer payload) throws IpcProtocolException {
        ByteBuffer buf = payload.order(ByteOrder.BIG_ENDIAN);
        try {
            IpcMessage message = decodePayload(type, buf);
            if (buf.hasRemaining()) {
                throw new IpcProtocolException(type + " frame has " + buf.remaining() + " unexpected trailing bytes");
            }
            return message;
        } catch (BufferUnderflowException e) {
            throw new IpcProtocolException(type + " frame payload is truncated", e);
        }
    }

    public static byte encodeConfirmation(ConfirmationState state) {
        return switch (state) {
            case UNCHECKED -> CONFIRMATION_UNCHECKED;
            case CONFIRMED -> CONFIRMATION_CONFIRMED;
            case UNCONFIRMED -> CONFIRMATION_UNCONFIRMED;
        };
    }

    public static ConfirmationState decodeConfirmation(byte value) throws IpcProtocolException {
        return switch (value) {
            case CONFIRMATION_UNCHECKED -> ConfirmationState.UNCHECKED;
            case CONFIRMATION_CONFIRMED -> ConfirmationState.CONFIRMED;
            case CONFIRMATION_UNCONFIRMED -> ConfirmationState.UNCONFIRMED;
            default -> throw new IpcProtocolException("Unknown confirmation state " + (value & 0xFF));
        };
    }

    private static IpcMessageType checkHeader(int length, byte typeId) throws IpcProtocolException {
        if (length < 0 || length > MAX_PAYLOAD_BYTES) {
            throw new IpcProtocolException("Frame payload length " + Integer.toUnsignedString(length)
                    + " is outside 0.." + MAX_PAYLOAD_BYTES);
        }
        return IpcMessageType.fromId(typeId);
    }

    private static byte[][] stringsOf(IpcMessage message) {
        return switch (message) {
            case AuthOk m -> new byte[][] {utf8(m.engineVersion()), utf8(m.profile())};
            case AuthFail m -> new byte[][] {utf8(m.reason())};
            case VerifyRequest m -> new byte[][] {utf8(m.primaryTranscript())};
            case TranscriptResult m -> new byte[][] {utf8(m.text()), utf8(m.language())};
            case EngineStatusResponse m -> new byte[][] {utf8(m.profile())};
            default -> new byte[0][];
        };
    }

    private static long payloadSize(IpcMessage message, byte[][] strings) {
        long stringBytes = 0;
        for (byte[] s : strings) {
            stringBytes += STRING_PREFIX_BYTES + s.length;
        }
        long fixed = switch (message) {
            case AuthRequest m -> IpcAuthToken.LENGTH_BYTES;
            case AuthOk m -> 0;
            case AuthFail m -> 0;
            case Ping m -> Long.BYTES;
            case Pong m -> Long.BYTES;
            case AudioLiveChunk m -> UUID_BYTES + Long.BYTES + Integer.BYTES + 1 + Integer.BYTES + 2L * m.pcm16().length;
            case AudioFinal m -> UUID_BYTES + Long.BYTES + 1 + Integer.BYTES + 2L * m.pcm16().length;
            case VerifyRequest m -> UUID_BYTES + Long.BYTES + Integer.BYTES + 2L * m.pcm16().length;
            case DropUtterance m -> UUID_BYTES + Long.BYTES;
            case TranscriptResult m -> UUID_BYTES + Long.BYTES + 1 + 1 + Integer.BYTES;
            case EngineStatusRequest m -> 0;
            case EngineStatusResponse m -> 5L * Long.BYTES + Integer.BYTES + 1;
            case Shutdown m -> 0;
        };
        return fixed + stringBytes;
    }

    private static void writePayload(ByteBuffer buf, IpcMessage message, byte[][] strings) {
        switch (message) {
            case AuthRequest m -> buf.put(m.token().bytes());
            case AuthOk m -> {
                putString(buf, strings[0]);
                putString(buf, strings[1]);
            }
            case AuthFail m -> putString(buf, strings[0]);
            case Ping m -> buf.putLong(m.nonce());
            case Pong m -> buf.putLong(m.nonce());
            case AudioLiveChunk m -> {
                putUuid(buf, m.playerId());
                buf.putLong(m.utteranceId());
                buf.putInt(m.chunkIndex());
                buf.put((byte) (m.endOfUtterance() ? FLAG_END_OF_UTTERANCE : 0));
                putPcm(buf, m.pcm16());
            }
            case AudioFinal m -> {
                putUuid(buf, m.playerId());
                buf.putLong(m.utteranceId());
                buf.put((byte) (m.requestDoubleCheck() ? FLAG_REQUEST_DOUBLE_CHECK : 0));
                putPcm(buf, m.pcm16());
            }
            case VerifyRequest m -> {
                putUuid(buf, m.playerId());
                buf.putLong(m.utteranceId());
                putString(buf, strings[0]);
                putPcm(buf, m.pcm16());
            }
            case DropUtterance m -> {
                putUuid(buf, m.playerId());
                buf.putLong(m.utteranceId());
            }
            case TranscriptResult m -> {
                putUuid(buf, m.playerId());
                buf.putLong(m.utteranceId());
                int flags = (m.isFinal() ? FLAG_IS_FINAL : 0)
                        | (m.isLive() ? FLAG_IS_LIVE : 0)
                        | (m.isVerificationResult() ? FLAG_IS_VERIFICATION_RESULT : 0);
                buf.put((byte) flags);
                buf.put(encodeConfirmation(m.confirmationState()));
                putString(buf, strings[0]);
                putString(buf, strings[1]);
                buf.putInt(m.latencyMs());
            }
            case EngineStatusRequest m -> {
            }
            case EngineStatusResponse m -> {
                buf.putLong(m.uptimeMs());
                buf.putLong(m.processedLive());
                buf.putLong(m.processedFinal());
                buf.putLong(m.processedVerify());
                buf.putLong(m.droppedOverload());
                buf.putInt(m.queuedJobs());
                putString(buf, strings[0]);
                buf.put((byte) (m.ready() ? 1 : 0));
            }
            case Shutdown m -> {
            }
        }
    }

    private static IpcMessage decodePayload(IpcMessageType type, ByteBuffer buf) throws IpcProtocolException {
        return switch (type) {
            case AUTH_REQ -> {
                if (buf.remaining() != IpcAuthToken.LENGTH_BYTES) {
                    throw new IpcProtocolException("AUTH_REQ token must be " + IpcAuthToken.LENGTH_BYTES
                            + " bytes, got " + buf.remaining());
                }
                byte[] token = new byte[IpcAuthToken.LENGTH_BYTES];
                buf.get(token);
                yield new AuthRequest(IpcAuthToken.of(token));
            }
            case AUTH_OK -> new AuthOk(getString(buf), getString(buf));
            case AUTH_FAIL -> new AuthFail(getString(buf));
            case PING -> new Ping(buf.getLong());
            case PONG -> new Pong(buf.getLong());
            case AUDIO_LIVE_CHUNK -> {
                UUID playerId = getUuid(buf);
                long utteranceId = buf.getLong();
                int chunkIndex = buf.getInt();
                int flags = getFlags(buf, FLAG_END_OF_UTTERANCE, type);
                yield new AudioLiveChunk(playerId, utteranceId, chunkIndex, (flags & FLAG_END_OF_UTTERANCE) != 0, getPcm(buf));
            }
            case AUDIO_FINAL -> {
                UUID playerId = getUuid(buf);
                long utteranceId = buf.getLong();
                int flags = getFlags(buf, FLAG_REQUEST_DOUBLE_CHECK, type);
                yield new AudioFinal(playerId, utteranceId, (flags & FLAG_REQUEST_DOUBLE_CHECK) != 0, getPcm(buf));
            }
            case VERIFY_REQUEST -> new VerifyRequest(getUuid(buf), buf.getLong(), getString(buf), getPcm(buf));
            case DROP_UTTERANCE -> new DropUtterance(getUuid(buf), buf.getLong());
            case TRANSCRIPT_RESULT -> {
                UUID playerId = getUuid(buf);
                long utteranceId = buf.getLong();
                int flags = getFlags(buf, FLAG_IS_FINAL | FLAG_IS_LIVE | FLAG_IS_VERIFICATION_RESULT, type);
                ConfirmationState state = decodeConfirmation(buf.get());
                yield new TranscriptResult(
                        playerId,
                        utteranceId,
                        (flags & FLAG_IS_FINAL) != 0,
                        (flags & FLAG_IS_LIVE) != 0,
                        (flags & FLAG_IS_VERIFICATION_RESULT) != 0,
                        state,
                        getString(buf),
                        getString(buf),
                        buf.getInt());
            }
            case ENGINE_STATUS_REQ -> new EngineStatusRequest();
            case ENGINE_STATUS_RESP -> new EngineStatusResponse(
                    buf.getLong(),
                    buf.getLong(),
                    buf.getLong(),
                    buf.getLong(),
                    buf.getLong(),
                    buf.getInt(),
                    getString(buf),
                    getBoolean(buf));
            case SHUTDOWN -> new Shutdown();
        };
    }

    private static byte[] utf8(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static void putString(ByteBuffer buf, byte[] utf8) {
        buf.putInt(utf8.length);
        buf.put(utf8);
    }

    private static String getString(ByteBuffer buf) throws IpcProtocolException {
        int length = buf.getInt();
        if (length < 0 || length > buf.remaining()) {
            throw new IpcProtocolException("String length " + length + " exceeds the " + buf.remaining()
                    + " bytes left in the frame");
        }
        ByteBuffer slice = buf.slice(buf.position(), length);
        buf.position(buf.position() + length);
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(slice)
                    .toString();
        } catch (CharacterCodingException e) {
            throw new IpcProtocolException("String is not valid UTF-8", e);
        }
    }

    private static void putUuid(ByteBuffer buf, UUID uuid) {
        buf.putLong(uuid.getMostSignificantBits());
        buf.putLong(uuid.getLeastSignificantBits());
    }

    private static UUID getUuid(ByteBuffer buf) {
        return new UUID(buf.getLong(), buf.getLong());
    }

    private static int getFlags(ByteBuffer buf, int allowed, IpcMessageType type) throws IpcProtocolException {
        int flags = buf.get() & 0xFF;
        if ((flags & ~allowed) != 0) {
            throw new IpcProtocolException(type + " has undefined flag bits 0x" + Integer.toHexString(flags & ~allowed));
        }
        return flags;
    }

    private static boolean getBoolean(ByteBuffer buf) throws IpcProtocolException {
        byte value = buf.get();
        if (value != 0 && value != 1) {
            throw new IpcProtocolException("Boolean byte must be 0 or 1, got " + (value & 0xFF));
        }
        return value == 1;
    }

    private static void putPcm(ByteBuffer buf, short[] pcm) {
        buf.putInt(pcm.length);
        int start = buf.position();
        buf.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().put(pcm);
        buf.order(ByteOrder.BIG_ENDIAN).position(start + pcm.length * 2);
    }

    private static short[] getPcm(ByteBuffer buf) throws IpcProtocolException {
        int sampleCount = buf.getInt();
        long byteCount = 2L * sampleCount;
        if (sampleCount < 0 || byteCount != buf.remaining()) {
            throw new IpcProtocolException("PCM sample count " + sampleCount + " does not match the "
                    + buf.remaining() + " PCM bytes in the frame");
        }
        short[] pcm = new short[sampleCount];
        int start = buf.position();
        buf.order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(pcm);
        buf.order(ByteOrder.BIG_ENDIAN).position(start + (int) byteCount);
        return pcm;
    }

    private static void readFully(ReadableByteChannel channel, ByteBuffer buf, boolean frameStart) throws IOException {
        while (buf.hasRemaining()) {
            int n = channel.read(buf);
            if (n < 0) {
                throw eof(frameStart && buf.position() == 0, buf.position(), buf.capacity());
            }
        }
    }

    private static void readFully(InputStream in, byte[] dst, boolean frameStart) throws IOException {
        int off = 0;
        while (off < dst.length) {
            int n = in.read(dst, off, dst.length - off);
            if (n < 0) {
                throw eof(frameStart && off == 0, off, dst.length);
            }
            off += n;
        }
    }

    private static IOException eof(boolean clean, int read, int expected) {
        if (clean) {
            return new EOFException("IPC peer closed the connection");
        }
        return new IpcProtocolException("IPC stream ended mid-frame after " + read + " of " + expected + " bytes");
    }
}
