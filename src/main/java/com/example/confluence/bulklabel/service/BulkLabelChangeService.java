package com.example.confluence.bulklabel.service;

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
import com.atlassian.plugin.spring.scanner.annotation.imports.ComponentImport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import javax.inject.Inject;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * Handles the bulk relabelling operation, scoped to content the
 * calling user has permission to edit.
 *
 * Flow:
 *  1. Query LabelManager for every Labelable carrying the source label.
 *  2. Filter to items the supplied user can EDIT.
 *  3. For each permitted item, remove the source label and add the target.
 *  4. Return a summary distinguishing successes, permission-skips, and errors.
 */
@Component
public class BulkLabelChangeService {

    private static final Logger log = LoggerFactory.getLogger(BulkLabelChangeService.class);

    private final LabelManager labelManager;
    private final PermissionManager permissionManager;
    private final SpaceManager spaceManager;

    @Inject
    public BulkLabelChangeService(
            @ComponentImport LabelManager labelManager,
            @ComponentImport PermissionManager permissionManager,
            @ComponentImport SpaceManager spaceManager) {
        this.labelManager = labelManager;
        this.permissionManager = permissionManager;
        this.spaceManager = spaceManager;
    }

    // ---------------------------------------------------------------
    //  Space listing
    // ---------------------------------------------------------------

    public List<SpaceInfo> getAllSpaces() {
        List<Space> spaces = spaceManager.getAllSpaces();
        List<SpaceInfo> result = new ArrayList<>();
        for (Space s : spaces) {
            result.add(new SpaceInfo(s.getKey(), s.getName()));
        }
        result.sort((a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        return result;
    }

    // ---------------------------------------------------------------
    //  Preview – returns affected content the user CAN edit
    // ---------------------------------------------------------------

    public PreviewResult preview(String sourceLabel, ConfluenceUser user, Set<String> spaceKeys) {
        if (sourceLabel == null || sourceLabel.isBlank()) {
            return new PreviewResult(Collections.emptyList(), 0);
        }

        String normalised = normalise(sourceLabel);
        List<ContentEntityObject> allItems = findAllByLabel(normalised);

        List<AffectedContent> permitted = new ArrayList<>();
        int skippedCount = 0;

        for (ContentEntityObject item : allItems) {
            if (!matchesSpaceFilter(item, spaceKeys)) {
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

    // ---------------------------------------------------------------
    //  Execute – rename sourceLabel → targetLabel where user can edit
    // ---------------------------------------------------------------

    public ChangeResult execute(String sourceLabel, String targetLabel, ConfluenceUser user, Set<String> spaceKeys) {
        String src = normalise(sourceLabel);
        String tgt = normalise(targetLabel);

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

        for (ContentEntityObject item : allItems) {
            if (!matchesSpaceFilter(item, spaceKeys)) {
                continue;
            }
            // ---- Permission gate ----
            if (!userCanEdit(item, user)) {
                skippedCount++;
                continue;
            }

            try {
                // Remove old label
                Label oldLabel = findLabelOnItem(item, src);
                if (oldLabel != null) {
                    labelManager.removeLabel(item, oldLabel);
                }

                // Add new label (idempotent – won't duplicate)
                labelManager.addLabel(item, new Label(tgt, Namespace.GLOBAL));

                successCount++;
                changed.add(AffectedContent.from(item));
            } catch (Exception e) {
                failCount++;
                log.error("Failed to relabel content id={}: {}",
                        getContentId(item), e.getMessage(), e);
            }
        }

        ChangeResult result = new ChangeResult(successCount, failCount, skippedCount, null);
        result.setChangedContent(changed);
        return result;
    }

    // ---------------------------------------------------------------
    //  Permission check
    // ---------------------------------------------------------------

    private boolean matchesSpaceFilter(ContentEntityObject item, Set<String> spaceKeys) {
        if (spaceKeys == null || spaceKeys.isEmpty()) {
            return true; // no filter = all spaces
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
        if (user == null) {
            return false;
        }
        return permissionManager.hasPermission(user, Permission.EDIT, content);
    }

    // ---------------------------------------------------------------
    //  Internal helpers
    // ---------------------------------------------------------------

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

    private long getContentId(ContentEntityObject content) {
        return content.getId();
    }

    // ---------------------------------------------------------------
    //  DTOs
    // ---------------------------------------------------------------

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
}
