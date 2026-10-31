package dev.mobilehorizon.compat.api;

import org.junit.Test;

import static org.junit.Assert.assertEquals;

public final class ProtocolCodecTest {
    @Test
    public void roundTripsUnicodeAndProtocolSeparators() {
        String value = "Olá | Forge\nHorizon 雪";
        assertEquals(value, ProtocolCodec.decode(ProtocolCodec.encode(value)));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsOddHexLength() {
        ProtocolCodec.decode("abc");
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsNonHexCharacters() {
        ProtocolCodec.decode("gg");
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsLineProtocolDelimiters() {
        ProtocolCodec.requireSingleLine("one|two", "test", 20);
    }
}
