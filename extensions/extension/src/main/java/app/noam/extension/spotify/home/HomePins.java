package app.noam.extension.spotify.home;

import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import app.noam.extension.spotify.Utils;
import app.noam.extension.spotify.localserver.ServerConfig;

/**
 * Keeps chosen shortcuts at the front of Spotify's home grid.
 *
 * The grid itself is filled by the server, so nothing is added or fetched here: the list Spotify is
 * about to render is simply reordered on the way past, using names kept on the device. If the model
 * does not look the way this expects, the list is left exactly as it came.
 */
public final class HomePins {

    public static final String KEY_PINS = "home_pins";

    /** The shortcut names last seen on Home, so the settings can offer them to pin. */
    public static final String KEY_SEEN = "home_seen";

    /** Marks a pinned shortcut in the grid, since Spotify draws no pin there itself. */
    private static final String PIN_MARK = "\uD83D\uDCCC ";

    private HomePins() {}

    public static Set<String> pinned() {
        Set<String> names = new LinkedHashSet<>();
        for (String line : ServerConfig.getString(KEY_PINS, "").split("\n")) {
            String name = line.trim();
            if (!name.isEmpty()) names.add(name.toLowerCase());
        }
        return names;
    }

    public static void setPinned(String text) {
        ServerConfig.putString(KEY_PINS, text == null ? "" : text);
    }

    /**
     * Called as the home shortcuts grid is about to render.
     *
     * @param grid  the grid element, used to reach its view and attach long press.
     * @param model the model carrying the shortcuts.
     */
    public static void bind(final Object grid, Object model) {
        lastModel = model;
        reorder(model);
        attachLongPress(grid);
    }

    /**
     * Gives each shortcut a long press that pins or unpins it.
     *
     * Spotify attaches no long press to this grid at all, so there is no menu to add an entry to;
     * this adds the gesture itself. The tile's own tap is left alone.
     */
    private static void attachLongPress(final Object grid) {
        try {
            if (grid == null) return;

            final View view = (View) grid.getClass().getMethod("getView").invoke(grid);
            if (view == null) return;

            view.post(new Runnable() {
                @Override
                public void run() {
                    try {
                        List<View> tiles = new ArrayList<>();
                        collectTiles(view, tiles);

                        List<String> names = seenNames();
                        for (int i = 0; i < tiles.size() && i < names.size(); i++) {
                            final String name = names.get(i);
                            View tile = tiles.get(i);

                            // Spotify rebinds these tiles when returning from a playlist, which
                            // drops anything written into the model. Reassert the marker here, and
                            // only ever add it: removing on this pass strips the marker from a
                            // pinned cover whenever a name and a tile fall out of step.
                            if (pinned().contains(name.toLowerCase())) markTileNow(tile, true);

                            if (Boolean.TRUE.equals(tile.getTag(TAG_BOUND))) continue;
                            tile.setTag(TAG_BOUND, Boolean.TRUE);

                            // Keep whatever Spotify already had here, so its own menu stays
                            // reachable instead of being replaced by the pin.
                            tile.setTag(TAG_ORIGINAL, existingLongClick(tile));

                            tile.setOnLongClickListener(new View.OnLongClickListener() {
                                @Override
                                public boolean onLongClick(View v) {
                                    showMenu(v, name);
                                    return true;
                                }
                            });
                        }
                    } catch (Throwable ex) {
                        Utils.logError("Could not attach long press to the home shortcuts", ex);
                    }
                }
            });
        } catch (Throwable ex) {
            Utils.logError("Could not reach the home shortcuts view", ex);
        }
    }

    private static final int TAG_BOUND = 0x4d4f5260;
    private static final int TAG_ORIGINAL = 0x4d4f5261;

    /**
     * Spotify's own long press handler for a cover, read before it is replaced.
     *
     * There is no public way to read a view's listener back, so this reaches the field the platform
     * keeps it in. If that is not reachable, the pin menu simply has no entry for Spotify's menu.
     */
    private static View.OnLongClickListener existingLongClick(View view) {
        try {
            java.lang.reflect.Field infoField = View.class.getDeclaredField("mListenerInfo");
            infoField.setAccessible(true);
            Object info = infoField.get(view);
            if (info == null) return null;

            java.lang.reflect.Field listenerField =
                    info.getClass().getDeclaredField("mOnLongClickListener");
            listenerField.setAccessible(true);
            Object listener = listenerField.get(info);

            if (listener instanceof View.OnLongClickListener) {
                Utils.log("Home pins: kept Spotify's own long press on a cover");
                return (View.OnLongClickListener) listener;
            }
        } catch (Throwable ex) {
            Utils.log("Home pins: no original long press to keep: " + ex);
        }
        return null;
    }

    /** The model last handed to the grid, so a pin can be applied without waiting for a refresh. */
    private static volatile Object lastModel;

    /**
     * Offers the pin as a menu entry on the cover, rather than acting on the press itself.
     *
     * Spotify attaches nothing to these covers, so there is no menu of its own to extend here; this
     * is the menu, and it leaves the cover's ordinary tap alone.
     */
    private static void showMenu(final View tile, final String name) {
        try {
            boolean isPinned = pinned().contains(name.toLowerCase());

            PopupMenu menu = new PopupMenu(tile.getContext(), tile);
            menu.getMenu().add(isPinned ? "Unpin from Home" : "Pin to Home")
                    .setOnMenuItemClickListener(new MenuItem.OnMenuItemClickListener() {
                        @Override
                        public boolean onMenuItemClick(MenuItem item) {
                            toggle(tile, name);
                            return true;
                        }
                    });

            // Spotify's own menu for the item, so adding the pin does not cost access to it.
            final Object original = tile.getTag(TAG_ORIGINAL);
            final View overflow = original == null ? findOverflow(tile) : null;

            if (original instanceof View.OnLongClickListener || overflow != null) {
                menu.getMenu().add("Spotify options")
                        .setOnMenuItemClickListener(new MenuItem.OnMenuItemClickListener() {
                            @Override
                            public boolean onMenuItemClick(MenuItem item) {
                                if (original instanceof View.OnLongClickListener) {
                                    ((View.OnLongClickListener) original).onLongClick(tile);
                                } else if (overflow != null) {
                                    overflow.performClick();
                                }
                                return true;
                            }
                        });
            }
            menu.show();
        } catch (Throwable ex) {
            Utils.logError("Could not show the pin menu", ex);
        }
    }

    /**
     * Moves the tile to the front of the grid on the spot.
     *
     * Reordering the model only shows up when Spotify next rebuilds the grid, which leaves a pin
     * looking like it did nothing. Moving the view puts it where it belongs immediately, and the
     * model keeps it there afterwards.
     */
    private static void moveTileNow(View tile, boolean nowPinned) {
        try {
            if (!(tile.getParent() instanceof ViewGroup)) return;
            ViewGroup parent = (ViewGroup) tile.getParent();

            // A recycling list owns the position of its children; moving them by hand corrupts it.
            if (parent.getClass().getName().contains("RecyclerView")) return;

            int target = nowPinned ? 0 : parent.getChildCount() - 1;
            if (parent.indexOfChild(tile) == target) return;

            parent.removeView(tile);
            parent.addView(tile, target);
            Utils.log("Home pins: tile moved to position " + target);
        } catch (Throwable ex) {
            Utils.log("Home pins: could not move the tile: " + ex);
        }
    }

    /** @return the tile's own context button, the one Spotify draws as three dots. */
    private static View findOverflow(View view) {
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                View child = group.getChildAt(i);

                CharSequence description = child.getContentDescription();
                if (child.isClickable() && description != null) {
                    String text = description.toString().toLowerCase();
                    if (text.contains("more") || text.contains("option") || text.contains("context")) {
                        return child;
                    }
                }

                View found = findOverflow(child);
                if (found != null) return found;
            }
        }
        return null;
    }

    /** Pins or unpins a shortcut and tells the user which happened. */
    private static void toggle(View anchor, String name) {
        try {
            Set<String> pins = pinned();
            String key = name.toLowerCase();

            boolean nowPinned;
            if (pins.remove(key)) {
                nowPinned = false;
            } else {
                pins.add(key);
                nowPinned = true;
            }

            StringBuilder text = new StringBuilder();
            for (String pin : pins) text.append(pin).append('\n');
            setPinned(text.toString());

            // Update the tile in front of the user straight away. Spotify rebuilds this grid on
            // its own schedule, so waiting for that leaves the tap looking like it did nothing.
            markTileNow(anchor, nowPinned);
            moveTileNow(anchor, nowPinned);

            reorder(lastModel);
            refreshGrid(anchor);

            Toast.makeText(anchor.getContext(),
                    (nowPinned ? "Pinned " : "Unpinned ") + name,
                    Toast.LENGTH_SHORT).show();
            Utils.log("Home pins: " + (nowPinned ? "pinned " : "unpinned ") + name);
        } catch (Throwable ex) {
            Utils.logError("Could not toggle the pin", ex);
        }
    }

    /** Adds or removes the pin on the tile that was just tapped, without waiting for a rebuild. */
    private static void markTileNow(View tile, boolean nowPinned) {
        try {
            List<TextView> labels = new ArrayList<>();
            collectLabels(tile, labels);

            for (TextView label : labels) {
                String text = label.getText() == null ? "" : label.getText().toString();
                if (text.isEmpty()) continue;

                if (nowPinned && !text.startsWith(PIN_MARK)) {
                    label.setText(PIN_MARK + text);
                } else if (!nowPinned && text.startsWith(PIN_MARK)) {
                    label.setText(text.substring(PIN_MARK.length()));
                }
                return;
            }
        } catch (Throwable ex) {
            Utils.log("Home pins: could not update the tile: " + ex);
        }
    }

    private static void collectLabels(View view, List<TextView> into) {
        if (view instanceof TextView) {
            into.add((TextView) view);
            return;
        }
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) collectLabels(group.getChildAt(i), into);
        }
    }

    /** Redraws the grid so a pin takes effect on the spot. */
    private static void refreshGrid(View tile) {
        try {
            ViewGroup parent = (ViewGroup) tile.getParent();
            while (parent != null) {
                try {
                    Object adapter = parent.getClass().getMethod("getAdapter").invoke(parent);
                    if (adapter != null) {
                        adapter.getClass().getMethod("notifyDataSetChanged").invoke(adapter);
                        Utils.log("Home pins: grid redrawn");
                        return;
                    }
                } catch (NoSuchMethodException notAList) {
                    // Keep walking up; this one is a plain group.
                }
                parent = parent.getParent() instanceof ViewGroup
                        ? (ViewGroup) parent.getParent()
                        : null;
            }
        } catch (Throwable ex) {
            Utils.log("Home pins: could not redraw the grid: " + ex);
        }
    }

    /** The tiles of the grid, in the order they are shown. */
    private static void collectTiles(View view, List<View> into) {
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;

            // A grid's tiles are the children of whichever group holds several of them.
            if (group.getChildCount() > 2 && group.getChildAt(0) instanceof ViewGroup) {
                for (int i = 0; i < group.getChildCount(); i++) into.add(group.getChildAt(i));
                return;
            }
            for (int i = 0; i < group.getChildCount(); i++) {
                collectTiles(group.getChildAt(i), into);
                if (!into.isEmpty()) return;
            }
        }
    }

    public static List<String> seenNames() {
        List<String> names = new ArrayList<>();
        for (String line : ServerConfig.getString(KEY_SEEN, "").split("\n")) {
            if (!line.trim().isEmpty()) names.add(line.trim());
        }
        return names;
    }

    /** Called as the home shortcuts grid is about to render. */
    public static void reorder(Object model) {
        try {
            Set<String> pins = pinned();
            Utils.log("Home pins: grid rendering, " + pins.size() + " pinned, model "
                    + (model == null ? "null" : model.getClass().getName()));
            if (pins.isEmpty() || model == null) return;

            Field[] fields = model.getClass().getDeclaredFields();
            StringBuilder shape = new StringBuilder();
            for (Field f : fields) shape.append(f.getName()).append(':').append(f.getType().getSimpleName()).append(' ');
            Utils.log("Home pins: model fields = " + shape);

            for (Field field : fields) {
                if (!List.class.isAssignableFrom(field.getType())) continue;

                field.setAccessible(true);
                Object value = field.get(model);
                if (!(value instanceof List)) continue;

                @SuppressWarnings("unchecked")
                List<Object> items = (List<Object>) value;
                if (items.size() < 2) continue;

                List<Object> front = new ArrayList<>();
                List<Object> rest = new ArrayList<>();
                for (Object item : items) {
                    if (isPinned(item, pins)) {
                        front.add(item);
                    } else {
                        rest.add(item);
                    }
                }

                Utils.log("Home pins: list field " + field.getName() + " has " + items.size()
                        + " items, " + front.size() + " matched");
                if (front.isEmpty() || front.size() == items.size()) {
                    // Nothing moved, so what is shown is what arrived.
                    rememberNames(items);
                    continue;
                }

                for (Object item : front.subList(0, front.size() - rest.size() >= 0
                        ? front.size() - rest.size() : front.size())) {
                    markPinned(item);
                }

                front.addAll(rest);
                try {
                    items.clear();
                    items.addAll(front);
                } catch (UnsupportedOperationException immutable) {
                    field.set(model, front);
                }

                // Record the names in the order they are actually drawn, so a long press on the
                // third tile pins the third playlist rather than whatever the server sent third.
                rememberNames(front);
                Utils.log("Home pins: moved " + (items.size() - rest.size()) + " shortcuts to the front");
                return;
            }
        } catch (Throwable ex) {
            Utils.logError("Could not reorder the home shortcuts", ex);
        }
    }

    /** @return true when a title was found on this object and marked. */
    private static boolean markIn(Object holder) throws IllegalAccessException {
        for (Field field : holder.getClass().getDeclaredFields()) {
            if (field.getType() != String.class) continue;

            field.setAccessible(true);
            Object value = field.get(holder);
            if (!(value instanceof String)) continue;

            String text = (String) value;
            if (text.isEmpty() || text.startsWith("spotify:") || text.startsWith(PIN_MARK)
                    || text.length() > 60) {
                continue;
            }

            field.set(holder, PIN_MARK + text);
            Utils.log("Home pins: marked \"" + text + "\" on " + holder.getClass().getSimpleName());
            return true;
        }
        return false;
    }

    /** Records the names on the grid, so the settings screen can list them to pin. */
    private static void rememberNames(List<Object> items) {
        StringBuilder names = new StringBuilder();
        for (Object item : items) {
            for (String text : textOf(item)) {
                String name = text.trim();
                if (name.isEmpty() || name.startsWith("spotify:") || name.length() > 60) continue;
                names.append(name.replace(PIN_MARK, "")).append('\n');
                break;
            }
        }
        ServerConfig.putString(KEY_SEEN, names.toString());
    }

    private static boolean reportedShape;

    /** Puts a pin in front of a pinned shortcut's title, so it reads as pinned on the grid. */
    private static void markPinned(Object item) {
        try {
            if (!reportedShape) {
                reportedShape = true;
                StringBuilder shape = new StringBuilder("Home pins: shortcut "
                        + item.getClass().getName() + " -> ");
                for (Field f : item.getClass().getDeclaredFields()) {
                    f.setAccessible(true);
                    Object v = f.get(item);
                    shape.append(f.getName()).append('=')
                            .append(v == null ? "null"
                                    : v instanceof String ? "\"" + v + "\""
                                    : v.getClass().getSimpleName())
                            .append(' ');
                }
                Utils.log(shape.toString());
            }

            // The shortcut's text lives inside a nested model rather than on the item itself.
            if (markIn(item)) return;
            for (Field field : item.getClass().getDeclaredFields()) {
                field.setAccessible(true);
                Object value = field.get(item);
                if (value != null && !isPlain(value) && markIn(value)) return;
            }
        } catch (Throwable ex) {
            // The shortcut simply renders without the marker.
            Utils.log("Home pins: could not mark a shortcut: " + ex);
        }
    }


    /** A shortcut counts as pinned when any of its text carries a pinned name. */
    private static boolean isPinned(Object item, Set<String> pins) {
        if (item == null) return false;

        // Any of the strings the item carries may be the title; exact matching makes checking all
        // of them safe, where substring matching dragged in unrelated shortcuts.
        for (String text : textOf(item)) {
            String lower = text.replace(PIN_MARK, "").trim().toLowerCase();
            // Exact names only. Substring matching kept dragging unrelated shortcuts along with a
            // pin, because these items carry several strings and some are far from unique.
            for (String pin : pins) {
                if (lower.equals(pin)) return true;
            }
        }
        return false;
    }

    /** Collects the strings a shortcut carries, one level deep. */
    private static List<String> textOf(Object item) {
        List<String> texts = new ArrayList<>();
        try {
            for (Field field : item.getClass().getDeclaredFields()) {
                field.setAccessible(true);
                Object value = field.get(item);

                if (value instanceof String) {
                    texts.add((String) value);
                } else if (value != null && !isPlain(value)) {
                    for (Field inner : value.getClass().getDeclaredFields()) {
                        inner.setAccessible(true);
                        Object nested = inner.get(value);
                        if (nested instanceof String) texts.add((String) nested);
                    }
                }
            }
        } catch (Throwable ignored) {
            // A shortcut whose fields cannot be read simply never matches.
        }
        return texts;
    }

    private static boolean isPlain(Object value) {
        if (value instanceof Number || value instanceof Boolean || value instanceof Character) {
            return true;
        }
        String type = value.getClass().getName();
        return type.startsWith("java.") || type.startsWith("kotlin.") || type.startsWith("android.");
    }
}
