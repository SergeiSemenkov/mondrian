package mondrian.rolap.cache;

import mondrian.olap.MondrianProperties;
import mondrian.spi.SegmentCache;
import mondrian.spi.SegmentHeader;
import mondrian.spi.SegmentBody;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;
import redis.clients.jedis.JedisPubSub;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Redis-backed implementation of {@link mondrian.spi.SegmentCache}.
 * priority: system property → env var → default
 * Configuration (system properties):
 * - mondrian.redis.host (default "redis")
 * - mondrian.redis.port (default "6379")
 * - mondrian.redis.prefix (default "mondrian:segments")
 *
 * Notes:
 * - Uses Java serialization for SegmentHeader and SegmentBody.
 * - Stores header and body under keys derived from SHA-256(headerBytes).
 * - Publishes events on a Redis channel so other nodes can notify listeners.
 */
public class RedisSegmentCache implements SegmentCache {
    private static final Logger LOGGER = Logger.getLogger(RedisSegmentCache.class.getName());

    private static final String DEFAULT_HOST = "redis";
    private static final int DEFAULT_PORT = 6379;
    private static final String DEFAULT_PREFIX = "mondrian:segments";
    private static final String EVENTS_CHANNEL = "mondrian:segment:events";

    private final String redisHost;
    private final int redisPort;
    private final String prefix;

    private final JedisPool pool;
    private final List<SegmentCacheListener> listeners = new CopyOnWriteArrayList<SegmentCacheListener>();

    private final JedisPubSub subscriber = new JedisPubSub() {
        @Override
        public void onMessage(String channel, String message) {
            try {
                handleRemoteMessage(message);
            } catch (Throwable t) {
                LOGGER.log(Level.WARNING, "Error handling remote message: " + message, t);
            }
        }
    };

    private Thread subscriberThread;
    private volatile Jedis subscriberJedis;
    private volatile boolean running = true;

    private final String id = UUID.randomUUID().toString();
    private final int ttlSeconds;

    public RedisSegmentCache() {
        //priority: system property → env var → default
        String envHost = System.getenv("MONDRIAN_REDIS_HOST");
        String envPort = System.getenv("MONDRIAN_REDIS_PORT");

        this.redisHost = System.getProperty(
            "mondrian.redis.host",
            envHost != null ? envHost : DEFAULT_HOST
        );
        this.redisPort = Integer.parseInt(System.getProperty(
            "mondrian.redis.port",
            envPort != null ? envPort : Integer.toString(DEFAULT_PORT)
        ));
        this.prefix = System.getProperty("mondrian.redis.prefix", DEFAULT_PREFIX);

        this.pool = new JedisPool(new JedisPoolConfig(), redisHost, redisPort);

        this.ttlSeconds = MondrianProperties.instance().IdleOrphanSessionTimeout.get();

        startSubscriber();
    }

    // Helper key builders
    private String headerKey(String id) {
        return prefix + ":header:" + id;
    }
    private String bodyKey(String id) {
        return prefix + ":body:" + id;
    }
    private String indexKey() {
        return prefix + ":index:" + id;
    }

    // Compute SHA-256 hex of serialized header
    private String idForHeader(SegmentHeader header) {
        return id + ":" + header.getUniqueID();
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b & 0xff));
        }
        return sb.toString();
    }

    private byte[] serialize(Object obj) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        ObjectOutputStream out = new ObjectOutputStream(bos);
        out.writeObject(obj);
        out.flush();
        return bos.toByteArray();
    }

    private Object deserialize(byte[] bytes) throws IOException, ClassNotFoundException {
        ByteArrayInputStream bis = new ByteArrayInputStream(bytes);
        ObjectInputStream in = new ObjectInputStream(bis);
        return in.readObject();
    }

    @Override
    public SegmentBody get(SegmentHeader header) {
        try {
            final String id = idForHeader(header);
            try (Jedis jedis = pool.getResource()) {
                byte[] bodyKeyBytes = bodyKey(id).getBytes(StandardCharsets.UTF_8);
                byte[] b = jedis.get(bodyKeyBytes);
                if (b == null) {
                    return null;
                }
                // Refresh TTL on read
                jedis.expire(bodyKeyBytes, ttlSeconds);
                jedis.expire(headerKey(id).getBytes(StandardCharsets.UTF_8), ttlSeconds);
                jedis.expire(indexKey().getBytes(StandardCharsets.UTF_8), ttlSeconds);

                Object o = deserialize(b);
                if (o instanceof SegmentBody) {
                    return (SegmentBody) o;
                } else {
                    LOGGER.log(Level.WARNING, "Unexpected object type in Redis body for id=" + id);
                    return null;
                }
            }
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Error reading segment from Redis", e);
            return null;
        }
    }

    @Override
    public List<SegmentHeader> getSegmentHeaders() {
        List<SegmentHeader> result = new ArrayList<SegmentHeader>();
        try (Jedis jedis = pool.getResource()) {
            Set<String> ids = jedis.smembers(indexKey());
            for (String id : ids) {
                try {
                    byte[] hb = jedis.get(headerKey(id).getBytes(StandardCharsets.UTF_8));
                    if (hb == null) {
                        // stale index entry; optionally remove
                        jedis.srem(indexKey(), id);
                        continue;
                    }
                    Object o = deserialize(hb);
                    if (o instanceof SegmentHeader) {
                        result.add((SegmentHeader) o);
                    }
                } catch (Exception e) {
                    LOGGER.log(Level.FINE, "Error deserializing header " + id, e);
                }
            }
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Error listing segment headers from Redis", e);
        }
        return result;
    }

    @Override
    public boolean put(final SegmentHeader header, SegmentBody body) {
        try {
            final byte[] headerBytes = serialize(header);
            final byte[] bodyBytes = serialize(body);
            final String id = idForHeader(header);

            try (Jedis jedis = pool.getResource()) {
                byte[] hk = headerKey(id).getBytes(StandardCharsets.UTF_8);
                byte[] bk = bodyKey(id).getBytes(StandardCharsets.UTF_8);
                String ik = indexKey();

                jedis.set(hk, headerBytes);
                jedis.expire(hk, ttlSeconds);

                jedis.set(bk, bodyBytes);
                jedis.expire(bk, ttlSeconds);

                jedis.sadd(ik, id);
                jedis.expire(ik.getBytes(StandardCharsets.UTF_8), ttlSeconds);

                // publish event with header bytes so remote nodes can reconstruct
                final String headerB64 = Base64.getEncoder().encodeToString(headerBytes);
                final String msg = "CREATED:" + id + ":" + headerB64;
                jedis.publish(EVENTS_CHANNEL, msg);
            }

            fireSegmentCacheEvent(new SegmentCacheListener.SegmentCacheEvent() {
                public boolean isLocal() { return true; }
                public SegmentHeader getSource() { return header; }
                public EventType getEventType() {
                    return EventType.ENTRY_CREATED;
                }
            });

            return true;
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Error putting segment to Redis", e);
            return false;
        }
    }

    @Override
    public boolean remove(final SegmentHeader header) {
        try {
            final byte[] headerBytes = serialize(header);
            final String id = idForHeader(header);

            try (Jedis jedis = pool.getResource()) {
                Long removed = jedis.del(bodyKey(id).getBytes(StandardCharsets.UTF_8),
                                         headerKey(id).getBytes(StandardCharsets.UTF_8));
                jedis.srem(indexKey(), id);

                final String headerB64 = Base64.getEncoder().encodeToString(headerBytes);
                final String msg = "DELETED:" + id + ":" + headerB64;
                jedis.publish(EVENTS_CHANNEL, msg);

                boolean result = removed != null && removed > 0;
                if (result) {
                    fireSegmentCacheEvent(new SegmentCacheListener.SegmentCacheEvent() {
                        public boolean isLocal() { return true; }
                        public SegmentHeader getSource() { return header; }
                        public EventType getEventType() {
                            return EventType.ENTRY_DELETED;
                        }
                    });
                }
                return result;
            }
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, "Error removing segment from Redis", e);
            return false;
        }
    }

    @Override
    public void tearDown() {
        running = false;
        try {
            subscriber.unsubscribe();
        } catch (Exception e) {
            LOGGER.log(Level.FINE, "Error unsubscribing", e);
        }

        // Close the dedicated subscribe connection to unblock Jedis.subscribe().
        try {
            Jedis jedis = subscriberJedis;
            if (jedis != null) {
                jedis.close();
            }
        } catch (Exception e) {
            LOGGER.log(Level.FINE, "Error closing subscriber Jedis", e);
        }

        if (subscriberThread != null) {
            try {
                subscriberThread.interrupt();
                subscriberThread.join(5000);
                if (subscriberThread.isAlive()) {
                    LOGGER.log(Level.WARNING,
                        "Redis subscriber thread did not stop within timeout");
                }
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        }
        try {
            pool.close();
        } catch (Exception e) {
            LOGGER.log(Level.FINE, "Error closing JedisPool", e);
        }
        listeners.clear();
    }

    @Override
    public void addListener(SegmentCacheListener listener) {
        listeners.add(listener);
    }

    @Override
    public void removeListener(SegmentCacheListener listener) {
        listeners.remove(listener);
    }

    @Override
    public boolean supportsRichIndex() {
        return true;
    }

    public String getId() {
        return id;
    }

    // Fire local events to registered listeners
    private void fireSegmentCacheEvent(final SegmentCacheListener.SegmentCacheEvent evt) {
        for (SegmentCacheListener l : listeners) {
            try {
                l.handle(evt);
            } catch (Throwable t) {
                LOGGER.log(Level.FINE, "Exception in listener", t);
            }
        }
    }

    // Start background subscriber thread to listen for remote events
    private void startSubscriber() {
        subscriberThread = new Thread(new Runnable() {
            public void run() {
                try (Jedis jedis = pool.getResource()) {
                    subscriberJedis = jedis;
                    // blocks until unsubscribed/connection closed
                    jedis.subscribe(subscriber, EVENTS_CHANNEL);
                } catch (Exception e) {
                    if (running) {
                        LOGGER.log(Level.WARNING, "Redis subscriber exited unexpectedly", e);
                    }
                } finally {
                    subscriberJedis = null;
                }
            }
        }, "RedisSegmentCache-Subscriber");
        subscriberThread.setDaemon(true);
        subscriberThread.setContextClassLoader(null);
        subscriberThread.start();
    }

    // Handle messages from other nodes (format TYPE:id:base64(headerBytes))
    private void handleRemoteMessage(String message) {
        if (message == null) return;
        String[] parts = message.split(":", 3);
        if (parts.length < 3) {
            return;
        }
        final String type = parts[0];
        // final String id = parts[1]; // id may be unused
        final String headerB64 = parts[2];
        try {
            byte[] hb = Base64.getDecoder().decode(headerB64);
            Object o = deserialize(hb);
            if (!(o instanceof SegmentHeader)) {
                return;
            }
            final SegmentHeader header = (SegmentHeader) o;
            final SegmentCacheListener.SegmentCacheEvent evt =
                new SegmentCacheListener.SegmentCacheEvent() {
                    public boolean isLocal() { return false; }
                    public SegmentHeader getSource() { return header; }
                    public EventType getEventType() {
                        return "CREATED".equals(type) ?
                            EventType.ENTRY_CREATED :
                            EventType.ENTRY_DELETED;
                    }
                };
            fireSegmentCacheEvent(evt);
        } catch (Exception e) {
            LOGGER.log(Level.FINE, "Error handling remote event", e);
        }
    }
}
