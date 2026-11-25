package dev.voicewarden.core.ipc;

public enum IpcMessageType {
    AUTH_REQ(0x01),
    AUTH_OK(0x02),
    AUTH_FAIL(0x03),
    PING(0x04),
    PONG(0x05),
    AUDIO_LIVE_CHUNK(0x10),
    AUDIO_FINAL(0x11),
    VERIFY_REQUEST(0x12),
    DROP_UTTERANCE(0x13),
    TRANSCRIPT_RESULT(0x20),
    ENGINE_STATUS_REQ(0x30),
    ENGINE_STATUS_RESP(0x31),
    SHUTDOWN(0x7F);

    private static final IpcMessageType[] BY_ID = new IpcMessageType[256];

    static {
        for (IpcMessageType type : values()) {
            BY_ID[type.id & 0xFF] = type;
        }
    }

    private final byte id;

    IpcMessageType(int id) {
        this.id = (byte) id;
    }

    public byte id() {
        return id;
    }

    public static IpcMessageType fromId(byte id) throws IpcProtocolException {
        IpcMessageType type = BY_ID[id & 0xFF];
        if (type == null) {
            throw new IpcProtocolException(String.format("Unknown IPC message type 0x%02X", id & 0xFF));
        }
        return type;
    }
}
