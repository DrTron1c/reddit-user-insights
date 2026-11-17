package app.userinsights.extension;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.BaseAdapter;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * Shows a user's archived posts and comments from Arctic Shift,
 * including users whose Reddit profile is hidden.
 */
public class ArchiveActivity extends Activity {
    static final String EXTRA_USER = "user_insights_user";
    static final String EXTRA_SUBREDDIT = "user_insights_subreddit";

    private static final int BG = 0xFF0B0B0C;
    private static final int SURFACE = 0xFF17181B;
    private static final int TEXT = 0xFFEDEEF0;
    private static final int TEXT_DIM = 0xFF9A9DA3;
    private static final int ACCENT = 0xFFFF4500;

    private String user;
    private String subreddit;
    private boolean showPosts;

    private final List<ArcticShift.Item> items = new ArrayList<>();
    private boolean loading;
    private boolean endReached;
    private int requestToken;

    private LinearLayout chipRow;
    private TextView commentsTab;
    private TextView postsTab;
    private TextView footer;
    private ItemAdapter adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        UserInsights.init(this);

        user = getIntent().getStringExtra(EXTRA_USER);
        subreddit = getIntent().getStringExtra(EXTRA_SUBREDDIT);
        if (user == null || user.trim().isEmpty()) {
            finish();
            return;
        }
        user = user.trim().replaceFirst("^/?u/", "");

        try {
            getWindow().setStatusBarColor(BG);
            getWindow().setNavigationBarColor(BG);
        } catch (Throwable ignored) {
            // Not important.
        }

        setContentView(buildLayout());
        loadTopSubreddits();
        reload();
    }

    private View buildLayout() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);
        root.setFitsSystemWindows(true);

        // Header.
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(4), dp(8), dp(12), dp(8));

        TextView back = new TextView(this);
        back.setText("←");
        back.setTextColor(TEXT);
        back.setTextSize(TypedValue.COMPLEX_UNIT_SP, 22);
        back.setPadding(dp(12), dp(4), dp(12), dp(4));
        back.setOnClickListener(v -> finish());
        header.addView(back);

        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        TextView title = new TextView(this);
        title.setText("u/" + user);
        title.setTextColor(TEXT);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 18);
        title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.END);
        titles.addView(title);
        TextView subtitle = new TextView(this);
        subtitle.setText("Archived activity · Arctic Shift · may be incomplete");
        subtitle.setTextColor(TEXT_DIM);
        subtitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
        titles.addView(subtitle);
        header.addView(titles, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

        TextView profile = smallButton("Profile");
        profile.setOnClickListener(v -> UserInsights.openInReddit(this, "https://www.reddit.com/user/" + user));
        header.addView(profile);

        TextView settings = smallButton("⚙");
        settings.setOnClickListener(v -> UserInsights.openSettings(this));
        LinearLayout.LayoutParams settingsParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        settingsParams.leftMargin = dp(8);
        header.addView(settings, settingsParams);
        root.addView(header);

        // Subreddit chips.
        HorizontalScrollView chipScroll = new HorizontalScrollView(this);
        chipScroll.setHorizontalScrollBarEnabled(false);
        chipRow = new LinearLayout(this);
        chipRow.setOrientation(LinearLayout.HORIZONTAL);
        chipRow.setPadding(dp(12), dp(4), dp(12), dp(8));
        chipScroll.addView(chipRow);
        root.addView(chipScroll);
        renderChips(null);

        // Tabs.
        LinearLayout tabs = new LinearLayout(this);
        tabs.setOrientation(LinearLayout.HORIZONTAL);
        commentsTab = tab("Comments");
        postsTab = tab("Posts");
        commentsTab.setOnClickListener(v -> selectTab(false));
        postsTab.setOnClickListener(v -> selectTab(true));
        tabs.addView(commentsTab, new LinearLayout.LayoutParams(0, dp(44), 1f));
        tabs.addView(postsTab, new LinearLayout.LayoutParams(0, dp(44), 1f));
        root.addView(tabs);
        updateTabs();

        View divider = new View(this);
        divider.setBackgroundColor(0xFF26282C);
        root.addView(divider, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1));

        // List.
        ListView list = new ListView(this);
        list.setDivider(null);
        list.setBackgroundColor(BG);
        list.setCacheColorHint(BG);
        list.setSelector(android.R.color.transparent);
        footer = new TextView(this);
        footer.setGravity(Gravity.CENTER);
        footer.setTextColor(TEXT_DIM);
        footer.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        footer.setPadding(dp(16), dp(20), dp(16), dp(28));
        footer.setOnClickListener(v -> {
            if (!loading && !endReached) {
                loadMore();
            }
        });
        list.addFooterView(footer, null, true);
        adapter = new ItemAdapter();
        list.setAdapter(adapter);
        list.setOnItemClickListener((parent, view, position, id) -> {
            if (position < items.size()) {
                ArcticShift.Item item = items.get(position);
                if (!item.permalink.isEmpty()) {
                    String link = item.permalink.startsWith("http")
                            ? item.permalink : "https://www.reddit.com" + item.permalink;
                    UserInsights.openInReddit(this, link);
                }
            }
        });
        list.setOnItemLongClickListener((parent, view, position, id) -> {
            if (position < items.size()) {
                ArcticShift.Item item = items.get(position);
                String text = item.isPost ? item.title + "\n\n" + item.body : item.body;
                ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                clipboard.setPrimaryClip(ClipData.newPlainText("Reddit text", text.trim()));
                Toast.makeText(this, "Copied", Toast.LENGTH_SHORT).show();
                return true;
            }
            return false;
        });
        list.setOnScrollListener(new AbsListView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(AbsListView view, int scrollState) {
            }

            @Override
            public void onScroll(AbsListView view, int first, int visible, int total) {
                if (total > 1 && first + visible >= total - 3 && !loading && !endReached && !items.isEmpty()) {
                    loadMore();
                }
            }
        });
        root.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        return root;
    }

    private TextView smallButton(String text) {
        TextView button = new TextView(this);
        button.setText(text);
        button.setTextColor(TEXT);
        button.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        button.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        button.setPadding(dp(12), dp(6), dp(12), dp(6));
        GradientDrawable background = new GradientDrawable();
        background.setCornerRadius(dp(50));
        background.setColor(SURFACE);
        button.setBackground(background);
        return button;
    }

    private TextView tab(String text) {
        TextView tab = new TextView(this);
        tab.setText(text);
        tab.setGravity(Gravity.CENTER);
        tab.setTextSize(TypedValue.COMPLEX_UNIT_SP, 15);
        tab.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return tab;
    }

    private void selectTab(boolean posts) {
        if (showPosts == posts) {
            return;
        }
        showPosts = posts;
        updateTabs();
        reload();
    }

    private void updateTabs() {
        styleTab(commentsTab, !showPosts);
        styleTab(postsTab, showPosts);
    }

    private void styleTab(TextView tab, boolean selected) {
        tab.setTextColor(selected ? TEXT : TEXT_DIM);
        tab.setBackground(selected ? underlineDrawable() : null);
    }

    private android.graphics.drawable.Drawable underlineDrawable() {
        GradientDrawable line = new GradientDrawable();
        line.setColor(ACCENT);
        android.graphics.drawable.InsetDrawable inset = new android.graphics.drawable.InsetDrawable(line, 0);
        android.graphics.drawable.LayerDrawable layer =
                new android.graphics.drawable.LayerDrawable(new android.graphics.drawable.Drawable[]{inset});
        layer.setLayerGravity(0, Gravity.BOTTOM | Gravity.FILL_HORIZONTAL);
        layer.setLayerHeight(0, dp(3));
        layer.setLayerInsetLeft(0, dp(40));
        layer.setLayerInsetRight(0, dp(40));
        return layer;
    }

    private void renderChips(List<ArcticShift.SubCount> subs) {
        chipRow.removeAllViews();
        addChip("All", null, 0, -1);
        boolean selectedPresent = subreddit == null;
        if (subs != null) {
            for (int i = 0; i < subs.size(); i++) {
                ArcticShift.SubCount sub = subs.get(i);
                if (sub.subreddit.equalsIgnoreCase(subreddit)) {
                    selectedPresent = true;
                }
                addChip("r/" + sub.subreddit + " · " + Ui.formatCount(sub.count), sub.subreddit, sub.count, i);
            }
        }
        if (!selectedPresent) {
            addChip("r/" + subreddit, subreddit, 0, -1);
        }
    }

    private void addChip(String label, final String chipSubreddit, int count, int rank) {
        boolean selected = chipSubreddit == null ? subreddit == null : chipSubreddit.equalsIgnoreCase(subreddit);
        int color = rank >= 0 && rank < OverlayManager.PILL_COLORS.length ? OverlayManager.PILL_COLORS[rank] : TEXT;

        TextView chip = new TextView(this);
        chip.setText(label);
        chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        chip.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        chip.setSingleLine(true);
        chip.setPadding(dp(12), dp(6), dp(12), dp(6));
        GradientDrawable background = new GradientDrawable();
        background.setCornerRadius(dp(50));
        if (selected) {
            background.setColor(color);
            chip.setTextColor(0xFF000000);
        } else {
            background.setColor(SURFACE);
            background.setStroke(dp(1), rank >= 0 ? color : 0xFF3A3D42);
            chip.setTextColor(rank >= 0 ? color : TEXT);
        }
        chip.setBackground(background);
        chip.setOnClickListener(v -> {
            boolean same = chipSubreddit == null ? subreddit == null : chipSubreddit.equalsIgnoreCase(subreddit);
            if (same) {
                return;
            }
            subreddit = chipSubreddit;
            renderChips(lastSubs);
            reload();
        });
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        params.rightMargin = dp(8);
        chipRow.addView(chip, params);
    }

    private List<ArcticShift.SubCount> lastSubs;

    private void loadTopSubreddits() {
        ArcticShift.topSubs(user, 15, (result, error) -> {
            if (isFinishing() || result == null) {
                return;
            }
            lastSubs = result;
            renderChips(result);
        });
    }

    private void reload() {
        items.clear();
        endReached = false;
        loading = false;
        requestToken++;
        adapter.notifyDataSetChanged();
        loadMore();
    }

    private void loadMore() {
        if (loading || endReached) {
            return;
        }
        loading = true;
        footer.setText("Loading…");
        final int token = requestToken;
        long before = 0;
        if (!items.isEmpty()) {
            before = items.get(items.size() - 1).createdUtc;
        }
        ArcticShift.search(showPosts, user, subreddit, before, (result, error) -> {
            if (token != requestToken || isFinishing()) {
                return;
            }
            loading = false;
            if (result == null) {
                footer.setText((error == null ? "Could not load" : error) + "\nTap to retry");
                return;
            }
            // Skip items already shown (same timestamp page boundary).
            int added = 0;
            for (ArcticShift.Item item : result) {
                boolean duplicate = false;
                for (int i = Math.max(0, items.size() - 60); i < items.size(); i++) {
                    if (items.get(i).id.equals(item.id)) {
                        duplicate = true;
                        break;
                    }
                }
                if (!duplicate) {
                    items.add(item);
                    added++;
                }
            }
            if (result.isEmpty() || added == 0) {
                endReached = true;
            }
            adapter.notifyDataSetChanged();
            if (items.isEmpty()) {
                footer.setText(showPosts
                        ? "No archived posts found" + (subreddit != null ? " in r/" + subreddit : "")
                        : "No archived comments found" + (subreddit != null ? " in r/" + subreddit : ""));
            } else if (endReached) {
                footer.setText("That's everything in the archive");
            } else {
                footer.setText("Load more");
            }
        });
    }

    private int dp(float value) {
        return Ui.dp(this, value);
    }

    private final class ItemAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return items.size();
        }

        @Override
        public Object getItem(int position) {
            return items.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            ViewHolder holder;
            if (convertView == null) {
                LinearLayout card = new LinearLayout(ArchiveActivity.this);
                card.setOrientation(LinearLayout.VERTICAL);
                card.setPadding(dp(16), dp(12), dp(16), dp(12));
                holder = new ViewHolder();
                holder.meta = new TextView(ArchiveActivity.this);
                holder.meta.setTextColor(TEXT_DIM);
                holder.meta.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12);
                holder.title = new TextView(ArchiveActivity.this);
                holder.title.setTextColor(TEXT);
                holder.title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 16);
                holder.title.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
                holder.title.setPadding(0, dp(4), 0, 0);
                holder.body = new TextView(ArchiveActivity.this);
                holder.body.setTextColor(0xFFCFD1D4);
                holder.body.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
                holder.body.setMaxLines(10);
                holder.body.setEllipsize(TextUtils.TruncateAt.END);
                holder.body.setPadding(0, dp(4), 0, 0);
                View divider = new View(ArchiveActivity.this);
                divider.setBackgroundColor(0xFF1F2124);
                card.addView(holder.meta);
                card.addView(holder.title);
                card.addView(holder.body);
                LinearLayout wrapper = new LinearLayout(ArchiveActivity.this);
                wrapper.setOrientation(LinearLayout.VERTICAL);
                wrapper.addView(card);
                wrapper.addView(divider, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 1));
                wrapper.setTag(holder);
                convertView = wrapper;
            } else {
                holder = (ViewHolder) convertView.getTag();
            }

            ArcticShift.Item item = items.get(position);
            StringBuilder meta = new StringBuilder("r/").append(item.subreddit);
            String ago = Ui.timeAgo(item.createdUtc);
            if (!ago.isEmpty()) {
                meta.append(" · ").append(ago);
            }
            meta.append(" · ↑ ").append(Ui.formatCount(Math.max(0, item.score)));
            if (item.isPost) {
                meta.append(" · ").append(Ui.formatCount(item.numComments)).append(" comments");
            }
            holder.meta.setText(meta);

            if (item.isPost) {
                holder.title.setVisibility(View.VISIBLE);
                holder.title.setText(item.title);
                String body = item.body;
                if (body.isEmpty() && !item.url.isEmpty() && !item.url.contains(item.id)) {
                    body = item.url;
                }
                holder.body.setVisibility(body.isEmpty() ? View.GONE : View.VISIBLE);
                holder.body.setText(body);
            } else {
                holder.title.setVisibility(View.GONE);
                holder.body.setVisibility(View.VISIBLE);
                holder.body.setText(item.body.isEmpty() ? "[no text]" : item.body);
            }
            return convertView;
        }
    }

    private static final class ViewHolder {
        TextView meta;
        TextView title;
        TextView body;
    }
}
