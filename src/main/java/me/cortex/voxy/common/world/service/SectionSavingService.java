package me.cortex.voxy.common.world.service;

import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.thread.Service;
import me.cortex.voxy.common.thread.UnifiedServiceThreadPool;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.common.world.WorldSection;

import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.ArrayList;

//TODO: add an option for having synced saving, that is when call enqueueSave, that will instead, instantly
// save to the db, this can be useful for just reducing the amount of thread pools in total
// might have some issues with threading if the same section is saved from multiple threads?
public class SectionSavingService {
    private static final int SOFT_MAX_QUEUE_SIZE = 5_000;
    private static final int MAX_BATCH_SIZE = 32;

    private final Service service;
    private final UnifiedServiceThreadPool savingPool = new UnifiedServiceThreadPool();
    private final ThreadLocal<Boolean> processingSave = ThreadLocal.withInitial(() -> false);
    private record SaveEntry(WorldEngine engine, WorldSection section) {}
    private final ConcurrentLinkedDeque<SaveEntry> saveQueue = new ConcurrentLinkedDeque<>();

    public SectionSavingService() {
        //Durable writes must not occupy Sodium/ingestion/mesh workers waiting for LMDB's single writer.
        this.service = this.savingPool.serviceManager.createServiceNoCleanup(() -> this::processJob, 100, "Section saving service");
        this.savingPool.setNumThreads(1);
    }

    private synchronized void processJob() {
        var task = this.saveQueue.pop();
        var batch = new ArrayList<WorldSection>(MAX_BATCH_SIZE);
        batch.add(task.section);
        while (batch.size() < MAX_BATCH_SIZE) {
            var next = this.saveQueue.peek();
            if (next == null || next.engine != task.engine || !this.service.steal()) break;
            batch.add(this.saveQueue.pop().section);
        }
        this.processingSave.set(true);
        try {
            var toSave = new ArrayList<WorldSection>(batch.size());
            for (var section : batch) {
                section.assertNotFree();
                boolean save = section.exchangeIsInSaveQueue(false);
                section.setNotDirty();//do after the atomic exchange
                if (save) toSave.add(section);
            }
            try {
                if (!toSave.isEmpty()) task.engine.storage.saveSections(toSave);
            } catch (Exception e) {
                //A failed transaction may roll back every entry; keep them all dirty for retry.
                for (var section : toSave) {
                    section.markDirty();
                }
                Logger.error("Voxy saver had an exception while executing please check logs and report the error", e);
            }
        } finally {
            try {
                for (var section : batch) section.release();
            } finally {
                this.processingSave.remove();
            }
        }
    }

    /*
    public void enqueueSave(WorldSection section) {
        if (section._getSectionTracker() != null && section._getSectionTracker().engine != null) {
            this.enqueueSave(section._getSectionTracker().engine, section);
        } else {
            Logger.error("Tried saving world section, but did not have world associated");
        }
    }*/

    public boolean enqueueSave(WorldEngine in, WorldSection section, boolean nonBlocking, boolean sectionAlreadyAcquired) {
        //If its not enqueued for saving then enqueue it
        if (section.exchangeIsInSaveQueue(true)) {
            if (!sectionAlreadyAcquired) {
                section.acquire(); //Acquire the section for use
            }

            //Hard limit the save count to prevent OOM
            if ((!nonBlocking) && !this.processingSave.get() && this.getTaskCount() > SOFT_MAX_QUEUE_SIZE) {
                //wait a bit
                Thread.yield();
                /*
                try {
                    Thread.sleep(10);
                } catch (InterruptedException e) {
                    throw new RuntimeException(e);
                }*/
                //If we are still full, process entries in the queue ourselves instead of waiting for the service
                while (this.getTaskCount() > SOFT_MAX_QUEUE_SIZE && this.service.isLive()) {
                    if (!this.service.steal()) {
                        break;
                    }
                    this.processJob();
                }
            }

            this.saveQueue.add(new SaveEntry(in, section));
            this.service.execute();
            return true;
        }
        return false;
    }

    public void shutdown() {
        if (this.service.numJobs() != 0) {
            Logger.error("Voxy section saving still in progress, estimated " + this.service.numJobs() + " sections remaining.");
            this.service.blockTillEmpty();
        }
        this.service.shutdown();
        //Manually save any remaining entries
        while (!this.saveQueue.isEmpty()) {
            this.processJob();
        }
        this.savingPool.shutdown();
    }

    public int getTaskCount() {
        return this.service.numJobs();
    }
}
