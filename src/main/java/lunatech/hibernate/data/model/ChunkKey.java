package lunatech.hibernate.data.model;

import java.util.UUID;

/** Immutable identity; x/z are already chunk coordinates, including negative values. */
public record ChunkKey(UUID worldId, int x, int z) { }
