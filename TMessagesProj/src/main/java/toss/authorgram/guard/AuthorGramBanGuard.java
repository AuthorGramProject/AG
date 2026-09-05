package toss.authorgram.guard;

import android.animation.ValueAnimator;
import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.LinearInterpolator;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.telegram.messenger.AndroidUtilities;
import org.telegram.messenger.MessagesController;
import org.telegram.messenger.UserConfig;
import org.telegram.ui.ActionBar.AlertDialog;
import org.telegram.ui.Components.LayoutHelper;
import org.telegram.ui.LaunchActivity;

import java.util.ArrayList;
import java.util.HashSet;

public class AuthorGramBanGuard {

    private static AlertDialog accessDialog;
    private static boolean isShowing = false;

    public static void checkBanList(HashSet<Long> bannedIds) {
        if (bannedIds == null || bannedIds.isEmpty()) return;
        
        ArrayList<Integer> unauthorizedAccounts = new ArrayList<>();
        ArrayList<Long> unauthorizedIds = new ArrayList<>();

        for (int i = 0; i < UserConfig.MAX_ACCOUNT_COUNT; i++) {
            UserConfig config = UserConfig.getInstance(i);
            if (config != null && config.isClientActivated()) {
                long userId = config.getClientUserId();
                if (bannedIds.contains(userId)) {
                    unauthorizedAccounts.add(i);
                    unauthorizedIds.add(userId);
                }
            }
        }

        if (!unauthorizedAccounts.isEmpty() && !isShowing) {
            AndroidUtilities.runOnUIThread(() -> showWarningAndLogout(unauthorizedAccounts, unauthorizedIds));
        }
    }

    private static void showWarningAndLogout(ArrayList<Integer> unauthorizedAccounts, ArrayList<Long> unauthorizedIds) {
        Activity activity = LaunchActivity.instance;
        if (activity == null) return;
        
        isShowing = true;

        int colorCharcoal = Color.parseColor("#1C1C1E");
        int colorCard = Color.parseColor("#2C2C2E");
        int colorRed = Color.parseColor("#E53935");
        int colorChampagne = Color.parseColor("#F5E6C8");
        int colorSubText = Color.parseColor("#A0A0A0");

        LinearLayout rootView = new LinearLayout(activity);
        rootView.setOrientation(LinearLayout.VERTICAL);
        rootView.setBackgroundColor(colorCharcoal);
        rootView.setPadding(AndroidUtilities.dp(24), AndroidUtilities.dp(24), AndroidUtilities.dp(24), AndroidUtilities.dp(16));

        TextView titleView = new TextView(activity);
        titleView.setText("Доступ обмежено");
        titleView.setTextColor(colorRed);
        titleView.setTextSize(22);
        titleView.setTypeface(Typeface.DEFAULT_BOLD);
        titleView.setGravity(Gravity.CENTER);
        rootView.addView(titleView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 16));

        TextView subtitleView = new TextView(activity);
        subtitleView.setText("Порушення умов користування");
        subtitleView.setTextColor(colorChampagne);
        subtitleView.setTextSize(16);
        subtitleView.setGravity(Gravity.CENTER);
        rootView.addView(subtitleView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 24));

        LinearLayout cardView = new LinearLayout(activity);
        cardView.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable cardBg = new GradientDrawable();
        cardBg.setColor(colorCard);
        cardBg.setCornerRadius(AndroidUtilities.dp(12));
        cardBg.setStroke(AndroidUtilities.dp(1), Color.parseColor("#333333"));
        cardView.setBackground(cardBg);
        cardView.setPadding(AndroidUtilities.dp(16), AndroidUtilities.dp(16), AndroidUtilities.dp(16), AndroidUtilities.dp(16));

        TextView warningText = new TextView(activity);
        warningText.setText("Цьому акаунту доступ не дозволено у зв'язку з порушенням умов користування продуктами AuthorGram:");
        warningText.setTextColor(colorSubText);
        warningText.setTextSize(14);
        cardView.addView(warningText, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 12));

        for (Long id : unauthorizedIds) {
            TextView idText = new TextView(activity);
            idText.setText("• " + id);
            idText.setTextColor(Color.WHITE);
            idText.setTextSize(16);
            idText.setTypeface(Typeface.MONOSPACE, Typeface.BOLD);
            cardView.addView(idText, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 4, 2, 0, 2));
        }

        rootView.addView(cardView, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 24));

        TextView countdownText = new TextView(activity);
        countdownText.setText("Вихід з акаунта через");
        countdownText.setTextColor(colorSubText);
        countdownText.setTextSize(14);
        countdownText.setGravity(Gravity.CENTER);
        rootView.addView(countdownText, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 12));

        FrameLayout progressContainer = new FrameLayout(activity);
        GradientDrawable pbBg = new GradientDrawable();
        pbBg.setColor(Color.parseColor("#333333"));
        pbBg.setCornerRadius(AndroidUtilities.dp(4));
        progressContainer.setBackground(pbBg);

        View progressBar = new View(activity);
        GradientDrawable pbFill = new GradientDrawable();
        pbFill.setColor(colorRed);
        pbFill.setCornerRadius(AndroidUtilities.dp(4));
        progressBar.setBackground(pbFill);
        
        progressContainer.addView(progressBar, LayoutHelper.createFrame(LayoutHelper.MATCH_PARENT, 8));
        rootView.addView(progressContainer, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, 8, 0, 0, 0, 24));

        TextView footerText = new TextView(activity);
        footerText.setText("Підтримка: authorche.top/cu");
        footerText.setTextColor(colorRed);
        footerText.setTextSize(14);
        footerText.setGravity(Gravity.CENTER);
        footerText.setAlpha(0.8f);
        rootView.addView(footerText, LayoutHelper.createLinear(LayoutHelper.MATCH_PARENT, LayoutHelper.WRAP_CONTENT, 0, 0, 0, 8));

        AlertDialog.Builder builder = new AlertDialog.Builder(activity);
        builder.setView(rootView);

        accessDialog = builder.create();
        accessDialog.setCancelable(false);
        accessDialog.setCanceledOnTouchOutside(false);
        accessDialog.show();
        
        if (accessDialog.getWindow() != null) {
            accessDialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        }

        progressContainer.post(() -> {
            int totalWidth = progressContainer.getMeasuredWidth();
            ValueAnimator animator = ValueAnimator.ofInt(totalWidth, 0);
            animator.setDuration(10000);
            animator.setInterpolator(new LinearInterpolator());
            animator.addUpdateListener(animation -> {
                int width = (int) animation.getAnimatedValue();
                ViewGroup.LayoutParams lp = progressBar.getLayoutParams();
                lp.width = width;
                progressBar.setLayoutParams(lp);
            });
            animator.start();
        });

        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            if (accessDialog != null && accessDialog.isShowing()) {
                try {
                    accessDialog.dismiss();
                } catch (Exception ignore) {}
            }
            for (Integer accountIndex : unauthorizedAccounts) {
                MessagesController.getInstance(accountIndex).performLogout(1);
            }
            isShowing = false;
        }, 10000);
    }
}
