package dev.voicewarden.core.ipc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;

class IpcAuthTokenTest {

    @Test
    void generatesDistinct256BitTokens() {
        IpcAuthToken a = IpcAuthToken.generate();
        IpcAuthToken b = IpcAuthToken.generate();
        assertEquals(32, a.bytes().length);
        assertNotEquals(a, b);
        assertEquals(64, a.toHex().length());
    }

    @Test
    void roundTripsThroughHexAndEnvironment() {
        IpcAuthToken token = IpcAuthToken.generate();
        assertEquals(token, IpcAuthToken.fromHex(token.toHex()));
        assertEquals(token, IpcAuthToken.fromEnvironment(Map.of(IpcAuthToken.ENV_VAR, token.toHex())));
        assertThrows(IllegalStateException.class, () -> IpcAuthToken.fromEnvironment(Map.of()));
    }

    @Test
    void matchesOnlyTheExactBytes() {
        IpcAuthToken token = IpcAuthToken.generate();
        byte[] same = token.bytes();
        assertTrue(token.matches(same));
        byte[] flipped = token.bytes();
        flipped[31] ^= 1;
        assertFalse(token.matches(flipped));
        assertFalse(token.matches(new byte[16]));
        assertFalse(token.matches((byte[]) null));
    }

    @Test
    void copiesBytesDefensively() {
        IpcAuthToken token = IpcAuthToken.generate();
        byte[] exposed = token.bytes();
        exposed[0] ^= 1;
        assertFalse(token.matches(exposed));
    }

    @Test
    void rejectsWrongLengthsAndBadHex() {
        assertThrows(IllegalArgumentException.class, () -> IpcAuthToken.of(new byte[31]));
        assertThrows(IllegalArgumentException.class, () -> IpcAuthToken.fromHex("abcd"));
        assertThrows(IllegalArgumentException.class, () -> IpcAuthToken.fromHex("z".repeat(64)));
    }

    @Test
    void neverPrintsTheSecret() {
        IpcAuthToken token = IpcAuthToken.generate();
        assertFalse(token.toString().contains(token.toHex()));
        assertFalse(new IpcMessage.AuthRequest(token).toString().contains(token.toHex()));
    }
}
