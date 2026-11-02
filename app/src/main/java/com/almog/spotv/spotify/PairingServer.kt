package com.almog.spotv.spotify

import com.almog.spotv.Constants
import com.almog.spotv.DebugLog
import java.io.IOException
import java.net.Inet4Address
import java.net.InetAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.security.SecureRandom
import kotlinx.coroutines.CompletableDeferred

/**
 * Lets the viewer log in to Spotify on their phone instead of with a TV remote.
 *
 * 1. The TV shows a QR code for http://<tv-ip>:<port>/ (served here, on the local network).
 * 2. The phone opens it and is sent on to Spotify's login page (PKCE, the TV keeps the verifier).
 * 3. Spotify sends the phone to the https redirect page (docs/callback.html), which bounces it back
 *    to http://<tv-ip>:<port>/callback?code=... on this server.
 * 4. The TV trades the code for tokens. The phone is told to look at the TV.
 */
class PairingServer(private val auth: SpotifyAuth) {
    private val verifier = SpotifyAuth.newVerifier()
    private val nonce = ByteArray(12).also { SecureRandom().nextBytes(it) }
        .joinToString("") { "%02x".format(it) }
    private var server: ServerSocket? = null
    private var state: String = ""

    /** Completes when a login code was accepted and exchanged for tokens. */
    val loggedIn = CompletableDeferred<Unit>()

    /** What to show as a QR code / typed address on the TV. */
    var url: String? = null
        private set

    @Throws(IOException::class)
    fun start() {
        val ip = localIpv4() ?: throw IOException("No local network address")
        var socket: ServerSocket? = null
        for (port in Constants.PAIRING_PORT_FIRST..Constants.PAIRING_PORT_LAST) {
            try {
                socket = ServerSocket(port)
                break
            } catch (_: IOException) {
                // Port busy: try the next one.
            }
        }
        val bound = socket ?: throw IOException("No free port for the login page")
        server = bound
        state = "${ip.hostAddress}:${bound.localPort}:$nonce"
        url = "http://${ip.hostAddress}:${bound.localPort}/"
        Thread({ acceptLoop(bound) }, "spotv-pairing").apply { isDaemon = true }.start()
        DebugLog.d(TAG, "Login page at $url")
    }

    fun stop() {
        try {
            server?.close()
        } catch (_: IOException) {
        }
        server = null
    }

    private fun acceptLoop(bound: ServerSocket) {
        while (!bound.isClosed) {
            val client = try {
                bound.accept()
            } catch (_: IOException) {
                return
            }
            try {
                handle(client)
            } catch (e: Exception) {
                DebugLog.w(TAG, "Login page request failed: ${e.message}")
            } finally {
                try {
                    client.close()
                } catch (_: IOException) {
                }
            }
        }
    }

    private fun handle(client: Socket) {
        client.soTimeout = 5_000
        val reader = client.getInputStream().bufferedReader(Charsets.ISO_8859_1)
        val requestLine = reader.readLine() ?: return
        // Drain the headers.
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isEmpty()) break
        }
        val parts = requestLine.split(" ")
        if (parts.size < 2 || parts[0] != "GET") {
            respond(client, 405, "text/plain", "Method not allowed")
            return
        }
        val target = parts[1]
        val path = target.substringBefore('?')
        val query = parseQuery(target.substringAfter('?', ""))
        when (path) {
            "/" -> {
                val authUrl = SpotifyAuth.authorizeUrl(state, SpotifyAuth.challengeFor(verifier))
                respond(client, 302, "text/plain", "Redirecting to Spotify...", "Location: $authUrl")
            }
            "/callback" -> handleCallback(client, query)
            else -> respond(client, 404, "text/plain", "Not found")
        }
    }

    private fun handleCallback(client: Socket, query: Map<String, String>) {
        if (query["state"] != state) {
            respond(client, 400, "text/html", page("Wrong login session", "Scan the code on the TV again and retry."))
            return
        }
        val error = query["error"]
        val code = query["code"]
        if (error != null || code.isNullOrEmpty()) {
            respond(client, 400, "text/html", page("Login cancelled", "Spotify said: ${escape(error ?: "no code")}. Scan the code on the TV again to retry."))
            return
        }
        if (auth.exchangeCodeBlocking(code, verifier)) {
            respond(client, 200, "text/html", page("You're connected", "Look at your TV. You can close this page."))
            loggedIn.complete(Unit)
        } else {
            respond(client, 502, "text/html", page("Couldn't finish the login", "The TV could not reach Spotify. Scan the code again to retry."))
        }
    }

    private fun respond(client: Socket, code: Int, type: String, body: String, extraHeader: String? = null) {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val status = when (code) {
            200 -> "OK"
            302 -> "Found"
            400 -> "Bad Request"
            404 -> "Not Found"
            405 -> "Method Not Allowed"
            else -> "Error"
        }
        val head = buildString {
            append("HTTP/1.1 $code $status\r\n")
            append("Content-Type: $type; charset=utf-8\r\n")
            append("Content-Length: ${bytes.size}\r\n")
            append("Cache-Control: no-store\r\n")
            if (extraHeader != null) append(extraHeader).append("\r\n")
            append("Connection: close\r\n\r\n")
        }
        val out = client.getOutputStream()
        out.write(head.toByteArray(Charsets.ISO_8859_1))
        out.write(bytes)
        out.flush()
    }

    private fun page(title: String, message: String): String =
        "<!doctype html><html><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">" +
            "<title>SpotTV</title></head><body style=\"font-family:sans-serif;background:#121212;color:#fff;text-align:center;padding:15vh 24px\">" +
            "<h1 style=\"color:#1DB954\">$title</h1><p style=\"font-size:1.2em\">$message</p></body></html>"

    private fun escape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

    private fun parseQuery(q: String): Map<String, String> {
        if (q.isEmpty()) return emptyMap()
        val map = HashMap<String, String>()
        for (pair in q.split('&')) {
            val k = pair.substringBefore('=')
            val v = pair.substringAfter('=', "")
            map[URLDecoder.decode(k, "UTF-8")] = URLDecoder.decode(v, "UTF-8")
        }
        return map
    }

    companion object {
        private const val TAG = "PairingServer"

        /** The TV's address on the local network (Wi-Fi or Ethernet), or null when offline. */
        fun localIpv4(): InetAddress? {
            val interfaces = try {
                NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            } catch (_: Exception) {
                emptyList()
            }
            return interfaces
                .filter { runCatching { it.isUp && !it.isLoopback }.getOrDefault(false) }
                .flatMap { it.inetAddresses.toList() }
                .firstOrNull { it is Inet4Address && it.isSiteLocalAddress }
        }
    }
}
