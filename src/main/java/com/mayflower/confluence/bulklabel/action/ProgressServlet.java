package com.mayflower.confluence.bulklabel.action;

import com.atlassian.confluence.user.AuthenticatedUserThreadLocal;
import com.atlassian.confluence.user.ConfluenceUser;

import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Read-only servlet that returns task progress as JSON.
 * The worker thread is managed by {@link TaskWorker} on demand.
 */
public class ProgressServlet extends HttpServlet {

    private static final Pattern VALID_TASK_ID = Pattern.compile("^[0-9a-f\\-]+$");

    @Override
    public void destroy() {
        // Let worker finish current batch on plugin unload
        TaskWorker.awaitShutdown(30_000);
    }

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

    private void writeProgress(HttpServletResponse resp, String taskId,
                               BulkLabelChangeAction.TaskProgress task) throws IOException {
        int pct = task.totalCount > 0 ? (task.processedCount.get() * 100) / task.totalCount : 0;

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
