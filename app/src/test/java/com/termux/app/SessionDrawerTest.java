package com.termux.app;

import android.app.Application;
import android.graphics.Rect;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.ScrollView;

import com.termux.R;
import com.termux.app.terminal.TerminalBookmark;
import com.termux.app.terminal.TerminalBookmarkStore;
import com.termux.app.terminal.TerminalBookmarksListViewController;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;

import java.util.Collections;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 31, application = Application.class, qualifiers = "zh-rCN-w320dp-h640dp")
public class SessionDrawerTest {
    @Test
    public void sessionsStayVisibleWhenRefreshingAndRestoringOldPreferences() {
        TermuxActivity activity = host(true);
        View drawer = activity.findViewById(R.id.left_drawer);
        ListView sessions = activity.findViewById(R.id.terminal_sessions_list);
        measure(drawer, 640);
        int actionTop = activity.findViewById(R.id.terminal_drawer_actions).getTop();
        assertFalse(activity.findViewById(R.id.terminal_sessions_header).performClick());
        ((ArrayAdapter<?>) sessions.getAdapter()).notifyDataSetChanged();
        measure(drawer, 640);
        assertEquals(View.VISIBLE, sessions.getVisibility());
        assertTrue(sessions.getChildCount() > 0);
        assertEquals(actionTop, activity.findViewById(R.id.terminal_drawer_actions).getTop());

        activity.getSharedPreferences("terminal_sessions", 0).edit().putBoolean("collapsed", true).commit();
        TermuxActivity restored = host(false);
        ListView restoredSessions = restored.findViewById(R.id.terminal_sessions_list);
        measure(restored.findViewById(R.id.left_drawer), 640);
        assertEquals(View.VISIBLE, restoredSessions.getVisibility());
        assertTrue(restoredSessions.getChildCount() > 0);
    }

    @Test
    public void compactDrawerKeepsTitleSessionsAndNewSessionActionAvailable() {
        TermuxActivity activity = host(true);
        ReflectionHelpers.callInstanceMethod(activity, "setAdaptiveDrawerLayout");
        ViewGroup drawer = activity.findViewById(R.id.left_drawer);
        ListView sessions = activity.findViewById(R.id.terminal_sessions_list);
        ScrollView scroll = activity.findViewById(R.id.terminal_drawer_scroll);
        View action = activity.findViewById(R.id.new_session_button);
        for (int height : new int[]{320, 640, 320}) {
            measure(drawer, height);
            assertEquals(View.VISIBLE, activity.findViewById(R.id.terminal_sessions_header).getVisibility());
            assertEquals(View.VISIBLE, sessions.getVisibility());
            assertTrue(sessions.getChildCount() > 0);
            scroll.scrollTo(0, scroll.getChildAt(0).getHeight());
            Rect card = bounds(drawer, sessions.getChildAt(0));
            assertTrue(card.top >= bounds(drawer, scroll).top);
            assertTrue(card.bottom <= bounds(drawer, action).top);
            assertEquals(dp(drawer, 56), action.getWidth());
            assertEquals(dp(drawer, 56), action.getHeight());
            assertEquals(dp(drawer, 16), drawer.getHeight() - bounds(drawer, action).bottom);
        }
    }

    @Test
    public void bookmarksAndSessionsScrollTogetherWhileHeaderAndFabStayFixed() {
        ActivityController<TermuxActivity> controller = Robolectric.buildActivity(TermuxActivity.class);
        TermuxActivity activity = host(controller, true, 8);
        TerminalBookmarkStore store = new TerminalBookmarkStore(activity);
        for (int i = 0; i < 8; i++) {
            store.add(new TerminalBookmark("bookmark-" + i, "Bookmark " + i, "local", "",
                Collections.emptyList(), "/tmp/" + i));
        }
        new TerminalBookmarksListViewController(activity, store, item -> {});
        controller.visible();
        activity.getDrawer().openDrawer(Gravity.START, false);
        measure(activity.getDrawer(), 640);

        ViewGroup drawer = activity.findViewById(R.id.left_drawer);
        ScrollView scroll = activity.findViewById(R.id.terminal_drawer_scroll);
        ListView bookmarks = activity.findViewById(R.id.terminal_bookmarks_list);
        ListView sessions = activity.findViewById(R.id.terminal_sessions_list);
        View header = activity.findViewById(R.id.terminal_drawer_header);
        View fab = activity.findViewById(R.id.new_session_button);
        View bookmarkTitle = activity.findViewById(R.id.terminal_bookmarks_header);
        View sessionTitle = activity.findViewById(R.id.terminal_sessions_header);
        assertEquals(8, bookmarks.getChildCount());
        assertEquals(8, sessions.getChildCount());
        int cardWidth = scroll.getWidth() - scroll.getPaddingLeft() - scroll.getPaddingRight();
        assertCardSizesAndSpacing(bookmarks, cardWidth);
        assertCardSizesAndSpacing(sessions, cardWidth);
        assertFalse(bookmarks.canScrollVertically(1));
        assertFalse(bookmarks.canScrollVertically(-1));
        assertFalse(sessions.canScrollVertically(1));
        assertFalse(sessions.canScrollVertically(-1));
        assertTrue(scroll.canScrollVertically(1));
        Rect fixedHeader = bounds(drawer, header);
        Rect fixedFab = bounds(drawer, fab);
        int bookmarkTitleTop = bounds(drawer, bookmarkTitle).top;
        int sessionTitleTop = bounds(drawer, sessionTitle).top;

        scroll.scrollTo(0, dp(drawer, 144));
        assertTrue(scroll.getScrollY() > 0);
        assertEquals(bookmarkTitleTop - scroll.getScrollY(), bounds(drawer, bookmarkTitle).top);
        assertEquals(sessionTitleTop - scroll.getScrollY(), bounds(drawer, sessionTitle).top);
        assertEquals(fixedHeader, bounds(drawer, header));
        assertEquals(fixedFab, bounds(drawer, fab));
        assertEquals(0, bookmarks.getFirstVisiblePosition());
        assertEquals(0, sessions.getFirstVisiblePosition());

        scroll.scrollTo(0, scroll.getChildAt(0).getHeight());
        Rect lastSession = bounds(drawer, sessions.getChildAt(sessions.getChildCount() - 1));
        assertTrue("The last card must scroll fully above the floating action", lastSession.bottom <= fixedFab.top);
        assertTrue(lastSession.top >= bounds(drawer, scroll).top);
        assertFalse(scroll.canScrollVertically(1));
        assertEquals(fixedHeader, bounds(drawer, header));
        assertEquals(fixedFab, bounds(drawer, fab));
    }

    @Test
    public void drawerFillsContentAndActionsFollowRightEdgeWhenViewportChanges() {
        ActivityController<TermuxActivity> controller = Robolectric.buildActivity(TermuxActivity.class);
        TermuxActivity activity = host(controller, true, 2);
        TerminalBookmarkStore store = new TerminalBookmarkStore(activity);
        for (int i = 0; i < 2; i++) {
            store.add(new TerminalBookmark("bookmark-" + i, "Bookmark " + i, "local", "",
                Collections.emptyList(), "/tmp/" + i));
        }
        new TerminalBookmarksListViewController(activity, store, item -> {});
        controller.visible();
        activity.getDrawer().openDrawer(Gravity.START, false);
        ViewGroup content = activity.getDrawer();
        ViewGroup drawer = activity.findViewById(R.id.left_drawer);
        View fab = activity.findViewById(R.id.new_session_button);
        View settings = activity.findViewById(R.id.settings_button);
        View files = activity.findViewById(R.id.file_system_button);
        ListView bookmarks = activity.findViewById(R.id.terminal_bookmarks_list);
        ListView sessions = activity.findViewById(R.id.terminal_sessions_list);
        int previousWidth = 0;
        int previousSettingsRight = 0;
        int previousFilesRight = 0;
        for (int[] size : new int[][]{{320, 640}, {600, 320}, {360, 720}, {320, 320}}) {
            measure(content, size[0], size[1]);
            assertEquals(content.getWidth(), drawer.getWidth());
            assertEquals(0, bounds(content, drawer).left);
            assertEquals(content.getWidth(), bounds(content, drawer).right);
            Rect action = bounds(drawer, fab);
            assertEquals(dp(drawer, 56), action.width());
            assertEquals(dp(drawer, 56), action.height());
            assertEquals(dp(drawer, 16), drawer.getWidth() - action.right);
            assertEquals(dp(drawer, 16), drawer.getHeight() - action.bottom);
            int settingsRight = bounds(drawer, settings).right;
            int filesRight = bounds(drawer, files).right;
            if (previousWidth != 0) {
                int widthChange = drawer.getWidth() - previousWidth;
                assertEquals(widthChange, settingsRight - previousSettingsRight);
                assertEquals(widthChange, filesRight - previousFilesRight);
            }
            previousWidth = drawer.getWidth();
            previousSettingsRight = settingsRight;
            previousFilesRight = filesRight;
            int cardWidth = drawer.getWidth() - dp(drawer, 24);
            assertEquals(2, bookmarks.getChildCount());
            assertEquals(2, sessions.getChildCount());
            assertCardSizesAndSpacing(bookmarks, cardWidth);
            assertCardSizesAndSpacing(sessions, cardWidth);
            for (ListView list : new ListView[]{bookmarks, sessions}) {
                Rect card = bounds(drawer, list.getChildAt(0));
                assertEquals(dp(drawer, 12), card.left);
                assertEquals(dp(drawer, 12), drawer.getWidth() - card.right);
            }
        }
    }

    @Test
    public void sessionTitleHasSameGapToFirstCardAsBookmarks() {
        ActivityController<TermuxActivity> controller = Robolectric.buildActivity(TermuxActivity.class);
        TermuxActivity activity = host(controller, true);
        TerminalBookmarkStore store = new TerminalBookmarkStore(activity);
        store.add(new TerminalBookmark("first", "First", "local", "", Collections.emptyList(), "/tmp"));
        TerminalBookmarksListViewController bookmarks =
            new TerminalBookmarksListViewController(activity, store, item -> {});
        controller.visible();
        activity.getDrawer().openDrawer(Gravity.START, false);
        ViewGroup drawer = activity.getDrawer();
        measure(drawer, 640);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        measure(drawer, 640);
        ListView bookmarkList = activity.findViewById(R.id.terminal_bookmarks_list);
        ListView sessionList = activity.findViewById(R.id.terminal_sessions_list);
        assertNotNull(bookmarkList.getChildAt(0));
        assertNotNull(sessionList.getChildAt(0));
        int bookmarkGap = bounds(drawer, bookmarkList.getChildAt(0)).top
            - bounds(drawer, activity.findViewById(R.id.terminal_bookmarks_header)).bottom;
        int sessionGap = bounds(drawer, sessionList.getChildAt(0)).top
            - bounds(drawer, activity.findViewById(R.id.terminal_sessions_header)).bottom;
        assertEquals(bookmarkGap, sessionGap);
    }

    private static TermuxActivity host(boolean clearPreferences) {
        return host(Robolectric.buildActivity(TermuxActivity.class), clearPreferences);
    }

    private static TermuxActivity host(ActivityController<TermuxActivity> controller, boolean clearPreferences) {
        return host(controller, clearPreferences, 1);
    }

    private static TermuxActivity host(ActivityController<TermuxActivity> controller, boolean clearPreferences,
                                        int sessionCount) {
        TermuxActivity activity = controller.get();
        activity.setTheme(R.style.Theme_TermuxActivity_DayNight_NoActionBar);
        activity.setContentView(R.layout.activity_termux);
        if (clearPreferences) {
            activity.getSharedPreferences("terminal_sessions", 0).edit().clear().commit();
            activity.getSharedPreferences("terminal_bookmarks", 0).edit().clear().commit();
        }
        ListView sessions = activity.findViewById(R.id.terminal_sessions_list);
        sessions.setAdapter(new ArrayAdapter<String>(activity, R.layout.item_terminal_sessions_list,
            Collections.nCopies(sessionCount, "Session")) {
            @Override
            public View getView(int position, View recycled, ViewGroup parent) {
                return activity.getLayoutInflater().inflate(R.layout.item_terminal_sessions_list, parent, false);
            }
        });
        return activity;
    }

    private static void measure(View view, int heightDp) {
        measure(view, 320, heightDp);
    }

    private static void measure(View view, int widthDp, int heightDp) {
        measureOnce(view, widthDp, heightDp);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        measureOnce(view, widthDp, heightDp);
    }

    private static void measureOnce(View view, int widthDp, int heightDp) {
        int width = dp(view, widthDp), height = dp(view, heightDp);
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        view.layout(0, 0, width, height);
    }

    private static void assertCardSizesAndSpacing(ListView list, int expectedWidth) {
        assertTrue(list.getChildCount() > 0);
        for (int i = 0; i < list.getChildCount(); i++) {
            View card = list.getChildAt(i);
            assertEquals(expectedWidth, card.getWidth());
            assertEquals(dp(list, 64), card.getHeight());
            if (i > 0) assertEquals(dp(list, 8), card.getTop() - list.getChildAt(i - 1).getBottom());
        }
    }

    private static int dp(View view, int value) {
        return Math.round(value * view.getResources().getDisplayMetrics().density);
    }

    private static Rect bounds(ViewGroup parent, View view) {
        Rect bounds = new Rect();
        view.getDrawingRect(bounds);
        parent.offsetDescendantRectToMyCoords(view, bounds);
        return bounds;
    }
}
