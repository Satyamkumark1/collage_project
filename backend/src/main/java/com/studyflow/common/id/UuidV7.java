package com.studyflow.common.id;

import java.security.SecureRandom;
import java.util.UUID;

/**
 * RFC 9562 UUIDv7: 48-bit millisecond timestamp + version/variant bits + randomness.
 * Time-sortable, which is what backs cursor pagination and B-tree index locality
 * (see specs/02-data-model.md).
 */
public final class UuidV7 {

    private static final SecureRandom RANDOM = new SecureRandom();

    private UuidV7() {
    }

    public static UUID generate() {
        long timestampMs = System.currentTimeMillis();
        byte[] rand = new byte[10];
        RANDOM.nextBytes(rand);

        long msb = (timestampMs & 0xFFFFFFFFFFFFL) << 16;
        msb |= 0x7000L; // version 7
        msb |= ((rand[0] & 0xFF) << 8 | (rand[1] & 0xFF)) & 0x0FFF; // 12 bits rand_a

        long lsb = ((long) (rand[2] & 0x3F) | 0x80) << 56; // variant 10 + top 6 bits of rand_b
        lsb |= ((long) (rand[3] & 0xFF)) << 48;
        lsb |= ((long) (rand[4] & 0xFF)) << 40;
        lsb |= ((long) (rand[5] & 0xFF)) << 32;
        lsb |= ((long) (rand[6] & 0xFF)) << 24;
        lsb |= ((long) (rand[7] & 0xFF)) << 16;
        lsb |= ((long) (rand[8] & 0xFF)) << 8;
        lsb |= (rand[9] & 0xFF);

        return new UUID(msb, lsb);
    }
}
