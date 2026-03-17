package com.kiddolock.app.management

import android.content.Context
import android.util.Log
import com.google.gson.Gson
import com.kiddolock.app.config.ApiConfig
import com.kiddolock.app.utils.DeviceIdentifier
import com.kiddolock.app.utils.SecurityUtils
import okhttp3.*
import okio.ByteString

/**
 * Handles real-time communication with the Cloudflare Agent via WebSockets.
 */
class RealTimeMessenger(
    private val context: Context,
    private val commandHandler: (type: String?, payload: String?) -> Unit
) : WebSocketListener() {

    private val client = OkHttpClient()
    private var webSocket: WebSocket? = null
    private val gson = Gson()
    
    // Command deduplication to prevent "loops" on state broadcast
    private val processedCommandIds = mutableSetOf<String>()
    private var isClosing = false

    fun connect() {
        if (webSocket != null || isClosing) return

        val baseUrl = ApiConfig.getBaseUrl(context)
        val deviceId = DeviceIdentifier.getAndroidId(context)
        
        // Correctly transform http/https to ws/wss
        val wsUrl = if (baseUrl.startsWith("https://")) {
            baseUrl.replace("https://", "wss://") + "/api/ws?deviceId=$deviceId"
        } else {
            baseUrl.replace("http://", "ws://") + "/api/ws?deviceId=$deviceId"
        }

        Log.i("RealTimeMessenger", "Connecting to WebSocket: $wsUrl")

        val request = Request.Builder()
            .url(wsUrl)
            .build()
            
        webSocket = client.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d("RealTimeMessenger", "Connected to WebSocket")
                isClosing = false
                webSocket.send("ping")
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                Log.d("RealTimeMessenger", "Received: $text")
                if (text == "pong") return

                try {
                    val state = gson.fromJson(text, Map::class.java)
                    val commands = state["commands"] as? List<Map<String, Any>>
                    commands?.forEach { cmd ->
                        val commandId = cmd["id"] as? String
                        if (commandId != null && !processedCommandIds.contains(commandId)) {
                            Log.i("RealTimeMessenger", "New Real-time command: $commandId")
                            // Original logic: if (cmd["status"] == "PENDING") {
                            // The new instruction implies executing all new commands and handling status within RemoteCommandHandler
                            val type = cmd["command_type"] as? String
                            val payload = cmd["payload"] as? String
                            commandHandler(type, payload) // Keep original commandHandler call
                            processedCommandIds.add(commandId)
                            
                            // Keep set size manageable
                            if (processedCommandIds.size > 100) {
                                processedCommandIds.remove(processedCommandIds.first())
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e("RealTimeMessenger", "Message Parse Error", e)
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
                Log.w("RealTimeMessenger", "Closing: $reason")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.w("RealTimeMessenger", "Closed: $reason")
                this@RealTimeMessenger.webSocket = null
                if (!isClosing) {
                    // Simple reconnect logic after 5 seconds
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        connect()
                    }, 5000)
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                Log.e("RealTimeMessenger", "WebSocket Failure", t)
                this@RealTimeMessenger.webSocket = null
                if (!isClosing) {
                    android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                        connect()
                    }, 10000)
                }
            }
        })
    }

    fun disconnect() {
        isClosing = true
        webSocket?.close(1000, "User logout/Service stop")
        webSocket = null
    }
}
