package com.splicemachine.derby.impl.sql.execute.operations;

import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectOutput;
import java.util.Collections;
import java.util.List;
import com.google.common.base.Strings;
import com.splicemachine.db.impl.sql.execute.TriggerInfo;
import com.splicemachine.utils.SpliceLogUtils;
import org.apache.log4j.Logger;
import com.splicemachine.db.iapi.error.StandardException;
import com.splicemachine.db.iapi.services.loader.GeneratedMethod;
import com.splicemachine.db.iapi.sql.Activation;
import com.splicemachine.db.iapi.sql.ResultColumnDescriptor;
import com.splicemachine.db.iapi.sql.ResultDescription;
import com.splicemachine.db.iapi.sql.dictionary.DataDictionary;
import com.splicemachine.db.iapi.sql.dictionary.TableDescriptor;
import com.splicemachine.db.iapi.sql.execute.ExecRow;
import com.splicemachine.db.iapi.types.DataValueDescriptor;
import com.splicemachine.db.impl.sql.execute.ValueRow;
import com.splicemachine.derby.iapi.sql.execute.SpliceOperation;
import com.splicemachine.derby.iapi.sql.execute.SpliceOperationContext;
import com.splicemachine.derby.impl.SpliceMethod;
import com.splicemachine.derby.impl.sql.execute.actions.WriteCursorConstantOperation;
import com.splicemachine.derby.impl.sql.execute.operations.iapi.DMLWriteInfo;
import com.splicemachine.derby.impl.sql.execute.operations.iapi.OperationInformation;
import com.splicemachine.pipeline.exception.Exceptions;
import static com.splicemachine.derby.impl.sql.execute.operations.DMLTriggerEventMapper.getAfterEvent;
import static com.splicemachine.derby.impl.sql.execute.operations.DMLTriggerEventMapper.getBeforeEvent;


/**
 *
 * @author Scott Fines
 *
 */
public abstract class DMLWriteOperation extends SpliceBaseOperation {
		private static final long serialVersionUID = 2l;
		private static final Logger LOG = Logger.getLogger(DMLWriteOperation.class);
		protected SpliceOperation source;
		public SpliceOperation savedSource;
		protected long heapConglom;
		protected DataDictionary dd;
		protected TableDescriptor td;
		private boolean isScan = true;
		protected DMLWriteInfo writeInfo;
        private TriggerHandler triggerHandler;
        private SpliceMethod<ExecRow> generationClauses;
        private String generationClausesFunMethodName;
        private SpliceMethod<ExecRow> checkGM;
        private String checkGMFunMethodName;


    public DMLWriteOperation(){
				super();
		}

		public DMLWriteOperation(SpliceOperation source, Activation activation) throws StandardException{
				super(activation, -1, 0d, 0d);
				this.source = source;
				this.activation = activation;
				this.writeInfo = new DerbyDMLWriteInfo();
				try {
						init(SpliceOperationContext.newContext(activation));
				} catch (IOException e) {
						throw Exceptions.parseException(e);
				}

		}

		public DMLWriteOperation(SpliceOperation source,
														 GeneratedMethod generationClauses,
														 GeneratedMethod checkGM,
														 Activation activation) throws StandardException{
				this(source, activation);

            if(generationClauses != null) {
                this.generationClausesFunMethodName = generationClauses.getMethodName();
                this.generationClauses = new SpliceMethod<>(generationClausesFunMethodName, activation);
            }
            if (checkGM != null) {
                this.checkGMFunMethodName = checkGM.getMethodName();
                this.checkGM = new SpliceMethod<>(checkGMFunMethodName, activation);
            }

        }

		DMLWriteOperation(SpliceOperation source,
											OperationInformation opInfo,
											DMLWriteInfo writeInfo) throws StandardException{
				super(opInfo);
				this.writeInfo = writeInfo;
				this.source = source;
		}

		@Override
		public void readExternal(ObjectInput in) throws IOException,
						ClassNotFoundException {
				super.readExternal(in);
				source = (SpliceOperation)in.readObject();
				writeInfo = (DMLWriteInfo)in.readObject();
            generationClausesFunMethodName = readNullableString(in);
            checkGMFunMethodName = readNullableString(in);
            heapConglom = in.readLong();
		}

		@Override
		public void writeExternal(ObjectOutput out) throws IOException {
				super.writeExternal(out);
				out.writeObject(source);
				out.writeObject(writeInfo);
            writeNullableString(generationClausesFunMethodName, out);
            writeNullableString(checkGMFunMethodName, out);
            out.writeLong(heapConglom);
		}

		@Override
		public void init(SpliceOperationContext context) throws StandardException, IOException {
				SpliceLogUtils.trace(LOG, "DMLWriteOperation#init");
				super.init(context);
				source.init(context);
				writeInfo.initialize(context);

            WriteCursorConstantOperation constantAction = (WriteCursorConstantOperation) writeInfo.getConstantAction();

            TriggerInfo triggerInfo = constantAction.getTriggerInfo();

            if(this.triggerHandler == null && triggerInfo != null) {
                this.triggerHandler = new TriggerHandler(
                        triggerInfo,
                        writeInfo,
                        getActivation(),
                        getBeforeEvent(getClass()),
                        getAfterEvent(getClass())
                );
            }
		}

    public byte[] getDestinationTable(){
				return Long.toString(heapConglom).getBytes();
		}

		@Override
		public SpliceOperation getLeftOperation() {
				return source;
		}

		@Override
		public List<SpliceOperation> getSubOperations() {
				return Collections.singletonList(source);
		}

		@Override
		public ExecRow getExecRowDefinition() throws StandardException {
				/*
				 * Typically, we just call down to our source and then pass that along
				 * unfortunately, with autoincrement columns this can lead to a
				 * StackOverflow, so we can't do that(see DB-1098 for more info)
				 *
				 * Luckily, DML operations are the top of their stack, so we can
				 * just form our exec row from our result description.
				 */
				ResultDescription description = writeInfo.getResultDescription();
				ResultColumnDescriptor[] rcd = description.getColumnInfo();
				DataValueDescriptor[] dvds = new DataValueDescriptor[rcd.length];
				for(int i=0;i<rcd.length;i++){
						dvds[i] = rcd[i].getType().getNull();
				}
				ExecRow row = new ValueRow(dvds.length);
				row.setRowArray(dvds);
				SpliceLogUtils.trace(LOG,"execRowDefinition=%s",row);
				return row;
		}

		public String prettyPrint(int indentLevel) {
				String indent = "\n"+ Strings.repeat("\t",indentLevel);

				return indent + "resultSetNumber:" + resultSetNumber + indent
								+ "heapConglom:" + heapConglom + indent
								+ "isScan:" + isScan + indent
								+ "writeInfo:" + writeInfo + indent
								+ "source:" + source.prettyPrint(indentLevel + 1);
		}

		@Override
		public int[] getRootAccessedCols(long tableNumber) throws StandardException{
				return source.getRootAccessedCols(tableNumber);
		}

		@Override
		public boolean isReferencingTable(long tableNumber) {
				return source.isReferencingTable(tableNumber);
		}

        public void fireBeforeStatementTriggers () throws StandardException {
            if (triggerHandler != null)
                triggerHandler.fireBeforeStatementTriggers();
        }

        public void fireAfterStatementTriggers () throws StandardException {
            if (triggerHandler != null)
               triggerHandler.fireAfterStatementTriggers();
        }

        public void fireBeforeRowTriggers() throws StandardException {
                TriggerHandler.fireBeforeRowTriggers(triggerHandler,getCurrentRow());
        }
        @Override
        public TriggerHandler getTriggerHandler() {
            return triggerHandler;
        }
    /**
     * Compute the generation clauses, if any, on the current row in order to fill in
     * computed columns.
     *
     * @param newRow the base row being evaluated
     */
    public void evaluateGenerationClauses(ExecRow newRow) throws StandardException {
        if (generationClausesFunMethodName == null && checkGMFunMethodName == null)
            return;
        if (generationClausesFunMethodName != null) {
            if (generationClauses == null)
                this.generationClauses = new SpliceMethod<>(generationClausesFunMethodName, activation);
        }
        if (checkGMFunMethodName != null) {
            if (checkGM == null)
                this.checkGM = new SpliceMethod<>(checkGMFunMethodName, activation);
        }
        ExecRow oldRow = (ExecRow) activation.getCurrentRow(source.resultSetNumber());
        //
        // The generation clause may refer to other columns in this row.
        //
        try {
            source.setCurrentRow(newRow);
            // this is where the magic happens
            if (generationClausesFunMethodName != null)
                generationClauses.invoke();
            if (checkGMFunMethodName != null)
                checkGM.invoke();
        } finally {
            //
            // We restore the Activation to its state before we ran the generation
            // clause. This may not be necessary but I don't understand all of
            // the paths through the Insert and Update operations. This
            // defensive coding seems prudent to me.
            //
            if (oldRow == null) {
                source.clearCurrentRow();
            } else {
                source.setCurrentRow(oldRow);
            }
        }
    }
}
