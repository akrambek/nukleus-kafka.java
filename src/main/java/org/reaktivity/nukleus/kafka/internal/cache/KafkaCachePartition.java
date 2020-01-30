/**
 * Copyright 2016-2019 The Reaktivity Project
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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.NavigableMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.function.LongPredicate;
import java.util.function.LongSupplier;

import org.agrona.LangUtil;
import org.reaktivity.nukleus.kafka.internal.KafkaConfiguration;
import org.reaktivity.nukleus.kafka.internal.types.ArrayFW;
import org.reaktivity.nukleus.kafka.internal.types.KafkaHeaderFW;
import org.reaktivity.nukleus.kafka.internal.types.KafkaKeyFW;
import org.reaktivity.nukleus.kafka.internal.types.OctetsFW;
import org.reaktivity.nukleus.kafka.internal.types.cache.KafkaCacheEntryFW;

public final class KafkaCachePartition
{
    private final Path path;
    private final int segmentBytes;
    private final int partitionId;
    private final NavigableMap<Integer, KafkaCacheSegment> segmentsByOffset;

    private LongSupplier progressOffset;

    public KafkaCachePartition(
        KafkaConfiguration config,
        String clusterName,
        String topicName,
        int partitionId)
    {
        this.path = initDirectory(config.cacheDirectory(), clusterName, topicName, partitionId);
        this.segmentBytes = config.cacheSegmentBytes();
        this.partitionId = partitionId;
        this.segmentsByOffset = new ConcurrentSkipListMap<>();

        // TODO: sync with file system to progress
        this.progressOffset = () -> -2; // EARLIEST
    }

    public int id()
    {
        return partitionId;
    }

    public long progressOffset()
    {
        return progressOffset.getAsLong();
    }

    public KafkaCacheEntryFW readEntry(
        long nextOffset)
    {
        // TODO Auto-generated method stub
        return null;
    }

    public KafkaCacheEntryFW readEntry(
        long nextOffset,
        LongPredicate filter)
    {
        // TODO Auto-generated method stub
        return null;
    }

    public void writeEntry(
        long offset,
        long timestamp,
        KafkaKeyFW key,
        ArrayFW<KafkaHeaderFW> headers,
        OctetsFW payload)
    {
        writeEntryStart(timestamp, key);
        writeEntryContinue(payload);
        writeEntryFinish(headers, offset);
    }

    public void writeEntryStart(
        long timestamp,
        KafkaKeyFW key)
    {
        // append timestamp and key to partition cache
    }

    public void writeEntryContinue(
        OctetsFW payload)
    {
        // append payload to partition cache
    }

    public void writeEntryFinish(
        ArrayFW<KafkaHeaderFW> headers,
        long offset)
    {
        // append headers to partition cache
    }

    private KafkaCacheSegment seekSegment(
        int offset)
    {
        Map.Entry<Integer, KafkaCacheSegment> entry = segmentsByOffset.floorEntry(offset);
        if (entry == null)
        {
            entry = segmentsByOffset.firstEntry();
        }

        assert entry != null;

        return entry.getValue();
    }

    private KafkaCacheSegment nextSegment(
        int offset)
    {
        assert segmentsByOffset.isEmpty() || offset > segmentsByOffset.lastKey();

        final KafkaCacheSegment segment = new KafkaCacheSegment(path, offset, segmentBytes);
        segmentsByOffset.put(offset, segment);

        return segment;
    }

    static Path initDirectory(
        Path cacheDirectory,
        String clusterName,
        String topicName,
        int partitionId)
    {
        final String partitionName = String.format("%s-%d", topicName, partitionId);
        final Path directory = cacheDirectory.resolve(clusterName).resolve(partitionName);

        try
        {
            Files.createDirectories(directory);
        }
        catch (IOException ex)
        {
            LangUtil.rethrowUnchecked(ex);
        }

        return directory;
    }
}
