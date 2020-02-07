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

import java.nio.file.Path;

import org.agrona.MutableDirectBuffer;

public final class KafkaCacheLogIndexFile extends KafkaCacheIndexFile
{
    KafkaCacheLogIndexFile(
        MutableDirectBuffer writeBuffer,
        Path directory,
        long baseOffset,
        int maxCapacity)
    {
        super(writeBuffer, filename(directory, baseOffset, "index"), baseOffset, maxCapacity);
    }

    public int findOffset(
        long offset,
        int index)
    {
        final int deltaOffset = (int)(offset - baseOffset);
        return super.seek(deltaOffset, index);
    }

    public int position(
        int index)
    {
        return super.value(index);
    }
}
