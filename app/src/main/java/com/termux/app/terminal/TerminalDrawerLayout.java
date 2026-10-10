package com.termux.app.terminal;

import android.content.Context;
import android.util.AttributeSet;
import android.view.Gravity;
import android.view.View;

import androidx.drawerlayout.widget.DrawerLayout;

/** A terminal navigation drawer that covers the available content width. */
public final class TerminalDrawerLayout extends DrawerLayout {
    public TerminalDrawerLayout(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int width = MeasureSpec.getSize(widthMeasureSpec);
        for (int i = 0; i < getChildCount(); i++) {
            View child = getChildAt(i);
            LayoutParams params = (LayoutParams) child.getLayoutParams();
            if (params.gravity != Gravity.NO_GRAVITY) {
                // An explicit width avoids DrawerLayout's minimum margin for wrap/match_parent.
                params.width = Math.max(0, width - params.leftMargin - params.rightMargin);
            }
        }
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
    }
}
