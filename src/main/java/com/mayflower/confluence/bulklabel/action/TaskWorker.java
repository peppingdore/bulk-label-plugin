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

    private TransactionTemplate getTxTemplate() {
        return (TransactionTemplate) ContainerManager.getComponent("transactionTemplate");
    }

    /** Snapshot of a content item — only IDs and metadata, no Hibernate entity. */
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

        // Collect IDs + metadata in one transaction, then discard the Hibernate entities
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

            // Each item gets its own transaction with a freshly loaded entity
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
