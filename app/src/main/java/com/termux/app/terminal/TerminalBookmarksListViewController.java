package com.termux.app.terminal;

import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.appcompat.widget.PopupMenu;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.termux.R;
import com.termux.app.TermuxActivity;
import com.termux.shared.termux.interact.TextInputDialogUtils;

import java.util.List;

/** The bookmark section shares session card layout, spacing and theme. */
public final class TerminalBookmarksListViewController extends ArrayAdapter<TerminalBookmark> {
    public interface OnBookmarkClickListener {
        void onBookmarkClick(TerminalBookmark bookmark);
    }

    private final TermuxActivity activity;
    private final TerminalBookmarkStore store;
    private final TerminalDrawerListView list;

    public TerminalBookmarksListViewController(TermuxActivity activity, TerminalBookmarkStore store,
                                                OnBookmarkClickListener listener) {
        super(activity, R.layout.item_terminal_sessions_list, store.getAll());
        this.activity = activity;
        this.store = store;
        list = activity.findViewById(R.id.terminal_bookmarks_list);
        list.setAdapter(this);
        list.setOnItemClickListener((parent, view, position, id) -> {
            TerminalBookmark bookmark = getItem(position);
            if (bookmark != null) listener.onBookmarkClick(bookmark);
        });
    }

    public void refresh() {
        List<TerminalBookmark> bookmarks = store.getAll();
        boolean sameBookmarks = bookmarks.size() == getCount();
        for (int i = 0; sameBookmarks && i < getCount(); i++) {
            sameBookmarks = getItem(i).id.equals(bookmarks.get(i).id);
        }
        // A preceding delete may have notified the list without laying out its rows yet.
        for (int i = 0; sameBookmarks && i < list.getChildCount(); i++) {
            int position = list.getFirstVisiblePosition() + i;
            sameBookmarks = position >= 0 && position < bookmarks.size()
                && bookmarks.get(position).id.equals(list.getChildAt(i).getTag());
        }
        setNotifyOnChange(false);
        clear();
        addAll(bookmarks);
        if (sameBookmarks) {
            // Updating labels in place preserves a menu button's current touch gesture.
            for (int i = 0; i < list.getChildCount(); i++) {
                getView(list.getFirstVisiblePosition() + i, list.getChildAt(i), list);
            }
        } else {
            notifyDataSetChanged();
        }
    }

    /** Show a newly saved bookmark in the drawer's shared scroll area. */
    public void refreshAndReveal(String bookmarkId) {
        refresh();
        for (int i = 0; i < getCount(); i++) {
            if (!getItem(i).id.equals(bookmarkId)) continue;
            list.revealItem(() -> {
                for (int position = 0; position < getCount(); position++) {
                    if (getItem(position).id.equals(bookmarkId)) return position;
                }
                return -1;
            });
            break;
        }
    }

    @NonNull
    @Override
    public View getView(int position, View convertView, @NonNull ViewGroup parent) {
        View row = convertView == null
            ? activity.getLayoutInflater().inflate(R.layout.item_terminal_sessions_list, parent, false)
            : convertView;
        TerminalBookmark bookmark = getItem(position);
        if (bookmark == null) return row;
        if (!bookmark.id.equals(row.getTag())) row.cancelPendingInputEvents();
        row.setTag(bookmark.id);
        // Handle the card surface directly; the overflow button handles its own touches.
        View card = row;
        row.findViewById(R.id.session_card_content).setOnClickListener(view -> {
            // A delete may arrive between ACTION_DOWN and the next list layout.
            if (position < getCount() && bookmark.id.equals(getItem(position).id)) {
                list.performItemClick(card, position, getItemId(position));
            }
        });
        row.setActivated(false);
        TextView badge = row.findViewById(R.id.session_number);
        badge.setText("★");
        badge.setContentDescription(activity.getString(R.string.terminal_bookmark_description));
        TextView name = row.findViewById(R.id.session_name);
        name.setText(bookmark.name);
        name.setVisibility(View.VISIBLE);
        TextView summary = row.findViewById(R.id.session_title);
        if (convertView == null) {
            summary.setSelected(true);
        }
        String environment = "proot".equals(bookmark.kind) ? bookmark.distro
            : "ssh".equals(bookmark.kind) ? (bookmark.sshArgs.isEmpty() ? "SSH"
                : bookmark.sshArgs.get(bookmark.sshArgs.size() - 1)) : "";
        String label = environment.isEmpty() ? bookmark.path : environment + " · " + bookmark.path;
        // Terminal output refreshes the drawer frequently; do not restart the marquee.
        if (!TextUtils.equals(summary.getText(), label)) summary.setText(label);
        summary.setVisibility(View.VISIBLE);
        View menu = row.findViewById(R.id.session_menu_button);
        menu.setEnabled(true);
        menu.setContentDescription(activity.getString(R.string.bookmark_menu_description, bookmark.name));
        menu.setOnClickListener(view -> showMenu(view, bookmark));
        return row;
    }

    private void showMenu(View anchor, TerminalBookmark bookmark) {
        PopupMenu menu = new PopupMenu(activity, anchor);
        menu.getMenu().add(0, 1, 0, R.string.action_rename_bookmark);
        menu.getMenu().add(0, 2, 1, R.string.action_delete_bookmark);
        menu.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == 1) {
                TextInputDialogUtils.textInput(activity, R.string.action_rename_bookmark,
                    bookmark.name, R.string.action_rename_session_confirm, text -> {
                        String name = text == null ? "" : text.trim();
                        if (!name.isEmpty()) {
                            store.rename(bookmark.id, name);
                            refresh();
                        }
                    }, -1, null, -1, null, null);
                return true;
            }
            if (item.getItemId() == 2) {
                new MaterialAlertDialogBuilder(activity)
                    .setMessage(activity.getString(R.string.message_confirm_delete_bookmark, bookmark.name))
                    .setPositiveButton(R.string.action_delete_bookmark, (dialog, which) -> {
                        store.delete(bookmark.id);
                        refresh();
                    })
                    .setNegativeButton(android.R.string.cancel, null)
                    .show();
                return true;
            }
            return false;
        });
        menu.show();
    }
}
