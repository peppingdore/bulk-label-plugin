package com.example.confluence.bulklabel.action;

import com.atlassian.confluence.core.ConfluenceActionSupport;
import com.atlassian.confluence.user.ConfluenceUser;
import com.example.confluence.bulklabel.service.BulkLabelChangeService;
import com.example.confluence.bulklabel.service.BulkLabelChangeService.ChangeResult;
import com.example.confluence.bulklabel.service.BulkLabelChangeService.PreviewResult;

/**
 * User-facing action that drives the bulk-label-change UI.
 *
 * Any authenticated user can access it. The service layer enforces
 * per-content edit permissions, so a user will only ever modify
 * labels on content they are allowed to edit.
 *
 * Flows:
 *   GET  change.action   → show form
 *   POST preview.action  → dry-run preview (filtered by user perms)
 *   POST execute.action  → apply the rename (filtered by user perms)
 */
public class BulkLabelChangeAction extends ConfluenceActionSupport {

    private BulkLabelChangeService bulkLabelChangeService;

    // Form fields
    private String sourceLabel;
    private String targetLabel;

    // Results
    private PreviewResult previewResult;
    private ChangeResult changeResult;

    // ------------------------------------------------------------------
    //  Entry point – render the form
    // ------------------------------------------------------------------

    public String doDefault() {
        if (!isAuthenticated()) {
            addActionError("You must be logged in.");
            return ERROR;
        }
        return INPUT;
    }

    // ------------------------------------------------------------------
    //  Preview – show what the current user would affect
    // ------------------------------------------------------------------

    public String doPreview() {
        if (!isAuthenticated()) {
            addActionError("You must be logged in.");
            return ERROR;
        }
        if (sourceLabel == null || sourceLabel.isBlank()) {
            addActionError("Please enter a source label.");
            return ERROR;
        }

        ConfluenceUser user = getAuthenticatedUser();
        previewResult = bulkLabelChangeService.preview(sourceLabel, user);

        if (previewResult.getEditableCount() == 0 && previewResult.getSkippedCount() == 0) {
            addActionMessage("No content found with label \u201c" + sourceLabel + "\u201d.");
        }

        return SUCCESS;
    }

    // ------------------------------------------------------------------
    //  Execute – apply the rename
    // ------------------------------------------------------------------

    public String doExecute() {
        if (!isAuthenticated()) {
            addActionError("You must be logged in.");
            return ERROR;
        }
        if (sourceLabel == null || sourceLabel.isBlank()) {
            addActionError("Please enter a source label.");
            return ERROR;
        }
        if (targetLabel == null || targetLabel.isBlank()) {
            addActionError("Please enter a target label.");
            return ERROR;
        }

        ConfluenceUser user = getAuthenticatedUser();
        changeResult = bulkLabelChangeService.execute(sourceLabel, targetLabel, user);

        if (changeResult.hasError()) {
            addActionError(changeResult.getErrorMessage());
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

    // ------------------------------------------------------------------
    //  Getters / setters
    // ------------------------------------------------------------------

    public String getSourceLabel()                 { return sourceLabel; }
    public void setSourceLabel(String s)           { this.sourceLabel = s; }

    public String getTargetLabel()                 { return targetLabel; }
    public void setTargetLabel(String s)           { this.targetLabel = s; }

    public PreviewResult getPreviewResult()        { return previewResult; }
    public ChangeResult getChangeResult()          { return changeResult; }

    public void setBulkLabelChangeService(BulkLabelChangeService s) {
        this.bulkLabelChangeService = s;
    }
}
