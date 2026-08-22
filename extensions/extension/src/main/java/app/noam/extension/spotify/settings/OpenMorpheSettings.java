package app.noam.extension.spotify.settings;

import android.content.Context;
import android.content.Intent;

import app.noam.extension.spotify.Utils;
import kotlin.Unit;
import kotlin.jvm.functions.Function1;

/**
 * The click action of the Morphe settings tile.
 *
 * Spotify's settings rows take their action as a {@code kotlin.jvm.functions.Function1}, which for a
 * suspending lambda is invoked with a Continuation and may return a value directly to signal that it
 * completed without suspending. Returning {@link Unit} does exactly that.
 */
public final class OpenMorpheSettings implements Function1<Object, Object> {

    @Override
    public Object invoke(Object ignoredContinuation) {
        try {
            Context context = Utils.getContext();
            if (context != null) {
                Intent intent = new Intent(context, MorpheSettingsActivity.class);
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(intent);
            }
        } catch (Exception ex) {
            Utils.logError("Could not open Morphe settings", ex);
        }
        return Unit.INSTANCE;
    }
}
