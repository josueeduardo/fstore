package io.joshworks.fstore.codec.std;

import io.joshworks.fstore.core.Codec;

import java.nio.ByteBuffer;
import java.util.zip.Deflater;
import java.util.zip.Inflater;

public class DeflaterCodec implements Codec {

    private final ThreadLocal<Deflater> deflater;
    private final ThreadLocal<Inflater> inflater;

    public DeflaterCodec() {
        this(Deflater.DEFAULT_COMPRESSION, true);
    }

    public DeflaterCodec(int level, boolean nowrap) {
        this.deflater = ThreadLocal.withInitial(() -> new Deflater(level, nowrap));
        this.inflater = ThreadLocal.withInitial(() -> new Inflater(nowrap));
    }

    @Override
    public void compress(ByteBuffer src, ByteBuffer dst) {
        try {
            Deflater instance = deflater.get();
            instance.setInput(src);
            instance.finish();
            instance.deflate(dst);
            instance.reset();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void decompress(ByteBuffer src, ByteBuffer dst) {
        try {
            Inflater instance = inflater.get();
            instance.setInput(src);
            instance.inflate(dst);
            instance.reset();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
