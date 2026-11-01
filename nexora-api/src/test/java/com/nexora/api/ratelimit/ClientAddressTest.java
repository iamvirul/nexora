package com.nexora.api.ratelimit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ClientAddressTest {

    private static final String PROXY = "10.0.0.5";
    private static final String EDGE_PROXY = "10.0.0.9";
    private static final Set<String> TRUSTED = Set.of(PROXY, EDGE_PROXY);

    @Test
    void untrustedConnectionIsLimitedByItsOwnAddressWhateverItForwards() {
        assertEquals("203.0.113.7", resolve("203.0.113.7", List.of("198.51.100.1")));
    }

    @Test
    void trustedProxyNamesTheClientAsTheRightmostUntrustedHop() {
        // The client wrote the first entry itself; only what our proxies appended counts.
        assertEquals("198.51.100.1", resolve(PROXY, List.of("1.1.1.1, 198.51.100.1")));
        assertEquals("198.51.100.1", resolve(PROXY, List.of("1.1.1.1, 198.51.100.1, " + EDGE_PROXY)));
        assertEquals("198.51.100.1", resolve(PROXY, List.of("1.1.1.1", "198.51.100.1")));
    }

    @Test
    void trustedProxyWithoutUsableHeaderIsTheClient() {
        assertEquals(PROXY, resolve(PROXY, List.of()));
        assertEquals(PROXY, resolve(PROXY, null));
        assertEquals(PROXY, resolve(PROXY, List.of(EDGE_PROXY)));
        assertEquals(PROXY, resolve(PROXY, List.of("198.51.100.1, not-an-ip")));
        assertEquals(PROXY, resolve(PROXY, List.of("999.1.1.1")));
    }

    @Test
    void hostNamesInTheHeaderAreNeverResolved() {
        // A DNS lookup per request would be a cost any client could impose; names are simply rejected.
        assertTrue(ClientAddress.parseLiteral("localhost").isEmpty());
        assertTrue(ClientAddress.parseLiteral("evil.example").isEmpty());
        assertTrue(ClientAddress.parseLiteral("1.2.3").isEmpty());
        assertTrue(ClientAddress.parseLiteral("::g").isEmpty());
    }

    @Test
    void ipv6LiteralsAreCanonicalised() {
        assertEquals(ClientAddress.parseLiteral("::1"), ClientAddress.parseLiteral("0:0:0:0:0:0:0:1"));
        assertEquals("2001:db8:0:0:0:0:0:1", resolve(PROXY, List.of("2001:db8::1")));
    }

    @Test
    void trustedProxiesMustBeIpAddresses() {
        assertThrows(IllegalArgumentException.class, () -> new RateLimitConfig(true, 60, 10, Set.of("proxy.internal")));
        assertThrows(IllegalArgumentException.class, () -> new RateLimitConfig(true, 60, 10, Set.of("10.0.0.0/8")));
        assertEquals(Set.of("0:0:0:0:0:0:0:1"), new RateLimitConfig(true, 60, 10, Set.of("::1")).trustedProxies());
    }

    private static String resolve(String remote, List<String> forwardedFor) {
        return ClientAddress.resolve(new InetSocketAddress(remote, 40_000), forwardedFor, TRUSTED);
    }
}
