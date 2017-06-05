package com.jmedeisis.draglinearlayout;

/**
 * Created by carson on 6/5/17.
 */
public interface ScrollableView {
    int getScrollX();
    int getScrollY();
    int getHeight();
    int getWidth();
    void smoothScrollBy(int dx, int dy);
    boolean removeCallbacks(Runnable action);
    boolean post(Runnable action);
}
