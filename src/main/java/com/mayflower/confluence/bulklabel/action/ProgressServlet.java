package com.mayflower.confluence.bulklabel.action;

import com.atlassian.confluence.core.ContentEntityObject;
import com.atlassian.confluence.core.PartialList;
import com.atlassian.confluence.labels.Label;
import com.atlassian.confluence.labels.LabelManager;
import com.atlassian.confluence.labels.Namespace;
import com.atlassian.confluence.pages.BlogPost;
import com.atlassian.confluence.pages.Page;
import com.atlassian.spring.container.ContainerManager;

import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class ProgressServlet extends HttpServlet {

    private LabelManager getLabelManager() {
        return (LabelManager) ContainerManager.getComponent("labelManager");
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        resp.setContentType("application/json");
        resp.setCharacterEncoding("UTF-8");

        String taskId = req.getParameter("taskId");
        String action = req.getParameter("action");

        if (taskId == null || taskId.isBlank()) {
            resp.getWriter().write("{\"error\":\"Missing taskId\",\"done\":true,\"items\":[]}");
            return;
        }

        BulkLabelChangeAction.TaskProgress task = BulkLabelChangeAction.TASKS.get(taskId);
        if (task == null) {
            resp.getWriter().write("{\"error\":\"Task not found\",\"done\":true,\"items\":[]}");
            return;
        }

        if ("process".equals(action) && !task.done) {
            processBatch(task);
        }

        writeProgress(resp, taskId, task);
    }

    private static final int BATCH_SIZE = 25;

    private void processBatch(BulkLabelChangeAction.TaskProgress task) {
        LabelManager lm = getLabelManager();
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
            if (!task.remainingIds.contains(item.getId())) continue;
            task.remainingIds.remove(item.getId());
            madeProgress = true;

            boolean success = false;
            try {
                Label oldLabel = findLabelOnItem(item, src);
                if (oldLabel != null) {
                    lm.removeLabel(item, oldLabel);
                }
                lm.addLabel(item, new Label(tgt, Namespace.GLOBAL));
                task.successCount.incrementAndGet();
                success = true;
            } catch (Exception e) {
                task.failCount.incrementAndGet();
            }
            task.processedCount.incrementAndGet();

            // Record item result
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

    private void writeProgress(HttpServletResponse resp, String taskId,
                               BulkLabelChangeAction.TaskProgress task) throws IOException {
        int pct = task.totalCount > 0 ? (task.processedCount.get() * 100) / task.totalCount : 0;

        // Return only new items since last client cursor
        int cursor = task.clientCursor.get();
        List<Map<String, Object>> allItems = task.processedItems;
        int newEnd = allItems.size();
        task.clientCursor.set(newEnd);

        StringBuilder itemsJson = new StringBuilder("[");
        for (int i = cursor; i < newEnd; i++) {
            Map<String, Object> item = allItems.get(i);
            if (i > cursor) itemsJson.append(",");
            itemsJson.append("{\"id\":").append(item.get("id"))
                      .append(",\"title\":\"").append(escapeJson(String.valueOf(item.get("title")))).append("\"")
                      .append(",\"spaceKey\":\"").append(escapeJson(String.valueOf(item.get("spaceKey")))).append("\"")
                      .append(",\"type\":\"").append(item.get("type")).append("\"")
                      .append(",\"success\":").append(item.get("success"))
                      .append("}");
        }
        itemsJson.append("]");

        String json = "{\"taskId\":\"" + taskId + "\""
                + ",\"totalCount\":" + task.totalCount
                + ",\"processedCount\":" + task.processedCount.get()
                + ",\"successCount\":" + task.successCount.get()
                + ",\"failCount\":" + task.failCount.get()
                + ",\"done\":" + task.done
                + ",\"percentComplete\":" + pct
                + ",\"items\":" + itemsJson
                + "}";
        resp.getWriter().write(json);
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "");
    }
}
