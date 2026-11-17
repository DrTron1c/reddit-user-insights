package app.userinsights.extension;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;

/**
 * Draws the top subreddit pills inside Reddit's own comment header, right after the name,
 * so they are part of the comment and always in the right place.
 *
 * The patch calls {@link #setAuthor}, {@link #setStyle} and {@link #setModifier} just before
 * Reddit draws the name, and {@link #drawTags} right after the name and role badge (OP, MOD).
 * {@link #drawText} is replaced during patching with a call to Reddit's own Text composable.
 */
@SuppressWarnings("unused")
public final class EmbeddedPills {
    /** Always this many texts, so the layout of the comment header never changes shape. */
    private static final int SLOTS = 3;
    private static final int LOADING_COLOR = 0xFF9A9DA3;

    private static String author;
    private static Object style;
    private static Object modifier;
    private static volatile long lastDrawnAt;

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static boolean stateLookedUp;
    private static Object state;
    private static Method getValue;
    private static Method setValue;
    private static int version;
    private static boolean updatePending;

    private EmbeddedPills() {
    }

    /** Injection point: the name about to be drawn. */
    public static void setAuthor(String name) {
        author = name;
    }

    /** Injection point: the text style of the name. */
    public static void setStyle(Object textStyle) {
        style = textStyle;
    }

    /** Injection point: the modifier of the name. */
    public static void setModifier(Object nameModifier) {
        modifier = nameModifier;
    }

    /** True while comment headers draw their own pills, so the floating ones are not needed there. */
    static boolean isActive() {
        return SystemClock.uptimeMillis() - lastDrawnAt < 10_000;
    }

    /**
     * Injection point: right after the name and role badge, inside the comment header row.
     *
     * @param composer Compose composer.
     */
    public static void drawTags(Object composer) {
        String[] texts = {"", "", ""};
        long[] colors = {color(LOADING_COLOR), color(LOADING_COLOR), color(LOADING_COLOR)};
        try {
            lastDrawnAt = SystemClock.uptimeMillis();
            // Read the shared state, so this header is redrawn when new subreddits arrive.
            readState();
            String user = author;
            if (UserInsights.app() != null && Prefs.badgesEnabled() && user != null && !user.isEmpty()) {
                List<ArcticShift.SubCount> subs = ArcticShift.cachedTopSubs(user);
                if (subs == null) {
                    ArcticShift.requestTopSubs(user);
                    if (ArcticShift.isLoading(user)) {
                        texts[0] = "  ···";
                    }
                } else {
                    int count = Math.min(Prefs.badgeCount(), Math.min(SLOTS, subs.size()));
                    for (int i = 0; i < count; i++) {
                        ArcticShift.SubCount sub = subs.get(i);
                        texts[i] = "  r/" + sub.subreddit + " " + Ui.formatCount(sub.count);
                        colors[i] = color(OverlayManager.PILL_COLORS[i % OverlayManager.PILL_COLORS.length]);
                    }
                }
            }
        } catch (Throwable t) {
            Log.e(UserInsights.TAG, "drawTags failed", t);
        }
        for (int i = 0; i < SLOTS; i++) {
            drawText(composer, texts[i], colors[i], style, modifier);
        }
    }

    /** A Compose Color value from an ARGB colour. */
    private static long color(int argb) {
        return ((long) argb) << 32;
    }

    /**
     * Placeholder replaced during patching with a call to Reddit's Text composable.
     * Not private, so it is called directly and not through a synthetic accessor.
     */
    static void drawText(Object composer, String text, long color, Object textStyle, Object textModifier) {
        // Replaced during patching.
    }

    /** Placeholder replaced during patching: Java name of Compose's mutable state class. */
    static String stateClassName() {
        return null;
    }

    /** Placeholder replaced during patching: Java name of Compose's mutation policy interface. */
    static String policyClassName() {
        return null;
    }

    /** Called when new subreddits arrive: redraws the comment headers that are on screen. */
    static void onDataChanged() {
        if (state == null || updatePending) {
            return;
        }
        updatePending = true;
        // Batch arrivals that come close together into one redraw.
        MAIN.postDelayed(() -> {
            updatePending = false;
            try {
                version++;
                setValue.invoke(state, version);
            } catch (Throwable t) {
                Log.w(UserInsights.TAG, "Could not redraw comment headers", t);
            }
        }, 120);
    }

    private static void readState() throws Exception {
        if (!stateLookedUp) {
            stateLookedUp = true;
            createState();
        }
        if (state != null) {
            getValue.invoke(state);
        }
    }

    /** A Compose mutable state of Reddit's own Compose version, made with reflection. */
    private static void createState() {
        try {
            String stateName = stateClassName();
            String policyName = policyClassName();
            if (stateName == null || policyName == null) {
                return;
            }
            ClassLoader loader = EmbeddedPills.class.getClassLoader();
            Class<?> stateClass = Class.forName(stateName, true, loader);
            Class<?> policyClass = Class.forName(policyName, true, loader);
            Object policy = Proxy.newProxyInstance(loader, new Class<?>[]{policyClass}, (proxy, method, args) -> {
                Class<?> returnType = method.getReturnType();
                if (args != null && args.length == 2 && returnType == boolean.class) {
                    // equivalent(a, b)
                    return args[0] == null ? args[1] == null : args[0].equals(args[1]);
                }
                switch (method.getName()) {
                    case "equals":
                        return args != null && args.length == 1 && proxy == args[0];
                    case "hashCode":
                        return System.identityHashCode(proxy);
                    case "toString":
                        return "UserInsightsPolicy";
                    default:
                        return returnType == boolean.class ? Boolean.FALSE : null;
                }
            });
            Constructor<?> constructor = stateClass.getDeclaredConstructor(Object.class, policyClass);
            constructor.setAccessible(true);
            Object created = constructor.newInstance(0, policy);
            Method get = findMethod(stateClass, "getValue", 0);
            Method set = findMethod(stateClass, "setValue", 1);
            if (get != null && set != null) {
                getValue = get;
                setValue = set;
                state = created;
            }
        } catch (Throwable t) {
            Log.w(UserInsights.TAG, "Comment headers will not redraw on their own", t);
        }
    }

    private static Method findMethod(Class<?> type, String name, int parameters) {
        for (Class<?> c = type; c != null; c = c.getSuperclass()) {
            for (Method method : c.getDeclaredMethods()) {
                if (method.getName().equals(name) && method.getParameterTypes().length == parameters) {
                    method.setAccessible(true);
                    return method;
                }
            }
        }
        return null;
    }
}
