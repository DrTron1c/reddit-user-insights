package app.userinsights.extension;

import android.graphics.Rect;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Finds usernames and the "hidden profile" message on a Reddit screen by reading its
 * accessibility tree. This does not depend on Reddit's (obfuscated) code, so it keeps working
 * across app updates as long as the visible text stays similar.
 */
final class ScreenScanner {
    private static final int MAX_NODES = 2500;
    private static final int MAX_DUMP_LINES = 500;

    private static final String NAME = "([A-Za-z0-9_-]{3,20})";

    /** "u/name" on its own. */
    private static final Pattern U_EXACT = Pattern.compile("^\\s*u/" + NAME + "\\s*$");
    /** "u/name • 3h" and similar short headers. */
    private static final Pattern U_LEADING = Pattern.compile("^\\s*u/" + NAME + "\\s*[\\u2022\\u00B7,]");
    /** "name • 3h" in a single text node. */
    private static final Pattern NAME_DOT_TIME = Pattern.compile(
            "^" + NAME + "\\s*[\\u2022\\u00B7,]\\s*(?:[\\u2022\\u00B7]\\s*)?(?:\\d+\\s?(?:s|m|h|d|w|mo|y|yr|min|mins|hr|hrs)\\b|just now)",
            Pattern.CASE_INSENSITIVE);
    /** A bare name candidate. */
    private static final Pattern BARE_NAME = Pattern.compile("^" + NAME + "$");
    /** "3h", "• 3h", "3h ago", "2mo", "just now", "Edited 1h". */
    private static final Pattern TIME = Pattern.compile(
            "^[\\u2022\\u00B7]?\\s*(?:edited\\s+)?(?:\\d+\\s?(?:s|m|h|d|w|mo|y|yr|yrs|min|mins|hr|hrs|sec|secs|day|days|wk|wks)"
                    + "(?:\\s+ago)?|just now|now)\\s*$",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern DOT = Pattern.compile("^\\s*[\\u2022\\u00B7]\\s*$");

    /** Content descriptions of whole comments/posts (TalkBack labels). */
    private static final Pattern DESC_U = Pattern.compile("(?<![A-Za-z0-9_/-])u/" + NAME + "(?![A-Za-z0-9_-])");
    private static final Pattern DESC_BY = Pattern.compile(
            "\\b(?:comment|reply|post|posted|commented|replied|written)\\s+by\\s+(?:u/)?" + NAME + "(?![A-Za-z0-9_-])",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern DESC_AUTHOR = Pattern.compile(
            "\\bauthor[:,]?\\s+(?:u/)?" + NAME + "(?![A-Za-z0-9_-])", Pattern.CASE_INSENSITIVE);
    private static final Pattern DESC_NAME_AGO = Pattern.compile(
            "^" + NAME + "[,.]?\\s+(?:\\d+|an?|one)\\s+(?:second|minute|hour|day|week|month|year)s?\\s+ago",
            Pattern.CASE_INSENSITIVE);

    /** The message Reddit shows on profiles that hide their activity. */
    private static final Pattern HIDDEN = Pattern.compile(
            "(keeps?\\s+(?:their|this|his|her)\\s+(?:profile|activity|account|posts|comments)[^.]{0,30}(?:hidden|private)"
                    + "|likes\\s+to\\s+keep"
                    + "|(?:profile|activity|history|posts and comments)\\s+(?:is|are)\\s+(?:hidden|private)"
                    + "|(?:hid|hides|hidden)\\s+(?:their|this|his|her)\\s+(?:profile|activity|posts|comments|history)"
                    + "|has\\s+hidden)",
            Pattern.CASE_INSENSITIVE);
    private static final Pattern NAME_IN_HIDDEN = Pattern.compile(
            "(?:u/)?" + NAME + "\\s+(?:likes\\s+to\\s+keep|keeps|has\\s+hidden|hid|hides)", Pattern.CASE_INSENSITIVE);

    private static final Set<String> STOP_WORDS = new HashSet<>(Arrays.asList(
            "reply", "replies", "share", "award", "awards", "vote", "votes", "upvote", "downvote", "join", "joined",
            "follow", "following", "followers", "sort", "best", "top", "new", "hot", "rising", "controversial", "old",
            "comments", "comment", "more", "edited", "pinned", "locked", "moderator", "mod", "mods", "admin", "op",
            "search", "home", "answers", "chat", "inbox", "create", "communities", "community", "popular", "latest",
            "trending", "promoted", "sponsored", "save", "saved", "hide", "hidden", "report", "block", "copy",
            "translate", "translated", "view", "show", "collapse", "expand", "profile", "about", "posts", "post",
            "overview", "karma", "nsfw", "spoiler", "video", "image", "images", "gallery", "link", "poll", "live",
            "reddit", "settings", "cancel", "done", "back", "close", "menu", "open", "see", "all", "notifications",
            "avatar", "today", "yesterday", "now", "ago", "deleted", "removed", "unknown", "anonymous", "guest",
            "media", "text", "feed", "default", "card", "compact", "classic", "games", "shop", "premium", "coins",
            "online", "members", "member", "subscribers", "rules", "wiki", "sidebar", "edit", "delete", "send",
            "post_title", "username", "user", "users", "you", "me", "your", "this", "that", "here", "there", "and"));

    static final class Hit {
        final String user;
        final Rect bounds;
        /** True when the name came from the label of a whole comment/post, not its own text. */
        final boolean merged;

        Hit(String user, Rect bounds, boolean merged) {
            this.user = user;
            this.bounds = bounds;
            this.merged = merged;
        }
    }

    static final class Result {
        final List<Hit> hits = new ArrayList<>();
        String hiddenUser;
        Rect hiddenBounds;
        String dump;
    }

    private static final class Node {
        String text;
        String desc;
        final Rect bounds = new Rect();
        boolean clickable;
        String cls;
        String viewId;
        int depth;
    }

    private ScreenScanner() {
    }

    static Result scan(AccessibilityNodeInfo root, boolean dump, float density) {
        List<Node> nodes = flatten(root);
        Result result = new Result();
        if (dump) {
            result.dump = buildDump(nodes);
        }

        int maxRowHeight = (int) (72 * density);
        Set<String> usedBounds = new HashSet<>();

        for (int i = 0; i < nodes.size(); i++) {
            Node node = nodes.get(i);
            String text = node.text;

            if (text != null && text.length() <= 400) {
                // Long text is a whole comment/post merged into one node (name at the start).
                boolean tall = node.bounds.height() > maxRowHeight;
                boolean shortText = text.length() <= 60;

                // u/name on its own, or at the start of a header such as "u/name • 3h".
                Matcher m = U_EXACT.matcher(text);
                if (!m.find()) {
                    m = U_LEADING.matcher(text);
                    if (!m.find() || (tall && shortText)) {
                        m = null;
                    }
                }
                if (m != null) {
                    addHit(result, usedBounds, m.group(1), node.bounds, tall);
                    continue;
                }

                // name • 3h  /  name, 3h
                m = NAME_DOT_TIME.matcher(text);
                if (m.find() && isPlausibleName(m.group(1))) {
                    addHit(result, usedBounds, m.group(1), node.bounds, tall);
                    continue;
                }

                // A bare name followed by a time on the same row.
                if (shortText) {
                    m = BARE_NAME.matcher(text.trim());
                    if (m.find() && isPlausibleName(m.group(1))
                            && node.bounds.height() > 0 && !tall
                            && followedByTime(nodes, i)) {
                        addHit(result, usedBounds, m.group(1), node.bounds, false);
                        continue;
                    }
                }
            }

            // Labels of whole comments/posts, used when the name is not a separate node.
            String desc = node.desc;
            if (desc != null && desc.length() <= 400) {
                String user = null;
                Matcher m = DESC_BY.matcher(desc);
                if (m.find()) {
                    user = m.group(1);
                } else if ((m = DESC_AUTHOR.matcher(desc)).find()) {
                    user = m.group(1);
                } else if ((m = DESC_NAME_AGO.matcher(desc)).find() && isPlausibleName(m.group(1))) {
                    user = m.group(1);
                } else if (desc.length() <= 60 && (m = DESC_U.matcher(desc)).find()) {
                    user = m.group(1);
                }
                if (user != null) {
                    boolean merged = node.bounds.height() > maxRowHeight;
                    addHit(result, usedBounds, user, node.bounds, merged);
                }
            }
        }

        removeMergedDuplicates(result.hits);
        findHiddenProfile(nodes, result);
        return result;
    }

    private static boolean isPlausibleName(String name) {
        if (name == null || name.length() < 3) {
            return false;
        }
        String lower = name.toLowerCase(Locale.ROOT);
        if (STOP_WORDS.contains(lower)) {
            return false;
        }
        // All digits, or a time like "12h", is not a name.
        if (lower.matches("\\d+") || lower.matches("\\d+(s|m|h|d|w|mo|y|yr|k)")) {
            return false;
        }
        return true;
    }

    private static boolean followedByTime(List<Node> nodes, int index) {
        Node name = nodes.get(index);
        int checked = 0;
        for (int j = index + 1; j < nodes.size() && checked < 5; j++) {
            Node next = nodes.get(j);
            String text = next.text != null ? next.text : next.desc;
            if (text == null || text.trim().isEmpty()) {
                continue;
            }
            checked++;
            if (!sameRow(name.bounds, next.bounds)) {
                return false;
            }
            String trimmed = text.trim();
            if (TIME.matcher(trimmed).matches()) {
                return true;
            }
            if (DOT.matcher(trimmed).matches() || trimmed.equalsIgnoreCase("OP")
                    || trimmed.equalsIgnoreCase("MOD") || trimmed.equalsIgnoreCase("Admin")) {
                continue;
            }
            // Flair or other text between the name and the time: keep looking a little.
        }
        return false;
    }

    private static boolean sameRow(Rect a, Rect b) {
        if (b.height() <= 0) {
            return true;
        }
        int tolerance = Math.max(a.height(), b.height());
        return Math.abs(a.centerY() - b.centerY()) <= tolerance;
    }

    private static void addHit(Result result, Set<String> usedBounds, String user, Rect bounds, boolean merged) {
        if (bounds.width() <= 0 || bounds.height() <= 0) {
            return;
        }
        String key = bounds.flattenToString();
        if (!usedBounds.add(key)) {
            return;
        }
        result.hits.add(new Hit(user, new Rect(bounds), merged));
    }

    /** Drops a whole-comment hit when the same name was also found as its own text inside it. */
    private static void removeMergedDuplicates(List<Hit> hits) {
        List<Hit> remove = new ArrayList<>();
        for (Hit merged : hits) {
            if (!merged.merged) {
                continue;
            }
            for (Hit exact : hits) {
                if (!exact.merged && exact.user.equalsIgnoreCase(merged.user)
                        && merged.bounds.contains(exact.bounds.centerX(), exact.bounds.centerY())) {
                    remove.add(merged);
                    break;
                }
            }
        }
        hits.removeAll(remove);
    }

    private static void findHiddenProfile(List<Node> nodes, Result result) {
        for (int i = 0; i < nodes.size(); i++) {
            Node node = nodes.get(i);
            String text = node.text != null ? node.text : node.desc;
            if (text == null || text.length() > 300 || !HIDDEN.matcher(text).find()) {
                continue;
            }

            String user = null;
            Matcher inMessage = NAME_IN_HIDDEN.matcher(text);
            if (inMessage.find() && isPlausibleName(inMessage.group(1))) {
                user = inMessage.group(1);
            }
            if (user == null) {
                // The profile header usually shows u/name near the top.
                Hit top = null;
                for (Hit hit : result.hits) {
                    if (top == null || hit.bounds.top < top.bounds.top) {
                        top = hit;
                    }
                }
                if (top != null) {
                    user = top.user;
                }
            }
            if (user == null) {
                // Nearest plausible bare name above the message.
                for (int j = i - 1; j >= 0; j--) {
                    String candidate = nodes.get(j).text;
                    if (candidate == null) {
                        continue;
                    }
                    Matcher m = BARE_NAME.matcher(candidate.trim());
                    if (m.find() && isPlausibleName(m.group(1))) {
                        user = m.group(1);
                        break;
                    }
                }
            }
            if (user != null) {
                result.hiddenUser = user;
                result.hiddenBounds = new Rect(node.bounds);
                return;
            }
        }
    }

    private static List<Node> flatten(AccessibilityNodeInfo root) {
        List<Node> out = new ArrayList<>();
        ArrayDeque<AccessibilityNodeInfo> stack = new ArrayDeque<>();
        ArrayDeque<Integer> depths = new ArrayDeque<>();
        stack.push(root);
        depths.push(0);

        while (!stack.isEmpty() && out.size() < MAX_NODES) {
            AccessibilityNodeInfo info = stack.pop();
            int depth = depths.pop();
            if (info == null) {
                continue;
            }
            try {
                if (!info.isVisibleToUser()) {
                    continue;
                }
                Node node = new Node();
                CharSequence text = info.getText();
                CharSequence desc = info.getContentDescription();
                node.text = text == null ? null : text.toString();
                node.desc = desc == null ? null : desc.toString();
                info.getBoundsInScreen(node.bounds);
                node.clickable = info.isClickable();
                CharSequence cls = info.getClassName();
                node.cls = cls == null ? "" : cls.toString();
                node.viewId = info.getViewIdResourceName();
                node.depth = depth;
                out.add(node);

                // Push children in reverse so they are visited in order.
                int count = info.getChildCount();
                for (int c = count - 1; c >= 0; c--) {
                    AccessibilityNodeInfo child = info.getChild(c);
                    if (child != null) {
                        stack.push(child);
                        depths.push(depth + 1);
                    }
                }
            } catch (Throwable ignored) {
                // Node went stale while reading.
            }
        }
        return out;
    }

    private static String buildDump(List<Node> nodes) {
        StringBuilder sb = new StringBuilder("User Insights screen dump (")
                .append(nodes.size()).append(" nodes)\n");
        int lines = 0;
        for (Node node : nodes) {
            if (node.text == null && node.desc == null && !node.clickable) {
                continue;
            }
            if (lines++ >= MAX_DUMP_LINES) {
                sb.append("...\n");
                break;
            }
            for (int d = 0; d < Math.min(node.depth, 20); d++) {
                sb.append(' ');
            }
            String cls = node.cls;
            int dot = cls.lastIndexOf('.');
            sb.append(dot >= 0 ? cls.substring(dot + 1) : cls);
            if (node.text != null) {
                sb.append(" text=\"").append(shorten(node.text)).append('"');
            }
            if (node.desc != null) {
                sb.append(" desc=\"").append(shorten(node.desc)).append('"');
            }
            if (node.viewId != null) {
                sb.append(" id=").append(node.viewId);
            }
            if (node.clickable) {
                sb.append(" [click]");
            }
            sb.append(' ').append(node.bounds.toShortString()).append('\n');
        }
        return sb.toString();
    }

    private static String shorten(String s) {
        String oneLine = s.replace('\n', ' ');
        return oneLine.length() > 120 ? oneLine.substring(0, 120) + "…" : oneLine;
    }
}
