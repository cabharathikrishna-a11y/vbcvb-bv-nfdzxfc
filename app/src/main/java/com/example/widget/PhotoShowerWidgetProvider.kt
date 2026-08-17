package com.example.widget

import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class PhotoShowerWidgetProvider : AppWidgetProvider() {

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray
    ) {
        super.onUpdate(context, appWidgetManager, appWidgetIds)
        WidgetManager.updatePhotoShowerWidget(context, forceNext = false)
    }

    override fun onAppWidgetOptionsChanged(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetId: Int,
        newOptions: Bundle?
    ) {
        super.onAppWidgetOptionsChanged(context, appWidgetManager, appWidgetId, newOptions)
        WidgetManager.updatePhotoShowerWidget(context, forceNext = false)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        val action = intent.action ?: return
        Log.d("PhotoShowerWidget", "Widget received action: $action")

        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                when (action) {
                    "com.example.widget.ACTION_PHOTO_SHOWER_NEXT" -> {
                        WidgetManager.updatePhotoShowerWidget(context, forceNext = true)
                    }
                    "com.example.widget.ACTION_PHOTO_SHOWER_REFRESH" -> {
                        WidgetManager.updatePhotoShowerWidget(context, forceNext = false)
                    }
                }
            } catch (e: Throwable) {
                Log.e("PhotoShowerWidget", "Error handling action $action: ${e.message}", e)
            } finally {
                pendingResult.finish()
            }
        }
    }
}
