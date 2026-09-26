package com.example.util

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.createSupabaseClient
import io.github.jan.supabase.postgrest.Postgrest
import io.github.jan.supabase.realtime.Realtime
import io.ktor.client.engine.okhttp.OkHttp

object SupabaseManager {
    const val SUPABASE_URL = ""
    const val SUPABASE_KEY = "" // Supabase publishable / anon API key

    val isConfigured: Boolean
        get() = SUPABASE_URL.isNotBlank() && 
                SUPABASE_URL.startsWith("https://") &&
                !SUPABASE_URL.contains("example") &&
                SUPABASE_KEY.isNotBlank() && 
                !SUPABASE_KEY.contains("...") && 
                SUPABASE_KEY.length > 30

    val client: SupabaseClient? by lazy {
        if (!isConfigured) {
            null
        } else {
            try {
                createSupabaseClient(
                    supabaseUrl = SUPABASE_URL,
                    supabaseKey = SUPABASE_KEY
                ) {
                    httpEngine = OkHttp.create()
                    install(Postgrest)
                    install(Realtime) {
                        reconnectDelay = kotlin.time.Duration.parse("30s")
                    }
                }
            } catch (e: Exception) {
                null
            }
        }
    }
}
