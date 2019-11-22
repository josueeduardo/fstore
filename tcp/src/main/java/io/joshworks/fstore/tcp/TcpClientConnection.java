package io.joshworks.fstore.tcp;

import io.joshworks.fstore.core.io.buffers.BufferPool;
import io.joshworks.fstore.tcp.internal.Ping;
import io.joshworks.fstore.tcp.internal.Pong;
import io.joshworks.fstore.tcp.internal.Response;
import io.joshworks.fstore.tcp.internal.ResponseTable;
import org.xnio.StreamConnection;

import java.nio.ByteBuffer;

import static java.util.Objects.requireNonNull;

public class TcpClientConnection extends TcpConnection {

    private final ResponseTable responseTable;


    public TcpClientConnection(StreamConnection connection, BufferPool writePool, ResponseTable responseTable) {
        super(connection, writePool);
        this.responseTable = responseTable;
    }

    public long ping() {
        Response<Pong> response = request(new Ping());
        return response.get().timestamp;
    }

    public <T, R> Response<R> request(T data) {
        requireNonNull(data, "Entity must be provided");

        try (writePool) {
            ByteBuffer buffer = writePool.allocate();
            Response<R> response = responseTable.newRequest(data, buffer);
            buffer.flip();
            super.write(buffer, false);
            return response;
        }
    }

}
