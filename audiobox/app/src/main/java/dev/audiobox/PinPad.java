package dev.audiobox;

import android.app.Dialog;
import android.content.Context;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.Button;
import android.widget.GridLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * A 4-digit PIN pad with its own big keys (the on-screen keyboard is disabled on
 * the box). Used to enter the admin screen and to set a new PIN (twice).
 */
final class PinPad extends Dialog {
    interface Done {
        void onPin(String pin);
    }

    private final TextView prompt, dots;
    private final StringBuilder entered = new StringBuilder();
    private final Done done;

    PinPad(Context c, String title, Done done) {
        super(c);
        this.done = done;
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        getWindow().setBackgroundDrawable(new ColorDrawable(0));

        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.setPadding(24, 16, 24, 16);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(Ui.PANEL);
        bg.setCornerRadius(24);
        box.setBackground(bg);

        prompt = Ui.text(c, 22, Ui.TEXT);
        prompt.setText(title);
        prompt.setGravity(Gravity.CENTER);
        box.addView(prompt);
        dots = Ui.text(c, 30, Ui.ACCENT);
        dots.setGravity(Gravity.CENTER);
        box.addView(dots, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 44));

        GridLayout keys = new GridLayout(c);
        keys.setColumnCount(6);
        String[] labels = {"1", "2", "3", "4", "5", "6", "7", "8", "9", "0", "←", "✕"};
        for (String l : labels) {
            Button b = new Button(c);
            b.setText(l);
            b.setTextSize(24);
            b.setTypeface(Ui.font(c));
            b.setTextColor(Ui.TEXT);
            GradientDrawable kb = new GradientDrawable();
            kb.setColor(Ui.BG);
            kb.setCornerRadius(14);
            b.setBackground(kb);
            GridLayout.LayoutParams lp = new GridLayout.LayoutParams();
            lp.width = 76;
            lp.height = 64;
            lp.setMargins(5, 5, 5, 5);
            keys.addView(b, lp);
            b.setOnClickListener(v -> press(l));
        }
        box.addView(keys);
        setContentView(box);
        refresh();
    }

    private void press(String key) {
        if ("✕".equals(key)) {
            dismiss();
        } else if ("←".equals(key)) {
            if (entered.length() > 0) entered.setLength(entered.length() - 1);
        } else if (entered.length() < 4) {
            entered.append(key);
            if (entered.length() == 4) {
                String pin = entered.toString();
                entered.setLength(0);
                done.onPin(pin);
            }
        }
        refresh();
    }

    void setPrompt(String text) {
        prompt.setText(text);
    }

    private void refresh() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 4; i++) sb.append(i < entered.length() ? "● " : "○ ");
        dots.setText(sb.toString().trim());
    }
}
