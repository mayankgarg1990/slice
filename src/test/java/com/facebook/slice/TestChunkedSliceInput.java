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

import com.facebook.slice.ChunkedSliceInput.BufferReference;
import com.facebook.slice.ChunkedSliceInput.SliceLoader;
import com.google.common.base.Strings;
import org.openjdk.jol.info.ClassLayout;
import org.testng.annotations.Test;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static com.google.common.base.Preconditions.checkPositionIndex;
import static org.testng.Assert.assertEquals;
import static org.testng.Assert.assertTrue;

public class TestChunkedSliceInput
        extends AbstractSliceInputTest
{
    @Override
    protected SliceInput createSliceInput(Slice slice)
    {
        return new ChunkedSliceInput(new SliceSliceLoader(slice), BUFFER_SIZE);
    }

    private static class SliceSliceLoader
            implements SliceLoader<BufferReference>
    {
        private final Slice data;
        private final AtomicInteger count = new AtomicInteger(0);

        public SliceSliceLoader(Slice data)
        {
            this.data = data;
        }

        @Override
        public BufferReference createBuffer(int bufferSize)
        {
            final Slice slice = Slices.allocate(bufferSize);
            return new BufferReference()
            {
                @Override
                public Slice getSlice()
                {
                    return slice;
                }
            };
        }

        @Override
        public long getSize()
        {
            return data.length();
        }

        @Override
        public void load(long position, BufferReference buffer, int length)
        {
            checkPositionIndex((int) (position + length), (int) getSize());
            count.incrementAndGet();
            this.data.getBytes((int) position, buffer.getSlice(), 0, length);
        }

        public int getCount()
        {
            return count.get();
        }

        @Override
        public void close()
        {
        }
    }

    @Test
    public void testSetPosition()
    {
        // Create a ChunkedSliceInput with 160 bytes, and set bufferSize to 130.
        // This test read bytes sequentially, but calls setPosition every time.
        // Only 2 reads should be issued to the underlying SliceLoader. One when
        // trying to read position 0, and one when trying to read position 130.

        int length = 160;
        int bufferSize = 130;
        Slice slice = Slices.utf8Slice(Strings.repeat("0", length));
        SliceSliceLoader loader = new SliceSliceLoader(slice);
        ChunkedSliceInput chunkedSliceInput = new ChunkedSliceInput(loader, bufferSize);

        ArrayList<Integer> actual = new ArrayList<>();
        for (int i = 0; i < length; i++) {
            chunkedSliceInput.setPosition(i);
            chunkedSliceInput.readByte();
            int count = loader.getCount();
            actual.add(count);
        }

        List<Integer> expected = IntStream.range(0, length)
                .map(i -> i < bufferSize ? 1 : 2)
                .boxed()
                .collect(Collectors.toList());
        assertEquals(actual, expected);
    }

    @Test
    public void testRetainedSize()
    {
        int length = 1024;
        int bufferSize = 128;
        Slice slice = Slices.utf8Slice(Strings.repeat("0", length));
        SliceSliceLoader loader = new SliceSliceLoader(slice);
        ChunkedSliceInput chunkedSliceInput = new ChunkedSliceInput(loader, bufferSize);

        for (int i = 0; i < length; i++) {
            chunkedSliceInput.setPosition(i);
            chunkedSliceInput.readByte();
            assertRetainedSize(chunkedSliceInput);
            long retainedSize = chunkedSliceInput.getRetainedSize();
            assertTrue(retainedSize > bufferSize && retainedSize < length);
        }
    }

    protected void assertRetainedSize(SliceInput input)
    {
        long retainedSize = ClassLayout.parseClass(input.getClass()).instanceSize();
        Field[] fields = input.getClass().getDeclaredFields();

        for (Field field : fields) {
            Class<?> type = field.getType();
            // we skip the array fields since jacoco mvn plugin instruments the classes during
            // tests and add a boolean array field (boolean[] com.facebook.slice.ChunkedSliceInput.$jacocoData)
            // https://github.com/jacoco/jacoco/issues/168
            if (type.isPrimitive() || type.isArray()) {
                continue;
            }

            field.setAccessible(true);

            try {
                Object value = field.get(input);
                retainedSize += (long) value.getClass().getDeclaredMethod("getRetainedSize").invoke(value);
            }
            catch (IllegalAccessException | InvocationTargetException | NoSuchMethodException e) {
                throw new RuntimeException(e);
            }
        }
        assertEquals(input.getRetainedSize(), retainedSize);
    }
}
