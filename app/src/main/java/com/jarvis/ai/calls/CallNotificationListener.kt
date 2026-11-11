package com.jarvis.ai.calls

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

/**
 * Reads notifications from the phone (only when the user has allowed notification access) and, in away mode,
 * handles incoming call notifications from the watched apps. Other notifications are ignored.
 */
class CallNotificationListener : NotificationListenerService() {

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        val item = sbn ?: return
        if (item.packageName == packageName) return
        if (!AwayMode.isOn(this)) return

        val n: Notification = item.notification ?: return
        val extras = n.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString().orEmpty()
        if (!AwayMode.looksLikeCall(n, title, text, item.packageName)) return

        val caller = title.ifBlank { "अज्ञात" }
        AwayMode.addMissed(this, caller, item.packageName)

        val action = AwayMode.replyAction(n) ?: return
        if (!AwayMode.cooldownOk(caller)) return
        AwayMode.sendReply(this, action, AwayMode.REPLY_TEXT)
    }
}
