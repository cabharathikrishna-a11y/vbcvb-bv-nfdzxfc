package com.example.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.RemoteViews
import com.example.MainActivity
import com.example.R
import com.example.data.AppDatabase
import com.example.ui.components.BUILT_IN_FESTIVALS
import com.example.ui.components.parseDateStringToCalendar
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.util.Calendar

class CountdownWidgetProvider : AppWidgetProvider() {

    companion object {
        const val ACTION_SCROLL_TO_TOP = "com.example.widget.ACTION_COUNTDOWN_SCROLL_TOP"
        const val ACTION_REFRESH_COUNTDOWNS = "com.example.widget.ACTION_REFRESH_COUNTDOWNS"

        fun updateAppWidget(
            context: Context,
            appWidgetManager: AppWidgetManager,
            appWidgetId: Int
        ) {
            val bgRes = WidgetManager.getBackgroundDrawableRes(context)
            val views = RemoteViews(context.packageName, R.layout.widget_countdown)

            views.setInt(android.R.id.background, "setBackgroundResource", bgRes)

            // Setup remote adapter for scrollable list
            val serviceIntent = Intent(context, CountdownWidgetService::class.java).apply {
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
                data = Uri.parse(toUri(Intent.URI_INTENT_SCHEME))
            }
            views.setRemoteAdapter(R.id.countdown_widget_list, serviceIntent)
            views.setEmptyView(R.id.countdown_widget_list, R.id.countdown_empty_view)

            // Up Arrow button: scrolls list back to position 0 (top)
            val upIntent = Intent(context, CountdownWidgetProvider::class.java).apply {
                action = ACTION_SCROLL_TO_TOP
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            }
            val upPending = PendingIntent.getBroadcast(
                context,
                7100 + appWidgetId,
                upIntent,
                WidgetManager.getPendingIntentFlags()
            )
            views.setOnClickPendingIntent(R.id.btn_scroll_top, upPending)

            // Refresh button
            val refreshIntent = Intent(context, CountdownWidgetProvider::class.java).apply {
                action = ACTION_REFRESH_COUNTDOWNS
                putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId)
            }
            val refreshPending = PendingIntent.getBroadcast(
                context,
                7200 + appWidgetId,
                refreshIntent,
                WidgetManager.getPendingIntentFlags()
            )
            views.setOnClickPendingIntent(R.id.btn_widget_refresh, refreshPending)

            // Click header to open Countdowns in Life OS
            val openAppIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra("SHOW_COUNTDOWN_PAGE", true)
                putExtra("NAVIGATE_TO", "COUNTDOWN")
            }
            val openAppPending = PendingIntent.getActivity(
                context,
                7300,
                openAppIntent,
                WidgetManager.getPendingIntentFlags()
            )
            views.setOnClickPendingIntent(R.id.countdown_header_bar, openAppPending)

            // Item click template
            val itemClickIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
                putExtra("SHOW_COUNTDOWN_PAGE", true)
                putExtra("NAVIGATE_TO", "COUNTDOWN")
            }
            val itemClickPending = PendingIntent.getActivity(
                context,
                7400,
                itemClickIntent,
                WidgetManager.getPendingIntentFlags(isMutable = true)
            )
            views.setPendingIntentTemplate(R.id.countdown_widget_list, itemClickPending)

            // Asynchronously fetch count to display on header badge
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    val db = AppDatabase.getInstance(context)
                    val contacts = db.contactDao().getAllContactsDirect()
                    val deadlines = db.deadlineDao().getAllDeadlinesDirect()
                    val totalCount = BUILT_IN_FESTIVALS.size +
                            contacts.count { it.dobString.isNotBlank() } +
                            contacts.count { it.anniversaryString.isNotBlank() } +
                            deadlines.count { !it.isCompleted }

                    views.setTextViewText(R.id.countdown_count_badge, "$totalCount")
                    appWidgetManager.partiallyUpdateAppWidget(appWidgetId, views)
                } catch (_: Exception) {}
            }

            appWidgetManager.updateAppWidget(appWidgetId, views)
            appWidgetManager.notifyAppWidgetViewDataChanged(appWidgetId, R.id.countdown_widget_list)
        }

        fun updateAllCountdownWidgets(context: Context) {
            val appWidgetManager = AppWidgetManager.getInstance(context) ?: return
            val thisWidget = ComponentName(context, CountdownWidgetProvider::class.java)
            val allIds = appWidgetManager.getAppWidgetIds(thisWidget)
            for (id in allIds) {
                updateAppWidget(context, appWidgetManager, id)
            }
        }
    }

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        super.onUpdate(context, appWidgetManager, appWidgetIds)
        for (appWidgetId in appWidgetIds) {
            updateAppWidget(context, appWidgetManager, appWidgetId)
        }
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle?
    ) {
        super.onAppWidgetOptionsChanged(context, appWidgetManager, appWidgetId, newOptions)
        updateAppWidget(context, appWidgetManager, appWidgetId)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        val action = intent.action ?: return
        val appWidgetManager = AppWidgetManager.getInstance(context) ?: return
        val thisWidget = ComponentName(context, CountdownWidgetProvider::class.java)
        val allIds = appWidgetManager.getAppWidgetIds(thisWidget)

        when (action) {
            ACTION_SCROLL_TO_TOP -> {
                val targetId = intent.getIntExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, AppWidgetManager.INVALID_APPWIDGET_ID)
                val targetIds = if (targetId != AppWidgetManager.INVALID_APPWIDGET_ID) intArrayOf(targetId) else allIds
                for (id in targetIds) {
                    val partialViews = RemoteViews(context.packageName, R.layout.widget_countdown)
                    // Smooth scroll and set selection to 0
                    partialViews.setInt(R.id.countdown_widget_list, "smoothScrollToPosition", 0)
                    partialViews.setInt(R.id.countdown_widget_list, "setSelection", 0)
                    appWidgetManager.partiallyUpdateAppWidget(id, partialViews)
                }
            }
            ACTION_REFRESH_COUNTDOWNS,
            Intent.ACTION_TIME_TICK,
            Intent.ACTION_DATE_CHANGED,
            Intent.ACTION_TIME_CHANGED,
            Intent.ACTION_TIMEZONE_CHANGED -> {
                appWidgetManager.notifyAppWidgetViewDataChanged(allIds, R.id.countdown_widget_list)
                for (id in allIds) {
                    updateAppWidget(context, appWidgetManager, id)
                }
            }
        }
    }
}
