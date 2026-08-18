package com.example.vray.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.widget.RemoteViews
import com.example.vray.MainActivity
import com.example.vray.R
import com.example.vray.core.ConnectionState
import com.example.vray.core.ProxyVpnService
import com.example.vray.data.Repository

class VpnWidgetProvider : AppWidgetProvider() {

    companion object {
        const val ACTION_TOGGLE = "com.example.vray.widget.ACTION_TOGGLE"

        /** Called from ProxyVpnService whenever connection state changes. */
        fun updateAll(context: Context) {
            val mgr = AppWidgetManager.getInstance(context)
            val ids = mgr.getAppWidgetIds(ComponentName(context, VpnWidgetProvider::class.java))
            ids.forEach { id -> updateOne(context, mgr, id) }
        }

        private fun updateOne(context: Context, mgr: AppWidgetManager, widgetId: Int) {
            val state = ProxyVpnService.state.value
            val (statusText, buttonText) = when (state) {
                ConnectionState.CONNECTED -> "متصل" to "قطع اتصال"
                ConnectionState.CONNECTING -> "در حال اتصال…" to "…"
                ConnectionState.ERROR -> "خطا در اتصال" to "اتصال"
                ConnectionState.DISCONNECTED -> "قطع" to "اتصال"
            }

            val views = RemoteViews(context.packageName, R.layout.widget_vpn)
            views.setTextViewText(R.id.widget_status_text, statusText)
            views.setTextViewText(R.id.widget_toggle_button, buttonText)

            val toggleIntent = Intent(context, VpnWidgetProvider::class.java).apply { action = ACTION_TOGGLE }
            val togglePending = android.app.PendingIntent.getBroadcast(
                context, 0, toggleIntent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
            )
            views.setOnClickPendingIntent(R.id.widget_toggle_button, togglePending)

            mgr.updateAppWidget(widgetId, views)
        }
    }

    override fun onUpdate(context: Context, appWidgetManager: AppWidgetManager, appWidgetIds: IntArray) {
        appWidgetIds.forEach { updateOne(context, appWidgetManager, it) }
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action != ACTION_TOGGLE) return

        val repo = Repository(context)
        val state = ProxyVpnService.state.value

        if (state == ConnectionState.CONNECTED || state == ConnectionState.CONNECTING) {
            val svc = Intent(context, ProxyVpnService::class.java).apply {
                action = ProxyVpnService.ACTION_DISCONNECT
            }
            context.startService(svc)
        } else {
            val profileId = repo.loadSelectedProfileId()
            if (profileId == null) {
                // No server chosen yet — open the app instead of silently failing.
                val open = Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(open)
                return
            }
            // VPN permission may not be granted yet; if VpnService.prepare() returns
            // non-null we can't silently start from a widget, so fall back to opening the app.
            if (VpnService.prepare(context) != null) {
                val open = Intent(context, MainActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(open)
                return
            }
            val svc = Intent(context, ProxyVpnService::class.java).apply {
                action = ProxyVpnService.ACTION_CONNECT
                putExtra(ProxyVpnService.EXTRA_PROFILE_ID, profileId)
            }
            context.startForegroundService(svc)
        }
    }
}
