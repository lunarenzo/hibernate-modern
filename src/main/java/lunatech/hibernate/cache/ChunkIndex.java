package lunatech.hibernate.cache;

import lunatech.hibernate.data.model.ChunkKey;
import java.util.UUID;

/** Layout-required cache boundary. Contains only immutable coordinate identities. */
public interface ChunkIndex {
    void add(ChunkKey key);
    void remove(ChunkKey key);
    void removeWorld(UUID worldId);
    ChunkKey next();
    void limit(int capacity);
    int size();
    void close();
}
