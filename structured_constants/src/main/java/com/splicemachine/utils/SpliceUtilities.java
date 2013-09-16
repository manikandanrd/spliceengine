package com.splicemachine.utils;

import java.io.IOException;

import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.HColumnDescriptor;
import org.apache.hadoop.hbase.HTableDescriptor;
import org.apache.hadoop.hbase.MasterNotRunningException;
import org.apache.hadoop.hbase.ZooKeeperConnectionException;
import org.apache.hadoop.hbase.client.HBaseAdmin;
import org.apache.hadoop.hbase.client.HTableInterface;
import org.apache.hadoop.hbase.io.hfile.Compression;
import org.apache.hadoop.hbase.regionserver.StoreFile;
import org.apache.hadoop.hbase.regionserver.StoreFile.BloomType;
import org.apache.log4j.Logger;

import com.google.common.io.Closeables;
import com.splicemachine.constants.SIConstants;
import com.splicemachine.constants.SpliceConstants;

public class SpliceUtilities extends SIConstants {
	private static final Logger LOG = Logger.getLogger(SpliceUtilities.class);
    private static byte[][] PREFIXES;
	
	public static HBaseAdmin getAdmin() {
		try {
			return new HBaseAdmin(config);
		} catch (MasterNotRunningException e) {
			throw new RuntimeException(e);
		} catch (ZooKeeperConnectionException e) {
			throw new RuntimeException(e);
		}
	}

	public static Configuration getConfig() {
		return config;
	}
	
	public static HBaseAdmin getAdmin(Configuration configuration) {
		try {
			return new HBaseAdmin(configuration);
		} catch (MasterNotRunningException e) {
			throw new RuntimeException(e);
		} catch (ZooKeeperConnectionException e) {
			throw new RuntimeException(e);
		}
	}

	

	public static HTableDescriptor generateDefaultSIGovernedTable(String tableName) {
		HTableDescriptor desc = new HTableDescriptor(tableName);
		desc.addFamily(createDataFamily());
        desc.addFamily(createTransactionFamily());
        return desc;
	}
	
	public static HTableDescriptor generateNonSITable(String tableName) {
		HTableDescriptor desc = new HTableDescriptor(tableName);
		desc.addFamily(createDataFamily());
        return desc;
	}
	
	public static HTableDescriptor generateTransactionTable(String tableName) {
            HTableDescriptor desc = new HTableDescriptor(SpliceConstants.TRANSACTION_TABLE_BYTES);
            HColumnDescriptor columnDescriptor = new HColumnDescriptor(DEFAULT_FAMILY.getBytes());
            columnDescriptor.setMaxVersions(5);
            columnDescriptor.setCompressionType(Compression.Algorithm.valueOf(compression.toUpperCase()));
            columnDescriptor.setInMemory(DEFAULT_IN_MEMORY);
            columnDescriptor.setBlockCacheEnabled(DEFAULT_BLOCKCACHE);
            columnDescriptor.setBloomFilterType(StoreFile.BloomType.valueOf(DEFAULT_BLOOMFILTER.toUpperCase()));
            columnDescriptor.setTimeToLive(DEFAULT_TTL);
            desc.addFamily(columnDescriptor);

            return desc;
	}
	
	public static HColumnDescriptor createDataFamily() {
        HColumnDescriptor snapshot = new HColumnDescriptor(SpliceConstants.DEFAULT_FAMILY.getBytes());
        snapshot.setMaxVersions(Integer.MAX_VALUE);
        snapshot.setCompressionType(Compression.Algorithm.valueOf(compression.toUpperCase()));
        snapshot.setInMemory(DEFAULT_IN_MEMORY);
        snapshot.setBlockCacheEnabled(DEFAULT_BLOCKCACHE);
        snapshot.setBloomFilterType(BloomType.ROWCOL);
        snapshot.setTimeToLive(DEFAULT_TTL);
        return snapshot;
	}
	
    public static HColumnDescriptor createTransactionFamily() {
        final HColumnDescriptor siFamily = new HColumnDescriptor(SIConstants.SNAPSHOT_ISOLATION_FAMILY_BYTES);
        siFamily.setMaxVersions(Integer.MAX_VALUE);
        siFamily.setCompressionType(Compression.Algorithm.valueOf(compression.toUpperCase()));
        siFamily.setInMemory(true);
        siFamily.setBlockCacheEnabled(DEFAULT_BLOCKCACHE);
        siFamily.setBloomFilterType(BloomType.ROWCOL); //SI Column Family should use BloomFilters
        siFamily.setTimeToLive(DEFAULT_TTL);
        return siFamily;
    }

    public static void refreshHbase() {
    	 SpliceLogUtils.info(LOG, "Refresh HBase");
         HBaseAdmin admin = null;
         try{
             admin = getAdmin();
             HTableDescriptor[] descriptors = admin.listTables();
             for (HTableDescriptor desc : descriptors) {
            	 admin.deleteTable(desc.getName());
             }
         }catch(Exception e){
             SpliceLogUtils.error(LOG,"Unable to Refresh Hbase",e);
         }finally{
         	Closeables.closeQuietly(admin);
         }
    }
    
    public static boolean createSpliceHBaseTables () {
        SpliceLogUtils.info(LOG, "Creating Splice Required HBase Tables");
        HBaseAdmin admin = null;
        
        try{
            admin = getAdmin();
            if(!admin.tableExists(TEMP_TABLE_BYTES)){
                createTempTable(admin);
            }
            if(!admin.tableExists(SpliceConstants.TRANSACTION_TABLE_BYTES)){
                HTableDescriptor td = generateTransactionTable(TRANSACTION_TABLE);
                admin.createTable(td);
                SpliceLogUtils.info(LOG, SpliceConstants.TRANSACTION_TABLE_BYTES+" created");
            }

            if(!admin.tableExists(SpliceConstants.CONGLOMERATE_TABLE_NAME_BYTES)){
                HTableDescriptor td = generateDefaultSIGovernedTable(CONGLOMERATE_TABLE_NAME);
                admin.createTable(td);
                SpliceLogUtils.info(LOG, SpliceConstants.CONGLOMERATE_TABLE_NAME_BYTES+" created");
            }

            /*
             * We have to have a special table to hold our Sequence values, because we shouldn't
             * manage sequential generators transactionally.
             */
            if(!admin.tableExists(SpliceConstants.SEQUENCE_TABLE_NAME_BYTES)){
                HTableDescriptor td = generateNonSITable(SEQUENCE_TABLE_NAME);
                admin.createTable(td);
                SpliceLogUtils.info(LOG,SpliceConstants.SEQUENCE_TABLE_NAME_BYTES+" created");
            }

            
            return true;
        }catch(Exception e){
            SpliceLogUtils.error(LOG,"Unable to set up HBase Tables",e);
            return false;
        }finally{
        	Closeables.closeQuietly(admin);
        }
    }

    public static void createTempTable(HBaseAdmin admin) throws IOException {
        HTableDescriptor td = generateDefaultSIGovernedTable(TEMP_TABLE);
        byte[][] prefixes = getAllPossibleBucketPrefixes();
        byte[][] splitKeys = new byte[prefixes.length - 1][];
        System.arraycopy(prefixes, 1, splitKeys, 0, prefixes.length - 1);
        admin.createTable(td, splitKeys);
        SpliceLogUtils.info(LOG, SpliceConstants.TEMP_TABLE+" created");
    }

    static {
        PREFIXES = new byte[16][];
        for (int i = 0; i < 16; i++) {
            PREFIXES[i] = new byte[] { (byte) ( i * 0x10 ) };
        }
    }

    public static byte[][] getAllPossibleBucketPrefixes() {
        return PREFIXES;
    }

    public static void closeHTableQuietly(HTableInterface table) {
		try {
			if (table != null)
				table.close();
		} catch (Exception e) {
			SpliceLogUtils.error(LOG, e);
		}
	}
    
}
