package app.userinsights.extension;

import android.app.Activity;
import android.content.Context;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.TextPaint;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Collections;
import java.util.List;

/**
 * Draws the subreddit pills, the hidden-profile view and the hidden-profile button on top of the
 * Reddit activity. The views live in a transparent layer added to the activity's window, so touches
 * outside them go straight through to Reddit.
 */
final class OverlayManager {
    private static final String LAYER_TAG = "user_insights_layer";
    private static final String HIDDEN_BUTTON_TAG = "user_insights_hidden_button";
    private static final String INLINE_TAG = "user_insights_inline";
    private static final String ROW_TAG_PREFIX = "user_insights_row:";
    private static final int MAX_ROWS = 30;

    /** Border/text colours of the 1st, 2nd and 3rd pill. */
    static final int[] PILL_COLORS = {0xFFE8834A, 0xFFA77BF3, 0xFFE0709A};
    private static final int FULL_HISTORY_COLOR = 0xFF9A9DA3;
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
        // Keep these views out of the accessibility tree, so they are not scanned or read out.
        layer.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
        layer.setElevation(Ui.dp(activity, 24));
        layer.setTranslationZ(Ui.dp(activity, 24));
        decorGroup.addView(layer, new ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        return layer;
    }

    /** Hides everything while the screen scrolls, so pills never point at the wrong comment. */
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
        if (layer == null) {
            return;
        }
        releaseInline(layer);
        if (layer.getParent() instanceof ViewGroup) {
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

        boolean inlineShown = renderInline(activity, layer, result, origin, layerHeight);
        renderHiddenButton(activity, layer, result, origin, layerWidth, layerHeight, inlineShown);
        renderRows(activity, layer, result, origin, layerWidth, layerHeight, inlineShown);

        if (layer.getAlpha() < 1f) {
            layer.animate().alpha(1f).setDuration(120).start();
        }
    }

    // Pills.

    private static void renderRows(Activity activity, FrameLayout layer, ScreenScanner.Result result,
                                   int[] origin, int layerWidth, int layerHeight, boolean inlineShown) {
        int used = 0;
        boolean badges = Prefs.badgesEnabled();
        int wanted = Prefs.badgeCount();
        int inlineTop = inlineShown ? (int) layer.findViewWithTag(INLINE_TAG).getTranslationY() : Integer.MAX_VALUE;

        for (ScreenScanner.Hit hit : result.hits) {
            if (used >= MAX_ROWS) {
                break;
            }
            // "Full history" on the u/name header of a profile that is not hidden.
            boolean fullHistory = hit.profileHeader && result.hiddenUser == null
                    && hit.user.equalsIgnoreCase(result.profileUser) && Prefs.hiddenButtonEnabled();
            if (!badges && !fullHistory) {
                continue;
            }

            List<ArcticShift.SubCount> subs = Collections.emptyList();
            if (badges) {
                List<ArcticShift.SubCount> cached = ArcticShift.cachedTopSubs(hit.user);
                if (cached == null) {
                    ArcticShift.requestTopSubs(hit.user);
                    if (!fullHistory) {
                        continue;
                    }
                } else {
                    subs = cached;
                }
            }
            if (subs.isEmpty() && !fullHistory) {
                continue;
            }

            Rect local = new Rect(hit.bounds);
            local.offset(-origin[0], -origin[1]);
            if (local.bottom <= 0 || local.top >= layerHeight || local.top >= inlineTop) {
                continue;
            }
            LinearLayout row = rowAt(layer, used);
            bindRow(activity, row, hit.user, subs, badges ? wanted : 0, fullHistory);
            if (positionRow(activity, row, local, anchorRight(activity, hit, local), hit.merged, layerWidth)) {
                row.setVisibility(View.VISIBLE);
                used++;
            } else {
                row.setVisibility(View.GONE);
            }
        }

        // Hide unused rows.
        int rowIndex = 0;
        for (int i = 0; i < layer.getChildCount(); i++) {
            View child = layer.getChildAt(i);
            if (isRow(child)) {
                if (rowIndex >= used) {
                    child.setVisibility(View.GONE);
                }
                rowIndex++;
            }
        }
    }

    private static boolean isRow(View view) {
        Object tag = view.getTag();
        return tag instanceof String && ((String) tag).startsWith(ROW_TAG_PREFIX);
    }

    /**
     * Where the name (and time) text ends. A node can be wider than its text,
     * such as a whole header row, so measure the text in that case.
     */
    private static int anchorRight(Context context, ScreenScanner.Hit hit, Rect local) {
        if (hit.merged || hit.headerText == null) {
            return local.right;
        }
        TextPaint paint = new TextPaint();
        paint.setTextSize(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, 14,
                context.getResources().getDisplayMetrics()));
        int textWidth = (int) Math.ceil(paint.measureText(hit.headerText.split("\n")[0]));
        if (local.width() > textWidth + Ui.dp(context, 24)) {
            return Math.min(local.right, local.left + textWidth);
        }
        return local.right;
    }

    private static LinearLayout rowAt(FrameLayout layer, int index) {
        int rowIndex = 0;
        for (int i = 0; i < layer.getChildCount(); i++) {
            View child = layer.getChildAt(i);
            if (isRow(child)) {
                if (rowIndex == index) {
                    return (LinearLayout) child;
                }
                rowIndex++;
            }
        }
        LinearLayout row = new LinearLayout(layer.getContext());
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setTag(ROW_TAG_PREFIX);
        layer.addView(row, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        return row;
    }

    private static void bindRow(final Activity activity, LinearLayout row, final String user,
                                List<ArcticShift.SubCount> subs, int wanted, boolean fullHistory) {
        int count = Math.min(wanted, subs.size());
        StringBuilder signature = new StringBuilder(ROW_TAG_PREFIX).append(user).append('|');
        for (int i = 0; i < count; i++) {
            signature.append(subs.get(i).subreddit).append(':').append(subs.get(i).count).append(',');
        }
        signature.append(fullHistory);
        String key = signature.toString();
        if (key.equals(row.getTag())) {
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
            addPill(row, pill, i > 0 ? gap : 0);
        }
        if (fullHistory) {
            TextView pill = pill(activity, "Full history ›", FULL_HISTORY_COLOR);
            pill.setOnClickListener(v -> UserInsights.openArchive(activity, user, null));
            pill.setOnLongClickListener(v -> {
                UserInsights.openSettings(activity);
                return true;
            });
            addPill(row, pill, count > 0 ? gap : 0);
        }
    }

    private static void addPill(LinearLayout row, TextView pill, int leftMargin) {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.leftMargin = leftMargin;
        row.addView(pill, params);
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
     * Places a row on the name's line, right after the name and time, dropping pills that do not fit.
     *
     * @return false if not even one pill fits.
     */
    private static boolean positionRow(Context context, LinearLayout row, Rect name, int anchorRight,
                                       boolean merged, int layerWidth) {
        int margin = Ui.dp(context, 8);
        int gap = Ui.dp(context, 8);
        int unspecified = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED);

        if (merged) {
            // The name is somewhere inside a whole comment; put the pills in its top-right corner.
            if (!fitPills(row, name.width() - 2 * margin)) {
                return false;
            }
            row.measure(unspecified, unspecified);
            row.setTranslationX(name.right - margin - row.getMeasuredWidth());
            row.setTranslationY(name.top + Ui.dp(context, 4));
            return true;
        }

        // Centre on the first line of the name, even if the node also covers a flair line below it.
        int lineHeight = Math.min(name.height(), Ui.dp(context, 28));
        int x = anchorRight + gap;
        if (fitPills(row, layerWidth - margin - x)) {
            row.measure(unspecified, unspecified);
            row.setTranslationX(x);
            row.setTranslationY(name.top + (lineHeight - row.getMeasuredHeight()) / 2f);
            return true;
        }
        // No room on the line at all: just below the name.
        if (!fitPills(row, layerWidth - margin - name.left)) {
            return false;
        }
        row.measure(unspecified, unspecified);
        row.setTranslationX(name.left);
        row.setTranslationY(name.bottom + Ui.dp(context, 2));
        return true;
    }

    /** Hides pills from the end until the row fits; false if none fits. */
    private static boolean fitPills(LinearLayout row, int available) {
        if (available <= 0 || row.getChildCount() == 0) {
            return false;
        }
        int unspecified = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED);
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

    // Hidden profiles.

    /**
     * Shows the archived activity right inside a hidden profile, under its tabs.
     *
     * @return true if shown.
     */
    private static boolean renderInline(Activity activity, FrameLayout layer, ScreenScanner.Result result,
                                        int[] origin, int layerHeight) {
        View existing = layer.findViewWithTag(INLINE_TAG);
        if (!Prefs.hiddenButtonEnabled() || !Prefs.inlineHiddenEnabled()
                || result.hiddenUser == null || result.hiddenBounds == null) {
            if (existing != null) {
                existing.setVisibility(View.GONE);
            }
            return false;
        }

        Rect message = new Rect(result.hiddenBounds);
        message.offset(-origin[0], -origin[1]);
        int top;
        if (result.profileContentTop > 0 && result.profileContentTop - origin[1] < message.top) {
            top = result.profileContentTop - origin[1];
        } else {
            // No tabs found: start a little above the message, which usually has a picture above it.
            top = message.top - Ui.dp(activity, 140);
        }
        top = Math.max(top, Ui.dp(activity, 80));
        int height = layerHeight - top;
        if (height < Ui.dp(activity, 220)) {
            if (existing != null) {
                existing.setVisibility(View.GONE);
            }
            return false;
        }

        FrameLayout panel;
        if (existing instanceof FrameLayout) {
            panel = (FrameLayout) existing;
            View child = panel.getChildCount() > 0 ? panel.getChildAt(0) : null;
            if (!(child instanceof ArchiveView) || !((ArchiveView) child).user.equalsIgnoreCase(result.hiddenUser)) {
                if (child instanceof ArchiveView) {
                    ((ArchiveView) child).release();
                }
                panel.removeAllViews();
                panel.addView(new ArchiveView(activity, result.hiddenUser, null, true));
            }
        } else {
            panel = new FrameLayout(activity);
            panel.setTag(INLINE_TAG);
            panel.setClickable(true);
            panel.addView(new ArchiveView(activity, result.hiddenUser, null, true));
            layer.addView(panel, 0, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, height));
        }

        FrameLayout.LayoutParams params = (FrameLayout.LayoutParams) panel.getLayoutParams();
        if (params.height != height) {
            params.height = height;
            panel.setLayoutParams(params);
        }
        panel.setTranslationY(top);
        panel.setVisibility(View.VISIBLE);
        return true;
    }

    private static void releaseInline(FrameLayout layer) {
        View existing = layer.findViewWithTag(INLINE_TAG);
        if (existing instanceof FrameLayout) {
            FrameLayout panel = (FrameLayout) existing;
            if (panel.getChildCount() > 0 && panel.getChildAt(0) instanceof ArchiveView) {
                ((ArchiveView) panel.getChildAt(0)).release();
            }
            layer.removeView(panel);
        }
    }

    private static void renderHiddenButton(final Activity activity, FrameLayout layer, ScreenScanner.Result result,
                                           int[] origin, int layerWidth, int layerHeight, boolean inlineShown) {
        View existing = layer.findViewWithTag(HIDDEN_BUTTON_TAG);
        if (inlineShown || !Prefs.hiddenButtonEnabled() || result.hiddenUser == null || result.hiddenBounds == null) {
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
