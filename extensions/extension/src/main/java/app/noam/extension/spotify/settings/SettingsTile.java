package app.noam.extension.spotify.settings;

import android.content.Context;
import android.content.Intent;

import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import app.noam.extension.spotify.Utils;

/**
 * Builds the "Morphe" row and appends it to one of Spotify's settings sections.
 *
 * Spotify's settings model is fully obfuscated and its names change with every release, so nothing
 * here is referenced by name. Instead the patch bakes in the two class names it identified
 * structurally at patch time, and everything else is copied from a row that is already in the list.
 * If any assumption fails the original list is returned unchanged, so a broken tile can never stop
 * Spotify's settings screen from rendering.
 */
public final class SettingsTile {

    private SettingsTile() {}

    /** Patched to return the settings-row-action holder class, e.g. {@code p.i5g0}. */
    private static String navigationHolderClassName() {
        return "";
    }

    /** Patched to return the destination-action class, e.g. {@code p.g5g0}. */
    private static String destinationActionClassName() {
        return "";
    }

    /**
     * The destination Spotify is asked to navigate to when the Morphe row is tapped.
     *
     * Spotify's settings rows do not take a click listener: whichever action a row carries, it ends
     * up producing a destination string that is handed to the navigator. So the row carries this
     * sentinel, and the patch intercepts it on the way to the navigator.
     */
    private static final String MORPHE_DESTINATION = "morphe:settings";

    /**
     * Called with every settings destination before Spotify navigates to it.
     *
     * @return the destination to actually navigate to.
     */
    public static String rewriteDestination(String destination) {
        try {
            if (MORPHE_DESTINATION.equals(destination)) {
                Context context = Utils.getContext();
                if (context != null) {
                    Intent intent = new Intent(context, MorpheSettingsActivity.class);
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    context.startActivity(intent);
                }
            }
        } catch (Throwable ex) {
            Utils.logError("Could not open the Morphe settings screen", ex);
        }
        return destination;
    }

    /**
     * Called from Spotify's settings section builder with that section's array of rows.
     *
     * @return the same rows plus the Morphe row, or the untouched array if the row cannot be built.
     */
    public static Object[] extend(Object[] rows) {
        try {
            Object tile = buildTile(rows);

            // The array keeps its original component type, so the caller's list builder sees exactly
            // the array type it expects.
            Object[] extended = (Object[]) Array.newInstance(
                    rows.getClass().getComponentType(), rows.length + 1);
            System.arraycopy(rows, 0, extended, 0, rows.length);
            extended[rows.length] = tile;
            return extended;
        } catch (Throwable ex) {
            Utils.logError("Could not add the Morphe settings tile", ex);
            return rows;
        }
    }

    private static Object buildTile(Object[] items) throws Exception {
        Class<?> holderClass = Class.forName(navigationHolderClassName());
        Class<?> actionClass = Class.forName(destinationActionClassName());

        // A row from this very list is the template: it supplies the obfuscated row class, the row
        // "kind" value, and the navigation metadata, none of which can be named at compile time.
        Object template = null;
        Field templateHolderField = null;
        for (Object item : items) {
            for (Field field : item.getClass().getDeclaredFields()) {
                field.setAccessible(true);
                Object value = field.get(item);
                if (value != null && holderClass.isInstance(value)) {
                    template = item;
                    templateHolderField = field;
                    break;
                }
            }
            if (template != null) break;
        }
        if (template == null) throw new IllegalStateException("no navigation row to use as a template");

        Object templateHolder = templateHolderField.get(template);

        // The holder pairs navigation metadata with an action. Reuse the template's metadata object
        // and swap in our own action, so tapping the row runs our code instead of navigating.
        Constructor<?> holderConstructor = constructorWithParameterCount(holderClass, 2);
        Object navigationMetadata = firstFieldAssignableTo(
                templateHolder, holderConstructor.getParameterTypes()[0]);

        // The action simply carries the destination; nothing here has to satisfy a Kotlin lambda type.
        Constructor<?> actionConstructor = actionClass.getDeclaredConstructor(String.class);
        actionConstructor.setAccessible(true);

        Object action = actionConstructor.newInstance(MORPHE_DESTINATION);
        Object accessor = holderConstructor.newInstance(navigationMetadata, action);

        return buildRow(template, items, accessor);
    }

    /**
     * Builds the Morphe row from the rows already in the section.
     *
     * A settings row carries both per-row values (its title, and the condition deciding whether it is
     * shown at all) and values every row in a section shares, such as the row kind the renderer
     * switches on. Only the shared ones may be copied: inheriting a neighbour's visibility condition
     * would hide the Morphe row whenever that neighbour happens to be hidden. A value is treated as
     * shared when the same instance appears in at least two rows.
     */
    private static Object buildRow(Object template, Object[] rows, Object accessor) throws Exception {
        Class<?> rowClass = template.getClass();

        Constructor<?> rowConstructor = null;
        for (Constructor<?> candidate : rowClass.getDeclaredConstructors()) {
            Class<?>[] parameters = candidate.getParameterTypes();
            if (parameters.length >= 10 && parameters[0] == String.class
                    && (rowConstructor == null
                        || parameters.length > rowConstructor.getParameterTypes().length)) {
                rowConstructor = candidate;
            }
        }
        if (rowConstructor == null) throw new IllegalStateException("no usable settings row constructor");
        rowConstructor.setAccessible(true);

        Field[] fields = rowClass.getDeclaredFields();
        Object[] shared = new Object[fields.length];
        for (int i = 0; i < fields.length; i++) {
            fields[i].setAccessible(true);
            shared[i] = sharedValue(fields[i], rows, rowClass);
        }

        Class<?>[] parameterTypes = rowConstructor.getParameterTypes();
        Object[] arguments = new Object[parameterTypes.length];
        Map<Class<?>, Integer> seenPerType = new HashMap<>();

        for (int i = 0; i < parameterTypes.length; i++) {
            Class<?> parameterType = parameterTypes[i];
            arguments[i] = defaultFor(parameterType);
            if (parameterType.isPrimitive()) continue;

            // Constructor parameters and fields line up per type, which holds even though the overall
            // field order is not guaranteed.
            Integer previous = seenPerType.get(parameterType);
            int occurrence = previous == null ? 0 : previous;
            seenPerType.put(parameterType, occurrence + 1);
            int seen = 0;
            for (int f = 0; f < fields.length; f++) {
                if (fields[f].getType() != parameterType) continue;
                if (seen++ != occurrence) continue;

                if (isFlow(parameterType)) {
                    // A row's flows are its own objects, never shared, so the shared-value rule would
                    // leave them null — and the renderer collects one of them without a null check.
                    Object templateFlow = fields[f].get(template);
                    arguments[i] = templateFlow == null ? null : flowOfTrue(templateFlow);
                } else {
                    arguments[i] = shared[f];
                }
                break;
            }
        }

        arguments[0] = "morphe";
        arguments[1] = MorpheSettingsActivity.titleResourceId();
        arguments[2] = MorpheSettingsActivity.descriptionResourceId();
        arguments[accessorParameterIndex(parameterTypes, accessor)] = accessor;

        return rowConstructor.newInstance(arguments);
    }

    /** A settings flow, which the renderer collects to decide whether a row is shown and enabled. */
    private static boolean isFlow(Class<?> type) {
        if (!type.isInterface()) return false;
        for (Method method : type.getMethods()) {
            if (method.getName().equals("collect")) return true;
        }
        return false;
    }

    /**
     * Builds a flow that emits true, by cloning the template's own flow object.
     *
     * These flows are constant-valued and compile to a shared class taking the value and a tag
     * identifying which flow it stands for, so keeping the tag and swapping the value yields a flow
     * of the same kind that reports the Morphe row as available.
     */
    private static Object flowOfTrue(Object templateFlow) {
        try {
            Class<?> flowClass = templateFlow.getClass();

            int tag = 0;
            for (Field field : flowClass.getDeclaredFields()) {
                if (field.getType() == int.class) {
                    field.setAccessible(true);
                    tag = field.getInt(templateFlow);
                    break;
                }
            }

            Constructor<?> constructor = flowClass.getDeclaredConstructor(Object.class, int.class);
            constructor.setAccessible(true);
            return constructor.newInstance(Boolean.TRUE, tag);
        } catch (Throwable ex) {
            // Falling back to the template's flow keeps the row rendering, following its neighbour.
            Utils.log("Could not build the Morphe row flow: " + ex);
            return templateFlow;
        }
    }

    /** @return the value held by [field] in at least two rows, or null if every row differs. */
    private static Object sharedValue(Field field, Object[] rows, Class<?> rowClass) throws Exception {
        for (int i = 0; i < rows.length; i++) {
            if (rows[i] == null || rows[i].getClass() != rowClass) continue;
            Object value = field.get(rows[i]);
            if (value == null) continue;

            for (int j = i + 1; j < rows.length; j++) {
                if (rows[j] == null || rows[j].getClass() != rowClass) continue;
                if (field.get(rows[j]) == value) return value;
            }
        }
        return null;
    }

    private static int accessorParameterIndex(Class<?>[] parameterTypes, Object accessor) {
        for (int i = 0; i < parameterTypes.length; i++) {
            if (parameterTypes[i].isInstance(accessor)) return i;
        }
        throw new IllegalStateException("settings row does not accept the action accessor");
    }

    private static Constructor<?> constructorWithParameterCount(Class<?> type, int count) {
        for (Constructor<?> candidate : type.getDeclaredConstructors()) {
            if (candidate.getParameterTypes().length == count) {
                candidate.setAccessible(true);
                return candidate;
            }
        }
        throw new IllegalStateException(type + " has no constructor taking " + count + " arguments");
    }

    private static Object firstFieldAssignableTo(Object instance, Class<?> type) throws Exception {
        for (Field field : instance.getClass().getDeclaredFields()) {
            field.setAccessible(true);
            Object value = field.get(instance);
            if (value != null && type.isInstance(value)) return value;
        }
        return null;
    }

    private static Object defaultFor(Class<?> type) {
        if (!type.isPrimitive()) return null;
        if (type == boolean.class) return Boolean.FALSE;
        if (type == int.class) return 0;
        if (type == long.class) return 0L;
        if (type == float.class) return 0f;
        if (type == double.class) return 0d;
        if (type == short.class) return (short) 0;
        if (type == byte.class) return (byte) 0;
        if (type == char.class) return (char) 0;
        return null;
    }
}
