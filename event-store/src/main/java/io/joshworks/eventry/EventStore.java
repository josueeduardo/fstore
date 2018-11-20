package io.joshworks.eventry;

import io.joshworks.eventry.data.Constant;
import io.joshworks.eventry.data.IndexFlushed;
import io.joshworks.eventry.data.LinkTo;
import io.joshworks.eventry.data.ProjectionCreated;
import io.joshworks.eventry.data.ProjectionDeleted;
import io.joshworks.eventry.data.ProjectionStarted;
import io.joshworks.eventry.data.ProjectionUpdated;
import io.joshworks.eventry.data.StreamCreated;
import io.joshworks.eventry.data.StreamFormat;
import io.joshworks.eventry.data.SystemStreams;
import io.joshworks.eventry.index.IndexEntry;
import io.joshworks.eventry.index.Range;
import io.joshworks.eventry.index.TableIndex;
import io.joshworks.eventry.log.EventLog;
import io.joshworks.eventry.log.EventRecord;
import io.joshworks.eventry.log.EventSerializer;
import io.joshworks.eventry.log.IEventLog;
import io.joshworks.eventry.log.RecordCleanup;
import io.joshworks.eventry.projections.Projection;
import io.joshworks.eventry.projections.ProjectionExecutor;
import io.joshworks.eventry.projections.Projections;
import io.joshworks.eventry.projections.result.Metrics;
import io.joshworks.eventry.projections.result.TaskStatus;
import io.joshworks.eventry.stream.StreamInfo;
import io.joshworks.eventry.stream.StreamMetadata;
import io.joshworks.eventry.stream.Streams;
import io.joshworks.eventry.utils.StringUtils;
import io.joshworks.fstore.core.io.IOUtils;
import io.joshworks.fstore.core.io.buffers.SingleBufferThreadCachedPool;
import io.joshworks.fstore.core.util.Size;
import io.joshworks.fstore.log.Direction;
import io.joshworks.fstore.log.Iterators;
import io.joshworks.fstore.log.LogIterator;
import io.joshworks.fstore.log.appender.FlushMode;
import io.joshworks.fstore.log.appender.LogAppender;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

public class EventStore implements IEventStore {

    private static final Logger logger = LoggerFactory.getLogger("event-store");

    //TODO expose
    private static final int LRU_CACHE_SIZE = 1000000;

    private final TableIndex index;
    private final Streams streams;
    public final IEventLog eventLog;
    private final Projections projections;

    private EventStore(File rootDir) {
        this.index = new TableIndex(rootDir);
        this.projections = new Projections(new ProjectionExecutor(rootDir, this::appendSystemEvent));
        this.streams = new Streams(rootDir, LRU_CACHE_SIZE, index::version);
        this.eventLog = new EventLog(LogAppender.builder(rootDir, new EventSerializer())
                .segmentSize(Size.MB.of(512))
                .name("event-log")
                .flushMode(FlushMode.NEVER)
                .bufferPool(new SingleBufferThreadCachedPool(false))
                .checksumProbability(1)
                .disableCompaction()
                .compactionStrategy(new RecordCleanup(streams)));

        try {
            this.loadIndex();
//            this.loadStreams();
            this.loadProjections();
        } catch (Exception e) {
            IOUtils.closeQuietly(index);
            IOUtils.closeQuietly(projections);
            IOUtils.closeQuietly(streams);
            IOUtils.closeQuietly(eventLog);
            throw new RuntimeException(e);
        }
    }

    public static EventStore open(File rootDir) {
        return new EventStore(rootDir);
    }


    private void loadIndex() {
        logger.info("Loading index");
        long start = System.currentTimeMillis();
        int loaded = 0;
        long p = 0;

        Map<EventRecord, Long> backwardsIndex = new HashMap<>(500000);
        try (LogIterator<EventRecord> iterator = eventLog.iterator(Direction.BACKWARD)) {

            while (iterator.hasNext()) {
                EventRecord next = iterator.next();
                if (IndexFlushed.TYPE.equals(next.type)) {
                    break;
                }
                backwardsIndex.put(next, iterator.position());
                if (++loaded % 50000 == 0) {
                    logger.info("Loaded {} index entries", loaded);
                }
            }

            backwardsIndex.entrySet().stream().sorted(Comparator.comparingLong(Map.Entry::getValue)).forEach(e -> {
                EventRecord entry = e.getKey();
                long position = e.getValue();
                long streamHash = streams.hashOf(entry.stream);
                index.add(streamHash, entry.version, position);
            });

        } catch (Exception e) {
            throw new RuntimeException("Failed to load memIndex on position " + p, e);
        }

        logger.info("Loaded {} index entries in {}ms", loaded, (System.currentTimeMillis() - start));
    }

    private void loadProjections() {
        logger.info("Loading projections");
        long start = System.currentTimeMillis();

        long streamHash = streams.hashOf(SystemStreams.PROJECTIONS);
        LogIterator<IndexEntry> addresses = index.indexedIterator(streamHash);

        while (addresses.hasNext()) {
            IndexEntry next = addresses.next();
            EventRecord event = eventLog.get(next.position);

            //pattern matching would be great here
            if (ProjectionCreated.TYPE.equals(event.type)) {
                projections.add(ProjectionCreated.from(event));
            } else if (ProjectionUpdated.TYPE.equals(event.type)) {
                projections.add(ProjectionCreated.from(event));
            } else if (ProjectionDeleted.TYPE.equals(event.type)) {
                ProjectionDeleted deleted = ProjectionDeleted.from(event);
                projections.delete(deleted.name);
            }
        }

        int loaded = projections.all().size();
        logger.info("Loaded {} projections in {}ms", loaded, (System.currentTimeMillis() - start));
        if (loaded == 0) {
            logger.info("Creating system projections");
            for (Projection projection : this.projections.loadSystemProjections()) {
                createProjection(projection);
                projections.add(projection);
            }

        }

        projections.bootstrapProjections(this);

    }

    @Override
    public void cleanup() {
        eventLog.cleanup();
    }

    @Override
    public void compactIndex() {
        index.compact();
    }

    @Override
    public void createStream(String name) {
        createStream(name, -1, -1);
    }

    @Override
    public Collection<Projection> projections() {
        return new ArrayList<>(projections.all());
    }

    @Override
    public Projection projection(String name) {
        return projections.get(name);
    }

    @Override
    public synchronized Projection createProjection(String script) {
        Projection projection = projections.create(script);
        return createProjection(projection);
    }

    private synchronized Projection createProjection(Projection projection) {
        EventRecord eventRecord = ProjectionCreated.create(projection);
        this.appendSystemEvent(eventRecord);
        return projection;
    }

    @Override
    public synchronized Projection updateProjection(String name, String script) {
        Projection projection = projections.update(name, script);
        EventRecord eventRecord = ProjectionUpdated.create(projection);
        this.appendSystemEvent(eventRecord);

        return projection;
    }

    @Override
    public synchronized void deleteProjection(String name) {
        projections.delete(name);
        EventRecord eventRecord = ProjectionDeleted.create(name);
        this.appendSystemEvent(eventRecord);
    }

    @Override
    public synchronized void runProjection(String name) {
        projections.run(name, this);
        EventRecord eventRecord = ProjectionStarted.create(name);
        this.appendSystemEvent(eventRecord);
    }

    @Override
    public void resetProjection(String name) {
        projections.reset(name);
    }

    @Override
    public void stopProjectionExecution(String name) {
        projections.stop(name);
    }

    @Override
    public void disableProjection(String name) {
        Projection projection = projections.get(name);
        if(!projection.enabled) {
            return;
        }
        projection.enabled = false;
        EventRecord eventRecord = ProjectionUpdated.create(projection);
        this.appendSystemEvent(eventRecord);
    }

    @Override
    public void enableProjection(String name) {
        Projection projection = projections.get(name);
        if(projection.enabled) {
            return;
        }
        projection.enabled = true;
        EventRecord eventRecord = ProjectionUpdated.create(projection);
        this.appendSystemEvent(eventRecord);
    }

    @Override
    public Map<String, TaskStatus> projectionExecutionStatus(String name) {
        return projections.executionStatus(name);
    }

    @Override
    public Collection<Metrics> projectionExecutionStatuses() {
        return projections.executionStatuses();
    }

    @Override
    public void createStream(String name, int maxCount, long maxAge) {
        createStream(name, maxCount, maxAge, new HashMap<>(), new HashMap<>());
    }

    @Override
    public synchronized StreamMetadata createStream(String stream, int maxCount, long maxAge, Map<String, Integer> permissions, Map<String, String> metadata) {
        StreamMetadata created = streams.create(stream, maxAge, maxCount, permissions, metadata);
        if (created == null) {
            throw new IllegalStateException("Stream '" + stream + "' already exist");
        }
        EventRecord eventRecord = StreamCreated.create(created);
        this.appendSystemEvent(eventRecord);
        return created;
    }

    @Override
    public List<StreamInfo> streamsMetadata() {
        return streams.all().stream().map(meta -> {
            int version = streams.version(meta.hash);
            return StreamInfo.from(meta, version);
        }).collect(Collectors.toList());
    }

    @Override
    public Optional<StreamInfo> streamMetadata(String stream) {
        long streamHash = streams.hashOf(stream);
        return streams.get(streamHash).map(meta -> {
            int version = streams.version(meta.hash);
            return StreamInfo.from(meta, version);
        });
    }

    //TODO remove ?? use only for log dump
    @Override
    public LogIterator<IndexEntry> scanIndex() {
        return index.scanner();
    }

    @Override
    public EventLogIterator fromStream(String stream) {
        return fromStream(stream, Range.START_VERSION);
    }

    //TODO expose direction ?
    @Override
    public EventLogIterator fromStream(String stream, int versionInclusive) {
        long streamHash = streams.hashOf(stream);
        LogIterator<IndexEntry> indexIterator = index.indexedIterator(Map.of(streamHash, versionInclusive - 1));
        indexIterator = withMaxCountFilter(streamHash, indexIterator);
        SingleStreamIterator singleStreamIterator = new SingleStreamIterator(indexIterator, eventLog);
        EventLogIterator ageFilterIterator = withMaxAgeFilter(Set.of(streamHash), singleStreamIterator);
        return new LinkToResolveIterator(ageFilterIterator, this::resolve);

    }

    @Override
    public EventLogIterator zipStreams(String stream) {
        Set<String> eventStreams = streams.streamMatching(stream);
        if (eventStreams.isEmpty()) {
            return EventLogIterator.empty();
        }
        return zipStreams(eventStreams);
    }

    @Override
    public EventLogIterator zipStreams(Set<String> streamNames) {
        if (streamNames.size() == 1) {
            return fromStream(streamNames.iterator().next());
        }

        Set<Long> hashes = streamNames.stream()
                .filter(StringUtils::nonBlank)
                .map(streams::hashOf)
                .collect(Collectors.toSet());

        List<LogIterator<IndexEntry>> indexes = hashes.stream()
                .map(index::indexedIterator)
                .collect(Collectors.toList());

        EventLogIterator ageFilterIterator = withMaxAgeFilter(hashes, new MultiStreamIterator(indexes, eventLog));
        return new LinkToResolveIterator(ageFilterIterator, this::resolve);
    }

    @Override
    public int version(String stream) {
        long streamHash = streams.hashOf(stream);
        return streams.version(streamHash);
    }

    @Override
    public LogIterator<EventRecord> fromAll(LinkToPolicy linkToPolicy, SystemEventPolicy systemEventPolicy) {
        LogIterator<EventRecord> filtering = Iterators.filtering(eventLog.iterator(Direction.FORWARD), ev -> {
            if (ev == null) {
                return false;
            }
            if (LinkToPolicy.IGNORE.equals(linkToPolicy) && ev.isLinkToEvent()) {
                return false;
            }
            if (SystemEventPolicy.IGNORE.equals(systemEventPolicy) && ev.isSystemEvent()) {
                return false;
            }
            return true;
        });

        return Iterators.mapping(filtering, this::resolve);

    }

    @Override
    public synchronized EventRecord linkTo(String stream, EventRecord event) {
        if (event.isLinkToEvent()) {
            //resolve event
            event = get(event.stream, event.version);
        }
        EventRecord linkTo = LinkTo.create(stream, StreamFormat.of(event));
        return this.appendSystemEvent(linkTo);
    }

    @Override
    public synchronized EventRecord linkTo(String dstStream, String sourceStream, int sourceVersion, String sourceType) {
        if (LinkTo.TYPE.equals(sourceType)) {
            //resolve event
            EventRecord event = get(sourceStream, sourceVersion);
            EventRecord linkTo = LinkTo.create(dstStream, StreamFormat.of(event));
            return this.appendSystemEvent(linkTo);
        }
        EventRecord linkTo = LinkTo.create(dstStream, StreamFormat.of(sourceStream, sourceVersion));
        return this.appendSystemEvent(linkTo);

    }

    @Override
    public EventRecord get(String stream, int version) {
        long streamHash = streams.hashOf(stream);

        if (version <= IndexEntry.NO_VERSION) {
            throw new IllegalArgumentException("Version must be greater than " + IndexEntry.NO_VERSION);
        }
        Optional<IndexEntry> indexEntry = index.get(streamHash, version);
        if (!indexEntry.isPresent()) {
            //TODO improve this to a non exception response
            throw new RuntimeException("IndexEntry not found for " + StreamFormat.toString(stream, version));
        }

        return get(indexEntry.get());
    }

    @Override
    public EventRecord get(IndexEntry indexEntry) {
        Objects.requireNonNull(indexEntry, "IndexEntry mus not be null");

        EventRecord record = eventLog.get(indexEntry.position);
        return resolve(record);
    }

    @Override
    public EventRecord resolve(EventRecord record) {
        if (record.isLinkToEvent()) {
            StreamFormat streamFormat = StreamFormat.parse(record.dataAsString());
            var linkToStream = streamFormat.stream;
            var linkToVersion = streamFormat.version;
            return get(linkToStream, linkToVersion);
        }
        return record;
    }

    private void validateEvent(EventRecord event) {
        Objects.requireNonNull(event, "Event must be provided");
        StringUtils.requireNonBlank(event.stream, "closeableStream must be provided");
        StringUtils.requireNonBlank(event.type, "Type must be provided");
        if (event.stream.startsWith(Constant.SYSTEM_PREFIX)) {
            throw new IllegalArgumentException("Stream cannot start with " + Constant.SYSTEM_PREFIX);
        }
    }

    @Override
    public synchronized EventRecord append(EventRecord event) {
        return append(event, IndexEntry.NO_VERSION);
    }

    @Override
    public EventRecord append(EventRecord event, int expectedVersion) {
        validateEvent(event);

        StreamMetadata metadata = getOrCreateStream(event.stream);
        return append(metadata, event, expectedVersion);
    }

    private EventRecord appendSystemEvent(EventRecord event) {
        StreamMetadata metadata = getOrCreateStream(event.stream);
        return append(metadata, event, IndexEntry.NO_VERSION);
    }

    private EventRecord append(StreamMetadata streamMetadata, EventRecord event, int expectedVersion) {
        if (streamMetadata == null) {
            throw new IllegalArgumentException("EventStream cannot be null");
        }
        long streamHash = streams.hashOf(event.stream);
        if (streamMetadata.name.equals(event.stream) && streamMetadata.hash != streamHash) {
            //TODO improve ??
            throw new IllegalStateException("Hash collision of closeableStream: " + event.stream + " with existing name: " + streamMetadata.name);
        }

        int version = streams.tryIncrementVersion(streamHash, expectedVersion);

        var record = new EventRecord(event.stream, event.type, version, System.currentTimeMillis(), event.body, event.metadata);

        long position = eventLog.append(record);
        var flushInfo = index.add(streamHash, version, position);
        if (flushInfo != null) {
            var indexFlushedEvent = IndexFlushed.create(position, flushInfo.timeTaken, flushInfo.entries);
            this.appendSystemEvent(indexFlushedEvent);
        }

        return record;
    }

    private StreamMetadata getOrCreateStream(String stream) {
        return streams.createIfAbsent(stream, created -> {
            EventRecord eventRecord = StreamCreated.create(created);
            this.append(created, eventRecord, IndexEntry.NO_VERSION);
        });
    }

    private LogIterator<IndexEntry> withMaxCountFilter(long streamHash, LogIterator<IndexEntry> iterator) {
        return streams.get(streamHash)
                .map(stream -> stream.maxCount)
                .filter(maxCount -> maxCount > 0)
                .map(maxCount -> MaxCountFilteringIterator.of(maxCount, streams.version(streamHash), iterator))
                .orElse(iterator);
    }

    private EventLogIterator withMaxAgeFilter(Set<Long> streamHashes, EventLogIterator iterator) {
        Map<String, Long> metadataMap = streamHashes.stream()
                .map(streams::get)
                .filter(Optional::isPresent)
                .map(Optional::get)
                .collect(Collectors.toMap(StreamMetadata::name, meta -> meta.maxAge));

        return new MaxAgeFilteringIterator(metadataMap, iterator);
    }

    @Override
    public synchronized void close() {
        index.close();
        eventLog.close();
        streams.close();
        projections.close();
    }

}