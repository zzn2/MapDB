/*
 *  Copyright (c) 2012 Jan Kotek
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package org.mapdb;

import java.io.File;
import java.io.IOError;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Iterator;
import java.util.Queue;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Storage Engine which saves record directly into file.
 * It has zero protection from data corruption and must be closed properly after modifications.
 * It is  used when Write-Ahead-Log transactions are disabled.
 *
 *
 * Storage format
 * ----------------
 * `StoreDirect` is composed of two files: Index file is sequence of 8-byte longs, it translates
 * `recid` (offset in index file) to record size and offset in physical file. Records position
 * may change, but it requires stable ID, so the index file is used for translation.
 * This store uses data structure called `Long Stack` to manage (and reuse) free space, it is
 * is linked LIFO queue of 8-byte longs.
 *
 * Index file
 * --------------
 * Index file is translation table between permanent record ID (recid) and mutable location in physical file.
 * Index file is sequence of 8-byte longs, one for each record. It also has some extra longs to manage
 * free space and other metainfo. Index table and physical data could be stored in single file, but
 * keeping index table separate simplifies compaction.
 *
 * Basic **structure of index file** is bellow. Each slot is 8-bytes long so `offset=slot*8`
 *
 *  slot        | in code                       | description
 *  ---         | ---                               | ---
 *  0           | {@link StoreDirect#HEADER}        | File header
 *  1           | {@link StoreDirect#IO_INDEX_SIZE} | Allocated file size of index file in bytes.
 *  2           | {@link StoreDirect#IO_PHYS_SIZE}  | Allocated file size of physical file in bytes.
 *  3           | {@link StoreDirect#IO_FREE_SIZE}  | Space occupied by free records in physical file in bytes.
 *  4..9        |                                   | Reserved for future use
 *  10..14      |                                   | For usage by user
 *  15          | {@link StoreDirect#IO_FREE_RECID} |Long Stack of deleted recids, those will be reused and returned by {@link Engine#put(Object, Serializer)}
 *  16..4111    |                                   |Long Stack of free physical records. This contains free space released by record update or delete. Each slots corresponds to free record size. TODO check 4111 is right
 *  4112        | {@link StoreDirect#IO_USER_START} |Record size and offset in physical file for recid=1
 *  4113        |                                   |Record size and offset in physical file for recid=2
 *  ...         | ...                               |... snip ...
 *  N+4111      |                                   |Record size and offset in physical file for recid=N
 *
 *
 * Long Stack
 * ------------
 * Long Stack is data structure used to store free records. It is LIFO queue which uses linked records to store 8-byte longs.
 * Long Stack is identified by slot in Index File, which stores pointer to Long Stack head.  The structure of
 * of index pointer is following:
 *
 *  byte    | description
 *  ---     |---
 *  0..1    | relative offset in head Long Stack Record to take value from. This value decreases by 8 each take
 *  2..7    | physical file offset of head Long Stack Record, zero if Long Stack is empty
 *
 * Each Long Stack Record  is sequence of 8-byte longs, first slot is header. Long Stack Record structure is following:
 *
 *  byte    | description
 *  ---     |---
 *  0..1    | length of current Long Stack Record in bytes
 *  2..7    | physical file offset of next Long Stack Record, zero of this record is last
 *  8-15    | Long Stack value
 *  16-23   | Long Stack value
 *   ...    | and so on until end of Long Stack Record
 *
 * Physical pointer
 * ----------------
 * Index slot value typically contains physical pointer (information about record location and size in physical file). First 2 bytes
 * are record size (max 65536). Then there is 6 byte offset in physical file (max store size is 281 TB).
 * Physical file offset must always be multiple of 16, so last 4 bites are used to flag extra record information.
 * Structure of **physical pointer**:
 *
 * bite     | in code                                   | description
 *   ---    | ---                                       | ---
 * 0-15     |`val>>>48`                                 | record size
 * 16-59    |`val&{@link StoreDirect#MASK_OFFSET}`      | physical offset
 * 60       |`val&{@link StoreDirect#MASK_LINKED}!=0`   | linked record flag
 * 61       |`val&{@link StoreDirect#MASK_DISCARD}!=0`  | to be discarded while storage is offline flag
 * 62       |`val&{@link StoreDirect#MASK_ARCHIVE}!=0`  | record modified since last backup flag
 * 63       |                                           | not used yet
 *
 * Records in Physical File
 * ---------------------------
 * Records are stored in physical file. Maximal record size size is 64KB, so larger records must
 * be stored in form of the linked list. Each record starts by Physical Pointer from Index File.
 * There is flag in Physical Pointer indicating if record is linked. If record is not linked you may
 * just read ByteBuffer from given size and offset.
 *
 * If record is linked, each record starts with Physical Pointer to next record. So actual data payload is record size-8.
 * The last linked record does not have the Physical Pointer header to next record, there is MASK_LINKED flag which
 * indicates if next record is the last one.
 *
 *
 * @author Jan Kotek
 */
public class StoreDirect extends Store{

    protected static final long MASK_OFFSET = 0x0000FFFFFFFFFFF0L;

    protected static final long MASK_LINKED = 0x8L;
    protected static final long MASK_DISCARD = 0x4L;
    protected static final long MASK_ARCHIVE = 0x2L;

    protected static final long HEADER = 9032094932889042394L;

    /** maximal non linked record size */
    protected static final int MAX_REC_SIZE = 65536-1;

    /** number of free physical slots */
    protected static final int PHYS_FREE_SLOTS_COUNT = 2048*2;

    /** index file offset where current size of index file is stored*/
    protected static final int IO_INDEX_SIZE = 1*8;
    /** index file offset where current size of phys file is stored */
    protected static final int IO_PHYS_SIZE = 2*8;

    /** index file offset where space occupied by free phys records is stored */
    protected static final int IO_FREE_SIZE = 3*8;

    /** index file offset where reference to longstack of free recid is stored*/
    protected static final int IO_FREE_RECID = 15*8;

    /** index file offset where first recid available to user is stored */
    protected static final int IO_USER_START = IO_FREE_RECID+PHYS_FREE_SLOTS_COUNT*8+8;

    public static final String DATA_FILE_EXT = ".p";

    protected final static int LONG_STACK_PREF_COUNT = 204;
    protected final static long LONG_STACK_PREF_SIZE = 8+LONG_STACK_PREF_COUNT*6;


    protected final ReentrantReadWriteLock[] locks = Utils.newReadWriteLocks();
    protected final ReentrantLock structuralLock = new ReentrantLock();

    protected Volume index;
    protected Volume phys;

    protected long physSize;
    protected long indexSize;
    protected long freeSize;

    protected final boolean deleteFilesAfterClose;

    protected final boolean readOnly;
    protected final boolean syncOnCommitDisabled;

    protected final boolean spaceReclaimReuse;
    protected final boolean spaceReclaimTrack;

    protected final long sizeLimit;

    protected final Queue<DataOutput2> recycledDataOuts = new ArrayBlockingQueue<DataOutput2>(128);

    public StoreDirect(Volume.Factory volFac, boolean readOnly, boolean deleteFilesAfterClose,
                       int spaceReclaimMode, boolean syncOnCommitDisabled, long sizeLimit) {
        this.readOnly = readOnly;
        this.deleteFilesAfterClose = deleteFilesAfterClose;
        this.syncOnCommitDisabled = syncOnCommitDisabled;
        this.sizeLimit = sizeLimit;

        this.spaceReclaimReuse = spaceReclaimMode>2;
        this.spaceReclaimTrack = spaceReclaimMode>0;

        index = volFac.createIndexVolume();
        phys = volFac.createPhysVolume();
        if(index.isEmpty()){
            createStructure();
        }else{
            checkHeaders();
            indexSize = index.getLong(IO_INDEX_SIZE);
            physSize = index.getLong(IO_PHYS_SIZE);
            indexSize = index.getLong(IO_FREE_SIZE);
        }

    }

    public StoreDirect(Volume.Factory volFac) {
        this(volFac, false,false,5,false,0L);
    }



    protected void checkHeaders() {
        if(index.getLong(0)!=HEADER||phys.getLong(0)!=HEADER)throw new IOError(new IOException("storage has invalid header"));
    }

    protected void createStructure() {
        indexSize = IO_USER_START+LAST_RESERVED_RECID*8+8;
        index.ensureAvailable(indexSize);
        for(int i=0;i<indexSize;i+=8) index.putLong(i,0L);
        index.putLong(0, HEADER);
        index.putLong(IO_INDEX_SIZE,indexSize);
        physSize =16;
        index.putLong(IO_PHYS_SIZE,physSize);
        phys.ensureAvailable(physSize);
        phys.putLong(0, HEADER);
        freeSize = 0;
        index.putLong(IO_FREE_SIZE,freeSize);

    }


    @Override
    public <A> long put(A value, Serializer<A> serializer) {
        DataOutput2 out = serialize(value, serializer);

        structuralLock.lock();
        final long ioRecid;
        final long[] indexVals;
        try{
            ioRecid = freeIoRecidTake(true) ;
            indexVals = physAllocate(out.pos,true);
        }finally {
            structuralLock.unlock();
        }

        put2(out, ioRecid, indexVals);
        recycledDataOuts.offer(out);
        return (ioRecid-IO_USER_START)/8;
    }

    private void put2(DataOutput2 out, long ioRecid, long[] indexVals) {
        index.putLong(ioRecid, indexVals[0]|MASK_ARCHIVE);
        //write stuff
        if(indexVals.length==1||indexVals[1]==0){ //is more then one? ie linked
            //write single

            phys.putData(indexVals[0]&MASK_OFFSET, out.buf, 0, out.pos);

        }else{
            int outPos = 0;
            //write linked
            for(int i=0;i<indexVals.length;i++){
                final int c =   i==indexVals.length-1 ? 0: 8;
                final long indexVal = indexVals[i];
                final boolean isLast = (indexVal & MASK_LINKED) ==0;
                if(isLast!=(i==indexVals.length-1)) throw new InternalError();
                final int size = (int) (indexVal>>>48);
                final long offset = indexVal&MASK_OFFSET;

                //write data
                phys.putData(offset+c,out.buf,outPos, size-c);
                outPos+=size-c;

                if(c>0){
                    //write position of next linked record
                    phys.putLong(offset, indexVals[i + 1]);
                }
            }
              if(outPos!=out.pos) throw new InternalError();
        }
    }


    @Override
    public <A> A get(long recid, Serializer<A> serializer) {
        final long ioRecid = IO_USER_START + recid*8;
        final Lock lock  = locks[Utils.longHash(recid)&Utils.LOCK_MASK].readLock();
        lock.lock();
        try{
            return get2(ioRecid,serializer);
        }catch(IOException e){
            throw new IOError(e);
        }finally{
            lock.unlock();
        }
    }

    protected <A> A get2(long ioRecid,Serializer<A> serializer) throws IOException {
        long indexVal = index.getLong(ioRecid);

        int size = (int) (indexVal>>>48);
        DataInput2 di;
        long offset = indexVal&MASK_OFFSET;
        if((indexVal& MASK_LINKED)==0){
            //read single record
            di = phys.getDataInput(offset, size);

        }else{
            //is linked, first construct buffer we will read data to
            int pos = 0;
            int c = 8;
            //TODO use mapped bb and direct copying?
            byte[] buf = new byte[64];
            //read parts into segment
            for(;;){
                DataInput2 in = phys.getDataInput(offset + c, size-c);

                if(buf.length<pos+size-c)
                    buf = ArraysCompat.copyOf(buf,Math.max(pos+size-c,buf.length*2)); //buf to small, grow
                in.readFully(buf,pos,size-c);
                pos+=size-c;
                if(c==0) break;
                //read next part
                long next = phys.getLong(offset);
                offset = next&MASK_OFFSET;
                size = (int) (next>>>48);
                //is the next part last?
                c =  ((next& MASK_LINKED)==0)? 0 : 8;
            }
            di = new DataInput2(buf);
            size = pos;
        }
        int start = di.pos;
        A ret = serializer.deserialize(di,size);
        if(size+start>di.pos)throw new InternalError("data were not fully read, check your serializier "+ioRecid);
        if(size+start<di.pos)throw new InternalError("data were read beyond record size, check your serializier");
        return ret;
    }


    @Override
    public <A> void update(long recid, A value, Serializer<A> serializer) {
        DataOutput2 out = serialize(value, serializer);

        final long ioRecid = IO_USER_START + recid*8;


        final Lock lock  = locks[Utils.longHash(recid)&Utils.LOCK_MASK].writeLock();
        lock.lock();
        try{
            long indexVal = index.getLong(ioRecid);
            long[] indexVals = spaceReclaimTrack ? getLinkedRecordsIndexVals(indexVal) : null;
            structuralLock.lock();
            try{

                if(spaceReclaimTrack){
                    //free first record pointed from indexVal
                    freePhysPut(indexVal);

                    //if there are more linked records, free those as well
                    if(indexVals!=null){
                        for(int i=0;i<indexVals.length && indexVals[i]!=0;i++){
                            freePhysPut(indexVals[i]);
                        }
                    }
                }

                indexVals = physAllocate(out.pos,true);
            }finally {
                structuralLock.unlock();
            }

            put2(out, ioRecid, indexVals);
        }finally{
            lock.unlock();
        }
        recycledDataOuts.offer(out);
    }

    @Override
    public <A> boolean compareAndSwap(long recid, A expectedOldValue, A newValue, Serializer<A> serializer) {
        final long ioRecid = IO_USER_START + recid*8;
        final Lock lock  = locks[Utils.longHash(recid)&Utils.LOCK_MASK].writeLock();
        lock.lock();

        DataOutput2 out;
        try{
            /*
             * deserialize old value
             */

            A oldVal = get2(ioRecid,serializer);

            /*
             * compare oldValue and expected
             */
            if((oldVal == null && expectedOldValue!=null) || (oldVal!=null && !oldVal.equals(expectedOldValue)))
                return false;

            /*
             * write new value
             */
             out = serialize(newValue, serializer);

            long indexVal = index.getLong(ioRecid);
            long[] indexVals = spaceReclaimTrack ? getLinkedRecordsIndexVals(indexVal) : null;

            structuralLock.lock();
            try{
                if(spaceReclaimTrack){
                    //free first record pointed from indexVal
                    freePhysPut(indexVal);

                    //if there are more linked records, free those as well
                    if(indexVals!=null){
                        for(int i=0;i<indexVals.length && indexVals[i]!=0;i++){
                            freePhysPut(indexVals[i]);
                        }
                    }
                }

                indexVals = physAllocate(out.pos,true);
            }finally {
                structuralLock.unlock();
            }

            put2(out, ioRecid, indexVals);

        }catch(IOException e){
            throw new IOError(e);
        }finally{
            lock.unlock();
        }
        recycledDataOuts.offer(out);
        return true;
    }

    @Override
    public <A> void delete(long recid, Serializer<A> serializer) {
        final long ioRecid = IO_USER_START + recid*8;
        final Lock lock  = locks[Utils.longHash(recid)&Utils.LOCK_MASK].writeLock();
        lock.lock();
        try{
            //get index val and zero it out
            final long indexVal = index.getLong(ioRecid);
            index.putLong(ioRecid,0L|MASK_ARCHIVE);

            if(!spaceReclaimTrack) return; //free space is not tracked, so do not mark stuff as free

            long[] linkedRecords = getLinkedRecordsIndexVals(indexVal);

            //now lock everything and mark free space
            structuralLock.lock();
            try{
                //free recid
                freeIoRecidPut(ioRecid);
                //free first record pointed from indexVal
                freePhysPut(indexVal);

                //if there are more linked records, free those as well
                if(linkedRecords!=null){
                    for(int i=0; i<linkedRecords.length &&linkedRecords[i]!=0;i++){
                        freePhysPut(linkedRecords[i]);
                    }
                }
            }finally {
                structuralLock.unlock();
            }

        }finally{
            lock.unlock();
        }
    }

    protected long[] getLinkedRecordsIndexVals(long indexVal) {
        long[] linkedRecords = null;

        int linkedPos = 0;
        if((indexVal& MASK_LINKED)!=0){
            //record is composed of multiple linked records, so collect all of them
            linkedRecords = new long[2];

            //traverse linked records
            long linkedVal = phys.getLong(indexVal&MASK_OFFSET);
            for(;;){
                if(linkedPos==linkedRecords.length) //grow if necessary
                    linkedRecords = ArraysCompat.copyOf(linkedRecords, linkedRecords.length * 2);
                //store last linkedVal
                linkedRecords[linkedPos] = linkedVal;

                if((linkedVal& MASK_LINKED)==0){
                    break; //this is last linked record, so break
                }
                //move and read to next
                linkedPos++;
                linkedVal = phys.getLong(linkedVal&MASK_OFFSET);
            }
        }
        return linkedRecords;
    }

    protected long[] physAllocate(int size, boolean ensureAvail) {
        if(size==0L) return new long[]{0L};
        //append to end of file
        if(size<MAX_REC_SIZE){
            long indexVal = freePhysTake(size,ensureAvail);
            indexVal |= ((long)size)<<48;
            return new long[]{indexVal};
        }else{
            long[] ret = new long[2];
            int retPos = 0;
            int c = 8;

            while(size>0){
                if(retPos == ret.length) ret = ArraysCompat.copyOf(ret, ret.length*2);
                int allocSize = Math.min(size, MAX_REC_SIZE);
                size -= allocSize - c;

                //append to end of file
                long indexVal = freePhysTake(allocSize, ensureAvail);
                indexVal |= (((long)allocSize)<<48);
                if(c!=0) indexVal|= MASK_LINKED;
                ret[retPos++] = indexVal;

                c = size<=MAX_REC_SIZE ? 0 : 8;
            }
            if(size!=0) throw new InternalError();

            return ArraysCompat.copyOf(ret, retPos);
        }
    }



    protected static long roundTo16(long offset){
        long rem = offset%16;
        if(rem!=0) offset +=16-rem;
        return offset;
    }





    @Override
    public void close() {
        structuralLock.lock();
        for(ReentrantReadWriteLock lock:locks) lock.writeLock().lock();
        if(!readOnly){
            index.putLong(IO_PHYS_SIZE,physSize);
            index.putLong(IO_INDEX_SIZE,indexSize);
            index.putLong(IO_FREE_SIZE,freeSize);
        }

        index.sync();
        phys.sync();
        index.close();
        phys.close();
        if(deleteFilesAfterClose){
            index.deleteFile();
            phys.deleteFile();
        }
        index = null;
        phys = null;
        for(ReentrantReadWriteLock lock:locks) lock.writeLock().unlock();
        structuralLock.unlock();
    }

    @Override
    public boolean isClosed() {
        return index==null;
    }

    @Override
    public void commit() {
        if(!readOnly){
            index.putLong(IO_PHYS_SIZE,physSize);
            index.putLong(IO_INDEX_SIZE,indexSize);
            index.putLong(IO_FREE_SIZE,freeSize);
        }
        if(!syncOnCommitDisabled){
            index.sync();
            phys.sync();
        }
    }

    @Override
    public void rollback() throws UnsupportedOperationException {
        throw new UnsupportedOperationException("rollback not supported with journal disabled");
    }

    @Override
    public boolean isReadOnly() {
        return readOnly;
    }

    @Override
    public boolean canRollback(){
        return false;
    }

    @Override
    public void clearCache() {
    }

    @Override
    public void compact() {

        if(readOnly) throw new IllegalAccessError();
        index.putLong(IO_PHYS_SIZE,physSize);
        index.putLong(IO_INDEX_SIZE,indexSize);
        index.putLong(IO_FREE_SIZE,freeSize);

        if(index.getFile()==null) throw new UnsupportedOperationException("compact not supported for memory storage yet");
        structuralLock.lock();
        for(ReentrantReadWriteLock l:locks) l.writeLock().lock();
        try{
            //create secondary files for compaction
            //TODO RAF
            //TODO memory based stores
            final File indexFile = index.getFile();
            final File physFile = phys.getFile();
            int rafMode = 0;
            if(index instanceof  Volume.FileChannelVol) rafMode=2;
            if(index instanceof  Volume.MappedFileVol && phys instanceof Volume.FileChannelVol) rafMode = 1;

            final boolean isRaf = index instanceof Volume.FileChannelVol;
            Volume.Factory fab = Volume.fileFactory(false, rafMode, new File(indexFile+".compact"),sizeLimit);
            StoreDirect store2 = new StoreDirect(fab);
            store2.structuralLock.lock();

            //transfer stack of free recids
            for(long recid =longStackTake(IO_FREE_RECID);
                recid!=0; recid=longStackTake(IO_FREE_RECID)){
                store2.longStackPut(recid, IO_FREE_RECID);
            }

            //iterate over recids and transfer physical records
            store2.index.putLong(IO_INDEX_SIZE, indexSize);

            for(long ioRecid = IO_USER_START; ioRecid<indexSize;ioRecid+=8){
                byte[] bb = get2(ioRecid,Serializer.BYTE_ARRAY_SERIALIZER);
                store2.index.ensureAvailable(ioRecid+8);
                if(bb==null||bb.length==0){
                    store2.index.putLong(ioRecid,0);
                }else{
                    long[] indexVals = store2.physAllocate(bb.length,true);
                    DataOutput2 out = new DataOutput2();
                    out.buf = bb;
                    out.pos = bb.length;
                    store2.put2(out, ioRecid,indexVals);
                }
            }



            File indexFile2 = store2.index.getFile();
            File physFile2 = store2.phys.getFile();
            store2.structuralLock.unlock();
            store2.close();

            long time = System.currentTimeMillis();
            File indexFile_ = new File(indexFile.getPath()+"_"+time+"_orig");
            File physFile_ = new File(physFile.getPath()+"_"+time+"_orig");

            index.close();
            phys.close();
            if(!indexFile.renameTo(indexFile_))throw new InternalError("could not rename file");
            if(!physFile.renameTo(physFile_))throw new InternalError("could not rename file");

            if(!indexFile2.renameTo(indexFile))throw new InternalError("could not rename file");
            //TODO process may fail in middle of rename, analyze sequence and add recovery
            if(!physFile2.renameTo(physFile))throw new InternalError("could not rename file");

            indexFile_.delete();
            physFile_.delete();

            Volume.Factory fac2 = Volume.fileFactory(false, rafMode, indexFile,sizeLimit);

            index = fac2.createIndexVolume();
            phys = fac2.createPhysVolume();

            physSize = store2.physSize;
            index.putLong(IO_PHYS_SIZE, physSize);
            index.putLong(IO_INDEX_SIZE, indexSize);
            index.putLong(IO_FREE_SIZE, freeSize);

        }catch(IOException e){
            throw new IOError(e);
        }finally {
            structuralLock.unlock();
            for(ReentrantReadWriteLock l:locks) l.writeLock().unlock();
        }

    }


    protected long longStackTake(final long ioList) {
        if(!structuralLock.isLocked())throw new InternalError();
        if(ioList<IO_FREE_RECID || ioList>=IO_USER_START) throw new IllegalArgumentException("wrong ioList: "+ioList);

        long dataOffset = index.getLong(ioList);
        if(dataOffset == 0) return 0; //there is no such list, so just return 0

        long pos = dataOffset>>>48;
        dataOffset &= MASK_OFFSET;

        if(pos<8) throw new InternalError();

        final long ret = phys.getSixLong(dataOffset + pos);

        //was it only record at that page?
        if(pos == 8){
            //yes, delete this page
            long next =phys.getLong(dataOffset);
            long size = next>>>48;
            next &=MASK_OFFSET;
            if(next !=0){
                //update index so it points to previous page
                long nextSize = phys.getUnsignedShort(next);
                if((nextSize-8)%6!=0)throw new InternalError();
                index.putLong(ioList , ((nextSize-6)<<48)|next);
            }else{
                //zero out index
                index.putLong(ioList , 0L);
            }
            //put space used by this page into free list
            freePhysPut((size<<48) | dataOffset);
        }else{
            //no, it was not last record at this page, so just decrement the counter
            pos-=6;
            index.putLong(ioList, (pos<<48)| dataOffset); //TODO update just 2 bytes
        }

        //System.out.println("longStackTake: "+ioList+" - "+ret);

        return ret;

    }


    protected void longStackPut(final long ioList, long offset){
        if(offset>>>48!=0) throw new IllegalArgumentException();
        if(!structuralLock.isLocked())throw new InternalError();
        if(ioList<IO_FREE_RECID || ioList>=IO_USER_START) throw new InternalError("wrong ioList: "+ioList);

        long dataOffset = index.getLong(ioList);
        long pos = dataOffset>>>48;
        dataOffset &= MASK_OFFSET;

        if(dataOffset == 0){ //empty list?
            //yes empty, create new page and fill it with values
            final long listPhysid = freePhysTake((int) LONG_STACK_PREF_SIZE,true) &MASK_OFFSET;
            if(listPhysid == 0) throw new InternalError();
            //set previous Free Index List page to zero as this is first page
            //also set size of this record
            phys.putLong(listPhysid , LONG_STACK_PREF_SIZE << 48);
            //set  record
            phys.putSixLong(listPhysid + 8, offset);
            //and update index file with new page location
            index.putLong(ioList , ( 8L << 48) | listPhysid);
        }else{
            long next = phys.getLong(dataOffset);
            long size = next>>>48;
            next &=MASK_OFFSET;

            if(pos+6==size){ //is current page full?
                //yes it is full, so we need to allocate new page and write our number there
                final long listPhysid = freePhysTake((int) LONG_STACK_PREF_SIZE,true) &MASK_OFFSET;
                if(listPhysid == 0) throw new InternalError();

                //set location to previous page and set current page size
                phys.putLong(listPhysid, (LONG_STACK_PREF_SIZE<<48)|dataOffset);

                //set the value itself
                phys.putSixLong(listPhysid+8, offset);

                //and update index file with new page location and number of records
                index.putLong(ioList , (8L<<48) | listPhysid);
            }else{
                //there is space on page, so just write offset and increase the counter
                pos+=6;
                phys.putSixLong(dataOffset + pos, offset);
                index.putLong(ioList, (pos<<48)| dataOffset); //TODO update just 2 bytes
            }
        }
    }



    protected void freeIoRecidPut(long ioRecid) {
        if(spaceReclaimTrack)
            longStackPut(IO_FREE_RECID, ioRecid);
    }

    protected long freeIoRecidTake(boolean ensureAvail){
        if(spaceReclaimTrack){
            long ioRecid = longStackTake(IO_FREE_RECID);
            if(ioRecid!=0) return ioRecid;
        }
        indexSize+=8;
        if(ensureAvail)
            index.ensureAvailable(indexSize);
        return indexSize-8;
    }

    protected static long size2ListIoRecid(long size){
        return IO_FREE_RECID + 8 + ((size-1)/16)*8;
    }
    protected void freePhysPut(long indexVal) {
        long size = indexVal >>>48;
        freeSize+=roundTo16(size);
        longStackPut(size2ListIoRecid(size), indexVal & MASK_OFFSET);
    }

    protected long freePhysTake(int size, boolean ensureAvail) {
        if(size==0)throw new IllegalArgumentException();
        //check free space
        if(spaceReclaimReuse){
            long ret =  longStackTake(size2ListIoRecid(size));
            if(ret!=0){
                freeSize-=roundTo16(size);
                return ret;
            }
        }
        //not available, increase file size
        if(physSize%Volume.BUF_SIZE+size>Volume.BUF_SIZE)
            physSize += Volume.BUF_SIZE - physSize%Volume.BUF_SIZE;
        long physSize2 = physSize;
        physSize = roundTo16(physSize+size);
        if(ensureAvail)
            phys.ensureAvailable(physSize);
        return physSize2;
    }


    protected <A> DataOutput2 serialize(A value, Serializer<A> serializer) {
        try {
            DataOutput2 out = recycledDataOuts.poll();
            if(out==null) out = new DataOutput2();
            else out.pos=0;

            serializer.serialize(out,value);
            return out;
        } catch (IOException e) {
            throw new IOError(e);
        }
    }

    @Override
    public long getMaxRecid() {
        return (indexSize-IO_USER_START)/8;
    }

    @Override
    public ByteBuffer getRaw(long recid) {
        //TODO use direct BB
        byte[] bb = get(recid, Serializer.BYTE_ARRAY_SERIALIZER);
        if(bb==null) return null;
        return ByteBuffer.wrap(bb);
    }

    @Override
    public Iterator<Long> getFreeRecids() {
        return Utils.EMPTY_ITERATOR; //TODO iterate over stack of free recids, without modifying it
    }

    @Override
    public void updateRaw(long recid, ByteBuffer data) {
        long ioRecid = recid*8 + IO_USER_START;
        if(ioRecid>=indexSize){
            indexSize = ioRecid+8;
            index.ensureAvailable(indexSize);
        }

        byte[] b = null;

        if(data!=null){
            data = data.duplicate();
            b = new byte[data.remaining()];
            data.get(b);
        }
        //TODO use BB without copying
        update(recid, b, Serializer.BYTE_ARRAY_SERIALIZER);
    }

    @Override
    public long getSizeLimit() {
        return sizeLimit;
    }

    @Override
    public long getCurrSize() {
        return physSize;
    }

    @Override
    public long getFreeSize() {
        return freeSize;
    }

    @Override
    public String calculateStatistics() {
        String s = "";
        s+=getClass().getName()+"\n";
        s+="volume: "+"\n";
        s+="  "+phys+"\n";

        s+="indexSize="+indexSize+"\n";
        s+="physSize="+physSize+"\n";
        s+="freeSize="+freeSize+"\n";

        s+="num of freeRecids: "+countLongStackItems(IO_FREE_RECID)+"\n";

        for(int size = 16;size<MAX_REC_SIZE+10;size*=2){
            long sum = 0;
            for(int ss=size/2;ss<size;s+=16){
                sum+=countLongStackItems(size2ListIoRecid(ss))*ss;
            }
            s+="Size occupied by free records (size="+size+") = "+sum;
        }


        return s;
    }

    protected long countLongStackItems(long ioList){
        long ret=0;
        long v = index.getLong(ioList);

        while(true){
            long next = v&MASK_OFFSET;
            if(next==0) return ret;
            ret+=v>>>48;
            v = phys.getLong(next);
        }

    }
}
