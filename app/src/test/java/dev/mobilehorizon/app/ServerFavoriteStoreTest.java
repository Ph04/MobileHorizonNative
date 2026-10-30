package dev.mobilehorizon.app;

import org.junit.Test;
import static org.junit.Assert.*;

public final class ServerFavoriteStoreTest {
    @Test public void acceptsDomainAndPort() {
        assertEquals("play.example.net:25565",
                ServerFavoriteStore.validateAddress(" play.example.net:25565 "));
    }

    @Test public void acceptsBracketedIpv6() {
        assertEquals("[2001:db8::1]:25565",
                ServerFavoriteStore.validateAddress("[2001:db8::1]:25565"));
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsUrlScheme() {
        ServerFavoriteStore.validateAddress("https://example.net");
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsOutOfRangePort() {
        ServerFavoriteStore.validateAddress("example.net:65536");
    }

    @Test(expected = IllegalArgumentException.class)
    public void rejectsPathTraversalLikeAddress() {
        ServerFavoriteStore.validateAddress("example.net/path");
    }
}
