package com.splicemachine.hbase.backup;

import com.splicemachine.derby.impl.job.coprocessor.CoprocessorJob;
import com.splicemachine.derby.impl.job.coprocessor.RegionTask;
import com.splicemachine.job.Task;
import com.splicemachine.si.api.Txn;
import org.apache.hadoop.hbase.HConstants;
import org.apache.hadoop.hbase.client.HTableInterface;
import org.apache.hadoop.hbase.util.Pair;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Map;

public class RestoreBackupJob implements CoprocessorJob {
	    private final BackupItem backupItem;
	    private final HTableInterface table;

	    public RestoreBackupJob(BackupItem backupItem, HTableInterface table) {
	        this.table = table;
	        this.backupItem = backupItem;
	    }

	    @Override
	    public Map<? extends RegionTask, Pair<byte[], byte[]>> getTasks() throws Exception {
	    	RestoreBackupTask task = new RestoreBackupTask(backupItem,getJobId());
	        return Collections.singletonMap(task,Pair.newPair(HConstants.EMPTY_START_ROW,HConstants.EMPTY_END_ROW));
	    }

	    @Override
	    public HTableInterface getTable() {
	        return table;
	    }

	    @Override
	    public String getJobId() {
	        return "restoreBackupJob-"+backupItem.getBackupTransaction().getTxnId();
	    }

	    @Override
	    public <T extends Task> Pair<T, Pair<byte[], byte[]>> resubmitTask(T originalTask, byte[] taskStartKey, byte[] taskEndKey) throws IOException {
	        return Pair.newPair(originalTask,Pair.newPair(taskStartKey,taskEndKey));
	    }

    @Override
    public byte[] getDestinationTable() {
        return table.getTableName();
    }

    @Override
    public Txn getTxn() {
        return backupItem.getBackupTransaction();
    }
	}