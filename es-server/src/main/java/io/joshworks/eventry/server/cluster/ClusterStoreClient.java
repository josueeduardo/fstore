package io.joshworks.eventry.server.cluster;

import io.joshworks.eventry.LinkToPolicy;
import io.joshworks.eventry.SystemEventPolicy;
import io.joshworks.eventry.api.EventStoreIterator;
import io.joshworks.eventry.api.IEventStore;
import io.joshworks.eventry.network.ClusterNode;
import io.joshworks.eventry.network.client.ClusterClient;
import io.joshworks.eventry.server.cluster.messages.Append;
import io.joshworks.eventry.server.cluster.messages.AppendResult;
import io.joshworks.eventry.server.cluster.messages.CreateStream;
import io.joshworks.eventry.server.cluster.messages.EventBatch;
import io.joshworks.eventry.server.cluster.messages.EventData;
import io.joshworks.eventry.server.cluster.messages.FromAll;
import io.joshworks.eventry.server.cluster.messages.FromStream;
import io.joshworks.eventry.server.cluster.messages.Get;
import io.joshworks.eventry.server.cluster.messages.IteratorClose;
import io.joshworks.eventry.server.cluster.messages.IteratorCreated;
import io.joshworks.eventry.server.cluster.messages.IteratorNext;
import io.joshworks.eventry.stream.StreamInfo;
import io.joshworks.eventry.stream.StreamMetadata;
import io.joshworks.fstore.es.shared.EventId;
import io.joshworks.fstore.es.shared.EventMap;
import io.joshworks.fstore.es.shared.EventRecord;
import org.jgroups.Address;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Queue;
import java.util.Set;

import static io.joshworks.eventry.server.cluster.RemoteIterators.DEFAULT_BATCH_SIZE;
import static io.joshworks.eventry.server.cluster.RemoteIterators.DEFAULT_TIMEOUT;
import static io.joshworks.eventry.stream.StreamMetadata.NO_MAX_AGE;
import static io.joshworks.eventry.stream.StreamMetadata.NO_MAX_COUNT;
import static io.joshworks.fstore.es.shared.EventId.NO_EXPECTED_VERSION;

//CLIENT
public class ClusterStoreClient implements IEventStore {

    private final ClusterClient client;
    private final String partitionId;
    private final ClusterNode node;

    public ClusterStoreClient(ClusterNode node, String partitionId, ClusterClient client) {
        this.client = client;
        this.node = node;
        this.partitionId = partitionId;
    }

    @Override
    public void compact() {
        throw new UnsupportedOperationException("TODO");
    }

    @Override
    public void close() {
        throw new UnsupportedOperationException("TODO");
    }

    @Override
    public EventRecord linkTo(String stream, EventRecord event) {
        throw new UnsupportedOperationException("TODO");
    }

    @Override
    public EventRecord linkTo(String dstStream, EventId source, String sourceType) {
        throw new UnsupportedOperationException("TODO");
    }

    @Override
    public EventRecord append(EventRecord event) {
        return append(event, NO_EXPECTED_VERSION);
    }

    @Override
    public EventRecord append(EventRecord event, int expectedVersion) {
        Append append = new Append(event, expectedVersion);
        AppendResult response = client.send(node.address, append);
        return new EventRecord(event.stream, event.type, response.version, response.timestamp, event.data, event.metadata);
    }

    @Override
    public EventStoreIterator fromStream(EventId stream) {
        IteratorCreated it = client.send(node.address, new FromStream(stream.toString(), DEFAULT_TIMEOUT, DEFAULT_BATCH_SIZE));
        return new RemoteStoreClientIterator(client, node.address, it.iteratorId);
    }

    @Override
    public EventStoreIterator fromStreams(EventMap checkpoint, Set<String> streamPatterns) {
        throw new UnsupportedOperationException("TODO");
    }

    @Override
    public EventStoreIterator fromStreams(EventMap streams) {
        throw new UnsupportedOperationException("TODO");
    }

    @Override
    public EventStoreIterator fromAll(LinkToPolicy linkToPolicy, SystemEventPolicy systemEventPolicy) {
        return fromAll(linkToPolicy, systemEventPolicy, null);
    }

    @Override
    public EventStoreIterator fromAll(LinkToPolicy linkToPolicy, SystemEventPolicy systemEventPolicy, EventId lastEvent) {
        FromAll fromAll = new FromAll(DEFAULT_TIMEOUT, DEFAULT_BATCH_SIZE, partitionId, linkToPolicy, systemEventPolicy, lastEvent);
        IteratorCreated it = client.send(node.address, fromAll);
        return new RemoteStoreClientIterator(client, node.address, it.iteratorId);
    }

    @Override
    public StreamMetadata createStream(String name) {
        return createStream(name, NO_MAX_COUNT, NO_MAX_AGE, new HashMap<>(), new HashMap<>());
    }

    @Override
    public StreamMetadata createStream(String stream, int maxCount, long maxAge, Map<String, Integer> acl, Map<String, String> metadata) {
        StreamMetadata created = client.send(node.address, new CreateStream(stream, maxCount, maxAge, acl, metadata));
        return created;
    }

    @Override
    public List<StreamInfo> streamsMetadata() {
        throw new UnsupportedOperationException("TODO");
    }

    @Override
    public Set<Long> streams() {
        throw new UnsupportedOperationException("TODO");
    }

    @Override
    public Optional<StreamInfo> streamMetadata(String stream) {
        throw new UnsupportedOperationException("TODO");
    }

    @Override
    public void truncate(String stream, int version) {
        throw new UnsupportedOperationException("TODO");
    }

    @Override
    public EventRecord get(EventId eventId) {
        EventData eventData = client.send(node.address, new Get(eventId));
        return eventData.record;
    }

    @Override
    public int version(String stream) {
        throw new UnsupportedOperationException("TODO");
    }

    @Override
    public int count(String stream) {
        throw new UnsupportedOperationException("TODO");
    }

    private static class RemoteStoreClientIterator implements EventStoreIterator {

        private final ClusterClient client;
        private final Address address;
        private final String iteratorId;

        private final Queue<EventRecord> cached = new ArrayDeque<>();

        private RemoteStoreClientIterator(ClusterClient client, Address address, String iteratorId) {
            this.client = client;
            this.iteratorId = iteratorId;
            this.address = address;
        }

        @Override
        public void close() {
            client.send(address, new IteratorClose(iteratorId));
        }

        @Override
        public boolean hasNext() {
            if (!cached.isEmpty()) {
                return true;
            }
            fetch();
            return !cached.isEmpty();
        }

        @Override
        public EventRecord next() {
            if (!hasNext()) {
                throw new NoSuchElementException("No remote element found");
            }
            return cached.poll();
        }

        private void fetch() {
            EventBatch events = client.send(address, new IteratorNext(iteratorId));
            cached.addAll(events.records);
        }

        @Override
        public EventMap checkpoint() {
            //TODO
            throw new UnsupportedOperationException("TODO");
        }
    }

}
