package com.splicemachine.si.impl;

import com.splicemachine.si.api.TransactionManager;
import com.splicemachine.si.api.TxnAccess;
import com.splicemachine.si.api.TxnLifecycleManager;
import com.splicemachine.si.data.api.AbstractClientTransactor;
import com.splicemachine.si.data.api.SDataLib;
import org.apache.hadoop.hbase.client.*;

/**
 * @author Scott Fines
 * Date: 2/13/14
 */
public class HBaseClientTransactor extends AbstractClientTransactor<Put,Get,Scan,Mutation>{
		public HBaseClientTransactor(DataStore dataStore,
																 TransactionManager control,
																 SDataLib dataLib) {
				super(dataStore,null,dataLib);
				throw new UnsupportedOperationException("REMOVE");
		}
		public HBaseClientTransactor(DataStore dataStore,
									TxnLifecycleManager control,
									SDataLib dataLib) {
			super(dataStore,control,dataLib);
		}

}
