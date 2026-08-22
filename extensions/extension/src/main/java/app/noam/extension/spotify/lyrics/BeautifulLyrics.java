package app.noam.extension.spotify.lyrics;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

import app.noam.extension.spotify.Utils;
import app.noam.extension.spotify.localserver.ServerConfig;

/**
 * Restyles Spotify's full screen lyrics to read more like the desktop player's: larger type, more
 * room between lines, and a gradient drawn from the colour Spotify already derives from the artwork.
 *
 * Everything is done to the views Spotify built, after it has laid them out — no layout is replaced
 * and no lyrics are fetched, so if any assumption here is wrong the screen simply stays as it was.
 */
public final class BeautifulLyrics {

    /** How much larger the lyric lines are drawn. */
    private static final float TEXT_SCALE = 1.35f;

    /** Extra room between lines, as a multiplier of the line height. */
    private static final float LINE_SPACING = 1.18f;

    /** Lines Spotify has already dimmed are pushed further back. */
    private static final float INACTIVE_ALPHA = 0.45f;

    /** Below this, a text view is a header or a control rather than a lyric line. */
    private static final float MINIMUM_LYRIC_SP = 14f;

    private BeautifulLyrics() {}

    public static boolean isEnabled() {
        return ServerConfig.getBoolean(ServerConfig.KEY_BEAUTIFUL_LYRICS, false);
    }

    /** Called when Spotify's full screen lyrics page is created. */
    public static void onLyricsCreated(final Activity activity) {
        try {
            if (!isEnabled()) return;

            final View root = activity.getWindow().getDecorView();

            // The lines are recycled and restyled as the song plays, so reapply on every layout
            // pass rather than once.
            root.getViewTreeObserver().addOnGlobalLayoutListener(
                    new ViewTreeObserver.OnGlobalLayoutListener() {
                        @Override
                        public void onGlobalLayout() {
                            try {
                                restyle(root);
                            } catch (Throwable ex) {
                                root.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                                Utils.logError("Could not restyle the lyrics", ex);
                            }
                        }
                    });
        } catch (Throwable ex) {
            Utils.logError("Could not hook the lyrics screen", ex);
        }
    }

    private static void restyle(View root) {
        List<TextView> lines = new ArrayList<>();
        collectLyricLines(root, lines);
        if (lines.isEmpty()) return;

        // The brightest line is the one being sung; everything else is pushed back.
        int brightest = 0;
        for (TextView line : lines) {
            brightest = Math.max(brightest, brightness(line.getCurrentTextColor()));
        }

        for (TextView line : lines) {
            if (line.getTag(TAG_SCALED) == null) {
                line.setTextSize(TypedValue.COMPLEX_UNIT_PX, line.getTextSize() * TEXT_SCALE);
                line.setLineSpacing(0f, LINE_SPACING);
                line.setTag(TAG_SCALED, Boolean.TRUE);
            }

            boolean active = brightness(line.getCurrentTextColor()) >= brightest - 12;
            line.setAlpha(active ? 1f : INACTIVE_ALPHA);
        }

        applyGradient(root);
    }

    private static final int TAG_SCALED = 0x4d4f5250; // "MORP"
    private static final int TAG_GRADIENT = 0x4d4f5251;

    /** Collects the text views that are lyric lines, skipping the title and the controls. */
    private static void collectLyricLines(View view, List<TextView> into) {
        if (view instanceof TextView) {
            TextView text = (TextView) view;
            float sp = text.getTextSize() / text.getResources().getDisplayMetrics().scaledDensity;

            if (sp >= MINIMUM_LYRIC_SP && text.getText() != null && text.getText().length() > 0) {
                into.add(text);
            }
            return;
        }

        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                collectLyricLines(group.getChildAt(i), into);
            }
        }
    }

    /**
     * Lays a gradient over the flat backdrop, based on the colour Spotify already picked from the
     * artwork, so the page fades into darkness towards the controls.
     */
    private static void applyGradient(View root) {
        View backdrop = findColouredBackdrop(root);
        if (backdrop == null || backdrop.getTag(TAG_GRADIENT) != null) return;

        int base = ((ColorDrawable) backdrop.getBackground()).getColor();
        GradientDrawable gradient = new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{lighten(base, 0.18f), base, darken(base, 0.55f)});

        backdrop.setBackground(gradient);
        backdrop.setTag(TAG_GRADIENT, Boolean.TRUE);
    }

    /** @return the largest view whose background is the flat artwork colour. */
    private static View findColouredBackdrop(View view) {
        Drawable background = view.getBackground();
        if (background instanceof ColorDrawable
                && Color.alpha(((ColorDrawable) background).getColor()) == 255
                && view.getWidth() > 0
                && view.getHeight() > 0) {
            return view;
        }

        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                View found = findColouredBackdrop(group.getChildAt(i));
                if (found != null) return found;
            }
        }
        return null;
    }

    private static int brightness(int color) {
        return (Color.red(color) * 299 + Color.green(color) * 587 + Color.blue(color) * 114) / 1000;
    }

    private static int lighten(int color, float amount) {
        return Color.rgb(
                (int) (Color.red(color) + (255 - Color.red(color)) * amount),
                (int) (Color.green(color) + (255 - Color.green(color)) * amount),
                (int) (Color.blue(color) + (255 - Color.blue(color)) * amount));
    }

    private static int darken(int color, float amount) {
        float keep = 1f - amount;
        return Color.rgb(
                (int) (Color.red(color) * keep),
                (int) (Color.green(color) * keep),
                (int) (Color.blue(color) * keep));
    }
}
