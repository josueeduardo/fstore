package io.joshworks.eventry;

import io.joshworks.eventry.index.Checkpoint;
import io.joshworks.eventry.index.IndexEntry;
import io.joshworks.eventry.index.IndexIterator;
import io.joshworks.eventry.stream.StreamMetadata;

import java.util.Set;

public class IndexListenerRemoval implements IndexIterator {

    private final Set<StreamListener> listeners;
    private final IndexIterator delegate;

    public IndexListenerRemoval(Set<StreamListener> listeners, IndexIterator delegate) {
        this.listeners = listeners;
        this.delegate = delegate;
        this.listeners.add(delegate);
    }

    @Override
    public void close() {
        listeners.remove(delegate);
        delegate.close();
    }

    @Override
    public Checkpoint checkpoint() {
        return delegate.checkpoint();
    }

    @Override
    public boolean hasNext() {
        return delegate.hasNext();
    }

    @Override
    public IndexEntry next() {
        return delegate.next();
    }

    @Override
    public void onStreamCreated(StreamMetadata metadata) {
        delegate.onStreamCreated(metadata);
    }

    @Override
    public void onStreamTruncated(StreamMetadata metadata) {
        delegate.onStreamTruncated(metadata);
    }

    @Override
    public void onStreamDeleted(StreamMetadata metadata) {
        delegate.onStreamDeleted(metadata);
    }
}
