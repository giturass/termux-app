package com.termux.app;

import android.app.Application;
import android.graphics.Rect;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Menu;
import android.view.MenuItem;
import android.widget.ListView;
import android.widget.PopupMenu;
import android.widget.PopupWindow;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;

import com.termux.R;
import com.termux.app.terminal.TerminalBookmark;
import com.termux.app.terminal.TerminalBookmarkStore;
import com.termux.app.terminal.TerminalBookmarksListViewController;
import com.termux.shared.termux.settings.preferences.TermuxAppSharedPreferences;
import com.termux.shared.termux.terminal.TermuxTerminalSessionClientBase;
import com.termux.terminal.TerminalSession;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.shadows.ShadowApplication;
import org.robolectric.shadows.ShadowDialog;
import org.robolectric.annotation.Config;
import org.robolectric.util.ReflectionHelpers;

import java.util.Collections;
import java.time.Duration;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 31, application = Application.class, qualifiers = "zh-rCN-w320dp-h640dp")
public class BookmarkUiTest {
    @Test
    public void savingBookmarkBeyondVisibleCardsRevealsItWithoutPinningLaterRefreshes() {
        verifyNewBookmarkIsRevealed(false);
    }

    @Test
    public void savingBookmarkExpandsCollapsedCollectionAndRevealsTheNewCard() {
        verifyNewBookmarkIsRevealed(true);
    }

    private void verifyNewBookmarkIsRevealed(boolean initiallyCollapsed) {
        ActivityController<TermuxActivity> controller = Robolectric.buildActivity(TermuxActivity.class);
        TermuxActivity activity = host(controller);
        TerminalBookmarkStore store = new TerminalBookmarkStore(activity);
        for (int i = 0; i < 8; i++) {
            store.add(new TerminalBookmark("saved-" + i, "Bookmark " + i, "local", "",
                Collections.emptyList(), "/tmp/" + i));
        }
        store.setCollapsed(initiallyCollapsed);
        TerminalBookmarksListViewController adapter =
            new TerminalBookmarksListViewController(activity, store, item -> {});
        controller.visible();
        ViewGroup drawer = activity.getDrawer();
        activity.getDrawer().openDrawer(Gravity.START, false);
        measureDrawer(drawer);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        measureDrawer(drawer);
        ListView list = activity.findViewById(R.id.terminal_bookmarks_list);
        ScrollView scroll = activity.findViewById(R.id.terminal_drawer_scroll);
        if (!initiallyCollapsed) {
            View lastCard = list.getChildAt(adapter.getCount() - 1);
            assertTrue(bounds(drawer, lastCard).bottom > bounds(drawer, scroll).bottom - scroll.getPaddingBottom());
        }

        store.add(bookmark());
        adapter.refreshAndReveal("location");
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        measureDrawer(drawer);
        assertEquals(View.VISIBLE, list.getVisibility());
        assertFalse(store.isCollapsed());
        int newPosition = adapter.getCount() - 1;
        assertEquals(0, list.getFirstVisiblePosition());
        assertEquals(adapter.getCount(), list.getChildCount());
        View newCard = list.getChildAt(newPosition);
        assertEquals("location", newCard.getTag());
        Rect viewport = bounds(drawer, scroll);
        Rect cardBounds = bounds(drawer, newCard);
        assertTrue("The drawer must scroll to reveal the new bookmark", scroll.getScrollY() > 0);
        assertTrue("The new card must be fully visible", cardBounds.top >= viewport.top + scroll.getPaddingTop());
        assertTrue("The new card must be fully visible",
            cardBounds.bottom <= viewport.bottom - scroll.getPaddingBottom());
        assertTrue("The new card must stay above the floating action",
            cardBounds.bottom <= bounds(drawer, activity.findViewById(R.id.new_session_button)).top);

        // Once the user scrolls elsewhere, terminal refreshes must not pull them back to the new item.
        scroll.scrollTo(0, Math.round(72 * drawer.getResources().getDisplayMetrics().density));
        measureDrawer(drawer);
        int scrollY = scroll.getScrollY();
        int firstTop = bounds(drawer, list.getChildAt(0)).top;
        store.rename("saved-1", "Renamed while browsing");
        adapter.refresh();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        measureDrawer(drawer);
        assertEquals(scrollY, scroll.getScrollY());
        assertEquals(firstTop, bounds(drawer, list.getChildAt(0)).top);
    }

    @Test
    public void collapsingBookmarksReclaimsSpaceAndSurvivesRefreshAndRecreation() {
        ActivityController<TermuxActivity> controller = Robolectric.buildActivity(TermuxActivity.class);
        TermuxActivity activity = host(controller);
        TerminalBookmarkStore store = new TerminalBookmarkStore(activity);
        store.add(bookmark());
        TerminalBookmarksListViewController adapter =
            new TerminalBookmarksListViewController(activity, store, item -> {});
        ViewGroup drawer = activity.getDrawer();
        ListView list = activity.findViewById(R.id.terminal_bookmarks_list);
        View sessions = activity.findViewById(R.id.terminal_sessions_list);
        View toggle = activity.findViewById(R.id.terminal_bookmarks_toggle);
        controller.visible();
        activity.getDrawer().openDrawer(Gravity.START, false);
        measureDrawer(drawer);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        measureDrawer(drawer);
        int expandedSessionsTop = bounds(drawer, sessions).top;
        assertTrue(list.getHeight() > 0);
        toggle.performClick();
        measureDrawer(drawer);
        assertEquals(View.GONE, list.getVisibility());
        assertTrue("Collapsing bookmarks moves sessions up in the shared page",
            bounds(drawer, sessions).top < expandedSessionsTop);
        assertEquals(activity.getString(R.string.action_expand_bookmarks), toggle.getContentDescription());
        store.rename("location", "Updated while collapsed");
        adapter.refresh();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertEquals(View.GONE, list.getVisibility());

        ActivityController<TermuxActivity> recreatedController = Robolectric.buildActivity(TermuxActivity.class);
        TermuxActivity recreated = recreatedController.get();
        recreated.setTheme(R.style.Theme_TermuxActivity_DayNight_NoActionBar);
        recreated.setContentView(R.layout.activity_termux);
        TerminalBookmarksListViewController restored = new TerminalBookmarksListViewController(
            recreated, new TerminalBookmarkStore(recreated), item -> {});
        ListView restoredList = recreated.findViewById(R.id.terminal_bookmarks_list);
        assertEquals(View.GONE, restoredList.getVisibility());
        recreatedController.visible();
        recreated.getDrawer().openDrawer(Gravity.START, false);
        recreated.findViewById(R.id.terminal_bookmarks_toggle).performClick();
        measureDrawer(recreated.getDrawer());
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        measureDrawer(recreated.getDrawer());
        assertEquals(View.VISIBLE, restoredList.getVisibility());
        assertTrue(restoredList.getHeight() > 0);
        assertEquals("Updated while collapsed", restored.getItem(0).name);
        assertFalse(new TerminalBookmarkStore(recreated).isCollapsed());
    }

    @Test
    @Config(shadows = SessionActionsTest.ShadowTerminalSession.class)
    public void terminalActionsOfferSavingOnlyForRunningSessions() {
        TermuxActivity activity = host();
        activity.mTerminalView = activity.findViewById(R.id.terminal_view);
        ReflectionHelpers.setField(activity, "mPreferences", TermuxAppSharedPreferences.build(activity));
        TerminalSession terminal = new TerminalSession("", "", new String[0], new String[0], 100,
            new TermuxTerminalSessionClientBase());
        activity.mTerminalView.mTermSession = terminal;
        for (boolean running : new boolean[]{true, false}) {
            ReflectionHelpers.setField(terminal, "mShellPid", running ? 1234 : -1);
            Menu menu = new PopupMenu(activity, activity.mTerminalView).getMenu();
            ReflectionHelpers.callInstanceMethod(activity, "populateTerminalActions",
                ReflectionHelpers.ClassParameter.from(Menu.class, menu));
            MenuItem save = null;
            for (int i = 0; i < menu.size(); i++) {
                MenuItem item = menu.getItem(i);
                if (activity.getString(R.string.action_save_bookmark).contentEquals(item.getTitle())) save = item;
            }
            assertNotNull(save);
            assertEquals(running, save.isEnabled());
        }
    }

    @Test
    public void longSessionSummaryAndBookmarkPathStayOutsideMenuAtNarrowWidths() {
        TermuxActivity activity = host();
        TerminalBookmarkStore store = new TerminalBookmarkStore(activity);
        store.add(bookmark());
        TerminalBookmarksListViewController adapter =
            new TerminalBookmarksListViewController(activity, store, item -> {});
        ListView list = activity.findViewById(R.id.terminal_bookmarks_list);
        View bookmark = adapter.getView(0, null, list);
        View session = activity.getLayoutInflater().inflate(R.layout.item_terminal_sessions_list, list, false);
        ((TextView) session.findViewById(R.id.session_number)).setText("12");
        TextView name = session.findViewById(R.id.session_name);
        name.setVisibility(View.VISIBLE);
        name.setText("非常长的会话名称 abcdefghijklmnopqrstuvwxyz");
        ((TextView) session.findViewById(R.id.session_title)).setText(bookmark().path);
        for (int width : new int[]{240, 288, 320}) {
            measure(bookmark, width);
            measure(session, width);
            assertOutsideMenu((ViewGroup) bookmark);
            assertOutsideMenu((ViewGroup) session);
            TextView path = bookmark.findViewById(R.id.session_title);
            assertEquals(1, path.getLineCount());
            assertEquals(TextUtils.TruncateAt.MARQUEE, path.getEllipsize());
            assertTrue("Long bookmark paths must be selected for marquee scrolling", path.isSelected());
            TextView summary = session.findViewById(R.id.session_title);
            assertEquals(1, summary.getLineCount());
            assertEquals(TextUtils.TruncateAt.MARQUEE, summary.getEllipsize());
            assertEquals(session.getWidth(), bookmark.getWidth());
            assertEquals(session.getHeight(), bookmark.getHeight());
            name.setVisibility(View.GONE);
            measure(session, width);
            assertEquals("Bookmarks must also match unnamed session cards", session.getHeight(), bookmark.getHeight());
            name.setVisibility(View.VISIBLE);
        }
    }

    @Test
    public void tappingBookmarkPathOpensTheSavedLocation() {
        verifyBookmarkTouch(false);
    }

    @Test
    public void refreshingBookmarkDuringPathTapStillOpensTheSavedLocation() {
        verifyBookmarkTouch(true);
    }

    @Test
    public void deletingBookmarkDuringTapDoesNotOpenItsReplacementBeforeLayout() {
        verifyBookmarkTouch(false, true);
    }

    @Test
    public void scrollingFromBookmarkMenuCancelsTheClick() {
        verifyBookmarkSwipe(R.id.session_menu_button);
    }

    @Test
    public void scrollingFromBookmarkContentDoesNotOpenTheLocation() {
        verifyBookmarkSwipe(R.id.session_card_content);
    }

    private void verifyBookmarkSwipe(int touchTargetId) {
        ActivityController<TermuxActivity> controller = Robolectric.buildActivity(TermuxActivity.class);
        TermuxActivity activity = host(controller);
        TerminalBookmarkStore store = new TerminalBookmarkStore(activity);
        for (int i = 0; i < 12; i++) {
            store.add(new TerminalBookmark("saved-" + i, "Bookmark " + i, "local", "",
                Collections.emptyList(), "/tmp/" + i));
        }
        TerminalBookmark[] opened = {null};
        new TerminalBookmarksListViewController(activity, store, item -> opened[0] = item);
        controller.visible();
        activity.getDrawer().openDrawer(Gravity.START, false);
        ViewGroup drawer = activity.getDrawer();
        measureDrawer(drawer);
        ListView list = activity.findViewById(R.id.terminal_bookmarks_list);
        ScrollView scroll = activity.findViewById(R.id.terminal_drawer_scroll);
        Rect target = bounds(drawer, list.getChildAt(1).findViewById(touchTargetId));
        long time = SystemClock.uptimeMillis();
        float step = 40 * activity.getResources().getDisplayMetrics().density;
        int[] actions = {MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE,
            MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP};
        for (int i = 0; i < actions.length; i++) {
            MotionEvent event = MotionEvent.obtain(time, time + i * 30, actions[i],
                target.exactCenterX(), target.exactCenterY() - i * step, 0);
            drawer.dispatchTouchEvent(event);
            event.recycle();
        }
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        assertTrue("Dragging a bookmark must scroll the shared page", scroll.getScrollY() > 0);
        assertEquals(0, list.getFirstVisiblePosition());
        assertEquals(list.getPaddingTop(), list.getChildAt(0).getTop());
        assertNull(ShadowApplication.getInstance().getLatestPopupWindow());
        assertNull(opened[0]);
    }

    private void verifyBookmarkTouch(boolean refresh) {
        verifyBookmarkTouch(refresh, false);
    }

    private void verifyBookmarkTouch(boolean refresh, boolean delete) {
        ActivityController<TermuxActivity> controller = Robolectric.buildActivity(TermuxActivity.class);
        TermuxActivity activity = host(controller);
        TerminalBookmarkStore store = new TerminalBookmarkStore(activity);
        store.add(bookmark());
        if (delete) store.add(new TerminalBookmark("remaining", "Remaining bookmark", "local", "",
            Collections.emptyList(), "/tmp"));
        TerminalBookmark[] opened = {null};
        int[] openCount = {0};
        TerminalBookmarksListViewController adapter =
            new TerminalBookmarksListViewController(activity, store, item -> {
                opened[0] = item;
                openCount[0]++;
            });
        controller.visible();
        ViewGroup drawer = activity.getDrawer();
        measureDrawer(drawer);
        activity.getDrawer().openDrawer(Gravity.START, false);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        measureDrawer(drawer);
        ListView list = activity.findViewById(R.id.terminal_bookmarks_list);
        View path = list.getChildAt(0).findViewById(R.id.session_title);
        Rect bounds = bounds(drawer, path);
        assertTrue(path.isShown());
        assertTrue(bounds.width() > 0);
        long time = SystemClock.uptimeMillis();
        MotionEvent down = MotionEvent.obtain(time, time, MotionEvent.ACTION_DOWN,
            bounds.exactCenterX(), bounds.exactCenterY(), 0);
        drawer.dispatchTouchEvent(down);
        down.recycle();
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(150));
        if (refresh) {
            store.rename("location", "Updated bookmark");
            adapter.refresh();
            measureDrawer(drawer);
        }
        if (delete) {
            store.delete("location");
            adapter.refresh();
            // Deliberately deliver UP before ListView lays out its changed data.
        }
        MotionEvent up = MotionEvent.obtain(time, time + 150, MotionEvent.ACTION_UP,
            bounds.exactCenterX(), bounds.exactCenterY(), 0);
        drawer.dispatchTouchEvent(up);
        up.recycle();
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300));
        if (delete) {
            assertEquals(0, openCount[0]);
            assertNull(opened[0]);
            assertEquals("remaining", adapter.getItem(0).id);
            return;
        }
        assertEquals("A card tap must open its bookmark exactly once", 1, openCount[0]);
        assertEquals("location", opened[0].id);
        assertEquals("ubuntu", opened[0].distro);
        assertEquals(bookmark().path, opened[0].path);
        if (refresh) assertEquals("Updated bookmark", opened[0].name);
        assertNull(ShadowApplication.getInstance().getLatestPopupWindow());
    }

    @Test
    public void drawerRefreshReflectsRenameAndDeleteAndOpeningKeepsSavedEnvironment() {
        TermuxActivity activity = host();
        TerminalBookmarkStore store = new TerminalBookmarkStore(activity);
        store.add(bookmark());
        TerminalBookmark[] opened = {null};
        TerminalBookmarksListViewController adapter =
            new TerminalBookmarksListViewController(activity, store, item -> opened[0] = item);
        ListView list = activity.findViewById(R.id.terminal_bookmarks_list);
        assertSame(adapter, list.getAdapter());
        View row = adapter.getView(0, null, list);
        list.performItemClick(row, 0, 0);
        assertEquals("ubuntu", opened[0].distro);
        assertEquals(bookmark().path, opened[0].path);
        store.rename("location", "新名称");
        adapter.refresh();
        row = adapter.getView(0, row, list);
        assertEquals("新名称", ((TextView) row.findViewById(R.id.session_name)).getText().toString());
        assertEquals("新名称", new TerminalBookmarkStore(activity).getAll().get(0).name);
        store.delete("location");
        adapter.refresh();
        assertEquals(0, adapter.getCount());
    }

    @Test
    public void cancellingBookmarkDeletionKeepsTheBookmark() {
        verifyBookmarkDeletion(AlertDialog.BUTTON_NEGATIVE);
    }

    @Test
    public void pressingBackDuringBookmarkDeletionKeepsTheBookmark() {
        verifyBookmarkDeletion(KeyEvent.KEYCODE_BACK);
    }

    @Test
    public void confirmingBookmarkDeletionRemovesOnlyItsTargetAndRefreshesTheList() {
        verifyBookmarkDeletion(AlertDialog.BUTTON_POSITIVE);
    }

    private void verifyBookmarkDeletion(int action) {
        ActivityController<TermuxActivity> controller = Robolectric.buildActivity(TermuxActivity.class);
        TermuxActivity activity = host(controller);
        TerminalBookmarkStore store = new TerminalBookmarkStore(activity);
        store.add(bookmark());
        store.add(new TerminalBookmark("remaining", "Remaining bookmark", "local", "",
            Collections.emptyList(), "/tmp"));
        TerminalBookmark[] opened = {null};
        TerminalBookmarksListViewController adapter =
            new TerminalBookmarksListViewController(activity, store, item -> opened[0] = item);
        controller.visible();
        View drawer = activity.getDrawer();
        activity.getDrawer().openDrawer(Gravity.START, false);
        measureDrawer(drawer);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        measureDrawer(drawer);
        ListView list = activity.findViewById(R.id.terminal_bookmarks_list);
        list.getChildAt(0).findViewById(R.id.session_menu_button).performClick();
        PopupWindow popup = ShadowApplication.getInstance().getLatestPopupWindow();
        assertNotNull(popup);
        ListView actions = popupList(popup.getContentView());
        assertNotNull(actions);
        actions.performItemClick(actions.getAdapter().getView(1, null, actions), 1, 1);

        AlertDialog dialog = (AlertDialog) ShadowDialog.getLatestDialog();
        assertNotNull("Deleting a bookmark must first ask for confirmation", dialog);
        assertTrue(dialog.isShowing());
        TextView message = dialog.findViewById(android.R.id.message);
        assertEquals(activity.getString(R.string.message_confirm_delete_bookmark, bookmark().name),
            message.getText().toString());
        assertEquals(2, adapter.getCount());
        assertEquals(2, new TerminalBookmarkStore(activity).getAll().size());
        assertNull(opened[0]);

        if (action == KeyEvent.KEYCODE_BACK) {
            dialog.onBackPressed();
        } else {
            dialog.getButton(action).performClick();
        }
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        measureDrawer(drawer);
        assertFalse(dialog.isShowing());
        boolean deleted = action == AlertDialog.BUTTON_POSITIVE;
        assertEquals(deleted ? 1 : 2, adapter.getCount());
        assertEquals(deleted ? 1 : 2, new TerminalBookmarkStore(activity).getAll().size());
        String firstId = deleted ? "remaining" : "location";
        assertEquals(firstId, adapter.getItem(0).id);
        assertEquals(firstId, list.getChildAt(0).getTag());
        assertEquals(firstId, new TerminalBookmarkStore(activity).getAll().get(0).id);
        assertNull(opened[0]);
    }

    @Test
    public void refreshingBookmarkNameDuringMenuTapKeepsTheMenuUsable() {
        ActivityController<TermuxActivity> controller = Robolectric.buildActivity(TermuxActivity.class);
        TermuxActivity activity = host(controller);
        TerminalBookmarkStore store = new TerminalBookmarkStore(activity);
        store.add(bookmark());
        TerminalBookmark[] opened = {null};
        TerminalBookmarksListViewController adapter =
            new TerminalBookmarksListViewController(activity, store, item -> opened[0] = item);
        controller.visible();
        ViewGroup drawer = activity.getDrawer();
        measureDrawer(drawer);
        activity.getDrawer().openDrawer(Gravity.START, false);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        measureDrawer(drawer);
        ListView list = activity.findViewById(R.id.terminal_bookmarks_list);
        View menu = list.getChildAt(0).findViewById(R.id.session_menu_button);
        Rect bounds = bounds(drawer, menu);
        long time = SystemClock.uptimeMillis();
        MotionEvent down = MotionEvent.obtain(time, time, MotionEvent.ACTION_DOWN,
            bounds.exactCenterX(), bounds.exactCenterY(), 0);
        drawer.dispatchTouchEvent(down);
        down.recycle();
        Shadows.shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(150));
        store.rename("location", "Updated bookmark");
        adapter.refresh();
        measureDrawer(drawer);
        MotionEvent up = MotionEvent.obtain(time, time + 150, MotionEvent.ACTION_UP,
            bounds.exactCenterX(), bounds.exactCenterY(), 0);
        drawer.dispatchTouchEvent(up);
        up.recycle();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        PopupWindow popup = ShadowApplication.getInstance().getLatestPopupWindow();
        assertNotNull(popup);
        assertTrue(popup.isShowing());
        assertNull(opened[0]);
        assertEquals("Updated bookmark", ((TextView) list.getChildAt(0)
            .findViewById(R.id.session_name)).getText().toString());
        popup.dismiss();
    }

    @Test
    public void deleteThenRenameBeforeLayoutUpdatesTheRemainingBookmark() {
        ActivityController<TermuxActivity> controller = Robolectric.buildActivity(TermuxActivity.class);
        TermuxActivity activity = host(controller);
        TerminalBookmarkStore store = new TerminalBookmarkStore(activity);
        store.add(bookmark());
        store.add(new TerminalBookmark("remaining", "Second", "local", "",
            Collections.emptyList(), "/tmp"));
        TerminalBookmarksListViewController adapter =
            new TerminalBookmarksListViewController(activity, store, item -> {});
        controller.visible();
        ViewGroup drawer = activity.getDrawer();
        measureDrawer(drawer);
        activity.getDrawer().openDrawer(Gravity.START, false);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        measureDrawer(drawer);
        store.delete("location");
        adapter.refresh();
        store.rename("remaining", "Updated survivor");
        adapter.refresh();
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        measureDrawer(drawer);
        ListView list = activity.findViewById(R.id.terminal_bookmarks_list);
        assertEquals(1, list.getChildCount());
        assertEquals("Updated survivor", ((TextView) list.getChildAt(0)
            .findViewById(R.id.session_name)).getText().toString());
    }

    private static void measureDrawer(View drawer) {
        measureDrawerOnce(drawer);
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        measureDrawerOnce(drawer);
    }

    private static void measureDrawerOnce(View drawer) {
        float density = drawer.getResources().getDisplayMetrics().density;
        int width = Math.round(320 * density), height = Math.round(640 * density);
        drawer.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY));
        drawer.layout(0, 0, width, height);
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

    private static TerminalBookmark bookmark() {
        return new TerminalBookmark("location", "非常长的书签名称 abcdefghijklmnopqrstuvwxyz", "proot",
            "ubuntu", Collections.emptyList(), "/very/long/目录/with spaces/projects/abcdefghijklmnopqrstuvwxyz/subdirectory");
    }

    private static TermuxActivity host() {
        return host(Robolectric.buildActivity(TermuxActivity.class));
    }

    private static TermuxActivity host(ActivityController<TermuxActivity> controller) {
        TermuxActivity activity = controller.get();
        activity.setTheme(R.style.Theme_TermuxActivity_DayNight_NoActionBar);
        activity.setContentView(R.layout.activity_termux);
        activity.getSharedPreferences("terminal_bookmarks", 0).edit().clear().commit();
        return activity;
    }

    private static void measure(View row, int widthDp) {
        int width = Math.round(widthDp * row.getResources().getDisplayMetrics().density);
        int rowHeight = row.getLayoutParams().height;
        row.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(Math.max(0, rowHeight),
                rowHeight >= 0 ? View.MeasureSpec.EXACTLY : View.MeasureSpec.UNSPECIFIED));
        row.layout(0, 0, row.getMeasuredWidth(), row.getMeasuredHeight());
    }

    private static void assertOutsideMenu(ViewGroup row) {
        View menu = row.findViewById(R.id.session_menu_button);
        Rect menuBounds = bounds(row, menu);
        assertTrue(menu.getWidth() > 0);
        for (int id : new int[]{R.id.session_name, R.id.session_title}) {
            TextView text = row.findViewById(id);
            assertTrue(text.getWidth() > 0);
            assertFalse("Text must not overlap the three-dot menu", Rect.intersects(bounds(row, text), menuBounds));
        }
    }

    private static Rect bounds(ViewGroup row, View child) {
        Rect rect = new Rect();
        child.getDrawingRect(rect);
        row.offsetDescendantRectToMyCoords(child, rect);
        return rect;
    }
}
