package com.example.confluence.bulklabel.action;

import com.atlassian.confluence.core.ConfluenceActionSupport;
import com.atlassian.confluence.user.ConfluenceUser;
import com.example.confluence.bulklabel.service.BulkLabelChangeService;
import com.example.confluence.bulklabel.service.BulkLabelChangeService.ChangeResult;
import com.example.confluence.bulklabel.service.BulkLabelChangeService.PreviewResult;
import com.example.confluence.bulklabel.service.BulkLabelChangeService.SpaceInfo;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class BulkLabelChangeAction extends ConfluenceActionSupport {

    private BulkLabelChangeService bulkLabelChangeService;

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
        previewResult = bulkLabelChangeService.preview(sourceLabel, user, filter);

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
        changeResult = bulkLabelChangeService.execute(sourceLabel, targetLabel, user, filter);

        if (changeResult.hasError()) {
            addActionError(changeResult.getErrorMessage());
            loadSpaces();
            return ERROR;
        }

        return SUCCESS;
    }

    // ------------------------------------------------------------------
    //  Helpers
    // ------------------------------------------------------------------

    private boolean isAuthenticated() {
        return getAuthenticatedUser() != null;
    }

    private void loadSpaces() {
        availableSpaces = bulkLabelChangeService.getAllSpaces();
    }

    private Set<String> resolveSpaceFilter() {
        if (allSpaces || spaceKeys == null || spaceKeys.length == 0) {
            return Collections.emptySet(); // empty = all spaces
        }
        return new LinkedHashSet<>(Arrays.asList(spaceKeys));
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

    public void setBulkLabelChangeService(BulkLabelChangeService s) {
        this.bulkLabelChangeService = s;
    }
}
