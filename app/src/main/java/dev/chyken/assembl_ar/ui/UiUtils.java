package dev.chyken.assembl_ar.ui;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.view.View;
import android.view.Window;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;

import dev.chyken.assembl_ar.R;
import dev.chyken.assembl_ar.AdminAccessActivity;
import dev.chyken.assembl_ar.ComponentLibraryActivity;
import dev.chyken.assembl_ar.LearningActivity;
import dev.chyken.assembl_ar.MainActivity;

public final class UiUtils {
    private UiUtils() {
    }

    public static void configureStandardWindow(Activity activity) {
        Window window = activity.getWindow();
        // Edge-to-edge is enforced on current Android versions, so standard
        // screens apply the system-bar insets to their content explicitly.
        WindowCompat.setDecorFitsSystemWindows(window, false);
        window.setStatusBarColor(activity.getColor(R.color.canvas));
        window.setNavigationBarColor(activity.getColor(R.color.canvas));
        WindowCompat.getInsetsController(window, window.getDecorView()).setAppearanceLightStatusBars(true);
        WindowCompat.getInsetsController(window, window.getDecorView()).setAppearanceLightNavigationBars(true);
    }

    /** Keeps a standard screen's content clear of system bars and display cutouts. */
    public static void applyStandardInsets(Activity activity) {
        View content = activity.findViewById(android.R.id.content);
        if (content == null || !(content instanceof android.view.ViewGroup)
                || ((android.view.ViewGroup) content).getChildCount() == 0) {
            return;
        }

        View root = ((android.view.ViewGroup) content).getChildAt(0);
        int initialLeft = root.getPaddingLeft();
        int initialTop = root.getPaddingTop();
        int initialRight = root.getPaddingRight();
        int initialBottom = root.getPaddingBottom();
        ViewCompat.setOnApplyWindowInsetsListener(root, (view, insets) -> {
            Insets safeArea = insets.getInsets(
                    WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            view.setPadding(
                    initialLeft + safeArea.left,
                    initialTop + safeArea.top,
                    initialRight + safeArea.right,
                    initialBottom + safeArea.bottom);
            return insets;
        });
        ViewCompat.requestApplyInsets(root);
    }

    public static void configureCameraWindow(Activity activity, View topView, View bottomView) {
        Window window = activity.getWindow();
        int originalStartPadding = ViewCompat.getPaddingStart(topView);
        int originalEndPadding = ViewCompat.getPaddingEnd(topView);
        WindowCompat.setDecorFitsSystemWindows(window, false);
        window.setStatusBarColor(Color.TRANSPARENT);
        window.setNavigationBarColor(activity.getColor(R.color.camera_scrim));
        WindowCompat.getInsetsController(window, window.getDecorView()).setAppearanceLightStatusBars(false);
        WindowCompat.getInsetsController(window, window.getDecorView()).setAppearanceLightNavigationBars(false);

        ViewCompat.setOnApplyWindowInsetsListener(window.getDecorView(), (view, insets) -> {
            Insets bars = insets.getInsets(
                    WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            boolean rtl = ViewCompat.getLayoutDirection(topView)
                    == ViewCompat.LAYOUT_DIRECTION_RTL;
            int startInset = rtl ? bars.right : bars.left;
            int endInset = rtl ? bars.left : bars.right;
            ViewCompat.setPaddingRelative(topView,
                    originalStartPadding + startInset,
                    bars.top + dp(activity, 8),
                    originalEndPadding + endInset,
                    topView.getPaddingBottom());
            if (bottomView != null) {
                bottomView.setTranslationY(-bars.bottom);
            }
            return insets;
        });
        ViewCompat.requestApplyInsets(window.getDecorView());
    }

    public static void setupToolbar(AppCompatActivity activity, String title) {
        View back = activity.findViewById(R.id.backButton);
        android.widget.TextView titleView = activity.findViewById(R.id.toolbarTitle);
        if (titleView != null) {
            titleView.setText(title);
        }
        if (back != null) {
            back.setOnClickListener(view -> activity.getOnBackPressedDispatcher().onBackPressed());
        }
    }

    /** Wires the four persistent top-level destinations used on phone and tablet layouts. */
    public static void setupPrimaryNavigation(AppCompatActivity activity, int selectedItemId) {
        int[] itemIds = {
                R.id.navAssemblyButton,
                R.id.navLearningButton,
                R.id.navComponentsButton,
                R.id.navAdminButton
        };
        for (int itemId : itemIds) {
            View item = activity.findViewById(itemId);
            if (item != null) {
                item.setSelected(itemId == selectedItemId);
            }
        }

        // The landing page is the root of the Assemble section. Choosing a project remains an
        // explicit action on that page rather than a side effect of reselecting its tab.
        wireDestination(activity, R.id.navAssemblyButton, MainActivity.class);
        wireDestination(activity, R.id.navLearningButton, LearningActivity.class);
        wireDestination(activity, R.id.navComponentsButton, ComponentLibraryActivity.class);
        wireDestination(activity, R.id.navAdminButton, AdminAccessActivity.class);
    }

    private static void wireDestination(
            AppCompatActivity activity,
            int viewId,
            Class<? extends Activity> destination) {
        View item = activity.findViewById(viewId);
        if (item == null) {
            return;
        }
        item.setOnClickListener(view -> {
            if (destination.isInstance(activity)) {
                return;
            }
            Intent intent = new Intent(activity, destination)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            activity.startActivity(intent);
            activity.overridePendingTransition(BrandingAssets.motion()?R.anim.tab_enter:0,BrandingAssets.motion()?R.anim.tab_exit:0);

            // Keep the landing page as the stable task root, but replace peer tab Activities.
            // This prevents Back from walking through every previous tab selection.
            if (!(activity instanceof MainActivity)) {
                activity.finish();
            }
        });
    }

    public static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    public static boolean isTablet(Context context) {
        return context.getResources().getConfiguration().smallestScreenWidthDp >= 600;
    }

    /** True when the current tablet window is wide enough for a comfortable two-column card. */
    public static boolean isExpandedTablet(Context context) {
        return isTablet(context)
                && context.getResources().getConfiguration().screenWidthDp >= 720;
    }

    public static boolean isLargeTabletWindow(Context context) {
        return isTablet(context)
                && context.getResources().getConfiguration().screenWidthDp >= 840;
    }

    public static String nonBlank(@Nullable CharSequence value) {
        return value == null ? "" : value.toString().trim();
    }
}
