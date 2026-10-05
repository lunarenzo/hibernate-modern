package lunatech.hibernate.cache.impl;

import lunatech.hibernate.cache.ChunkIndex;
import lunatech.hibernate.data.model.ChunkKey;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/** Short synchronized metadata operations; never executes server calls under its lock. */
public final class BoundedChunkIndex implements ChunkIndex {
    private final Map<ChunkKey, Node> keys = new HashMap<>();
    private Node first;
    private Node last;
    private int capacity;
    private boolean closed;

    public BoundedChunkIndex(int capacity) {
        limit(capacity);
    }

    @Override
    public synchronized void add(ChunkKey key) {
        if (closed || keys.containsKey(key)) return;
        if (keys.size() == capacity) remove(first.key);
        Node node = new Node(key);
        keys.put(key, node);
        append(node);
    }

    @Override
    public synchronized void remove(ChunkKey key) {
        Node node = keys.remove(key);
        if (node != null) unlink(node);
    }

    @Override
    public synchronized void removeWorld(UUID worldId) {
        Node current = first;
        while (current != null) {
            Node next = current.next;
            if (current.key.worldId().equals(worldId)) remove(current.key);
            current = next;
        }
    }

    @Override
    public synchronized ChunkKey next() {
        if (closed || keys.isEmpty()) return null;
        Node node = first;
        if (node != last) {
            unlink(node);
            append(node);
        }
        return node.key;
    }

    @Override
    public synchronized void limit(int capacity) {
        if (capacity < 1) throw new IllegalArgumentException("Chunk index capacity must be positive");
        this.capacity = capacity;
        while (keys.size() > capacity) remove(first.key);
    }

    @Override
    public synchronized int size() {
        return keys.size();
    }

    @Override
    public synchronized void close() {
        closed = true;
        keys.clear();
        first = null;
        last = null;
    }

    private void append(Node node) {
        node.previous = last;
        node.next = null;
        if (last == null) first = node;
        else last.next = node;
        last = node;
    }

    private void unlink(Node node) {
        if (node.previous == null) first = node.next;
        else node.previous.next = node.next;
        if (node.next == null) last = node.previous;
        else node.next.previous = node.previous;
        node.previous = null;
        node.next = null;
    }

    private static final class Node {
        private final ChunkKey key;
        private Node previous;
        private Node next;

        private Node(ChunkKey key) {
            this.key = key;
        }
    }
}
