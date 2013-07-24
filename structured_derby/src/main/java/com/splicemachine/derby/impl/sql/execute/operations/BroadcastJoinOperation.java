package com.splicemachine.derby.impl.sql.execute.operations;

import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;
import com.google.common.cache.RemovalListener;
import com.google.common.cache.RemovalNotification;
import com.google.common.collect.Iterators;
import com.splicemachine.derby.iapi.sql.execute.SpliceNoPutResultSet;
import com.splicemachine.derby.iapi.sql.execute.SpliceOperation;
import com.splicemachine.derby.iapi.sql.execute.SpliceOperationContext;
import com.splicemachine.derby.iapi.storage.RowProvider;
import com.splicemachine.derby.impl.store.access.hbase.HBaseRowLocation;
import com.splicemachine.derby.utils.SpliceUtils;
import com.splicemachine.derby.utils.marshall.KeyMarshall;
import com.splicemachine.derby.utils.marshall.KeyType;
import com.splicemachine.derby.utils.marshall.RowDecoder;
import com.splicemachine.encoding.MultiFieldEncoder;
import com.splicemachine.utils.SpliceLogUtils;
import org.apache.derby.iapi.error.StandardException;
import org.apache.derby.iapi.services.loader.GeneratedMethod;
import org.apache.derby.iapi.sql.Activation;
import org.apache.derby.iapi.sql.execute.ExecRow;
import org.apache.derby.iapi.sql.execute.NoPutResultSet;
import org.apache.derby.iapi.store.access.Qualifier;
import org.apache.derby.iapi.types.SQLInteger;
import org.apache.derby.shared.common.reference.MessageId;
import org.apache.hadoop.hbase.client.Scan;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.log4j.Logger;

import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectOutput;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

public class BroadcastJoinOperation extends JoinOperation {
    private static final long serialVersionUID = 2l;
    private static Logger LOG = Logger.getLogger(BroadcastJoinOperation.class);
    protected String emptyRowFunMethodName;
    protected boolean wasRightOuterJoin;
    protected Qualifier[][] qualifierProbe;
    protected int leftHashKeyItem;
    protected int[] leftHashKeys;
    protected int rightHashKeyItem;
    protected int[] rightHashKeys;
    protected ExecRow rightTemplate;
    protected static List<NodeType> nodeTypes;
    protected Scan reduceScan;
    protected RowProvider clientProvider;
    protected SQLInteger rowType;
    protected byte[] priorHash;
    protected List<ExecRow> rights;
    protected byte[] rightHash;
    protected Iterator<ExecRow> rightIterator;
    protected BroadcastNextRowIterator broadcastIterator;
    protected Map<ByteBuffer, List<ExecRow>> rightSideMap;
    protected boolean isOuterJoin = false;
    protected static final Cache<Integer, Map<ByteBuffer, List<ExecRow>>> broadcastJoinCache;


    static {
        nodeTypes = new ArrayList<NodeType>();
        nodeTypes.add(NodeType.MAP);
        nodeTypes.add(NodeType.SCROLL);
        broadcastJoinCache = CacheBuilder.newBuilder().
                maximumSize(50000).expireAfterWrite(10, TimeUnit.MINUTES).removalListener(new RemovalListener<Integer, Map<ByteBuffer, List<ExecRow>>>() {
            @Override
            public void onRemoval(RemovalNotification<Integer, Map<ByteBuffer, List<ExecRow>>> notification) {
                SpliceLogUtils.trace(LOG, "Removing unique sequence ID %s", notification.getKey());
            }
        }).build();
    }

    public BroadcastJoinOperation() {
        super();
    }

    public BroadcastJoinOperation(NoPutResultSet leftResultSet,
                                  int leftNumCols,
                                  NoPutResultSet rightResultSet,
                                  int rightNumCols,
                                  int leftHashKeyItem,
                                  int rightHashKeyItem,
                                  Activation activation,
                                  GeneratedMethod restriction,
                                  int resultSetNumber,
                                  boolean oneRowRightSide,
                                  boolean notExistsRightSide,
                                  double optimizerEstimatedRowCount,
                                  double optimizerEstimatedCost,
                                  String userSuppliedOptimizerOverrides) throws StandardException {
        super(leftResultSet, leftNumCols, rightResultSet, rightNumCols,
                activation, restriction, resultSetNumber, oneRowRightSide, notExistsRightSide,
                optimizerEstimatedRowCount, optimizerEstimatedCost, userSuppliedOptimizerOverrides);
        SpliceLogUtils.trace(LOG, "instantiate");
        this.leftHashKeyItem = leftHashKeyItem;
        this.rightHashKeyItem = rightHashKeyItem;
        init(SpliceOperationContext.newContext(activation));
    }

    @Override
    public void readExternal(ObjectInput in) throws IOException, ClassNotFoundException {
        SpliceLogUtils.trace(LOG, "readExternal");
        super.readExternal(in);
        leftHashKeyItem = in.readInt();
        rightHashKeyItem = in.readInt();
    }

    @Override
    public void writeExternal(ObjectOutput out) throws IOException {
        SpliceLogUtils.trace(LOG, "writeExternal");
        super.writeExternal(out);
        out.writeInt(leftHashKeyItem);
        out.writeInt(rightHashKeyItem);
    }

    @Override
    public ExecRow getNextRowCore() throws StandardException {
        SpliceLogUtils.trace(LOG, "getNextRowCore");
        if (rightSideMap == null)
            rightSideMap = retrieveRightSideCache();

        if (broadcastIterator == null || !broadcastIterator.hasNext()) {
            if ((leftRow = leftResultSet.getNextRowCore()) == null) {
                mergedRow = null;
                this.setCurrentRow(mergedRow);
                return mergedRow;
            } else {
                broadcastIterator = new BroadcastNextRowIterator(leftRow);
                return getNextRowCore();
            }
        } else {
            return broadcastIterator.next();
        }
    }

    @Override
    public RowProvider getReduceRowProvider(SpliceOperation top, RowDecoder decoder) throws StandardException {
        return leftResultSet.getReduceRowProvider(top, decoder);
    }

    @Override
    public RowProvider getMapRowProvider(SpliceOperation top, RowDecoder decoder) throws StandardException {
        return leftResultSet.getMapRowProvider(top, decoder);
    }


    @Override
    public void init(SpliceOperationContext context) throws StandardException {
        SpliceLogUtils.trace(LOG, "init");
        super.init(context);
        leftHashKeys = generateHashKeys(leftHashKeyItem, (SpliceBaseOperation) this.leftResultSet);
        rightHashKeys = generateHashKeys(rightHashKeyItem, (SpliceBaseOperation) this.rightResultSet);
        mergedRow = activation.getExecutionFactory().getValueRow(leftNumCols + rightNumCols);
        rightTemplate = activation.getExecutionFactory().getValueRow(rightNumCols);
        rightResultSet.init(context);
    }

    @Override
    public NoPutResultSet executeScan() throws StandardException {
        SpliceLogUtils.trace(LOG, "executeScan");
        final List<SpliceOperation> opStack = new ArrayList<SpliceOperation>();
        this.generateLeftOperationStack(opStack);
        SpliceLogUtils.trace(LOG, "operationStack=%s", opStack);

        // Get the topmost value, instead of the bottommost, in case it's you
        SpliceOperation regionOperation = opStack.get(opStack.size() - 1);
        SpliceLogUtils.trace(LOG, "regionOperation=%s", opStack);
        RowProvider provider;
        RowDecoder decoder = getRowEncoder().getDual(getExecRowDefinition());
        if (regionOperation.getNodeTypes().contains(NodeType.REDUCE)) {
            provider = regionOperation.getReduceRowProvider(this, decoder);
        } else {
            provider = regionOperation.getMapRowProvider(this, decoder);
        }
        return new SpliceNoPutResultSet(activation, this, provider);
    }

    @Override
    public ExecRow getExecRowDefinition() throws StandardException {
        SpliceLogUtils.trace(LOG, "getExecRowDefinition");
        JoinUtils.getMergedRow(((SpliceOperation) this.leftResultSet).getExecRowDefinition(), ((SpliceOperation) this.rightResultSet).getExecRowDefinition(), wasRightOuterJoin, rightNumCols, leftNumCols, mergedRow);
        return mergedRow;
    }

    @Override
    public List<NodeType> getNodeTypes() {
        SpliceLogUtils.trace(LOG, "getNodeTypes");
        return nodeTypes;
    }

    @Override
    public SpliceOperation getLeftOperation() {
        SpliceLogUtils.trace(LOG, "getLeftOperation");
        return leftResultSet;
    }

    protected class BroadcastNextRowIterator implements Iterator<ExecRow> {
        protected ExecRow leftRow;
        protected Iterator<ExecRow> rightSideIterator = null;
        protected KeyMarshall leftKeyEncoder = KeyType.BARE;
        protected MultiFieldEncoder keyEncoder = MultiFieldEncoder.create(leftNumCols);

        public BroadcastNextRowIterator(ExecRow leftRow) throws StandardException {
            this.leftRow = leftRow;
            keyEncoder.reset();
            leftKeyEncoder.encodeKey(leftRow.getRowArray(),leftHashKeys,null,null,keyEncoder);
            List<ExecRow> rows = rightSideMap.get(ByteBuffer.wrap(keyEncoder.build()));
            if (rows != null) {
                if (!notExistsRightSide) {
                    // Sorry for the double negative: only populate the iterator if we're not executing an antijoin
                    rightSideIterator = rows.iterator();
                }
            } else if (isOuterJoin || notExistsRightSide) {
                rightSideIterator = Iterators.singletonIterator(getEmptyRow());
            }
        }

        @Override
        public boolean hasNext() {
            if (rightSideIterator != null && rightSideIterator.hasNext()) {
                mergedRow = JoinUtils.getMergedRow(leftRow, rightSideIterator.next(), wasRightOuterJoin, rightNumCols, leftNumCols, mergedRow);
                setCurrentRow(mergedRow);
                currentRowLocation = new HBaseRowLocation(SpliceUtils.getUniqueKey());
                SpliceLogUtils.trace(LOG, "current row returned %s", currentRow);
                return true;
            }
            return false;
        }

        @Override
        public ExecRow next() {
            SpliceLogUtils.trace(LOG, "next row=" + mergedRow);
            return mergedRow;
        }

        @Override
        public void remove() {
            throw new RuntimeException("Cannot Be Removed - Not Implemented!");
        }
    }

    protected ExecRow getEmptyRow() throws StandardException{
        throw new RuntimeException("Should only be called on outer joins");
    }

    private Map<ByteBuffer, List<ExecRow>> retrieveRightSideCache() throws StandardException {
        try {
            // Cache population is what we want here concurrency-wise: only one Callable will be invoked to
            // populate the cache for a given key; any other concurrent .get(k, callable) calls will block
            return broadcastJoinCache.get(Bytes.mapKey(uniqueSequenceID), new Callable<Map<ByteBuffer, List<ExecRow>>>() {
                @Override
                public Map<ByteBuffer, List<ExecRow>> call() throws Exception {
                    SpliceLogUtils.trace(LOG, "Load right-side cache for BroadcastJoin, uniqueSequenceID " + uniqueSequenceID);
                    return loadRightSide();
                }
            });
        } catch (Exception e) {
            throw StandardException.newException(MessageId.SPLICE_GENERIC_EXCEPTION, e,
                    "Problem loading right-hand cache for BroadcastJoin, uniqueSequenceID " + uniqueSequenceID);
        }
    }

    private Map<ByteBuffer, List<ExecRow>> loadRightSide() throws StandardException, IOException {
        ByteBuffer hashKey;
        List<ExecRow> rows;
        Map<ByteBuffer, List<ExecRow>> cache = new HashMap<ByteBuffer, List<ExecRow>>();
        KeyMarshall hasher = KeyType.BARE;
        NoPutResultSet resultSet = rightResultSet.executeScan();
        resultSet.openCore();
        MultiFieldEncoder keyEncoder = MultiFieldEncoder.create(rightNumCols);
        keyEncoder.mark();

        while ((rightRow = resultSet.getNextRowCore()) != null) {
            keyEncoder.reset();
            hasher.encodeKey(rightRow.getRowArray(),rightHashKeys,null,null,keyEncoder);
            hashKey = ByteBuffer.wrap(keyEncoder.build());
            if ((rows = cache.get(hashKey)) != null) {
                // Only add additional row for same hash if we need it
                if (!oneRowRightSide) {
                    rows.add(rightRow.getClone());
                }
            } else {
                rows = new ArrayList<ExecRow>();
                rows.add(rightRow.getClone());
                cache.put(hashKey, rows);
            }
        }
        return Collections.unmodifiableMap(cache);
    }

}
