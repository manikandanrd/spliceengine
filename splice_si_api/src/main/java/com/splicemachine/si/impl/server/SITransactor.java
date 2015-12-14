package com.splicemachine.si.impl.server;

import com.carrotsearch.hppc.IntObjectOpenHashMap;
import com.carrotsearch.hppc.LongOpenHashSet;
import com.carrotsearch.hppc.cursors.IntObjectCursor;
import com.google.common.base.Predicate;
import com.google.common.collect.Collections2;
import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.splicemachine.kvpair.KVPair;
import com.splicemachine.si.api.data.*;
import com.splicemachine.si.api.filter.TxnFilter;
import com.splicemachine.si.api.server.ConstraintChecker;
import com.splicemachine.si.api.readresolve.RollForward;
import com.splicemachine.si.api.server.Transactor;
import com.splicemachine.si.api.txn.ConflictType;
import com.splicemachine.si.api.txn.Txn;
import com.splicemachine.si.api.txn.TxnSupplier;
import com.splicemachine.si.api.txn.TxnView;
import com.splicemachine.si.constants.SIConstants;
import com.splicemachine.primitives.Bytes;
import com.splicemachine.si.impl.ConflictResults;
import com.splicemachine.si.impl.DataStore;
import com.splicemachine.si.impl.SimpleTxnFilter;
import com.splicemachine.si.impl.driver.SIDriver;
import com.splicemachine.si.impl.readresolve.NoOpReadResolver;
import com.splicemachine.si.impl.store.IgnoreTxnCacheSupplier;
import com.splicemachine.utils.Pair;
import com.splicemachine.utils.SpliceLogUtils;
import org.apache.log4j.Logger;
import javax.annotation.Nullable;
import java.io.IOException;
import java.lang.reflect.Array;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import static com.splicemachine.si.constants.SIConstants.SNAPSHOT_ISOLATION_FK_COUNTER_COLUMN_BYTES;
import static com.splicemachine.si.constants.SIConstants.DEFAULT_FAMILY_BYTES;
import static com.splicemachine.si.constants.SIConstants.PACKED_COLUMN_BYTES;
import static com.splicemachine.si.constants.SIConstants.SNAPSHOT_ISOLATION_COMMIT_TIMESTAMP_COLUMN_BYTES;
import static com.splicemachine.si.constants.SIConstants.SNAPSHOT_ISOLATION_TOMBSTONE_COLUMN_BYTES;

/**
 * Central point of implementation of the "snapshot isolation" MVCC algorithm that provides transactions across atomic
 * row updates in the underlying store. This is the core brains of the SI logic.
 */
@SuppressWarnings("unchecked")
public class SITransactor<OperationWithAttributes,Data,Delete extends OperationWithAttributes,
        Get extends OperationWithAttributes,Filter,
        Mutation extends OperationWithAttributes,OperationStatus,
        Put extends OperationWithAttributes,RegionScanner,Result,ReturnCode, RowLock, Scan extends OperationWithAttributes,Table>
        implements Transactor<Mutation,OperationStatus,Put,RowLock,Table> {
    private static final Logger LOG = Logger.getLogger(SITransactor.class);
    private final SDataLib<OperationWithAttributes,Data,Delete,Filter,Get,
            Put,RegionScanner,Result,Scan> dataLib = SIDriver.getDataLib();
    private final STableWriter<Delete,Mutation,OperationStatus,Put,RowLock,Table> tableWriter = SIDriver.getTableWriter();
    private final DataStore<OperationWithAttributes,Data,Delete,Filter,
                Get,Mutation,OperationStatus,
                Put,RegionScanner,Result,ReturnCode,RowLock,Scan,Table> dataStore = SIDriver.getDataStore();
    private final TxnOperationFactory<OperationWithAttributes,Delete,
            Get,Mutation,Put,Scan> txnOperationFactory = SIDriver.getTxnOperationFactory();
    private final TxnSupplier txnSupplier = SIDriver.getTxnSupplier();
    private final IgnoreTxnCacheSupplier ignoreTxnCacheSupplier = SIDriver.getIgnoreTxnCacheSupplier();
    private final SOperationStatusLib<OperationStatus> operationStatusLib = SIDriver.getOperationStatusLib();
    private final STableReader<Table, Get, Scan,Result> tableReader = SIDriver.getTableReader();
    private final SReturnCodeLib<ReturnCode> returnCodeLib = SIDriver.getReturnCodeLib();
    private final SExceptionLib exceptionLib = SIDriver.getExceptionLib();

    public SITransactor() {
    }

    // Operation pre-processing. These are to be called "server-side" when we are about to process an operation.

    // Process update operations

    @Override
    public boolean processPut(Table table, RollForward rollForwardQueue, Put put) throws IOException {
        if (!isFlaggedForSITreatment(put)) return false;
        final Put[] mutations = (Put[]) Array.newInstance(put.getClass(), 1);
        mutations[0] = put;
        OperationStatus[] operationStatuses = processPutBatch(table, rollForwardQueue, mutations);
        return operationStatusLib.processPutStatus(operationStatuses[0]);
    }

    @Override
    public OperationStatus[] processPutBatch(Table table, RollForward rollForwardQueue, Put[] mutations)
            throws IOException {
        if (mutations.length == 0) {
            //short-circuit special case of empty batch
            //noinspection unchecked
            return dataStore.writeBatch(table, new Pair[0]);
        }
        /*
         * Here we convert a Put into a KVPair.
         *
         * Each Put represents a single row, but a KVPair represents a single column. Each row
         * is written with a single transaction.
         *
         * What we do here is we group up the puts by their Transaction id (just in case they are different),
         * then we group them up by family and column to create proper KVPair groups. Then, we attempt
         * to write all the groups in sequence.
         *
         * Note the following:
         *
         * 1) We do all this as support for things that probably don't happen. With Splice's Packed Row
         * Encoding, it is unlikely that people will send more than a single column of data over each
         * time. Additionally, people likely won't send over a batch of Puts that have more than one
         * transaction id (as that would be weird). Still, better safe than sorry.
         *
         * 2). This method is, because of all the regrouping and the partial writes and stuff,
         * Significantly slower than the equivalent KVPair method, so It is highly recommended that you
         * use the BulkWrite pipeline along with the KVPair abstraction to improve your overall throughput.
         *
         *
         * To be frank, this is only here to support legacy code without needing to rewrite everything under
         * the sun. You should almost certainly NOT use it.
         */
        Map<Long, Map<byte[], Map<byte[], List<KVPair>>>> kvPairMap = Maps.newHashMap();
        for (Put mutation : mutations) {
            long txnId = txnOperationFactory.fromWrites(mutation).getTxnId();
            boolean isDelete = dataStore.getDeletePutAttribute(mutation);
            byte[] row = dataLib.getPutKey(mutation);
            Iterable<Data> dataValues = dataLib.listPut(mutation);
            boolean isSIDataOnly = true;
            for (Data data : dataValues) {
                byte[] family = dataLib.getDataFamily(data);
                byte[] column = dataLib.getDataQualifier(data);
                if (!Bytes.equals(column, SIConstants.PACKED_COLUMN_BYTES)) {
                    continue; //skip SI columns
                }

                isSIDataOnly = false;
                byte[] value = dataLib.getDataValue(data);
                Map<byte[], Map<byte[], List<KVPair>>> familyMap = kvPairMap.get(txnId);
                if (familyMap == null) {
                    familyMap = Maps.newTreeMap(Bytes.BASE_COMPARATOR);
                    kvPairMap.put(txnId, familyMap);
                }
                Map<byte[], List<KVPair>> columnMap = familyMap.get(family);
                if (columnMap == null) {
                    columnMap = Maps.newTreeMap(Bytes.BASE_COMPARATOR);
                    familyMap.put(family, columnMap);
                }
                List<KVPair> kvPairs = columnMap.get(column);
                if (kvPairs == null) {
                    kvPairs = Lists.newArrayList();
                    columnMap.put(column, kvPairs);
                }
                kvPairs.add(new KVPair(row, value, isDelete ? KVPair.Type.DELETE : KVPair.Type.INSERT));
            }
            if (isSIDataOnly) {
                /*
                 * Someone attempted to write only SI data, which means that the values column is empty.
                 * Put a KVPair which is an empty byte[] for all the columns in the data
                 */
                byte[] family = DEFAULT_FAMILY_BYTES;
                byte[] column = PACKED_COLUMN_BYTES;
                byte[] value = new byte[]{};
                Map<byte[], Map<byte[], List<KVPair>>> familyMap = kvPairMap.get(txnId);
                if (familyMap == null) {
                    familyMap = Maps.newTreeMap(Bytes.BASE_COMPARATOR);
                    kvPairMap.put(txnId, familyMap);
                }
                Map<byte[], List<KVPair>> columnMap = familyMap.get(family);
                if (columnMap == null) {
                    columnMap = Maps.newTreeMap(Bytes.BASE_COMPARATOR);
                    familyMap.put(family, columnMap);
                }
                List<KVPair> kvPairs = columnMap.get(column);
                if (kvPairs == null) {
                    kvPairs = Lists.newArrayList();
                    columnMap.put(column, kvPairs);
                }
                kvPairs.add(new KVPair(row, value, isDelete ? KVPair.Type.DELETE : KVPair.Type.EMPTY_COLUMN));
            }
        }
        final Map<byte[], OperationStatus> statusMap = Maps.newTreeMap(Bytes.BASE_COMPARATOR);
        for (Map.Entry<Long, Map<byte[], Map<byte[], List<KVPair>>>> entry : kvPairMap.entrySet()) {
            long txnId = entry.getKey();
            Map<byte[], Map<byte[], List<KVPair>>> familyMap = entry.getValue();
            for (Map.Entry<byte[], Map<byte[], List<KVPair>>> familyEntry : familyMap.entrySet()) {
                byte[] family = familyEntry.getKey();
                Map<byte[], List<KVPair>> columnMap = familyEntry.getValue();
                for (Map.Entry<byte[], List<KVPair>> columnEntry : columnMap.entrySet()) {
                    byte[] qualifier = columnEntry.getKey();
                    List<KVPair> kvPairs = Lists.newArrayList(Collections2.filter(columnEntry.getValue(), new Predicate<KVPair>() {
                        @Override
                        public boolean apply(@Nullable KVPair input) {
                            assert input != null;
                            return !statusMap.containsKey(input.getRowKey()) || operationStatusLib.isSuccess(statusMap.get(input.getRowKey()));
                        }
                    }));
                    OperationStatus[] statuses = processKvBatch(table, null, family, qualifier, kvPairs, txnId, dataLib.getNoOpConstraintChecker());
                    for (int i = 0; i < statuses.length; i++) {
                        byte[] row = kvPairs.get(i).getRowKey();
                        OperationStatus status = statuses[i];
                        if (statusMap.containsKey(row)) {
                            OperationStatus oldStatus = statusMap.get(row);
                            status = getCorrectStatus(status, oldStatus);
                        }
                        statusMap.put(row, status);
                    }
                }
            }
        }
        OperationStatus[] retStatuses = operationStatusLib.getArrayOfSize(mutations.length);
        for (int i = 0; i < mutations.length; i++) {
            Put put = mutations[i];
            retStatuses[i] = statusMap.get(dataLib.getPutKey(put));
        }
        return retStatuses;
    }

    @Override
    public OperationStatus[] processKvBatch(Table table,
                                            RollForward rollForward,
                                            byte[] defaultFamilyBytes,
                                            byte[] packedColumnBytes,
                                            Collection<KVPair> toProcess,
                                            long txnId,
                                            ConstraintChecker constraintChecker) throws IOException {
        TxnView txn = txnSupplier.getTransaction(txnId);
        ensureTransactionAllowsWrites(txnId, txn);
        return processInternal(table, rollForward, txn, defaultFamilyBytes, packedColumnBytes, toProcess, constraintChecker);
    }

    @Override
    public OperationStatus[] processKvBatch(Table table,
                                            RollForward rollForwardQueue,
                                            TxnView txn,
                                            byte[] family, byte[] qualifier,
                                            Collection<KVPair> mutations,
                                            ConstraintChecker constraintChecker) throws IOException {
        ensureTransactionAllowsWrites(txn.getTxnId(), txn);
        return processInternal(table, rollForwardQueue, txn, family, qualifier, mutations, constraintChecker);
    }


    private OperationStatus getCorrectStatus(OperationStatus status, OperationStatus oldStatus) {
        return operationStatusLib.getCorrectStatus(status,oldStatus);
    }

    protected OperationStatus[] processInternal(Table table,
                                                RollForward rollForwardQueue,
                                                TxnView txn,
                                                byte[] family, byte[] qualifier,
                                                Collection<KVPair> mutations,
                                                ConstraintChecker constraintChecker) throws IOException {
//                if (LOG.isTraceEnabled()) LOG.trace(String.format("processInternal: table = %s, txnId = %s", table.toString(), txn.getTxnId()));
        OperationStatus[] finalStatus = operationStatusLib.getArrayOfSize(mutations.size());
        Pair<KVPair, SRowLock>[] lockPairs = new Pair[mutations.size()];
        TxnFilter constraintState = null;
        if (constraintChecker != null)
            constraintState = new SimpleTxnFilter(null, txn, NoOpReadResolver.INSTANCE);
        @SuppressWarnings("unchecked") final LongOpenHashSet[] conflictingChildren = new LongOpenHashSet[mutations.size()];
        try {
            lockRows(table, mutations, lockPairs, finalStatus);

                        /*
                         * You don't need a low-level operation check here, because this code can only be called from
                         * 1 of 2 paths (bulk write pipeline and SIObserver). Since both of those will externally ensure that
                         * the region can't close until after this method is complete, we don't need the calls.
                         */
            IntObjectOpenHashMap<Pair<Mutation, SRowLock>> writes = checkConflictsForKvBatch(table, rollForwardQueue, lockPairs,
                    conflictingChildren, txn, family, qualifier, constraintChecker, constraintState, finalStatus);

            //TODO -sf- this can probably be made more efficient
            //convert into array for usefulness
            Pair[] toWrite = new Pair[writes.size()];
            int i = 0;
            for (IntObjectCursor<Pair<Mutation, SRowLock>> write : writes) {
                toWrite[i] = write.value;
                i++;
            }
            final OperationStatus[] status = dataStore.writeBatch(table, toWrite);

            resolveConflictsForKvBatch(table, toWrite, conflictingChildren, status);

            //convert the status back into the larger array
            i = 0;
            for (IntObjectCursor<Pair<Mutation, SRowLock>> write : writes) {
                finalStatus[write.key] = status[i];
                i++;
            }
            return finalStatus;
        } finally {
            releaseLocksForKvBatch(lockPairs);
        }
    }

    @SuppressWarnings("UnusedDeclaration")
    private void checkPermission(Table table, Mutation[] mutations) throws IOException {
        final String tableName = dataStore.getTableName(table);
        throw new UnsupportedOperationException("IMPLEMENT");
    }

    private void releaseLocksForKvBatch(Pair<KVPair, SRowLock>[] locks) {
        if (locks == null) return;
        for (Pair<KVPair, SRowLock> lock : locks) {
            if (lock == null || lock.getSecond() == null) continue;
            lock.getSecond().unlock();
        }
    }

    private void resolveConflictsForKvBatch(Table table,
                                            Pair<Mutation, SRowLock>[] mutations,
                                            LongOpenHashSet[] conflictingChildren,
                                            OperationStatus[] status) {
        for (int i = 0; i < mutations.length; i++) {
            try {
                Put put = (Put) mutations[i].getFirst();
                SRowLock lock = mutations[i].getSecond();
                resolveChildConflicts(table, put, lock, conflictingChildren[i]);
            } catch (Exception e) {
                status[i] = operationStatusLib.newFailureOperationStatus(e);
            }
        }
    }


    private IntObjectOpenHashMap<Pair<Mutation, SRowLock>> checkConflictsForKvBatch(Table table,
                                                                                    RollForward rollForwardQueue,
                                                                                    Pair<KVPair, SRowLock>[] dataAndLocks,
                                                                                    LongOpenHashSet[] conflictingChildren,
                                                                                    TxnView transaction,
                                                                                    byte[] family, byte[] qualifier,
                                                                                    ConstraintChecker constraintChecker,
                                                                                    TxnFilter constraintStateFilter,
                                                                                    OperationStatus[] finalStatus) throws IOException {
        IntObjectOpenHashMap<Pair<Mutation, SRowLock>> finalMutationsToWrite = IntObjectOpenHashMap.newInstance();
        for (int i = 0; i < dataAndLocks.length; i++) {
            Pair<KVPair, SRowLock> baseDataAndLock = dataAndLocks[i];
            if (baseDataAndLock == null) continue;

            ConflictResults conflictResults = ConflictResults.NO_CONFLICT;
            KVPair kvPair = baseDataAndLock.getFirst();
            KVPair.Type writeType = kvPair.getType();
            if (constraintChecker != null || !KVPair.Type.INSERT.equals(writeType)) {
                /*
                 *
                 * If the table has no keys, then the hbase row key is a randomly generated UUID, so it's not
                 * going to incur a write/write penalty, because there isn't any other row there (as long as we are inserting).
                 * Therefore, we do not need to perform a write/write conflict check or a constraint check
                 *
                 * We know that this is the case because there is no constraint checker (constraint checkers are only
                 * applied on key elements.
                 */
                //todo -sf remove the Row key copy here
                Result possibleConflicts = dataStore.getCommitTimestampsAndTombstonesSingle(table, kvPair.getRowKey());
                if (possibleConflicts != null) {
                    //we need to check for write conflicts
                    conflictResults = ensureNoWriteConflict(transaction, writeType, possibleConflicts);
                }
                if (applyConstraint(constraintChecker, constraintStateFilter, i, kvPair, possibleConflicts, finalStatus, conflictResults.hasAdditiveConflicts())) {
                    //filter this row out, it fails the constraint
                    continue;
                }
                //TODO -sf- if type is an UPSERT, and conflict type is ADDITIVE_CONFLICT, then we
                //set the status on the row to ADDITIVE_CONFLICT_DURING_UPSERT
                if (KVPair.Type.UPSERT.equals(writeType)) {
                    /*
                     * If the type is an upsert, then we want to check for an ADDITIVE conflict. If so,
                     * we fail this row with an ADDITIVE_UPSERT_CONFLICT.
                     */
                    if (conflictResults.hasAdditiveConflicts()) {
                        finalStatus[i] = operationStatusLib.getAdditiveWriteConflictOperationStatus();
                    }
                }
            }

            conflictingChildren[i] = conflictResults.getChildConflicts();
            Mutation mutationToRun = getMutationToRun(table, rollForwardQueue, kvPair,
                    family, qualifier, transaction, conflictResults);
            finalMutationsToWrite.put(i, Pair.newPair(mutationToRun, baseDataAndLock.getSecond()));
        }
        return finalMutationsToWrite;
    }

    private boolean applyConstraint(ConstraintChecker<OperationStatus,Result> constraintChecker,
                                    TxnFilter<Data,ReturnCode> constraintStateFilter,
                                    int rowPosition,
                                    KVPair mutation, Result row,
                                    OperationStatus[] finalStatus,
                                    boolean additiveConflict) throws IOException {
        /*
         * Attempts to apply the constraint (if there is any). When this method returns true, the row should be filtered
         * out.
         */
        if (constraintChecker == null) return false;

        int size = dataLib.getResultSize(row);

        if (row == null || size <= 0) return false; //you can't apply a constraint on a non-existent row

        //we need to make sure that this row is visible to the current transaction
        List<Data> visibleColumns = Lists.newArrayListWithExpectedSize(size);
        for (Data data : dataLib.getDataFromResult(row)) {
            ReturnCode code = constraintStateFilter.filterKeyValue(data);
            if (returnCodeLib.isNextCol(code) || returnCodeLib.isNextRow(code)|| returnCodeLib.isSeekNextUsingHint(code))
                return false;
            if (returnCodeLib.isSkip(code))
                continue;
            visibleColumns.add(data);
        }
        constraintStateFilter.nextRow();
        if (!additiveConflict && visibleColumns.size() <= 0) return false; //no visible values to check

        OperationStatus operationStatus = constraintChecker.checkConstraint(mutation, dataLib.newResult(visibleColumns));
        if (operationStatus != null && operationStatusLib.isSuccess(operationStatus)) {
            finalStatus[rowPosition] = operationStatus;
            return true;
        }
        return false;
    }


    private void lockRows(Table table, Collection<KVPair> mutations, Pair<KVPair, SRowLock>[] mutationsAndLocks, OperationStatus[] finalStatus) throws IOException {
        /*
         * We attempt to lock each row in the collection.
         *
         * If the lock is acquired, we place it into mutationsAndLocks (at the position equal
         * to the position in the collection's iterator).
         *
         * If the lock cannot be acquired, then we set NOT_RUN into the finalStatus array. Those rows will be filtered
         * out and must be retried by the writer. mutationsAndLocks at the same location will be null
         */
        int position = 0;
        for (KVPair mutation : mutations) {
            SRowLock lock = tableWriter.tryLock(table, mutation.rowKeySlice());
            if (lock != null)
                mutationsAndLocks[position] = Pair.newPair(mutation, lock);
            else
                finalStatus[position] = operationStatusLib.getNotRun();

            position++;
        }
    }


    private Mutation getMutationToRun(Table table, RollForward rollForwardQueue, KVPair kvPair,
                                      byte[] family, byte[] column,
                                      TxnView transaction, ConflictResults conflictResults) throws IOException {
        long txnIdLong = transaction.getTxnId();
//                if (LOG.isTraceEnabled()) LOG.trace(String.format("table = %s, kvPair = %s, txnId = %s", table.toString(), kvPair.toString(), txnIdLong));
        Put newPut;
        if (kvPair.getType() == KVPair.Type.EMPTY_COLUMN) {
            /*
             * WARNING: This requires a read of column data to populate! Try not to use
             * it unless no other option presents itself.
             *
             * In point of fact, this only occurs if someone sends over a non-delete Put
             * which has only SI data. In the event that we send over a row with all nulls
             * from actual Splice system, we end up with a KVPair that has a non-empty byte[]
             * for the values column (but which is nulls everywhere)
             */
            newPut = dataLib.newPut(kvPair.rowKeySlice());
            dataStore.setTombstonesOnColumns(table, txnIdLong, newPut);
        } else if (kvPair.getType() == KVPair.Type.DELETE) {
            newPut = dataLib.newPut(kvPair.rowKeySlice());
            dataStore.setTombstoneOnPut(newPut, txnIdLong);
        } else
            newPut = dataLib.toPut(kvPair, family, column, txnIdLong);

        dataStore.suppressIndexing(newPut);
//                if (LOG.isTraceEnabled()) LOG.trace(String.format("Checking for anti-tombstone condition: kvPair.type is not Delete = %s, conflictResults.hasTombstone = %s", (kvPair.getType()!= KVPair.Type.DELETE), conflictResults.hasTombstone));
        if (kvPair.getType() != KVPair.Type.DELETE && conflictResults.hasTombstone())
            dataStore.setAntiTombstoneOnPut(newPut, txnIdLong);

        if (rollForwardQueue != null)
            rollForwardQueue.submitForResolution(kvPair.rowKeySlice(), txnIdLong);
        return (Mutation)newPut;
    }

    private void resolveChildConflicts(Table table, Put put, SRowLock lock, LongOpenHashSet conflictingChildren) throws IOException {
        if (conflictingChildren != null && !conflictingChildren.isEmpty()) {
            Delete delete = dataStore.copyPutToDelete(put, conflictingChildren);
            dataStore.suppressIndexing(delete);
            tableWriter.delete(table, delete, lock);
        }
    }

    /**
     * While we hold the lock on the row, check to make sure that no transactions have updated the row since the
     * updating transaction started.
     */
    private ConflictResults ensureNoWriteConflict(TxnView updateTransaction, KVPair.Type updateType, Result result) throws IOException {

        Data commitTsKeyValue = dataLib.getColumnLatest(result, DEFAULT_FAMILY_BYTES, SNAPSHOT_ISOLATION_COMMIT_TIMESTAMP_COLUMN_BYTES);
        Data tombstoneKeyValue = dataLib.getColumnLatest(result, DEFAULT_FAMILY_BYTES, SNAPSHOT_ISOLATION_TOMBSTONE_COLUMN_BYTES);
        Data userDataKeyValue = dataLib.getColumnLatest(result, DEFAULT_FAMILY_BYTES, PACKED_COLUMN_BYTES);

        ConflictResults conflictResults = null;
        if (commitTsKeyValue != null) {
            conflictResults = checkCommitTimestampForConflict(updateTransaction, null, commitTsKeyValue);
        }
        if (tombstoneKeyValue != null) {
            long dataTransactionId = dataLib.getTimestamp(tombstoneKeyValue);
            conflictResults = checkDataForConflict(updateTransaction, conflictResults, tombstoneKeyValue, dataTransactionId);
            conflictResults = (conflictResults == null) ? new ConflictResults() : conflictResults;
            conflictResults.setHasTombstone(hasCurrentTransactionTombstone(updateTransaction, tombstoneKeyValue));
        }
        if (userDataKeyValue != null) {
            long dataTransactionId = dataLib.getTimestamp(userDataKeyValue);
            conflictResults = checkDataForConflict(updateTransaction, conflictResults, userDataKeyValue, dataTransactionId);
        }
        // FK counter -- can only conflict with DELETE
        if (updateType == KVPair.Type.DELETE) {
            Data fkCounterKeyValue = dataLib.getColumnLatest(result, DEFAULT_FAMILY_BYTES, SIConstants.SNAPSHOT_ISOLATION_FK_COUNTER_COLUMN_BYTES);
            if(fkCounterKeyValue != null) {
                long dataTransactionId = dataLib.getValueToLong(fkCounterKeyValue);
                conflictResults = checkDataForConflict(updateTransaction, conflictResults, fkCounterKeyValue, dataTransactionId);
            }
        }

        return conflictResults == null ? ConflictResults.NO_CONFLICT : conflictResults;
    }

    private boolean hasCurrentTransactionTombstone(TxnView updateTxn, Data tombstoneValue) throws IOException {
        if (tombstoneValue == null) return false; //no tombstone at all
        if (dataStore.isAntiTombstone(tombstoneValue)) return false; //actually an anti-tombstone
        TxnView tombstoneTxn = txnSupplier.getTransaction(dataLib.getTimestamp(tombstoneValue));
        return updateTxn.conflicts(tombstoneTxn) == ConflictType.NONE;
    }

    @SuppressWarnings("StatementWithEmptyBody")
    private ConflictResults checkCommitTimestampForConflict(TxnView updateTransaction,
                                                            ConflictResults conflictResults,
                                                            Data dataCommitKeyValue) throws IOException {
        final long dataTransactionId = dataLib.getTimestamp(dataCommitKeyValue);
        if (updateTransaction.getTxnId() != dataTransactionId) {
            if (dataStore.isSINull(dataCommitKeyValue)) {
                // Unknown transaction status
                final TxnView dataTransaction = txnSupplier.getTransaction(dataTransactionId);
                if (dataTransaction.getState() == Txn.State.ROLLEDBACK)
                    return conflictResults; //can't conflict with a rolled back transaction
                final ConflictType conflictType = updateTransaction.conflicts(dataTransaction);
                switch (conflictType) {
                    case CHILD:
                        if (conflictResults == null)
                            conflictResults = new ConflictResults();
                        conflictResults.addChild(dataTransactionId);
                        break;
                    case ADDITIVE:
                        if (conflictResults == null)
                            conflictResults = new ConflictResults();
                        conflictResults.addAdditive(dataTransactionId);
                        break;
                    case SIBLING:
                        if (LOG.isTraceEnabled()) {
                            SpliceLogUtils.trace(LOG, "Write conflict on row "
                                    + Bytes.toHex(dataLib.getDataRow(dataCommitKeyValue)));
                        }

                        throw exceptionLib.getWriteConflictException(dataTransactionId, updateTransaction.getTxnId());
                }
            } else if (dataStore.isSIFail(dataCommitKeyValue)) {
                // Can't conflict with failed transaction.
            } else {
                // Committed transaction
                final long dataCommitTimestamp = dataLib.getValueToLong(dataCommitKeyValue);
                if (dataCommitTimestamp > updateTransaction.getBeginTimestamp()) {
                    if (LOG.isTraceEnabled()) {
                        SpliceLogUtils.trace(LOG, "Write conflict on row "
                                + Bytes.toHex(dataLib.getDataRow(dataCommitKeyValue)));
                    }
                    throw exceptionLib.getWriteConflictException(dataTransactionId, updateTransaction.getTxnId());
                }
            }
        }
        return conflictResults;
    }

    private ConflictResults checkDataForConflict(TxnView updateTransaction,
                                                 ConflictResults conflictResults,
                                                 Data dataCommitKeyValue,
                                                 long dataTransactionId) throws IOException {

        if (updateTransaction.getTxnId() != dataTransactionId) {
            final TxnView dataTransaction = txnSupplier.getTransaction(dataTransactionId);
            if (dataTransaction.getState() == Txn.State.ROLLEDBACK) {
                return conflictResults; //can't conflict with a rolled back transaction
            }
            final ConflictType conflictType = updateTransaction.conflicts(dataTransaction);
            switch (conflictType) {
                case CHILD:
                    if (conflictResults == null) {
                        conflictResults = new ConflictResults();
                    }
                    conflictResults.addChild(dataTransactionId);
                    break;
                case ADDITIVE:
                    if (conflictResults == null) {
                        conflictResults = new ConflictResults();
                    }
                    conflictResults.addAdditive(dataTransactionId);
                    break;
                case SIBLING:
                    if (LOG.isTraceEnabled()) {
                        SpliceLogUtils.trace(LOG, "Write conflict on row "
                                + Bytes.toHex(dataLib.getDataRow(dataCommitKeyValue)));
                    }
                    throw exceptionLib.getWriteConflictException(dataTransactionId, updateTransaction.getTxnId());
            }
        }
        return conflictResults;
    }

    // Helpers

    /**
     * Is this operation supposed to be handled by "snapshot isolation".
     */
    private boolean isFlaggedForSITreatment(OperationWithAttributes operation) {
        return dataStore.getSINeededAttribute(operation) != null;
    }

    private void ensureTransactionAllowsWrites(long txnId, TxnView transaction) throws IOException {
        if (transaction == null || !transaction.allowsWrites()) {
            throw exceptionLib.getReadOnlyModificationException("transaction is read only: " + txnId);
        }
    }

    @Override
    public DataStore getDataStore() {
        return dataStore;
    }

    @Override
    public SDataLib getDataLib() {
        return dataLib;
    }

    @Override
    public void updateCounterColumn(Table hbRegion, TxnView txnView, RowLock rowLock, byte[] rowKey) throws IOException {
        // Get the current counter value.
        Get get = dataLib.newGet(rowKey);
        dataLib.addFamilyQualifierToGet(get,DEFAULT_FAMILY_BYTES, SNAPSHOT_ISOLATION_FK_COUNTER_COLUMN_BYTES);
        Result result = tableReader.get(hbRegion,get);
        long counterTransactionId = dataLib.isResultEmpty(result) ? 0L : Bytes.toLong(
                dataLib.getDataValue(dataLib.getColumnLatest(result,DEFAULT_FAMILY_BYTES, SNAPSHOT_ISOLATION_FK_COUNTER_COLUMN_BYTES)));
        // Update counter value if the calling transaction started after counter value.
        if (txnView.getTxnId() > counterTransactionId) {
            long offset = txnView.getTxnId() - counterTransactionId;
            tableWriter.increment(hbRegion, DEFAULT_FAMILY_BYTES, SNAPSHOT_ISOLATION_FK_COUNTER_COLUMN_BYTES, offset, rowLock);
        }
        // Throw WriteConflict exception if the target row has already been deleted concurrently.
        Result possibleConflicts = dataStore.getCommitTimestampsAndTombstonesSingle((Table) hbRegion, rowKey);
        ensureNoWriteConflict(txnView, KVPair.Type.FOREIGN_KEY_PARENT_EXISTENCE_CHECK, possibleConflicts);
    }
}