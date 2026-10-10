package com.termux.app.terminal;

import android.content.Context;
import android.graphics.Rect;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewParent;
import android.widget.ListView;
import android.widget.ScrollView;

import java.util.function.IntSupplier;

/** Expands every drawer card so its enclosing ScrollView owns vertical scrolling. */
public final class TerminalDrawerListView extends ListView {
    private IntSupplier positionToReveal;
    private final Runnable revealAfterLayout = this::revealPendingItem;

    public TerminalDrawerListView(Context context) {
        this(context, null);
    }

    public TerminalDrawerListView(Context context, AttributeSet attrs) {
        this(context, attrs, android.R.attr.listViewStyle);
    }

    public TerminalDrawerListView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        setNestedScrollingEnabled(false);
        setOverScrollMode(OVER_SCROLL_NEVER);
        setVerticalScrollBarEnabled(false);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        // UNSPECIFIED makes ListView measure only its first row. AT_MOST measures all
        // rows and dividers without changing the card dimensions supplied by the adapter.
        super.onMeasure(widthMeasureSpec,
            MeasureSpec.makeMeasureSpec(Integer.MAX_VALUE >> 2, MeasureSpec.AT_MOST));
    }

    /** Reveal a card once the adapter's pending layout has completed. */
    public void revealItem(int position) {
        revealItem(() -> position);
    }

    /** Resolve the position after layout, since an item can move while a refresh is pending. */
    public void revealItem(IntSupplier position) {
        positionToReveal = position;
        scheduleReveal();
    }

    public void cancelPendingReveal() {
        positionToReveal = null;
        removeCallbacks(revealAfterLayout);
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        scheduleReveal();
    }

    @Override
    protected void onDetachedFromWindow() {
        cancelPendingReveal();
        super.onDetachedFromWindow();
    }

    private void scheduleReveal() {
        removeCallbacks(revealAfterLayout);
        if (positionToReveal != null) post(revealAfterLayout);
    }

    private void revealPendingItem() {
        if (positionToReveal == null || getVisibility() != VISIBLE || isLayoutRequested()
            || getHeight() == 0) return;
        int position = positionToReveal.getAsInt();
        if (position < 0 || getAdapter() == null || position >= getAdapter().getCount()) {
            cancelPendingReveal();
            return;
        }
        View row = getChildAt(position - getFirstVisiblePosition());
        if (row == null || row.isLayoutRequested() || row.getHeight() == 0) return;

        ScrollView scroll = null;
        for (ViewParent parent = getParent(); parent != null; parent = parent.getParent()) {
            if (parent instanceof ScrollView) {
                scroll = (ScrollView) parent;
                break;
            }
        }
        if (scroll == null) {
            cancelPendingReveal();
            return;
        }
        if (scroll.getHeight() == 0) return;

        Rect bounds = new Rect();
        row.getDrawingRect(bounds);
        scroll.offsetDescendantRectToMyCoords(row, bounds);
        int scrollY = scroll.getScrollY();
        // The scroll padding reserves room above the fixed FAB. Account for it even
        // with clipToPadding=false, when cards can otherwise draw underneath the FAB.
        if (bounds.top < scrollY + scroll.getPaddingTop()) {
            scrollY = bounds.top - scroll.getPaddingTop();
        } else if (bounds.bottom > scrollY + scroll.getHeight() - scroll.getPaddingBottom()) {
            scrollY = bounds.bottom - scroll.getHeight() + scroll.getPaddingBottom();
        }
        cancelPendingReveal();
        scroll.scrollTo(scroll.getScrollX(), scrollY);
    }
}
