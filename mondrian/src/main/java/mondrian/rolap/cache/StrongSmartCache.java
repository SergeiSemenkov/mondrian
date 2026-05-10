package mondrian.rolap.cache;

import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Map.Entry;

/**
 * Cache implementation with strong keys and strong values.
 *
 * Optional LRU bound:
 * - maxEntries <= 0 : unbounded
 * - maxEntries  > 0 : evict eldest entry when size exceeds maxEntries
 *
 * Thread-safety is provided by SmartCacheImpl's read/write lock.
 */
public class StrongSmartCache<K, V> extends SmartCacheImpl<K, V> {
    private final int maxEntries;
    private final LinkedHashMap<K, V> cache;

    public StrongSmartCache() {
        this(0);
    }

    public StrongSmartCache(final int maxEntries) {
        this.maxEntries = maxEntries;
        this.cache = new LinkedHashMap<K, V>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Entry<K, V> eldest) {
                return StrongSmartCache.this.maxEntries > 0
                        && size() > StrongSmartCache.this.maxEntries;
            }
        };
    }

    @Override
    protected Iterator<Map.Entry<K, V>> iteratorImpl() {
        return cache.entrySet().iterator();
    }

    @Override
    protected V putImpl(K key, V value) {
        return cache.put(key, value);
    }

    @Override
    protected V getImpl(K key) {
        return cache.get(key);
    }

    @Override
    protected V removeImpl(K key) {
        return cache.remove(key);
    }

    @Override
    protected void clearImpl() {
        cache.clear();
    }

    @Override
    protected int sizeImpl() {
        return cache.size();
    }
}