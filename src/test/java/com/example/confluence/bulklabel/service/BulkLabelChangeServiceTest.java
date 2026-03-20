package com.example.confluence.bulklabel.service;

import com.atlassian.confluence.core.ContentEntityObject;
import com.atlassian.confluence.core.PartialList;
import com.atlassian.confluence.labels.Label;
import com.atlassian.confluence.labels.LabelManager;
import com.atlassian.confluence.labels.Namespace;
import com.atlassian.confluence.pages.Page;
import com.atlassian.confluence.security.Permission;
import com.atlassian.confluence.security.PermissionManager;
import com.atlassian.confluence.user.ConfluenceUser;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

public class BulkLabelChangeServiceTest {

    @Mock private LabelManager labelManager;
    @Mock private PermissionManager permissionManager;
    @Mock private ConfluenceUser user;

    private BulkLabelChangeService service;

    @Before
    public void setUp() {
        MockitoAnnotations.openMocks(this);
        service = new BulkLabelChangeService(labelManager, permissionManager);
    }

    // ------------------------------------------------------------------
    //  Validation
    // ------------------------------------------------------------------

    @Test
    public void execute_identicalLabels_returnsError() {
        BulkLabelChangeService.ChangeResult result = service.execute("same", "same", user);
        assertTrue(result.hasError());
        assertEquals(0, result.getSuccessCount());
    }

    @Test
    public void execute_emptySource_returnsError() {
        BulkLabelChangeService.ChangeResult result = service.execute("", "target", user);
        assertTrue(result.hasError());
    }

    @Test
    public void execute_emptyTarget_returnsError() {
        BulkLabelChangeService.ChangeResult result = service.execute("source", "", user);
        assertTrue(result.hasError());
    }

    // ------------------------------------------------------------------
    //  Preview
    // ------------------------------------------------------------------

    @Test
    public void preview_unknownLabel_returnsEmptyList() {
        when(labelManager.getLabel("nonexistent")).thenReturn(null);
        BulkLabelChangeService.PreviewResult result = service.preview("nonexistent", user);
        assertEquals(0, result.getEditableCount());
        assertEquals(0, result.getSkippedCount());
    }

    @Test
    public void preview_filtersOutUnpermittedContent() {
        Label label = new Label("tag", Namespace.GLOBAL);
        when(labelManager.getLabel("tag")).thenReturn(label);

        Page editable = mockPage(1L, "Editable Page", "DEV");
        Page forbidden = mockPage(2L, "Forbidden Page", "HR");

        when(labelManager.getContentForLabel(eq(0), eq(1000), eq(label)))
                .thenReturn(toPartialList(editable, forbidden));

        when(permissionManager.hasPermission(user, Permission.EDIT, editable)).thenReturn(true);
        when(permissionManager.hasPermission(user, Permission.EDIT, forbidden)).thenReturn(false);

        BulkLabelChangeService.PreviewResult result = service.preview("tag", user);
        assertEquals(1, result.getEditableCount());
        assertEquals(1, result.getSkippedCount());
        assertEquals("Editable Page", result.getItems().get(0).getTitle());
    }

    // ------------------------------------------------------------------
    //  Execute
    // ------------------------------------------------------------------

    @Test
    public void execute_onlyModifiesPermittedContent() {
        Label oldLabel = new Label("old-tag", Namespace.GLOBAL);
        when(labelManager.getLabel("old-tag")).thenReturn(oldLabel);

        Page editable = mockPage(1L, "Editable", "DEV");
        Page forbidden = mockPage(2L, "Forbidden", "HR");

        when(editable.getLabels()).thenReturn(List.of(oldLabel));
        when(forbidden.getLabels()).thenReturn(List.of(oldLabel));

        when(labelManager.getContentForLabel(eq(0), eq(1000), eq(oldLabel)))
                .thenReturn(toPartialList(editable, forbidden));

        when(permissionManager.hasPermission(user, Permission.EDIT, editable)).thenReturn(true);
        when(permissionManager.hasPermission(user, Permission.EDIT, forbidden)).thenReturn(false);

        BulkLabelChangeService.ChangeResult result = service.execute("old-tag", "new-tag", user);

        assertFalse(result.hasError());
        assertEquals(1, result.getSuccessCount());
        assertEquals(1, result.getSkippedCount());
        assertEquals(0, result.getFailCount());

        // Editable page: label was swapped
        verify(labelManager).removeLabel(eq(editable), eq(oldLabel));
        verify(labelManager).addLabel(eq(editable), argThat(l ->
                l.getName().equals("new-tag") && l.getNamespace().equals(Namespace.GLOBAL)));

        // Forbidden page: never touched
        verify(labelManager, never()).removeLabel(eq(forbidden), any());
        verify(labelManager, never()).addLabel(eq(forbidden), any());
    }

    // ------------------------------------------------------------------
    //  Helpers
    // ------------------------------------------------------------------

    private Page mockPage(long id, String title, String spaceKey) {
        Page page = mock(Page.class);
        when(page.getId()).thenReturn(id);
        when(page.getTitle()).thenReturn(title);
        when(page.getSpaceKey()).thenReturn(spaceKey);
        return page;
    }

    private PartialList<ContentEntityObject> toPartialList(ContentEntityObject... items) {
        List<ContentEntityObject> list = Arrays.asList(items);
        return new PartialList<>(0, list.size(), list);
    }
}
