/**
 * Copyright 2016-2020 The Reaktivity Project
 *
 * The Reaktivity Project licenses this file to you under the Apache License,
 * version 2.0 (the "License"); you may not use this file except in compliance
 * with the License. You may obtain a copy of the License at:
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */
package org.reaktivity.nukleus.kafka.internal.cache;

import static java.nio.ByteBuffer.allocateDirect;
import static org.reaktivity.nukleus.kafka.internal.cache.KafkaCacheCursorRecord.NEXT_SEGMENT;
import static org.reaktivity.nukleus.kafka.internal.cache.KafkaCacheCursorRecord.RETRY_SEGMENT;
import static org.reaktivity.nukleus.kafka.internal.cache.KafkaCacheCursorRecord.cursor;
import static org.reaktivity.nukleus.kafka.internal.cache.KafkaCacheCursorRecord.cursorValue;
import static org.reaktivity.nukleus.kafka.internal.cache.KafkaCacheCursorRecord.previousIndex;
import static org.reaktivity.nukleus.kafka.internal.types.KafkaDeltaType.JSON_PATCH;

import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.zip.CRC32C;

import javax.json.JsonArray;
import javax.json.JsonPatch;
import javax.json.JsonReader;
import javax.json.JsonStructure;
import javax.json.JsonWriter;
import javax.json.spi.JsonProvider;

import org.agrona.DirectBuffer;
import org.agrona.ExpandableArrayBuffer;
import org.agrona.IoUtil;
import org.agrona.MutableDirectBuffer;
import org.agrona.concurrent.UnsafeBuffer;
import org.agrona.io.DirectBufferInputStream;
import org.agrona.io.ExpandableDirectBufferOutputStream;
import org.reaktivity.nukleus.kafka.internal.KafkaConfiguration;
import org.reaktivity.nukleus.kafka.internal.types.ArrayFW;
import org.reaktivity.nukleus.kafka.internal.types.KafkaDeltaType;
import org.reaktivity.nukleus.kafka.internal.types.KafkaHeaderFW;
import org.reaktivity.nukleus.kafka.internal.types.KafkaKeyFW;
import org.reaktivity.nukleus.kafka.internal.types.OctetsFW;
import org.reaktivity.nukleus.kafka.internal.types.cache.KafkaCacheDeltaFW;
import org.reaktivity.nukleus.kafka.internal.types.cache.KafkaCacheEntryFW;

public final class KafkaCacheSegmentFactory
{
    public static final int POSITION_UNSET = -1;

    public static final int OFFSET_LATEST = -1;
    public static final int OFFSET_EARLIEST = -2;

    public static final String CACHE_EXTENSION_LOG = "log";
    public static final String CACHE_EXTENSION_LOG_INDEX = "index";
    public static final String CACHE_EXTENSION_HASH_SCAN = "hscan";
    public static final String CACHE_EXTENSION_HASH_SCAN_SORTING = String.format("%s.sorting", CACHE_EXTENSION_HASH_SCAN);
    public static final String CACHE_EXTENSION_HASH_INDEX = "hindex";
    public static final String CACHE_EXTENSION_KEYS_SCAN = "kscan";
    public static final String CACHE_EXTENSION_KEYS_SCAN_SORTING = String.format("%s.sorting", CACHE_EXTENSION_KEYS_SCAN);
    public static final String CACHE_EXTENSION_KEYS_INDEX = "kindex";
    public static final String CACHE_EXTENSION_DELTA = "delta";

    private final KafkaCacheEntryFW ancestorEntryRO = new KafkaCacheEntryFW();
    private final KafkaCacheEntryFW headEntryRO = new KafkaCacheEntryFW();

    private final MutableDirectBuffer entryInfo = new UnsafeBuffer(new byte[3 * Long.BYTES + Integer.BYTES]);
    private final MutableDirectBuffer valueInfo = new UnsafeBuffer(new byte[Integer.BYTES]);
    private final MutableDirectBuffer indexInfo = new UnsafeBuffer(new byte[Integer.BYTES + Integer.BYTES]);
    private final MutableDirectBuffer hashInfo = new UnsafeBuffer(new byte[Integer.BYTES + Integer.BYTES]);
    private final MutableDirectBuffer keysInfo = new UnsafeBuffer(new byte[Integer.BYTES + Integer.BYTES]);

    private final DirectBufferInputStream ancestorIn = new DirectBufferInputStream();
    private final DirectBufferInputStream headIn = new DirectBufferInputStream();
    private final MutableDirectBuffer diffBuffer = new ExpandableArrayBuffer();
    private final ExpandableDirectBufferOutputStream diffOut = new ExpandableDirectBufferOutputStream();

    private final MutableDirectBuffer writeBuffer;
    private final CRC32C checksum;

    KafkaCacheSegmentFactory(
        KafkaConfiguration config)
    {
        this.writeBuffer = new UnsafeBuffer(allocateDirect(64 * 1024)); // TODO: configure
        this.checksum = new CRC32C();
    }

    KafkaCacheSentinelSegment newSentinel(
        KafkaCacheTopicConfig topicConfig,
        Path directory)
    {
        return new KafkaCacheSentinelSegment(topicConfig, directory);
    }

    KafkaCacheCandidateSegment newCandidate()
    {
        return new KafkaCacheCandidateSegment();
    }

    public abstract class KafkaCacheSegment implements Comparable<KafkaCacheSegment>
    {
        protected final KafkaCacheTopicConfig topicConfig;
        protected final Path directory;
        protected final long timestamp;

        protected volatile KafkaCacheSegment previousSegment;
        protected volatile KafkaCacheSegment nextSegment;

        protected KafkaCacheSegment(
            KafkaCacheTopicConfig topicConfig,
            Path directory)
        {
            this.topicConfig = topicConfig;
            this.directory = directory;
            this.previousSegment = this;
            this.timestamp = System.currentTimeMillis();
        }

        protected KafkaCacheSegment(
            KafkaCacheSegment previousSegment,
            Path directory)
        {
            this.previousSegment = previousSegment;
            this.directory = directory;
            this.topicConfig = previousSegment.topicConfig;
            this.timestamp = System.currentTimeMillis();
        }

        @Override
        public int compareTo(
            KafkaCacheSegment that)
        {
            return Long.compare(baseOffset(), that.baseOffset());
        }

        @Override
        public int hashCode()
        {
            return Long.hashCode(baseOffset());
        }

        @Override
        public boolean equals(
            Object obj)
        {
            return this == obj ||
                    (obj instanceof KafkaCacheSegment &&
                     equalTo((KafkaCacheSegment) obj));
        }

        @Override
        public String toString()
        {
            return String.format("%s %d", getClass().getSimpleName(), baseOffset());
        }

        public KafkaCacheSegment nextSegment()
        {
            return nextSegment;
        }

        public KafkaCacheHeadSegment nextSegment(
            long nextOffset)
        {
            assert nextSegment == null;

            final KafkaCacheSegment frozenSegment = freezeSegment(nextOffset);

            assert nextSegment == frozenSegment.nextSegment;

            return nextSegment.headSegment();
        }

        public KafkaCacheHeadSegment headSegment()
        {
            KafkaCacheHeadSegment headSegment = null;

            if (nextSegment != null)
            {
                headSegment = nextSegment.headSegment();
            }

            return headSegment;
        }

        public KafkaCacheTailSegment tailSegment()
        {
            KafkaCacheTailSegment tailSegment = null;

            if (previousSegment != null)
            {
                tailSegment = previousSegment.tailSegment();
            }

            return tailSegment;
        }


        public abstract long baseOffset();

        public long seekOffset(
            long nextOffset)
        {
            return NEXT_SEGMENT;
        }

        public long scanOffset(
            long cursor)
        {
            return NEXT_SEGMENT;
        }

        public long seekHash(
            int hash,
            int position)
        {
            return NEXT_SEGMENT;
        }

        public long scanHash(
            int hash,
            long cursor)
        {
            return NEXT_SEGMENT;
        }

        public long reverseSeekHash(
            int hash,
            int position)
        {
            return NEXT_SEGMENT;
        }

        public long reverseScanHash(
            int hash,
            long cursor)
        {
            return NEXT_SEGMENT;
        }

        public KafkaCacheEntryFW readLog(
            int position,
            KafkaCacheEntryFW entry)
        {
            return null;
        }

        public KafkaCacheDeltaFW readDelta(
            int position,
            KafkaCacheDeltaFW delta)
        {
            return null;
        }

        protected KafkaCacheSegment freezeSegment(
            long nextOffset)
        {
            this.nextSegment = new KafkaCacheHeadSegment(this, directory, nextOffset);
            return this;
        }

        protected final Path cacheFile(
            String extension)
        {
            return directory.resolve(String.format("%016x.%s", baseOffset(), extension));
        }

        private boolean equalTo(
            KafkaCacheSegment that)
        {
            return baseOffset() == that.baseOffset();
        }
    }

    public final class KafkaCacheCandidateSegment extends KafkaCacheSegment
    {
        private long baseOffset;

        KafkaCacheCandidateSegment()
        {
            super((KafkaCacheTopicConfig) null, null);
        }

        public void baseOffset(
            long baseOffset)
        {
            this.baseOffset = baseOffset;
        }

        @Override
        public long baseOffset()
        {
            return baseOffset;
        }
    }

    public final class KafkaCacheSentinelSegment extends KafkaCacheSegment
    {
        private KafkaCacheSentinelSegment(
            KafkaCacheTopicConfig topicConfig,
            Path directory)
        {
            super(topicConfig, directory);
        }

        public KafkaCacheHeadSegment headSegment(
            long nextOffset)
        {
            return nextSegment != null ? nextSegment.headSegment() : nextSegment(nextOffset);
        }

        @Override
        public KafkaCacheTailSegment tailSegment()
        {
            return null;
        }

        @Override
        public long baseOffset()
        {
            return OFFSET_EARLIEST;
        }

        public void nextSegment(
            KafkaCacheSegment segment)
        {
            assert segment != null;

            if (nextSegment != segment)
            {
                KafkaCacheSegment expiredSegment = nextSegment;

                this.nextSegment = segment;
                segment.previousSegment = this;

                while (expiredSegment != segment)
                {
                    final KafkaCacheTailSegment tailSegment = expiredSegment.tailSegment();
                    assert tailSegment == expiredSegment;

                    if (tailSegment.tailKeys.nextKeys != null)
                    {
                        tailSegment.tailKeys.nextKeys.previousKeys = null;
                        tailSegment.tailKeys.nextKeys = null;
                    }

                    tailSegment.delete();

                    expiredSegment = expiredSegment.nextSegment;
                }
            }
        }
    }

    public final class KafkaCacheHeadSegment extends KafkaCacheSegment
    {
        private final long baseOffset;
        final KafkaCacheHeadLogFile logFile;
        final KafkaCacheHeadLogIndexFile indexFile;
        final KafkaCacheHeadHashFile hashFile;
        final KafkaCacheHeadKeysFile keysFile;
        final KafkaCacheHeadDeltaFile deltaFile;

        private long headOffset;
        private int headPosition;
        private KafkaCacheEntryFW ancestor;

        private KafkaCacheHeadSegment(
            KafkaCacheSegment previousSegment,
            Path directory,
            long baseOffset)
        {
            this(previousSegment, directory, baseOffset, null);
        }

        private KafkaCacheHeadSegment(
            KafkaCacheTailSegment previousSegment,
            Path directory,
            long baseOffset)
        {
            this(previousSegment, directory, baseOffset, previousSegment.tailKeys);
        }

        private KafkaCacheHeadSegment(
            KafkaCacheSegment previousSegment,
            Path directory,
            long baseOffset,
            KafkaCacheTailKeysFile previousKeys)
        {
            super(previousSegment, directory);
            this.baseOffset = baseOffset;
            this.headOffset = OFFSET_LATEST;
            this.logFile = new KafkaCacheHeadLogFile(this, writeBuffer);
            this.indexFile = new KafkaCacheHeadLogIndexFile(this, writeBuffer);
            this.hashFile = new KafkaCacheHeadHashFile(this, writeBuffer);
            this.keysFile = new KafkaCacheHeadKeysFile(this, writeBuffer, previousKeys);
            this.deltaFile = new KafkaCacheHeadDeltaFile(this, writeBuffer);
        }

        @Override
        public long baseOffset()
        {
            return baseOffset;
        }

        public long nextOffset()
        {
            return headOffset == OFFSET_LATEST ? baseOffset : headOffset + 1;
        }

        @Override
        public long seekOffset(
            long nextOffset)
        {
            return indexFile.seekOffset(nextOffset);
        }

        @Override
        public long scanOffset(
            long cursor)
        {
            return indexFile.scanOffset(cursor);
        }

        @Override
        public long seekHash(
            int hash,
            int position)
        {
            return hashFile.scanHash(hash, cursor(0, position));
        }

        @Override
        public long scanHash(
            int hash,
            long cursor)
        {
            return hashFile.scanHash(hash, cursor);
        }

        @Override
        public long reverseSeekHash(
            int hash,
            int position)
        {
            return hashFile.reverseScanHash(hash, cursor(Integer.MAX_VALUE, position));
        }

        @Override
        public long reverseScanHash(
            int hash,
            long cursor)
        {
            return hashFile.reverseScanHash(hash, cursor);
        }

        @Override
        public KafkaCacheEntryFW readLog(
            int position,
            KafkaCacheEntryFW entry)
        {
            return logFile.read(position, entry);
        }

        @Override
        public KafkaCacheDeltaFW readDelta(
            int position,
            KafkaCacheDeltaFW delta)
        {
            return deltaFile.read(position, delta);
        }

        @Override
        public KafkaCacheHeadSegment headSegment()
        {
            return this;
        }

        @Override
        protected KafkaCacheTailSegment freezeSegment(
            long nextOffset)
        {
            logFile.freeze();
            indexFile.freeze();
            hashFile.freeze();
            deltaFile.freeze();

            hashFile.toHashIndex();
            keysFile.toKeysIndex();

            final KafkaCacheTailSegment tailSegment =
                    new KafkaCacheTailSegment(previousSegment, directory, baseOffset, keysFile.previousKeys);
            tailSegment.nextSegment = new KafkaCacheHeadSegment(tailSegment, directory, nextOffset);

            if (keysFile.previousKeys != null)
            {
                keysFile.previousKeys.nextKeys = tailSegment.tailKeys;
            }

            previousSegment.nextSegment = tailSegment;
            this.nextSegment = tailSegment.nextSegment;

            if (deltaFile.readCapacity == 0)
            {
                IoUtil.delete(cacheFile(CACHE_EXTENSION_DELTA).toFile(), true);
            }

            return tailSegment;
        }

        void writeEntry(
            long offset,
            long timestamp,
            KafkaKeyFW key,
            ArrayFW<KafkaHeaderFW> headers,
            OctetsFW value,
            KafkaDeltaType deltaType)
        {
            final int valueLength = value != null ? value.sizeof() : -1;
            writeEntryStart(offset, timestamp, key, valueLength, deltaType);
            if (value != null)
            {
                writeEntryContinue(value);
            }
            writeEntryFinish(headers, deltaType);
        }

        public void writeEntryStart(
            long offset,
            long timestamp,
            KafkaKeyFW key,
            int valueLength,
            KafkaDeltaType deltaType)
        {
            assert offset > this.headOffset;
            this.headOffset = offset;
            this.headPosition = logFile.readCapacity;

            // TODO: compute null key hash in advance
            final DirectBuffer buffer = key.buffer();
            final ByteBuffer byteBuffer = buffer.byteBuffer();
            byteBuffer.clear();
            assert byteBuffer != null;
            checksum.reset();
            byteBuffer.position(key.offset());
            byteBuffer.limit(key.limit());
            checksum.update(byteBuffer);
            final long hash = checksum.getValue();
            final KafkaCacheEntryFW ancestor = findAncestor(key, hash);
            final long ancestorOffset = ancestor != null ? ancestor.offset$() : -1L;
            final int deltaPosition = deltaType == JSON_PATCH &&
                                      ancestor != null && ancestor.valueLen() != -1 &&
                                      valueLength != -1
                        ? deltaFile.readCapacity
                        : -1;

            assert deltaPosition == -1 || ancestor != null;
            this.ancestor = ancestor;

            entryInfo.putLong(0, headOffset);
            entryInfo.putLong(Long.BYTES, timestamp);
            entryInfo.putLong(Long.BYTES + Long.BYTES, ancestorOffset);
            entryInfo.putInt(Long.BYTES + Long.BYTES + Long.BYTES, deltaPosition);
            valueInfo.putInt(0, valueLength);

            logFile.write(entryInfo, 0, entryInfo.capacity());
            logFile.write(key.buffer(), key.offset(), key.sizeof());
            logFile.write(valueInfo, 0, valueInfo.capacity());

            final long hashEntry = hash << 32 | headPosition;
            hashInfo.putLong(0, hashEntry);
            hashFile.write(hashInfo, 0, hashInfo.capacity());

            final int deltaBaseOffset = 0;
            final long keyEntry = hash << 32 | deltaBaseOffset;
            keysInfo.putLong(0, keyEntry);
            keysFile.write(keysInfo, 0, keysInfo.capacity());
        }

        public void writeEntryContinue(
            OctetsFW payload)
        {
            final int logRemaining = logFile.writeCapacity - logFile.readCapacity;
            final int logRequired = payload.sizeof();
            assert logRemaining >= logRequired;

            logFile.write(payload.buffer(), payload.offset(), payload.sizeof());
        }

        public void writeEntryFinish(
            ArrayFW<KafkaHeaderFW> headers,
            KafkaDeltaType deltaType)
        {
            final int logRemaining = logFile.writeCapacity - logFile.readCapacity;
            final int logRequired = headers.sizeof();
            assert logRemaining >= logRequired;

            logFile.write(headers.buffer(), headers.offset(), headers.sizeof());

            final long offsetDelta = (int)(headOffset - baseOffset());
            indexInfo.putLong(0, (offsetDelta << 32) | headPosition);

            if (!headers.isEmpty())
            {
                final DirectBuffer buffer = headers.buffer();
                final ByteBuffer byteBuffer = buffer.byteBuffer();
                assert byteBuffer != null;
                byteBuffer.clear();
                headers.forEach(h ->
                {
                    checksum.reset();
                    byteBuffer.position(h.offset());
                    byteBuffer.limit(h.limit());
                    checksum.update(byteBuffer);
                    final long hash = checksum.getValue();
                    hashInfo.putLong(0, (hash << 32) | headPosition);
                    hashFile.write(hashInfo, 0, hashInfo.capacity());
                });
            }

            final int indexRemaining = indexFile.writeCapacity - indexFile.readCapacity;
            final int indexRequired = indexInfo.capacity();
            assert indexRemaining >= indexRequired;

            indexFile.write(indexInfo, 0, indexInfo.capacity());

            final KafkaCacheEntryFW head = logFile.read(headPosition, headEntryRO);

            if (deltaType == JSON_PATCH &&
                ancestor != null && ancestor.valueLen() != -1 &&
                head.valueLen() != -1)
            {
                final OctetsFW ancestorValue = ancestor.value();
                final OctetsFW headValue = head.value();
                assert head.offset$() == headOffset;

                final JsonProvider json = JsonProvider.provider();
                ancestorIn.wrap(ancestorValue.buffer(), ancestorValue.offset(), ancestorValue.sizeof());
                final JsonReader ancestorReader = json.createReader(ancestorIn);
                final JsonStructure ancestorJson = ancestorReader.read();
                ancestorReader.close();

                headIn.wrap(headValue.buffer(), headValue.offset(), headValue.sizeof());
                final JsonReader headReader = json.createReader(headIn);
                final JsonStructure headJson = headReader.read();
                headReader.close();

                final JsonPatch diff = json.createDiff(ancestorJson, headJson);
                final JsonArray diffJson = diff.toJsonArray();
                diffOut.wrap(diffBuffer, Integer.BYTES);
                final JsonWriter writer = json.createWriter(diffOut);
                writer.write(diffJson);
                writer.close();

                // TODO: signal delta.sizeof > head.sizeof via null delta, otherwise delta file can exceed log file

                final int deltaLength = diffOut.position();
                diffBuffer.putInt(0, deltaLength);
                deltaFile.write(diffBuffer, 0, Integer.BYTES + deltaLength);
            }
        }

        private KafkaCacheEntryFW findAncestor(
            KafkaKeyFW key,
            long hash)
        {
            KafkaCacheEntryFW ancestor = null;
            ancestor:
            if (key.length() != -1)
            {
                long hashCursor = reverseSeekHash((int) hash, Integer.MAX_VALUE);
                while (hashCursor != NEXT_SEGMENT && hashCursor != RETRY_SEGMENT)
                {
                    final int position = cursorValue(hashCursor);
                    final KafkaCacheEntryFW cacheEntry = logFile.read(position, ancestorEntryRO);
                    assert cacheEntry != null;
                    if (key.equals(cacheEntry.key()))
                    {
                        ancestor = cacheEntry;
                        break ancestor;
                    }

                    final long nextHashCursor = reverseScanHash((int) hash, previousIndex(hashCursor));
                    if (nextHashCursor == NEXT_SEGMENT || nextHashCursor == RETRY_SEGMENT)
                    {
                        break;
                    }

                    hashCursor = nextHashCursor;
                }
                assert hashCursor == NEXT_SEGMENT || hashCursor == RETRY_SEGMENT;

                KafkaCacheTailKeysFile previousKeys = keysFile.previousKeys;
                while (previousKeys != null)
                {
                    long keyCursor = previousKeys.reverseSeekKey((int) hash, Integer.MAX_VALUE);
                    while (keyCursor != NEXT_SEGMENT && keyCursor != RETRY_SEGMENT)
                    {
                        final long ancestorBaseOffset = previousKeys.baseOffset(keyCursor);

                        KafkaCacheSegment ancestorSegment = this;
                        while (ancestorSegment != null && ancestorSegment.baseOffset() > ancestorBaseOffset)
                        {
                            ancestorSegment = ancestorSegment.previousSegment;
                        }

                        if (ancestorSegment != null && ancestorSegment.baseOffset() == ancestorBaseOffset)
                        {
                            long ancestorCursor = ancestorSegment.reverseSeekHash((int) hash, Integer.MAX_VALUE);
                            while (ancestorCursor != NEXT_SEGMENT && ancestorCursor != RETRY_SEGMENT)
                            {
                                final int position = cursorValue(ancestorCursor);
                                final KafkaCacheEntryFW cacheEntry = ancestorSegment.readLog(position, ancestorEntryRO);
                                assert cacheEntry != null;
                                if (key.equals(cacheEntry.key()))
                                {
                                    ancestor = cacheEntry;
                                    break ancestor;
                                }

                                final long nextAncestorCursor =
                                        ancestorSegment.reverseScanHash((int) hash, previousIndex(ancestorCursor));
                                if (nextAncestorCursor == NEXT_SEGMENT || nextAncestorCursor == RETRY_SEGMENT)
                                {
                                    break;
                                }

                                ancestorCursor = nextAncestorCursor;
                            }
                        }

                        final long nextKeyCursor = previousKeys.reverseScanKey((int) hash, previousIndex(keyCursor));
                        if (nextKeyCursor == NEXT_SEGMENT || nextKeyCursor == RETRY_SEGMENT)
                        {
                            break;
                        }

                        keyCursor = nextKeyCursor;
                    }

                    previousKeys = previousKeys.previousKeys;
                }
            }

            return ancestor;
        }
    }

    final class KafkaCacheTailSegment extends KafkaCacheSegment
    {
        private final long baseOffset;

        final KafkaCacheTailLogFile logFile;
        final KafkaCacheTailLogIndexFile indexFile;
        final KafkaCacheTailHashFile hashFile;
        final KafkaCacheTailDeltaFile deltaFile;
        final KafkaCacheTailKeysFile tailKeys;

        private KafkaCacheTailSegment(
            KafkaCacheSegment previousSegment,
            Path directory,
            long baseOffset,
            KafkaCacheTailKeysFile previousKeys)
        {
            super(previousSegment, directory);
            this.baseOffset = baseOffset;
            this.logFile = new KafkaCacheTailLogFile(this);
            this.indexFile = new KafkaCacheTailLogIndexFile(this);
            this.hashFile = new KafkaCacheTailHashFile(this);
            this.deltaFile = new KafkaCacheTailDeltaFile(this);
            this.tailKeys = new KafkaCacheTailKeysFile(this, previousKeys);
        }

        @Override
        public long baseOffset()
        {
            return baseOffset;
        }

        @Override
        public KafkaCacheTailSegment tailSegment()
        {
            return this;
        }

        @Override
        public long seekOffset(
            long nextOffset)
        {
            return indexFile.seekOffset(nextOffset);
        }

        @Override
        public long scanOffset(
            long cursor)
        {
            return indexFile.scanOffset(cursor);
        }

        @Override
        public long seekHash(
            int hash,
            int position)
        {
            return hashFile.seekHash(hash, position);
        }

        @Override
        public long scanHash(
            int hash,
            long cursor)
        {
            return hashFile.scanHash(hash, cursor);
        }

        @Override
        public long reverseScanHash(
            int hash,
            long record)
        {
            return hashFile.reverseScanHash(hash, record);
        }

        @Override
        public KafkaCacheEntryFW readLog(
            int position,
            KafkaCacheEntryFW entry)
        {
            return logFile.read(position, entry);
        }

        @Override
        public KafkaCacheDeltaFW readDelta(
            int position,
            KafkaCacheDeltaFW entry)
        {
            return deltaFile.read(position, entry);
        }

        private void delete()
        {
            logFile.delete(this);
            indexFile.delete(this);
            hashFile.delete(this);
            deltaFile.delete(this);
            tailKeys.delete(this);
        }
    }
}
