package com.splicemachine.si.data.hbase.coprocessor;

import com.splicemachine.access.HConfiguration;
import com.splicemachine.access.api.DistributedFileSystem;
import com.splicemachine.access.api.SConfiguration;
import com.splicemachine.access.api.PartitionFactory;
import com.splicemachine.concurrent.Clock;
import com.splicemachine.si.api.SIConfigurations;
import com.splicemachine.si.api.data.ExceptionFactory;
import com.splicemachine.si.api.data.OperationFactory;
import com.splicemachine.si.api.data.OperationStatusFactory;
import com.splicemachine.si.api.data.TxnOperationFactory;
import com.splicemachine.si.api.readresolve.KeyedReadResolver;
import com.splicemachine.si.api.readresolve.RollForward;
import com.splicemachine.si.api.txn.KeepAliveScheduler;
import com.splicemachine.si.api.txn.TxnStore;
import com.splicemachine.si.api.txn.TxnSupplier;
import com.splicemachine.si.data.HExceptionFactory;
import com.splicemachine.si.data.hbase.HOperationStatusFactory;
import com.splicemachine.si.impl.*;
import com.splicemachine.si.impl.driver.SIDriver;
import com.splicemachine.si.impl.driver.SIEnvironment;
import com.splicemachine.si.impl.readresolve.SynchronousReadResolver;
import com.splicemachine.si.impl.rollforward.NoopRollForward;
import com.splicemachine.si.impl.store.CompletedTxnCacheSupplier;
import com.splicemachine.si.impl.store.IgnoreTxnCacheSupplier;
import com.splicemachine.storage.*;
import com.splicemachine.timestamp.api.TimestampSource;
import com.splicemachine.timestamp.hbase.ZkTimestampSource;
import org.apache.hadoop.fs.FileSystem;
import org.apache.hadoop.hbase.TableName;
import org.apache.hadoop.hbase.zookeeper.RecoverableZooKeeper;

import java.io.IOException;

/**
 * @author Scott Fines
 *         Date: 12/18/15
 */
public class HBaseSIEnvironment implements SIEnvironment{
    private static volatile HBaseSIEnvironment INSTANCE;

    private final TimestampSource timestampSource;
    private final PartitionFactory<TableName> partitionFactory;
    private final TxnStore txnStore;
    private final TxnSupplier txnSupplier;
    private final IgnoreTxnCacheSupplier ignoreTxnSupplier;
    private final TxnOperationFactory txnOpFactory;
    private final PartitionInfoCache partitionCache;
    private final KeepAliveScheduler keepAlive;
    private final SConfiguration config;
    private final HOperationFactory opFactory;
    private final Clock clock;
    private final DistributedFileSystem fileSystem;

    private SIDriver siDriver;

    public static HBaseSIEnvironment loadEnvironment(Clock clock,RecoverableZooKeeper rzk) throws IOException{
        HBaseSIEnvironment env = INSTANCE;
        if(env==null){
            synchronized(HBaseSIEnvironment.class){
                env = INSTANCE;
                if(env==null){
                    env = INSTANCE = new HBaseSIEnvironment(rzk,clock);
                    env.siDriver=SIDriver.loadDriver(INSTANCE);
                }
            }
        }
        return env;
    }

    public static void setEnvironment(HBaseSIEnvironment siEnv){
        INSTANCE = siEnv;
    }

    public HBaseSIEnvironment(TimestampSource timeSource,Clock clock) throws IOException{
        this.config=HConfiguration.INSTANCE;
        this.config.addDefaults(StorageConfiguration.defaults);
        this.config.addDefaults(SIConfigurations.defaults);
        this.timestampSource =timeSource;
        this.partitionCache = PartitionCacheService.loadPartitionCache(config);
        this.partitionFactory =TableFactoryService.loadTableFactory(clock,this.config,partitionCache);
        TxnNetworkLayerFactory txnNetworkLayerFactory= TableFactoryService.loadTxnNetworkLayer(this.config);
        this.txnStore = new CoprocessorTxnStore(txnNetworkLayerFactory,timestampSource,null);
        int completedTxnCacheSize = config.getInt(SIConfigurations.completedTxnCacheSize);
        int completedTxnConcurrency = config.getInt(SIConfigurations.completedTxnConcurrency);
        this.txnSupplier = new CompletedTxnCacheSupplier(txnStore,completedTxnCacheSize,completedTxnConcurrency);
        this.txnStore.setCache(txnSupplier);
        this.opFactory =HOperationFactory.INSTANCE;
        this.ignoreTxnSupplier = new IgnoreTxnCacheSupplier(opFactory, partitionFactory);
        this.txnOpFactory = new SimpleTxnOperationFactory(exceptionFactory(),opFactory);
        this.clock = clock;
        this.fileSystem =new HNIOFileSystem(FileSystem.get(((HConfiguration)config).unwrapDelegate()));


        this.keepAlive = new QueuedKeepAliveScheduler(config.getLong(SIConfigurations.TRANSACTION_KEEP_ALIVE_INTERVAL),
                config.getLong(SIConfigurations.TRANSACTION_TIMEOUT),
                config.getInt(SIConfigurations.TRANSACTION_KEEP_ALIVE_THREADS),
                txnStore);
    }

    @SuppressWarnings("unchecked")
    public HBaseSIEnvironment(RecoverableZooKeeper rzk,Clock clock) throws IOException{
        this.config=HConfiguration.INSTANCE;
        this.config.addDefaults(StorageConfiguration.defaults);
        this.config.addDefaults(SIConfigurations.defaults);

        this.timestampSource =new ZkTimestampSource(config,rzk);
        this.partitionCache = PartitionCacheService.loadPartitionCache(config);
        this.partitionFactory =TableFactoryService.loadTableFactory(clock, this.config,partitionCache);
        TxnNetworkLayerFactory txnNetworkLayerFactory= TableFactoryService.loadTxnNetworkLayer(this.config);
        this.txnStore = new CoprocessorTxnStore(txnNetworkLayerFactory,timestampSource,null);
        int completedTxnCacheSize = config.getInt(SIConfigurations.completedTxnCacheSize);
        int completedTxnConcurrency = config.getInt(SIConfigurations.completedTxnConcurrency);
        this.txnSupplier = new CompletedTxnCacheSupplier(txnStore,completedTxnCacheSize,completedTxnConcurrency);
        this.txnStore.setCache(txnSupplier);
        this.opFactory =HOperationFactory.INSTANCE;
        this.ignoreTxnSupplier = new IgnoreTxnCacheSupplier(opFactory, partitionFactory);
        this.txnOpFactory = new SimpleTxnOperationFactory(exceptionFactory(),opFactory);
        this.clock = clock;
        this.fileSystem =new HNIOFileSystem(FileSystem.get(((HConfiguration)config).unwrapDelegate()));


        this.keepAlive = new QueuedKeepAliveScheduler(config.getLong(SIConfigurations.TRANSACTION_KEEP_ALIVE_INTERVAL),
                config.getLong(SIConfigurations.TRANSACTION_TIMEOUT),
                config.getInt(SIConfigurations.TRANSACTION_KEEP_ALIVE_THREADS),
                txnStore);
    }


    @Override public PartitionFactory tableFactory(){ return partitionFactory; }

    @Override
    public ExceptionFactory exceptionFactory(){
        return HExceptionFactory.INSTANCE;
    }

    @Override
    public SConfiguration configuration(){
        return config;
    }

    @Override
    public TxnStore txnStore(){
        return txnStore;
    }

    @Override
    public TxnSupplier txnSupplier(){
        return txnSupplier;
    }

    @Override
    public IgnoreTxnCacheSupplier ignoreTxnSupplier(){
        return ignoreTxnSupplier;
    }

    @Override
    public OperationStatusFactory statusFactory(){
        return HOperationStatusFactory.INSTANCE;
    }

    @Override
    public TimestampSource timestampSource(){
        return timestampSource;
    }

    @Override
    public RollForward rollForward(){
        return NoopRollForward.INSTANCE;
    }

    @Override
    public TxnOperationFactory operationFactory(){
        return txnOpFactory;
    }

    @Override
    public SIDriver getSIDriver(){
        return siDriver;
    }

    @Override
    public PartitionInfoCache partitionInfoCache(){
        return partitionCache;
    }

    @Override
    public KeepAliveScheduler keepAliveScheduler(){
        return keepAlive;
    }

    @Override
    public DataFilterFactory filterFactory(){
        return HFilterFactory.INSTANCE;
    }

    @Override
    public Clock systemClock(){
        return clock;
    }

    @Override
    public KeyedReadResolver keyedReadResolver(){
        return SynchronousReadResolver.INSTANCE;
    }

    @Override
    public DistributedFileSystem fileSystem(){
        return fileSystem;
    }

    @Override
    public OperationFactory baseOperationFactory(){
        return opFactory;
    }

    public void setSIDriver(SIDriver siDriver) {
        this.siDriver = siDriver;
    }

}