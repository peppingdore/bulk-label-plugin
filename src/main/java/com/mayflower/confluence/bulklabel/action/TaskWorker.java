package com.mayflower.confluence.bulklabel.action;

import com.atlassian.confluence.core.ContentEntityObject;
import com.atlassian.confluence.core.PartialList;
import com.atlassian.confluence.labels.Label;
import com.atlassian.confluence.labels.LabelManager;
import com.atlassian.confluence.labels.Namespace;
import com.atlassian.confluence.pages.BlogPost;
import com.atlassian.confluence.pages.Page;
import com.atlassian.confluence.pages.PageManager;
import com.atlassian.sal.api.transaction.TransactionTemplate;
import com.atlassian.spring.container.ContainerManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Background worker that processes bulk label rename tasks.
 * A single thread is spawned on demand when work is queued and exits
 * automatically when all tasks are complete. Thread-safe: only one
 * worker thread runs at a time.
 */
public class TaskWorker implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(TaskWorker.class);
    private static final int BATCH_SIZE = 25;
    private static final long IDLE_SLEEP_MS = 200;

    private static final Object LOCK = new Object();
    private static Thread workerThread;

    /** Called after adding a task to TASKS. Starts the worker if not already running. */
    static void ensureRunning() {
        synchronized (LOCK) {
            if (workerThread != null && workerThread.isAlive()) return;
            TaskWorker worker = new TaskWorker();
            workerThread = new Thread(worker, "bulk-label-worker");
            workerThread.setDaemon(true);
            workerThread.start();
        }
    }

    /** Wait for the worker to finish current work (called on plugin unload). */
    static void awaitShutdown(long timeoutMs) {
        Thread t;
        synchronized (LOCK) {
            t = workerThread;
        }
        if (t != null && t.isAlive()) {
            try {
                t.join(timeoutMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    @Override
    public void run() {
        log.info("Bulk label worker thread started");

        try {
            while (true) {
                boolean didWork = false;

                for (Map.Entry<String, BulkLabelChangeAction.TaskProgress> entry :
                        BulkLabelChangeAction.TASKS.entrySet()) {

                    BulkLabelChangeAction.TaskProgress task = entry.getValue();
                    if (task.done || task.remainingIds.isEmpty()) continue;

                    try {
                        processBatch(task);
                        didWork = true;
                    } catch (Exception e) {
                        log.error("Unexpected error processing task {}: {}", entry.getKey(), e.getMessage(), e);
                        int left = task.remainingIds.size();
                        task.processedCount.addAndGet(left);
                        task.failCount.addAndGet(left);
                        task.remainingIds.clear();
                        task.done = true;
                        task.completedAt = System.currentTimeMillis();
                    }
                }

                BulkLabelChangeAction.evictStaleTasks();

                // If no work was done, check if any tasks are still active
                if (!didWork) {
                    boolean hasActiveTasks = false;
                    for (BulkLabelChangeAction.TaskProgress task : BulkLabelChangeAction.TASKS.values()) {
                        if (!task.done && !task.remainingIds.isEmpty()) {
                            hasActiveTasks = true;
                            break;
                        }
                    }
                    if (!hasActiveTasks) break; // exit thread — no more work

                    // Active tasks exist but batch returned nothing useful — brief pause
                    Thread.sleep(IDLE_SLEEP_MS);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        synchronized (LOCK) {
            workerThread = null;
        }
        log.info("Bulk label worker thread exiting — no more tasks");
    }

    private TransactionTemplate getTxTemplate() {
        return (TransactionTemplate) ContainerManager.getComponent("transactionTemplate");
    }

    private static class ItemInfo {
        final long id;
        final String title;
        final String spaceKey;
        final String type;
        ItemInfo(long id, String title, String spaceKey, String type) {
            this.id = id; this.title = title; this.spaceKey = spaceKey; this.type = type;
        }
    }

    private void processBatch(BulkLabelChangeAction.TaskProgress task) {
        String src = task.sourceLabel;
        String tgt = task.targetLabel;

        List<ItemInfo> batch = getTxTemplate().execute(() -> {
            LabelManager lm = (LabelManager) ContainerManager.getComponent("labelManager");
            Label label = lm.getLabel(src);
            if (label == null) return Collections.<ItemInfo>emptyList();
            PartialList<ContentEntityObject> partial = lm.getContentForLabel(0, BATCH_SIZE, label);
            if (partial == null) return Collections.<ItemInfo>emptyList();
            List<ItemInfo> result = new ArrayList<>();
            for (ContentEntityObject ceo : partial.getList()) {
                String spaceKey = "";
                String type = ceo.getType();
                if (ceo instanceof Page p) { spaceKey = p.getSpaceKey(); type = "Page"; }
                else if (ceo instanceof BlogPost bp) { spaceKey = bp.getSpaceKey(); type = "Blog Post"; }
                result.add(new ItemInfo(ceo.getId(), ceo.getTitle(), spaceKey, type));
            }
            return result;
        });

        if (batch.isEmpty()) {
            int left = task.remainingIds.size();
            task.processedCount.addAndGet(left);
            task.remainingIds.clear();
            task.done = true;
            task.completedAt = System.currentTimeMillis();
            return;
        }

        boolean madeProgress = false;
        for (ItemInfo info : batch) {
            if (!task.remainingIds.remove(info.id)) continue;
            madeProgress = true;

            boolean success = false;
            try {
                getTxTemplate().execute(() -> {
                    PageManager pm = (PageManager) ContainerManager.getComponent("pageManager");
                    ContentEntityObject fresh = pm.getAbstractPage(info.id);
                    if (fresh == null) throw new RuntimeException("Content " + info.id + " not found");
                    LabelManager lm = (LabelManager) ContainerManager.getComponent("labelManager");
                    lm.addLabel(fresh, new Label(tgt, Namespace.GLOBAL));
                    Label oldLabel = findLabelOnItem(fresh, src);
                    if (oldLabel != null) {
                        lm.removeLabel(fresh, oldLabel);
                    }
                    return null;
                });
                task.successCount.incrementAndGet();
                success = true;
            } catch (Exception e) {
                log.error("Failed to rename label '{}' -> '{}' on content id={} title='{}'",
                        src, tgt, info.id, info.title, e);
                task.failCount.incrementAndGet();
            }
            task.processedCount.incrementAndGet();

            Map<String, Object> itemResult = new HashMap<>();
            itemResult.put("id", info.id);
            itemResult.put("title", info.title);
            itemResult.put("spaceKey", info.spaceKey);
            itemResult.put("type", info.type);
            itemResult.put("success", success);
            task.processedItems.add(itemResult);
        }

        if (!madeProgress) {
            int left = task.remainingIds.size();
            task.processedCount.addAndGet(left);
            task.failCount.addAndGet(left);
            task.remainingIds.clear();
        }

        if (task.remainingIds.isEmpty()) {
            task.done = true;
            task.completedAt = System.currentTimeMillis();
        }
    }

    private Label findLabelOnItem(ContentEntityObject item, String labelName) {
        for (Label l : item.getLabels()) {
            if (l.getName().equalsIgnoreCase(labelName)
                    && Namespace.GLOBAL.equals(l.getNamespace())) {
                return l;
            }
        }
        return null;
    }
}
