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

import static org.reaktivity.nukleus.kafka.internal.cache.KafkaCacheSegmentFactory.CACHE_EXTENSION_LOG_INDEX;

import org.agrona.MutableDirectBuffer;
import org.reaktivity.nukleus.kafka.internal.cache.KafkaCacheSegmentFactory.KafkaCacheHeadSegment;

public final class KafkaCacheHeadLogIndexFile extends KafkaCacheHeadIndexFile
{
    KafkaCacheHeadLogIndexFile(
        KafkaCacheHeadSegment segment,
        MutableDirectBuffer writeBuffer,
        int maxCapacity)
    {
        super(segment, CACHE_EXTENSION_LOG_INDEX, writeBuffer, maxCapacity);
    }

    public long seekOffset(
        long offset)
    {
        final int deltaOffset = (int)(offset - baseOffset);
        return super.seekKey(deltaOffset);
    }

    public long scanOffset(
        long record)
    {
        return super.scanIndex(record);
    }
}