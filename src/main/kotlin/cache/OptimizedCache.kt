package cache

import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * LRU (Least Recently Used) кэш с ограничением по размеру
 * Потокобезопасная реализация для многопоточного доступа
 */
class LruCache<K, V>(
    private val maxSize: Int
) {
    private val cache = LinkedHashMap<K, V>(maxSize, 0.75f, true)
    private val lock = ReentrantReadWriteLock()

    /**
     * Статистика кэша
     */
    data class CacheStats(
        val size: Int,
        val maxSize: Int,
        val hitCount: Long,
        val missCount: Long,
        val evictionCount: Long,
        val hitRate: Double
    )

    private var hitCount = 0L
    private var missCount = 0L
    private var evictionCount = 0L

    /**
     * Получение значения из кэша
     */
    fun get(key: K): V? {
        return lock.read {
            val value = cache[key]
            if (value != null) {
                hitCount++
            } else {
                missCount++
            }
            value
        }
    }

    /**
     * Сохранение значения в кэш
     */
    fun put(key: K, value: V): V? {
        return lock.write {
            // Проверяем нужно ли удалять старые записи
            while (cache.size >= maxSize && maxSize > 0) {
                val iterator = cache.iterator()
                if (iterator.hasNext()) {
                    iterator.next()
                    iterator.remove()
                    evictionCount++
                }
            }

            cache.put(key, value)
        }
    }

    /**
     * Сохранение пакета записей
     */
    fun putAll(entries: Map<K, V>) {
        lock.write {
            entries.forEach { (key, value) ->
                // Проверяем нужно ли удалять старые записи
                while (cache.size >= maxSize && maxSize > 0) {
                    val iterator = cache.iterator()
                    if (iterator.hasNext()) {
                        iterator.next()
                        iterator.remove()
                        evictionCount++
                    }
                }
                cache[key] = value
            }
        }
    }

    /**
     * Проверка наличия ключа
     */
    fun contains(key: K): Boolean {
        return lock.read {
            cache.containsKey(key)
        }
    }

    /**
     * Удаление ключа
     */
    fun remove(key: K): V? {
        return lock.write {
            cache.remove(key)
        }
    }

    /**
     * Очистка кэша
     */
    fun clear() {
        lock.write {
            cache.clear()
            hitCount = 0
            missCount = 0
            evictionCount = 0
        }
    }

    /**
     * Размер кэша
     */
    fun size(): Int {
        return lock.read {
            cache.size
        }
    }

    /**
     * Статистика кэша
     */
    fun stats(): CacheStats {
        return lock.read {
            val totalRequests = hitCount + missCount
            CacheStats(
                size = cache.size,
                maxSize = maxSize,
                hitCount = hitCount,
                missCount = missCount,
                evictionCount = evictionCount,
                hitRate = if (totalRequests > 0) hitCount.toDouble() / totalRequests else 0.0
            )
        }
    }

    /**
     * Все ключи
     */
    fun keys(): Set<K> {
        return lock.read {
            cache.keys.toSet()
        }
    }

    /**
     * Все значения
     */
    fun values(): Collection<V> {
        return lock.read {
            cache.values.toList()
        }
    }
}

/**
 * TTL (Time To Live) кэш с автоматической очисткой устаревших записей
 */
class TtlCache<K, V>(
    private val maxSize: Int,
    private val ttlMillis: Long
) {
    data class CacheEntry<V>(
        val value: V,
        val createdAt: Long
    )

    private val cache = ConcurrentHashMap<K, CacheEntry<V>>()
    private var hitCount = 0L
    private var missCount = 0L
    private var evictionCount = 0L

    /**
     * Статистика кэша
     */
    data class CacheStats(
        val size: Int,
        val maxSize: Int,
        val hitCount: Long,
        val missCount: Long,
        val expiredCount: Long,
        val hitRate: Double
    )

    private var expiredCount = 0L

    /**
     * Получение значения из кэша
     */
    fun get(key: K): V? {
        val entry = cache[key] ?: run {
            missCount++
            return null
        }

        // Проверяем не истёк ли TTL
        val age = System.currentTimeMillis() - entry.createdAt
        if (age > ttlMillis) {
            cache.remove(key)
            expiredCount++
            missCount++
            return null
        }

        hitCount++
        return entry.value
    }

    /**
     * Сохранение значения в кэш
     */
    fun put(key: K, value: V): V? {
        // Очищаем старые записи если достигнут лимит
        if (cache.size >= maxSize && maxSize > 0) {
            cleanupExpired()
            
            // Если всё ещё переполнен, удаляем oldest
            if (cache.size >= maxSize) {
                val oldestKey = cache.entries.minByOrNull { it.value.createdAt }?.key
                oldestKey?.let { cache.remove(it) }
                evictionCount++
            }
        }

        cache[key] = CacheEntry(value, System.currentTimeMillis())
        return null
    }

    /**
     * Очистка устаревших записей
     */
    fun cleanupExpired() {
        val now = System.currentTimeMillis()
        val toRemove = cache.filter { 
            now - it.value.createdAt > ttlMillis 
        }.keys

        toRemove.forEach { cache.remove(it) }
        expiredCount += toRemove.size
    }

    /**
     * Размер кэша
     */
    fun size(): Int {
        cleanupExpired()
        return cache.size
    }

    /**
     * Статистика кэша
     */
    fun stats(): CacheStats {
        val totalRequests = hitCount + missCount
        return CacheStats(
            size = size(),
            maxSize = maxSize,
            hitCount = hitCount,
            missCount = missCount,
            expiredCount = expiredCount,
            hitRate = if (totalRequests > 0) hitCount.toDouble() / totalRequests else 0.0
        )
    }

    /**
     * Очистка кэша
     */
    fun clear() {
        cache.clear()
        hitCount = 0
        missCount = 0
        expiredCount = 0
    }
}

/**
 * Двухуровневый кэш: L1 (in-memory) + L2 (database/disk)
 */
class TwoLevelCache<K, V>(
    private val l1Cache: LruCache<K, V>,
    private val l2Fetcher: (K) -> V?
) {
    /**
     * Статистика двухуровневого кэша
     */
    data class CacheStats(
        val l1Size: Int,
        val l1HitCount: Long,
        val l1MissCount: Long,
        val l1HitRate: Double,
        val l2FetchCount: Long
    )

    private var l2FetchCount = 0L

    /**
     * Получение значения с проверкой L1 и L2
     */
    fun get(key: K): V? {
        // Сначала проверяем L1 (быстрый)
        val l1Value = l1Cache.get(key)
        if (l1Value != null) {
            return l1Value
        }

        // Если нет в L1, загружаем из L2 (медленный)
        val l2Value = l2Fetcher(key)
        if (l2Value != null) {
            l2FetchCount++
            // Сохраняем в L1 для будущих запросов
            l1Cache.put(key, l2Value)
        }

        return l2Value
    }

    /**
     * Сохранение в L1
     */
    fun put(key: K, value: V) {
        l1Cache.put(key, value)
    }

    /**
     * Статистика
     */
    fun stats(): CacheStats {
        val l1Stats = l1Cache.stats()
        return CacheStats(
            l1Size = l1Stats.size,
            l1HitCount = l1Stats.hitCount,
            l1MissCount = l1Stats.missCount,
            l1HitRate = l1Stats.hitRate,
            l2FetchCount = l2FetchCount
        )
    }
}
