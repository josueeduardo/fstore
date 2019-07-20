package io.joshworks.fstore.serializer.collection;

import io.joshworks.fstore.core.Serializer;

import java.nio.ByteBuffer;
import java.util.Collection;
import java.util.function.Function;
import java.util.function.Supplier;

public class CollectionSerializer<V, T extends Collection<V>> implements Serializer<T> {

    private final Serializer<V> valueSerializer;
    private final Supplier<T> instanceSupplier;
    private final Function<V, Integer> sizeOfValue;

    public CollectionSerializer(Serializer<V> valueSerializer, Function<V, Integer> sizeOfValue, Supplier<T> instanceSupplier) {
        this.valueSerializer = valueSerializer;
        this.sizeOfValue = sizeOfValue;
        this.instanceSupplier = instanceSupplier;
    }

    //practical but not very fast
    @Override
    public ByteBuffer toBytes(T data) {
        int totalSize = sizeOf(data);
        ByteBuffer bb = ByteBuffer.allocate(totalSize);
        writeTo(data, bb);
        return bb.flip();
    }

    @Override
    public void writeTo(T data, ByteBuffer dst) {
        dst.putInt(data.size());
        for (V entry : data) {
            dst.put(valueSerializer.toBytes(entry));
        }
    }

    @Override
    public T fromBytes(ByteBuffer buffer) {
        int size = buffer.getInt();

        T list = instanceSupplier.get();

        for (int i = 0; i < size; i++) {
            V value = valueSerializer.fromBytes(buffer);
            list.add(value);
        }
        return list;
    }

    public int sizeOf(Collection<V> collection) {
        return Integer.BYTES + sizeOfCollection(collection);
    }

    private int sizeOfCollection(Collection<V> collection) {
        int size = 0;
        for (V entry : collection) {
            size += sizeOfValue.apply(entry);
        }
        return size;
    }
}
