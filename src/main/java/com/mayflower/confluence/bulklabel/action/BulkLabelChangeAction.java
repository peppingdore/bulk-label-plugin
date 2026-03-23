package com.mayflower.confluence.bulklabel.action;

import com.atlassian.confluence.core.ConfluenceActionSupport;
import com.atlassian.confluence.core.ContentEntityObject;
import com.atlassian.confluence.core.PartialList;
import com.atlassian.confluence.labels.Label;
import com.atlassian.confluence.labels.LabelManager;
import com.atlassian.confluence.labels.Namespace;
import com.atlassian.confluence.pages.BlogPost;
import com.atlassian.confluence.pages.Page;
import com.atlassian.confluence.security.Permission;
import com.atlassian.confluence.security.PermissionManager;
import com.atlassian.confluence.spaces.Space;
import com.atlassian.confluence.spaces.SpaceManager;
import com.atlassian.confluence.user.ConfluenceUser;
import com.atlassian.xwork.ParameterSafe;
import com.atlassian.xwork.RequireSecurityToken;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * All data exposed to Velocity uses Map&lt;String,Object&gt; because
 * Confluence 9's Velocity allowlist blocks access to custom plugin classes.
 */
public class BulkLabelChangeAction extends ConfluenceActionSupport {

    private static final Logger log = LoggerFactory.getLogger(BulkLabelChangeAction.class);

    // In-memory task tracking (keyed by taskId) — package-visible for ProgressServlet
    static final ConcurrentHashMap<String, TaskProgress> TASKS = new ConcurrentHashMap<>();

    private SpaceManager spaceManager;

    // Form fields
    private String sourceLabel;
    private String targetLabel;
    private String[] spaceKeys;
    private boolean allSpaces = true;
    private String taskId;

    // Results exposed to Velocity (all Maps, no custom DTOs)
    private Map<String, Object> previewResult;
    private Map<String, Object> changeResult;
    private List<Map<String, Object>> availableSpaces;

    // ------------------------------------------------------------------
    //  Entry point – render the form
    // ------------------------------------------------------------------

    @RequireSecurityToken(false)
    public String doDefault() {
        if (!isAuthenticated()) {
            addActionError("You must be logged in.");
            return ERROR;
        }
        loadSpaces();
        return INPUT;
    }

    // ------------------------------------------------------------------
    //  Preview
    // ------------------------------------------------------------------

    @RequireSecurityToken(false)
    public String doPreview() {
        if (!isAuthenticated()) {
            addActionError("You must be logged in.");
            return ERROR;
        }
        if (sourceLabel == null || sourceLabel.isBlank()) {
            addActionError("Please enter a source label.");
            loadSpaces();
            return ERROR;
        }
        if (targetLabel == null || targetLabel.isBlank()) {
            addActionError("Please enter a target label.");
            loadSpaces();
            return ERROR;
        }
        if (!isValidLabel(normalise(targetLabel))) {
            addActionError("Invalid target label. Labels may only contain lowercase letters, numbers, hyphens, underscores, and dots.");
            loadSpaces();
            return ERROR;
        }

        ConfluenceUser user = getAuthenticatedUser();
        Set<String> filter = resolveSpaceFilter();
        previewResult = doPreviewWork(sourceLabel, user, filter);

        int editableCount = (int) previewResult.get("editableCount");
        int skippedCount = (int) previewResult.get("skippedCount");
        if (editableCount == 0 && skippedCount == 0) {
            addActionMessage("No content found with label \u201c" + sourceLabel + "\u201d.");
        }

        return SUCCESS;
    }

    // ------------------------------------------------------------------
    //  Execute – starts background task, redirects to progress page
    // ------------------------------------------------------------------

    @RequireSecurityToken(false)
    public String doExecute() {
        if (!isAuthenticated()) {
            addActionError("You must be logged in.");
            return ERROR;
        }
        if (sourceLabel == null || sourceLabel.isBlank()) {
            addActionError("Please enter a source label.");
            loadSpaces();
            return ERROR;
        }
        if (targetLabel == null || targetLabel.isBlank()) {
            addActionError("Please enter a target label.");
            loadSpaces();
            return ERROR;
        }
        if (!isValidLabel(normalise(targetLabel))) {
            addActionError("Invalid target label. Labels may only contain lowercase letters, numbers, hyphens, underscores, and dots.");
            loadSpaces();
            return ERROR;
        }

        String src = normalise(sourceLabel);
        String tgt = normalise(targetLabel);

        if (src.equals(tgt)) {
            addActionError("Source and target labels are identical.");
            loadSpaces();
            return ERROR;
        }

        ConfluenceUser user = getAuthenticatedUser();
        Set<String> filter = resolveSpaceFilter();

        // Clean up old completed tasks
        evictStaleTasks();

        // Collect editable item IDs (runs in request's Hibernate context)
        List<Long> itemIds = collectEditableItemIdsBackground(src, user, filter,
                this.labelManager, this.permissionManager);

        if (itemIds.isEmpty()) {
            addActionMessage("No editable content found with label \u201c" + sourceLabel + "\u201d.");
            loadSpaces();
            return ERROR;
        }

        // Create task with collected IDs stored for batch processing
        taskId = Long.toHexString(System.nanoTime());
        TaskProgress task = new TaskProgress(itemIds.size(), src, tgt);
        task.remainingIds.addAll(itemIds);
        TASKS.put(taskId, task);

        return SUCCESS;
    }

    private static final int BATCH_SIZE = 25;

    // ------------------------------------------------------------------
    //  Process a batch — called via AJAX from the progress page.
    //  Runs in a proper request thread with Hibernate session.
    // ------------------------------------------------------------------

    @RequireSecurityToken(false)
    public String doProcessBatch() throws Exception {
        javax.servlet.http.HttpServletResponse response =
            com.atlassian.confluence.struts.compat.ServletActionContext.getResponse();

        if (taskId == null) return SUCCESS;
        TaskProgress task = TASKS.get(taskId);
        if (task == null || task.done) {
            writeProgressJson(response, taskId);
            return null;
        }

        String src = task.sourceLabel;
        String tgt = task.targetLabel;

        Label label = labelManager.getLabel(src);
        if (label == null) {
            // No more content with this label
            int left = task.remainingIds.size();
            task.processedCount.addAndGet(left);
            task.remainingIds.clear();
            task.done = true;
            task.completedAt = System.currentTimeMillis();
            return SUCCESS;
        }

        PartialList<ContentEntityObject> items = labelManager.getContentForLabel(0, BATCH_SIZE, label);
        if (items == null || items.getList().isEmpty()) {
            int left = task.remainingIds.size();
            task.processedCount.addAndGet(left);
            task.remainingIds.clear();
            task.done = true;
            task.completedAt = System.currentTimeMillis();
            return SUCCESS;
        }

        boolean madeProgress = false;
        for (ContentEntityObject item : items.getList()) {
            if (!task.remainingIds.contains(item.getId())) continue;
            task.remainingIds.remove(item.getId());
            madeProgress = true;

            try {
                Label oldLabel = findLabelOnItem(item, src);
                if (oldLabel != null) {
                    labelManager.removeLabel(item, oldLabel);
                }
                labelManager.addLabel(item, new Label(tgt, Namespace.GLOBAL));
                task.successCount.incrementAndGet();
            } catch (Exception e) {
                task.failCount.incrementAndGet();
                log.error("Failed to relabel content id={}: {}", item.getId(), e.getMessage(), e);
            }
            task.processedCount.incrementAndGet();
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

        writeProgressJson(response, taskId);
        return null;
    }

    private void writeProgressJson(javax.servlet.http.HttpServletResponse response, String tid) throws Exception {
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        TaskProgress t = TASKS.get(tid);
        String json;
        if (t != null) {
            int pct = t.totalCount > 0 ? (t.processedCount.get() * 100) / t.totalCount : 0;
            json = "{\"taskId\":\"" + tid + "\""
                 + ",\"totalCount\":" + t.totalCount
                 + ",\"processedCount\":" + t.processedCount.get()
                 + ",\"successCount\":" + t.successCount.get()
                 + ",\"failCount\":" + t.failCount.get()
                 + ",\"done\":" + t.done
                 + ",\"percentComplete\":" + pct + "}";
        } else {
            json = "{\"error\":\"Task not found\",\"done\":true}";
        }
        response.getWriter().write(json);
    }

    // ------------------------------------------------------------------
    //  Progress polling (AJAX endpoint)
    // ------------------------------------------------------------------

    @RequireSecurityToken(false)
    public String doProgress() {
        return SUCCESS;
    }

    public String getProgressJson() {
        Map<String, Object> progress = getTaskProgress();
        if (progress != null) {
            return "{\"taskId\":\"" + progress.get("taskId") + "\""
                 + ",\"totalCount\":" + progress.get("totalCount")
                 + ",\"processedCount\":" + progress.get("processedCount")
                 + ",\"successCount\":" + progress.get("successCount")
                 + ",\"failCount\":" + progress.get("failCount")
                 + ",\"done\":" + progress.get("done")
                 + ",\"percentComplete\":" + progress.get("percentComplete")
                 + "}";
        }
        return "{\"error\":\"Task not found\",\"done\":true}";
    }

    // ------------------------------------------------------------------
    //  Results page
    // ------------------------------------------------------------------

    @RequireSecurityToken(false)
    public String doResults() {
        if (taskId != null) {
            TaskProgress task = TASKS.get(taskId);
            if (task != null) {
                changeResult = new HashMap<>();
                changeResult.put("successCount", task.successCount.get());
                changeResult.put("failCount", task.failCount.get());
                changeResult.put("skippedCount", 0);
                changeResult.put("errorMessage", null);
                changeResult.put("changedContent", Collections.emptyList());
                changeResult.put("sourceLabel", task.sourceLabel);
                changeResult.put("targetLabel", task.targetLabel);
                if (task.done) {
                    TASKS.remove(taskId);
                }
            }
        }
        return SUCCESS;
    }

    // ------------------------------------------------------------------
    //  Core logic
    // ------------------------------------------------------------------

    private Map<String, Object> doPreviewWork(String srcLabel, ConfluenceUser user, Set<String> spaces) {
        String normalised = normalise(srcLabel);
        List<ContentEntityObject> allItems = findAllByLabel(normalised);

        List<Map<String, Object>> permitted = new ArrayList<>();
        int skippedCount = 0;

        for (ContentEntityObject item : allItems) {
            if (!matchesSpaceFilter(item, spaces)) continue;
            if (userCanEdit(item, user)) {
                permitted.add(contentToMap(item));
            } else {
                skippedCount++;
            }
        }

        Map<String, Object> result = new HashMap<>();
        result.put("items", permitted);
        result.put("skippedCount", skippedCount);
        result.put("editableCount", permitted.size());
        result.put("totalCount", permitted.size() + skippedCount);
        return result;
    }

    /**
     * Collect editable item IDs — safe to call from background thread.
     * Uses passed-in managers instead of instance fields.
     */
    private List<Long> collectEditableItemIdsBackground(String srcLabel, ConfluenceUser user,
                                                         Set<String> spaces, LabelManager lm,
                                                         PermissionManager pm) {
        Label label = lm.getLabel(srcLabel);
        if (label == null) return Collections.emptyList();
        PartialList<ContentEntityObject> partialList = lm.getContentForLabel(0, 10000, label);
        if (partialList == null) return Collections.emptyList();

        List<Long> ids = new ArrayList<>();
        for (ContentEntityObject item : partialList.getList()) {
            if (!matchesSpaceFilter(item, spaces)) continue;
            if (user != null && pm.hasPermission(user, Permission.EDIT, item)) {
                ids.add(item.getId());
            }
        }
        return ids;
    }

    private Map<String, Object> contentToMap(ContentEntityObject content) {
        Map<String, Object> map = new HashMap<>();
        if (content instanceof Page p) {
            map.put("id", p.getId());
            map.put("title", p.getTitle());
            map.put("type", "Page");
            map.put("spaceKey", p.getSpaceKey());
            map.put("spaceName", p.getSpace() != null ? p.getSpace().getName() : p.getSpaceKey());
        } else if (content instanceof BlogPost bp) {
            map.put("id", bp.getId());
            map.put("title", bp.getTitle());
            map.put("type", "Blog Post");
            map.put("spaceKey", bp.getSpaceKey());
            map.put("spaceName", bp.getSpace() != null ? bp.getSpace().getName() : bp.getSpaceKey());
        } else {
            map.put("id", content.getId());
            map.put("title", content.getTitle());
            map.put("type", content.getType());
            map.put("spaceKey", "");
            map.put("spaceName", "");
        }
        return map;
    }

    // ------------------------------------------------------------------
    //  Helpers
    // ------------------------------------------------------------------

    private boolean isAuthenticated() {
        return getAuthenticatedUser() != null;
    }

    public void setSpaceManager(SpaceManager spaceManager) {
        this.spaceManager = spaceManager;
    }


    private void loadSpaces() {
        List<Space> spaces = spaceManager.getAllSpaces();
        availableSpaces = new ArrayList<>();
        for (Space s : spaces) {
            Map<String, Object> map = new HashMap<>();
            map.put("key", s.getKey());
            map.put("name", s.getName());
            availableSpaces.add(map);
        }
        availableSpaces.sort((a, b) -> ((String) a.get("name")).compareToIgnoreCase((String) b.get("name")));
    }

    private Set<String> resolveSpaceFilter() {
        if (allSpaces || spaceKeys == null || spaceKeys.length == 0) {
            return Collections.emptySet();
        }
        return new LinkedHashSet<>(Arrays.asList(spaceKeys));
    }

    private boolean matchesSpaceFilter(ContentEntityObject item, Set<String> spaceKeys) {
        if (spaceKeys == null || spaceKeys.isEmpty()) return true;
        String key = null;
        if (item instanceof Page p) key = p.getSpaceKey();
        else if (item instanceof BlogPost bp) key = bp.getSpaceKey();
        return key != null && spaceKeys.contains(key);
    }

    private boolean userCanEdit(ContentEntityObject content, ConfluenceUser user) {
        return user != null && permissionManager.hasPermission(user, Permission.EDIT, content);
    }

    private List<ContentEntityObject> findAllByLabel(String labelName) {
        Label label = labelManager.getLabel(labelName);
        if (label == null) return Collections.emptyList();
        PartialList<ContentEntityObject> partialList = labelManager.getContentForLabel(0, 10000, label);
        if (partialList == null) return Collections.emptyList();
        return new ArrayList<>(partialList.getList());
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

    private String normalise(String raw) {
        return raw == null ? "" : raw.trim().toLowerCase();
    }

    private static final java.util.regex.Pattern VALID_LABEL =
            java.util.regex.Pattern.compile("^[a-z0-9][a-z0-9._-]{0,254}$");

    private boolean isValidLabel(String label) {
        return label != null && VALID_LABEL.matcher(label).matches();
    }

    // ------------------------------------------------------------------
    //  Task progress tracker
    // ------------------------------------------------------------------

    private static final long TASK_TTL_MS = 10 * 60 * 1000; // 10 minutes

    static class TaskProgress {
        volatile int totalCount;
        final String sourceLabel;
        final String targetLabel;
        final Set<Long> remainingIds = Collections.synchronizedSet(new LinkedHashSet<>());
        final AtomicInteger processedCount = new AtomicInteger(0);
        final AtomicInteger successCount = new AtomicInteger(0);
        final AtomicInteger failCount = new AtomicInteger(0);
        volatile boolean done = false;
        volatile long completedAt = 0;
        // Per-item results: list of {id, title, spaceKey, type, success}
        final List<Map<String, Object>> processedItems = Collections.synchronizedList(new ArrayList<>());
        // Track how many items the client has already seen
        final AtomicInteger clientCursor = new AtomicInteger(0);

        TaskProgress(int totalCount, String sourceLabel, String targetLabel) {
            this.totalCount = totalCount;
            this.sourceLabel = sourceLabel;
            this.targetLabel = targetLabel;
        }
    }

    /** Remove tasks that completed more than TASK_TTL_MS ago. */
    private static void evictStaleTasks() {
        long now = System.currentTimeMillis();
        TASKS.entrySet().removeIf(e -> {
            TaskProgress t = e.getValue();
            return t.done && t.completedAt > 0 && (now - t.completedAt) > TASK_TTL_MS;
        });
    }

    public Map<String, Object> getTaskProgress() {
        if (taskId == null) return null;
        TaskProgress task = TASKS.get(taskId);
        if (task == null) return null;
        Map<String, Object> map = new HashMap<>();
        map.put("taskId", taskId);
        map.put("totalCount", task.totalCount);
        map.put("processedCount", task.processedCount.get());
        map.put("successCount", task.successCount.get());
        map.put("failCount", task.failCount.get());
        map.put("done", task.done);
        map.put("sourceLabel", task.sourceLabel);
        map.put("targetLabel", task.targetLabel);
        int pct = task.totalCount > 0 ? (task.processedCount.get() * 100) / task.totalCount : 0;
        map.put("percentComplete", pct);
        return map;
    }

    // ------------------------------------------------------------------
    //  Getters / setters for Velocity and form binding
    // ------------------------------------------------------------------

    public String getSourceLabel()                          { return sourceLabel; }
    @ParameterSafe
    public void setSourceLabel(String s)                    { this.sourceLabel = s; }

    public String getTargetLabel()                          { return targetLabel; }
    @ParameterSafe
    public void setTargetLabel(String s)                    { this.targetLabel = s; }

    public String[] getSpaceKeys()                          { return spaceKeys; }
    @ParameterSafe
    public void setSpaceKeys(String[] s)                    { this.spaceKeys = s; }

    public boolean isAllSpaces()                            { return allSpaces; }
    @ParameterSafe
    public void setAllSpaces(boolean b)                     { this.allSpaces = b; }

    public String getTaskId()                               { return taskId; }
    @ParameterSafe
    public void setTaskId(String s)                         { this.taskId = s; }

    public List<Map<String, Object>> getAvailableSpaces()   { return availableSpaces; }
    public Map<String, Object> getPreviewResult()           { return previewResult; }
    public Map<String, Object> getChangeResult()            { return changeResult; }
}
