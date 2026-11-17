package app.userinsights.extension;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.Toast;

/**
 * Reads the Reddit app's own screens to find usernames and hidden-profile messages.
 * It runs inside the Reddit app and only receives events from it.
 */
public class InsightsAccessibilityService extends AccessibilityService {
    private static InsightsAccessibilityService instance;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable scanRunnable = this::scanNow;
    private boolean scanPending;

    /** Asks the running service (if any) to scan the screen soon. */
    static void requestScan(long delayMs) {
        InsightsAccessibilityService service = instance;
        if (service != null) {
            service.scheduleScan(delayMs, true);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();
        UserInsights.init(this);
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        try {
            AccessibilityServiceInfo info = getServiceInfo();
            if (info != null) {
                info.packageNames = new String[]{getPackageName()};
                info.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                        | AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
                        | AccessibilityEvent.TYPE_VIEW_SCROLLED;
                info.notificationTimeout = 100;
                setServiceInfo(info);
            }
        } catch (Throwable t) {
            Log.w(UserInsights.TAG, "setServiceInfo failed", t);
        }
        ArcticShift.setOnDataChanged(() -> scheduleScan(60, true));
        scheduleScan(300, true);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || event.getPackageName() == null
                || !getPackageName().contentEquals(event.getPackageName())) {
            return;
        }
        int type = event.getEventType();
        if (type == AccessibilityEvent.TYPE_VIEW_SCROLLED) {
            Activity activity = UserInsights.currentActivity();
            if (activity != null && !UserInsights.isOwnScreen(activity)) {
                OverlayManager.hideTemporarily(activity);
            }
            // Wait until scrolling settles.
            scheduleScan(220, true);
        } else if (type == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            scheduleScan(350, true);
        } else {
            // Content changes can be constant (videos, animations): throttle instead of debounce.
            scheduleScan(250, false);
        }
    }

    private void scheduleScan(long delayMs, boolean restart) {
        if (scanPending && !restart) {
            return;
        }
        handler.removeCallbacks(scanRunnable);
        handler.postDelayed(scanRunnable, delayMs);
        scanPending = true;
    }

    private void scanNow() {
        scanPending = false;
        Activity activity = UserInsights.currentActivity();
        if (activity == null || UserInsights.isOwnScreen(activity)) {
            return;
        }
        if (!Prefs.badgesEnabled() && !Prefs.hiddenButtonEnabled()) {
            OverlayManager.clear(activity);
            return;
        }
        try {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null || root.getPackageName() == null
                    || !getPackageName().contentEquals(root.getPackageName())) {
                return;
            }
            boolean dump = Prefs.consumeDebugDump();
            float density = getResources().getDisplayMetrics().density;
            ScreenScanner.Result result = ScreenScanner.scan(root, dump, density);
            if (dump && result.dump != null) {
                copyDump(result);
            }
            OverlayManager.render(activity, result);
        } catch (Throwable t) {
            Log.e(UserInsights.TAG, "Scan failed", t);
        }
    }

    private void copyDump(ScreenScanner.Result result) {
        try {
            StringBuilder text = new StringBuilder(result.dump);
            text.append("\nDetected users: ");
            for (ScreenScanner.Hit hit : result.hits) {
                text.append(hit.user).append(hit.merged ? "(merged) " : " ");
            }
            text.append("\nHidden profile: ").append(result.hiddenUser);
            ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            clipboard.setPrimaryClip(ClipData.newPlainText("User Insights screen dump", text.toString()));
            Toast.makeText(this, "User Insights: screen layout copied", Toast.LENGTH_LONG).show();
        } catch (Throwable t) {
            Log.e(UserInsights.TAG, "Could not copy dump", t);
        }
    }

    @Override
    public void onInterrupt() {
    }

    @Override
    public void onDestroy() {
        handler.removeCallbacks(scanRunnable);
        if (instance == this) {
            instance = null;
        }
        ArcticShift.setOnDataChanged(null);
        Activity activity = UserInsights.currentActivity();
        if (activity != null) {
            OverlayManager.clear(activity);
        }
        super.onDestroy();
    }
}
