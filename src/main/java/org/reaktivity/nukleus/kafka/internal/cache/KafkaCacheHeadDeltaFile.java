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

import static org.reaktivity.nukleus.kafka.internal.cache.KafkaCacheSegmentFactory.CACHE_EXTENSION_DELTA;

import org.agrona.MutableDirectBuffer;
import org.reaktivity.nukleus.kafka.internal.cache.KafkaCacheSegmentFactory.KafkaCacheHeadSegment;
import org.reaktivity.nukleus.kafka.internal.types.cache.KafkaCacheDeltaFW;

public final class KafkaCacheHeadDeltaFile extends KafkaCacheHeadFile
{
    KafkaCacheHeadDeltaFile(
        KafkaCacheHeadSegment segment,
        MutableDirectBuffer writeBuffer)
    {
        super(segment, CACHE_EXTENSION_DELTA, writeBuffer, segment.topicConfig.segmentBytes);
    }

    public KafkaCacheDeltaFW read(
        int position,
        KafkaCacheDeltaFW delta)
    {
        assert position >= 0;
        assert delta != null;
        return delta.tryWrap(readableBuf, position, readCapacity);
    }
}