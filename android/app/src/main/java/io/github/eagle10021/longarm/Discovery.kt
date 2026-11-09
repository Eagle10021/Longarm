package io.github.eagle10021.longarm

import android.os.Build
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.URL
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.util.UUID
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject

private const val DEFAULT_PORT = 47126
private const val DISCOVERY_PORT = 47127
private const val DISCOVERY_REQUEST = "longarm-discover-1"
private const val LISTEN_MS = 1500L
private const val POLL_MS = 1500L
private const val PAIRING_LIFETIME_MS = 120_000L

/** A device that was found. Its fingerprint is blank when the address was typed in. */
data class Found(val name: String, val fingerprint: String, val addresses: List<String>)

data class PairingProgress(val name: String, val code: String, val waiting: Boolean)

fun withDefaultPort(address: String) = address.trim().let { if (":" in it) it else "$it:$DEFAULT_PORT" }

/** "Tailscale" for an address in Tailscale's 100.64.0.0/10 range, otherwise "Local network". */
fun networkLabel(address: String): String {
    val parts = address.substringBefore(":").split(".").map { it.toIntOrNull() }
    return if (parts.size == 4 && parts[0] == 100 && (parts[1] ?: 0) in 64..127) "Tailscale" else "Local network"
}

/** One entry per device, combining the addresses it was found at. */
fun mergeFound(found: List<Found>): List<Found> =
    found.filter { it.fingerprint.isNotBlank() }
        .groupBy { it.fingerprint }
        .map { (fingerprint, same) -> Found(same.first().name, fingerprint, same.flatMap { it.addresses }.distinct()) }
        .sortedBy { it.name.lowercase() }

suspend fun findOnLocalNetwork(): List<Found> = withContext(Dispatchers.IO) {
    DatagramSocket().use { socket ->
        socket.broadcast = true
        socket.soTimeout = 250
        val request = DISCOVERY_REQUEST.toByteArray()
        val everyone = InetAddress.getByName("255.255.255.255")
        socket.send(DatagramPacket(request, request.size, everyone, DISCOVERY_PORT))
        val found = ArrayList<Found>()
        val deadline = System.currentTimeMillis() + LISTEN_MS
        while (System.currentTimeMillis() < deadline) {
            val reply = DatagramPacket(ByteArray(1024), 1024)
            try {
                socket.receive(reply)
            } catch (_: SocketTimeoutException) {
                continue
            }
            val hello = runCatching { JSONObject(String(reply.data, 0, reply.length)) }.getOrNull() ?: continue
            if (hello.optString("app") != "longarm") continue
            val address = "${reply.address.hostAddress}:${hello.optInt("port", DEFAULT_PORT)}"
            found += Found(hello.optString("name", address), hello.optString("fingerprint"), listOf(address))
        }
        found
    }
}

/**
 * Records the certificate presented during pairing, before any secret has been exchanged. A fingerprint learned from
 * discovery must match. The code the owner compares is what proves this certificate belongs to their device.
 */
private class FirstUseTrustManager(private val expected: String) : X509TrustManager {
    @Volatile
    var seen = ""
        private set

    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
        val digest = MessageDigest.getInstance("SHA-256").digest(chain[0].encoded)
        seen = digest.joinToString("") { "%02x".format(it) }
        if (expected.isNotBlank() && seen != expected) throw CertificateException("Unexpected certificate")
    }

    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {
        throw CertificateException("Client certificates aren't used")
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}

private fun pairCode(fingerprint: String, nonce: String): String {
    val digest = MessageDigest.getInstance("SHA-256").digest((fingerprint + nonce).toByteArray())
    val hex = digest.joinToString("") { "%02x".format(it) }
    return "%06d".format(hex.take(12).toLong(16) % 1_000_000)
}

private fun randomNonce(): String {
    val bytes = ByteArray(16).also { SecureRandom().nextBytes(it) }
    return bytes.joinToString("") { "%02x".format(it) }
}

/** Asks the device to pair, reports the code to compare, and waits for the owner's answer. */
suspend fun runPairing(item: Found, onCode: (String) -> Unit): Device = withContext(Dispatchers.IO) {
    val trust = FirstUseTrustManager(item.fingerprint)
    val sockets = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), SecureRandom()) }.socketFactory

    fun post(address: String, path: String, body: JSONObject): Pair<Int, JSONObject> {
        val connection = URL("https://$address$path").openConnection() as HttpsURLConnection
        try {
            connection.sslSocketFactory = sockets
            connection.setHostnameVerifier { _, _ -> true }
            connection.connectTimeout = 2500
            connection.readTimeout = 8000
            connection.requestMethod = "POST"
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.use { it.write(body.toString().toByteArray()) }
            val code = connection.responseCode
            val stream = if (code >= 400) connection.errorStream else connection.inputStream
            return code to JSONObject(stream?.bufferedReader()?.use { it.readText() } ?: "{}")
        } finally {
            connection.disconnect()
        }
    }

    val nonce = randomNonce()
    val request = JSONObject().put("nonce", nonce).put("phone", Build.MODEL)
    var chosen = ""
    var started: Pair<Int, JSONObject>? = null
    var failure: java.io.IOException? = null
    for (address in item.addresses) {
        try {
            started = post(address, "/pair/request", request)
            chosen = address
            break
        } catch (e: java.io.IOException) {
            failure = e
        }
    }
    val (status, reply) = started ?: throw (failure ?: java.io.IOException("No address"))
    if (status == 429) throw ApiException("${item.name} is busy with another request. Try again in a moment.")
    if (status != 202) throw ApiException("${item.name} refused the request")

    val fingerprint = trust.seen
    onCode(pairCode(fingerprint, nonce))
    val poll = JSONObject().put("id", reply.getString("id"))
    val deadline = System.currentTimeMillis() + PAIRING_LIFETIME_MS
    while (System.currentTimeMillis() < deadline) {
        delay(POLL_MS)
        val (_, answer) = post(chosen, "/pair/poll", poll)
        when (answer.optString("status")) {
            "pending" -> continue
            "approved" -> {
                val addresses = answer.getJSONArray("addresses")
                val all = List(addresses.length()) { addresses.getString(it) }
                val name = answer.optString("name", item.name)
                if (trust.seen != fingerprint) throw ApiException("${item.name}'s identity changed during pairing")
                return@withContext Device(
                    UUID.randomUUID().toString(),
                    name,
                    all.ifEmpty { listOf(chosen) },
                    answer.getString("token"),
                    fingerprint,
                )
            }
            "denied" -> throw ApiException("${item.name} refused the connection")
            else -> throw ApiException("The request to ${item.name} expired")
        }
    }
    throw ApiException("The request to ${item.name} expired")
}
