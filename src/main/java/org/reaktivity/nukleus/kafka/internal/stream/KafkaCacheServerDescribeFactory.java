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

import static java.nio.charset.StandardCharsets.UTF_8;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.function.Consumer;
import java.util.function.LongFunction;
import java.util.function.LongSupplier;
import java.util.function.LongUnaryOperator;
import java.util.function.ToIntFunction;

import org.agrona.DirectBuffer;
import org.agrona.MutableDirectBuffer;
import org.agrona.collections.Long2ObjectHashMap;
import org.agrona.concurrent.UnsafeBuffer;
import org.reaktivity.nukleus.buffer.BufferPool;
import org.reaktivity.nukleus.function.MessageConsumer;
import org.reaktivity.nukleus.function.MessageFunction;
import org.reaktivity.nukleus.function.MessagePredicate;
import org.reaktivity.nukleus.kafka.internal.KafkaConfiguration;
import org.reaktivity.nukleus.kafka.internal.KafkaNukleus;
import org.reaktivity.nukleus.kafka.internal.cache.KafkaCacheTopicConfig;
import org.reaktivity.nukleus.kafka.internal.cache.KafkaCacheTopicConfigSupplier;
import org.reaktivity.nukleus.kafka.internal.types.ArrayFW;
import org.reaktivity.nukleus.kafka.internal.types.Flyweight;
import org.reaktivity.nukleus.kafka.internal.types.KafkaConfigFW;
import org.reaktivity.nukleus.kafka.internal.types.OctetsFW;
import org.reaktivity.nukleus.kafka.internal.types.String16FW;
import org.reaktivity.nukleus.kafka.internal.types.control.KafkaRouteExFW;
import org.reaktivity.nukleus.kafka.internal.types.control.RouteFW;
import org.reaktivity.nukleus.kafka.internal.types.stream.AbortFW;
import org.reaktivity.nukleus.kafka.internal.types.stream.BeginFW;
import org.reaktivity.nukleus.kafka.internal.types.stream.DataFW;
import org.reaktivity.nukleus.kafka.internal.types.stream.EndFW;
import org.reaktivity.nukleus.kafka.internal.types.stream.ExtensionFW;
import org.reaktivity.nukleus.kafka.internal.types.stream.KafkaBeginExFW;
import org.reaktivity.nukleus.kafka.internal.types.stream.KafkaDataExFW;
import org.reaktivity.nukleus.kafka.internal.types.stream.KafkaDescribeBeginExFW;
import org.reaktivity.nukleus.kafka.internal.types.stream.KafkaDescribeDataExFW;
import org.reaktivity.nukleus.kafka.internal.types.stream.ResetFW;
import org.reaktivity.nukleus.kafka.internal.types.stream.WindowFW;
import org.reaktivity.nukleus.route.RouteManager;
import org.reaktivity.nukleus.stream.StreamFactory;

public final class KafkaCacheServerDescribeFactory implements StreamFactory
{
    private static final Consumer<OctetsFW.Builder> EMPTY_EXTENSION = ex -> {};

    private final RouteFW routeRO = new RouteFW();
    private final KafkaRouteExFW routeExRO = new KafkaRouteExFW();

    private final BeginFW beginRO = new BeginFW();
    private final DataFW dataRO = new DataFW();
    private final EndFW endRO = new EndFW();
    private final AbortFW abortRO = new AbortFW();
    private final ResetFW resetRO = new ResetFW();
    private final WindowFW windowRO = new WindowFW();
    private final ExtensionFW extensionRO = new ExtensionFW();
    private final KafkaBeginExFW kafkaBeginExRO = new KafkaBeginExFW();
    private final KafkaDataExFW kafkaDataExRO = new KafkaDataExFW();

    private final BeginFW.Builder beginRW = new BeginFW.Builder();
    private final DataFW.Builder dataRW = new DataFW.Builder();
    private final EndFW.Builder endRW = new EndFW.Builder();
    private final AbortFW.Builder abortRW = new AbortFW.Builder();
    private final ResetFW.Builder resetRW = new ResetFW.Builder();
    private final WindowFW.Builder windowRW = new WindowFW.Builder();
    private final KafkaBeginExFW.Builder kafkaBeginExRW = new KafkaBeginExFW.Builder();
    private final KafkaDataExFW.Builder kafkaDataExRW = new KafkaDataExFW.Builder();

    private final MessageFunction<RouteFW> wrapRoute = (t, b, i, l) -> routeRO.wrap(b, i, i + l);

    private final int kafkaTypeId;
    private final RouteManager router;
    private final MutableDirectBuffer writeBuffer;
    private final MutableDirectBuffer extBuffer;
    private final BufferPool bufferPool;
    private final LongUnaryOperator supplyInitialId;
    private final LongUnaryOperator supplyReplyId;
    private final LongFunction<KafkaCacheRoute> supplyCacheRoute;
    private final KafkaCacheTopicConfigSupplier supplyTopicConfig;
    private final Long2ObjectHashMap<MessageConsumer> correlations;
    private final boolean reconnect;

    public KafkaCacheServerDescribeFactory(
        KafkaConfiguration config,
        RouteManager router,
        MutableDirectBuffer writeBuffer,
        BufferPool bufferPool,
        LongUnaryOperator supplyInitialId,
        LongUnaryOperator supplyReplyId,
        LongSupplier supplyTraceId,
        ToIntFunction<String> supplyTypeId,
        LongFunction<KafkaCacheRoute> supplyCacheRoute,
        KafkaCacheTopicConfigSupplier supplyTopicConfig,
        Long2ObjectHashMap<MessageConsumer> correlations)
    {
        this.kafkaTypeId = supplyTypeId.applyAsInt(KafkaNukleus.NAME);
        this.router = router;
        this.writeBuffer = new UnsafeBuffer(new byte[writeBuffer.capacity()]);
        this.extBuffer = new UnsafeBuffer(new byte[writeBuffer.capacity()]);
        this.bufferPool = bufferPool;
        this.supplyInitialId = supplyInitialId;
        this.supplyReplyId = supplyReplyId;
        this.supplyCacheRoute = supplyCacheRoute;
        this.supplyTopicConfig = supplyTopicConfig;
        this.correlations = correlations;
        this.reconnect = config.cacheServerReconnect();
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
        final long routeId = begin.routeId();
        final long initialId = begin.streamId();
        final long authorization = begin.authorization();
        final long affinity = begin.affinity();

        assert (initialId & 0x0000_0000_0000_0001L) != 0L;

        final OctetsFW extension = begin.extension();
        final ExtensionFW beginEx = extension.get(extensionRO::tryWrap);
        assert beginEx != null && beginEx.typeId() == kafkaTypeId;
        final KafkaBeginExFW kafkaBeginEx = extension.get(kafkaBeginExRO::tryWrap);
        assert kafkaBeginEx.kind() == KafkaBeginExFW.KIND_DESCRIBE;
        final KafkaDescribeBeginExFW kafkaDescribeBeginEx = kafkaBeginEx.describe();
        final String16FW beginTopic = kafkaDescribeBeginEx.topic();

        final MessagePredicate filter = (t, b, i, l) ->
        {
            final RouteFW route = wrapRoute.apply(t, b, i, l);
            final KafkaRouteExFW routeEx = route.extension().get(routeExRO::tryWrap);
            final String16FW routeTopic = routeEx != null ? routeEx.topic() : null;
            return !route.localAddress().equals(route.remoteAddress()) &&
                    routeTopic != null && Objects.equals(routeTopic, beginTopic);
        };

        MessageConsumer newStream = null;

        final RouteFW route = router.resolve(routeId, authorization, filter, wrapRoute);
        if (route != null)
        {
            final long resolvedId = route.correlationId();
            final String topicName = beginTopic.asString();
            final KafkaCacheRoute cacheRoute = supplyCacheRoute.apply(resolvedId);
            final int topicKey = cacheRoute.topicKey(topicName);
            KafkaCacheServerDescribeFanout fanout = cacheRoute.serverDescribeFanoutsByTopic.get(topicKey);
            if (fanout == null)
            {
                final String clusterName = route.localAddress().asString();
                final KafkaCacheTopicConfig topicConfig = supplyTopicConfig.get(clusterName, topicName);
                final List<String> configNames = new ArrayList<>();
                kafkaDescribeBeginEx.configs().forEach(c -> configNames.add(c.asString()));
                final KafkaCacheServerDescribeFanout newFanout =
                        new KafkaCacheServerDescribeFanout(resolvedId, authorization, topicConfig, topicName, configNames);

                cacheRoute.serverDescribeFanoutsByTopic.put(topicKey, newFanout);
                fanout = newFanout;
            }

            if (fanout != null)
            {
                newStream = new KafkaCacheServerDescribeStream(
                        fanout,
                        sender,
                        routeId,
                        initialId,
                        affinity,
                        authorization)::onDescribeMessage;
            }
        }

        return newStream;
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

    final class KafkaCacheServerDescribeFanout
    {
        private final long routeId;
        private final long authorization;
        private final KafkaCacheTopicConfig topicConfig;
        private final String topic;
        private final List<String> configNames;
        private final List<KafkaCacheServerDescribeStream> members;

        private long initialId;
        private long replyId;
        private MessageConsumer receiver;
        private Map<String, String> configValues;

        private int state;

        private KafkaCacheServerDescribeFanout(
            long routeId,
            long authorization,
            KafkaCacheTopicConfig topicConfig,
            String topic,
            List<String> configNames)
        {
            this.routeId = routeId;
            this.authorization = authorization;
            this.topic = topic;
            this.configNames = configNames;
            this.topicConfig = topicConfig;
            this.members = new ArrayList<>();
        }

        private void onDescribeFanoutMemberOpening(
            long traceId,
            KafkaCacheServerDescribeStream member)
        {
            members.add(member);

            assert !members.isEmpty();

            doDescribeFanoutInitialBeginIfNecessary(traceId);

            if (KafkaState.initialOpened(state))
            {
                member.doDescribeInitialWindowIfNecessary(traceId, 0L, 0, 0);
            }

            if (KafkaState.replyOpened(state))
            {
                member.doDescribeReplyBeginIfNecessary(traceId);
            }
        }

        private void onDescribeFanoutMemberOpened(
            long traceId,
            KafkaCacheServerDescribeStream member)
        {
            if (configValues != null)
            {
                final KafkaDataExFW kafkaDataEx =
                        kafkaDataExRW.wrap(extBuffer, 0, extBuffer.capacity())
                                     .typeId(kafkaTypeId)
                                     .describe(d -> configValues.forEach((n, v) -> d.configsItem(i -> i.name(n).value(v))))
                                     .build();
                member.doDescribeReplyDataIfNecessary(traceId, kafkaDataEx);
            }
        }

        private void onDescribeFanoutMemberClosed(
            long traceId,
            KafkaCacheServerDescribeStream member)
        {
            members.remove(member);

            if (members.isEmpty())
            {
                correlations.remove(replyId);
                doDescribeFanoutInitialEndIfNecessary(traceId);
            }
        }

        private void doDescribeFanoutInitialBeginIfNecessary(
            long traceId)
        {
            if (KafkaState.initialClosed(state) &&
                KafkaState.replyClosed(state))
            {
                state = 0;
            }

            if (!KafkaState.initialOpening(state))
            {
                doDescribeFanoutInitialBegin(traceId);
            }
        }

        private void doDescribeFanoutInitialBegin(
            long traceId)
        {
            assert state == 0;

            this.initialId = supplyInitialId.applyAsLong(routeId);
            this.replyId = supplyReplyId.applyAsLong(initialId);
            this.receiver = router.supplyReceiver(initialId);

            correlations.put(replyId, this::onDescribeFanoutMessage);
            router.setThrottle(initialId, this::onDescribeFanoutMessage);
            doBegin(receiver, routeId, initialId, traceId, authorization, 0L,
                ex -> ex.set((b, o, l) -> kafkaBeginExRW.wrap(b, o, l)
                        .typeId(kafkaTypeId)
                        .describe(d -> d.topic(topic).configs(cs -> configNames.forEach(c -> cs.item(i -> i.set(c, UTF_8)))))
                        .build()
                        .sizeof()));
            state = KafkaState.openingInitial(state);
        }

        private void doDescribeFanoutInitialEndIfNecessary(
            long traceId)
        {
            if (!KafkaState.initialClosed(state))
            {
                doDescribeFanoutInitialEnd(traceId);
            }
        }

        private void doDescribeFanoutInitialEnd(
            long traceId)
        {
            doEnd(receiver, routeId, initialId, traceId, authorization, EMPTY_EXTENSION);

            state = KafkaState.closedInitial(state);
        }

        private void onDescribeFanoutInitialReset(
            ResetFW reset)
        {
            final long traceId = reset.traceId();

            state = KafkaState.closedInitial(state);

            doDescribeFanoutReplyResetIfNecessary(traceId);

            if (reconnect)
            {
                System.out.format("TODO: progressive back-off, DESCRIBE\n");
                doDescribeFanoutInitialBeginIfNecessary(traceId);
            }
            else
            {
                members.forEach(s -> s.doDescribeInitialResetIfNecessary(traceId));
            }
        }

        private void onDescribeFanoutInitialWindow(
            WindowFW window)
        {
            if (!KafkaState.initialOpened(state))
            {
                final long traceId = window.traceId();

                state = KafkaState.openedInitial(state);

                members.forEach(s -> s.doDescribeInitialWindowIfNecessary(traceId, 0L, 0, 0));
            }
        }

        private void onDescribeFanoutMessage(
            int msgTypeId,
            DirectBuffer buffer,
            int index,
            int length)
        {
            switch (msgTypeId)
            {
            case BeginFW.TYPE_ID:
                final BeginFW begin = beginRO.wrap(buffer, index, index + length);
                onDescribeFanoutReplyBegin(begin);
                break;
            case DataFW.TYPE_ID:
                final DataFW data = dataRO.wrap(buffer, index, index + length);
                onDescribeFanoutReplyData(data);
                break;
            case EndFW.TYPE_ID:
                final EndFW end = endRO.wrap(buffer, index, index + length);
                onDescribeFanoutReplyEnd(end);
                break;
            case AbortFW.TYPE_ID:
                final AbortFW abort = abortRO.wrap(buffer, index, index + length);
                onDescribeFanoutReplyAbort(abort);
                break;
            case ResetFW.TYPE_ID:
                final ResetFW reset = resetRO.wrap(buffer, index, index + length);
                onDescribeFanoutInitialReset(reset);
                break;
            case WindowFW.TYPE_ID:
                final WindowFW window = windowRO.wrap(buffer, index, index + length);
                onDescribeFanoutInitialWindow(window);
                break;
            default:
                break;
            }
        }

        private void onDescribeFanoutReplyBegin(
            BeginFW begin)
        {
            final long traceId = begin.traceId();

            state = KafkaState.openedReply(state);

            members.forEach(s -> s.doDescribeReplyBeginIfNecessary(traceId));

            doDescribeFanoutReplyWindow(traceId, bufferPool.slotCapacity());
        }

        private void onDescribeFanoutReplyData(
            DataFW data)
        {
            final long traceId = data.traceId();
            final int reserved = data.reserved();
            final OctetsFW extension = data.extension();
            final ExtensionFW dataEx = extensionRO.tryWrap(extension.buffer(), extension.offset(), extension.limit());
            final KafkaDataExFW kafkaDataEx = dataEx.typeId() == kafkaTypeId ? extension.get(kafkaDataExRO::tryWrap) : null;
            assert kafkaDataEx == null || kafkaDataEx.kind() == KafkaBeginExFW.KIND_DESCRIBE;
            final KafkaDescribeDataExFW kafkaDescribeDataEx = kafkaDataEx != null ? kafkaDataEx.describe() : null;

            if (kafkaDescribeDataEx != null)
            {
                final ArrayFW<KafkaConfigFW> changedConfigs = kafkaDescribeDataEx.configs();
                if (configValues == null)
                {
                    configValues = new TreeMap<>();
                }

                configValues.clear();
                changedConfigs.forEach(this::onDescribeFanoutConfigChanged);
                members.forEach(s -> s.doDescribeReplyDataIfNecessary(traceId, kafkaDataEx));
            }

            doDescribeFanoutReplyWindow(traceId, reserved);
        }

        private void onDescribeFanoutConfigChanged(
            KafkaConfigFW config)
        {
            final String16FW configName = config.name();
            final String16FW configValue = config.value();

            topicConfig.onTopicConfigChanged(configName, configValue);

            configValues.put(configName.asString(), configValue.asString());
        }

        private void onDescribeFanoutReplyEnd(
            EndFW end)
        {
            final long traceId = end.traceId();

            members.forEach(s -> s.doDescribeReplyEndIfNecessary(traceId));

            state = KafkaState.closedReply(state);
        }

        private void onDescribeFanoutReplyAbort(
            AbortFW abort)
        {
            final long traceId = abort.traceId();

            members.forEach(s -> s.doDescribeReplyAbortIfNecessary(traceId));

            state = KafkaState.closedReply(state);
        }

        private void doDescribeFanoutReplyResetIfNecessary(
            long traceId)
        {
            if (!KafkaState.replyClosed(state))
            {
                doDescribeFanoutReplyReset(traceId);
            }
        }

        private void doDescribeFanoutReplyReset(
            long traceId)
        {
            doReset(receiver, routeId, replyId, traceId, authorization);

            state = KafkaState.closedReply(state);
        }

        private void doDescribeFanoutReplyWindow(
            long traceId,
            int credit)
        {
            doWindow(receiver, routeId, replyId, traceId, authorization, 0L, credit, 0);

            state = KafkaState.openedReply(state);
        }
    }

    private final class KafkaCacheServerDescribeStream
    {
        private final KafkaCacheServerDescribeFanout group;
        private final MessageConsumer sender;
        private final long routeId;
        private final long initialId;
        private final long replyId;
        private final long affinity;
        private final long authorization;

        private int state;

        private long replyBudgetId;
        private int replyBudget;
        private int replyPadding;

        KafkaCacheServerDescribeStream(
            KafkaCacheServerDescribeFanout group,
            MessageConsumer sender,
            long routeId,
            long initialId,
            long affinity,
            long authorization)
        {
            this.group = group;
            this.sender = sender;
            this.routeId = routeId;
            this.initialId = initialId;
            this.replyId = supplyReplyId.applyAsLong(initialId);
            this.affinity = affinity;
            this.authorization = authorization;
        }

        private void onDescribeMessage(
            int msgTypeId,
            DirectBuffer buffer,
            int index,
            int length)
        {
            switch (msgTypeId)
            {
            case BeginFW.TYPE_ID:
                final BeginFW begin = beginRO.wrap(buffer, index, index + length);
                onDescribeInitialBegin(begin);
                break;
            case EndFW.TYPE_ID:
                final EndFW end = endRO.wrap(buffer, index, index + length);
                onDescribeInitialEnd(end);
                break;
            case AbortFW.TYPE_ID:
                final AbortFW abort = abortRO.wrap(buffer, index, index + length);
                onDescribeInitialAbort(abort);
                break;
            case WindowFW.TYPE_ID:
                final WindowFW window = windowRO.wrap(buffer, index, index + length);
                onDescribeReplyWindow(window);
                break;
            case ResetFW.TYPE_ID:
                final ResetFW reset = resetRO.wrap(buffer, index, index + length);
                onDescribeReplyReset(reset);
                break;
            default:
                break;
            }
        }

        private void onDescribeInitialBegin(
            BeginFW begin)
        {
            final long traceId = begin.traceId();

            state = KafkaState.openingInitial(state);

            group.onDescribeFanoutMemberOpening(traceId, this);
        }

        private void onDescribeInitialEnd(
            EndFW end)
        {
            final long traceId = end.traceId();

            state = KafkaState.closedInitial(state);

            group.onDescribeFanoutMemberClosed(traceId, this);

            doDescribeReplyEndIfNecessary(traceId);
        }

        private void onDescribeInitialAbort(
            AbortFW abort)
        {
            final long traceId = abort.traceId();

            state = KafkaState.closedInitial(state);

            group.onDescribeFanoutMemberClosed(traceId, this);

            doDescribeReplyAbortIfNecessary(traceId);
        }

        private void doDescribeInitialResetIfNecessary(
            long traceId)
        {
            if (KafkaState.initialOpening(state) && !KafkaState.initialClosed(state))
            {
                doDescribeInitialReset(traceId);
            }
        }

        private void doDescribeInitialReset(
            long traceId)
        {
            state = KafkaState.closedInitial(state);

            doReset(sender, routeId, initialId, traceId, authorization);
        }

        private void doDescribeInitialWindowIfNecessary(
            long traceId,
            long budgetId,
            int credit,
            int padding)
        {
            if (!KafkaState.initialOpened(state) || credit > 0)
            {
                doDescribeInitialWindow(traceId, budgetId, credit, padding);
            }
        }

        private void doDescribeInitialWindow(
            long traceId,
            long budgetId,
            int credit,
            int padding)
        {
            doWindow(sender, routeId, initialId, traceId, authorization,
                    budgetId, credit, padding);

            state = KafkaState.openedInitial(state);
        }

        private void doDescribeReplyBeginIfNecessary(
            long traceId)
        {
            if (!KafkaState.replyOpening(state))
            {
                doDescribeReplyBegin(traceId);
            }
        }

        private void doDescribeReplyBegin(
            long traceId)
        {
            state = KafkaState.openingReply(state);

            router.setThrottle(replyId, this::onDescribeMessage);
            doBegin(sender, routeId, replyId, traceId, authorization, affinity,
                ex -> ex.set((b, o, l) -> kafkaBeginExRW.wrap(b, o, l)
                        .typeId(kafkaTypeId)
                        .describe(m -> m.topic(group.topic)
                                        .configs(cs -> group.configNames.forEach(c -> cs.item(i -> i.set(c, UTF_8)))))
                        .build()
                        .sizeof()));
        }

        private void doDescribeReplyDataIfNecessary(
            long traceId,
            KafkaDataExFW extension)
        {
            if (KafkaState.replyOpened(state))
            {
                doDescribeReplyData(traceId, extension);
            }
        }

        private void doDescribeReplyData(
            long traceId,
            KafkaDataExFW extension)
        {
            final int reserved = replyPadding;

            replyBudget -= reserved;

            assert replyBudget >= 0;

            doDataNull(sender, routeId, replyId, traceId, authorization, replyBudgetId, reserved, extension);
        }

        private void doDescribeReplyEndIfNecessary(
            long traceId)
        {
            if (KafkaState.replyOpening(state) && !KafkaState.replyClosed(state))
            {
                doDescribeReplyEnd(traceId);
            }
        }

        private void doDescribeReplyEnd(
            long traceId)
        {
            state = KafkaState.closedReply(state);
            doEnd(sender, routeId, replyId, traceId, authorization, EMPTY_EXTENSION);
        }

        private void doDescribeReplyAbortIfNecessary(
            long traceId)
        {
            if (KafkaState.replyOpening(state) && !KafkaState.replyClosed(state))
            {
                doDescribeReplyAbort(traceId);
            }
        }

        private void doDescribeReplyAbort(
            long traceId)
        {
            state = KafkaState.closedReply(state);
            doAbort(sender, routeId, replyId, traceId, authorization, EMPTY_EXTENSION);
        }

        private void onDescribeReplyReset(
            ResetFW reset)
        {
            final long traceId = reset.traceId();

            state = KafkaState.closedInitial(state);

            group.onDescribeFanoutMemberClosed(traceId, this);

            doDescribeInitialResetIfNecessary(traceId);
        }

        private void onDescribeReplyWindow(
            WindowFW window)
        {
            final long budgetId = window.budgetId();
            final int credit = window.credit();
            final int padding = window.padding();

            replyBudgetId = budgetId;
            replyBudget += credit;
            replyPadding = padding;

            if (!KafkaState.replyOpened(state))
            {
                state = KafkaState.openedReply(state);

                final long traceId = window.traceId();
                group.onDescribeFanoutMemberOpened(traceId, this);
            }
        }
    }
}
