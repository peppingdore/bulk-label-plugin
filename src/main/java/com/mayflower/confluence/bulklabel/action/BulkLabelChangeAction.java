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
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

/**
 * All data exposed to Velocity uses Map&lt;String,Object&gt; because
 * Confluence 9's Velocity allowlist blocks access to custom plugin classes.
 */
public class BulkLabelChangeAction extends ConfluenceActionSupport {

    private static final Logger log = LoggerFactory.getLogger(BulkLabelChangeAction.class);

    // In-memory task tracking (keyed by taskId) — package-visible for ProgressServlet
    static final ConcurrentHashMap<String, TaskProgress> TASKS = new ConcurrentHashMap<>();
    private static final Pattern VALID_TASK_ID = Pattern.compile("^[0-9a-f\\-]+$");
    private static final int MAX_ITEMS = 1000;

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
        if (!isValidLabel(normalise(sourceLabel))) {
            addActionError("Invalid source label. Labels may only contain lowercase letters, numbers, hyphens, underscores, and dots.");
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
        previewResult = doPreviewWork(sourceLabel, user, filter);

        int editableCount = (int) previewResult.get("editableCount");
        int skippedCount = (int) previewResult.get("skippedCount");
        if (editableCount == 0 && skippedCount == 0) {
            addActionMessage("No content found with label \u201c" + sourceLabel + "\u201d.");
        }
        if ((boolean) previewResult.get("truncated")) {
            addActionMessage("Warning: results are limited to " + MAX_ITEMS + " items. Some content with this label may not be shown.");
        }

        return SUCCESS;
    }

    // ------------------------------------------------------------------
    //  Execute – starts background task, redirects to progress page
    // ------------------------------------------------------------------

    @RequireSecurityToken(true)
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
        if (!isValidLabel(normalise(sourceLabel))) {
            addActionError("Invalid source label. Labels may only contain lowercase letters, numbers, hyphens, underscores, and dots.");
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
        taskId = UUID.randomUUID().toString();
        TaskProgress task = new TaskProgress(itemIds.size(), src, tgt, user.getKey().getStringValue());
        task.remainingIds.addAll(itemIds);
        TASKS.put(taskId, task);

        return SUCCESS;
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
        result.put("truncated", allItems.size() >= MAX_ITEMS);
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
        PartialList<ContentEntityObject> partialList = lm.getContentForLabel(0, MAX_ITEMS, label);
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
        PartialList<ContentEntityObject> partialList = labelManager.getContentForLabel(0, MAX_ITEMS, label);
        if (partialList == null) return Collections.emptyList();
        return new ArrayList<>(partialList.getList());
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
        final String ownerUserKey;
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

        TaskProgress(int totalCount, String sourceLabel, String targetLabel, String ownerUserKey) {
            this.totalCount = totalCount;
            this.sourceLabel = sourceLabel;
            this.targetLabel = targetLabel;
            this.ownerUserKey = ownerUserKey;
        }
    }

    /** Remove tasks that completed more than TASK_TTL_MS ago. */
    static void evictStaleTasks() {
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
    public void setTaskId(String s)                         { this.taskId = (s != null && VALID_TASK_ID.matcher(s).matches()) ? s : null; }

    public List<Map<String, Object>> getAvailableSpaces()   { return availableSpaces; }
    public Map<String, Object> getPreviewResult()           { return previewResult; }
    public Map<String, Object> getChangeResult()            { return changeResult; }
}
