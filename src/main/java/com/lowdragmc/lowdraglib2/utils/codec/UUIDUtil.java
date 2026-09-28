package com.lowdragmc.lowdraglib2.utils.codec;

import io.netty.buffer.ByteBuf;

import java.util.UUID;

public class UUIDUtil {
    public static final StreamCodec<ByteBuf, UUID> STREAM_CODEC = new StreamCodec<ByteBuf, UUID>() {
        public UUID decode(ByteBuf p_320929_) {
            return readUUID(p_320929_);
        }

        public void encode(ByteBuf p_320610_, UUID p_320851_) {
            writeUUID(p_320610_, p_320851_);
        }
    };

    public static UUID readUUID(ByteBuf buffer) {
        return new UUID(buffer.readLong(), buffer.readLong());
    }

    public static void writeUUID(ByteBuf buffer, UUID id) {
        buffer.writeLong(id.getMostSignificantBits());
        buffer.writeLong(id.getLeastSignificantBits());
    }
}
