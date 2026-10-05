package be.wwx.hibernate.listener;

import be.wwx.hibernate.cache.ChunkIndex;
import be.wwx.hibernate.data.model.ChunkKey;
import org.bukkit.Chunk;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.world.ChunkLoadEvent;
import org.bukkit.event.world.ChunkUnloadEvent;
import org.bukkit.event.world.WorldUnloadEvent;

/** Event-owned chunk access; only immutable coordinates are retained. */
public final class ChunkListener implements Listener {
    private final ChunkIndex chunks;

    public ChunkListener(ChunkIndex chunks) {
        this.chunks = chunks;
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onLoad(ChunkLoadEvent event) {
        Chunk chunk = event.getChunk();
        chunks.add(new ChunkKey(event.getWorld().getUID(), chunk.getX(), chunk.getZ()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onUnload(ChunkUnloadEvent event) {
        Chunk chunk = event.getChunk();
        chunks.remove(new ChunkKey(event.getWorld().getUID(), chunk.getX(), chunk.getZ()));
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onWorldUnload(WorldUnloadEvent event) {
        chunks.removeWorld(event.getWorld().getUID());
    }
}
