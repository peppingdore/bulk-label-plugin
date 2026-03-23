package com.mayflower.confluence.bulklabel.action;

import com.atlassian.confluence.core.ContentEntityObject;
import com.atlassian.confluence.core.PartialList;
import com.atlassian.confluence.labels.Label;
import com.atlassian.confluence.labels.LabelManager;
import com.atlassian.confluence.labels.Namespace;
import com.atlassian.confluence.pages.BlogPost;
import com.atlassian.confluence.pages.Page;
import com.atlassian.spring.container.ContainerManager;

import com.atlassian.confluence.user.AuthenticatedUserThreadLocal;
import com.atlassian.confluence.user.ConfluenceUser;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

public class ProgressServlet extends HttpServlet {

    private static final Logger log = LoggerFactory.getLogger(ProgressServlet.class);
    private static final Pattern VALID_TASK_ID = Pattern.compile("^[0-9a-f\\-]+$");
    private static final int BATCH_SIZE = 25;

    private LabelManager getLabelManager() {
        return (LabelManager) ContainerManager.getComponent("labelManager");
    }

    /** GET returns progress JSON (read-only, no side effects). */
    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        resp.setContentType("application/json");
        resp.setCharacterEncoding("UTF-8");

        ConfluenceUser user = AuthenticatedUserThreadLocal.get();
        if (user == null) {
            resp.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            resp.getWriter().write("{\"error\":\"Authentication required\",\"done\":true,\"items\":[]}");
            return;
        }

        BulkLabelChangeAction.TaskProgress task = resolveTask(req, resp, user);
        if (task == null) return;

        writeProgress(resp, req.getParameter("taskId"), task);
    }

    /** POST processes a batch and returns progress JSON. */
    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        resp.setContentType("application/json");
        resp.setCharacterEncoding("UTF-8");

        ConfluenceUser user = AuthenticatedUserThreadLocal.get();
        if (user == null) {
            resp.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            resp.getWriter().write("{\"error\":\"Authentication required\",\"done\":true,\"items\":[]}");
            return;
        }

        BulkLabelChangeAction.TaskProgress task = resolveTask(req, resp, user);
        if (task == null) return;

        if (!task.done) {
            processBatch(task);
        }

        // Piggyback eviction on requests so stale tasks don't accumulate
        BulkLabelChangeAction.evictStaleTasks();

        writeProgress(resp, req.getParameter("taskId"), task);
    }

    /** Validate taskId and check that the calling user owns the task. Returns null if response was already written. */
    private BulkLabelChangeAction.TaskProgress resolveTask(HttpServletRequest req, HttpServletResponse resp,
                                                            ConfluenceUser user) throws IOException {
        String taskId = req.getParameter("taskId");
        if (taskId == null || taskId.isBlank() || !VALID_TASK_ID.matcher(taskId).matches()) {
            resp.getWriter().write("{\"error\":\"Missing or invalid taskId\",\"done\":true,\"items\":[]}");
            return null;
        }

        BulkLabelChangeAction.TaskProgress task = BulkLabelChangeAction.TASKS.get(taskId);
        if (task == null) {
            resp.getWriter().write("{\"error\":\"Task not found\",\"done\":true,\"items\":[]}");
            return null;
        }

        if (!task.ownerUserKey.equals(user.getKey().getStringValue())) {
            resp.setStatus(HttpServletResponse.SC_FORBIDDEN);
            resp.getWriter().write("{\"error\":\"Access denied\",\"done\":true,\"items\":[]}");
            return null;
        }

        return task;
    }

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
            if (!task.remainingIds.remove(item.getId())) continue;
            madeProgress = true;

            boolean success = false;
            try {
                // Add target label first so the item is never left without either label
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

    private void writeProgress(HttpServletResponse resp, String taskId,
                               BulkLabelChangeAction.TaskProgress task) throws IOException {
        int pct = task.totalCount > 0 ? (task.processedCount.get() * 100) / task.totalCount : 0;

        // Return only new items since last client cursor
        List<Map<String, Object>> allItems = task.processedItems;
        int newEnd = allItems.size();
        int cursor = task.clientCursor.getAndSet(newEnd);

        StringBuilder itemsJson = new StringBuilder("[");
        for (int i = cursor; i < newEnd; i++) {
            Map<String, Object> item = allItems.get(i);
            if (i > cursor) itemsJson.append(",");
            itemsJson.append("{\"id\":").append(item.get("id"))
                      .append(",\"title\":\"").append(escapeJson(String.valueOf(item.get("title")))).append("\"")
                      .append(",\"spaceKey\":\"").append(escapeJson(String.valueOf(item.get("spaceKey")))).append("\"")
                      .append(",\"type\":\"").append(escapeJson(String.valueOf(item.get("type")))).append("\"")
                      .append(",\"success\":").append(item.get("success"))
                      .append("}");
        }
        itemsJson.append("]");

        String json = "{\"taskId\":\"" + escapeJson(taskId) + "\""
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
        StringBuilder sb = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '\\': sb.append("\\\\"); break;
                case '"':  sb.append("\\\""); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                case '\b': sb.append("\\b"); break;
                case '\f': sb.append("\\f"); break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.toString();
    }
}
