package com.nexora.api.ratelimit;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Which client a request counts against.
 *
 * <p>The connecting address, unless that address is a trusted proxy. Then the client is the
 * rightmost {@code X-Forwarded-For} entry that is not itself a trusted proxy: proxies append the
 * address they received from, so entries left of the first untrusted one were written by the client
 * and prove nothing. Trusting the header from anyone else would let every request claim a fresh
 * address and escape its limit.
 */
public final class ClientAddress {

    public static final String FORWARDED_FOR = "X-Forwarded-For";

    private static final String OCTET = "(25[0-5]|2[0-4]\\d|1\\d\\d|[1-9]?\\d)";
    private static final Pattern IPV4 = Pattern.compile(OCTET + "(\\." + OCTET + "){3}");
    private static final Pattern IPV6 = Pattern.compile("[0-9A-Fa-f:.]+");

    private ClientAddress() {}

    /**
     * @param forwardedFor every {@value #FORWARDED_FOR} header value, in order; may be null or empty
     */
    public static String resolve(InetSocketAddress remote, List<String> forwardedFor, Set<String> trustedProxies) {
        String connecting = remote == null || remote.getAddress() == null
                ? "unknown"
                : remote.getAddress().getHostAddress();
        if (!trustedProxies.contains(connecting) || forwardedFor == null || forwardedFor.isEmpty()) {
            return connecting;
        }
        List<String> hops = new ArrayList<>();
        for (String header : forwardedFor) {
            for (String hop : header.split(",")) {
                hops.add(hop.strip());
            }
        }
        for (int i = hops.size() - 1; i >= 0; i--) {
            Optional<String> hop = parseLiteral(hops.get(i));
            if (hop.isEmpty()) {
                // A malformed hop: nothing to its left can be trusted, so the proxy itself is the client.
                return connecting;
            }
            if (!trustedProxies.contains(hop.get())) {
                return hop.get();
            }
        }
        return connecting;
    }

    /**
     * The canonical form of an IPv4 or IPv6 literal, or empty for anything else. Validated before it
     * reaches {@link InetAddress#getByName}, which resolves non-literals through DNS: header values
     * come from the network and must never trigger a lookup.
     */
    static Optional<String> parseLiteral(String value) {
        if (value == null) {
            return Optional.empty();
        }
        String candidate = value.strip();
        boolean literal = IPV4.matcher(candidate).matches()
                || (candidate.indexOf(':') >= 0 && IPV6.matcher(candidate).matches());
        if (!literal) {
            return Optional.empty();
        }
        try {
            return Optional.of(InetAddress.getByName(candidate).getHostAddress());
        } catch (UnknownHostException e) {
            // A string with ':' that is not a valid IPv6 literal: rejected without any lookup.
            return Optional.empty();
        }
    }
}
