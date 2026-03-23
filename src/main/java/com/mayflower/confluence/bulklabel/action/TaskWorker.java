package com.mayflower.confluence.bulklabel.action;

import com.atlassian.confluence.core.ContentEntityObject;
import com.atlassian.confluence.core.PartialList;
import com.atlassian.confluence.labels.Label;
import com.atlassian.confluence.labels.LabelManager;
import com.atlassian.confluence.labels.Namespace;
import com.atlassian.confluence.pages.BlogPost;
import com.atlassian.confluence.pages.Page;
import com.atlassian.sal.api.transaction.TransactionTemplate;
import com.atlassian.spring.container.ContainerManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;

/**
 * Single background daemon thread that processes bulk label rename tasks.
 * Started/stopped from {@link ProgressServlet} init/destroy.
 */
public class TaskWorker implements Runnable {

    private static final Logger log = LoggerFactory.getLogger(TaskWorker.class);
    private static final int BATCH_SIZE = 25;
    private static final long POLL_INTERVAL_MS = 200;
    private static final long EVICT_INTERVAL_MS = 60_000;

    private volatile boolean running = true;

    void shutdown() {
        running = false;
    }

    @Override
    public void run() {
        log.info("Bulk label worker thread started");
        long lastEvict = System.currentTimeMillis();

        while (running) {
            boolean didWork = false;

            for (Map.Entry<String, BulkLabelChangeAction.TaskProgress> entry :
                    BulkLabelChangeAction.TASKS.entrySet()) {
                if (!running) break;

                BulkLabelChangeAction.TaskProgress task = entry.getValue();
                if (task.done || task.remainingIds.isEmpty()) continue;

                try {
                    TransactionTemplate txTemplate =
                            (TransactionTemplate) ContainerManager.getComponent("transactionTemplate");
                    txTemplate.execute(() -> {
                        processBatch(task);
                        return null;
                    });
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

            long now = System.currentTimeMillis();
            if (now - lastEvict > EVICT_INTERVAL_MS) {
                BulkLabelChangeAction.evictStaleTasks();
                lastEvict = now;
            }

            if (!didWork && running) {
                try {
                    Thread.sleep(POLL_INTERVAL_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }

        log.info("Bulk label worker thread stopped");
    }

    private void processBatch(BulkLabelChangeAction.TaskProgress task) {
        LabelManager lm = (LabelManager) ContainerManager.getComponent("labelManager");
        String src = task.sourceLabel;
        String tgt = task.targetLabel;

        Label label = lm.getLabel(src);
        if (label == null) {
            int left = task.remainingIds.size();
            task.processedCount.addAndGet(left);
            task.remainingIds.clear();
            task.done = true;
            task.completedAt = System.currentTimeMillis();
            return;
        }

        PartialList<ContentEntityObject> items = lm.getContentForLabel(0, BATCH_SIZE, label);
        if (items == null || items.getList().isEmpty()) {
            int left = task.remainingIds.size();
            task.processedCount.addAndGet(left);
            task.remainingIds.clear();
            task.done = true;
            task.completedAt = System.currentTimeMillis();
            return;
        }

        boolean madeProgress = false;
        for (ContentEntityObject item : items.getList()) {
            if (!task.remainingIds.remove(item.getId())) continue;
            madeProgress = true;

            boolean success = false;
            try {
                lm.addLabel(item, new Label(tgt, Namespace.GLOBAL));
                Label oldLabel = findLabelOnItem(item, src);
                if (oldLabel != null) {
                    lm.removeLabel(item, oldLabel);
                }
                task.successCount.incrementAndGet();
                success = true;
            } catch (Exception e) {
                log.error("Failed to rename label '{}' -> '{}' on content id={} title='{}'",
                        src, tgt, item.getId(), item.getTitle(), e);
                task.failCount.incrementAndGet();
            }
            task.processedCount.incrementAndGet();

            Map<String, Object> itemResult = new HashMap<>();
            itemResult.put("id", item.getId());
            itemResult.put("title", item.getTitle());
            itemResult.put("success", success);
            if (item instanceof Page p) {
                itemResult.put("spaceKey", p.getSpaceKey());
                itemResult.put("type", "Page");
            } else if (item instanceof BlogPost bp) {
                itemResult.put("spaceKey", bp.getSpaceKey());
                itemResult.put("type", "Blog Post");
            } else {
                itemResult.put("spaceKey", "");
                itemResult.put("type", item.getType());
            }
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
