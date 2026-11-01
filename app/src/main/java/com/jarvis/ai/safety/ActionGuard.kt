package com.jarvis.ai.safety

import com.jarvis.ai.ai.ActionType
import com.jarvis.ai.ai.ValidatedAction
import com.jarvis.ai.ai.ValidatedPlan

/**
 * Decides whether a validated plan must be confirmed by the user before it runs.
 * This is a second line of defence on top of the parser and the allowlist.
 */
object ActionGuard {

    /** Actions that always need a "haan" from the user. */
    private val ALWAYS_CONFIRM = setOf(ActionType.DOWNLOAD_FILE, ActionType.INSTALL_APK)

    /** Tap labels that look destructive, irreversible, or external (sending, paying, publishing). */
    private val RISKY_LABEL = Regex(
        "\\b(send|submit|delete|remove|publish|pay|buy|order|confirm|discard|reset|logout|sign out|" +
            "bhejo|bhejen|hatao|kharido|bharo|payment)\\b",
        RegexOption.IGNORE_CASE
    )

    fun needsConfirmation(plan: ValidatedPlan): Boolean = plan.actions.any { action ->
        action is ValidatedAction.Ready && (
            action.type in ALWAYS_CONFIRM ||
                (action.type == ActionType.TAP && RISKY_LABEL.containsMatchIn(action.args["target"].orEmpty()))
            )
    }

    /** Short description for the spoken or visible confirmation question. */
    fun describe(plan: ValidatedPlan): String =
        plan.actions
            .filterIsInstance<ValidatedAction.Ready>()
            .joinToString(", ") { action ->
                val detail = action.args.values.joinToString(" ").take(80)
                if (detail.isEmpty()) action.type.wire else "${action.type.wire} ($detail)"
            }
}
