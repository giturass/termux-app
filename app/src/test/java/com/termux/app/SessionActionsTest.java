package com.termux.app;

import android.app.Application;
import android.content.Intent;
import android.graphics.Rect;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ListView;
import android.widget.PopupWindow;
import android.widget.ScrollView;
import android.widget.EditText;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.drawerlayout.widget.DrawerLayout;

import com.termux.R;
import com.termux.app.terminal.TermuxSessionsListViewController;
import com.termux.app.terminal.TermuxTerminalSessionActivityClient;
import com.termux.app.terminal.TermuxTerminalViewClient;
import com.termux.shared.shell.command.ExecutionCommand;
import com.termux.shared.termux.shell.TermuxShellManager;
import com.termux.shared.termux.shell.command.runner.terminal.TermuxSession;
import com.termux.shared.termux.terminal.TermuxTerminalSessionClientBase;
import com.termux.shared.termux.settings.properties.TermuxAppSharedProperties;
import com.termux.shared.termux.settings.properties.TermuxPropertyConstants;
import com.termux.shared.termux.settings.preferences.TermuxAppSharedPreferences;
import com.termux.terminal.TerminalSession;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowDialog;
import org.robolectric.shadows.ShadowApplication;
import org.robolectric.shadows.ShadowToast;
import org.robolectric.util.ReflectionHelpers;
import org.robolectric.util.ReflectionHelpers.ClassParameter;

import java.time.Duration;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 31, application = Application.class, qualifiers = "w320dp-h640dp", shadows = SessionActionsTest.ShadowTerminalSession.class)
public class SessionActionsTest {
    private ActivityController<TermuxActivity> controller;
    private TermuxActivity activity;
    private TermuxService service;
    private TermuxShellManager manager;
    private TermuxTerminalSessionActivityClient client;

    @Before
    public void setUp() {
        // Attach hosts without starting a native shell or the foreground service.
        controller = Robolectric.buildActivity(TermuxActivity.class);
        activity = controller.get();
        activity.setTheme(R.style.Theme_TermuxActivity_DayNight_NoActionBar);
        activity.setContentView(R.layout.activity_termux);
        activity.mTerminalView = activity.findViewById(R.id.terminal_view);
        activity.mTerminalView.setVisibility(View.GONE);
        ReflectionHelpers.setField(activity, "mPreferences", TermuxAppSharedPreferences.build(activity));
        service = Robolectric.buildService(TermuxService.class).get();
        manager = new TermuxShellManager(activity);
        ReflectionHelpers.setField(service, "mShellManager", manager);
        ReflectionHelpers.setField(activity, "mTermuxService", service);
        client = new TermuxTerminalSessionActivityClient(activity) {
            @Override public void setCurrentSession(TerminalSession session) {
                // Avoid attaching a native PTY while exercising session selection.
                activity.mTerminalView.mTermSession = session;
            }
        };
        ReflectionHelpers.setField(activity, "mTermuxTerminalSessionActivityClient", client);
        activity.mTermuxTerminalViewClient = new TermuxTerminalViewClient(activity, client);
        activity.mTerminalView.setTerminalViewClient(activity.mTermuxTerminalViewClient);
        ReflectionHelpers.callInstanceMethod(activity, "setAdaptiveDrawerLayout");
    }

    @Test
    public void sessionChangeToastRespectsPropertiesSetting() {
        TermuxSession session = addSession(true);
        client.setCurrentSession(session.getTerminalSession());
        ReflectionHelpers.setField(activity, "mIsVisible", true);
        TermuxAppSharedProperties properties = TermuxAppSharedProperties.init(activity);
        properties.loadTermuxPropertiesFromDisk();
        ReflectionHelpers.setField(activity, "mProperties", properties);
        Object shared = ReflectionHelpers.getField(properties, "mSharedProperties");
        java.util.Map<String, Object> values = ReflectionHelpers.getField(shared, "mMap");
        values.put(TermuxPropertyConstants.KEY_DISABLE_TERMINAL_SESSION_CHANGE_TOAST, true);
        ShadowToast.reset();

        ReflectionHelpers.callInstanceMethod(client, "notifyOfSessionChange");
        assertNull(ShadowToast.getTextOfLatestToast());

        values.put(TermuxPropertyConstants.KEY_DISABLE_TERMINAL_SESSION_CHANGE_TOAST, false);
        ReflectionHelpers.callInstanceMethod(client, "notifyOfSessionChange");
        assertEquals("[1]", ShadowToast.getTextOfLatestToast());
    }

    @Test
    public void closingRunningBackgroundSessionRemovesOnlyThatSessionAndPreservesSelection() {
        TermuxSession background = addSession(true);
        TermuxSession current = addSession(true);
        client.setCurrentSession(current.getTerminalSession());

        client.closeSession(background.getTerminalSession());

        assertEquals(1, service.getTermuxSessionsSize());
        assertSame(current, service.getTermuxSession(0));
        assertSame(current.getTerminalSession(), activity.getCurrentSession());
        assertTrue(wasKilled(background));
        assertFalse(wasKilled(current));
        assertEquals(Integer.valueOf(137), background.getExecutionCommand().resultData.exitCode);
        assertSame(service.getTermuxTerminalSessionClient(),
            ReflectionHelpers.getField(background.getTerminalSession(), "mClient"));
    }

    @Test
    public void closingFinishedCurrentSessionSelectsNeighborAndClosingLastReturnsToDrawer() {
        TermuxSession first = addSession(false);
        TermuxSession last = addSession(false);
        client.setCurrentSession(last.getTerminalSession());

        activity.showCloseSessionDialog(last.getTerminalSession());
        assertEquals(1, service.getTermuxSessionsSize());
        assertSame(first.getTerminalSession(), activity.getCurrentSession());
        assertFalse(activity.isFinishing());
        assertFalse(wasKilled(last));

        activity.showCloseSessionDialog(first.getTerminalSession());
        assertEquals(0, service.getTermuxSessionsSize());
        assertFalse(activity.isFinishing());
        assertNull(activity.getCurrentSession());
        assertTrue(activity.getDrawer().isDrawerOpen(Gravity.START));
        assertEquals(DrawerLayout.LOCK_MODE_LOCKED_OPEN, activity.getDrawer().getDrawerLockMode(Gravity.START));
        assertEquals(View.GONE, activity.getTerminalToolbar().getVisibility());
        activity.onBackPressed();
        assertTrue(activity.isFinishing());
    }

    @Test
    public void emptyServiceRemainsAvailableUntilActivityUnbinds() {
        service.setTermuxTerminalSessionClient(client);

        service.onTermuxSessionExited(null);
        assertFalse(Shadows.shadowOf(service).isStoppedBySelf());

        service.onUnbind(new Intent());
        assertTrue(Shadows.shadowOf(service).isStoppedBySelf());
    }

    @Test
    public void stoppingServiceAlsoClosesActivityWithoutSessions() {
        service.setTermuxTerminalSessionClient(client);

        ReflectionHelpers.callInstanceMethod(service, "actionStopService");

        assertTrue(activity.isFinishing());
        assertTrue(Shadows.shadowOf(service).isStoppedBySelf());
    }

    @Test
    public void closeDialogCanBeCancelledAndKeepsItsTargetWhenCurrentSessionChanges() {
        TermuxSession first = addSession(true);
        TermuxSession second = addSession(true);
        client.setCurrentSession(first.getTerminalSession());
        activity.showCloseSessionDialog(first.getTerminalSession());
        AlertDialog dialog = (AlertDialog) ShadowDialog.getLatestDialog();
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals(2, service.getTermuxSessionsSize());
        assertFalse(wasKilled(first));

        activity.showCloseSessionDialog(first.getTerminalSession());
        dialog = (AlertDialog) ShadowDialog.getLatestDialog();
        client.setCurrentSession(second.getTerminalSession());
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertSame(second.getTerminalSession(), activity.getCurrentSession());
        assertTrue(wasKilled(first));
        assertFalse(wasKilled(second));

        // A repeated action from a stale menu must not select or remove another session.
        client.closeSession(first.getTerminalSession());
        assertEquals(1, service.getTermuxSessionsSize());
        assertSame(second.getTerminalSession(), activity.getCurrentSession());
    }

    @Test
    public void tappingSessionOverflowOpensMenuWithoutSelectingOrClosingDrawer() {
        verifyOverflowTouch(320, 640, 150, false);
    }

    @Test
    public void tappingSessionNameSwitchesToThatSession() {
        verifySessionTouch(false);
    }

    @Test
    public void refreshingSessionDuringNameTapStillSwitchesToThatSession() {
        verifySessionTouch(true);
    }

    private void verifySessionTouch(boolean refresh) {
        TermuxSession target = addSession(true);
        target.getTerminalSession().mSessionName = "Background session";
        TermuxSession current = addSession(true);
        client.setCurrentSession(current.getTerminalSession());
        ReflectionHelpers.callInstanceMethod(activity, "setTermuxSessionsListView");
        activity.mTerminalView.setVisibility(View.GONE);
        controller.visible();
        ViewGroup drawer = activity.getDrawer();
        measure(drawer, 320, 640);
        activity.getDrawer().openDrawer(Gravity.START, false);
        measure(drawer, 320, 640);
        ListView sessions = activity.findViewById(R.id.terminal_sessions_list);
        View name = sessions.getChildAt(0).findViewById(R.id.session_name);
        Rect bounds = new Rect(0, 0, name.getWidth(), name.getHeight());
        drawer.offsetDescendantRectToMyCoords(name, bounds);
        assertTrue(name.isShown());
        assertTrue(bounds.width() > 0);
        long time = SystemClock.uptimeMillis();
        MotionEvent down = MotionEvent.obtain(time, time, MotionEvent.ACTION_DOWN,
            bounds.exactCenterX(), bounds.exactCenterY(), 0);
        drawer.dispatchTouchEvent(down);
        down.recycle();
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(150));
        if (refresh) {
            target.getTerminalSession().mSessionName = "Updated session";
            activity.termuxSessionListNotifyUpdated();
            measure(drawer, 320, 640);
        }
        MotionEvent up = MotionEvent.obtain(time, time + 150, MotionEvent.ACTION_UP,
            bounds.exactCenterX(), bounds.exactCenterY(), 0);
        drawer.dispatchTouchEvent(up);
        up.recycle();
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300));
        assertSame(target.getTerminalSession(), activity.getCurrentSession());
        assertNull(ShadowApplication.getInstance().getLatestPopupWindow());
        assertFalse(wasKilled(target));
        assertFalse(wasKilled(current));
    }

    @Test
    @Config(qualifiers = "w288dp-h320dp")
    public void quickTapOnSessionOverflowWorksInCompactDrawer() {
        verifyOverflowTouch(288, 320, 0, false);
    }

    @Test
    @Config(qualifiers = "w288dp-h320dp")
    public void heldTapOnSessionOverflowWorksInCompactDrawer() {
        verifyOverflowTouch(288, 320, 400, false);
    }

    @Test
    public void sessionRefreshDuringTapStillOpensTheTargetMenu() {
        verifyOverflowTouch(320, 640, 150, true);
    }

    @Test
    public void sessionSummaryScrollsOnlyAfterThatSessionsOutputHasBeenIdle() {
        TermuxSession active = addSession(true);
        TermuxSession other = addSession(true);
        setSummary(active, "A long changing command summary abcdefghijklmnopqrstuvwxyz");
        setSummary(other, "Another long command summary abcdefghijklmnopqrstuvwxyz");
        ListView sessions = showSessionDrawer();
        TextView activeSummary = sessions.getChildAt(0).findViewById(R.id.session_title);
        TextView otherSummary = sessions.getChildAt(1).findViewById(R.id.session_title);
        assertSummaryScrolling(activeSummary, true);
        assertSummaryScrolling(otherSummary, true);

        client.onTextChanged(active.getTerminalSession());
        assertSummaryScrolling(activeSummary, false);
        assertSummaryScrolling(otherSummary, true);
        assertEquals(1, activeSummary.getMaxLines());
        assertEquals("A long changing command summary abcdefghijklmnopqrstuvwxyz",
            activeSummary.getText().toString());

        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1500));
        client.onTextChanged(active.getTerminalSession());
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1999));
        assertSummaryScrolling(activeSummary, false);
        assertSummaryScrolling(otherSummary, true);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1));
        assertSummaryScrolling(activeSummary, true);
        assertTrue("A live shell may be idle", active.getTerminalSession().isRunning());

        client.onTextChanged(active.getTerminalSession());
        assertSummaryScrolling(activeSummary, false);
        assertSummaryScrolling(otherSummary, true);
    }

    @Test
    public void titleOnlyChangesPauseSummaryScrollingUntilIdle() {
        TermuxSession session = addSession(true);
        setSummary(session, "Original long command summary abcdefghijklmnopqrstuvwxyz");
        ListView sessions = showSessionDrawer();
        TextView summary = sessions.getChildAt(0).findViewById(R.id.session_title);
        ReflectionHelpers.setField(activity, "mIsVisible", true);

        setSummary(session, "Updated long command summary abcdefghijklmnopqrstuvwxyz");
        client.onTitleChanged(session.getTerminalSession());
        assertSummaryScrolling(summary, false);
        assertEquals("Updated long command summary abcdefghijklmnopqrstuvwxyz",
            summary.getText().toString());
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1999));
        assertSummaryScrolling(summary, false);
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1));
        assertSummaryScrolling(summary, true);
    }

    @Test
    public void recycledSessionRowsUseTheirOwnOutputActivityForScrolling() {
        TermuxSession active = addSession(true);
        TermuxSession idle = addSession(true);
        setSummary(active, "Active command summary abcdefghijklmnopqrstuvwxyz");
        setSummary(idle, "Idle command summary abcdefghijklmnopqrstuvwxyz");
        ListView sessions = showSessionDrawer();
        client.onTextChanged(active.getTerminalSession());
        TermuxSessionsListViewController adapter =
            ReflectionHelpers.getField(activity, "mTermuxSessionListViewController");

        View row = adapter.getView(0, null, sessions);
        assertSummaryScrolling(row.findViewById(R.id.session_title), false);
        assertSame(row, adapter.getView(1, row, sessions));
        assertSummaryScrolling(row.findViewById(R.id.session_title), true);
        assertSame(row, adapter.getView(0, row, sessions));
        assertSummaryScrolling(row.findViewById(R.id.session_title), false);
    }

    @Test
    public void disposingSessionListCancelsPendingIdleRefresh() {
        TermuxSession session = addSession(true);
        setSummary(session, "Long command summary abcdefghijklmnopqrstuvwxyz");
        ListView sessions = showSessionDrawer();
        TextView summary = sessions.getChildAt(0).findViewById(R.id.session_title);
        client.onTextChanged(session.getTerminalSession());
        assertSummaryScrolling(summary, false);

        TermuxSessionsListViewController adapter =
            ReflectionHelpers.getField(activity, "mTermuxSessionListViewController");
        adapter.dispose();
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(2500));
        assertSummaryScrolling(summary, false);
    }

    private ListView showSessionDrawer() {
        ReflectionHelpers.callInstanceMethod(activity, "setTermuxSessionsListView");
        activity.mTerminalView.setVisibility(View.GONE);
        controller.visible();
        ViewGroup drawer = activity.getDrawer();
        measure(drawer, 320, 640);
        activity.getDrawer().openDrawer(Gravity.START, false);
        measure(drawer, 320, 640);
        return activity.findViewById(R.id.terminal_sessions_list);
    }

    private static void assertSummaryScrolling(TextView summary, boolean scrolling) {
        assertEquals(scrolling ? TextUtils.TruncateAt.MARQUEE : TextUtils.TruncateAt.END,
            summary.getEllipsize());
        assertEquals(scrolling, summary.isSelected());
    }

    private static void setSummary(TermuxSession session, String title) {
        ShadowTerminalSession shadow = Shadow.extract(session.getTerminalSession());
        shadow.title = title;
    }

    private void verifyOverflowTouch(int widthDp, int heightDp, int holdMillis, boolean refresh) {
        TermuxSession first = addSession(true);
        first.getTerminalSession().mSessionName = "Session with a long name abcdefghijklmnopqrstuvwxyz";
        TermuxSession current = addSession(true);
        client.setCurrentSession(current.getTerminalSession());
        ReflectionHelpers.callInstanceMethod(activity, "setTermuxSessionsListView");
        activity.mTerminalView.setVisibility(View.GONE);
        controller.visible();
        ViewGroup drawer = activity.getDrawer();
        measure(drawer, widthDp, heightDp);
        activity.getDrawer().openDrawer(Gravity.START, false);
        measure(drawer, widthDp, heightDp);
        ListView sessions = activity.findViewById(R.id.terminal_sessions_list);
        View menuButton = sessions.getChildAt(0).findViewById(R.id.session_menu_button);
        TextView summary = sessions.getChildAt(0).findViewById(R.id.session_title);
        assertEquals(TextUtils.TruncateAt.MARQUEE, summary.getEllipsize());
        assertTrue("Session summaries must be selected for marquee scrolling", summary.isSelected());
        Rect bounds = new Rect(0, 0, menuButton.getWidth(), menuButton.getHeight());
        drawer.offsetDescendantRectToMyCoords(menuButton, bounds);
        assertTrue(menuButton.isShown());
        assertTrue(bounds.width() > 0);
        Rect visible = new Rect();
        assertTrue(menuButton.getGlobalVisibleRect(visible));
        int[] drawerLocation = new int[2];
        drawer.getLocationOnScreen(drawerLocation);
        visible.offset(-drawerLocation[0], -drawerLocation[1]);
        bounds.intersect(visible);
        long time = SystemClock.uptimeMillis();
        MotionEvent down = MotionEvent.obtain(time, time, MotionEvent.ACTION_DOWN,
            bounds.exactCenterX(), bounds.exactCenterY(), 0);
        drawer.dispatchTouchEvent(down);
        down.recycle();
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(holdMillis));
        if (refresh) {
            first.getTerminalSession().mSessionName = "Updated session";
            activity.termuxSessionListNotifyUpdated();
            measure(drawer, widthDp, heightDp);
        }
        MotionEvent up = MotionEvent.obtain(time, time + holdMillis, MotionEvent.ACTION_UP,
            bounds.exactCenterX(), bounds.exactCenterY(), 0);
        drawer.dispatchTouchEvent(up);
        up.recycle();
        Shadows.shadowOf(Looper.getMainLooper()).idle();

        PopupWindow popup = ShadowApplication.getInstance().getLatestPopupWindow();
        assertNotNull("Tapping the three-dot button must display the session actions", popup);
        assertTrue(popup.isShowing());
        assertSame(current.getTerminalSession(), activity.getCurrentSession());
        assertTrue(activity.getDrawer().isDrawerOpen(Gravity.START));
        assertFalse(wasKilled(first));
        if (refresh) {
            ListView actions = popupList(popup.getContentView());
            actions.performItemClick(actions.getAdapter().getView(0, null, actions), 0, 0);
            AlertDialog rename = (AlertDialog) ShadowDialog.getLatestDialog();
            EditText input = rename.findViewById(com.termux.shared.R.id.dialog_text_input);
            assertEquals("Updated session", input.getText().toString());
            input.setText("Renamed target");
            rename.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertEquals("Renamed target", first.getTerminalSession().mSessionName);
            assertNull(current.getTerminalSession().mSessionName);

            menuButton.performClick();
            popup = ShadowApplication.getInstance().getLatestPopupWindow();
            actions = popupList(popup.getContentView());
            actions.performItemClick(actions.getAdapter().getView(1, null, actions), 1, 1);
            AlertDialog close = (AlertDialog) ShadowDialog.getLatestDialog();
            close.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            assertTrue(wasKilled(first));
            assertFalse(wasKilled(current));
            assertSame(current.getTerminalSession(), activity.getCurrentSession());
        } else {
            popup.dismiss();
        }
    }

    @Test
    public void scrollingFromSessionMenuCancelsTheClick() {
        verifySessionSwipe(R.id.session_menu_button);
    }

    @Test
    public void scrollingFromSessionContentDoesNotSwitchSessions() {
        verifySessionSwipe(R.id.session_card_content);
    }

    @Test
    public void selectingSessionBelowViewportRevealsItInSharedDrawerScroll() {
        for (int i = 0; i < 12; i++) addSession(true);
        ListView sessions = showSessionDrawer();
        ScrollView scroll = activity.findViewById(R.id.terminal_drawer_scroll);
        ReflectionHelpers.setField(activity, "mIsVisible", true);

        client.checkAndScrollToSession(service.getTermuxSession(11).getTerminalSession());
        measure(activity.getDrawer(), 320, 640);

        assertEquals(11, sessions.getCheckedItemPosition());
        assertEquals(0, sessions.getFirstVisiblePosition());
        assertEquals(12, sessions.getChildCount());
        assertTrue(scroll.getScrollY() > 0);
        View lastCard = sessions.getChildAt(11);
        Rect card = new Rect();
        lastCard.getDrawingRect(card);
        scroll.offsetDescendantRectToMyCoords(lastCard, card);
        assertTrue(card.top >= scroll.getScrollY() + scroll.getPaddingTop());
        assertTrue("The selected session must remain above the fixed FAB",
            card.bottom <= scroll.getScrollY() + scroll.getHeight() - scroll.getPaddingBottom());
    }

    private void verifySessionSwipe(int touchTargetId) {
        for (int i = 0; i < 12; i++) addSession(true);
        TerminalSession current = service.getTermuxSession(11).getTerminalSession();
        client.setCurrentSession(current);
        ReflectionHelpers.callInstanceMethod(activity, "setTermuxSessionsListView");
        activity.mTerminalView.setVisibility(View.GONE);
        controller.visible();
        ViewGroup drawer = activity.getDrawer();
        measure(drawer, 320, 640);
        activity.getDrawer().openDrawer(Gravity.START, false);
        measure(drawer, 320, 640);
        ListView sessions = activity.findViewById(R.id.terminal_sessions_list);
        View target = sessions.getChildAt(0).findViewById(touchTargetId);
        Rect bounds = new Rect(0, 0, target.getWidth(), target.getHeight());
        drawer.offsetDescendantRectToMyCoords(target, bounds);
        long time = SystemClock.uptimeMillis();
        float step = 40 * activity.getResources().getDisplayMetrics().density;
        int[] actions = {MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE,
            MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP};
        for (int i = 0; i < actions.length; i++) {
            MotionEvent event = MotionEvent.obtain(time, time + i * 30, actions[i],
                bounds.exactCenterX(), bounds.exactCenterY() - i * step, 0);
            drawer.dispatchTouchEvent(event);
            event.recycle();
        }
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertNull(ShadowApplication.getInstance().getLatestPopupWindow());
        ScrollView scroll = activity.findViewById(R.id.terminal_drawer_scroll);
        assertTrue("Dragging a session must scroll the shared page", scroll.getScrollY() > 0);
        assertEquals(0, sessions.getFirstVisiblePosition());
        assertEquals(sessions.getPaddingTop(), sessions.getChildAt(0).getTop());
        assertEquals(12, service.getTermuxSessionsSize());
        assertSame(current, activity.getCurrentSession());
    }

    @Test
    public void removalFollowedByTitleRefreshRebindsTheRemainingSession() {
        TermuxSession removed = addSession(true);
        TermuxSession remaining = addSession(true);
        ReflectionHelpers.callInstanceMethod(activity, "setTermuxSessionsListView");
        controller.visible();
        ViewGroup drawer = activity.getDrawer();
        measure(drawer, 320, 640);
        activity.getDrawer().openDrawer(Gravity.START, false);
        measure(drawer, 320, 640);
        manager.mTermuxSessions.remove(removed);
        activity.termuxSessionListNotifyUpdated();
        remaining.getTerminalSession().mSessionName = "Remaining session";
        // Both callbacks can arrive before ListView lays out the structural update.
        activity.termuxSessionListNotifyUpdated();
        measure(drawer, 320, 640);
        ListView sessions = activity.findViewById(R.id.terminal_sessions_list);
        assertEquals(1, sessions.getChildCount());
        sessions.getChildAt(0).findViewById(R.id.session_menu_button).performClick();
        PopupWindow popup = ShadowApplication.getInstance().getLatestPopupWindow();
        ListView actions = popupList(popup.getContentView());
        actions.performItemClick(actions.getAdapter().getView(0, null, actions), 0, 0);
        AlertDialog rename = (AlertDialog) ShadowDialog.getLatestDialog();
        EditText input = rename.findViewById(com.termux.shared.R.id.dialog_text_input);
        assertEquals("Remaining session", input.getText().toString());
        input.setText("Renamed survivor");
        rename.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals("Renamed survivor", remaining.getTerminalSession().mSessionName);
        assertNull(removed.getTerminalSession().mSessionName);
    }

    private static ListView popupList(View view) {
        if (view instanceof ListView) return (ListView) view;
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                ListView list = popupList(group.getChildAt(i));
                if (list != null) return list;
            }
        }
        return null;
    }

    private static void measure(View view, int widthDp, int heightDp) {
        measureOnce(view, widthDp, heightDp);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        measureOnce(view, widthDp, heightDp);
    }

    private static void measureOnce(View view, int widthDp, int heightDp) {
        // Include parent margins so real layout passes do not resize cards during a touch.
        View root = view.getRootView().findViewById(R.id.activity_termux_root_view);
        assertNotNull(root);
        float density = root.getResources().getDisplayMetrics().density;
        int width = Math.round(widthDp * density), height = Math.round(heightDp * density);
        root.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, width, height);
    }

    private TermuxSession addSession(boolean running) {
        TerminalSession terminal = new TerminalSession("", "", new String[0], new String[0], 100,
            new TermuxTerminalSessionClientBase());
        ReflectionHelpers.setField(terminal, "mShellPid", running ? 1234 : -1);
        ExecutionCommand command = new ExecutionCommand();
        command.setState(ExecutionCommand.ExecutionState.EXECUTING);
        TermuxSession session = ReflectionHelpers.callConstructor(TermuxSession.class,
            ClassParameter.from(TerminalSession.class, terminal),
            ClassParameter.from(ExecutionCommand.class, command),
            ClassParameter.from(TermuxSession.TermuxSessionClient.class,
                (TermuxSession.TermuxSessionClient) manager.mTermuxSessions::remove),
            ClassParameter.from(boolean.class, false));
        manager.mTermuxSessions.add(session);
        return session;
    }

    private boolean wasKilled(TermuxSession session) {
        ShadowTerminalSession shadow = Shadow.extract(session.getTerminalSession());
        return shadow.killed;
    }

    @Implements(TerminalSession.class)
    public static class ShadowTerminalSession {
        boolean killed;
        String title;

        @Implementation
        protected String getTitle() {
            return title;
        }

        @Implementation
        protected void finishIfRunning() {
            killed = true;
        }
    }
}
