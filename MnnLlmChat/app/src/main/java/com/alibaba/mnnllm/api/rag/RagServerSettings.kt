package com.alibaba.mnnllm.api.rag

import android.content.Context

/**
 * Persistence for the RAG MCP server URL. Mirrors ApiServerConfig's SharedPreferences pattern:
 * its own prefs file so keys never collide with "api_settings", plain Context-based read/write
 * (no DI container is used in this app — see ServiceLocator).
 */
object RagServerSettings {

    private const val PREFS_NAME = "rag_server_settings"
    private const val KEY_BASE_URL = "rag_mcp_base_url"

    /** Fallback base URL when nothing has been configured yet (desktop server on the same LAN/WiFi). */
    fun getDefaultBaseUrl(): String = "http://127.0.0.1:8199"

    fun getBaseURL(context: Context): String {
        val stored = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_BASE_URL, null)
        return if (stored.isNullOrBlank()) getDefaultBaseUrl() else stored.trim()
    }

    fun saveBaseURL(context: Context, url: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().apply { putString(KEY_BASE_URL, url.trim()) }.apply()
    }

    /** Convenience factory building a client for the configured server (or default). */
    fun createClient(context: Context): RagMcpClient = RagMcpClient(getBaseURL(context))
}
