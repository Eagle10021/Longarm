package io.github.eagle10021.longarm

import java.io.IOException
import java.net.URL
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509TrustManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class Screen(val id: String, val label: String, val internal: Boolean, val percent: Int)

data class KeyboardState(
    val effect: String,
    val colors: List<String>,
    val brightness: String,
    val speed: Int,
    val profile: String,
    val profiles: List<String>,
    val effects: List<String>,
)

data class BatteryState(val percent: Int, val status: String, val watts: Int, val estimate: String)

data class PowerState(val chargeMode: String, val profile: String?, val profiles: List<String>)

data class Disk(val path: String, val percent: Int)

data class SystemState(
    val cpuPercent: Int,
    val temperature: Int?,
    val memoryPercent: Int,
    val uptimeSeconds: Long,
    val disks: List<Disk>,
)

data class Container(val name: String, val running: Boolean, val status: String, val restarting: Boolean)

data class AiEngine(val id: String, val label: String)

data class AiState(val active: String?, val busy: Boolean, val engines: List<AiEngine>)

data class CardRow(val label: String, val value: String, val warning: Boolean)

data class Card(val title: String, val rows: List<CardRow>)

/** One reading from a device. A section is null or empty when that device doesn't have it. */
data class DeviceState(
    val keyboard: KeyboardState?,
    val battery: BatteryState?,
    val power: PowerState?,
    val system: SystemState?,
    val containers: List<Container>?,
    val ai: AiState?,
    val cards: List<Card>,
    val screens: List<Screen>,
    val volumePercent: Int?,
    val muted: Boolean,
)

class ApiException(message: String) : Exception(message)

/** Accepts only the certificate whose fingerprint came from the pairing code. */
private class PinnedTrustManager(private val fingerprint: String) : X509TrustManager {
    override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
        val digest = MessageDigest.getInstance("SHA-256").digest(chain[0].encoded)
        if (digest.joinToString("") { "%02x".format(it) } != fingerprint) {
            throw CertificateException("Certificate fingerprint doesn't match")
        }
    }

    override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) {
        throw CertificateException("Client certificates aren't used")
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
}

/** Talks to Legion Control or the Longarm agent, trying the device's other addresses when one can't be reached. */
class DeviceApi(private val addresses: List<String>, private val token: String, fingerprint: String) {
    private val sockets: SSLSocketFactory = SSLContext.getInstance("TLS").apply {
        init(null, arrayOf(PinnedTrustManager(fingerprint)), SecureRandom())
    }.socketFactory

    @Volatile
    private var preferred = addresses.firstOrNull().orEmpty()

    suspend fun state(): DeviceState = withContext(Dispatchers.IO) { parse(JSONObject(request("GET", "/state", null))) }

    /** The address the last connection used. */
    val currentAddress: String get() = preferred

    suspend fun specs(): List<CardRow> = withContext(Dispatchers.IO) {
        val rows = JSONObject(request("GET", "/specs", null)).getJSONArray("specs")
        List(rows.length()) { i ->
            rows.getJSONObject(i).let { CardRow(it.getString("label"), it.getString("value"), false) }
        }
    }

    suspend fun peers(): List<Found> = withContext(Dispatchers.IO) {
        val peers = JSONObject(request("GET", "/peers", null)).getJSONArray("peers")
        List(peers.length()) { i ->
            val peer = peers.getJSONObject(i)
            Found(peer.getString("name"), peer.getString("fingerprint"), listOf(peer.getString("address")))
        }
    }

    suspend fun post(path: String, body: JSONObject) {
        withContext(Dispatchers.IO) { request("POST", path, body.toString()) }
    }

    /** Connects before anything is sent, so a request is never repeated on a second address. */
    private fun connect(method: String, path: String, hasBody: Boolean): HttpsURLConnection {
        var lastError: IOException? = null
        for (address in listOf(preferred) + addresses.filter { it != preferred }) {
            val connection = URL("https://$address$path").openConnection() as HttpsURLConnection
            connection.sslSocketFactory = sockets
            // The address can't appear in a self-signed certificate; the fingerprint check identifies the device
            connection.setHostnameVerifier { _, _ -> true }
            connection.connectTimeout = CONNECT_TIMEOUT_MS
            connection.readTimeout = READ_TIMEOUT_MS
            connection.requestMethod = method
            connection.setRequestProperty("Authorization", "Bearer $token")
            if (hasBody) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
            }
            try {
                connection.connect()
                preferred = address
                return connection
            } catch (e: IOException) {
                lastError = e
                connection.disconnect()
            }
        }
        if (lastError is SSLHandshakeException) throw ApiException("This device's identity has changed. Pair it again.")
        throw lastError ?: IOException("No address for this device")
    }

    private fun request(method: String, path: String, body: String?): String {
        val connection = connect(method, path, body != null)
        try {
            if (body != null) connection.outputStream.use { it.write(body.toByteArray()) }
            val code = connection.responseCode
            if (code == 401) throw ApiException("This device rejected the token. Pair it again.")
            if (code >= 400) throw ApiException("The device answered with error $code")
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private fun parse(json: JSONObject): DeviceState {
        val volume = json.optJSONObject("volume")
        val screens = json.optJSONArray("screens") ?: JSONArray()
        return DeviceState(
            keyboard = json.optJSONObject("keyboard")?.let(::parseKeyboard),
            battery = json.optJSONObject("battery")?.let(::parseBattery),
            power = if (json.has("charge_mode")) parsePower(json) else null,
            system = json.optJSONObject("system")?.let(::parseSystem),
            containers = json.optJSONArray("containers")?.let(::parseContainers),
            ai = json.optJSONObject("ai")?.let(::parseAi),
            cards = parseCards(json.optJSONArray("cards") ?: JSONArray()),
            screens = List(screens.length()) { i ->
                val screen = screens.getJSONObject(i)
                Screen(
                    screen.getString("id"),
                    screen.getString("label"),
                    screen.getBoolean("internal"),
                    screen.getInt("percent"),
                )
            },
            volumePercent = volume?.getInt("percent"),
            muted = volume?.getBoolean("muted") ?: false,
        )
    }

    private fun parseKeyboard(keyboard: JSONObject) = KeyboardState(
        effect = keyboard.getString("effect"),
        colors = keyboard.getJSONArray("colors").strings(),
        brightness = keyboard.getString("brightness"),
        speed = keyboard.getInt("speed"),
        profile = keyboard.getString("profile"),
        profiles = keyboard.getJSONArray("profiles").strings(),
        effects = keyboard.getJSONArray("effects").strings(),
    )

    private fun parseBattery(battery: JSONObject) = BatteryState(
        percent = battery.getInt("capacity"),
        status = battery.getString("status_text"),
        watts = battery.optInt("wattage"),
        estimate = battery.optString("time_str"),
    )

    private fun parsePower(json: JSONObject) = PowerState(
        chargeMode = json.getString("charge_mode"),
        profile = if (json.isNull("power_profile")) null else json.getString("power_profile"),
        profiles = (json.optJSONArray("power_profiles") ?: JSONArray()).strings(),
    )

    private fun parseSystem(system: JSONObject): SystemState {
        val disks = system.optJSONArray("disks") ?: JSONArray()
        return SystemState(
            cpuPercent = system.getInt("cpu_percent"),
            temperature = if (system.isNull("temperature")) null else system.getInt("temperature"),
            memoryPercent = system.getInt("memory_percent"),
            uptimeSeconds = system.getLong("uptime_s"),
            disks = List(disks.length()) { i ->
                disks.getJSONObject(i).let { Disk(it.getString("path"), it.getInt("percent")) }
            },
        )
    }

    private fun parseAi(ai: JSONObject): AiState {
        val engines = ai.getJSONArray("engines")
        return AiState(
            active = if (ai.isNull("active")) null else ai.getString("active"),
            busy = ai.optBoolean("busy"),
            engines = List(engines.length()) { i ->
                engines.getJSONObject(i).let { AiEngine(it.getString("id"), it.getString("label")) }
            },
        )
    }

    private fun parseContainers(containers: JSONArray) = List(containers.length()) { i ->
        val item = containers.getJSONObject(i)
        Container(
            item.getString("name"),
            item.getBoolean("running"),
            item.getString("status"),
            item.optBoolean("restarting"),
        )
    }

    private fun parseCards(cards: JSONArray) = List(cards.length()) { i ->
        val card = cards.getJSONObject(i)
        val rows = card.optJSONArray("rows") ?: JSONArray()
        Card(
            card.getString("title"),
            List(rows.length()) { r ->
                val row = rows.getJSONObject(r)
                CardRow(row.getString("label"), row.getString("value"), row.optBoolean("warning"))
            },
        )
    }

    private fun JSONArray.strings() = List(length()) { getString(it) }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 2500
        const val READ_TIMEOUT_MS = 8000
    }
}
