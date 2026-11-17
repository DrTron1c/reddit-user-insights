package app.userinsights.extension;

import android.app.Activity;
import android.content.Context;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;

/**
 * Draws the subreddit pills and the hidden-profile button on top of the Reddit activity.
 * The views live in a transparent layer added to the activity's window, so touches outside
 * the pills go straight through to Reddit.
 */
final class OverlayManager {
    private static final String LAYER_TAG = "user_insights_layer";
    private static final String HIDDEN_BUTTON_TAG = "user_insights_hidden_button";
    private static final int MAX_ROWS = 30;

    /** Border/text colours of the 1st, 2nd and 3rd pill. */
    static final int[] PILL_COLORS = {0xFFE8834A, 0xFFA77BF3, 0xFFE0709A};
    private static final int PILL_FILL = 0xE60E0F12;

    private OverlayManager() {
    }

    private static FrameLayout layer(Activity activity, boolean create) {
        View decor = activity.getWindow() == null ? null : activity.getWindow().getDecorView();
        if (!(decor instanceof ViewGroup)) {
            return null;
        }
        ViewGroup decorGroup = (ViewGroup) decor;
        View existing = decorGroup.findViewWithTag(LAYER_TAG);
        if (existing instanceof FrameLayout) {
            return (FrameLayout) existing;
        }
        if (!create) {
            return null;
        }
        FrameLayout layer = new FrameLayout(activity);
        layer.setTag(LAYER_TAG);
        layer.setClickable(false);
        layer.setFocusable(false);
        layer.setClipChildren(false);
        // Keep the pills out of the accessibility tree, so they are not scanned or read out.
        layer.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        layer.setElevation(Ui.dp(activity, 24));
        layer.setTranslationZ(Ui.dp(activity, 24));
        decorGroup.addView(layer, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        return layer;
    }

    /** Hides the pills while the screen scrolls, so they never point at the wrong comment. */
    static void hideTemporarily(Activity activity) {
        FrameLayout layer = layer(activity, false);
        if (layer != null) {
            layer.animate().cancel();
            layer.setAlpha(0f);
        }
    }

    static void clear(Activity activity) {
        FrameLayout layer = layer(activity, false);
        if (layer == null) {
            return;
        }
        for (int i = 0; i < layer.getChildCount(); i++) {
            layer.getChildAt(i).setVisibility(View.GONE);
        }
    }

    static void remove(Activity activity) {
        FrameLayout layer = layer(activity, false);
        if (layer != null && layer.getParent() instanceof ViewGroup) {
            ((ViewGroup) layer.getParent()).removeView(layer);
        }
    }

    static void render(Activity activity, ScreenScanner.Result result) {
        FrameLayout layer = layer(activity, true);
        if (layer == null) {
            return;
        }
        int[] origin = new int[2];
        layer.getLocationOnScreen(origin);
        int layerWidth = layer.getWidth();
        int layerHeight = layer.getHeight();
        if (layerWidth <= 0 || layerHeight <= 0) {
            // The layer was just added and is not laid out yet.
            InsightsAccessibilityService.requestScan(150);
            return;
        }

        int used = 0;
        if (Prefs.badgesEnabled()) {
            int wanted = Prefs.badgeCount();
            for (ScreenScanner.Hit hit : result.hits) {
                if (used >= MAX_ROWS) {
                    break;
                }
                List<ArcticShift.SubCount> subs = ArcticShift.cachedTopSubs(hit.user);
                if (subs == null) {
                    ArcticShift.requestTopSubs(hit.user);
                    continue;
                }
                if (subs.isEmpty()) {
                    continue;
                }
                Rect local = new Rect(hit.bounds);
                local.offset(-origin[0], -origin[1]);
                if (local.bottom <= 0 || local.top >= layerHeight) {
                    continue;
                }
                LinearLayout row = rowAt(layer, used);
                bindRow(activity, row, hit.user, subs, wanted);
                if (positionRow(activity, row, local, hit.merged, layerWidth)) {
                    row.setVisibility(View.VISIBLE);
                    used++;
                } else {
                    row.setVisibility(View.GONE);
                }
            }
        }
        // Hide unused rows.
        int rowIndex = 0;
        for (int i = 0; i < layer.getChildCount(); i++) {
            View child = layer.getChildAt(i);
            if (child instanceof LinearLayout && !HIDDEN_BUTTON_TAG.equals(child.getTag())) {
                if (rowIndex >= used) {
                    child.setVisibility(View.GONE);
                }
                rowIndex++;
            }
        }

        renderHiddenButton(activity, layer, result, origin, layerWidth, layerHeight);

        if (layer.getAlpha() < 1f) {
            layer.animate().alpha(1f).setDuration(120).start();
        }
    }

    private static LinearLayout rowAt(FrameLayout layer, int index) {
        int rowIndex = 0;
        for (int i = 0; i < layer.getChildCount(); i++) {
            View child = layer.getChildAt(i);
            if (child instanceof LinearLayout && !HIDDEN_BUTTON_TAG.equals(child.getTag())) {
                if (rowIndex == index) {
                    return (LinearLayout) child;
                }
                rowIndex++;
            }
        }
        LinearLayout row = new LinearLayout(layer.getContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        layer.addView(row, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return row;
    }

    private static void bindRow(final Activity activity, LinearLayout row, final String user,
                                List<ArcticShift.SubCount> subs, int wanted) {
        int count = Math.min(wanted, subs.size());
        StringBuilder signature = new StringBuilder(user).append('|');
        for (int i = 0; i < count; i++) {
            signature.append(subs.get(i).subreddit).append(':').append(subs.get(i).count).append(',');
        }
        String key = signature.toString();
        if (key.equals(row.getTag())) {
            // Same content; just make all pills visible again before measuring.
            for (int i = 0; i < row.getChildCount(); i++) {
                row.getChildAt(i).setVisibility(View.VISIBLE);
            }
            return;
        }
        row.setTag(key);
        row.removeAllViews();

        int gap = Ui.dp(activity, 4);
        for (int i = 0; i < count; i++) {
            final ArcticShift.SubCount sub = subs.get(i);
            TextView pill = pill(activity, "r/" + sub.subreddit + " · " + Ui.formatCount(sub.count),
                    PILL_COLORS[i % PILL_COLORS.length]);
            pill.setOnClickListener(v -> UserInsights.openArchive(activity, user, sub.subreddit));
            pill.setOnLongClickListener(v -> {
                UserInsights.openSettings(activity);
                return true;
            });
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            if (i > 0) {
                params.leftMargin = gap;
            }
            row.addView(pill, params);
        }
    }

    static TextView pill(Context context, String text, int color) {
        TextView pill = new TextView(context);
        pill.setText(text);
        pill.setTextColor(color);
        pill.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        pill.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        pill.setSingleLine(true);
        pill.setEllipsize(TextUtils.TruncateAt.END);
        pill.setMaxWidth(Ui.dp(context, 150));
        int h = Ui.dp(context, 7);
        int v = Ui.dp(context, 2);
        pill.setPadding(h, v, h, v);
        GradientDrawable background = new GradientDrawable();
        background.setCornerRadius(Ui.dp(context, 50));
        background.setColor(PILL_FILL);
        background.setStroke(Math.max(1, Ui.dp(context, 1)), color);
        pill.setBackground(background);
        pill.setClickable(true);
        return pill;
    }

    /**
     * Places a row after the name, dropping pills that do not fit.
     *
     * @return false if not even one pill fits.
     */
    private static boolean positionRow(Context context, LinearLayout row, Rect name, boolean merged,
                                       int layerWidth) {
        int margin = Ui.dp(context, 8);
        int gap = Ui.dp(context, 6);
        int unspecified = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED);

        row.measure(unspecified, unspecified);
        int height = row.getMeasuredHeight();

        if (merged) {
            // The name is somewhere inside a whole comment; put the pills in its top-right corner.
            int available = name.width() - 2 * margin;
            if (!fitPills(row, available)) {
                return false;
            }
            row.measure(unspecified, unspecified);
            row.setTranslationX(name.right - margin - row.getMeasuredWidth());
            row.setTranslationY(name.top + Ui.dp(context, 4));
            return true;
        }

        int y = name.centerY() - height / 2;
        int x = name.right + gap;
        int available = layerWidth - margin - x;
        if (fitPills(row, available)) {
            row.measure(unspecified, unspecified);
            row.setTranslationX(x);
            row.setTranslationY(y);
            return true;
        }
        // Not enough room after the name: show them just below it.
        row.measure(unspecified, unspecified);
        available = layerWidth - margin - name.left;
        if (!fitPills(row, available)) {
            return false;
        }
        row.setTranslationX(name.left);
        row.setTranslationY(name.bottom + Ui.dp(context, 2));
        return true;
    }

    /** Hides pills from the end until the row fits; false if none fits. */
    private static boolean fitPills(LinearLayout row, int available) {
        if (available <= 0) {
            return false;
        }
        int unspecified = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED);
        for (int i = 0; i < row.getChildCount(); i++) {
            row.getChildAt(i).setVisibility(View.VISIBLE);
        }
        for (int visible = row.getChildCount(); visible >= 1; visible--) {
            for (int i = 0; i < row.getChildCount(); i++) {
                row.getChildAt(i).setVisibility(i < visible ? View.VISIBLE : View.GONE);
            }
            row.measure(unspecified, unspecified);
            if (row.getMeasuredWidth() <= available) {
                return true;
            }
        }
        return false;
    }

    private static void renderHiddenButton(final Activity activity, FrameLayout layer, ScreenScanner.Result result,
                                           int[] origin, int layerWidth, int layerHeight) {
        View existing = layer.findViewWithTag(HIDDEN_BUTTON_TAG);
        if (!Prefs.hiddenButtonEnabled() || result.hiddenUser == null || result.hiddenBounds == null) {
            if (existing != null) {
                existing.setVisibility(View.GONE);
            }
            return;
        }

        LinearLayout button;
        TextView label;
        if (existing instanceof LinearLayout) {
            button = (LinearLayout) existing;
            label = (TextView) button.getChildAt(0);
        } else {
            button = new LinearLayout(activity);
            button.setTag(HIDDEN_BUTTON_TAG);
            button.setGravity(Gravity.CENTER);
            int h = Ui.dp(activity, 16);
            int v = Ui.dp(activity, 10);
            button.setPadding(h, v, h, v);
            GradientDrawable background = new GradientDrawable();
            background.setCornerRadius(Ui.dp(activity, 50));
            background.setColor(0xFFFF4500);
            button.setBackground(background);
            button.setElevation(Ui.dp(activity, 6));
            label = new TextView(activity);
            label.setTextColor(0xFFFFFFFF);
            label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
            label.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
            button.addView(label);
            layer.addView(button, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }

        final String user = result.hiddenUser;
        label.setText("Show u/" + user + "'s hidden activity");
        button.setOnClickListener(v -> UserInsights.openArchive(activity, user, null));
        button.setOnLongClickListener(v -> {
            UserInsights.openSettings(activity);
            return true;
        });

        Rect local = new Rect(result.hiddenBounds);
        local.offset(-origin[0], -origin[1]);
        int unspecified = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED);
        button.measure(unspecified, unspecified);
        int width = button.getMeasuredWidth();
        int height = button.getMeasuredHeight();
        int x = Math.max(0, Math.min(layerWidth - width, local.centerX() - width / 2));
        int y = local.bottom + Ui.dp(activity, 12);
        if (y + height > layerHeight - Ui.dp(activity, 16)) {
            y = Math.max(0, local.top - height - Ui.dp(activity, 12));
        }
        button.setTranslationX(x);
        button.setTranslationY(y);
        button.setVisibility(View.VISIBLE);
    }
}
