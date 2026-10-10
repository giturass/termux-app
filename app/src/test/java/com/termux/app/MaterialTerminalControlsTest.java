package com.termux.app;

import android.app.Application;
import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.ResolveInfo;
import android.provider.DocumentsContract;
import android.graphics.drawable.Drawable;
import android.view.Gravity;
import android.view.ContextThemeWrapper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.MotionEvent;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.ImageButton;
import android.os.SystemClock;
import android.text.InputType;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;

import androidx.fragment.app.FragmentController;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.termux.R;
import com.termux.filepicker.TermuxDocumentsProvider;
import com.termux.shared.termux.TermuxConstants;
import com.termux.app.terminal.TermuxTerminalViewClient;
import com.termux.app.terminal.io.TermuxTerminalExtraKeys;
import com.termux.app.settings.properties.TermuxPropertiesSettings;
import com.termux.shared.termux.settings.properties.TermuxAppSharedProperties;
import com.termux.shared.termux.settings.properties.TermuxPropertyConstants;
import com.termux.shared.termux.settings.properties.TermuxSharedProperties;
import com.termux.shared.termux.settings.preferences.TermuxAppSharedPreferences;
import com.termux.shared.termux.settings.preferences.TermuxPreferenceConstants;
import com.termux.shared.termux.extrakeys.ExtraKeysConstants;
import com.termux.shared.termux.extrakeys.ExtraKeyButton;
import com.termux.shared.termux.extrakeys.ExtraKeysInfo;
import com.termux.shared.termux.extrakeys.ExtraKeysView;
import com.termux.shared.termux.extrakeys.SpecialButton;
import com.termux.shared.termux.terminal.io.TerminalExtraKeys;
import com.termux.shared.termux.terminal.TermuxTerminalSessionClientBase;
import com.termux.terminal.TerminalEmulator;
import com.termux.terminal.TerminalSession;

import org.junit.Test;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Robolectric;
import org.robolectric.Shadows;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.Implementation;
import org.robolectric.annotation.Implements;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowActivity;
import org.robolectric.util.ReflectionHelpers;
import org.robolectric.util.ReflectionHelpers.ClassParameter;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 31, application = Application.class, qualifiers = "zh-rCN-w320dp-h640dp")
public class MaterialTerminalControlsTest {
    @Rule public TemporaryFolder temporary = new TemporaryFolder();

    @Test
    @Config(shadows = RecordingRecreationActivity.class)
    public void returningFromSettingsReloadsSavedValuesAndRecreatesOnlyOnce() throws Exception {
        TermuxActivity activity = drawerActivity(true);
        TermuxAppSharedProperties runtime = activity.getProperties();
        List<String> originalPaths = ReflectionHelpers.getField(runtime, "mPropertiesFilePaths");
        File primary = temporary.newFile("termux.properties");
        File secondary = new File(temporary.getRoot(), "secondary.properties");
        Files.write(primary.toPath(), "terminal-margin-horizontal=3\n".getBytes(StandardCharsets.UTF_8));
        try {
            ReflectionHelpers.setField(runtime, "mPropertiesFilePaths", Collections.singletonList(primary.getAbsolutePath()));
            ReflectionHelpers.callInstanceMethod(activity, "reloadProperties");
            ReflectionHelpers.callInstanceMethod(activity, "setTerminalToolbarView");
            // This test needs the real activity reload path, but no native session or keyboard work.
            activity.mTermuxTerminalViewClient = null;
            // drawerActivity skips onCreate to avoid starting TermuxService. Attach its fragment
            // host explicitly so the real FragmentActivity.onResume can run normally.
            FragmentController fragments = ReflectionHelpers.getField(activity, "mFragments");
            fragments.attachHost(null);
            RecordingRecreationActivity shadow = Shadow.extract(activity);
            activity.onResume();
            assertEquals(0, shadow.recreations);

            TermuxPropertiesSettings settings = ReflectionHelpers.callConstructor(TermuxPropertiesSettings.class,
                ClassParameter.from(Context.class, activity), ClassParameter.from(File.class, primary),
                ClassParameter.from(File.class, secondary));
            settings.set(TermuxPropertyConstants.KEY_TERMINAL_MARGIN_HORIZONTAL, 22);
            activity.onResume();

            assertEquals(1, shadow.recreations);
            assertEquals(22, activity.getProperties().getTerminalMarginHorizontal());
            ViewGroup.MarginLayoutParams margins = (ViewGroup.MarginLayoutParams)
                activity.findViewById(R.id.activity_termux_root_relative_layout).getLayoutParams();
            assertEquals(Math.round(22 * activity.getResources().getDisplayMetrics().density), margins.leftMargin);
            activity.onResume();
            assertEquals(1, shadow.recreations);
        } finally {
            ReflectionHelpers.setField(runtime, "mPropertiesFilePaths", originalPaths);
            runtime.loadTermuxPropertiesFromDisk();
        }
    }

    @Implements(Activity.class)
    public static class RecordingRecreationActivity extends ShadowActivity {
        int recreations;

        @Implementation
        protected void recreate() {
            recreations++;
        }
    }

    @Test
    public void characterInputPropertyOverridesComposingWithoutLosingItsPreference() {
        TermuxActivity activity = drawerActivity(true);
        activity.getPreferences().setImeComposingEnabled(true);
        setProperty(activity, TermuxPropertyConstants.KEY_ENFORCE_CHAR_BASED_INPUT, "false");
        EditorInfo composing = new EditorInfo();
        assertNotNull(activity.getTerminalView().onCreateInputConnection(composing));
        assertEquals(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_NORMAL
            | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS, composing.inputType);
        assertTrue(activity.getTermuxTerminalViewClient().shouldEnableImeComposing());

        setProperty(activity, TermuxPropertyConstants.KEY_ENFORCE_CHAR_BASED_INPUT, "true");
        EditorInfo characterInput = new EditorInfo();
        assertNotNull(activity.getTerminalView().onCreateInputConnection(characterInput));
        assertEquals(InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
            | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS, characterInput.inputType);
        assertFalse(activity.getTermuxTerminalViewClient().shouldEnableImeComposing());
        assertTrue(activity.getPreferences().isImeComposingEnabled());

        setProperty(activity, TermuxPropertyConstants.KEY_ENFORCE_CHAR_BASED_INPUT, "false");
        EditorInfo restored = new EditorInfo();
        activity.getTerminalView().onCreateInputConnection(restored);
        assertEquals(composing.inputType, restored.inputType);
        assertTrue(activity.getTermuxTerminalViewClient().shouldEnableImeComposing());
    }

    @Test
    public void toolbarStyleReloadPreservesEveryExistingKeyAndItsPosition() {
        TermuxActivity activity = drawerActivity(true);
        setProperty(activity, TermuxPropertyConstants.KEY_EXTRA_KEYS_STYLE, "default");
        setProperty(activity, TermuxPropertyConstants.KEY_EXTRA_KEYS, TermuxPropertyConstants.DEFAULT_IVALUE_EXTRA_KEYS);
        TermuxTerminalExtraKeys extraKeys = new TermuxTerminalExtraKeys(activity, activity.mTerminalView,
            activity.mTermuxTerminalViewClient, null);
        ExtraKeysInfo before = extraKeys.getExtraKeysInfo();
        assertEquals("ESC", before.getMatrix()[0][0].getDisplay());

        setProperty(activity, TermuxPropertyConstants.KEY_EXTRA_KEYS_STYLE, "all");
        extraKeys.reload();

        ExtraKeysInfo after = extraKeys.getExtraKeysInfo();
        assertEquals(1, after.getMatrix().length);
        assertEquals(11, after.getMatrix()[0].length);
        assertNotEquals(before.getMatrix()[0][0].getDisplay(), after.getMatrix()[0][0].getDisplay());
        for (int i = 0; i < before.getMatrix()[0].length; i++)
            assertEquals(before.getMatrix()[0][i].getKey(), after.getMatrix()[0][i].getKey());
        assertEquals("TAB", after.getMatrix()[0][4].getDisplay());
    }

    @Test
    public void toolbarReloadUsesCustomKeysRowsAndPopups() {
        TermuxActivity activity = drawerActivity(true);
        setProperty(activity, TermuxPropertyConstants.KEY_EXTRA_KEYS,
            "[['ESC',{'key':'TAB','popup':'HOME'}],['CTRL','KEYBOARD']]");
        TermuxTerminalExtraKeys extraKeys = new TermuxTerminalExtraKeys(activity, activity.mTerminalView,
            activity.mTermuxTerminalViewClient, null);
        ExtraKeysInfo configured = extraKeys.getExtraKeysInfo();
        assertEquals(2, configured.getMatrix().length);
        assertEquals(2, configured.getMatrix()[0].length);
        assertEquals("HOME", configured.getMatrix()[0][1].getPopup().getKey());
        assertEquals("KEYBOARD", configured.getMatrix()[1][1].getKey());

        setProperty(activity, TermuxPropertyConstants.KEY_EXTRA_KEYS, "[['A','B']]");
        extraKeys.reload();
        assertEquals(1, extraKeys.getExtraKeysInfo().getMatrix().length);
        assertEquals(2, extraKeys.getExtraKeysInfo().getMatrix()[0].length);
        assertEquals("A", extraKeys.getExtraKeysInfo().getMatrix()[0][0].getKey());
    }

    @Test
    @Config(shadows = RecordingRecreationActivity.class)
    public void returningFromSettingsSwitchesCursorControlsWithoutRecreation() {
        TermuxActivity activity = drawerActivity(true);
        setProperty(activity, TermuxPropertyConstants.KEY_EXTRA_KEYS, TermuxPropertyConstants.DEFAULT_IVALUE_EXTRA_KEYS);
        ReflectionHelpers.callInstanceMethod(activity, "setTerminalToolbarView");
        ReflectionHelpers.setField(activity, "mPropertiesRevision", TermuxPropertiesSettings.getRevision());
        TermuxTerminalViewClient client = activity.getTermuxTerminalViewClient();
        activity.mTermuxTerminalViewClient = null;
        FragmentController fragments = ReflectionHelpers.getField(activity, "mFragments");
        fragments.attachHost(null);

        assertTrue(activity.getPreferences().isCursorGesturesEnabled());
        assertTrue(client.shouldUseHorizontalCursorGestures());
        assertFalse(client.shouldUseVerticalCursorGestures());
        List<String> original = toolbarKeyNames(activity.getTermuxTerminalExtraKeys().getExtraKeysInfo());
        MaterialButton cursor = toolbarButton(activity, "CURSOR");
        cursor.performClick();
        toolbarButton(activity, "CTRL").performClick();
        activity.onResume();
        assertSame(cursor, toolbarButton(activity, "CURSOR"));
        assertTrue(client.shouldUseVerticalCursorGestures());

        activity.getPreferences().setCursorGesturesEnabled(false);
        assertFalse(client.shouldUseHorizontalCursorGestures());
        assertFalse(client.shouldUseVerticalCursorGestures());
        activity.onResume();
        ExtraKeysInfo traditional = activity.getTermuxTerminalExtraKeys().getExtraKeysInfo();
        assertTraditionalCursorKeys(traditional);
        assertEquals(14, traditional.getMatrix()[0].length);
        assertTrue(toolbarButton(activity, "CTRL").isChecked());
        assertEquals(View.VISIBLE, activity.getTerminalToolbar().getVisibility());
        activity.onResume();
        assertSame(traditional, activity.getTermuxTerminalExtraKeys().getExtraKeysInfo());

        activity.toggleTerminalToolbar();
        activity.getPreferences().setCursorGesturesEnabled(true);
        activity.onResume();
        assertEquals(original, toolbarKeyNames(activity.getTermuxTerminalExtraKeys().getExtraKeysInfo()));
        assertTrue(client.shouldUseHorizontalCursorGestures());
        assertTrue(client.shouldUseVerticalCursorGestures());
        assertTrue(toolbarButton(activity, "CURSOR").isChecked());
        assertFalse(activity.getPreferences().shouldShowTerminalToolbar());
        assertEquals(View.GONE, activity.getTerminalToolbar().getVisibility());
        RecordingRecreationActivity shadow = Shadow.extract(activity);
        assertEquals(0, shadow.recreations);
    }

    @Test
    public void traditionalCursorControlsPreserveCustomRowsMacrosPopupsAndStyle() throws Exception {
        TermuxActivity activity = drawerActivity(true);
        String layout = "[['LEFT',{'key':'CTRL','popup':{'macro':'CTRL c','display':'Copy'}},"
            + "{'key':'CURSOR','popup':'PGUP'}],[{'macro':'echo CURSOR','display':'literal'},"
            + "{'key':'RIGHT','display':'Right','popup':{'macro':'CURSOR'}}]]";
        setProperty(activity, TermuxPropertyConstants.KEY_EXTRA_KEYS, layout);
        setProperty(activity, TermuxPropertyConstants.KEY_EXTRA_KEYS_STYLE, "all");
        activity.getPreferences().setCursorGesturesEnabled(false);
        TermuxTerminalExtraKeys extraKeys = new TermuxTerminalExtraKeys(activity, activity.mTerminalView,
            activity.mTermuxTerminalViewClient, null);
        ExtraKeysInfo configured = new ExtraKeysInfo(layout, "all", ExtraKeysConstants.CONTROL_CHARS_ALIASES);
        ExtraKeyButton[][] traditional = extraKeys.getExtraKeysInfo().getMatrix();
        assertTraditionalCursorKeys(extraKeys.getExtraKeysInfo());
        assertEquals(2, traditional.length);
        assertEquals(4, traditional[0].length);
        assertEquals(configured.getMatrix()[0][1].getDisplay(), traditional[0][1].getDisplay());
        assertEquals("CTRL c", traditional[0][1].getPopup().getKey());
        assertTrue(traditional[0][1].getPopup().isMacro());
        assertEquals("PGUP", traditional[0][2].getPopup().getKey());
        assertEquals("echo CURSOR", traditional[1][0].getKey());
        assertTrue(traditional[1][0].isMacro());
        assertEquals("literal", traditional[1][0].getDisplay());
        assertEquals("Right", traditional[1][1].getDisplay());
        assertNull(traditional[1][1].getPopup());
        assertEquals(layout, activity.getProperties().getInternalPropertyValue(TermuxPropertyConstants.KEY_EXTRA_KEYS, true));

        activity.getPreferences().setCursorGesturesEnabled(true);
        assertTrue(extraKeys.reloadIfCursorGesturesChanged());
        ExtraKeyButton[][] restored = extraKeys.getExtraKeysInfo().getMatrix();
        assertEquals(3, restored[0].length);
        assertEquals("CURSOR", restored[0][2].getKey());
        assertEquals("PGUP", restored[0][2].getPopup().getKey());
        assertEquals("CURSOR", restored[1][1].getPopup().getKey());
        assertTrue(restored[1][1].getPopup().isMacro());
    }

    @Test
    public void traditionalCursorControlsFillEmptyOrIncompleteCustomLayouts() {
        TermuxActivity activity = drawerActivity(true);
        activity.getPreferences().setCursorGesturesEnabled(false);
        for (String layout : new String[]{"[]", "[[]]", "[['A']]", "[[{'macro':'CURSOR'}]]",
            "[['UP','DOWN'],['LEFT','CURSOR','RIGHT']]"}) {
            setProperty(activity, TermuxPropertyConstants.KEY_EXTRA_KEYS, layout);
            TermuxTerminalExtraKeys extraKeys = new TermuxTerminalExtraKeys(activity, activity.mTerminalView,
                activity.mTermuxTerminalViewClient, null);
            assertTraditionalCursorKeys(extraKeys.getExtraKeysInfo());
            assertTrue(extraKeys.getExtraKeysInfo().getMatrix().length > 0);
            assertEquals(layout, activity.getProperties().getInternalPropertyValue(TermuxPropertyConstants.KEY_EXTRA_KEYS, true));
        }
    }

    @Test
    public void traditionalCursorControlsKeepAlternateActionWhenArrowsAlreadyExist() {
        TermuxActivity activity = drawerActivity(true);
        activity.getPreferences().setCursorGesturesEnabled(false);
        setProperty(activity, TermuxPropertyConstants.KEY_EXTRA_KEYS,
            "[['LEFT','DOWN','UP','RIGHT',{'key':'CURSOR','popup':{'macro':'CTRL c','display':'Copy'}}]]");
        TermuxTerminalExtraKeys extraKeys = new TermuxTerminalExtraKeys(activity, activity.mTerminalView,
            activity.mTermuxTerminalViewClient, null);
        assertTraditionalCursorKeys(extraKeys.getExtraKeysInfo());
        ExtraKeyButton alternate = extraKeys.getExtraKeysInfo().getMatrix()[0][4];
        assertEquals("CTRL c", alternate.getKey());
        assertEquals("Copy", alternate.getDisplay());
        assertTrue(alternate.isMacro());
    }

    @Test
    public void invalidToolbarConfigurationFallsBackToTheSelectedCursorMode() {
        TermuxActivity activity = drawerActivity(true);
        setProperty(activity, TermuxPropertyConstants.KEY_EXTRA_KEYS, "[[");
        activity.getPreferences().setCursorGesturesEnabled(false);
        TermuxTerminalExtraKeys extraKeys = new TermuxTerminalExtraKeys(activity, activity.mTerminalView,
            activity.mTermuxTerminalViewClient, null);
        assertTraditionalCursorKeys(extraKeys.getExtraKeysInfo());
        assertEquals(14, extraKeys.getExtraKeysInfo().getMatrix()[0].length);
        activity.getPreferences().setCursorGesturesEnabled(true);
        extraKeys.reload();
        assertEquals(11, extraKeys.getExtraKeysInfo().getMatrix()[0].length);
        assertEquals("CURSOR", extraKeys.getExtraKeysInfo().getMatrix()[0][5].getKey());
    }

    @Test
    public void traditionalToolbarArrowsSendNormalApplicationAndModifiedCursorSequences() {
        TermuxActivity activity = drawerActivity(true);
        activity.getPreferences().setCursorGesturesEnabled(false);
        setProperty(activity, TermuxPropertyConstants.KEY_EXTRA_KEYS, TermuxPropertyConstants.DEFAULT_IVALUE_EXTRA_KEYS);
        ReflectionHelpers.callInstanceMethod(activity, "setTerminalToolbarView");
        TermuxTerminalSessionClientBase client = new TermuxTerminalSessionClientBase();
        TerminalSession session = new TerminalSession("", "", new String[0], new String[0], 100, client);
        TerminalEmulator emulator = new TerminalEmulator(session, 80, 24, 10, 20, 100, client);
        ReflectionHelpers.setField(session, "mEmulator", emulator);
        ReflectionHelpers.setField(session, "mShellPid", 1);
        ReflectionHelpers.setField(activity.getTerminalView(), "mTermSession", session);
        activity.getTerminalView().mEmulator = emulator;

        for (String prefix : new String[]{"\u001b[", "\u001bO"}) {
            if (prefix.equals("\u001bO")) {
                byte[] mode = "\u001b[?1h".getBytes(StandardCharsets.UTF_8);
                emulator.append(mode, mode.length);
            }
            for (String key : Arrays.asList("UP", "DOWN", "LEFT", "RIGHT")) toolbarButton(activity, key).performClick();
            assertEquals(prefix + "A" + prefix + "B" + prefix + "D" + prefix + "C", takeTerminalOutput(session));
        }
        toolbarButton(activity, "CTRL").performClick();
        toolbarButton(activity, "LEFT").performClick();
        assertEquals("\u001b[1;5D", takeTerminalOutput(session));
        assertFalse(toolbarButton(activity, "CTRL").isChecked());
    }

    @Test
    public void vibrationPreferenceUpdatesTerminalAndDrawerFeedbackWithoutRecreation() {
        TermuxActivity activity = drawerActivity(true);
        for (boolean enabled : new boolean[]{false, true}) {
            activity.getPreferences().setTerminalVibrationEnabled(enabled);
            ReflectionHelpers.callInstanceMethod(activity, "applyTerminalDisplayPreferences");
            assertEquals(enabled, activity.getTerminalView().isHapticFeedbackEnabled());
            assertEquals(enabled, activity.findViewById(R.id.new_session_button).isHapticFeedbackEnabled());
        }
    }

    @Test
    public void fileButtonOpensSystemFileManagerAtMdtermRoot() {
        TermuxActivity activity = drawerActivity(true);
        Intent browse = new Intent(Intent.ACTION_VIEW).setDataAndType(
            TermuxDocumentsProvider.getRootUri(), DocumentsContract.Root.MIME_TYPE_ITEM);
        ResolveInfo manager = new ResolveInfo();
        manager.activityInfo = new ActivityInfo();
        manager.activityInfo.packageName = "com.android.documentsui";
        manager.activityInfo.name = "com.android.documentsui.files.FilesActivity";
        manager.activityInfo.applicationInfo = new ApplicationInfo();
        manager.activityInfo.applicationInfo.flags = ApplicationInfo.FLAG_SYSTEM;
        Shadows.shadowOf(activity.getPackageManager()).addResolveInfoForIntent(browse, manager);
        ReflectionHelpers.callInstanceMethod(activity, "setFileSystemView");

        activity.findViewById(R.id.file_system_button).performClick();
        Intent launched = Shadows.shadowOf(activity).getNextStartedActivity();
        assertNotNull(launched);
        assertEquals(Intent.ACTION_VIEW, launched.getAction());
        assertEquals("com.android.documentsui", launched.getComponent().getPackageName());
        assertEquals(DocumentsContract.Root.MIME_TYPE_ITEM, launched.getType());
        assertEquals(TermuxConstants.TERMUX_PACKAGE_NAME + ".documents", launched.getData().getAuthority());
        assertEquals(TermuxConstants.TERMUX_FILES_DIR_PATH, DocumentsContract.getRootId(launched.getData()));
    }

    @Test
    public void cursorToggleIsIndependentOfCtrlAndStaysActiveAcrossKeyPresses() throws Exception {
        TermuxActivity activity = drawerActivity(true);
        ExtraKeysView keys = (ExtraKeysView) LayoutInflater.from(activity)
            .inflate(R.layout.view_terminal_toolbar_extra_keys, null);
        ExtraKeysInfo info = new ExtraKeysInfo("[['CTRL', 'CURSOR', 'ESC']]", "default", ExtraKeysConstants.CONTROL_CHARS_ALIASES);
        keys.reload(info, 52);
        activity.setExtraKeysView(keys);
        int[] keyPresses = {0};
        keys.setExtraKeysViewClient(new TerminalExtraKeys(activity.getTerminalView()) {
            @Override
            protected void onTerminalExtraKeyButtonClick(View view, String key, boolean ctrl, boolean alt, boolean shift, boolean fn) {
                assertEquals("ESC", key);
                keyPresses[0]++;
            }
        });
        TermuxTerminalViewClient client = activity.getTermuxTerminalViewClient();
        MaterialButton ctrl = (MaterialButton) keys.getChildAt(0);
        MaterialButton cursor = (MaterialButton) keys.getChildAt(1);
        assertTrue(client.shouldUseHorizontalCursorGestures());
        assertFalse(client.shouldUseVerticalCursorGestures());
        ctrl.performClick();
        assertFalse(client.shouldUseVerticalCursorGestures());
        cursor.performClick();
        assertTrue(client.shouldUseVerticalCursorGestures());
        assertTrue(client.shouldUseVerticalCursorGestures());
        assertTrue(cursor.isChecked());
        assertTrue(ctrl.isChecked());
        assertEquals(0, keyPresses[0]);
        assertTrue(client.readControlKey());
        assertFalse(client.readControlKey());
        assertFalse(ctrl.isChecked());
        keys.getChildAt(2).performClick();
        assertEquals(1, keyPresses[0]);
        assertTrue(client.shouldUseVerticalCursorGestures());
        keys.reload(info, 52);
        cursor = (MaterialButton) keys.getChildAt(1);
        assertTrue(cursor.isChecked());
        cursor.performClick();
        assertFalse(client.shouldUseVerticalCursorGestures());
        assertFalse(cursor.isChecked());
        assertTrue(client.shouldUseHorizontalCursorGestures());
    }

    @Test
    public void openingDrawerHidesInputWithoutChangingToolbarPreference() {
        TermuxActivity activity = drawerActivity(true);
        InputMethodManager input = (InputMethodManager) activity.getSystemService(Context.INPUT_METHOD_SERVICE);
        input.showSoftInput(activity.getTerminalView(), 0);
        assertTrue(Shadows.shadowOf(input).isSoftInputVisible());

        activity.getDrawer().openDrawer(Gravity.START, false);
        assertEquals(View.GONE, activity.getTerminalToolbar().getVisibility());
        assertFalse(Shadows.shadowOf(input).isSoftInputVisible());
        assertTrue(activity.getPreferences().shouldShowTerminalToolbar());

        Runnable delayedKeyboard = ReflectionHelpers.callInstanceMethod(
            activity.getTermuxTerminalViewClient(), "getShowSoftKeyboardRunnable");
        delayedKeyboard.run();
        assertFalse(Shadows.shadowOf(input).isSoftInputVisible());

        activity.getDrawer().closeDrawer(Gravity.START, false);
        assertEquals(View.VISIBLE, activity.getTerminalToolbar().getVisibility());
        assertFalse(Shadows.shadowOf(input).isSoftInputVisible());
    }

    @Test
    public void toolbarKeyboardButtonTogglesInputAfterCursorControl() {
        TermuxActivity activity = drawerActivity(true);
        int[] toggleRequests = {0};
        activity.mTermuxTerminalViewClient = new TermuxTerminalViewClient(activity, null) {
            @Override
            public void onToggleSoftKeyboardRequest() {
                assertFalse(activity.getDrawer().isDrawerVisible(Gravity.START));
                assertEquals(View.VISIBLE, activity.getTerminalToolbar().getVisibility());
                toggleRequests[0]++;
            }
        };
        activity.mTerminalView.setTerminalViewClient(activity.mTermuxTerminalViewClient);
        ReflectionHelpers.setField(activity, "mProperties", TermuxAppSharedProperties.init(activity));
        TermuxTerminalExtraKeys extraKeys = new TermuxTerminalExtraKeys(activity, activity.mTerminalView,
            activity.mTermuxTerminalViewClient, null);
        ExtraKeysView keys = activity.findViewById(R.id.terminal_toolbar_extra_keys);
        keys.setExtraKeysViewClient(extraKeys);
        keys.reload(extraKeys.getExtraKeysInfo(), 52);
        MaterialButton keyboard = (MaterialButton) keys.getChildAt(6);
        assertEquals("CURSOR", extraKeys.getExtraKeysInfo().getMatrix()[0][5].getKey());
        assertEquals("KEYBOARD", extraKeys.getExtraKeysInfo().getMatrix()[0][6].getKey());
        assertNotNull(keyboard.getIcon());
        assertEquals("", keyboard.getText().toString());
        assertEquals(activity.getString(com.termux.shared.R.string.extra_keys_keyboard_description),
            keyboard.getContentDescription());

        keyboard.performClick();
        assertEquals(1, toggleRequests[0]);
        keyboard.performClick();
        assertEquals(2, toggleRequests[0]);
        assertTrue(activity.getPreferences().shouldShowTerminalToolbar());

        activity.getDrawer().openDrawer(Gravity.START, false);
        activity.getDrawer().closeDrawer(Gravity.START, false);
        assertEquals(2, toggleRequests[0]);
    }

    @Test
    public void closingDrawerRespectsDisabledToolbarAndChangesMadeWhileOpen() {
        TermuxActivity activity = drawerActivity(false);
        activity.getDrawer().openDrawer(Gravity.START, false);
        activity.getDrawer().closeDrawer(Gravity.START, false);
        assertEquals(View.GONE, activity.getTerminalToolbar().getVisibility());

        activity.getDrawer().openDrawer(Gravity.START, false);
        activity.toggleTerminalToolbar();
        assertTrue(activity.getPreferences().shouldShowTerminalToolbar());
        assertEquals(View.GONE, activity.getTerminalToolbar().getVisibility());
        activity.getDrawer().closeDrawer(Gravity.START, false);
        assertEquals(View.VISIBLE, activity.getTerminalToolbar().getVisibility());
    }

    private static TermuxActivity drawerActivity(boolean showToolbar) {
        // Attach the activity without starting terminal sessions or the native service.
        TermuxActivity activity = Robolectric.buildActivity(TermuxActivity.class).get();
        activity.setTheme(R.style.Theme_TermuxActivity_DayNight_NoActionBar);
        activity.setContentView(R.layout.activity_termux);
        // Seed the file before build() opens both private and multi-process preferences.
        // On a missing file those opens can start overlapping disk loads on API 31,
        // letting a late empty load overwrite the toolbar value used by this fixture.
        assertTrue(activity.getSharedPreferences(
            TermuxConstants.TERMUX_DEFAULT_PREFERENCES_FILE_BASENAME_WITHOUT_EXTENSION, Context.MODE_PRIVATE)
            .edit().putBoolean(TermuxPreferenceConstants.TERMUX_APP.KEY_SHOW_TERMINAL_TOOLBAR, showToolbar)
            .commit());
        TermuxAppSharedPreferences preferences = TermuxAppSharedPreferences.build(activity);
        assertNotNull(preferences);
        assertEquals(showToolbar, preferences.shouldShowTerminalToolbar());
        ReflectionHelpers.setField(activity, "mPreferences", preferences);
        TermuxAppSharedProperties properties = TermuxAppSharedProperties.init(activity);
        properties.loadTermuxPropertiesFromDisk();
        ReflectionHelpers.setField(activity, "mProperties", properties);
        activity.mTerminalView = activity.findViewById(R.id.terminal_view);
        activity.mTermuxTerminalViewClient = new TermuxTerminalViewClient(activity, null);
        activity.mTerminalView.setTerminalViewClient(activity.mTermuxTerminalViewClient);
        activity.getTerminalToolbar().setVisibility(showToolbar ? View.VISIBLE : View.GONE);
        ReflectionHelpers.callInstanceMethod(activity, "setAdaptiveDrawerLayout");
        measure(activity.getDrawer(), activity, 320, 640);
        return activity;
    }

    private static void setProperty(TermuxActivity activity, String key, String value) {
        Object shared = ReflectionHelpers.getField(activity.getProperties(), "mSharedProperties");
        java.util.Map<String, Object> values = ReflectionHelpers.getField(shared, "mMap");
        values.put(key, TermuxSharedProperties.getInternalTermuxPropertyValueFromValue(activity, key, value));
    }

    private static List<String> toolbarKeyNames(ExtraKeysInfo info) {
        List<String> names = new ArrayList<>();
        for (ExtraKeyButton[] row : info.getMatrix()) {
            for (ExtraKeyButton button : row) names.add(button.getKey());
        }
        return names;
    }

    private static void assertTraditionalCursorKeys(ExtraKeysInfo info) {
        List<String> keys = toolbarKeyNames(info);
        assertFalse(keys.contains("CURSOR"));
        for (String arrow : Arrays.asList("LEFT", "DOWN", "UP", "RIGHT"))
            assertEquals(arrow, 1, Collections.frequency(keys, arrow));
        for (ExtraKeyButton[] row : info.getMatrix()) {
            for (ExtraKeyButton button : row) {
                if (button.getPopup() != null) assertNotEquals("CURSOR", button.getPopup().getKey());
            }
        }
    }

    private static MaterialButton toolbarButton(TermuxActivity activity, String key) {
        int index = toolbarKeyNames(activity.getTermuxTerminalExtraKeys().getExtraKeysInfo()).indexOf(key);
        assertTrue("Missing toolbar key " + key, index >= 0);
        return (MaterialButton) activity.getExtraKeysView().getChildAt(index);
    }

    private static String takeTerminalOutput(TerminalSession session) {
        Object queue = ReflectionHelpers.getField(session, "mTerminalToProcessIOQueue");
        byte[] bytes = new byte[4096];
        int count = ReflectionHelpers.callInstanceMethod(queue, "read",
            ClassParameter.from(byte[].class, bytes), ClassParameter.from(boolean.class, false));
        return new String(bytes, 0, count, StandardCharsets.UTF_8);
    }

    @Test
    public void drawerIconActionsFitNarrowAndCompactLayouts() {
        TermuxActivity activity = drawerActivity(true);
        FloatingActionButton newSession = activity.findViewById(R.id.new_session_button);
        ImageButton files = activity.findViewById(R.id.file_system_button);
        ImageButton settings = activity.findViewById(R.id.settings_button);
        for (int height : new int[]{640, 320, 640}) {
            measure(activity.getDrawer(), activity, 320, height);
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle();
            measure(activity.getDrawer(), activity, 320, height);
            assertSame(settings.getParent(), files.getParent());
            assertEquals(settings.getTop(), files.getTop());
            assertTrue(files.getRight() <= settings.getLeft());
            assertEquals(newSession.getWidth(), newSession.getHeight());
            assertEquals(Math.round(56 * activity.getResources().getDisplayMetrics().density), newSession.getWidth());
        }
        assertEquals(activity.getString(R.string.action_new_session), newSession.getContentDescription());
        assertEquals(activity.getString(R.string.action_open_file_system), files.getContentDescription());
        assertNotNull(newSession.getDrawable());
        assertNotNull(files.getDrawable());
    }

    @Test
    public void toolbarScrollsOneRowWithHomeAndEndBeforePageKeysAndNoTextInput() throws Exception {
        Context context = themedContext();
        View root = LayoutInflater.from(context).inflate(R.layout.activity_termux, null);
        HorizontalScrollView toolbar = root.findViewById(R.id.terminal_toolbar);
        toolbar.setVisibility(View.VISIBLE);
        ExtraKeysView keys = toolbar.findViewById(R.id.terminal_toolbar_extra_keys);
        keys.reload(new ExtraKeysInfo(com.termux.shared.termux.settings.properties.TermuxPropertyConstants.DEFAULT_IVALUE_EXTRA_KEYS,
            "default", ExtraKeysConstants.CONTROL_CHARS_ALIASES), 52);
        measure(toolbar, context, 304, 52);
        String[] labels = {"ESC", "CTRL", "ALT", "/", "TAB", "", "", "HOME", "END", "PGUP", "PGDN"};
        assertEquals(labels.length, keys.getChildCount());
        assertEquals(1, toolbar.getChildCount());
        assertEquals(1, keys.getRowCount());
        assertNoTextInput(toolbar);
        MaterialButton cursor = (MaterialButton) keys.getChildAt(5);
        assertNotNull(cursor.getIcon());
        assertEquals(context.getString(com.termux.shared.R.string.extra_keys_cursor_description),
            cursor.getContentDescription());
        float density = context.getResources().getDisplayMetrics().density;
        for (int i = 0; i < labels.length; i++) {
            MaterialButton key = (MaterialButton) keys.getChildAt(i);
            assertEquals(labels[i], key.getText().toString());
            assertEquals(keys.getChildAt(0).getTop(), key.getTop());
            assertTrue(key.getWidth() >= Math.round(48 * density));
            assertTrue(key.getHeight() > 0);
            assertTrue(key.getBottom() <= toolbar.getHeight());
            assertNotNull(key.getLayout());
            assertEquals(0, key.getLayout().getEllipsisCount(0));
            assertTrue(key.getLayout().getLineWidth(0) <= key.getWidth() - key.getCompoundPaddingLeft() - key.getCompoundPaddingRight());
        }
        assertTrue(toolbar.canScrollHorizontally(1));
        toolbar.scrollTo(keys.getWidth(), 0);
        assertTrue(toolbar.getScrollX() > 0);
        View lastKey = keys.getChildAt(keys.getChildCount() - 1);
        assertTrue(lastKey.getRight() - toolbar.getScrollX() <= toolbar.getWidth());
        assertFalse(toolbar.canScrollHorizontally(1));

        // A wider viewport fills the available space without adding another row.
        measure(toolbar, context, 600, 52);
        assertEquals(toolbar.getWidth(), keys.getWidth());
        assertFalse(toolbar.canScrollHorizontally(1));
        assertEquals(keys.getChildAt(0).getTop(), lastKey.getTop());
    }

    @Test
    public void draggingToolbarDoesNotToggleOrLockThePressedKey() throws Exception {
        Context context = themedContext();
        View root = LayoutInflater.from(context).inflate(R.layout.activity_termux, null);
        HorizontalScrollView toolbar = root.findViewById(R.id.terminal_toolbar);
        toolbar.setVisibility(View.VISIBLE);
        ExtraKeysView keys = toolbar.findViewById(R.id.terminal_toolbar_extra_keys);
        keys.reload(new ExtraKeysInfo(com.termux.shared.termux.settings.properties.TermuxPropertyConstants.DEFAULT_IVALUE_EXTRA_KEYS,
            "default", ExtraKeysConstants.CONTROL_CHARS_ALIASES), 52);
        measure(toolbar, context, 304, 52);
        View ctrl = keys.getChildAt(1);
        float x = (ctrl.getLeft() + ctrl.getRight()) / 2f;
        float y = toolbar.getHeight() / 2f;
        float step = 20 * context.getResources().getDisplayMetrics().density;
        long time = SystemClock.uptimeMillis();
        int[] actions = {MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP};
        for (int i = 0; i < actions.length; i++) {
            MotionEvent event = MotionEvent.obtain(time, time + i * 30, actions[i], x - i * step, y, 0);
            toolbar.dispatchTouchEvent(event);
            event.recycle();
        }
        assertTrue(toolbar.getScrollX() > 0);
        Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(1));
        assertEquals(Boolean.FALSE, keys.readSpecialButton(SpecialButton.CTRL, false));
        assertEquals(Boolean.FALSE, keys.readSpecialButton(SpecialButton.CURSOR, false));
        assertFalse(((MaterialButton) ctrl).isChecked());
        assertEquals(0, ((MaterialButton) ctrl).getStrokeWidth());
    }

    private static void assertNoTextInput(View view) {
        assertFalse(view instanceof EditText);
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) assertNoTextInput(group.getChildAt(i));
        }
    }

    @Test
    public void modifierLatchAndLockKeepMaterialBackgroundAndCheckedState() throws Exception {
        Context context = themedContext();
        ExtraKeysView keys = (ExtraKeysView) LayoutInflater.from(context)
            .inflate(R.layout.view_terminal_toolbar_extra_keys, null);
        keys.reload(new ExtraKeysInfo("[['CTRL', 'ESC']]", "default", ExtraKeysConstants.CONTROL_CHARS_ALIASES), 52);
        MaterialButton ctrl = (MaterialButton) keys.getChildAt(0);
        Drawable background = ctrl.getBackground();
        ctrl.performClick();
        assertTrue(ctrl.isChecked());
        assertEquals(Boolean.TRUE, keys.readSpecialButton(SpecialButton.CTRL, true));
        assertFalse(ctrl.isChecked());
        ctrl.performClick();
        keys.getSpecialButtons().get(SpecialButton.CTRL).setIsLocked(true);
        assertEquals(Boolean.TRUE, keys.readSpecialButton(SpecialButton.CTRL, true));
        assertTrue(ctrl.isChecked());
        assertTrue(ctrl.getStrokeWidth() > 0);
        ctrl.performClick();
        assertFalse(ctrl.isChecked());
        assertEquals(0, ctrl.getStrokeWidth());
        assertSame(background, ctrl.getBackground());
    }

    private static Context themedContext() {
        return new ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.Theme_TermuxActivity_DayNight_NoActionBar);
    }

    private static void measure(View view, Context context, int width, int height) {
        float density = context.getResources().getDisplayMetrics().density;
        int w = Math.round(width * density), h = Math.round(height * density);
        view.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(h, View.MeasureSpec.EXACTLY));
        view.layout(0, 0, w, h);
    }
}
