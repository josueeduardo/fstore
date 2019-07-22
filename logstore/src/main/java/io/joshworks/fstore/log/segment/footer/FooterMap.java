package io.joshworks.fstore.log.segment.footer;

import io.joshworks.fstore.core.Serializer;
import io.joshworks.fstore.core.hash.Hash;
import io.joshworks.fstore.core.hash.XXHash;
import io.joshworks.fstore.log.Direction;
import io.joshworks.fstore.log.record.DataStream;
import io.joshworks.fstore.log.record.RecordEntry;
import io.joshworks.fstore.log.segment.header.LogHeader;
import io.joshworks.fstore.serializer.Serializers;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class FooterMap {

    private final Serializer<Map<Integer, Long>> mapSerializer = Serializers.mapSerializer(Serializers.INTEGER, Serializers.LONG);
    private final Map<Integer, Long> items = new ConcurrentHashMap<>();
    private final Hash hasher = new XXHash();
    static final long NONE = -1;

    private int validateName(String name) {
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("Footer name must be blank");
        }
        int hash = hash(name);
        if (items.containsKey(hash)) {
            throw new IllegalArgumentException("Footer already contains entry with name: " + name);
        }
        return hash;
    }

    private int hash(String name) {
        return hasher.hash32(name.getBytes(StandardCharsets.UTF_8));
    }

    public long writeTo(DataStream stream) {
        return stream.write(items, mapSerializer);
    }

    public void load(LogHeader header, DataStream stream) {
        if (!header.readOnly() || header.footerSize() == 0) {
            return;
        }

        long mapPosition = header.footerMapPosition();
        RecordEntry<Map<Integer, Long>> record = stream.read(Direction.FORWARD, mapPosition, mapSerializer);
        if (record.isEmpty()) {
            throw new IllegalStateException("Could not load footer map: Empty footer map");
        }
        items.putAll(record.entry());
    }

    <T> int write(String name, DataStream stream, T entry, Serializer<T> serializer) {
        int hash = validateName(name);
        long position = stream.write(entry, serializer);
        items.put(hash, position);
        return hash;
    }

    long get(String name) {
        int hash = hash(name);
        return get(hash);
    }

    long get(int hash) {
        return items.getOrDefault(hash, NONE);
    }
}
