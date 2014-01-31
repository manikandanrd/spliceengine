package com.splicemachine.hbase.writer;

import com.carrotsearch.hppc.IntArrayList;
import com.carrotsearch.hppc.IntObjectOpenHashMap;
import com.carrotsearch.hppc.procedures.IntObjectProcedure;
import com.esotericsoftware.kryo.io.Input;
import com.esotericsoftware.kryo.io.Output;

import java.io.Externalizable;
import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectOutput;

/**
 * @author Scott Fines
 * Created on: 8/8/13
 */
public class BulkWriteResult implements Externalizable {
    private IntArrayList notRunRows;
		private IntObjectOpenHashMap<WriteResult> failedRows;

    public BulkWriteResult() {
        notRunRows = new IntArrayList();
        failedRows = new IntObjectOpenHashMap<WriteResult>();
    }

		public BulkWriteResult(IntArrayList notRunRows,IntObjectOpenHashMap<WriteResult> failedRows){
				this.notRunRows = notRunRows;
				this.failedRows = failedRows;
		}

    public IntObjectOpenHashMap<WriteResult> getFailedRows() {
        return failedRows;
    }

    public IntArrayList getNotRunRows() {
        return notRunRows;
    }

    public void addResult(int pos, WriteResult result) {
        if(result.getCode()== WriteResult.Code.SUCCESS) return; //nothing to do
        switch (result.getCode()) {
            case FAILED:
            case WRITE_CONFLICT:
            case PRIMARY_KEY_VIOLATION:
            case UNIQUE_VIOLATION:
            case FOREIGN_KEY_VIOLATION:
            case CHECK_VIOLATION:
            case NOT_SERVING_REGION:
            case WRONG_REGION:
                failedRows.put(pos,result);
                break;
            case NOT_RUN:
                notRunRows.add(pos);
                break;
            default:
                //no-op
        }
    }

    @Override
    public String toString() {
        return "BulkWriteResult{" +
                "notRunRows=" + notRunRows.size() +
                ", failedRows=" + failedRows.size() +
                '}';
    }

		@Override
		public void writeExternal(final ObjectOutput out) throws IOException {
				out.writeInt(notRunRows.size());

				int size = notRunRows.size();
				int[] notRunBuffer = notRunRows.buffer;
				for(int i=0;i<size;i++){
						int row = notRunBuffer[i];
						out.writeInt(row);
				}

				out.writeInt(failedRows.size());
				failedRows.forEach(new IntObjectProcedure<WriteResult>() {
						@Override
						public void apply(int key, WriteResult value) {
								try {
										out.writeInt(key);
										out.writeBoolean(value!=null);
										if(value!=null)
												out.writeObject(value);
								} catch (IOException e) {
										throw new RuntimeException(e); //shouldn't happen, because we only go to byte[]
								}
						}
				});
		}

		@Override
		public void readExternal(ObjectInput in) throws IOException, ClassNotFoundException {
				int notRunSize = in.readInt();
				notRunRows = IntArrayList.newInstanceWithCapacity(notRunSize);
				for(int i=0;i<notRunSize;i++){
						notRunRows.add(in.readInt());
				}

				int failedSize = in.readInt();
				failedRows = new IntObjectOpenHashMap<WriteResult>(failedSize);
				for(int i=0;i<failedSize;i++){
						int rowNum = in.readInt();
						if(in.readBoolean()){
								failedRows.put(rowNum,(WriteResult)in.readObject());
						}else{
								failedRows.put(rowNum,new WriteResult(WriteResult.Code.FAILED, "Unknown exception"));
						}
				}
		}

		public byte[] toBytes() throws IOException {
//				ByteArrayOutputStream baos = new ByteArrayOutputStream();
//				CompressionCodec codec = SpliceUtils.getSnappyCodec();
//				baos.write(Encoding.encode(codec!=null));
//
//				OutputStream os = codec==null?baos:codec.createOutputStream(baos);
				final Output out = new Output(1024,-1);
//				out.setOutputStream(os);
				int size = notRunRows.size();
				int[] notRunBuffer = notRunRows.buffer;
				out.writeInt(size);
				for(int i=0;i<size;i++){
						int row = notRunBuffer[i];
						out.writeInt(row);
				}
				out.writeInt(failedRows.size());

				failedRows.forEach(new IntObjectProcedure<WriteResult>() {
						@Override
						public void apply(int key, WriteResult value) {
								try {
										out.writeInt(key);
										if(value!=null)
												value.write(out);
								} catch (IOException e) {
										throw new RuntimeException(e); //shouldn't happen, because we only go to byte[]
								}
						}
				});
				out.flush();
				return out.toBytes();
		}

		public static BulkWriteResult fromBytes(byte[] bytes) throws IOException {
//				boolean encoded = Encoding.decodeBoolean(bytes);
//				InputStream is = new ByteArrayInputStream(bytes,1,bytes.length);
//				is = encoded?SpliceUtils.getSnappyCodec().createInputStream(is):is;

				Input input = new Input(bytes);
				int notRunSize = input.readInt();
				IntArrayList notRunRows = IntArrayList.newInstanceWithCapacity(notRunSize);
				for(int i=0;i<notRunSize;i++){
						notRunRows.add(input.readInt());
				}

				int failedSize = input.readInt();
				IntObjectOpenHashMap<WriteResult> failedRows = new IntObjectOpenHashMap<WriteResult>(failedSize);
				for(int i=0;i<failedSize;i++){
						failedRows.put(input.readInt(), WriteResult.fromBytes(input));
				}
				return new BulkWriteResult(notRunRows,failedRows);
		}
}
