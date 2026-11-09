package io.github.eagle10021.longarm

import android.app.Application
import android.content.Context
import android.net.Uri
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.edit
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

data class Device(
    val id: String,
    val name: String,
    val addresses: List<String>,
    val token: String,
    val fingerprint: String,
)

/** The parts of a pairing code: longarm://pair?n=name&a=host:port,host:port&t=token&f=fingerprint */
data class Pairing(val name: String, val addresses: List<String>, val token: String, val fingerprint: String)

fun parsePairingCode(text: String): Pairing? {
    val uri = runCatching { Uri.parse(text.trim()) }.getOrNull() ?: return null
    if (uri.scheme != "longarm" || !uri.isHierarchical) return null
    val addresses = uri.getQueryParameter("a").orEmpty().split(",").filter { it.isNotBlank() }
    val token = uri.getQueryParameter("t").orEmpty()
    val fingerprint = uri.getQueryParameter("f").orEmpty().lowercase()
    if (addresses.isEmpty() || token.isBlank() || !fingerprint.matches(Regex("[0-9a-f]{64}"))) return null
    return Pairing(uri.getQueryParameter("n").orEmpty(), addresses, token, fingerprint)
}

class DeviceViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("connection", Context.MODE_PRIVATE)
    private var api: DeviceApi? = null
    private val senders = HashMap<String, Channel<suspend () -> Unit>>()
    val settings = SettingsStore(app)

    // After a change, polls are ignored briefly so an older reading can't move a slider back
    private var holdPollsUntil = 0L

    var devices by mutableStateOf(loadDevices())
        private set
    var current by mutableStateOf<Device?>(null)
        private set
    var state by mutableStateOf<DeviceState?>(null)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var connecting by mutableStateOf(false)
        private set
    var specs by mutableStateOf<List<CardRow>?>(null)
        private set
    var found by mutableStateOf<List<Found>>(emptyList())
        private set
    var scanning by mutableStateOf(false)
        private set
    var pairing by mutableStateOf<PairingProgress?>(null)
        private set
    var connectedAddress by mutableStateOf("")
        private set
    private var pairingJob: Job? = null

    init {
        val saved = prefs.getString("current", null)
        (devices.firstOrNull { it.id == saved } ?: devices.firstOrNull())?.let(::select)
    }

    private fun loadDevices(): List<Device> {
        val array = JSONArray(prefs.getString("devices", "[]"))
        return List(array.length()) { i ->
            val item = array.getJSONObject(i)
            val addresses = item.getJSONArray("addresses")
            Device(
                item.getString("id"),
                item.getString("name"),
                List(addresses.length()) { addresses.getString(it) },
                item.getString("token"),
                item.getString("fingerprint"),
            )
        }
    }

    private fun saveDevices() {
        val array = JSONArray()
        devices.forEach {
            array.put(
                JSONObject()
                    .put("id", it.id)
                    .put("name", it.name)
                    .put("addresses", JSONArray(it.addresses))
                    .put("token", it.token)
                    .put("fingerprint", it.fingerprint),
            )
        }
        prefs.edit {
            putString("devices", array.toString())
            putString("current", current?.id)
        }
    }

    private fun apiFor(device: Device) = DeviceApi(device.addresses, device.token, device.fingerprint)

    fun select(device: Device) {
        current = device
        api = apiFor(device)
        state = null
        specs = null
        connectedAddress = ""
        error = null
        holdPollsUntil = 0L
        saveDevices()
    }

    fun loadSpecs() {
        val asked = api ?: return
        viewModelScope.launch {
            try {
                val rows = asked.specs()
                if (asked === api) specs = rows
            } catch (e: IOException) {
                error = describe(e)
            } catch (e: ApiException) {
                error = describe(e)
            } catch (e: JSONException) {
                error = describe(e)
            }
        }
    }

    /** Looks on the local network and asks every paired device for its Tailscale peers. */
    fun scan() {
        if (scanning) return
        scanning = true
        viewModelScope.launch {
            try {
                val lookups = devices.map { device ->
                    async { runCatching { apiFor(device).peers() }.getOrDefault(emptyList()) }
                } + async { runCatching { findOnLocalNetwork() }.getOrDefault(emptyList()) }
                val known = devices.map { Found(it.name, it.fingerprint, it.addresses) }
                found = mergeFound(known + lookups.awaitAll().flatten())
            } finally {
                scanning = false
            }
        }
    }

    fun isPaired(item: Found) = devices.any { it.fingerprint == item.fingerprint }

    /** Starts the approval steps with a device that was found, or typed in as [Found.addresses]. */
    fun pair(item: Found, done: () -> Unit) {
        pairingJob?.cancel()
        pairingJob = viewModelScope.launch {
            error = null
            pairing = PairingProgress(item.name, "", false)
            try {
                val device = runPairing(item) { code -> pairing = PairingProgress(item.name, code, true) }
                devices = devices.filter { it.fingerprint != device.fingerprint } + device
                select(device)
                done()
            } catch (e: IOException) {
                error = "Couldn't reach ${item.name}"
            } catch (e: ApiException) {
                error = e.message
            } catch (e: JSONException) {
                error = "${item.name} sent something unexpected"
            } finally {
                pairing = null
            }
        }
    }

    fun cancelPairing() {
        pairingJob?.cancel()
        pairing = null
    }

    fun setAiEngine(engine: String) {
        state = state?.let { s ->
            s.copy(ai = s.ai?.copy(busy = true, active = engine.takeIf { it != "off" }))
        }
        send("ai", "/ai", JSONObject().put("engine", engine))
    }

    /** Adds a device, or updates [existing], once it accepts the connection. A blank code keeps the old pairing. */
    fun save(existing: Device?, name: String, code: String, done: () -> Unit) {
        val pairing = if (code.isBlank()) null else parsePairingCode(code)
        if (code.isNotBlank() && pairing == null) {
            error = "That doesn't look like a Longarm pairing code"
            return
        }
        val device = Device(
            id = existing?.id ?: UUID.randomUUID().toString(),
            name = name.trim().ifBlank { pairing?.name.orEmpty() }.ifBlank { "Device" },
            addresses = pairing?.addresses ?: existing?.addresses.orEmpty(),
            token = pairing?.token ?: existing?.token.orEmpty(),
            fingerprint = pairing?.fingerprint ?: existing?.fingerprint.orEmpty(),
        )
        connecting = true
        error = null
        viewModelScope.launch {
            try {
                apiFor(device).state()
                devices = devices.filter { it.id != device.id } + device
                select(device)
                done()
            } catch (e: IOException) {
                error = describe(e)
            } catch (e: ApiException) {
                error = describe(e)
            } catch (e: JSONException) {
                error = describe(e)
            } finally {
                connecting = false
            }
        }
    }

    fun remove(device: Device) {
        devices = devices.filter { it.id != device.id }
        if (current?.id == device.id) {
            current = null
            api = null
            state = null
            error = null
            devices.firstOrNull()?.let(::select)
        }
        saveDevices()
    }

    fun clearError() {
        error = null
    }

    suspend fun refresh() {
        val asked = api ?: return
        try {
            val latest = asked.state()
            if (asked === api && SystemClock.elapsedRealtime() >= holdPollsUntil) state = latest
            if (asked === api) {
                error = null
                connectedAddress = asked.currentAddress
            }
        } catch (e: IOException) {
            if (asked === api) error = describe(e)
        } catch (e: ApiException) {
            if (asked === api) error = describe(e)
        } catch (e: JSONException) {
            if (asked === api) error = describe(e)
        }
    }

    private fun describe(e: Exception) = when (e) {
        is ApiException -> e.message ?: "Error"
        is IOException -> "Can't reach this device. Check that it's on and on your network or Tailscale."
        else -> "The device sent something unexpected"
    }

    /** Sends the newest change for a control, at most about eight times a second, so a slider can't queue up. */
    private fun send(key: String, path: String, body: JSONObject) {
        holdPollsUntil = SystemClock.elapsedRealtime() + HOLD_MS
        val target = api
        val channel = senders.getOrPut(key) {
            Channel<suspend () -> Unit>(Channel.CONFLATED).also { queue ->
                viewModelScope.launch {
                    for (work in queue) {
                        try {
                            work()
                            error = null
                        } catch (e: IOException) {
                            error = describe(e)
                        } catch (e: ApiException) {
                            error = describe(e)
                        }
                        delay(120)
                    }
                }
            }
        }
        channel.trySend { target?.post(path, body) }
    }

    private fun updateKeyboard(change: (KeyboardState) -> KeyboardState) {
        state = state?.let { s -> s.copy(keyboard = s.keyboard?.let(change)) }
    }

    fun setBrightness(screen: Screen, percent: Int) {
        state = state?.let { s ->
            s.copy(screens = s.screens.map { if (it.id == screen.id) it.copy(percent = percent) else it })
        }
        send("brightness-${screen.id}", "/brightness", JSONObject().put("screen", screen.id).put("percent", percent))
    }

    fun setVolume(percent: Int) {
        state = state?.copy(volumePercent = percent)
        send("volume", "/volume", JSONObject().put("percent", percent))
    }

    fun setMuted(muted: Boolean) {
        state = state?.copy(muted = muted)
        send("mute", "/volume", JSONObject().put("muted", muted))
    }

    fun restartContainer(container: Container) {
        state = state?.let { s ->
            s.copy(containers = s.containers?.map { if (it.name == container.name) it.copy(restarting = true) else it })
        }
        send("restart-${container.name}", "/containers/restart", JSONObject().put("name", container.name))
    }

    fun setEffect(effect: String) {
        updateKeyboard { it.copy(effect = effect, brightness = keepLit(it)) }
        send("effect", "/keyboard", JSONObject().put("effect", effect))
    }

    /** Colours one zone (0-3), or all four when zone is null. Coloured light also ends the "off" effect. */
    fun setColor(zone: Int?, hex: String) {
        val keyboard = state?.keyboard ?: return
        val colors = if (zone == null) {
            List(4) { hex }
        } else {
            keyboard.colors.mapIndexed { i, color -> if (i == zone) hex else color }
        }
        val effect = if (keyboard.effect == "off") "static" else keyboard.effect
        updateKeyboard { it.copy(colors = colors, effect = effect, brightness = keepLit(it)) }
        send("color", "/keyboard", JSONObject().put("colors", JSONArray(colors)).put("effect", effect))
    }

    fun setKeyboardBrightness(level: String) {
        updateKeyboard {
            val effect = if (level == "Off") "off" else if (it.effect == "off") "static" else it.effect
            it.copy(brightness = level, effect = effect)
        }
        send("level", "/keyboard", JSONObject().put("brightness", level))
    }

    fun setSpeed(speed: Int) {
        updateKeyboard { it.copy(speed = speed) }
        send("speed", "/keyboard", JSONObject().put("speed", speed))
    }

    fun setProfile(profile: String) {
        updateKeyboard { it.copy(profile = profile) }
        send("profile", "/keyboard", JSONObject().put("profile", profile))
        holdPollsUntil = 0L  // the profile's own colours arrive with the next reading
    }

    fun setPowerProfile(word: String, value: String) {
        state = state?.let { s -> s.copy(power = s.power?.copy(profile = value)) }
        send("power-profile", "/power", JSONObject().put("power_profile", word))
    }

    fun setChargeMode(word: String, label: String) {
        state = state?.let { s -> s.copy(power = s.power?.copy(chargeMode = label)) }
        send("charge-mode", "/power", JSONObject().put("charge_mode", word))
    }

    private fun keepLit(keyboard: KeyboardState) = keyboard.brightness.takeIf { it != "Off" } ?: "High"

    private companion object {
        const val HOLD_MS = 2000L
    }
}
