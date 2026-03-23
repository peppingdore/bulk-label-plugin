package com.mayflower.confluence.bulklabel.action;

import com.atlassian.confluence.user.AuthenticatedUserThreadLocal;
import com.atlassian.confluence.user.ConfluenceUser;
import com.atlassian.user.impl.DefaultUser;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.reflect.Method;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

public class ProgressServletTest {

    private static final String TEST_USER_KEY = "test-user-key";

    private ProgressServlet servlet;
    private HttpServletRequest request;
    private HttpServletResponse response;
    private StringWriter responseBody;
    private ConfluenceUser testUser;

    @Before
    public void setUp() throws Exception {
        servlet = new ProgressServlet();
        request = mock(HttpServletRequest.class);
        response = mock(HttpServletResponse.class);
        responseBody = new StringWriter();
        when(response.getWriter()).thenReturn(new PrintWriter(responseBody));

        testUser = mock(ConfluenceUser.class);
        com.atlassian.sal.api.user.UserKey userKey = new com.atlassian.sal.api.user.UserKey(TEST_USER_KEY);
        when(testUser.getKey()).thenReturn(userKey);
        AuthenticatedUserThreadLocal.set(testUser);
    }

    @After
    public void tearDown() {
        BulkLabelChangeAction.TASKS.clear();
        AuthenticatedUserThreadLocal.set(null);
    }

    @Test
    public void doGet_missingTaskId_returnsError() throws Exception {
        when(request.getParameter("taskId")).thenReturn(null);

        callDoGet();

        String json = responseBody.toString();
        assertTrue(json.contains("\"error\""));
        assertTrue(json.contains("taskId"));
    }

    @Test
    public void doGet_blankTaskId_returnsError() throws Exception {
        when(request.getParameter("taskId")).thenReturn("  ");

        callDoGet();

        String json = responseBody.toString();
        assertTrue(json.contains("\"error\""));
        assertTrue(json.contains("taskId"));
    }

    @Test
    public void doGet_unknownTask_returnsNotFound() throws Exception {
        when(request.getParameter("taskId")).thenReturn("ee55-ff66");
        when(request.getParameter("action")).thenReturn(null);

        callDoGet();

        String json = responseBody.toString();
        assertTrue(json.contains("\"error\":\"Task not found\""));
        assertTrue(json.contains("\"done\":true"));
    }

    @Test
    public void doGet_existingTask_returnsProgress() throws Exception {
        BulkLabelChangeAction.TaskProgress task =
                new BulkLabelChangeAction.TaskProgress(10, "old", "new", TEST_USER_KEY);
        task.processedCount.set(3);
        task.successCount.set(2);
        task.failCount.set(1);
        BulkLabelChangeAction.TASKS.put("aa11", task);

        when(request.getParameter("taskId")).thenReturn("aa11");
        when(request.getParameter("action")).thenReturn(null);

        callDoGet();

        String json = responseBody.toString();
        assertTrue(json.contains("\"taskId\":\"aa11\""));
        assertTrue(json.contains("\"totalCount\":10"));
        assertTrue(json.contains("\"processedCount\":3"));
        assertTrue(json.contains("\"successCount\":2"));
        assertTrue(json.contains("\"failCount\":1"));
        assertTrue(json.contains("\"done\":false"));
        assertTrue(json.contains("\"percentComplete\":30"));
        assertTrue(json.contains("\"items\":[]"));
    }

    @Test
    public void doGet_completedTask_showsDone() throws Exception {
        BulkLabelChangeAction.TaskProgress task =
                new BulkLabelChangeAction.TaskProgress(5, "a", "b", TEST_USER_KEY);
        task.processedCount.set(5);
        task.successCount.set(5);
        task.done = true;
        task.completedAt = System.currentTimeMillis();
        BulkLabelChangeAction.TASKS.put("bb22", task);

        when(request.getParameter("taskId")).thenReturn("bb22");
        when(request.getParameter("action")).thenReturn(null);

        callDoGet();

        String json = responseBody.toString();
        assertTrue(json.contains("\"done\":true"));
        assertTrue(json.contains("\"percentComplete\":100"));
    }

    @Test
    public void doGet_setsContentTypeJson() throws Exception {
        when(request.getParameter("taskId")).thenReturn(null);

        callDoGet();

        verify(response).setContentType("application/json");
        verify(response).setCharacterEncoding("UTF-8");
    }

    @Test
    public void doGet_clientCursor_incrementalItems() throws Exception {
        BulkLabelChangeAction.TaskProgress task =
                new BulkLabelChangeAction.TaskProgress(3, "a", "b", TEST_USER_KEY);
        // Simulate some processed items
        task.processedItems.add(new java.util.HashMap<>() {{
            put("id", 1L);
            put("title", "Page 1");
            put("spaceKey", "DS");
            put("type", "Page");
            put("success", true);
        }});
        task.processedCount.set(1);
        task.successCount.set(1);
        BulkLabelChangeAction.TASKS.put("cc33", task);

        // First request — should see item
        when(request.getParameter("taskId")).thenReturn("cc33");
        when(request.getParameter("action")).thenReturn(null);
        callDoGet();

        String json1 = responseBody.toString();
        assertTrue(json1.contains("\"title\":\"Page 1\""));

        // Second request — cursor advanced, no new items
        responseBody = new StringWriter();
        when(response.getWriter()).thenReturn(new PrintWriter(responseBody));

        callDoGet();
        String json2 = responseBody.toString();
        assertTrue(json2.contains("\"items\":[]"));
    }

    @Test
    public void doGet_unauthenticated_returns401() throws Exception {
        AuthenticatedUserThreadLocal.set(null);
        when(request.getParameter("taskId")).thenReturn("t1");

        callDoGet();

        verify(response).setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        String json = responseBody.toString();
        assertTrue(json.contains("\"error\":\"Authentication required\""));
    }

    @Test
    public void doGet_wrongUser_returns403() throws Exception {
        BulkLabelChangeAction.TaskProgress task =
                new BulkLabelChangeAction.TaskProgress(5, "a", "b", "other-user-key");
        BulkLabelChangeAction.TASKS.put("dd44", task);

        when(request.getParameter("taskId")).thenReturn("dd44");

        callDoGet();

        verify(response).setStatus(HttpServletResponse.SC_FORBIDDEN);
        String json = responseBody.toString();
        assertTrue(json.contains("\"error\":\"Access denied\""));
    }

    @Test
    public void escapeJson_handlesSpecialCharacters() throws Exception {
        // Access private method via reflection
        Method escapeJson = ProgressServlet.class.getDeclaredMethod("escapeJson", String.class);
        escapeJson.setAccessible(true);

        assertEquals("", escapeJson.invoke(null, (String) null));
        assertEquals("hello", escapeJson.invoke(null, "hello"));
        assertEquals("say \\\"hi\\\"", escapeJson.invoke(null, "say \"hi\""));
        assertEquals("back\\\\slash", escapeJson.invoke(null, "back\\slash"));
        assertEquals("new\\nline", escapeJson.invoke(null, "new\nline"));
        assertEquals("cr\\r", escapeJson.invoke(null, "cr\r"));
        assertEquals("tab\\t", escapeJson.invoke(null, "tab\t"));
    }

    // ------------------------------------------------------------------
    //  Helper to call protected doGet
    // ------------------------------------------------------------------

    private void callDoGet() throws Exception {
        Method doGet = ProgressServlet.class.getDeclaredMethod(
                "doGet", HttpServletRequest.class, HttpServletResponse.class);
        doGet.setAccessible(true);
        doGet.invoke(servlet, request, response);
    }
}
