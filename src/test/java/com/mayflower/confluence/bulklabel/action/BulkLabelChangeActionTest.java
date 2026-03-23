package com.mayflower.confluence.bulklabel.action;

import com.atlassian.confluence.core.ContentEntityObject;
import com.atlassian.confluence.core.PartialList;
import com.atlassian.confluence.labels.Label;
import com.atlassian.confluence.labels.LabelManager;
import com.atlassian.confluence.labels.Namespace;
import com.atlassian.confluence.pages.BlogPost;
import com.atlassian.confluence.pages.Page;
import com.atlassian.confluence.security.Permission;
import com.atlassian.confluence.security.PermissionManager;
import com.atlassian.confluence.spaces.SpaceManager;
import com.atlassian.confluence.user.ConfluenceUser;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.invocation.InvocationOnMock;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

public class BulkLabelChangeActionTest {

    private TestableAction action;
    private LabelManager labelManager;
    private PermissionManager permissionManager;
    private SpaceManager spaceManager;
    private ConfluenceUser user;

    static class TestableAction extends BulkLabelChangeAction {
        ConfluenceUser testUser;

        @Override
        protected ConfluenceUser getAuthenticatedUser() {
            return testUser;
        }
    }

    /**
     * PermissionManager has overloaded default + abstract hasPermission methods.
     * Mockito can't reliably stub them individually, so we use a custom Answer
     * that lets us control what hasPermission returns for all overloads.
     */
    private boolean permissionAnswer = false;

    private PermissionManager createPermissionManagerMock() {
        return mock(PermissionManager.class, (InvocationOnMock invocation) -> {
            if (invocation.getMethod().getReturnType() == boolean.class) return permissionAnswer;
            return null;
        });
    }

    @Before
    public void setUp() throws Exception {
        labelManager = mock(LabelManager.class);
        permissionManager = createPermissionManagerMock();
        spaceManager = mock(SpaceManager.class);
        user = mock(ConfluenceUser.class);

        com.atlassian.sal.api.user.UserKey userKey = new com.atlassian.sal.api.user.UserKey("test-user");
        when(user.getKey()).thenReturn(userKey);

        action = new TestableAction();
        action.testUser = user;
        setField(action, "labelManager", labelManager);
        setField(action, "permissionManager", permissionManager);
        action.setSpaceManager(spaceManager);

        when(spaceManager.getAllSpaces()).thenReturn(Collections.emptyList());
    }

    @After
    public void tearDown() {
        BulkLabelChangeAction.TASKS.clear();
    }

    @SuppressWarnings("unchecked")
    private static PartialList<ContentEntityObject> mockPartialList(List<? extends ContentEntityObject> items) {
        PartialList<ContentEntityObject> pl = mock(PartialList.class);
        doReturn((List<ContentEntityObject>) items).when(pl).getList();
        return pl;
    }

    // ------------------------------------------------------------------
    //  doDefault
    // ------------------------------------------------------------------

    @Test
    public void doDefault_notAuthenticated_returnsError() {
        action.testUser = null;
        assertEquals("error", action.doDefault());
    }

    @Test
    public void doDefault_authenticated_returnsInput() {
        assertEquals("input", action.doDefault());
    }

    // ------------------------------------------------------------------
    //  doPreview
    // ------------------------------------------------------------------

    @Test
    public void doPreview_notAuthenticated_returnsError() {
        action.testUser = null;
        assertEquals("error", action.doPreview());
    }

    @Test
    public void doPreview_blankSourceLabel_returnsError() {
        action.setSourceLabel("");
        action.setTargetLabel("target");
        assertEquals("error", action.doPreview());
    }

    @Test
    public void doPreview_blankTargetLabel_returnsError() {
        action.setSourceLabel("source");
        action.setTargetLabel("");
        assertEquals("error", action.doPreview());
    }

    @Test
    public void doPreview_invalidTargetLabel_returnsError() {
        action.setSourceLabel("source");
        action.setTargetLabel("INVALID LABEL!@#");
        assertEquals("error", action.doPreview());
    }

    @Test
    public void doPreview_noContentFound_returnsSuccessWithMessage() {
        action.setSourceLabel("source");
        action.setTargetLabel("target");
        action.setAllSpaces(true);
        when(labelManager.getLabel("source")).thenReturn(null);

        assertEquals("success", action.doPreview());
        Map<String, Object> preview = action.getPreviewResult();
        assertNotNull(preview);
        assertEquals(0, preview.get("editableCount"));
    }

    @Test
    public void doPreview_withEditableContent_returnsItems() {
        action.setSourceLabel("oldlabel");
        action.setTargetLabel("newlabel");
        action.setAllSpaces(true);
        permissionAnswer = true;

        Label label = new Label("oldlabel", Namespace.GLOBAL);
        Page page = mock(Page.class);
        when(page.getId()).thenReturn(1L);
        when(page.getTitle()).thenReturn("Test Page");
        when(page.getSpaceKey()).thenReturn("DS");
        when(page.getSpace()).thenReturn(null);

        PartialList<ContentEntityObject> pl = mockPartialList(List.of(page));

        when(labelManager.getLabel("oldlabel")).thenReturn(label);
        when(labelManager.getContentForLabel(anyInt(), anyInt(), any(Label.class))).thenReturn(pl);

        assertEquals("success", action.doPreview());
        Map<String, Object> preview = action.getPreviewResult();
        assertEquals(1, preview.get("editableCount"));
        assertEquals(0, preview.get("skippedCount"));
    }

    @Test
    public void doPreview_noPermission_countsAsSkipped() {
        action.setSourceLabel("oldlabel");
        action.setTargetLabel("newlabel");
        action.setAllSpaces(true);
        permissionAnswer = false;

        Label label = new Label("oldlabel", Namespace.GLOBAL);
        Page page = mock(Page.class);
        when(page.getId()).thenReturn(1L);
        when(page.getTitle()).thenReturn("Test Page");
        when(page.getSpaceKey()).thenReturn("DS");

        PartialList<ContentEntityObject> pl = mockPartialList(List.of(page));

        when(labelManager.getLabel("oldlabel")).thenReturn(label);
        when(labelManager.getContentForLabel(anyInt(), anyInt(), any(Label.class))).thenReturn(pl);

        assertEquals("success", action.doPreview());
        Map<String, Object> preview = action.getPreviewResult();
        assertEquals(0, preview.get("editableCount"));
        assertEquals(1, preview.get("skippedCount"));
    }

    // ------------------------------------------------------------------
    //  doExecute
    // ------------------------------------------------------------------

    @Test
    public void doExecute_notAuthenticated_returnsError() {
        action.testUser = null;
        assertEquals("error", action.doExecute());
    }

    @Test
    public void doExecute_sameSourceAndTarget_returnsError() {
        action.setSourceLabel("same");
        action.setTargetLabel("same");
        assertEquals("error", action.doExecute());
    }

    @Test
    public void doExecute_noEditableContent_returnsError() {
        action.setSourceLabel("old");
        action.setTargetLabel("new-label");
        action.setAllSpaces(true);
        when(labelManager.getLabel("old")).thenReturn(null);

        assertEquals("error", action.doExecute());
    }

    @Test
    public void doExecute_withContent_createsTaskAndReturnsSuccess() {
        action.setSourceLabel("old");
        action.setTargetLabel("new-label");
        action.setAllSpaces(true);
        permissionAnswer = true;

        Label label = new Label("old", Namespace.GLOBAL);
        Page page = mock(Page.class);
        when(page.getId()).thenReturn(42L);
        when(page.getSpaceKey()).thenReturn("DS");

        PartialList<ContentEntityObject> pl = mockPartialList(List.of(page));

        when(labelManager.getLabel("old")).thenReturn(label);
        when(labelManager.getContentForLabel(anyInt(), anyInt(), any(Label.class))).thenReturn(pl);

        assertEquals("success", action.doExecute());
        assertNotNull(action.getTaskId());
        assertTrue(BulkLabelChangeAction.TASKS.containsKey(action.getTaskId()));

        BulkLabelChangeAction.TaskProgress task = BulkLabelChangeAction.TASKS.get(action.getTaskId());
        assertEquals(1, task.totalCount);
        assertEquals("old", task.sourceLabel);
        assertEquals("new-label", task.targetLabel);
        assertTrue(task.remainingIds.contains(42L));
    }

    // ------------------------------------------------------------------
    //  doResults
    // ------------------------------------------------------------------

    @Test
    public void doResults_withCompletedTask_populatesChangeResult() {
        BulkLabelChangeAction.TaskProgress task =
                new BulkLabelChangeAction.TaskProgress(5, "old", "new", "test-user");
        task.successCount.set(4);
        task.failCount.set(1);
        task.processedCount.set(5);
        task.done = true;
        task.completedAt = System.currentTimeMillis();

        String taskId = "aabb0011";
        BulkLabelChangeAction.TASKS.put(taskId, task);
        action.setTaskId(taskId);

        assertEquals("success", action.doResults());
        Map<String, Object> result = action.getChangeResult();
        assertNotNull(result);
        assertEquals(4, result.get("successCount"));
        assertEquals(1, result.get("failCount"));
    }

    @Test
    public void doResults_noTaskId_returnsSuccessWithNullResult() {
        action.setTaskId(null);
        assertEquals("success", action.doResults());
        assertNull(action.getChangeResult());
    }

    // ------------------------------------------------------------------
    //  TaskProgress / getTaskProgress
    // ------------------------------------------------------------------

    @Test
    public void getTaskProgress_returnsCorrectMap() {
        BulkLabelChangeAction.TaskProgress task =
                new BulkLabelChangeAction.TaskProgress(10, "src", "tgt", "test-user");
        task.processedCount.set(5);
        task.successCount.set(4);
        task.failCount.set(1);

        BulkLabelChangeAction.TASKS.put("aabb0022", task);
        action.setTaskId("aabb0022");

        Map<String, Object> progress = action.getTaskProgress();
        assertNotNull(progress);
        assertEquals("aabb0022", progress.get("taskId"));
        assertEquals(10, progress.get("totalCount"));
        assertEquals(5, progress.get("processedCount"));
        assertEquals(4, progress.get("successCount"));
        assertEquals(1, progress.get("failCount"));
        assertEquals(false, progress.get("done"));
        assertEquals(50, progress.get("percentComplete"));
    }

    @Test
    public void getTaskProgress_noTask_returnsNull() {
        action.setTaskId("nonexistent");
        assertNull(action.getTaskProgress());
    }

    // ------------------------------------------------------------------
    //  Evict stale tasks
    // ------------------------------------------------------------------

    @Test
    public void evictStaleTasks_removesOldCompletedTasks() {
        BulkLabelChangeAction.TaskProgress stale =
                new BulkLabelChangeAction.TaskProgress(1, "a", "b", "test-user");
        stale.done = true;
        stale.completedAt = System.currentTimeMillis() - 11 * 60 * 1000;

        BulkLabelChangeAction.TaskProgress fresh =
                new BulkLabelChangeAction.TaskProgress(1, "c", "d", "test-user");
        fresh.done = true;
        fresh.completedAt = System.currentTimeMillis();

        BulkLabelChangeAction.TaskProgress running =
                new BulkLabelChangeAction.TaskProgress(1, "e", "f", "test-user");

        BulkLabelChangeAction.TASKS.put("stale", stale);
        BulkLabelChangeAction.TASKS.put("fresh", fresh);
        BulkLabelChangeAction.TASKS.put("running", running);

        // Trigger eviction via doExecute
        action.setSourceLabel("x");
        action.setTargetLabel("y");
        action.setAllSpaces(true);
        when(labelManager.getLabel("x")).thenReturn(null);
        action.doExecute();

        assertFalse(BulkLabelChangeAction.TASKS.containsKey("stale"));
        assertTrue(BulkLabelChangeAction.TASKS.containsKey("fresh"));
        assertTrue(BulkLabelChangeAction.TASKS.containsKey("running"));
    }

    // ------------------------------------------------------------------
    //  Space filter
    // ------------------------------------------------------------------

    @Test
    public void doPreview_withSpaceFilter_filtersContent() {
        action.setSourceLabel("label");
        action.setTargetLabel("newlabel");
        action.setAllSpaces(false);
        action.setSpaceKeys(new String[]{"DS"});
        permissionAnswer = true;

        Label label = new Label("label", Namespace.GLOBAL);

        Page pageInDs = mock(Page.class);
        when(pageInDs.getId()).thenReturn(1L);
        when(pageInDs.getTitle()).thenReturn("In DS");
        when(pageInDs.getSpaceKey()).thenReturn("DS");
        when(pageInDs.getSpace()).thenReturn(null);

        Page pageInOther = mock(Page.class);
        when(pageInOther.getId()).thenReturn(2L);
        when(pageInOther.getTitle()).thenReturn("In OTHER");
        when(pageInOther.getSpaceKey()).thenReturn("OTHER");

        PartialList<ContentEntityObject> pl = mockPartialList(List.of(pageInDs, pageInOther));

        when(labelManager.getLabel("label")).thenReturn(label);
        when(labelManager.getContentForLabel(anyInt(), anyInt(), any(Label.class))).thenReturn(pl);

        assertEquals("success", action.doPreview());
        Map<String, Object> preview = action.getPreviewResult();
        assertEquals(1, preview.get("editableCount"));
    }

    // ------------------------------------------------------------------
    //  Label validation
    // ------------------------------------------------------------------

    @Test
    public void doPreview_validTargetLabels_accepted() {
        when(labelManager.getLabel(anyString())).thenReturn(null);

        for (String valid : new String[]{"abc", "a-b", "a_b", "a.b", "abc123", "0start"}) {
            action.setSourceLabel("source");
            action.setTargetLabel(valid);
            assertEquals("Valid label '" + valid + "' should be accepted", "success", action.doPreview());
        }
    }

    @Test
    public void doPreview_invalidTargetLabels_rejected() {
        for (String invalid : new String[]{"has space", "special!char", "-start", ".start", "_start"}) {
            action.setSourceLabel("source");
            action.setTargetLabel(invalid);
            assertEquals("Invalid label '" + invalid + "' should be rejected", "error", action.doPreview());
        }
    }

    // ------------------------------------------------------------------
    //  getProgressJson
    // ------------------------------------------------------------------

    @Test
    public void getProgressJson_withTask_returnsJson() {
        BulkLabelChangeAction.TaskProgress task =
                new BulkLabelChangeAction.TaskProgress(10, "s", "t", "test-user");
        task.processedCount.set(3);
        task.successCount.set(2);
        task.failCount.set(1);
        BulkLabelChangeAction.TASKS.put("aabb0033", task);
        action.setTaskId("aabb0033");

        String json = action.getProgressJson();
        assertTrue(json.contains("\"taskId\":\"aabb0033\""));
        assertTrue(json.contains("\"totalCount\":10"));
        assertTrue(json.contains("\"processedCount\":3"));
        assertTrue(json.contains("\"done\":false"));
        assertTrue(json.contains("\"percentComplete\":30"));
    }

    @Test
    public void getProgressJson_noTask_returnsErrorJson() {
        action.setTaskId("missing");
        String json = action.getProgressJson();
        assertTrue(json.contains("\"error\""));
        assertTrue(json.contains("\"done\":true"));
    }

    // ------------------------------------------------------------------
    //  Blog post handling
    // ------------------------------------------------------------------

    @Test
    public void doPreview_blogPost_mappedCorrectly() {
        action.setSourceLabel("label");
        action.setTargetLabel("newlabel");
        action.setAllSpaces(true);
        permissionAnswer = true;

        Label label = new Label("label", Namespace.GLOBAL);
        BlogPost blog = mock(BlogPost.class);
        when(blog.getId()).thenReturn(99L);
        when(blog.getTitle()).thenReturn("My Blog");
        when(blog.getSpaceKey()).thenReturn("BLOG");
        when(blog.getSpace()).thenReturn(null);

        PartialList<ContentEntityObject> pl = mockPartialList(List.of(blog));

        when(labelManager.getLabel("label")).thenReturn(label);
        when(labelManager.getContentForLabel(anyInt(), anyInt(), any(Label.class))).thenReturn(pl);

        assertEquals("success", action.doPreview());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> items = (List<Map<String, Object>>) action.getPreviewResult().get("items");
        assertEquals(1, items.size());
        assertEquals("Blog Post", items.get(0).get("type"));
        assertEquals("My Blog", items.get(0).get("title"));
    }

    // ------------------------------------------------------------------
    //  Helpers
    // ------------------------------------------------------------------

    private static void setField(Object target, String fieldName, Object value) throws Exception {
        Class<?> clazz = target.getClass();
        while (clazz != null) {
            try {
                Field field = clazz.getDeclaredField(fieldName);
                field.setAccessible(true);
                field.set(target, value);
                return;
            } catch (NoSuchFieldException e) {
                clazz = clazz.getSuperclass();
            }
        }
        throw new NoSuchFieldException(fieldName);
    }
}
