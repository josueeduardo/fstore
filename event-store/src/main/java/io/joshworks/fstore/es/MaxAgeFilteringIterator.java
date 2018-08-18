package io.joshworks.fstore.es;

import io.joshworks.fstore.es.log.Event;
import io.joshworks.fstore.es.stream.StreamMetadata;
import io.joshworks.fstore.log.LogIterator;

import java.io.IOException;
import java.util.Map;
import java.util.NoSuchElementException;

public class MaxAgeFilteringIterator implements LogIterator<Event> {

    private final LogIterator<Event> delegate;
    private Event next;
    private final long timestamp = System.currentTimeMillis();
    private Map<String, StreamMetadata> metadataMap;

    public MaxAgeFilteringIterator(Map<String, StreamMetadata> metadataMap, LogIterator<Event> delegate) {
        this.metadataMap = metadataMap;
        this.delegate = delegate;
        next = dropEvents();
    }

    @Override
    public boolean hasNext() {
        return next != null;
    }

    @Override
    public Event next() {
        if (next == null) {
            throw new NoSuchElementException();
        }
        Event temp = next;
        next = dropEvents();
        return temp;
    }

    private Event dropEvents() {
        Event last = nextEntry();
        while(last != null && !withinMaxAge(last)) {
            last = nextEntry();
        }
        return last != null && withinMaxAge(last) ? last : null;
    }

    private boolean withinMaxAge(Event event) {
        StreamMetadata metadata = metadataMap.get(event.stream());
        return metadata.maxAge <= 0 || ((timestamp - event.timestamp()) / 1000) <= metadata.maxAge;
    }

    private Event nextEntry() {
        return delegate.hasNext() ? delegate.next() : null;
    }

    @Override
    public long position() {
        return delegate.position();
    }

    @Override
    public void close() throws IOException {
        delegate.close();
    }
}