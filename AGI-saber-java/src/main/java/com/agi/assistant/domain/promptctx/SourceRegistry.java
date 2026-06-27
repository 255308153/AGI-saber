package com.agi.assistant.domain.promptctx;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * 按 SlotKind 分组的 ContextSource 注册表（对应 Go promptctx.SourceRegistry）。
 */
public class SourceRegistry {
    private final Map<SlotKind, List<ContextSource>> sources = new EnumMap<>(SlotKind.class);
    private final ReadWriteLock lock = new ReentrantReadWriteLock();

    public void register(ContextSource source) {
        if (source == null) return;
        lock.writeLock().lock();
        try {
            for (SlotKind kind : SlotKind.values()) {
                if (source.supports(kind)) {
                    sources.computeIfAbsent(kind, k -> new ArrayList<>()).add(source);
                }
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    public List<ContextSource> forKind(SlotKind kind) {
        lock.readLock().lock();
        try {
            List<ContextSource> list = sources.get(kind);
            if (list == null) return List.of();
            return new ArrayList<>(list);
        } finally {
            lock.readLock().unlock();
        }
    }
}
