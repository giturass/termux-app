package com.termux.app;

import android.annotation.SuppressLint;

import android.content.ActivityNotFoundException;
import android.content.ClipboardManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Bundle;
import android.os.IBinder;
import android.provider.DocumentsContract;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.MotionEvent;
import android.os.SystemClock;
import android.widget.PopupMenu;
import android.view.WindowManager;
import android.widget.HorizontalScrollView;
import android.widget.ImageButton;
import android.widget.ListView;
import android.widget.RelativeLayout;
import android.widget.Toast;

import com.google.android.material.color.MaterialColors;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import com.termux.R;
import com.termux.filepicker.TermuxDocumentsProvider;
import com.termux.app.ui.MaterialMenuDialog;
import com.termux.app.api.file.FileReceiverActivity;
import com.termux.app.settings.properties.TermuxPropertiesSettings;
import com.termux.app.terminal.TermuxActivityRootView;
import com.termux.app.terminal.TermuxTerminalSessionActivityClient;
import com.termux.app.terminal.io.TermuxTerminalExtraKeys;
import com.termux.app.terminal.io.FullScreenWorkAround;
import com.termux.shared.activities.ReportActivity;
import com.termux.shared.activity.ActivityUtils;
import com.termux.shared.activity.media.AppCompatActivityUtils;
import com.termux.shared.data.IntentUtils;
import com.termux.shared.android.PermissionUtils;
import com.termux.shared.data.DataUtils;
import com.termux.shared.termux.TermuxConstants;
import com.termux.shared.termux.TermuxConstants.TERMUX_APP.TERMUX_ACTIVITY;
import com.termux.app.activities.HelpActivity;
import com.termux.app.activities.SettingsActivity;
import com.termux.shared.termux.crash.TermuxCrashUtils;
import com.termux.shared.termux.settings.preferences.TermuxAppSharedPreferences;
import com.termux.app.terminal.TermuxSessionsListViewController;
import com.termux.app.terminal.BookmarkEnvironment;
import com.termux.app.terminal.TerminalBookmark;
import com.termux.app.terminal.TerminalBookmarkStore;
import com.termux.app.terminal.TerminalBookmarksListViewController;
import com.termux.app.terminal.TermuxTerminalViewClient;
import com.termux.shared.termux.extrakeys.ExtraKeysView;
import com.termux.shared.termux.interact.TextInputDialogUtils;
import com.termux.shared.logger.Logger;
import com.termux.shared.termux.TermuxUtils;
import com.termux.shared.termux.settings.properties.TermuxAppSharedProperties;
import com.termux.shared.termux.theme.TermuxThemeUtils;
import com.termux.shared.theme.NightMode;
import com.termux.shared.view.ViewUtils;
import com.termux.terminal.TerminalSession;
import com.termux.terminal.TerminalSessionClient;
import com.termux.view.TerminalView;
import com.termux.view.TerminalViewClient;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;
import androidx.drawerlayout.widget.DrawerLayout;

import java.util.Arrays;
import java.util.List;
import java.io.IOException;

/**
 * A terminal emulator activity.
 * <p/>
 * See
 * <ul>
 * <li>http://www.mongrel-phones.com.au/default/how_to_make_a_local_service_and_bind_to_it_in_android</li>
 * <li>https://code.google.com/p/android/issues/detail?id=6426</li>
 * </ul>
 * about memory leaks.
 */
public final class TermuxActivity extends AppCompatActivity implements ServiceConnection {

    /**
     * The connection to the {@link TermuxService}. Requested in {@link #onCreate(Bundle)} with a call to
     * {@link #bindService(Intent, ServiceConnection, int)}, and obtained and stored in
     * {@link #onServiceConnected(ComponentName, IBinder)}.
     */
    TermuxService mTermuxService;

    /**
     * The {@link TerminalView} shown in  {@link TermuxActivity} that displays the terminal.
     */
    TerminalView mTerminalView;

    /**
     *  The {@link TerminalViewClient} interface implementation to allow for communication between
     *  {@link TerminalView} and {@link TermuxActivity}.
     */
    TermuxTerminalViewClient mTermuxTerminalViewClient;

    /**
     *  The {@link TerminalSessionClient} interface implementation to allow for communication between
     *  {@link TerminalSession} and {@link TermuxActivity}.
     */
    TermuxTerminalSessionActivityClient mTermuxTerminalSessionActivityClient;

    /**
     * Termux app shared preferences manager.
     */
    private TermuxAppSharedPreferences mPreferences;

    /**
     * Termux app SharedProperties loaded from termux.properties
     */
    private TermuxAppSharedProperties mProperties;

    /**
     * The root view of the {@link TermuxActivity}.
     */
    TermuxActivityRootView mTermuxActivityRootView;

    /**
     * The space at the bottom of {@link @mTermuxActivityRootView} of the {@link TermuxActivity}.
     */
    View mTermuxActivityBottomSpaceView;

    /**
     * The terminal extra keys view.
     */
    ExtraKeysView mExtraKeysView;

    /**
     * The client for the {@link #mExtraKeysView}.
     */
    TermuxTerminalExtraKeys mTermuxTerminalExtraKeys;

    /**
     * The termux sessions list controller.
     */
    TermuxSessionsListViewController mTermuxSessionListViewController;

    /**
     * The {@link TermuxActivity} broadcast receiver for various things like terminal style configuration changes.
     */
    private final BroadcastReceiver mTermuxActivityBroadcastReceiver = new TermuxActivityBroadcastReceiver();

    /**
     * The last toast shown, used cancel current toast before showing new in {@link #showToast(String, boolean)}.
     */
    Toast mLastToast;

    /**
     * If between onResume() and onStop(). Note that only one session is in the foreground of the terminal view at the
     * time, so if the session causing a change is not in the foreground it should probably be treated as background.
     */
    private boolean mIsVisible;

    /**
     * If onResume() was called after onCreate().
     */
    private boolean mIsOnResumeAfterOnCreate = false;

    /**
     * If activity was restarted like due to call to {@link #recreate()} after receiving
     * {@link TERMUX_ACTIVITY#ACTION_RELOAD_STYLE}, system dark night mode was changed or activity
     * was killed by android.
     */
    private boolean mIsActivityRecreated = false;

    /**
     * The {@link TermuxActivity} is in an invalid state and must not be run.
     */
    private boolean mIsInvalidState;

    private int mNavBarHeight;

    private float mTerminalToolbarDefaultHeight;
    private long mPropertiesRevision;

    private boolean mIsDrawerCompact;
    private boolean mIsDrawerInputCollapsed;
    private boolean mIsWaitingForSession;
    private AlertDialog mActionsDialog;
    private TerminalBookmarkStore mBookmarkStore;
    private TerminalBookmarksListViewController mBookmarksController;
    private boolean mSavingBookmark;


    private static final int CONTEXT_MENU_SELECT_TEXT_ID = 100;
    private static final int CONTEXT_MENU_PASTE_ID = 101;
    private static final int CONTEXT_MENU_SAVE_BOOKMARK_ID = 102;
    private static final int CONTEXT_MENU_SELECT_URL_ID = 0;
    private static final int CONTEXT_MENU_SHARE_TRANSCRIPT_ID = 1;
    private static final int CONTEXT_MENU_SHARE_SELECTED_TEXT = 10;
    private static final int CONTEXT_MENU_AUTOFILL_USERNAME = 11;
    private static final int CONTEXT_MENU_AUTOFILL_PASSWORD = 2;
    private static final int CONTEXT_MENU_RESET_TERMINAL_ID = 3;
    private static final int CONTEXT_MENU_STYLING_ID = 5;
    private static final int CONTEXT_MENU_TOGGLE_KEEP_SCREEN_ON = 6;
    private static final int CONTEXT_MENU_HELP_ID = 7;
    private static final int CONTEXT_MENU_SETTINGS_ID = 8;
    private static final int CONTEXT_MENU_REPORT_ID = 9;

    private static final String ARG_ACTIVITY_RECREATED = "activity_recreated";
    private static final String ARG_WAITING_FOR_SESSION = "waiting_for_session";

    private static final String LOG_TAG = "TermuxActivity";

    @Override
    public void onCreate(Bundle savedInstanceState) {
        Logger.logDebug(LOG_TAG, "onCreate");
        mIsOnResumeAfterOnCreate = true;

        if (savedInstanceState != null) {
            mIsActivityRecreated = savedInstanceState.getBoolean(ARG_ACTIVITY_RECREATED, false);
            mIsWaitingForSession = savedInstanceState.getBoolean(ARG_WAITING_FOR_SESSION, false);
        }

        // Delete ReportInfo serialized object files from cache older than 14 days
        ReportActivity.deleteReportInfoFilesOlderThanXDays(this, 14, false);

        try {
            TermuxPropertiesSettings.migrateLegacyFullscreen(this);
        } catch (IOException e) {
            Logger.logStackTraceWithMessage(LOG_TAG, "Could not migrate the fullscreen preference", e);
        }

        // Load Termux app SharedProperties from disk
        mProperties = TermuxAppSharedProperties.getProperties();
        reloadProperties();

        setActivityTheme();

        super.onCreate(savedInstanceState);

        setContentView(R.layout.activity_termux);

        // Load termux shared preferences
        // This will also fail if TermuxConstants.TERMUX_PACKAGE_NAME does not equal applicationId
        mPreferences = TermuxAppSharedPreferences.build(this, true);
        if (mPreferences == null) {
            // An AlertDialog should have shown to kill the app, so we don't continue running activity code
            mIsInvalidState = true;
            return;
        }

        setMargins();

        mTermuxActivityRootView = findViewById(R.id.activity_termux_root_view);
        mTermuxActivityRootView.setActivity(this);
        mTermuxActivityBottomSpaceView = findViewById(R.id.activity_termux_bottom_space_view);
        mTermuxActivityRootView.setOnApplyWindowInsetsListener(new TermuxActivityRootView.WindowInsetsListener());

        View content = findViewById(android.R.id.content);
        content.setOnApplyWindowInsetsListener((v, insets) -> {
            mNavBarHeight = insets.getSystemWindowInsetBottom();
            return insets;
        });

        if (mProperties.isUsingFullScreen() && mProperties.isUsingFullScreenWorkAround())
            FullScreenWorkAround.apply(this);

        setTermuxTerminalViewAndClients();

        setTerminalToolbarView();

        setSettingsButtonView();


        setNewSessionButtonView();

        setFileSystemView();

        mBookmarkStore = new TerminalBookmarkStore(this);
        mBookmarksController = new TerminalBookmarksListViewController(this, mBookmarkStore,
            bookmark -> mTermuxTerminalSessionActivityClient.openBookmark(bookmark));

        setAdaptiveDrawerLayout();

        if (mIsWaitingForSession || (!mIsActivityRecreated && hasBookmarks() && !isNewSessionIntent(getIntent()))) {
            // Show navigation before the service binds, without briefly revealing terminal input.
            mIsWaitingForSession = true;
            updateSessionUi();
        }

        mTerminalView.setContextMenuAction(() ->
            showTerminalActions(mTerminalView.getWidth() / 2f, mTerminalView.getHeight() / 2f));

        FileReceiverActivity.updateFileReceiverActivityComponentsState(this);

        try {
            // Start the {@link TermuxService} and make it run regardless of who is bound to it
            Intent serviceIntent = new Intent(this, TermuxService.class);
            startService(serviceIntent);

            // Attempt to bind to the service, this will call the {@link #onServiceConnected(ComponentName, IBinder)}
            // callback if it succeeds.
            if (!bindService(serviceIntent, this, 0))
                throw new RuntimeException("bindService() failed");
        } catch (Exception e) {
            Logger.logStackTraceWithMessage(LOG_TAG,"TermuxActivity failed to start TermuxService", e);
            Logger.showToast(this,
                getString(e.getMessage() != null && e.getMessage().contains("app is in background") ?
                    R.string.error_termux_service_start_failed_bg : R.string.error_termux_service_start_failed_general),
                true);
            mIsInvalidState = true;
            return;
        }

        // Send the {@link TermuxConstants#BROADCAST_TERMUX_OPENED} broadcast to notify apps that Termux
        // app has been opened.
        TermuxUtils.sendTermuxOpenedBroadcast(this);
    }

    @Override
    public void onStart() {
        super.onStart();

        Logger.logDebug(LOG_TAG, "onStart");

        if (mIsInvalidState) return;

        mIsVisible = true;

        if (mTermuxTerminalSessionActivityClient != null)
            mTermuxTerminalSessionActivityClient.onStart();

        if (mTermuxTerminalViewClient != null)
            mTermuxTerminalViewClient.onStart();

        if (mPreferences.isTerminalMarginAdjustmentEnabled())
            addTermuxActivityRootViewGlobalLayoutListener();

        registerTermuxActivityBroadcastReceiver();
    }

    @Override
    public void onResume() {
        super.onResume();

        Logger.logVerbose(LOG_TAG, "onResume");

        if (mIsInvalidState) return;

        if (mPropertiesRevision != TermuxPropertiesSettings.getRevision()) {
            reloadActivityStyling(true);
            return;
        }

        if (mExtraKeysView != null && mTermuxTerminalExtraKeys != null
            && mTermuxTerminalExtraKeys.reloadIfCursorGesturesChanged()) {
            mExtraKeysView.reload(mTermuxTerminalExtraKeys.getExtraKeysInfo(), mTerminalToolbarDefaultHeight);
            setTerminalToolbarHeight();
        }

        if (mTermuxService != null || mIsWaitingForSession) updateSessionUi();

        if (mTermuxTerminalSessionActivityClient != null)
            mTermuxTerminalSessionActivityClient.onResume();

        if (mTermuxTerminalViewClient != null)
            mTermuxTerminalViewClient.onResume();

        applyTerminalDisplayPreferences();

        // Check if a crash happened on last run of the app or if a plugin crashed and show a
        // notification with the crash details if it did
        TermuxCrashUtils.notifyAppCrashFromCrashLogFile(this, LOG_TAG);

        mIsOnResumeAfterOnCreate = false;
    }

    @Override
    protected void onStop() {
        super.onStop();

        Logger.logDebug(LOG_TAG, "onStop");

        if (mIsInvalidState) return;

        mIsVisible = false;

        if (mTermuxTerminalSessionActivityClient != null)
            mTermuxTerminalSessionActivityClient.onStop();

        if (mTermuxTerminalViewClient != null)
            mTermuxTerminalViewClient.onStop();

        removeTermuxActivityRootViewGlobalLayoutListener();

        unregisterTermuxActivityBroadcastReceiver();
        if (!mIsWaitingForSession) getDrawer().closeDrawers();
    }

    @Override
    public void onDestroy() {
        super.onDestroy();

        Logger.logDebug(LOG_TAG, "onDestroy");

        if (mIsInvalidState) return;

        if (mActionsDialog != null) mActionsDialog.dismiss();
        if (mTermuxSessionListViewController != null) mTermuxSessionListViewController.dispose();

        if (mTermuxService != null) {
            // Do not leave service and session clients with references to activity.
            mTermuxService.unsetTermuxTerminalSessionClient();
            mTermuxService = null;
        }

        try {
            unbindService(this);
        } catch (Exception e) {
            // ignore.
        }
    }

    @Override
    public void onSaveInstanceState(@NonNull Bundle savedInstanceState) {
        Logger.logVerbose(LOG_TAG, "onSaveInstanceState");

        super.onSaveInstanceState(savedInstanceState);
        savedInstanceState.putBoolean(ARG_ACTIVITY_RECREATED, true);
        savedInstanceState.putBoolean(ARG_WAITING_FOR_SESSION, mIsWaitingForSession);
    }





    /**
     * Part of the {@link ServiceConnection} interface. The service is bound with
     * {@link #bindService(Intent, ServiceConnection, int)} in {@link #onCreate(Bundle)} which will cause a call to this
     * callback method.
     */
    @Override
    public void onServiceConnected(ComponentName componentName, IBinder service) {
        Logger.logDebug(LOG_TAG, "onServiceConnected");

        mTermuxService = ((TermuxService.LocalBinder) service).service;
        mTermuxTerminalSessionActivityClient.applyPendingColorChanges();

        setTermuxSessionsListView();

        final Intent intent = getIntent();
        setIntent(null);
        final boolean launchNewSession = isNewSessionIntent(intent);
        final boolean showSessionDrawer = (hasBookmarks() || mIsWaitingForSession) && !launchNewSession;

        if (mTermuxService.isTermuxSessionsEmpty()) {
            if (mIsVisible) {
                if (showSessionDrawer) updateSessionUi();
                TermuxInstaller.setupBootstrapIfNeeded(TermuxActivity.this, () -> {
                    if (mTermuxService == null || isFinishing()) return;
                    if (showSessionDrawer) return;
                    try {
                        boolean launchFailsafe = false;
                        if (intent != null && intent.getExtras() != null) {
                            launchFailsafe = intent.getExtras().getBoolean(TERMUX_ACTIVITY.EXTRA_FAILSAFE_SESSION, false);
                        }
                        mTermuxTerminalSessionActivityClient.addNewSession(launchFailsafe, null);
                    } catch (WindowManager.BadTokenException e) {
                        // Activity finished - ignore.
                    }
                });
            } else {
                // The service connected while not in foreground - just bail out.
                finishActivityIfNotFinishing();
            }
        } else {
            // If termux was started from launcher "New session" shortcut and activity is recreated,
            // then the original intent will be re-delivered, resulting in a new session being re-added
            // each time.
            if (launchNewSession) {
                // Android 7.1 app shortcut from res/xml/shortcuts.xml.
                boolean isFailSafe = intent.getBooleanExtra(TERMUX_ACTIVITY.EXTRA_FAILSAFE_SESSION, false);
                mTermuxTerminalSessionActivityClient.addNewSession(isFailSafe, null);
            } else {
                mTermuxTerminalSessionActivityClient.setCurrentSession(mTermuxTerminalSessionActivityClient.getCurrentStoredSessionOrLast());
            }
            if (showSessionDrawer && !mIsActivityRecreated) {
                getDrawer().openDrawer(Gravity.START, false);
                setDrawerInputCollapsed(true);
            }
        }

        // Update the {@link TerminalSession} and {@link TerminalEmulator} clients.
        mTermuxService.setTermuxTerminalSessionClient(mTermuxTerminalSessionActivityClient);
    }

    private boolean hasBookmarks() {
        return mBookmarkStore != null && !mBookmarkStore.getAll().isEmpty();
    }

    private boolean isNewSessionIntent(@Nullable Intent intent) {
        return !mIsActivityRecreated && intent != null && Intent.ACTION_RUN.equals(intent.getAction());
    }

    @Override
    public void onServiceDisconnected(ComponentName name) {
        Logger.logDebug(LOG_TAG, "onServiceDisconnected");

        // Respect being stopped from the {@link TermuxService} notification action.
        finishActivityIfNotFinishing();
    }






    private void reloadProperties() {
        mProperties.loadTermuxPropertiesFromDisk();
        mPropertiesRevision = TermuxPropertiesSettings.getRevision();

        if (mTermuxTerminalViewClient != null)
            mTermuxTerminalViewClient.onReloadProperties();
    }



    private void setActivityTheme() {
        // Update NightMode.APP_NIGHT_MODE
        TermuxThemeUtils.setAppNightMode(mProperties.getNightMode());

        // Set activity night mode. If NightMode.SYSTEM is set, then android will automatically
        // trigger recreation of activity when uiMode/dark mode configuration is changed so that
        // day or night theme takes affect.
        AppCompatActivityUtils.setNightMode(this, NightMode.getAppNightMode().getName(), true);
    }

    private void setMargins() {
        RelativeLayout relativeLayout = findViewById(R.id.activity_termux_root_relative_layout);
        int marginHorizontal = mProperties.getTerminalMarginHorizontal();
        int marginVertical = mProperties.getTerminalMarginVertical();
        ViewUtils.setLayoutMarginsInDp(relativeLayout, marginHorizontal, marginVertical, marginHorizontal, marginVertical);
    }



    public void addTermuxActivityRootViewGlobalLayoutListener() {
        getTermuxActivityRootView().getViewTreeObserver().addOnGlobalLayoutListener(getTermuxActivityRootView());
    }

    public void removeTermuxActivityRootViewGlobalLayoutListener() {
        if (getTermuxActivityRootView() != null)
            getTermuxActivityRootView().getViewTreeObserver().removeOnGlobalLayoutListener(getTermuxActivityRootView());
    }



    private void setTermuxTerminalViewAndClients() {
        // Set termux terminal view and session clients
        mTermuxTerminalSessionActivityClient = new TermuxTerminalSessionActivityClient(this);
        mTermuxTerminalViewClient = new TermuxTerminalViewClient(this, mTermuxTerminalSessionActivityClient);

        // Set termux terminal view
        mTerminalView = findViewById(R.id.terminal_view);
        mTerminalView.setTerminalViewClient(mTermuxTerminalViewClient);

        if (mTermuxTerminalViewClient != null)
            mTermuxTerminalViewClient.onCreate();

        if (mTermuxTerminalSessionActivityClient != null)
            mTermuxTerminalSessionActivityClient.onCreate();
    }

    private void setTermuxSessionsListView() {
        ListView termuxSessionsListView = findViewById(R.id.terminal_sessions_list);
        if (mTermuxSessionListViewController != null) mTermuxSessionListViewController.dispose();
        mTermuxSessionListViewController = new TermuxSessionsListViewController(this, mTermuxService.getTermuxSessions());
        termuxSessionsListView.setAdapter(mTermuxSessionListViewController);
        termuxSessionsListView.setOnItemClickListener(mTermuxSessionListViewController);
    }



    private void setTerminalToolbarView() {
        mTermuxTerminalExtraKeys = new TermuxTerminalExtraKeys(this, mTerminalView,
            mTermuxTerminalViewClient, mTermuxTerminalSessionActivityClient);

        final HorizontalScrollView terminalToolbar = getTerminalToolbar();
        if (mPreferences.shouldShowTerminalToolbar()) terminalToolbar.setVisibility(View.VISIBLE);

        ViewGroup.LayoutParams layoutParams = terminalToolbar.getLayoutParams();
        mTerminalToolbarDefaultHeight = layoutParams.height;

        setTerminalToolbarHeight();

        mExtraKeysView = findViewById(R.id.terminal_toolbar_extra_keys);
        mExtraKeysView.setExtraKeysViewClient(mTermuxTerminalExtraKeys);
        mExtraKeysView.setButtonTextAllCaps(mProperties.shouldExtraKeysTextBeAllCaps());
        mExtraKeysView.reload(mTermuxTerminalExtraKeys.getExtraKeysInfo(), mTerminalToolbarDefaultHeight);
    }

    private void setTerminalToolbarHeight() {
        final HorizontalScrollView terminalToolbar = getTerminalToolbar();
        if (terminalToolbar == null) return;

        ViewGroup.LayoutParams layoutParams = terminalToolbar.getLayoutParams();
        layoutParams.height = Math.round(mTerminalToolbarDefaultHeight *
            (mTermuxTerminalExtraKeys.getExtraKeysInfo() == null ? 0 : mTermuxTerminalExtraKeys.getExtraKeysInfo().getMatrix().length) *
            mProperties.getTerminalToolbarHeightScaleFactor());
        terminalToolbar.setLayoutParams(layoutParams);
    }

    public void toggleTerminalToolbar() {
        final HorizontalScrollView terminalToolbar = getTerminalToolbar();
        if (terminalToolbar == null) return;

        final boolean showNow = mPreferences.toogleShowTerminalToolbar();
        Logger.showToast(this, (showNow ? getString(R.string.msg_enabling_terminal_toolbar) : getString(R.string.msg_disabling_terminal_toolbar)), true);
        terminalToolbar.setVisibility(showNow && !mIsDrawerInputCollapsed ? View.VISIBLE : View.GONE);
    }



    private void setSettingsButtonView() {
        ImageButton settingsButton = findViewById(R.id.settings_button);
        settingsButton.setOnClickListener(v -> {
            ActivityUtils.startActivity(this, new Intent(this, SettingsActivity.class));
        });
    }

    private void setNewSessionButtonView() {
        View newSessionButton = findViewById(R.id.new_session_button);
        newSessionButton.setOnClickListener(v -> mTermuxTerminalSessionActivityClient.addNewSession(false, null));
        newSessionButton.setOnLongClickListener(v -> {
            TextInputDialogUtils.textInput(TermuxActivity.this, R.string.title_create_named_session, null,
                R.string.action_create_named_session_confirm, text -> mTermuxTerminalSessionActivityClient.addNewSession(false, text),
                R.string.action_new_session_failsafe, text -> mTermuxTerminalSessionActivityClient.addNewSession(true, text),
                -1, null, null);
            return true;
        });
    }

    private void setFileSystemView() {
        findViewById(R.id.file_system_button).setOnClickListener(v -> {
            Intent intent = new Intent(Intent.ACTION_VIEW)
                .setDataAndType(TermuxDocumentsProvider.getRootUri(), DocumentsContract.Root.MIME_TYPE_ITEM);
            List<ResolveInfo> managers = getPackageManager().queryIntentActivities(intent,
                PackageManager.MATCH_DEFAULT_ONLY | PackageManager.MATCH_SYSTEM_ONLY);
            if (managers.isEmpty()) {
                showToast(getString(R.string.error_system_file_manager_unavailable), true);
                return;
            }
            ResolveInfo manager = managers.get(0);
            intent.setClassName(manager.activityInfo.packageName, manager.activityInfo.name);
            ActivityUtils.startActivity(this, intent);
            if (!mIsWaitingForSession) getDrawer().closeDrawers();
        });
    }

    public void setTerminalSurfaceColor(int color) {
        View surface = findViewById(R.id.terminal_surface);
        if (surface != null) surface.setBackgroundColor(color);
        getWindow().getDecorView().setBackgroundColor(MaterialColors.getColor(
            this, com.google.android.material.R.attr.colorSurface, 0));
    }

    /** Keep session navigation usable when the keyboard or split screen reduces the height. */
    private void setAdaptiveDrawerLayout() {
        getDrawer().addDrawerListener(new DrawerLayout.SimpleDrawerListener() {
            @Override
            public void onDrawerSlide(@NonNull View drawerView, float slideOffset) {
                setDrawerInputCollapsed(slideOffset > 0);
            }

            @Override
            public void onDrawerOpened(@NonNull View drawerView) {
                setDrawerInputCollapsed(true);
            }

            @Override
            public void onDrawerClosed(@NonNull View drawerView) {
                if (mIsWaitingForSession) {
                    // DrawerLayout finishes hiding the view after this callback returns.
                    getDrawer().post(() -> {
                        if (mIsWaitingForSession && !isFinishing() && !isDestroyed())
                            getDrawer().openDrawer(Gravity.START, false);
                    });
                    return;
                }
                setDrawerInputCollapsed(false);
            }
        });

        View drawer = findViewById(R.id.left_drawer);
        View header = findViewById(R.id.terminal_drawer_header);
        int headerPaddingTop = header.getPaddingTop();
        int headerPaddingBottom = header.getPaddingBottom();

        drawer.addOnLayoutChangeListener((view, left, top, right, bottom,
                                          oldLeft, oldTop, oldRight, oldBottom) -> {
            int availableHeight = bottom - top;
            if (availableHeight <= 0) return;
            boolean compact = availableHeight < ViewUtils.dpToPx(this, 360);
            // Updating child layout parameters requests another layout; only do it on a transition.
            if (compact == mIsDrawerCompact) return;
            mIsDrawerCompact = compact;

            header.setPaddingRelative(header.getPaddingStart(), compact ? 0 : headerPaddingTop,
                header.getPaddingEnd(), compact ? 0 : headerPaddingBottom);
        });
    }

    private void setDrawerInputCollapsed(boolean collapsed) {
        collapsed |= mIsWaitingForSession;
        if (mIsDrawerInputCollapsed == collapsed) return;
        mIsDrawerInputCollapsed = collapsed;
        getTerminalToolbar().setVisibility(
            !collapsed && mPreferences.shouldShowTerminalToolbar() ? View.VISIBLE : View.GONE);
        if (collapsed) {
            // Keep terminal focus and cancel any pending keyboard reveal.
            mTerminalView.requestFocus();
            mTermuxTerminalViewClient.onHideSoftKeyboardRequest();
        }
    }

    /** Keep navigation available until there is a session to display. */
    public void updateSessionUi() {
        if (mTermuxService != null)
            mIsWaitingForSession = mTermuxService.isTermuxSessionsEmpty();

        DrawerLayout drawer = getDrawer();
        int lockMode = mIsWaitingForSession
            ? DrawerLayout.LOCK_MODE_LOCKED_OPEN : mTerminalView.isSelectingText()
                ? DrawerLayout.LOCK_MODE_LOCKED_CLOSED : DrawerLayout.LOCK_MODE_UNLOCKED;
        if (mIsWaitingForSession) {
            mTerminalView.stopTextSelectionMode();
            mTermuxTerminalViewClient.onSessionDetached();
            if (mTerminalView.attachSession(null)) mTerminalView.invalidate();
            drawer.openDrawer(Gravity.START, false);
            mTermuxTerminalViewClient.onHideSoftKeyboardRequest();
        }
        if (drawer.getDrawerLockMode(Gravity.START) != lockMode)
            drawer.setDrawerLockMode(lockMode, Gravity.START);
        setDrawerInputCollapsed(mIsWaitingForSession || drawer.isDrawerVisible(Gravity.START));
    }

    public boolean isWaitingForSession() {
        return mIsWaitingForSession;
    }





    @SuppressLint("RtlHardcoded")
    @Override
    public void onBackPressed() {
        if (mIsWaitingForSession) {
            finishActivityIfNotFinishing();
        } else if (getDrawer().isDrawerOpen(Gravity.LEFT)) {
            getDrawer().closeDrawers();
        } else {
            finishActivityIfNotFinishing();
        }
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        // DrawerLayout consumes Back even when locked open, so handle the empty state first.
        if (mIsWaitingForSession && event.getKeyCode() == KeyEvent.KEYCODE_BACK) {
            if (event.getAction() == KeyEvent.ACTION_UP && !event.isCanceled())
                finishActivityIfNotFinishing();
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    public void finishActivityIfNotFinishing() {
        // prevent duplicate calls to finish() if called from multiple places
        if (!TermuxActivity.this.isFinishing()) {
            finish();
        }
    }

    /** Show a toast and dismiss the last one if still visible. */
    public void showToast(String text, boolean longDuration) {
        if (text == null || text.isEmpty()) return;
        if (mLastToast != null) mLastToast.cancel();
        mLastToast = Toast.makeText(TermuxActivity.this, text, longDuration ? Toast.LENGTH_LONG : Toast.LENGTH_SHORT);
        mLastToast.setGravity(Gravity.TOP, 0, 0);
        mLastToast.show();
    }



    public boolean showTerminalActions(float selectionX, float selectionY) {
        TerminalSession session = getCurrentSession();
        if (session == null || isFinishing()) return false;
        if (mActionsDialog != null && mActionsDialog.isShowing()) return true;
        Menu menu = new PopupMenu(this, mTerminalView).getMenu();
        menu.add(Menu.NONE, CONTEXT_MENU_SELECT_TEXT_ID, Menu.NONE, R.string.action_select_text)
            .setIcon(R.drawable.ic_action_select).setEnabled(session.getEmulator() != null);
        ClipboardManager clipboard = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
        menu.add(Menu.NONE, CONTEXT_MENU_PASTE_ID, Menu.NONE, R.string.action_paste_text).setIcon(R.drawable.ic_action_paste)
            .setEnabled(session.isRunning() && clipboard != null && clipboard.hasPrimaryClip());
        populateTerminalActions(menu);
        mActionsDialog = MaterialMenuDialog.show(this, getString(R.string.terminal_actions_title), menu, item -> {
            if (session != getCurrentSession()) return;
            if (item.getItemId() == CONTEXT_MENU_SELECT_TEXT_ID) {
                mTerminalView.post(() -> {
                    if (isFinishing() || session != getCurrentSession() || session.getEmulator() == null) return;
                    long now = SystemClock.uptimeMillis();
                    MotionEvent event = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, selectionX, selectionY, 0);
                    mTerminalView.startTextSelectionMode(event);
                    event.recycle();
                });
            } else if (item.getItemId() == CONTEXT_MENU_PASTE_ID) {
                session.onPasteTextFromClipboard();
            } else {
                onContextItemSelected(item);
            }
        }, () -> mTerminalView.onContextMenuClosed(menu));
        return true;
    }

    private void populateTerminalActions(Menu menu) {
        TerminalSession currentSession = getCurrentSession();
        if (currentSession == null) return;

        boolean autoFillEnabled = mTerminalView.isAutoFillEnabled();

        menu.add(Menu.NONE, CONTEXT_MENU_SAVE_BOOKMARK_ID, Menu.NONE, R.string.action_save_bookmark)
            .setIcon(R.drawable.ic_folder).setEnabled(currentSession.isRunning() && !mSavingBookmark);

        menu.add(Menu.NONE, CONTEXT_MENU_SELECT_URL_ID, Menu.NONE, R.string.action_select_url);
        if (!DataUtils.isNullOrEmpty(mTerminalView.getStoredSelectedText()))
            menu.add(Menu.NONE, CONTEXT_MENU_SHARE_SELECTED_TEXT, Menu.NONE, R.string.action_share_selected_text);
        if (autoFillEnabled)
            menu.add(Menu.NONE, CONTEXT_MENU_AUTOFILL_USERNAME, Menu.NONE, R.string.action_autofill_username);
        if (autoFillEnabled)
            menu.add(Menu.NONE, CONTEXT_MENU_AUTOFILL_PASSWORD, Menu.NONE, R.string.action_autofill_password);
        menu.add(Menu.NONE, CONTEXT_MENU_TOGGLE_KEEP_SCREEN_ON, Menu.NONE, R.string.action_toggle_keep_screen_on).setCheckable(true).setChecked(mPreferences.shouldKeepScreenOn());
        menu.add(Menu.NONE, CONTEXT_MENU_STYLING_ID, Menu.NONE, R.string.action_style_terminal);
        menu.add(Menu.NONE, CONTEXT_MENU_SHARE_TRANSCRIPT_ID, Menu.NONE, R.string.action_share_transcript);
        int[][] icons = {{CONTEXT_MENU_SELECT_URL_ID, R.drawable.ic_action_link},
            {CONTEXT_MENU_SHARE_TRANSCRIPT_ID, R.drawable.ic_action_share},
            {CONTEXT_MENU_SHARE_SELECTED_TEXT, R.drawable.ic_action_share},
            {CONTEXT_MENU_AUTOFILL_USERNAME, R.drawable.ic_action_paste},
            {CONTEXT_MENU_AUTOFILL_PASSWORD, R.drawable.ic_action_paste},
            {CONTEXT_MENU_STYLING_ID, R.drawable.settings_tune},
            {CONTEXT_MENU_TOGGLE_KEEP_SCREEN_ON, R.drawable.ic_action_screen}};
        for (int[] icon : icons) {
            MenuItem item = menu.findItem(icon[0]);
            if (item != null) item.setIcon(icon[1]);
        }
    }

    /** Hook system menu to show context menu instead. */
    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        mTerminalView.showContextMenu();
        return false;
    }

    @Override
    public boolean onContextItemSelected(MenuItem item) {
        TerminalSession session = getCurrentSession();

        switch (item.getItemId()) {
            case CONTEXT_MENU_SAVE_BOOKMARK_ID:
                saveCurrentBookmark(session);
                return true;
            case CONTEXT_MENU_SELECT_URL_ID:
                mTermuxTerminalViewClient.showUrlSelection();
                return true;
            case CONTEXT_MENU_SHARE_TRANSCRIPT_ID:
                mTermuxTerminalViewClient.shareSessionTranscript();
                return true;
            case CONTEXT_MENU_SHARE_SELECTED_TEXT:
                mTermuxTerminalViewClient.shareSelectedText();
                return true;
            case CONTEXT_MENU_AUTOFILL_USERNAME:
                mTerminalView.requestAutoFillUsername();
                return true;
            case CONTEXT_MENU_AUTOFILL_PASSWORD:
                mTerminalView.requestAutoFillPassword();
                return true;
            case CONTEXT_MENU_RESET_TERMINAL_ID:
                onResetTerminalSession(session);
                return true;
            case CONTEXT_MENU_STYLING_ID:
                showStylingDialog();
                return true;
            case CONTEXT_MENU_TOGGLE_KEEP_SCREEN_ON:
                setKeepScreenOn(item.isChecked());
                return true;
            case CONTEXT_MENU_HELP_ID:
                ActivityUtils.startActivity(this, new Intent(this, HelpActivity.class));
                return true;
            case CONTEXT_MENU_SETTINGS_ID:
                ActivityUtils.startActivity(this, new Intent(this, SettingsActivity.class));
                return true;
            case CONTEXT_MENU_REPORT_ID:
                mTermuxTerminalViewClient.reportIssueFromTranscript();
                return true;
            default:
                return super.onContextItemSelected(item);
        }
    }

    @Override
    public void onContextMenuClosed(Menu menu) {
        super.onContextMenuClosed(menu);
        // onContextMenuClosed() is triggered twice if back button is pressed to dismiss instead of tap for some reason
        mTerminalView.onContextMenuClosed(menu);
    }

    private void saveCurrentBookmark(TerminalSession session) {
        if (mSavingBookmark || session == null || !session.isRunning()) return;
        mSavingBookmark = true;
        showToast(getString(R.string.bookmark_saving), false);
        BookmarkEnvironment.capture(session, new BookmarkEnvironment.Callback() {
            @Override public boolean isActive() {
                return !isFinishing() && !isDestroyed() && getCurrentSession() == session;
            }

            @Override public void onQueryStarted() {
                showToast(getString(R.string.bookmark_query_prompt), true);
            }

            @Override public void onCaptured(TerminalBookmark bookmark) {
                mSavingBookmark = false;
                if (isFinishing() || isDestroyed()) return;
                TextInputDialogUtils.textInput(TermuxActivity.this, R.string.action_save_bookmark,
                    bookmark.name, android.R.string.ok, text -> {
                        if (isFinishing() || isDestroyed()) return;
                        String name = text == null ? "" : text.trim();
                        mBookmarkStore.add(name.isEmpty() ? bookmark : bookmark.withName(name));
                        mBookmarksController.refreshAndReveal(bookmark.id);
                        showToast(getString(R.string.bookmark_saved), false);
                    }, -1, null, -1, null, null);
            }

            @Override public void onError(String message) {
                mSavingBookmark = false;
                if (message != null && !isFinishing() && !isDestroyed()) showToast(message, true);
            }
        });
    }

    public void showCloseSessionDialog(TerminalSession session) {
        if (session == null) return;

        if (!session.isRunning()) {
            mTermuxTerminalSessionActivityClient.closeSession(session);
            return;
        }

        final AlertDialog.Builder b = new MaterialAlertDialogBuilder(this);
        b.setIcon(android.R.drawable.ic_dialog_alert);
        b.setMessage(R.string.title_confirm_close_session);
        b.setPositiveButton(R.string.action_close_session, (dialog, id) -> {
            dialog.dismiss();
            mTermuxTerminalSessionActivityClient.closeSession(session);
        });
        b.setNegativeButton(android.R.string.cancel, null);
        b.show();
    }

    private void onResetTerminalSession(TerminalSession session) {
        if (session != null) {
            session.reset();
            showToast(getResources().getString(R.string.msg_terminal_reset), true);

            if (mTermuxTerminalSessionActivityClient != null)
                mTermuxTerminalSessionActivityClient.onResetTerminalSession();
        }
    }

    private void showStylingDialog() {
        Intent stylingIntent = new Intent();
        stylingIntent.setClassName(TermuxConstants.TERMUX_STYLING_PACKAGE_NAME, TermuxConstants.TERMUX_STYLING_APP.TERMUX_STYLING_ACTIVITY_NAME);
        try {
            startActivity(stylingIntent);
        } catch (ActivityNotFoundException | IllegalArgumentException e) {
            // The startActivity() call is not documented to throw IllegalArgumentException.
            // However, crash reporting shows that it sometimes does, so catch it here.
            new MaterialAlertDialogBuilder(this).setMessage(getString(R.string.error_styling_not_installed))
                .setPositiveButton(R.string.action_styling_install,
                    (dialog, which) -> ActivityUtils.startActivity(this, new Intent(Intent.ACTION_VIEW, Uri.parse(TermuxConstants.TERMUX_STYLING_FDROID_PACKAGE_URL))))
                .setNegativeButton(android.R.string.cancel, null).show();
        }
    }

    private void setKeepScreenOn(boolean enabled) {
        mTerminalView.setKeepScreenOn(enabled);
        mPreferences.setKeepScreenOn(enabled);
    }

    private void applyTerminalDisplayPreferences() {
        // Insets-based immersive mode keeps IME resizing available, unlike FLAG_FULLSCREEN.
        WindowInsetsControllerCompat controller =
            WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        controller.setSystemBarsBehavior(
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        if (mProperties.isUsingFullScreen()) {
            controller.hide(WindowInsetsCompat.Type.systemBars());
        } else {
            controller.show(WindowInsetsCompat.Type.systemBars());
        }
        setTerminalHapticFeedback(getWindow().getDecorView(), mPreferences.isTerminalVibrationEnabled());
    }

    private void setTerminalHapticFeedback(View view, boolean enabled) {
        view.setHapticFeedbackEnabled(enabled);
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++)
                setTerminalHapticFeedback(group.getChildAt(i), enabled);
        }
    }



    /**
     * For processes to access primary external storage (/sdcard, /storage/emulated/0, ~/storage/shared),
     * termux needs to be granted legacy WRITE_EXTERNAL_STORAGE or MANAGE_EXTERNAL_STORAGE permissions
     * if targeting targetSdkVersion 30 (android 11) and running on sdk 30 (android 11) and higher.
     */
    public void requestStoragePermission(boolean isPermissionCallback) {
        new Thread() {
            @Override
            public void run() {
                // Do not ask for permission again
                int requestCode = isPermissionCallback ? -1 : PermissionUtils.REQUEST_GRANT_STORAGE_PERMISSION;

                // If permission is granted, then also setup storage symlinks.
                if(PermissionUtils.checkAndRequestLegacyOrManageExternalStoragePermission(
                    TermuxActivity.this, requestCode, !isPermissionCallback)) {
                    if (isPermissionCallback)
                        Logger.logInfoAndShowToast(TermuxActivity.this, LOG_TAG,
                            getString(com.termux.shared.R.string.msg_storage_permission_granted_on_request));

                    TermuxInstaller.setupStorageSymlinks(TermuxActivity.this);
                } else {
                    if (isPermissionCallback)
                        Logger.logInfoAndShowToast(TermuxActivity.this, LOG_TAG,
                            getString(com.termux.shared.R.string.msg_storage_permission_not_granted_on_request));
                }
            }
        }.start();
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        Logger.logVerbose(LOG_TAG, "onActivityResult: requestCode: " + requestCode + ", resultCode: "  + resultCode + ", data: "  + IntentUtils.getIntentString(data));
        if (requestCode == PermissionUtils.REQUEST_GRANT_STORAGE_PERMISSION) {
            requestStoragePermission(true);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        Logger.logVerbose(LOG_TAG, "onRequestPermissionsResult: requestCode: " + requestCode + ", permissions: "  + Arrays.toString(permissions) + ", grantResults: "  + Arrays.toString(grantResults));
        if (requestCode == PermissionUtils.REQUEST_GRANT_STORAGE_PERMISSION) {
            requestStoragePermission(true);
        }
    }



    public int getNavBarHeight() {
        return mNavBarHeight;
    }

    public TermuxActivityRootView getTermuxActivityRootView() {
        return mTermuxActivityRootView;
    }

    public View getTermuxActivityBottomSpaceView() {
        return mTermuxActivityBottomSpaceView;
    }

    public ExtraKeysView getExtraKeysView() {
        return mExtraKeysView;
    }

    public TermuxTerminalExtraKeys getTermuxTerminalExtraKeys() {
        return mTermuxTerminalExtraKeys;
    }

    public void setExtraKeysView(ExtraKeysView extraKeysView) {
        mExtraKeysView = extraKeysView;
    }

    public DrawerLayout getDrawer() {
        return (DrawerLayout) findViewById(R.id.drawer_layout);
    }


    public HorizontalScrollView getTerminalToolbar() {
        return findViewById(R.id.terminal_toolbar);
    }

    public void termuxSessionListNotifyUpdated() {
        if (mTermuxSessionListViewController != null)
            mTermuxSessionListViewController.notifyDataSetChanged();
        if (getCurrentSession() == null && mTermuxService != null && !mTermuxService.isTermuxSessionsEmpty()) {
            // A background command may create the first session without requesting a switch.
            mTermuxTerminalSessionActivityClient.setCurrentSession(
                mTermuxTerminalSessionActivityClient.getCurrentStoredSessionOrLast());
        } else {
            updateSessionUi();
        }
    }

    public void onSessionActivity(TerminalSession session) {
        if (mTermuxSessionListViewController != null) mTermuxSessionListViewController.onSessionActivity(session);
    }

    public boolean isVisible() {
        return mIsVisible;
    }

    public boolean isOnResumeAfterOnCreate() {
        return mIsOnResumeAfterOnCreate;
    }

    public boolean isActivityRecreated() {
        return mIsActivityRecreated;
    }



    public TermuxService getTermuxService() {
        return mTermuxService;
    }

    public TerminalView getTerminalView() {
        return mTerminalView;
    }

    public TermuxTerminalViewClient getTermuxTerminalViewClient() {
        return mTermuxTerminalViewClient;
    }

    public TermuxTerminalSessionActivityClient getTermuxTerminalSessionClient() {
        return mTermuxTerminalSessionActivityClient;
    }

    @Nullable
    public TerminalSession getCurrentSession() {
        if (mTerminalView != null)
            return mTerminalView.getCurrentSession();
        else
            return null;
    }

    public TermuxAppSharedPreferences getPreferences() {
        return mPreferences;
    }

    public TermuxAppSharedProperties getProperties() {
        return mProperties;
    }




    public static void updateTermuxActivityStyling(Context context, boolean recreateActivity) {
        // Make sure that terminal styling is always applied.
        Intent stylingIntent = new Intent(TERMUX_ACTIVITY.ACTION_RELOAD_STYLE);
        stylingIntent.putExtra(TERMUX_ACTIVITY.EXTRA_RECREATE_ACTIVITY, recreateActivity);
        context.sendBroadcast(stylingIntent);
    }

    private void registerTermuxActivityBroadcastReceiver() {
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(TERMUX_ACTIVITY.ACTION_NOTIFY_APP_CRASH);
        intentFilter.addAction(TERMUX_ACTIVITY.ACTION_RELOAD_STYLE);
        intentFilter.addAction(TERMUX_ACTIVITY.ACTION_REQUEST_PERMISSIONS);

        registerReceiver(mTermuxActivityBroadcastReceiver, intentFilter);
    }

    private void unregisterTermuxActivityBroadcastReceiver() {
        unregisterReceiver(mTermuxActivityBroadcastReceiver);
    }

    private void fixTermuxActivityBroadcastReceiverIntent(Intent intent) {
        if (intent == null) return;

        String extraReloadStyle = intent.getStringExtra(TERMUX_ACTIVITY.EXTRA_RELOAD_STYLE);
        if ("storage".equals(extraReloadStyle)) {
            intent.removeExtra(TERMUX_ACTIVITY.EXTRA_RELOAD_STYLE);
            intent.setAction(TERMUX_ACTIVITY.ACTION_REQUEST_PERMISSIONS);
        }
    }

    class TermuxActivityBroadcastReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent == null) return;

            if (mIsVisible) {
                fixTermuxActivityBroadcastReceiverIntent(intent);

                switch (intent.getAction()) {
                    case TERMUX_ACTIVITY.ACTION_NOTIFY_APP_CRASH:
                        Logger.logDebug(LOG_TAG, "Received intent to notify app crash");
                        TermuxCrashUtils.notifyAppCrashFromCrashLogFile(context, LOG_TAG);
                        return;
                    case TERMUX_ACTIVITY.ACTION_RELOAD_STYLE:
                        Logger.logDebug(LOG_TAG, "Received intent to reload styling");
                        reloadActivityStyling(intent.getBooleanExtra(TERMUX_ACTIVITY.EXTRA_RECREATE_ACTIVITY, true));
                        return;
                    case TERMUX_ACTIVITY.ACTION_REQUEST_PERMISSIONS:
                        Logger.logDebug(LOG_TAG, "Received intent to request storage permissions");
                        requestStoragePermission(false);
                        return;
                    default:
                }
            }
        }
    }

    private void reloadActivityStyling(boolean recreateActivity) {
        if (mProperties != null) {
            reloadProperties();

            if (mExtraKeysView != null) {
                mTermuxTerminalExtraKeys.reload();
                mExtraKeysView.setButtonTextAllCaps(mProperties.shouldExtraKeysTextBeAllCaps());
                mExtraKeysView.reload(mTermuxTerminalExtraKeys.getExtraKeysInfo(), mTerminalToolbarDefaultHeight);
            }

            // Update NightMode.APP_NIGHT_MODE
            TermuxThemeUtils.setAppNightMode(mProperties.getNightMode());
        }

        setMargins();
        setTerminalToolbarHeight();
        applyTerminalDisplayPreferences();

        FileReceiverActivity.updateFileReceiverActivityComponentsState(this);

        if (mTermuxTerminalSessionActivityClient != null)
            mTermuxTerminalSessionActivityClient.onReloadActivityStyling();

        if (mTermuxTerminalViewClient != null)
            mTermuxTerminalViewClient.onReloadActivityStyling();

        // To change the activity and drawer theme, activity needs to be recreated.
        // It will destroy the activity, including all stored variables and views, and onCreate()
        // will be called again. Extra keys input text, terminal sessions and transcripts will be preserved.
        if (recreateActivity) {
            Logger.logDebug(LOG_TAG, "Recreating activity");
            TermuxActivity.this.recreate();
        }
    }



    public static void startTermuxActivity(@NonNull final Context context) {
        ActivityUtils.startActivity(context, newInstance(context));
    }

    public static Intent newInstance(@NonNull final Context context) {
        Intent intent = new Intent(context, TermuxActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        return intent;
    }

}
