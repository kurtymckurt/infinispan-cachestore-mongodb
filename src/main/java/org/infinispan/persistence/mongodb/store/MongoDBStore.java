package org.infinispan.persistence.mongodb.store;

import net.jcip.annotations.ThreadSafe;
import org.infinispan.commons.marshall.StreamingMarshaller;
import org.infinispan.executors.ExecutorAllCompletionService;
import org.infinispan.marshall.core.MarshalledEntry;
import org.infinispan.metadata.InternalMetadata;
import org.infinispan.persistence.TaskContextImpl;
import org.infinispan.persistence.mongodb.cache.MongoDBCache;
import org.infinispan.persistence.mongodb.cache.MongoDBCacheImpl;
import org.infinispan.persistence.mongodb.configuration.MongoDBStoreConfiguration;
import org.infinispan.persistence.spi.AdvancedLoadWriteStore;
import org.infinispan.persistence.spi.InitializationContext;
import org.infinispan.persistence.spi.PersistenceException;

import java.io.IOException;
import java.util.Date;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.Executor;

/**
 * AdvancedLoadWriteStore implementation based on MongoDB. <br/>
 * This class is fully thread safe
 *
 * @param <K>
 * @param <V>
 * @author Gabriel Francisco <gabfssilva@gmail.com>
 */
@ThreadSafe
public class MongoDBStore<K, V> implements AdvancedLoadWriteStore<K, V> {
    private InitializationContext context;

    private MongoDBCache<K, V> cache;
    private MongoDBStoreConfiguration configuration;
    private final Set<byte[]> keys = new HashSet<byte[]>();

    @Override
    public void init(InitializationContext ctx) {
        context = ctx;
        configuration = ctx.getConfiguration();
        try {
            cache = new MongoDBCacheImpl<K, V>(configuration);
        } catch (Exception e) {
            throw new PersistenceException(e);
        }
    }

    @Override
    public void process(KeyFilter<K> filter, final CacheLoaderTask<K, V> task, Executor executor, boolean fetchValue, boolean fetchMetadata) {
        Set<byte[]> keys = this.keys;

        ExecutorAllCompletionService eacs = new ExecutorAllCompletionService(executor);
        final TaskContextImpl taskContext = new TaskContextImpl();
        for (byte[] key : keys) {
            final K marshalledKey = (K) toObject(key);
            if (filter == null || filter.shouldLoadKey(marshalledKey)) {
                if (taskContext.isStopped()) {
                    break;
                }
                eacs.submit(new Callable<Void>() {
                    @Override
                    public Void call() throws Exception {
                        try {
                            final MarshalledEntry<K, V> marshalledEntry = load(marshalledKey);
                            if (marshalledEntry != null) {
                                task.processEntry(marshalledEntry, taskContext);
                            }
                            return null;
                        } catch (Exception e) {
                            throw e;
                        }
                    }
                });
            }
        }
        eacs.waitUntilAllCompleted();
        if (eacs.isExceptionThrown()) {
            throw new PersistenceException("Execution exception!", eacs.getFirstException());
        }
    }

    @Override
    public int size() {
        return cache.size();
    }

    @Override
    public void clear() {
        keys.clear();
        cache.clear();
    }

    @Override
    public void purge(Executor threadPool, PurgeListener listener) {
        Set<byte[]> keysPurged = cache.removeExpiredData(this.keys);
        for(byte[] key : keysPurged) {
            keys.remove(key);
        }
    }

    @Override
    public void write(MarshalledEntry<K, V> entry) {
        MongoDBEntry.Builder<K, V> mongoDBEntryBuilder = MongoDBEntry.builder();
        byte[] byteArray = toByteArray(entry.getKey());
        mongoDBEntryBuilder
                .keyBytes(byteArray)
                .valueBytes(toByteArray(entry.getValue()))
                .metadataBytes(toByteArray(entry.getMetadata()))
                .expiryTime(entry.getMetadata() != null ? new Date(entry.getMetadata().expiryTime()) : null);

        MongoDBEntry<K, V> mongoDBEntry = mongoDBEntryBuilder.create();

        keys.add(byteArray);
        cache.put(mongoDBEntry);
    }

    @Override
    public boolean delete(K key) {
        byte[] byteArray = toByteArray(key);
        keys.remove(byteArray);
        return cache.remove(byteArray);
    }

    @Override
    public MarshalledEntry<K, V> load(K key) {
        return load(key, false);
    }

    private MarshalledEntry<K, V> load(K key, boolean binaryData) {
        byte[] byteArray = toByteArray(key);
        MongoDBEntry<K, V> mongoDBEntry = cache.get(byteArray);

        if (mongoDBEntry == null) {
            return null;
        }

        K k = mongoDBEntry.getKey(marshaller());
        V v = mongoDBEntry.getValue(marshaller());

        InternalMetadata metadata;

        metadata = (InternalMetadata) toObject(mongoDBEntry.getMetadataBytes());

        MarshalledEntry result = context.getMarshalledEntryFactory().newMarshalledEntry(k, v, metadata);

        if (isExpired(mongoDBEntry, result)) {
            keys.remove(byteArray);
            cache.remove(mongoDBEntry.getKeyBytes());
            return null;
        }

        return result;
    }

    @Override
    public boolean contains(K key) {
        return cache.containsKey(toByteArray(key));
    }

    @Override
    public void start() {
        if (configuration.purgeOnStartup()) {
            cache.clear();
        }
    }

    @Override
    public void stop() {
    }

    private boolean isExpired(MongoDBEntry<K, V> mongoDBEntry, MarshalledEntry result) {
        if (result.getMetadata() == null) {
            return false;
        }

        return result.getMetadata().isExpired(System.currentTimeMillis());
    }

    private Object toObject(byte[] bytes) {
        try {
            return marshaller().objectFromByteBuffer(bytes);
        } catch (IOException e) {
            e.printStackTrace();
        } catch (ClassNotFoundException e) {
            e.printStackTrace();
        }
        return null;
    }

    private byte[] toByteArray(Object obj) {
        try {
            return marshaller().objectToByteBuffer(obj);
        } catch (IOException e) {
            e.printStackTrace();
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        return null;
    }

    private StreamingMarshaller marshaller() {
        return context.getMarshaller();
    }

    public InitializationContext getContext() {
        return context;
    }

    public void setContext(InitializationContext context) {
        this.context = context;
    }

    public MongoDBCache<K, V> getCache() {
        return cache;
    }
}
