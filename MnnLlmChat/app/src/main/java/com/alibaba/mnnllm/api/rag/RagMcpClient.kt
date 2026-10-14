package com.alibaba.mnnllm.api.rag

import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.squareup.okhttp3.MediaType.Companion.toMediaType
import com.squareup.okhttp3.OkHttpClient
import com.squareup.okhttp3.Request
import com.squareup.okhttp3.RequestBody.Companion.toRequestBody
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume

/**
 * Minimal MCP (Model Context Protocol) JSON-RPC 2.0 client for the local RAG server.
 *
 * Server contract (see rag-mcp-server/mcp/{protocol.go,server.go}):
 *   - Single endpoint: POST {baseUrl}/mcp , Content-Type application/json
 *   - JSON-RPC 2.0 body; protocolVersion "2025-06-18"
 *   - initialize handshake + optional notifications/initialized (server returns its version)
 *   - tools/list / tools/call ; no session tracking, no auth on /mcp (every request independent).
 */
class RagMcpClient(
    baseUrl: String, // e.g. "http://192.168.x.x:8199"  (do NOT include "/mcp")
    private val callTimeoutMs: Long = 60_000L,
) {
    /** Endpoint the client posts to; derived from [baseUrl] + "/mcp". */
    var endpoint: String = baseUrl.trimEnd('/') + "/mcp"

    private val client = OkHttpClient.Builder()
        .connectTimeout(callTimeoutMs, TimeUnit.MILLISECONDS)
        .readTimeout(callTimeoutMs, TimeUnit.MILLISECONDS)
        .writeTimeout(callTimeoutMs, TimeUnit.MILLISECONDS)
        .build()

    private val gson = Gson()
    private val idGen: AtomicInteger = AtomicInteger(1_000_000)

    /** Perform the MCP handshake; returns the server's initialize response (jsonrpc/id/result). */
    suspend fun initialize(): JsonObject {
        val params = JsonObject().apply {
            addProperty("protocolVersion", "2025-06-18")
            putJsonObject("capabilities").apply { }  // advertised by our server, ignored client-side
            putJsonObject("clientInfo").apply {
                addProperty("name", "mnnllm-android")
                addProperty("version", "1.0")
            }
        }
        return rpcRaw("initialize", params) ?: error("MCP initialize failed: no response body")
    }

    /** Optional post-initialize notification; server just responds 204 with no body. */
    suspend fun notifyInitialized() {
        val ok = rpcIdOnly("notifications/initialized")
        if (!ok) Log.w(TAG, "MCP notifications/initialized not acknowledged")
    }

    /** Return the tool names advertised by the server (e.g. search_memory, store_memory). */
    suspend fun listTools(): List<RagTool> {
        val root = rpcRaw("tools/list") ?: error("tools/list failed: no response body")
        if (!root.has("result")) return emptyList()
        val result = root.getAsJsonObject("result") ?: return emptyList()
        if (!result.has("tools")) return emptyList()
        val arr = result.getAsJsonArray("tools")
        return buildList {
            for (el in arr) {
                val jo = el?.asJsonObject ?: continue
                val name = jo.getAsStringOrNull("name") ?: continue
                add(RagTool(name))
            }
        }
    }

    /** Call a single tool by name. Returns the server-provided text plus an isError flag. */
    suspend fun callTool(name: String, args: Map<String, Any?> = emptyMap()): RagResult {
        val params = JsonObject().apply {
            addProperty("name", name)
            add("arguments", gson.toJsonTree(args)) // JSON-serializable map/value
        }
        val root = rpcRaw("tools/call", params) ?: error("tools/call failed: no response body")
        if (!root.has("result")) return RagResult(null, isError = true)
        val res = root.getAsJsonObject("result")
        val isError = (res?.getAsJsonPrimitive("isError")?.asBoolean) ?: false
        var text = ""
        val content = res?.getAsJsonArray("content")
        if (content != null && content.size() > 0) {
            for (b in content) {
                val jo = b?.asJsonObject ?: continue
                val t = jo.get("text")
                if (t != null) append(t.asString).append("\n\n")
            }
        } else {
            text = root.toString()
        }
        return RagResult(text, isError = isError)
    }

    // ---- internals ---------------------------------------------------------

    private suspend fun rpcIdOnly(method: String): Boolean {
        val b = JsonObject().apply {
            addProperty("jsonrpc", "2.0")
            addProperty("id", idGen.decrementAndGet())
            addProperty("method", method)
        }
        val raw = send(b) ?: return false
        // notifications/initialized expects 204 No Content; a JSON response means the server is not MCP-aware.
        return raw.isBlank()
    }

    private suspend fun rpcRaw(method: String, params: JsonObject? = null): JsonObject? {
        val b = JsonObject().apply {
            addProperty("jsonrpc", "2.0")
            addProperty("id", idGen.decrementAndGet())
            addProperty("method", method)
            if (params != null) add("params", params)
        }
        return runCatching { gson.fromJson(send(b), JsonObject::class.java) }.getOrNull()
    }

    /** Send a JSON-RPC request body; returns the response text, or null on network failure. */
    private suspend fun send(body: JsonObject): String? = suspendCancellableCoroutine { cont ->
        val url = endpoint
        val reqJson = gson.toJson(body)
        val request = Request.Builder()
            .url(url)
            .post(reqJson.toRequestBody("application/json".toMediaType()))
            .build()
        client.newCall(request).enqueue(object : com.squareup.okhttp3.Callback {
            override fun onFailure(call: com.squareup.okhttp3.Call, e: java.io.IOException) = cont.resume(null)
            override fun onResponse(call: com.squareup.okhttp3.Call, response: com.squareup.okhttp3.Response) =
                runCatching { val s = response.body?.string(); cont.resume(s) }.getOrDefault {}
        })
    }

    private inline fun JsonObject.getAsStringOrNull(field: String): String? {
        if (!has(field)) return null
        val el = get(field) ?: return null
        return if (el.isJsonPrimitive && el.asJsonPrimitive.isString) el.asString else null
    }

    companion object {
        private const val TAG = "RagMcpClient"
    }
}

/** A tool advertised by the server. */
data class RagTool(val name: String)

/** Result of a single tools/call invocation. */
data class RagResult(
    val text: String?,
    val isError: Boolean = false,
) {
    /** Convenience accessor returning either the returned text (non-error) or null. */
    fun valueOrNull(): String? = if (isError || text.isNullOrBlank()) null else text
}
