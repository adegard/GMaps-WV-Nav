/*
 * Copyright (c) 2026 adegard
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 */
package io.adegard.gmapsnav

import android.content.Context
import android.util.Log
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URL
import java.util.Collections

/**
 * Tiny HTTP server that mirrors the live navigation state to the car screen
 * (head-unit browser or a free companion WebView app) over the phone hotspot.
 * Serves: car.html, leaflet assets, OSM tile proxy, and /api/state JSON.
 */
class MirrorServer(private val context: Context) {

    private val port = 8080
    private var serverSocket: ServerSocket? = null
    private var thread: Thread? = null

    @Volatile
    private var running = false

    /** Latest JSON navigation snapshot, rebuilt by NavigationActivity. */
    @Volatile
    var state: String = "{}"

    fun start() {
        if (running) return
        running = true
        thread = Thread({
            try {
                val ss = ServerSocket()
                ss.reuseAddress = true
                ss.bind(InetSocketAddress(port))
                serverSocket = ss
                Log.i(TAG, "Mirror server listening on :$port")
                while (running) {
                    val socket = try {
                        ss.accept()
                    } catch (e: Exception) {
                        null
                    }
                    if (socket != null) {
                        try {
                            val t = Thread { handle(socket) }
                            t.isDaemon = true
                            t.start()
                        } catch (e: Exception) {
                            try { socket.close() } catch (ignored: Exception) {}
                        }
                    }
                }
            } catch (e: Exception) {
                if (running) Log.w(TAG, "Mirror server failed", e)
            } finally {
                running = false
            }
        }, "MirrorServer")
        thread?.isDaemon = true
        thread?.start()
    }

    fun stop() {
        running = false
        try { serverSocket?.close() } catch (e: Exception) {}
        serverSocket = null
    }

    fun url(): String? {
        val h = hostname() ?: return null
        return "http://$h:$port"
    }

    private fun hostname(): String? {
        return try {
            var fallback: String? = null
            for (ni in Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp) continue
                val niName = ni.name ?: ""
                if (niName.contains("rmnet") || niName.contains("tun") ||
                    niName.contains("p2p") || niName.contains("dummy")
                ) continue
                for (addr in Collections.list(ni.inetAddresses)) {
                    if (addr.isLoopbackAddress) continue
                    val h = addr.hostAddress ?: continue
                    if (h.startsWith("192.168.") || h.startsWith("10.") || h.startsWith("172.")) {
                        return h
                    }
                    if (fallback == null) fallback = h
                }
            }
            fallback
        } catch (e: Exception) {
            null
        }
    }

    private fun handle(socket: Socket) {
        try {
            socket.soTimeout = 5000
            val reader = BufferedReader(InputStreamReader(socket.getInputStream()))
            val requestLine = reader.readLine() ?: return
            val parts = requestLine.split(" ")
            if (parts.size < 2) return
            if (parts[0] != "GET") {
                respondText(socket, 405, "text/plain", "Method Not Allowed")
                return
            }
            val path = parts[1].substringBefore("?").substringBefore("#")
            var line = reader.readLine()
            while (line != null && line.isNotBlank()) {
                line = reader.readLine()
            }
            when {
                path == "/" || path == "/index.html" ->
                    respondAsset(socket, "car.html", "text/html; charset=utf-8")
                path == "/leaflet.js" ->
                    respondAsset(socket, "leaflet/leaflet.js", "application/javascript; charset=utf-8")
                path == "/leaflet.css" ->
                    respondAsset(socket, "leaflet/leaflet.css", "text/css; charset=utf-8")
                path == "/favicon.ico" ->
                    respondText(socket, 404, "text/plain", "Not Found")
                path == "/api/state" ->
                    respondText(socket, 200, "application/json", state)
                path.startsWith("/api/tile/") ->
                    proxyTile(socket, path)
                else ->
                    respondText(socket, 404, "text/plain", "Not Found")
            }
        } catch (e: Exception) {
            Log.d(TAG, "mirror handle: ${e.message}")
        } finally {
            try { socket.close() } catch (e: Exception) {}
        }
    }

    private fun respondText(socket: Socket, code: Int, contentType: String, body: String) {
        val bytes = body.toByteArray()
        val out = socket.getOutputStream()
        out.write(headers(code, contentType, bytes.size))
        out.write(bytes)
        out.flush()
    }

    private fun respondAsset(socket: Socket, assetPath: String, contentType: String) {
        val bytes = try {
            context.assets.open(assetPath).use { it.readBytes() }
        } catch (e: Exception) {
            respondText(socket, 404, "text/plain", "Not Found")
            return
        }
        val out = socket.getOutputStream()
        out.write(headers(200, contentType, bytes.size))
        out.write(bytes)
        out.flush()
    }

    private fun headers(code: Int, contentType: String, len: Int): ByteArray {
        val reason = when (code) {
            200 -> "OK"
            404 -> "Not Found"
            405 -> "Method Not Allowed"
            else -> ""
        }
        return ("HTTP/1.1 $code $reason\r\n" +
            "Content-Type: $contentType\r\n" +
            "Content-Length: $len\r\n" +
            "Cache-Control: no-store\r\n" +
            "Connection: close\r\n\r\n").toByteArray()
    }

    private fun proxyTile(socket: Socket, path: String) {
        val m = Regex("^/api/tile/(\\d+)/(\\d+)/(\\d+)\\.png$").find(path)
            ?: return respondText(socket, 404, "text/plain", "Not Found")
        val (z, x, y) = m.destructured
        val url = URL("https://tile.openstreetmap.org/$z/$x/$y.png")
        try {
            val conn = url.openConnection() as HttpURLConnection
            conn.connectTimeout = 8000
            conn.readTimeout = 15000
            conn.setRequestProperty("User-Agent", USER_AGENT)
            conn.connect()
            conn.inputStream.use { ins ->
                val bytes = ins.readBytes()
                val out = socket.getOutputStream()
                out.write(headers(200, "image/png", bytes.size))
                out.write(bytes)
                out.flush()
            }
            conn.disconnect()
        } catch (e: Exception) {
            respondText(socket, 404, "text/plain", "Not Found")
        }
    }

    companion object {
        private const val TAG = "GMapsMirror"
        private const val USER_AGENT = "GMaps-WV-Nav/2.0 (mirror proxy)"
    }
}