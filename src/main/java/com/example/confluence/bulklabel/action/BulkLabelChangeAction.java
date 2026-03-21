package com.example.confluence.bulklabel.action;

import com.atlassian.confluence.core.ConfluenceActionSupport;
import com.atlassian.confluence.core.ContentEntityObject;
import com.atlassian.confluence.core.PartialList;
import com.atlassian.confluence.labels.Label;
import com.atlassian.confluence.labels.Namespace;
import com.atlassian.confluence.pages.BlogPost;
import com.atlassian.confluence.pages.Page;
import com.atlassian.confluence.security.Permission;
import com.atlassian.confluence.spaces.Space;
import com.atlassian.confluence.spaces.SpaceManager;
import com.atlassian.confluence.user.ConfluenceUser;
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

/**
 * All data exposed to Velocity uses Map&lt;String,Object&gt; because
 * Confluence 9's Velocity allowlist blocks access to custom plugin classes.
 */
public class BulkLabelChangeAction extends ConfluenceActionSupport {

    private static final Logger log = LoggerFactory.getLogger(BulkLabelChangeAction.class);

    private SpaceManager spaceManager;

    // Form fields
    private String sourceLabel;
    private String targetLabel;
    private String[] spaceKeys;
    private boolean allSpaces = true;

    // Results exposed to Velocity (all Maps, no custom DTOs)
    private Map<String, Object> previewResult;
    private Map<String, Object> changeResult;
    private List<Map<String, Object>> availableSpaces;

    // ------------------------------------------------------------------
    //  Entry point – render the form
    // ------------------------------------------------------------------

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
    //  Execute
    // ------------------------------------------------------------------

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

        ConfluenceUser user = getAuthenticatedUser();
        Set<String> filter = resolveSpaceFilter();
        changeResult = doExecuteWork(sourceLabel, targetLabel, user, filter);

        String errorMessage = (String) changeResult.get("errorMessage");
        if (errorMessage != null) {
            addActionError(errorMessage);
            loadSpaces();
            return ERROR;
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
            if (!matchesSpaceFilter(item, spaces)) {
                continue;
            }
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

    private Map<String, Object> doExecuteWork(String srcLabel, String tgtLabel, ConfluenceUser user, Set<String> spaces) {
        String src = normalise(srcLabel);
        String tgt = normalise(tgtLabel);

        if (src.isEmpty() || tgt.isEmpty()) {
            return errorResult("Source and target labels must not be empty.");
        }
        if (src.equals(tgt)) {
            return errorResult("Source and target labels are identical.");
        }

        List<ContentEntityObject> allItems = findAllByLabel(src);
        int successCount = 0;
        int failCount = 0;
        int skippedCount = 0;
        List<Map<String, Object>> changed = new ArrayList<>();

        for (ContentEntityObject item : allItems) {
            if (!matchesSpaceFilter(item, spaces)) {
                continue;
            }
            if (!userCanEdit(item, user)) {
                skippedCount++;
                continue;
            }

            try {
                Label oldLabel = findLabelOnItem(item, src);
                if (oldLabel != null) {
                    labelManager.removeLabel(item, oldLabel);
                }
                labelManager.addLabel(item, new Label(tgt, Namespace.GLOBAL));
                successCount++;
                changed.add(contentToMap(item));
            } catch (Exception e) {
                failCount++;
                log.error("Failed to relabel content id={}: {}", item.getId(), e.getMessage(), e);
            }
        }

        Map<String, Object> result = new HashMap<>();
        result.put("successCount", successCount);
        result.put("failCount", failCount);
        result.put("skippedCount", skippedCount);
        result.put("errorMessage", null);
        result.put("changedContent", changed);
        return result;
    }

    private Map<String, Object> errorResult(String message) {
        Map<String, Object> result = new HashMap<>();
        result.put("successCount", 0);
        result.put("failCount", 0);
        result.put("skippedCount", 0);
        result.put("errorMessage", message);
        result.put("changedContent", Collections.emptyList());
        return result;
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
        if (spaceKeys == null || spaceKeys.isEmpty()) {
            return true;
        }
        String key = null;
        if (item instanceof Page p) {
            key = p.getSpaceKey();
        } else if (item instanceof BlogPost bp) {
            key = bp.getSpaceKey();
        }
        return key != null && spaceKeys.contains(key);
    }

    private boolean userCanEdit(ContentEntityObject content, ConfluenceUser user) {
        return user != null && permissionManager.hasPermission(user, Permission.EDIT, content);
    }

    private List<ContentEntityObject> findAllByLabel(String labelName) {
        Label label = labelManager.getLabel(labelName);
        if (label == null) {
            return Collections.emptyList();
        }
        PartialList<ContentEntityObject> partialList = labelManager.getContentForLabel(0, 1000, label);
        if (partialList == null) {
            return Collections.emptyList();
        }
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

    // ------------------------------------------------------------------
    //  Getters / setters for Velocity and form binding
    // ------------------------------------------------------------------

    public String getSourceLabel()                          { return sourceLabel; }
    public void setSourceLabel(String s)                    { this.sourceLabel = s; }

    public String getTargetLabel()                          { return targetLabel; }
    public void setTargetLabel(String s)                    { this.targetLabel = s; }

    public String[] getSpaceKeys()                          { return spaceKeys; }
    public void setSpaceKeys(String[] s)                    { this.spaceKeys = s; }

    public boolean isAllSpaces()                            { return allSpaces; }
    public void setAllSpaces(boolean b)                     { this.allSpaces = b; }

    public List<Map<String, Object>> getAvailableSpaces()   { return availableSpaces; }
    public Map<String, Object> getPreviewResult()           { return previewResult; }
    public Map<String, Object> getChangeResult()            { return changeResult; }
}
