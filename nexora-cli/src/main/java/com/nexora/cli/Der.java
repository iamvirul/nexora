package com.nexora.cli;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;

/**
 * The few DER (ASN.1 distinguished encoding rules) types an X.509 certificate needs. The JDK can
 * read certificates but has no public API to build one, and pulling in BouncyCastle for a dev-only
 * certificate generator would add a large dependency to every install.
 */
final class Der {

    private static final int TAG_BOOLEAN = 0x01;
    private static final int TAG_INTEGER = 0x02;
    private static final int TAG_BIT_STRING = 0x03;
    private static final int TAG_OCTET_STRING = 0x04;
    private static final int TAG_OID = 0x06;
    private static final int TAG_UTF8_STRING = 0x0C;
    private static final int TAG_UTC_TIME = 0x17;
    private static final int TAG_GENERALIZED_TIME = 0x18;
    private static final int TAG_SEQUENCE = 0x30;
    private static final int TAG_SET = 0x31;
    private static final int CONTEXT_CONSTRUCTED = 0xA0;
    private static final int CONTEXT_PRIMITIVE = 0x80;
    private static final int MAX_CONTEXT_TAG = 30;
    private static final int SHORT_LENGTH_LIMIT = 0x80;
    // RFC 5280 4.1.2.5: UTCTime through 2049, GeneralizedTime from 2050.
    private static final int UTC_TIME_LAST_YEAR = 2049;
    private static final DateTimeFormatter UTC_TIME =
            DateTimeFormatter.ofPattern("yyMMddHHmmss'Z'").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter GENERALIZED_TIME =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmss'Z'").withZone(ZoneOffset.UTC);

    private Der() {}

    static byte[] sequence(byte[]... elements) {
        return tagged(TAG_SEQUENCE, concat(elements));
    }

    static byte[] integer(BigInteger value) {
        return tagged(TAG_INTEGER, value.toByteArray());
    }

    static byte[] integer(long value) {
        return integer(BigInteger.valueOf(value));
    }

    static byte[] bool(boolean value) {
        return tagged(TAG_BOOLEAN, new byte[] {(byte) (value ? 0xFF : 0x00)});
    }

    static byte[] set(byte[]... elements) {
        // Every SET written here has one element, so DER's sort order never comes into play.
        if (elements.length != 1) {
            throw new IllegalArgumentException("only single-element SETs are supported");
        }
        return tagged(TAG_SET, elements[0]);
    }

    /** A BIT STRING with no unused bits, as a signature is written. */
    static byte[] bitString(byte[] bits) {
        return bitString(bits, 0);
    }

    /** A BIT STRING whose last byte has {@code unusedBits} padding bits, as named bit lists need. */
    static byte[] bitString(byte[] bits, int unusedBits) {
        if (unusedBits < 0 || unusedBits > 7) {
            throw new IllegalArgumentException("unusedBits must be 0 to 7, got " + unusedBits);
        }
        return tagged(TAG_BIT_STRING, concat(new byte[] {(byte) unusedBits}, bits));
    }

    static byte[] octetString(byte[] content) {
        return tagged(TAG_OCTET_STRING, content);
    }

    static byte[] utf8String(String value) {
        return tagged(TAG_UTF8_STRING, value.getBytes(StandardCharsets.UTF_8));
    }

    static byte[] time(Instant instant) {
        boolean utc = instant.atZone(ZoneOffset.UTC).getYear() <= UTC_TIME_LAST_YEAR;
        String text = (utc ? UTC_TIME : GENERALIZED_TIME).format(instant);
        return tagged(utc ? TAG_UTC_TIME : TAG_GENERALIZED_TIME, text.getBytes(StandardCharsets.US_ASCII));
    }

    /** An OBJECT IDENTIFIER from its dotted form, for example {@code 2.5.29.17}. */
    static byte[] oid(String dotted) {
        String[] arcs = dotted.split("\\.");
        if (arcs.length < 2) {
            throw new IllegalArgumentException("OID needs at least two arcs: " + dotted);
        }
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        writeBase128(body, Long.parseLong(arcs[0]) * 40 + Long.parseLong(arcs[1]));
        for (int i = 2; i < arcs.length; i++) {
            writeBase128(body, Long.parseLong(arcs[i]));
        }
        return tagged(TAG_OID, body.toByteArray());
    }

    /** {@code [tag] EXPLICIT}: the element keeps its own tag inside the context tag. */
    static byte[] explicit(int tag, byte[] element) {
        return tagged(CONTEXT_CONSTRUCTED | checkContextTag(tag), element);
    }

    /** {@code [tag] IMPLICIT} on a primitive type: the context tag replaces the type's own. */
    static byte[] implicitPrimitive(int tag, byte[] content) {
        return tagged(CONTEXT_PRIMITIVE | checkContextTag(tag), content);
    }

    static byte[] concat(byte[]... parts) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] part : parts) {
            out.writeBytes(part);
        }
        return out.toByteArray();
    }

    private static byte[] tagged(int tag, byte[] content) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(content.length + 6);
        out.write(tag);
        writeLength(out, content.length);
        out.writeBytes(content);
        return out.toByteArray();
    }

    private static void writeLength(ByteArrayOutputStream out, int length) {
        if (length < SHORT_LENGTH_LIMIT) {
            out.write(length);
            return;
        }
        byte[] digits = BigInteger.valueOf(length).toByteArray();
        int skip = digits[0] == 0 ? 1 : 0;
        out.write(SHORT_LENGTH_LIMIT | (digits.length - skip));
        out.write(digits, skip, digits.length - skip);
    }

    private static void writeBase128(ByteArrayOutputStream out, long value) {
        int groups = Math.max(1, (64 - Long.numberOfLeadingZeros(value) + 6) / 7);
        for (int i = groups - 1; i >= 0; i--) {
            int group = (int) ((value >>> (7 * i)) & 0x7F);
            out.write(i == 0 ? group : group | 0x80);
        }
    }

    private static int checkContextTag(int tag) {
        if (tag < 0 || tag > MAX_CONTEXT_TAG) {
            throw new IllegalArgumentException("context tag out of range: " + tag);
        }
        return tag;
    }
}
