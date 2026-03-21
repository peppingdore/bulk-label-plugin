package com.example.confluence.bulklabel.action;

import com.atlassian.confluence.core.ConfluenceActionSupport;
import com.atlassian.confluence.core.ContentEntityObject;
import com.atlassian.confluence.core.PartialList;
import com.atlassian.confluence.labels.Label;
import com.atlassian.confluence.labels.LabelManager;
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
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class BulkLabelChangeAction extends ConfluenceActionSupport {

    private static final Logger log = LoggerFactory.getLogger(BulkLabelChangeAction.class);

    private SpaceManager spaceManager;

    // Form fields
    private String sourceLabel;
    private String targetLabel;
    private String[] spaceKeys;
    private boolean allSpaces = true;

    // Results
    private PreviewResult previewResult;
    private ChangeResult changeResult;

    // Space list for the form
    private List<SpaceInfo> availableSpaces;

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
        previewResult = preview(sourceLabel, user, filter);

        if (previewResult.getEditableCount() == 0 && previewResult.getSkippedCount() == 0) {
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
        changeResult = execute(sourceLabel, targetLabel, user, filter);

        if (changeResult.hasError()) {
            addActionError(changeResult.getErrorMessage());
            loadSpaces();
            return ERROR;
        }

        return SUCCESS;
    }

    // ------------------------------------------------------------------
    //  Core logic (uses managers from ConfluenceActionSupport)
    // ------------------------------------------------------------------

    private PreviewResult preview(String srcLabel, ConfluenceUser user, Set<String> spaces) {
        String normalised = normalise(srcLabel);
        List<ContentEntityObject> allItems = findAllByLabel(normalised);

        List<AffectedContent> permitted = new ArrayList<>();
        int skippedCount = 0;

        for (ContentEntityObject item : allItems) {
            if (!matchesSpaceFilter(item, spaces)) {
                continue;
            }
            if (userCanEdit(item, user)) {
                permitted.add(AffectedContent.from(item));
            } else {
                skippedCount++;
            }
        }

        return new PreviewResult(permitted, skippedCount);
    }

    private ChangeResult execute(String srcLabel, String tgtLabel, ConfluenceUser user, Set<String> spaces) {
        String src = normalise(srcLabel);
        String tgt = normalise(tgtLabel);

        if (src.isEmpty() || tgt.isEmpty()) {
            return ChangeResult.error("Source and target labels must not be empty.");
        }
        if (src.equals(tgt)) {
            return ChangeResult.error("Source and target labels are identical.");
        }

        List<ContentEntityObject> allItems = findAllByLabel(src);
        int successCount = 0;
        int failCount = 0;
        int skippedCount = 0;
        List<AffectedContent> changed = new ArrayList<>();

        LabelManager lm = labelManager;
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
                    lm.removeLabel(item, oldLabel);
                }
                lm.addLabel(item, new Label(tgt, Namespace.GLOBAL));
                successCount++;
                changed.add(AffectedContent.from(item));
            } catch (Exception e) {
                failCount++;
                log.error("Failed to relabel content id={}: {}", item.getId(), e.getMessage(), e);
            }
        }

        ChangeResult result = new ChangeResult(successCount, failCount, skippedCount, null);
        result.setChangedContent(changed);
        return result;
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
            availableSpaces.add(new SpaceInfo(s.getKey(), s.getName()));
        }
        availableSpaces.sort((a, b) -> a.getName().compareToIgnoreCase(b.getName()));
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
    //  Getters / setters
    // ------------------------------------------------------------------

    public String getSourceLabel()                 { return sourceLabel; }
    public void setSourceLabel(String s)           { this.sourceLabel = s; }

    public String getTargetLabel()                 { return targetLabel; }
    public void setTargetLabel(String s)           { this.targetLabel = s; }

    public String[] getSpaceKeys()                 { return spaceKeys; }
    public void setSpaceKeys(String[] s)           { this.spaceKeys = s; }

    public boolean isAllSpaces()                   { return allSpaces; }
    public void setAllSpaces(boolean b)            { this.allSpaces = b; }

    public List<SpaceInfo> getAvailableSpaces()    { return availableSpaces; }
    public PreviewResult getPreviewResult()        { return previewResult; }
    public ChangeResult getChangeResult()          { return changeResult; }

    // ------------------------------------------------------------------
    //  DTOs
    // ------------------------------------------------------------------

    public static class SpaceInfo {
        private final String key;
        private final String name;

        public SpaceInfo(String key, String name) {
            this.key = key;
            this.name = name;
        }

        public String getKey()  { return key; }
        public String getName() { return name; }
    }

    public static class AffectedContent {
        private long id;
        private String title;
        private String type;
        private String spaceKey;
        private String spaceName;

        public static AffectedContent from(ContentEntityObject content) {
            AffectedContent ac = new AffectedContent();
            if (content instanceof Page p) {
                ac.id = p.getId();
                ac.title = p.getTitle();
                ac.type = "Page";
                ac.spaceKey = p.getSpaceKey();
                ac.spaceName = p.getSpace() != null ? p.getSpace().getName() : p.getSpaceKey();
            } else if (content instanceof BlogPost bp) {
                ac.id = bp.getId();
                ac.title = bp.getTitle();
                ac.type = "Blog Post";
                ac.spaceKey = bp.getSpaceKey();
                ac.spaceName = bp.getSpace() != null ? bp.getSpace().getName() : bp.getSpaceKey();
            } else {
                ac.id = content.getId();
                ac.title = content.getTitle();
                ac.type = content.getType();
                ac.spaceKey = "";
                ac.spaceName = "";
            }
            return ac;
        }

        public long getId()          { return id; }
        public String getTitle()     { return title; }
        public String getType()      { return type; }
        public String getSpaceKey()  { return spaceKey; }
        public String getSpaceName() { return spaceName; }
    }

    public static class PreviewResult {
        private final List<AffectedContent> items;
        private final int skippedCount;

        public PreviewResult(List<AffectedContent> items, int skippedCount) {
            this.items = items;
            this.skippedCount = skippedCount;
        }

        public List<AffectedContent> getItems() { return items; }
        public int getSkippedCount()             { return skippedCount; }
        public int getEditableCount()            { return items.size(); }
        public int getTotalCount()               { return items.size() + skippedCount; }
    }

    public static class ChangeResult {
        private final int successCount;
        private final int failCount;
        private final int skippedCount;
        private final String errorMessage;
        private List<AffectedContent> changedContent = Collections.emptyList();

        public ChangeResult(int successCount, int failCount, int skippedCount, String errorMessage) {
            this.successCount = successCount;
            this.failCount = failCount;
            this.skippedCount = skippedCount;
            this.errorMessage = errorMessage;
        }

        public static ChangeResult error(String message) {
            return new ChangeResult(0, 0, 0, message);
        }

        public int getSuccessCount()                     { return successCount; }
        public int getFailCount()                        { return failCount; }
        public int getSkippedCount()                     { return skippedCount; }
        public String getErrorMessage()                  { return errorMessage; }
        public boolean hasError()                        { return errorMessage != null; }
        public List<AffectedContent> getChangedContent() { return changedContent; }
        public void setChangedContent(List<AffectedContent> c) { this.changedContent = c; }
    }
}
