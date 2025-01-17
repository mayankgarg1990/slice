/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.facebook.slice;

import net.jpountz.xxhash.XXHash64;
import net.jpountz.xxhash.XXHashFactory;
import org.testng.annotations.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;

import static com.facebook.slice.Slices.EMPTY_SLICE;
import static com.facebook.slice.XxHash64.hash;
import static java.lang.Math.min;
import static org.testng.Assert.assertEquals;

public class TestXxHash64
{
    private static final byte[] EMPTY_BYTES = {};

    private static final long PRIME = 2654435761L;

    private final Slice buffer;

    public TestXxHash64()
    {
        buffer = Slices.allocate(101);

        long value = PRIME;
        for (int i = 0; i < buffer.length(); i++) {
            buffer.setByte(i, (byte) (value >> 24));
            value *= value;
        }
    }

    @Test
    public void testSanity()
            throws Exception
    {
        assertHash(0, buffer, 1, 0x4FCE394CC88952D8L);
        assertHash(PRIME, buffer, 1, 0x739840CB819FA723L);

        assertHash(0, buffer, 4, 0x9256E58AA397AEF1L);
        assertHash(PRIME, buffer, 4, 0x9D5FFDFB928AB4BL);

        assertHash(0, buffer, 8, 0xF74CB1451B32B8CFL);
        assertHash(PRIME, buffer, 8, 0x9C44B77FBCC302C5L);

        assertHash(0, buffer, 14, 0xCFFA8DB881BC3A3DL);
        assertHash(PRIME, buffer, 14, 0x5B9611585EFCC9CBL);

        assertHash(0, buffer, 32, 0xAF5753D39159EDEEL);
        assertHash(PRIME, buffer, 32, 0xDCAB9233B8CA7B0FL);

        assertHash(0, buffer, buffer.length(), 0x0EAB543384F878ADL);
        assertHash(PRIME, buffer, buffer.length(), 0xCAA65939306F1E21L);
    }

    private static void assertHash(long seed, Slice data, int length, long expected)
            throws IOException
    {
        assertEquals(hash(seed, data, 0, length), expected);
        assertEquals(hash(seed, data.slice(0, length)), expected);

        assertEquals(new XxHash64(seed).update(data.slice(0, length)).hash(), expected);
        assertEquals(new XxHash64(seed).update(data, 0, length).hash(), expected);
        assertEquals(new XxHash64(seed).update(data.getBytes(0, length)).hash(), expected);
        assertEquals(new XxHash64(seed).update(data.getBytes(), 0, length).hash(), expected);

        assertEquals(hash(seed, new ByteArrayInputStream(data.getBytes(0, length))), expected);

        for (int chunkSize = 1; chunkSize <= length; chunkSize++) {
            XxHash64 hash = new XxHash64(seed);
            for (int i = 0; i < length; i += chunkSize) {
                int updateSize = min(length - i, chunkSize);
                hash.update(data.slice(i, updateSize));
                assertEquals(hash.hash(), hash(seed, data, 0, i + updateSize));
            }
            assertEquals(hash.hash(), expected);
        }
    }

    @Test
    public void testMultipleLengths()
            throws Exception
    {
        XXHash64 jpountz = XXHashFactory.fastestInstance().hash64();
        for (int i = 0; i < 20_000; i++) {
            byte[] data = new byte[i];
            long expected = jpountz.hash(data, 0, data.length, 0);

            Slice slice = Slices.wrappedBuffer(data);
            assertEquals(hash(slice), expected);
            assertEquals(new XxHash64().update(slice).hash(), expected);
            assertEquals(new XxHash64().update(data).hash(), expected);

            assertEquals(hash(new ByteArrayInputStream(data)), expected);
        }
    }

    @Test
    public void testEmpty()
            throws Exception
    {
        long expected = 0xEF46DB3751D8E999L;

        assertEquals(hash(EMPTY_SLICE), expected);
        assertEquals(hash(EMPTY_SLICE, 0, 0), expected);

        assertEquals(hash(0, EMPTY_SLICE), expected);
        assertEquals(hash(0, EMPTY_SLICE, 0, 0), expected);

        assertEquals(new XxHash64().update(EMPTY_SLICE).hash(), expected);
        assertEquals(new XxHash64().update(EMPTY_SLICE, 0, 0).hash(), expected);

        assertEquals(new XxHash64().update(EMPTY_BYTES).hash(), expected);
        assertEquals(new XxHash64().update(EMPTY_BYTES, 0, 0).hash(), expected);

        assertEquals(
                new XxHash64()
                        .update(EMPTY_BYTES)
                        .update(EMPTY_BYTES, 0, 0)
                        .update(EMPTY_SLICE)
                        .update(EMPTY_SLICE, 0, 0)
                        .hash(),
                expected);
    }

    @Test
    public void testHashLong()
            throws Exception
    {
        assertEquals(hash(buffer.getLong(0)), hash(buffer, 0, SizeOf.SIZE_OF_LONG));
    }
}
