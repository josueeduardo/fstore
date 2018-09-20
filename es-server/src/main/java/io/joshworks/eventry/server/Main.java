package io.joshworks.eventry.server;

import io.joshworks.eventry.EventStore;
import io.joshworks.eventry.LocalStore;
import io.joshworks.eventry.server.cluster.ClusterManager;
import io.joshworks.eventry.server.cluster.ClusterWebService;

import java.io.File;

import static io.joshworks.snappy.SnappyServer.cors;
import static io.joshworks.snappy.SnappyServer.delete;
import static io.joshworks.snappy.SnappyServer.get;
import static io.joshworks.snappy.SnappyServer.group;
import static io.joshworks.snappy.SnappyServer.onShutdown;
import static io.joshworks.snappy.SnappyServer.post;
import static io.joshworks.snappy.SnappyServer.sse;
import static io.joshworks.snappy.SnappyServer.start;
import static io.joshworks.snappy.SnappyServer.websocket;
import static io.joshworks.snappy.parser.MediaTypes.consumes;

public class Main {


    public static void main(String[] args) {

        EventStore store = LocalStore.open(new File("J:\\event-store-app"));

        EventBroadcaster broadcast = new EventBroadcaster(2000, 3);
        SubscriptionEndpoint subscriptions = new SubscriptionEndpoint(store, broadcast);
        StreamEndpoint streams = new StreamEndpoint(store);
        ProjectionsEndpoint projections = new ProjectionsEndpoint(store);


        ClusterManager clusterManager = new ClusterManager();
        ClusterWebService clusterService = new ClusterWebService(clusterManager);


        group("/streams", () -> {
            post("/", streams::create);
            get("/", streams::streamsQuery);
            get("/metadata", streams::listStreams);

            group("{streamId}", () -> {
                get(streams::fetchStreams);
                post(streams::append);
                delete(streams::delete);
                get("/metadata", streams::metadata);
            });
        });

        group("/projections", () -> {
            get(projections::getAll);
            group("{name}", () -> {
                post(projections::create, consumes("application/javascript"));
                post("/run", projections::run);
                get(projections::get);
            });
        });

        group("/push", () -> sse(subscriptions.newPushHandler()));


        websocket("/cluster", clusterService);


        onShutdown(store::close);

        cors();
        start();

    }
}
