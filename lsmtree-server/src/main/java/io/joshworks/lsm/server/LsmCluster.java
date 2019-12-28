package io.joshworks.lsm.server;

import io.joshworks.fstore.core.Serializer;
import io.joshworks.lsm.server.messages.Delete;
import io.joshworks.lsm.server.messages.Get;
import io.joshworks.lsm.server.messages.Put;
import io.joshworks.lsm.server.messages.Replicate;
import io.joshworks.lsm.server.messages.Result;
import io.joshworks.lsm.server.partition.Partitioner;

import java.io.Closeable;
import java.io.File;
import java.util.List;

public class LsmCluster<K extends Comparable<K>> implements Closeable {

    private final LocalNode<K> local;
    private final Node node;
    private final List<StoreNode> nodes;
    private final Partitioner partitioner;
    private final int replicationFactor = 3;

    public LsmCluster(File rootDir, Serializer<K> serializer, List<StoreNode> nodes, Partitioner partitioner, Node node) {
        this.nodes = nodes;
        this.node = node;
        this.local = new LocalNode<>(rootDir, serializer, node);
        this.nodes.add(local);
        this.partitioner = partitioner;
    }

    private boolean isLocal(StoreNode node) {
        return node.id().equals(local.id());
    }

    private StoreNode select(byte[] key) {
        return partitioner.select(nodes, key);
    }

    public void put(Put msg) {
        StoreNode node = select(msg.key);
        node.put(msg);
        if (isLocal(node)) { //Partition owner is responsible for coordinating the replication
            replicate(msg);
        }
    }

    public Result get(Get msg) {
        StoreNode node = select(msg.key);
        return node.get(msg);
    }

    public void delete(Delete msg) {
        StoreNode node = select(msg.key);
        node.delete(msg);
        if (isLocal(node)) { //Partition owner is responsible for coordinating the replication
            replicate(msg);
        }
    }

    private void replicate(Object msg) {
        for (StoreNode node : nodes) {
            if (!isLocal(node)) { //TODO change logic
                node.replicate(new Replicate(this.node.id, msg));
            }
        }
    }

    @Override
    public void close() {
        local.close();
    }
}
