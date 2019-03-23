package ch.rmy.android.http_shortcuts.actions.types

import android.content.Context
import ch.rmy.android.http_shortcuts.R
import ch.rmy.android.http_shortcuts.activities.ExecuteActivity
import ch.rmy.android.http_shortcuts.extensions.showToast
import ch.rmy.android.http_shortcuts.extensions.startActivity
import ch.rmy.android.http_shortcuts.http.ShortcutResponse
import ch.rmy.android.http_shortcuts.realm.Controller
import ch.rmy.android.http_shortcuts.utils.PromiseUtils
import ch.rmy.android.http_shortcuts.variables.VariablePlaceholderProvider
import ch.rmy.android.http_shortcuts.variables.Variables
import com.android.volley.VolleyError
import org.jdeferred2.Promise

class TriggerShortcutAction(
    id: String,
    actionType: TriggerShortcutActionType,
    data: Map<String, String>
) : BaseAction(id, actionType, data) {

    var shortcutId: String
        get() = internalData[KEY_SHORTCUT_ID]?: ""
        set(value) {
            internalData[KEY_SHORTCUT_ID] = value
        }

    val shortcutName: String?
        get() {
            Controller().use { controller ->
                return controller.getShortcutById(shortcutId)?.name
            }
        }

    override fun getDescription(context: Context): CharSequence =
        Variables.rawPlaceholdersToVariableSpans(
            context,
            context.getString(
                R.string.action_type_trigger_shortcut_description,
                shortcutName ?: "???"
            )
        )

    override fun perform(context: Context, shortcutId: String, variableValues: MutableMap<String, String>, response: ShortcutResponse?, volleyError: VolleyError?, recursionDepth: Int): Promise<Unit, Throwable, Unit> {
        if (recursionDepth >= MAX_RECURSION_DEPTH) {
            context.showToast(R.string.action_type_trigger_shortcut_error_recursion_depth_reached, long = true)
            return PromiseUtils.resolve(Unit)
        }
        return PromiseUtils.resolveDelayed<Unit, Throwable, Unit>(Unit, EXECUTION_DELAY)
            .done {
                ExecuteActivity.IntentBuilder(context, this.shortcutId)
                    .recursionDepth(recursionDepth + 1)
                    .build()
                    .startActivity(context)
            }
    }

    override fun createEditorView(context: Context, variablePlaceholderProvider: VariablePlaceholderProvider) =
        TriggerShortcutActionEditorView(context, this)

    companion object {

        private const val KEY_SHORTCUT_ID = "shortcutId"

        private const val MAX_RECURSION_DEPTH = 5

        private const val EXECUTION_DELAY = 500L

    }

}