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
package org.reaktivity.nukleus.kafka.internal.stream;

import static java.lang.System.currentTimeMillis;
import static java.util.Objects.requireNonNull;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.reaktivity.nukleus.buffer.BufferPool.NO_SLOT;

import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.LongFunction;
import java.util.function.LongSupplier;
import java.util.function.LongUnaryOperator;
import java.util.function.ToIntFunction;

import org.agrona.DirectBuffer;
import org.agrona.MutableDirectBuffer;
import org.agrona.collections.Int2IntHashMap;
import org.agrona.collections.Long2ObjectHashMap;
import org.agrona.concurrent.UnsafeBuffer;
import org.reaktivity.nukleus.budget.BudgetDebitor;
import org.reaktivity.nukleus.buffer.BufferPool;
import org.reaktivity.nukleus.concurrent.Signaler;
import org.reaktivity.nukleus.function.MessageConsumer;
import org.reaktivity.nukleus.function.MessageFunction;
import org.reaktivity.nukleus.function.MessagePredicate;
import org.reaktivity.nukleus.kafka.internal.KafkaConfiguration;
import org.reaktivity.nukleus.kafka.internal.KafkaNukleus;
import org.reaktivity.nukleus.kafka.internal.types.Flyweight;
import org.reaktivity.nukleus.kafka.internal.types.OctetsFW;
import org.reaktivity.nukleus.kafka.internal.types.String16FW;
import org.reaktivity.nukleus.kafka.internal.types.codec.RequestHeaderFW;
import org.reaktivity.nukleus.kafka.internal.types.codec.ResponseHeaderFW;
import org.reaktivity.nukleus.kafka.internal.types.codec.metadata.BrokerMetadataFW;
import org.reaktivity.nukleus.kafka.internal.types.codec.metadata.MetadataRequestFW;
import org.reaktivity.nukleus.kafka.internal.types.codec.metadata.MetadataResponseFW;
import org.reaktivity.nukleus.kafka.internal.types.codec.metadata.MetadataResponsePart2FW;
import org.reaktivity.nukleus.kafka.internal.types.codec.metadata.PartitionMetadataFW;
import org.reaktivity.nukleus.kafka.internal.types.codec.metadata.TopicMetadataFW;
import org.reaktivity.nukleus.kafka.internal.types.control.KafkaRouteExFW;
import org.reaktivity.nukleus.kafka.internal.types.control.RouteFW;
import org.reaktivity.nukleus.kafka.internal.types.stream.AbortFW;
import org.reaktivity.nukleus.kafka.internal.types.stream.BeginFW;
import org.reaktivity.nukleus.kafka.internal.types.stream.DataFW;
import org.reaktivity.nukleus.kafka.internal.types.stream.EndFW;
import org.reaktivity.nukleus.kafka.internal.types.stream.ExtensionFW;
import org.reaktivity.nukleus.kafka.internal.types.stream.KafkaBeginExFW;
import org.reaktivity.nukleus.kafka.internal.types.stream.KafkaDataExFW;
import org.reaktivity.nukleus.kafka.internal.types.stream.ResetFW;
import org.reaktivity.nukleus.kafka.internal.types.stream.SignalFW;
import org.reaktivity.nukleus.kafka.internal.types.stream.WindowFW;
import org.reaktivity.nukleus.route.RouteManager;
import org.reaktivity.nukleus.stream.StreamFactory;

public final class KafkaClientMetaFactory implements StreamFactory
{
    private static final int SIGNAL_NEXT_REQUEST = 1;

    private static final Consumer<OctetsFW.Builder> EMPTY_EXTENSION = ex -> {};

    private static final short METADATA_API_KEY = 3;
    private static final short METADATA_API_VERSION = 5;

    private final RouteFW routeRO = new RouteFW();
    private final KafkaRouteExFW routeExRO = new KafkaRouteExFW();

    private final BeginFW beginRO = new BeginFW();
    private final DataFW dataRO = new DataFW();
    private final EndFW endRO = new EndFW();
    private final AbortFW abortRO = new AbortFW();
    private final ResetFW resetRO = new ResetFW();
    private final WindowFW windowRO = new WindowFW();
    private final SignalFW signalRO = new SignalFW();
    private final ExtensionFW extensionRO = new ExtensionFW();
    private final KafkaBeginExFW kafkaBeginExRO = new KafkaBeginExFW();

    private final BeginFW.Builder beginRW = new BeginFW.Builder();
    private final DataFW.Builder dataRW = new DataFW.Builder();
    private final EndFW.Builder endRW = new EndFW.Builder();
    private final AbortFW.Builder abortRW = new AbortFW.Builder();
    private final ResetFW.Builder resetRW = new ResetFW.Builder();
    private final WindowFW.Builder windowRW = new WindowFW.Builder();
    private final KafkaBeginExFW.Builder kafkaBeginExRW = new KafkaBeginExFW.Builder();
    private final KafkaDataExFW.Builder kafkaDataExRW = new KafkaDataExFW.Builder();

    private final RequestHeaderFW.Builder requestHeaderRW = new RequestHeaderFW.Builder();
    private final MetadataRequestFW.Builder metadataRequestRW = new MetadataRequestFW.Builder();

    private final ResponseHeaderFW responseHeaderRO = new ResponseHeaderFW();
    private final MetadataResponseFW metadataResponseRO = new MetadataResponseFW();
    private final BrokerMetadataFW brokerMetadataRO = new BrokerMetadataFW();
    private final MetadataResponsePart2FW metadataResponsePart2RO = new MetadataResponsePart2FW();
    private final TopicMetadataFW topicMetadataRO = new TopicMetadataFW();
    private final PartitionMetadataFW partitionMetadataRO = new PartitionMetadataFW();

    private final Int2IntHashMap newPartitions = new Int2IntHashMap(-1);

    private final KafkaMetaClientDecoder decodeResponse = this::decodeResponse;
    private final KafkaMetaClientDecoder decodeIgnoreAll = this::decodeIgnoreAll;

    private final MessageFunction<RouteFW> wrapRoute = (t, b, i, l) -> routeRO.wrap(b, i, i + l);

    private final long metaMaxAgeMillis;
    private final int kafkaTypeId;
    private final RouteManager router;
    private final MutableDirectBuffer writeBuffer;
    private final MutableDirectBuffer extBuffer;
    private final BufferPool decodePool;
    private final BufferPool encodePool;
    private final Signaler signaler;
    private final LongUnaryOperator supplyInitialId;
    private final LongUnaryOperator supplyReplyId;
    private final Long2ObjectHashMap<MessageConsumer> correlations;
    private final Long2ObjectHashMap<Long2ObjectHashMap<KafkaBrokerInfo>> brokersByRouteId;

    public KafkaClientMetaFactory(
        KafkaConfiguration config,
        RouteManager router,
        Signaler signaler,
        MutableDirectBuffer writeBuffer,
        BufferPool bufferPool,
        LongUnaryOperator supplyInitialId,
        LongUnaryOperator supplyReplyId,
        LongSupplier supplyTraceId,
        ToIntFunction<String> supplyTypeId,
        LongFunction<BudgetDebitor> supplyDebitor,
        Long2ObjectHashMap<MessageConsumer> correlations,
        Long2ObjectHashMap<Long2ObjectHashMap<KafkaBrokerInfo>> brokersByRouteId)
    {
        this.metaMaxAgeMillis = SECONDS.toMillis(config.metaMaxAge());
        this.kafkaTypeId = supplyTypeId.applyAsInt(KafkaNukleus.NAME);
        this.router = router;
        this.signaler = signaler;
        this.writeBuffer = new UnsafeBuffer(new byte[writeBuffer.capacity()]);
        this.extBuffer = new UnsafeBuffer(new byte[writeBuffer.capacity()]);
        this.decodePool = bufferPool;
        this.encodePool = bufferPool;
        this.supplyInitialId = supplyInitialId;
        this.supplyReplyId = supplyReplyId;
        this.correlations = correlations;
        this.brokersByRouteId = brokersByRouteId;
    }

    @Override
    public MessageConsumer newStream(
        int msgTypeId,
        DirectBuffer buffer,
        int index,
        int length,
        MessageConsumer sender)
    {
        final BeginFW begin = beginRO.wrap(buffer, index, index + length);
        final long streamId = begin.streamId();

        MessageConsumer newStream = null;

        if ((streamId & 0x0000_0000_0000_0001L) != 0L)
        {
            newStream = newApplicationStream(begin, sender);
        }
        else
        {
            newStream = newNetworkStream(begin, sender);
        }

        return newStream;
    }

    private MessageConsumer newApplicationStream(
        BeginFW begin,
        MessageConsumer application)
    {
        final long routeId = begin.routeId();
        final long initialId = begin.streamId();
        final long affinity = begin.affinity();
        final long authorization = begin.authorization();
        final OctetsFW extension = begin.extension();
        final ExtensionFW beginEx = extensionRO.tryWrap(extension.buffer(), extension.offset(), extension.limit());
        final KafkaBeginExFW kafkaBeginEx = beginEx != null && beginEx.typeId() == kafkaTypeId ?
                kafkaBeginExRO.tryWrap(extension.buffer(), extension.offset(), extension.limit()) : null;

        assert kafkaBeginEx.kind() == KafkaBeginExFW.KIND_META;
        final String16FW beginTopic = kafkaBeginEx.meta().topic();

        final MessagePredicate filter = (t, b, i, l) ->
        {
            final RouteFW route = wrapRoute.apply(t, b, i, l);
            final KafkaRouteExFW routeEx = route.extension().get(routeExRO::tryWrap);
            final String16FW routeTopic = routeEx.topic();
            return Objects.equals(routeTopic, beginTopic);
        };

        final RouteFW route = router.resolve(routeId, authorization, filter, wrapRoute);

        MessageConsumer newStream = null;

        if (route != null && kafkaBeginEx != null)
        {
            final long resolvedId = route.correlationId();
            final String topic = beginTopic != null ? beginTopic.asString() : null;

            newStream = new KafkaMetaStream(
                    application,
                    routeId,
                    initialId,
                    affinity,
                    resolvedId,
                    topic)::onApplication;
        }

        return newStream;
    }

    private MessageConsumer newNetworkStream(
        BeginFW begin,
        MessageConsumer network)
    {
        final long streamId = begin.streamId();

        return correlations.remove(streamId);
    }

    private void doBegin(
        MessageConsumer receiver,
        long routeId,
        long streamId,
        long traceId,
        long authorization,
        long affinity,
        Consumer<OctetsFW.Builder> extension)
    {
        final BeginFW begin = beginRW.wrap(writeBuffer, 0, writeBuffer.capacity())
                .routeId(routeId)
                .streamId(streamId)
                .traceId(traceId)
                .authorization(authorization)
                .affinity(affinity)
                .extension(extension)
                .build();

        receiver.accept(begin.typeId(), begin.buffer(), begin.offset(), begin.sizeof());
    }

    private void doData(
        MessageConsumer receiver,
        long routeId,
        long streamId,
        long traceId,
        long authorization,
        long budgetId,
        int reserved,
        DirectBuffer payload,
        int offset,
        int length,
        Consumer<OctetsFW.Builder> extension)
    {
        final DataFW data = dataRW.wrap(writeBuffer, 0, writeBuffer.capacity())
                .routeId(routeId)
                .streamId(streamId)
                .traceId(traceId)
                .authorization(authorization)
                .budgetId(budgetId)
                .reserved(reserved)
                .payload(payload, offset, length)
                .extension(extension)
                .build();

        receiver.accept(data.typeId(), data.buffer(), data.offset(), data.sizeof());
    }

    private void doDataNull(
        MessageConsumer receiver,
        long routeId,
        long streamId,
        long traceId,
        long authorization,
        long budgetId,
        int reserved,
        Flyweight extension)
    {
        final DataFW data = dataRW.wrap(writeBuffer, 0, writeBuffer.capacity())
                .routeId(routeId)
                .streamId(streamId)
                .traceId(traceId)
                .authorization(authorization)
                .budgetId(budgetId)
                .reserved(reserved)
                .extension(extension.buffer(), extension.offset(), extension.sizeof())
                .build();

        receiver.accept(data.typeId(), data.buffer(), data.offset(), data.sizeof());
    }

    private void doEnd(
        MessageConsumer receiver,
        long routeId,
        long streamId,
        long traceId,
        long authorization,
        Consumer<OctetsFW.Builder> extension)
    {
        final EndFW end = endRW.wrap(writeBuffer, 0, writeBuffer.capacity())
                               .routeId(routeId)
                               .streamId(streamId)
                               .traceId(traceId)
                               .authorization(authorization)
                               .extension(extension)
                               .build();

        receiver.accept(end.typeId(), end.buffer(), end.offset(), end.sizeof());
    }

    private void doAbort(
        MessageConsumer receiver,
        long routeId,
        long streamId,
        long traceId,
        long authorization,
        Consumer<OctetsFW.Builder> extension)
    {
        final AbortFW abort = abortRW.wrap(writeBuffer, 0, writeBuffer.capacity())
                .routeId(routeId)
                .streamId(streamId)
                .traceId(traceId)
                .authorization(authorization)
                .extension(extension)
                .build();

        receiver.accept(abort.typeId(), abort.buffer(), abort.offset(), abort.sizeof());
    }

    private void doWindow(
        MessageConsumer sender,
        long routeId,
        long streamId,
        long traceId,
        long authorization,
        long budgetId,
        int credit,
        int padding)
    {
        final WindowFW window = windowRW.wrap(writeBuffer, 0, writeBuffer.capacity())
                .routeId(routeId)
                .streamId(streamId)
                .traceId(traceId)
                .authorization(authorization)
                .budgetId(budgetId)
                .credit(credit)
                .padding(padding)
                .build();

        sender.accept(window.typeId(), window.buffer(), window.offset(), window.sizeof());
    }

    private void doReset(
        MessageConsumer sender,
        long routeId,
        long streamId,
        long traceId,
        long authorization)
    {
        final ResetFW reset = resetRW.wrap(writeBuffer, 0, writeBuffer.capacity())
               .routeId(routeId)
               .streamId(streamId)
               .traceId(traceId)
               .authorization(authorization)
               .build();

        sender.accept(reset.typeId(), reset.buffer(), reset.offset(), reset.sizeof());
    }

    @FunctionalInterface
    private interface KafkaMetaClientDecoder
    {
        int decode(
            KafkaMetaStream.KafkaMetaClient client,
            long traceId,
            long authorization,
            long budgetId,
            int reserved,
            MutableDirectBuffer buffer,
            int offset,
            int progress,
            int limit);
    }

    private int decodeResponse(
        KafkaMetaStream.KafkaMetaClient client,
        long traceId,
        long authorization,
        long budgetId,
        int reserved,
        DirectBuffer buffer,
        int offset,
        int progress,
        int limit)
    {
        final int length = limit - progress;

        decode:
        if (length != 0)
        {
            final ResponseHeaderFW responseHeader = responseHeaderRO.tryWrap(buffer, progress, limit);
            if (responseHeader == null)
            {
                client.decoder = decodeIgnoreAll;
                break decode;
            }

            progress = responseHeader.limit();

            final int responseSize = responseHeader.length();

            if (length >= responseHeader.sizeof() + responseSize)
            {
                final MetadataResponseFW metadataResponse = metadataResponseRO.tryWrap(buffer, progress, limit);
                if (metadataResponse == null)
                {
                    client.decoder = decodeIgnoreAll;
                    break decode;
                }

                progress = metadataResponse.limit();

                final int brokerCount = metadataResponse.brokerCount();
                for (int brokerIndex = 0; brokerIndex < brokerCount; brokerIndex++)
                {
                    final BrokerMetadataFW broker = brokerMetadataRO.tryWrap(buffer, progress, limit);
                    if (broker == null)
                    {
                        client.decoder = decodeIgnoreAll;
                        break decode;
                    }

                    progress = broker.limit();

                    final int brokerId = broker.nodeId();
                    final String host = broker.host().asString();
                    final int port = broker.port();

                    client.onDecodeBroker(brokerId, host, port);
                }

                final MetadataResponsePart2FW metadataResponsePart2 =
                        metadataResponsePart2RO.tryWrap(buffer, progress, limit);

                if (metadataResponsePart2 == null)
                {
                    client.decoder = decodeIgnoreAll;
                    break decode;
                }

                progress = metadataResponsePart2.limit();

                final int topicCount = metadataResponsePart2.topicCount();
                assert topicCount == 1;
                for (int topicIndex = 0; topicIndex < topicCount; topicIndex++)
                {
                    final TopicMetadataFW topicMetadata = topicMetadataRO.tryWrap(buffer, progress, limit);
                    if (topicMetadata == null)
                    {
                        client.decoder = decodeIgnoreAll;
                        break decode;
                    }

                    progress = topicMetadata.limit();

                    final String topic = topicMetadata.topic().asString();
                    final int topicError = topicMetadata.errorCode();
                    if (topicError != 0 || !client.topic.equals(topic))
                    {
                        client.decoder = decodeIgnoreAll;
                        break decode;
                    }

                    newPartitions.clear();
                    final int partitionCount = topicMetadata.partitionCount();
                    for (int partitionIndex = 0; partitionIndex < partitionCount; partitionIndex++)
                    {
                        final PartitionMetadataFW partition = partitionMetadataRO.tryWrap(buffer, progress, limit);
                        if (partition == null)
                        {
                            client.decoder = decodeIgnoreAll;
                            break decode;
                        }

                        progress = partition.limit();

                        final int partitionError = partition.errorCode();
                        if (partitionError != 0)
                        {
                            client.decoder = decodeIgnoreAll;
                            break decode;
                        }

                        final int partitionId = partition.partitionId();
                        final int leaderId = partition.leader();

                        newPartitions.put(partitionId, leaderId);
                    }

                    client.onDecodeResponse(traceId, newPartitions);
                }
            }
        }

        if (client.decoder == decodeIgnoreAll)
        {
            client.cleanupNetwork(traceId);
        }

        return progress;
    }

    private int decodeIgnoreAll(
        KafkaMetaStream.KafkaMetaClient client,
        long traceId,
        long authorization,
        long budgetId,
        int reserved,
        DirectBuffer buffer,
        int offset,
        int progress,
        int limit)
    {
        return limit;
    }

    private final class KafkaMetaStream
    {
        private final MessageConsumer application;
        private final long routeId;
        private final long initialId;
        private final long replyId;
        private final long affinity;
        private final KafkaMetaClient client;

        private int state;

        private long replyBudgetId;
        private int replyBudget;
        private int replyPadding;

        KafkaMetaStream(
            MessageConsumer application,
            long routeId,
            long initialId,
            long affinity,
            long resolvedId,
            String topic)
        {
            this.application = application;
            this.routeId = routeId;
            this.initialId = initialId;
            this.replyId = supplyReplyId.applyAsLong(initialId);
            this.affinity = affinity;
            this.client = new KafkaMetaClient(resolvedId, topic);
        }

        private void onApplication(
            int msgTypeId,
            DirectBuffer buffer,
            int index,
            int length)
        {
            switch (msgTypeId)
            {
            case BeginFW.TYPE_ID:
                final BeginFW begin = beginRO.wrap(buffer, index, index + length);
                onApplicationBegin(begin);
                break;
            case DataFW.TYPE_ID:
                final DataFW data = dataRO.wrap(buffer, index, index + length);
                onApplicationData(data);
                break;
            case EndFW.TYPE_ID:
                final EndFW end = endRO.wrap(buffer, index, index + length);
                onApplicationEnd(end);
                break;
            case AbortFW.TYPE_ID:
                final AbortFW abort = abortRO.wrap(buffer, index, index + length);
                onApplicationAbort(abort);
                break;
            case WindowFW.TYPE_ID:
                final WindowFW window = windowRO.wrap(buffer, index, index + length);
                onApplicationWindow(window);
                break;
            case ResetFW.TYPE_ID:
                final ResetFW reset = resetRO.wrap(buffer, index, index + length);
                onApplicationReset(reset);
                break;
            default:
                break;
            }
        }

        private void onApplicationBegin(
            BeginFW begin)
        {
            final long traceId = begin.traceId();
            final long authorization = begin.authorization();

            state = KafkaState.openingInitial(state);

            client.doNetworkBegin(traceId, authorization, affinity);
        }

        private void onApplicationData(
            DataFW data)
        {
            final long traceId = data.traceId();

            client.cleanupNetwork(traceId);
        }

        private void onApplicationEnd(
            EndFW end)
        {
            final long traceId = end.traceId();
            final long authorization = end.authorization();

            state = KafkaState.closedInitial(state);

            client.doNetworkEnd(traceId, authorization);
        }

        private void onApplicationAbort(
            AbortFW abort)
        {
            final long traceId = abort.traceId();

            state = KafkaState.closedInitial(state);

            client.doNetworkAbortIfNecessary(traceId);
        }

        private void onApplicationWindow(
            WindowFW window)
        {
            final long budgetId = window.budgetId();
            final int credit = window.credit();
            final int padding = window.padding();

            replyBudgetId = budgetId;
            replyBudget += credit;
            replyPadding = padding;
        }

        private void onApplicationReset(
            ResetFW reset)
        {
            final long traceId = reset.traceId();

            state = KafkaState.closedInitial(state);

            client.doNetworkResetIfNecessary(traceId);
        }

        private void doApplicationBeginIfNecessary(
            long traceId,
            long authorization,
            String topic)
        {
            if (!KafkaState.replyOpening(state))
            {
                doApplicationBegin(traceId, authorization, topic);
            }
        }

        private void doApplicationBegin(
            long traceId,
            long authorization,
            String topic)
        {
            state = KafkaState.openingReply(state);

            router.setThrottle(replyId, this::onApplication);
            doBegin(application, routeId, replyId, traceId, authorization, affinity,
                ex -> ex.set((b, o, l) -> kafkaBeginExRW.wrap(b, o, l)
                                                        .typeId(kafkaTypeId)
                                                        .meta(m -> m.topic(topic))
                                                        .build()
                                                        .sizeof()));
        }

        private void doApplicationData(
            long traceId,
            long authorization,
            KafkaDataExFW extension)
        {
            final int reserved = replyPadding;

            replyBudget -= reserved;

            assert replyBudget >= 0;

            doDataNull(application, routeId, replyId, traceId, authorization, replyBudgetId, reserved, extension);
        }

        private void doApplicationEnd(
            long traceId)
        {
            state = KafkaState.closedReply(state);
            //client.stream = nullIfClosed(state, client.stream);
            doEnd(application, routeId, replyId, traceId, client.authorization, EMPTY_EXTENSION);
        }

        private void doApplicationAbort(
            long traceId)
        {
            state = KafkaState.closedReply(state);
            //client.stream = nullIfClosed(state, client.stream);
            doAbort(application, routeId, replyId, traceId, client.authorization, EMPTY_EXTENSION);
        }

        private void doApplicationWindow(
            long traceId,
            long budgetId,
            int credit,
            int padding)
        {
            if (!KafkaState.initialOpened(state) || credit > 0)
            {
                doWindow(application, routeId, initialId, traceId, client.authorization,
                        budgetId, credit, padding);
            }

            state = KafkaState.openedInitial(state);
        }

        private void doApplicationReset(
            long traceId)
        {
            state = KafkaState.closedInitial(state);
            //client.stream = nullIfClosed(state, client.stream);

            doReset(application, routeId, initialId, traceId, client.authorization);
        }

        private void doApplicationAbortIfNecessary(
            long traceId)
        {
            if (KafkaState.replyOpening(state) && !KafkaState.replyClosed(state))
            {
                doApplicationAbort(traceId);
            }
        }

        private void doApplicationResetIfNecessary(
            long traceId)
        {
            if (KafkaState.initialOpening(state) && !KafkaState.initialClosed(state))
            {
                doApplicationReset(traceId);
            }
        }

        private void cleanupApplication(
            long traceId)
        {
            doApplicationAbortIfNecessary(traceId);
            doApplicationResetIfNecessary(traceId);
        }

        private final class KafkaMetaClient
        {
            private final long routeId;
            private final long initialId;
            private final long replyId;
            private final MessageConsumer network;
            private final String topic;
            private final Int2IntHashMap partitions;

            private int state;
            private long authorization;

            private long initialBudgetId;
            private int initialBudget;
            private int initialPadding;
            private int replyBudget;

            private int encodeSlot = NO_SLOT;
            private int encodeSlotOffset;
            private long encodeSlotTraceId;

            private int decodeSlot = NO_SLOT;
            private int decodeSlotOffset;
            private int decodeSlotReserved;

            private int nextRequestId;
            private int nextResponseId;

            private KafkaMetaClientDecoder decoder;

            KafkaMetaClient(
                long routeId,
                String topic)
            {
                this.routeId = routeId;
                this.initialId = supplyInitialId.applyAsLong(routeId);
                this.replyId = supplyReplyId.applyAsLong(initialId);
                this.network = router.supplyReceiver(initialId);
                this.decoder = decodeResponse;
                this.topic = requireNonNull(topic);
                this.partitions = new Int2IntHashMap(-1);
            }

            private void onNetwork(
                int msgTypeId,
                DirectBuffer buffer,
                int index,
                int length)
            {
                switch (msgTypeId)
                {
                case BeginFW.TYPE_ID:
                    final BeginFW begin = beginRO.wrap(buffer, index, index + length);
                    onNetworkBegin(begin);
                    break;
                case DataFW.TYPE_ID:
                    final DataFW data = dataRO.wrap(buffer, index, index + length);
                    onNetworkData(data);
                    break;
                case EndFW.TYPE_ID:
                    final EndFW end = endRO.wrap(buffer, index, index + length);
                    onNetworkEnd(end);
                    break;
                case AbortFW.TYPE_ID:
                    final AbortFW abort = abortRO.wrap(buffer, index, index + length);
                    onNetworkAbort(abort);
                    break;
                case ResetFW.TYPE_ID:
                    final ResetFW reset = resetRO.wrap(buffer, index, index + length);
                    onNetworkReset(reset);
                    break;
                case WindowFW.TYPE_ID:
                    final WindowFW window = windowRO.wrap(buffer, index, index + length);
                    onNetworkWindow(window);
                    break;
                case SignalFW.TYPE_ID:
                    final SignalFW signal = signalRO.wrap(buffer, index, index + length);
                    onNetworkSignal(signal);
                    break;
                default:
                    break;
                }
            }

            private void onNetworkBegin(
                BeginFW begin)
            {
                final long traceId = begin.traceId();

                authorization = begin.authorization();
                state = KafkaState.openedReply(state);

                doNetworkWindow(traceId, 0L, decodePool.slotCapacity(), 0);
            }

            private void onNetworkData(
                DataFW data)
            {
                final long traceId = data.traceId();
                final long budgetId = data.budgetId();

                authorization = data.authorization();
                replyBudget -= data.reserved();

                if (replyBudget < 0)
                {
                    cleanupNetwork(traceId);
                }
                else
                {
                    if (decodeSlot == NO_SLOT)
                    {
                        decodeSlot = decodePool.acquire(initialId);
                    }

                    if (decodeSlot == NO_SLOT)
                    {
                        cleanupNetwork(traceId);
                    }
                    else
                    {
                        final OctetsFW payload = data.payload();
                        int reserved = data.reserved();
                        int offset = payload.offset();
                        int limit = payload.limit();

                        final MutableDirectBuffer buffer = decodePool.buffer(decodeSlot);
                        buffer.putBytes(decodeSlotOffset, payload.buffer(), offset, limit - offset);
                        decodeSlotOffset += limit - offset;
                        decodeSlotReserved += reserved;

                        offset = 0;
                        limit = decodeSlotOffset;
                        reserved = decodeSlotReserved;

                        decodeNetwork(traceId, authorization, budgetId, reserved, buffer, offset, limit);
                    }
                }
            }

            private void onNetworkEnd(
                EndFW end)
            {
                final long traceId = end.traceId();

                state = KafkaState.closedReply(state);

                doApplicationEnd(traceId);
            }

            private void onNetworkAbort(
                AbortFW abort)
            {
                final long traceId = abort.traceId();

                state = KafkaState.closedReply(state);

                cleanupNetwork(traceId);
            }

            private void onNetworkReset(
                ResetFW reset)
            {
                final long traceId = reset.traceId();

                state = KafkaState.closedInitial(state);

                cleanupNetwork(traceId);
            }

            private void onNetworkWindow(
                WindowFW window)
            {
                final long traceId = window.traceId();
                final long budgetId = window.budgetId();
                final int credit = window.credit();
                final int padding = window.padding();

                authorization = window.authorization();

                initialBudgetId = budgetId;
                initialBudget += credit;
                initialPadding = padding;

                state = KafkaState.openedInitial(state);

                if (encodeSlot != NO_SLOT)
                {
                    final MutableDirectBuffer buffer = encodePool.buffer(encodeSlot);
                    final int limit = encodeSlotOffset;

                    encodeNetwork(encodeSlotTraceId, authorization, budgetId, buffer, 0, limit);
                }

                doEncodeRequestIfNecessary(traceId, budgetId);
            }

            private void onNetworkSignal(
                SignalFW signal)
            {
                final long traceId = signal.traceId();
                final int signalId = signal.signalId();

                if (signalId == SIGNAL_NEXT_REQUEST)
                {
                    doEncodeRequestIfNecessary(traceId, initialBudgetId);
                }
            }

            private void doNetworkBegin(
                long traceId,
                long authorization,
                long affinity)
            {
                state = KafkaState.openingInitial(state);
                correlations.put(replyId, this::onNetwork);

                router.setThrottle(initialId, this::onNetwork);
                doBegin(network, routeId, initialId, traceId, authorization, affinity, EMPTY_EXTENSION);
            }

            private void doNetworkData(
                long traceId,
                long budgetId,
                DirectBuffer buffer,
                int offset,
                int limit)
            {
                if (encodeSlot != NO_SLOT)
                {
                    final MutableDirectBuffer encodeBuffer = encodePool.buffer(encodeSlot);
                    encodeBuffer.putBytes(encodeSlotOffset, buffer, offset, limit - offset);
                    encodeSlotOffset += limit - offset;
                    encodeSlotTraceId = traceId;

                    buffer = encodeBuffer;
                    offset = 0;
                    limit = encodeSlotOffset;
                }

                encodeNetwork(traceId, authorization, budgetId, buffer, offset, limit);
            }

            private void doNetworkEnd(
                long traceId,
                long authorization)
            {
                state = KafkaState.closedInitial(state);

                doEnd(network, routeId, initialId, traceId, authorization, EMPTY_EXTENSION);
            }

            private void doNetworkAbortIfNecessary(
                long traceId)
            {
                if (!KafkaState.initialClosed(state))
                {
                    doAbort(network, routeId, initialId, traceId, authorization, EMPTY_EXTENSION);
                    state = KafkaState.closedInitial(state);
                }

                cleanupEncodeSlotIfNecessary();
            }

            private void doNetworkResetIfNecessary(
                long traceId)
            {
                if (!KafkaState.replyClosed(state))
                {
                    doReset(network, routeId, replyId, traceId, authorization);
                    state = KafkaState.closedReply(state);
                }

                cleanupDecodeSlotIfNecessary();
            }

            private void doNetworkWindow(
                long traceId,
                long budgetId,
                int credit,
                int padding)
            {
                assert credit > 0 : String.format("%d > 0", credit);

                replyBudget += credit;

                doWindow(network, routeId, replyId, traceId, authorization, budgetId, credit, padding);
            }

            private void doEncodeRequestIfNecessary(
                long traceId,
                long budgetId)
            {
                if (nextRequestId == nextResponseId)
                {
                    doEncodeRequest(traceId, budgetId);
                }
            }

            private void doEncodeRequest(
                long traceId,
                long budgetId)
            {
                final MutableDirectBuffer encodeBuffer = writeBuffer;
                final int encodeOffset = DataFW.FIELD_OFFSET_PAYLOAD;
                final int encodeLimit = encodeBuffer.capacity();

                int encodeProgress = encodeOffset;

                final RequestHeaderFW requestHeader = requestHeaderRW.wrap(encodeBuffer, encodeProgress, encodeLimit)
                        .length(0)
                        .apiKey(METADATA_API_KEY)
                        .apiVersion(METADATA_API_VERSION)
                        .correlationId(0)
                        .clientId((String) null)
                        .build();

                encodeProgress = requestHeader.limit();

                final MetadataRequestFW metadataRequest = metadataRequestRW.wrap(encodeBuffer, encodeProgress, encodeLimit)
                        .topicCount(1)
                        .topicName(topic)
                        .build();

                encodeProgress = metadataRequest.limit();

                final int requestId = nextRequestId++;
                final int requestSize = encodeProgress - encodeOffset - RequestHeaderFW.FIELD_OFFSET_API_KEY;

                requestHeaderRW.wrap(encodeBuffer, requestHeader.offset(), requestHeader.limit())
                        .length(requestSize)
                        .apiKey(requestHeader.apiKey())
                        .apiVersion(requestHeader.apiVersion())
                        .correlationId(requestId)
                        .clientId(requestHeader.clientId().asString())
                        .build();

                doNetworkData(traceId, budgetId, encodeBuffer, encodeOffset, encodeProgress);
            }

            private void encodeNetwork(
                long traceId,
                long authorization,
                long budgetId,
                DirectBuffer buffer,
                int offset,
                int limit)
            {
                final int maxLength = limit - offset;
                final int length = Math.max(Math.min(initialBudget - initialPadding, maxLength), 0);

                if (length > 0)
                {
                    final int reserved = length + initialPadding;

                    initialBudget -= reserved;

                    assert initialBudget >= 0 : String.format("%d >= 0", initialBudget);

                    doData(network, routeId, initialId, traceId, authorization, budgetId,
                           reserved, buffer, offset, length, EMPTY_EXTENSION);
                }

                final int remaining = maxLength - length;
                if (remaining > 0)
                {
                    if (encodeSlot == NO_SLOT)
                    {
                        encodeSlot = encodePool.acquire(initialId);
                    }

                    if (encodeSlot == NO_SLOT)
                    {
                        cleanupNetwork(traceId);
                    }
                    else
                    {
                        final MutableDirectBuffer encodeBuffer = encodePool.buffer(encodeSlot);
                        encodeBuffer.putBytes(0, buffer, offset + length, remaining);
                        encodeSlotOffset = remaining;
                    }
                }
                else
                {
                    cleanupEncodeSlotIfNecessary();
                }
            }

            private void decodeNetwork(
                long traceId,
                long authorization,
                long budgetId,
                int reserved,
                MutableDirectBuffer buffer,
                int offset,
                int limit)
            {
                KafkaMetaClientDecoder previous = null;
                int progress = offset;
                while (progress <= limit && previous != decoder)
                {
                    previous = decoder;
                    progress = decoder.decode(this, traceId, authorization, budgetId, reserved, buffer, offset, progress, limit);
                }

                if (progress < limit)
                {
                    if (decodeSlot == NO_SLOT)
                    {
                        decodeSlot = decodePool.acquire(initialId);
                    }

                    if (decodeSlot == NO_SLOT)
                    {
                        cleanupNetwork(traceId);
                    }
                    else
                    {
                        final MutableDirectBuffer decodeBuffer = decodePool.buffer(decodeSlot);
                        decodeBuffer.putBytes(0, buffer, progress, limit - progress);
                        decodeSlotOffset = limit - progress;
                        decodeSlotReserved = (limit - progress) * reserved / (limit - offset);
                    }

                    final int credit = decodePool.slotCapacity() - decodeSlotOffset - replyBudget;
                    if (credit > 0)
                    {
                        doNetworkWindow(traceId, budgetId, credit, 0);
                    }
                }
                else
                {
                    cleanupDecodeSlotIfNecessary();

                    if (KafkaState.replyClosing(state))
                    {
                        doApplicationEnd(traceId);
                    }
                    else
                    {
                        final int credit = progress - offset;
                        if (credit > 0)
                        {
                            doNetworkWindow(traceId, budgetId, credit, 0);
                        }
                    }
                }
            }

            private void onDecodeBroker(
                int brokerId,
                String host,
                int port)
            {
                // TODO: share broker info across meta factory instances
                final Long2ObjectHashMap<KafkaBrokerInfo> brokers =
                        brokersByRouteId.computeIfAbsent(routeId, r -> new Long2ObjectHashMap<>());
                brokers.put(brokerId, new KafkaBrokerInfo(brokerId, host, port));
            }

            private void onDecodeResponse(
                long traceId,
                Int2IntHashMap newPartitions)
            {
                doApplicationWindow(traceId, 0L, 0, 0);
                doApplicationBeginIfNecessary(traceId, authorization, topic);

                if (!partitions.equals(newPartitions))
                {
                    partitions.clear();
                    newPartitions.forEach(partitions::put);

                    final KafkaDataExFW kafkaDataEx = kafkaDataExRW.wrap(extBuffer, 0, extBuffer.capacity())
                        .typeId(kafkaTypeId)
                        .meta(m -> partitions.forEach((k, v) -> m.partitionsItem(pi -> pi.partitionId(k).leaderId(v))))
                        .build();

                    doApplicationData(traceId, authorization, kafkaDataEx);
                }

                nextResponseId++;
                signaler.signalAt(currentTimeMillis() + metaMaxAgeMillis, routeId, initialId, SIGNAL_NEXT_REQUEST);
            }

            private void cleanupNetwork(
                long traceId)
            {
                doNetworkResetIfNecessary(traceId);
                doNetworkAbortIfNecessary(traceId);

                cleanupApplication(traceId);
            }

            private void cleanupDecodeSlotIfNecessary()
            {
                if (decodeSlot != NO_SLOT)
                {
                    decodePool.release(decodeSlot);
                    decodeSlot = NO_SLOT;
                    decodeSlotOffset = 0;
                    decodeSlotReserved = 0;
                }
            }

            private void cleanupEncodeSlotIfNecessary()
            {
                if (encodeSlot != NO_SLOT)
                {
                    encodePool.release(encodeSlot);
                    encodeSlot = NO_SLOT;
                    encodeSlotOffset = 0;
                    encodeSlotTraceId = 0;
                }
            }
        }
    }
}