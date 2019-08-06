package io.joshworks.eventry.api;

import io.joshworks.fstore.es.shared.EventId;
import io.joshworks.eventry.log.EventRecord;

public interface IStreamQuery {

    EventRecord get(EventId stream);

    int version(String stream);

    int count(String stream);

}
