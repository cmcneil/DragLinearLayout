package com.jmedeisis.draglinearlayout;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.content.Context;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.support.annotation.NonNull;
import android.support.v4.content.ContextCompat;
import android.support.v4.view.MotionEventCompat;
import android.util.AttributeSet;
import android.util.Log;
import android.util.SparseArray;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewTreeObserver;
import android.view.ViewTreeObserver.OnPreDrawListener;
import android.widget.LinearLayout;

/**
 * A LinearLayout that supports children Views that can be dragged and swapped around.
 * See {@link #addDragView(android.view.View, android.view.View)},
 * {@link #addDragView(android.view.View, android.view.View, int)},
 * {@link #setViewDraggable(android.view.View, android.view.View)}, and
 * {@link #removeDragView(android.view.View)}.
 * <p/>
 * Currently, no error-checking is done on standard {@link #addView(android.view.View)} and
 * {@link #removeView(android.view.View)} calls, so avoid using these with children previously
 * declared as draggable to prevent memory leaks and/or subtle bugs. Pull requests welcome!
 */
public class DragLinearLayout extends LinearLayout {
    private static final String LOG_TAG = DragLinearLayout.class.getSimpleName();
    private static final long NOMINAL_SWITCH_DURATION = 150;
    private static final long MIN_SWITCH_DURATION = NOMINAL_SWITCH_DURATION;
    private static final long MAX_SWITCH_DURATION = NOMINAL_SWITCH_DURATION * 2;
    private static final float NOMINAL_DISTANCE = 20;
    private static final int DEFAULT_ORTHOGONAL_DRAG_OFFSET = 0;
    private final float nominalDistanceScaled;

    private int orthogonalDragOffsetScaled;

    /**
     * Use with {@link com.jmedeisis.draglinearlayout.DragLinearLayout#setOnViewSwapListener(com.jmedeisis.draglinearlayout.DragLinearLayout.OnViewSwapListener)}
     * to listen for draggable view swaps.
     */
    public interface OnViewSwapListener {
        /**
         * Invoked right before the two items are swapped due to a drag event.
         * After the swap, the firstView will be in the secondPosition, and vice versa.
         * <p/>
         * No guarantee is made as to which of the two has a lesser/greater position.
         */
        void onSwap(View firstView, int firstPosition, View secondView, int secondPosition);
    }

    private OnViewSwapListener swapListener;

    /**
     * Mapping from child index to drag-related info container.
     * Presence of mapping implies the child can be dragged, and is considered for swaps with the
     * currently dragged item.
     */
    private final SparseArray<DraggableChild> draggableChildren;

    private class DraggableChild {
        /**
         * If non-null, a reference to an on-going position animation.
         */
        private ValueAnimator swapAnimation;

        public void endExistingAnimation() {
            if (null != swapAnimation) swapAnimation.end();
        }

        public void cancelExistingAnimation() {
            if (null != swapAnimation) swapAnimation.cancel();
        }
    }

    /**
     * Holds state information about the currently dragged item.
     * <p/>
     * Rough lifecycle:
     * <li>#startDetectingOnPossibleDrag - #detecting == true</li>
     * <li>     if drag is recognised, #onDragStart - #dragging == true</li>
     * <li>     if drag ends, #onDragStop - #dragging == false, #settling == true</li>
     * <li>if gesture ends without drag, or settling finishes, #stopDetecting - #detecting == false</li>
     */
    private class DragItem {
        private View view;
        private int startVisibility;
        private BitmapDrawable viewDrawable;
        private int position;
        private int startHead;
        private int thickness;
        private int totalDragOffset;
        private int targetHeadOffset;
        private int orthogonalOffset;
        private ValueAnimator orthogonalDragStartAnimation;
        private ValueAnimator orthogonalDragSettleAnimation;
        private ValueAnimator settleAnimation;
        private int mOrientation;

        private boolean detecting;
        private boolean dragging;

        public DragItem(int orientation) {
            this.mOrientation = orientation;
            stopDetecting();
        }

        public void startDetectingOnPossibleDrag(final View view, final int position) {
            this.view = view;
            this.startVisibility = view.getVisibility();
            this.viewDrawable = getDragDrawable(view);
            this.position = position;

            if (mOrientation == LinearLayout.VERTICAL) {
                this.startHead = view.getTop();
                this.thickness = view.getHeight();
            } else if (mOrientation == LinearLayout.HORIZONTAL) {
                this.startHead = view.getLeft();
                this.thickness = view.getWidth();
            }
            this.totalDragOffset = 0;
            this.targetHeadOffset = 0;

            this.settleAnimation = null;

            this.detecting = true;
        }

        public void onDragStart() {
            view.setVisibility(View.INVISIBLE);
            this.dragging = true;

            orthogonalDragStartAnimation = ValueAnimator.ofFloat(0, orthogonalDragOffsetScaled)
                    .setDuration(NOMINAL_SWITCH_DURATION);
            orthogonalDragStartAnimation.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
                @Override
                public void onAnimationUpdate(ValueAnimator animation) {
                    draggedItem.setOrthogonalOffset(((Float) animation.getAnimatedValue()).intValue());
                    invalidate();
                }
            });

            orthogonalDragStartAnimation.start();
        }

        public void setTotalOffset(int offset) {
            totalDragOffset = offset;
            updateTargetHead();
        }

        public void updateTargetHead() {
            switch (mOrientation) {
                case LinearLayout.VERTICAL:
                    targetHeadOffset = startHead - view.getTop() + totalDragOffset;
                    break;
                case LinearLayout.HORIZONTAL:
                    targetHeadOffset = startHead - view.getLeft() + totalDragOffset;
            }
        }

        public void onDragStop() {
            this.dragging = false;
        }

        public boolean settling() {
            return null != settleAnimation;
        }

        public void stopDetecting() {
            this.detecting = false;
            if (null != view) view.setVisibility(startVisibility);
            view = null;
            startVisibility = -1;
            viewDrawable = null;
            position = -1;
            startHead = -1;
            thickness = -1;
            totalDragOffset = 0;
            targetHeadOffset = 0;
            if (null != settleAnimation) settleAnimation.end();
            settleAnimation = null;
        }

        public void setOrthogonalOffset(int orthogonalOffset) {
            this.orthogonalOffset = orthogonalOffset;
        }
    }

    /**
     * The currently dragged item, if {@link com.jmedeisis.draglinearlayout.DragLinearLayout.DragItem#detecting}.
     */
    private final DragItem draggedItem;
    private final int slop;

    private static final int INVALID_POINTER_ID = -1;
    private int downPos = -1;
    private int activePointerId = INVALID_POINTER_ID;

    /**
     * The shadow to be drawn above the {@link #draggedItem}.
     */
    // TODO(cmcneil): Generalize this.
    private final Drawable dragTopShadowDrawable;
    /**
     * The shadow to be drawn below the {@link #draggedItem}.
     */
    // TODO(cmcneil): Generalize this
    private final Drawable dragBottomShadowDrawable;
    private final int dragShadowHeight;

    /**
     * See {@link #setContainerScrollView(View)}.
     */
    private View containerScrollView;
    private int scrollSensitiveAreaThickness;
    private static final int DEFAULT_SCROLL_SENSITIVE_AREA_HEIGHT_DP = 48;
    private static final int DEFAULT_SCROLL_SENSITIVE_AREA_WIDTH_DP = 48;
    private static final int MAX_DRAG_SCROLL_SPEED = 16;

    public DragLinearLayout(Context context) {
        this(context, null);
    }

    public DragLinearLayout(Context context, AttributeSet attrs) {
        super(context, attrs);

        draggableChildren = new SparseArray<>();

        draggedItem = new DragItem(getOrientation());
        ViewConfiguration vc = ViewConfiguration.get(context);
        slop = vc.getScaledTouchSlop();

        final Resources resources = getResources();
        dragTopShadowDrawable = ContextCompat.getDrawable(context, R.drawable.ab_solid_shadow_holo_flipped);
        dragBottomShadowDrawable = ContextCompat.getDrawable(context, R.drawable.ab_solid_shadow_holo);
        dragShadowHeight = resources.getDimensionPixelSize(R.dimen.downwards_drop_shadow_height);

        TypedArray a = context.getTheme().obtainStyledAttributes(attrs, R.styleable.DragLinearLayout, 0, 0);
        try {
            switch (getOrientation()) {
                case LinearLayout.VERTICAL:
                    scrollSensitiveAreaThickness = a.getDimensionPixelSize(R.styleable.DragLinearLayout_scrollSensitiveHeight,
                            (int) (DEFAULT_SCROLL_SENSITIVE_AREA_HEIGHT_DP * resources.getDisplayMetrics().density + 0.5f));
                    break;
                case LinearLayout.HORIZONTAL:
                    scrollSensitiveAreaThickness = a.getDimensionPixelSize(R.styleable.DragLinearLayout_scrollSensistiveWidth,
                            (int) (DEFAULT_SCROLL_SENSITIVE_AREA_WIDTH_DP * resources.getDisplayMetrics().density + 0.5f));
                    break;
            }
        } finally {
            a.recycle();
        }

        orthogonalDragOffsetScaled = (int) (DEFAULT_ORTHOGONAL_DRAG_OFFSET * resources.getDisplayMetrics().density + 0.5f);
        nominalDistanceScaled = (int) (NOMINAL_DISTANCE * resources.getDisplayMetrics().density + 0.5f);
    }

    /**
     * Calls {@link #addView(android.view.View)} followed by {@link #setViewDraggable(android.view.View, android.view.View)}.
     */
    public void addDragView(View child, View dragHandle) {
        addView(child);
        setViewDraggable(child, dragHandle);
    }

    /**
     * Calls {@link #addView(android.view.View, int)} followed by
     * {@link #setViewDraggable(android.view.View, android.view.View)} and correctly updates the
     * drag-ability state of all existing views.
     */
    public void addDragView(View child, View dragHandle, int index) {
        addView(child, index);

        // update drag-able children mappings
        final int numMappings = draggableChildren.size();
        for (int i = numMappings - 1; i >= 0; i--) {
            final int key = draggableChildren.keyAt(i);
            if (key >= index) {
                draggableChildren.put(key + 1, draggableChildren.get(key));
            }
        }

        setViewDraggable(child, dragHandle);
    }

    /**
     * Makes the child a candidate for dragging. Must be an existing child of this layout.
     */
    public void setViewDraggable(View child, View dragHandle) {
        if (this == child.getParent()) {
            dragHandle.setOnTouchListener(new DragHandleOnTouchListener(child));
            draggableChildren.put(indexOfChild(child), new DraggableChild());
        } else {
            Log.e(LOG_TAG, child + " is not a child, cannot make draggable.");
        }
    }

    /**
     * Calls {@link #removeView(android.view.View)} and correctly updates the drag-ability state of
     * all remaining views.
     */
    @SuppressWarnings("UnusedDeclaration")
    public void removeDragView(View child) {
        if (this == child.getParent()) {
            final int index = indexOfChild(child);
            removeView(child);

            // update drag-able children mappings
            final int mappings = draggableChildren.size();
            for (int i = 0; i < mappings; i++) {
                final int key = draggableChildren.keyAt(i);
                if (key >= index) {
                    DraggableChild next = draggableChildren.get(key + 1);
                    if (null == next) {
                        draggableChildren.delete(key);
                    } else {
                        draggableChildren.put(key, next);
                    }
                }
            }
        }
    }

    /**
     * If this layout is within a {@link android.widget.ScrollView}, register it here so that it
     * can be scrolled during item drags.
     */
    public void setContainerScrollView(View scrollView) {
        this.containerScrollView = scrollView;
    }

    /**
     * Sets the height from upper / lower edge at which a container {@link android.widget.ScrollView},
     * if one is registered via {@link #setContainerScrollView(View)},
     * is scrolled.
     */
    @SuppressWarnings("UnusedDeclaration")
    public void setScrollSensitiveHeight(int height) {
        this.scrollSensitiveAreaThickness = height;
    }

    @SuppressWarnings("UnusedDeclaration")
    public int getScrollSensitiveHeight() {
        return scrollSensitiveAreaThickness;
    }

    /**
     * Sets the width from right / left edge at which a container {@link android.widget.ScrollView},
     * if one is registered via {@link #setContainerScrollView(View)},
     * is scrolled.
     */
    @SuppressWarnings("UnusedDeclaration")
    public void setScrollSensitiveWidth(int width) {
        this.scrollSensitiveAreaThickness = width;
    }

    @SuppressWarnings("UnusedDeclaration")
    public int getScrollSensitiveWidth() {
        return scrollSensitiveAreaThickness;
    }

    /**
     * See {@link com.jmedeisis.draglinearlayout.DragLinearLayout.OnViewSwapListener}.
     */
    public void setOnViewSwapListener(OnViewSwapListener swapListener) {
        this.swapListener = swapListener;
    }

    /**
     * Sets the orthogonal offset that a view will be moved while being dragged
     */
    @SuppressWarnings("UnusedDeclaration")
    public void setOrthogonalDragOffset(int orthogonalDragOffset) {
        this.orthogonalDragOffsetScaled = (int) (orthogonalDragOffset * getResources().getDisplayMetrics().density + 0.5f);
    }

    /**
     * A linear relationship b/w distance and duration, bounded.
     */
    private long getTranslateAnimationDuration(float distance) {
        return Math.min(MAX_SWITCH_DURATION, Math.max(MIN_SWITCH_DURATION,
                (long) (NOMINAL_SWITCH_DURATION * Math.abs(distance) / nominalDistanceScaled)));
    }

    /**
     * Initiates a new {@link #draggedItem} unless the current one is still
     * {@link com.jmedeisis.draglinearlayout.DragLinearLayout.DragItem#detecting}.
     */
    private void startDetectingDrag(View child) {
        if (draggedItem.detecting)
            return; // existing drag in process, only one at a time is allowed

        final int position = indexOfChild(child);

        // complete any existing animations, both for the newly selected child and the previous dragged one
        draggableChildren.get(position).endExistingAnimation();

        draggedItem.startDetectingOnPossibleDrag(child, position);
    }

    private void startDrag() {
        draggedItem.onDragStart();
        requestDisallowInterceptTouchEvent(true);
    }

    /**
     * Animates the dragged item to its final resting position.
     */
    private void onDragStop() {
        // settle in drag direction
        draggedItem.settleAnimation = ValueAnimator.ofFloat(draggedItem.totalDragOffset,
                draggedItem.totalDragOffset - draggedItem.targetHeadOffset)
                .setDuration(getTranslateAnimationDuration(draggedItem.targetHeadOffset));
        draggedItem.settleAnimation.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                if (!draggedItem.detecting) return; // already stopped

                draggedItem.setTotalOffset(((Float) animation.getAnimatedValue()).intValue());

                final int shadowAlpha = (int) ((1 - animation.getAnimatedFraction()) * 255);
                if (null != dragTopShadowDrawable) dragTopShadowDrawable.setAlpha(shadowAlpha);
                dragBottomShadowDrawable.setAlpha(shadowAlpha);
                invalidate();
            }
        });
        draggedItem.settleAnimation.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationStart(Animator animation) {
                draggedItem.onDragStop();
            }

            @Override
            public void onAnimationEnd(Animator animation) {
                if (!draggedItem.detecting) {
                    return; // already stopped
                }

                draggedItem.settleAnimation = null;
                draggedItem.stopDetecting();

                if (null != dragTopShadowDrawable) dragTopShadowDrawable.setAlpha(255);
                dragBottomShadowDrawable.setAlpha(255);
            }
        });
        draggedItem.settleAnimation.start();

        // settle orthogonal, using the same duration as drag direction settle duration
        draggedItem.orthogonalDragSettleAnimation = ValueAnimator.ofFloat(draggedItem.orthogonalOffset, 0).
                setDuration(getTranslateAnimationDuration(draggedItem.targetHeadOffset));
        draggedItem.orthogonalDragSettleAnimation.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                draggedItem.setOrthogonalOffset(((Float) animation.getAnimatedValue()).intValue());
                invalidate();
            }
        });
        draggedItem.orthogonalDragSettleAnimation.start();
    }

    /**
     * Updates the dragged item with the given total offset from its starting position.
     * Evaluates and executes draggable view swaps.
     */
    private void onDrag(final int offset) {
        draggedItem.setTotalOffset(offset);
        invalidate();

        int currentHead = draggedItem.startHead + draggedItem.totalDragOffset;

        handleContainerScroll(currentHead);

        int belowPosition = nextDraggablePosition(draggedItem.position);
        int abovePosition = previousDraggablePosition(draggedItem.position);

        View belowView = getChildAt(belowPosition);
        View aboveView = getChildAt(abovePosition);

        // TODO(cmcneil): Figure out more reasonable defaults.
        int belowViewHead = 0;
        int belowViewThickness = 0;
        int aboveViewHead = 0;
        int aboveViewThickness = 0;
        switch (getOrientation()) {
            case LinearLayout.VERTICAL:
                if (belowView != null) {
                    belowViewHead = belowView.getTop();
                    belowViewThickness = belowView.getHeight();
                }
                if (aboveView != null) {
                    aboveViewHead = aboveView.getTop();
                    aboveViewThickness = aboveView.getHeight();
                }
                break;
            case LinearLayout.HORIZONTAL:
                if (belowView != null) {
                    belowViewHead = belowView.getLeft();
                    belowViewThickness = belowView.getWidth();
                }
                if (aboveView != null) {
                    aboveViewHead = aboveView.getLeft();
                    aboveViewThickness = aboveView.getWidth();
                }
                break;
            default:
                // TODO(cmcneil): Flip out.
        }

        final boolean isBelow = (belowView != null) &&
                (currentHead + draggedItem.thickness > belowViewHead + belowViewThickness / 2);
        final boolean isAbove = (aboveView != null) &&
                (currentHead < aboveViewHead + aboveViewThickness / 2);

        if (isBelow || isAbove) {
            final View switchView = isBelow ? belowView : aboveView;

            // swap elements
            final int originalPosition = draggedItem.position;
            final int switchPosition = isBelow ? belowPosition : abovePosition;

            draggableChildren.get(switchPosition).cancelExistingAnimation();
            // TODO(cmcneil): Determine more reasonable default. Pos means something different.
            //  use pix or something instead.
            float startPos = 0;
            switch (getOrientation()) {
                case LinearLayout.VERTICAL:
                    startPos = switchView.getY();
                    break;
                case LinearLayout.HORIZONTAL:
                    startPos = switchView.getX();
                    break;
            }
            final float switchViewStartPos = startPos;

            if (null != swapListener) {
                swapListener.onSwap(draggedItem.view, draggedItem.position, switchView, switchPosition);
            }

            if (isBelow) {
                removeViewAt(originalPosition);
                removeViewAt(switchPosition - 1);

                addView(belowView, originalPosition);
                addView(draggedItem.view, switchPosition);
            } else {
                removeViewAt(switchPosition);
                removeViewAt(originalPosition - 1);

                addView(draggedItem.view, switchPosition);
                addView(aboveView, originalPosition);
            }
            draggedItem.position = switchPosition;

            final ViewTreeObserver switchViewObserver = switchView.getViewTreeObserver();
            switchViewObserver.addOnPreDrawListener(new OnPreDrawListener() {
                @Override
                public boolean onPreDraw() {
                    switchViewObserver.removeOnPreDrawListener(this);

                    float currentPos = switchViewStartPos;
                    String dimension = "y";
                    switch (getOrientation()) {
                        case LinearLayout.VERTICAL:
                            currentPos = switchView.getTop();
                            dimension = "y";
                            break;
                        case LinearLayout.HORIZONTAL:
                            currentPos = switchView.getLeft();
                            dimension = "x";
                            break;
                    }
                    final ObjectAnimator switchAnimator = ObjectAnimator.ofFloat(switchView,
                            dimension, switchViewStartPos, currentPos)
                            .setDuration(getTranslateAnimationDuration(currentPos - switchViewStartPos));
                    switchAnimator.addListener(new AnimatorListenerAdapter() {
                        @Override
                        public void onAnimationStart(Animator animation) {
                            draggableChildren.get(originalPosition).swapAnimation = switchAnimator;
                        }

                        @Override
                        public void onAnimationEnd(Animator animation) {
                            draggableChildren.get(originalPosition).swapAnimation = null;
                        }
                    });
                    switchAnimator.start();

                    return true;
                }
            });

            final ViewTreeObserver observer = draggedItem.view.getViewTreeObserver();
            observer.addOnPreDrawListener(new OnPreDrawListener() {
                @Override
                public boolean onPreDraw() {
                    observer.removeOnPreDrawListener(this);
                    draggedItem.updateTargetHead();

                    // TODO test if still necessary..
                    // because draggedItem#view#getTop() is only up-to-date NOW
                    // (and not right after the #addView() swaps above)
                    // we may need to update an ongoing settle animation
                    if (draggedItem.settling()) {
                        Log.d(LOG_TAG, "Updating settle animation");
                        draggedItem.settleAnimation.removeAllListeners();
                        draggedItem.settleAnimation.cancel();
                        onDragStop();
                    }
                    return true;
                }
            });
        }
    }

    private int previousDraggablePosition(int position) {
        int startIndex = draggableChildren.indexOfKey(position);
        if (startIndex < 1 || startIndex > draggableChildren.size()) return -1;
        return draggableChildren.keyAt(startIndex - 1);
    }

    private int nextDraggablePosition(int position) {
        int startIndex = draggableChildren.indexOfKey(position);
        if (startIndex < -1 || startIndex > draggableChildren.size() - 2) return -1;
        return draggableChildren.keyAt(startIndex + 1);
    }

    private Runnable dragUpdater;

    // TODO(cmcneil): Generalize callers.
    private void handleContainerScroll(final int currentHead) {
        if (null != containerScrollView) {
            final int startScrollX = containerScrollView.getScrollX();
            final int startScrollY = containerScrollView.getScrollY();
            final int absHead;
            final int thickness;
            switch (getOrientation()) {
                case LinearLayout.VERTICAL:
                    absHead = getTop() - startScrollY + currentHead;
                    thickness = containerScrollView.getHeight();
                    break;
                case LinearLayout.HORIZONTAL:
                    absHead = getLeft() - startScrollX + currentHead;
                    thickness = containerScrollView.getWidth();
                    break;
                default:
                    // TODO(cmcneil): Throw an error or something
                    absHead = 0;
                    thickness = 0;
            }

            final int delta;

            if (absHead < scrollSensitiveAreaThickness) {
                delta = (int) (-MAX_DRAG_SCROLL_SPEED * smootherStep(scrollSensitiveAreaThickness, 0, absHead));
            } else if (absHead > thickness - scrollSensitiveAreaThickness) {
                delta = (int) (MAX_DRAG_SCROLL_SPEED * smootherStep(thickness - scrollSensitiveAreaThickness, thickness, absHead));
            } else {
                delta = 0;
            }

            containerScrollView.removeCallbacks(dragUpdater);
            switch (getOrientation()) {
                case LinearLayout.VERTICAL:
                    containerScrollView.scrollBy(0, delta);
                    dragUpdater = new Runnable() {
                        @Override
                        public void run() {
                            if (draggedItem.dragging && startScrollY != containerScrollView.getScrollY()) {
                                onDrag(draggedItem.totalDragOffset + delta);
                            }
                        }
                    };
                    break;
                case LinearLayout.HORIZONTAL:
                    containerScrollView.scrollBy(delta, 0);
                    dragUpdater = new Runnable() {
                        @Override
                        public void run() {
                            if (draggedItem.dragging && startScrollX != containerScrollView.getScrollX()) {
                                onDrag(draggedItem.totalDragOffset + delta);
                            }
                        }
                    };
                    break;
            }
            containerScrollView.post(dragUpdater);
        }
    }

    /**
     * By Ken Perlin. See <a href="http://en.wikipedia.org/wiki/Smoothstep">Smoothstep - Wikipedia</a>.
     */
    private static float smootherStep(float edge1, float edge2, float val) {
        val = Math.max(0, Math.min((val - edge1) / (edge2 - edge1), 1));
        return val * val * val * (val * (val * 6 - 15) + 10);
    }

    @Override
    protected void dispatchDraw(@NonNull Canvas canvas) {
        super.dispatchDraw(canvas);

        if (draggedItem.detecting && (draggedItem.dragging || draggedItem.settling())) {
            canvas.save();
            switch (getOrientation()) {
                case LinearLayout.VERTICAL:
                    canvas.translate(draggedItem.orthogonalOffset, draggedItem.totalDragOffset);
                    break;
                case LinearLayout.HORIZONTAL:
                    canvas.translate(draggedItem.totalDragOffset, draggedItem.orthogonalOffset);
                    break;
            }
            draggedItem.viewDrawable.draw(canvas);

            final int left = draggedItem.viewDrawable.getBounds().left;
            final int right = draggedItem.viewDrawable.getBounds().right;
            final int top = draggedItem.viewDrawable.getBounds().top;
            final int bottom = draggedItem.viewDrawable.getBounds().bottom;

            dragBottomShadowDrawable.setBounds(left, bottom, right, bottom + dragShadowHeight);
            dragBottomShadowDrawable.draw(canvas);

            if (null != dragTopShadowDrawable) {
                dragTopShadowDrawable.setBounds(left, top - dragShadowHeight, right, top);
                dragTopShadowDrawable.draw(canvas);
            }

            canvas.restore();
        }
    }

    /*
     * Note regarding touch handling:
     * In general, we have three cases -
     * 1) User taps outside any children.
     *      #onInterceptTouchEvent receives DOWN
     *      #onTouchEvent receives DOWN
     *          draggedItem.detecting == false, we return false and no further events are received
     * 2) User taps on non-interactive drag handle / child, e.g. TextView or ImageView.
     *      #onInterceptTouchEvent receives DOWN
     *      DragHandleOnTouchListener (attached to each draggable child) #onTouch receives DOWN
     *      #startDetectingDrag is called, draggedItem is now detecting
     *      view does not handle touch, so our #onTouchEvent receives DOWN
     *          draggedItem.detecting == true, we #startDrag() and proceed to handle the drag
     * 3) User taps on interactive drag handle / child, e.g. Button.
     *      #onInterceptTouchEvent receives DOWN
     *      DragHandleOnTouchListener (attached to each draggable child) #onTouch receives DOWN
     *      #startDetectingDrag is called, draggedItem is now detecting
     *      view handles touch, so our #onTouchEvent is not called yet
     *      #onInterceptTouchEvent receives ACTION_MOVE
     *      if dy > touch slop, we assume user wants to drag and intercept the event
     *      #onTouchEvent receives further ACTION_MOVE events, proceed to handle the drag
     *
     * For cases 2) and 3), lifting the active pointer at any point in the sequence of events
     * triggers #onTouchEnd and the draggedItem, if detecting, is #stopDetecting.
     */

    @Override
    public boolean onInterceptTouchEvent(MotionEvent event) {
        switch (MotionEventCompat.getActionMasked(event)) {
            case MotionEvent.ACTION_DOWN: {
                if (draggedItem.detecting) return false; // an existing item is (likely) settling
                switch (getOrientation()) {
                    case LinearLayout.VERTICAL:
                        downPos = (int) MotionEventCompat.getY(event, 0);
                        break;
                    case LinearLayout.HORIZONTAL:
                        downPos = (int) MotionEventCompat.getX(event, 0);
                }
                activePointerId = MotionEventCompat.getPointerId(event, 0);
                break;
            }
            case MotionEvent.ACTION_MOVE: {
                if (!draggedItem.detecting) return false;
                if (INVALID_POINTER_ID == activePointerId) break;
                final int pointerIndex = event.findPointerIndex(activePointerId);
                boolean move = false;
                switch (getOrientation()) {
                    case LinearLayout.VERTICAL:
                        final float y = MotionEventCompat.getY(event, pointerIndex);
                        final float dy = y - downPos;
                        move = Math.abs(dy) > slop;
                        break;
                    case LinearLayout.HORIZONTAL:
                        final float x = MotionEventCompat.getX(event, pointerIndex);
                        final float dx = x - downPos;
                        move = Math.abs(dx) > slop;
                }

                if (move) {
                    startDrag();
                    return true;
                }
                return false;
            }
            case MotionEvent.ACTION_POINTER_UP: {
                final int pointerIndex = MotionEventCompat.getActionIndex(event);
                final int pointerId = MotionEventCompat.getPointerId(event, pointerIndex);

                if (pointerId != activePointerId)
                    break; // if active pointer, fall through and cancel!
            }
            case MotionEvent.ACTION_CANCEL:
            case MotionEvent.ACTION_UP: {
                onTouchEnd();

                if (draggedItem.detecting) draggedItem.stopDetecting();
                break;
            }
        }

        return false;
    }

    @Override
    public boolean onTouchEvent(@NonNull MotionEvent event) {
        switch (MotionEventCompat.getActionMasked(event)) {
            case MotionEvent.ACTION_DOWN: {
                if (!draggedItem.detecting || draggedItem.settling()) return false;
                startDrag();
                return true;
            }
            case MotionEvent.ACTION_MOVE: {
                if (!draggedItem.dragging) break;
                if (INVALID_POINTER_ID == activePointerId) break;

                int pointerIndex = event.findPointerIndex(activePointerId);
                int lastEventPos = downPos;
                switch (getOrientation()) {
                    case LinearLayout.VERTICAL:
                        lastEventPos = (int) MotionEventCompat.getY(event, pointerIndex);
                        break;
                    case LinearLayout.HORIZONTAL:
                        lastEventPos = (int) MotionEventCompat.getX(event, pointerIndex);
                        break;
                }
                final int delta = lastEventPos - downPos;

                onDrag(delta);
                return true;
            }
            case MotionEvent.ACTION_POINTER_UP: {
                final int pointerIndex = MotionEventCompat.getActionIndex(event);
                final int pointerId = MotionEventCompat.getPointerId(event, pointerIndex);

                if (pointerId != activePointerId)
                    break; // if active pointer, fall through and cancel!
            }
            case MotionEvent.ACTION_CANCEL:
            case MotionEvent.ACTION_UP: {
                onTouchEnd();

                if (draggedItem.dragging) {
                    onDragStop();
                } else if (draggedItem.detecting) {
                    draggedItem.stopDetecting();
                }
                return true;
            }
        }
        return false;
    }

    private void onTouchEnd() {
        downPos = -1;
        activePointerId = INVALID_POINTER_ID;
    }

    private class DragHandleOnTouchListener implements OnTouchListener {
        private final View view;

        public DragHandleOnTouchListener(final View view) {
            this.view = view;
        }

        @Override
        public boolean onTouch(View v, MotionEvent event) {
            if (MotionEvent.ACTION_DOWN == MotionEventCompat.getActionMasked(event)) {
                startDetectingDrag(view);
            }
            return false;
        }
    }

    private BitmapDrawable getDragDrawable(View view) {
        int top = view.getTop();
        int left = view.getLeft();

        Bitmap bitmap = getBitmapFromView(view);

        BitmapDrawable drawable = new BitmapDrawable(getResources(), bitmap);

        drawable.setBounds(new Rect(left, top, left + view.getWidth(), top + view.getHeight()));

        return drawable;
    }

    /**
     * @return a bitmap showing a screenshot of the view passed in.
     */
    private static Bitmap getBitmapFromView(View view) {
        Bitmap bitmap = Bitmap.createBitmap(view.getWidth(), view.getHeight(), Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        view.draw(canvas);
        return bitmap;
    }
}