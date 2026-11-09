@file:OptIn(ExperimentalMaterial3Api::class)

package io.github.eagle10021.longarm

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.edit
import androidx.core.graphics.ColorUtils
import java.io.File
import java.util.UUID
import kotlin.math.max
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

private const val MATERIAL_YOU = "material"
private const val DEFAULT_ACCENT = "1e88e5"
private const val DEFAULT_DIM = 60
private const val MAX_IMAGE_SIDE = 2400
private val HEX_COLOUR = Regex("[0-9a-f]{6}")
private val REFRESH_CHOICES = listOf(1, 3, 5, 10, 30)
private val THEME_MODES = listOf("system" to "System", "light" to "Light", "dark" to "Dark")
private val PRESETS = listOf(
    "crimson" to ("Crimson" to "c62839"),
    "ocean" to ("Ocean" to "1e88e5"),
    "jade" to ("Jade" to "2e9d76"),
    "violet" to ("Violet" to "7e57c2"),
    "amber" to ("Amber" to "f9a825"),
)
private val PALETTE = listOf("c62839", "ff7a00", "f9a825", "2e9d76", "00acc1", "1e88e5", "3949ab", "7e57c2", "d81b60")

/** A theme made by the owner. [background] is a file name in the app's private storage, or blank. */
data class CustomTheme(
    val id: String,
    val name: String,
    val accent: String,
    val background: String,
    val dim: Int,
)

/** What the screen uses: a null accent means Material You. */
data class ActiveTheme(val accent: String?, val background: String, val dim: Int)

class SettingsStore(private val context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val backgrounds = File(context.filesDir, "backgrounds").apply { mkdirs() }

    var themeMode by mutableStateOf(prefs.getString("theme_mode", "system") ?: "system")
        private set
    var themeId by mutableStateOf(prefs.getString("theme", MATERIAL_YOU) ?: MATERIAL_YOU)
        private set
    var customThemes by mutableStateOf(loadThemes())
        private set
    var refreshSeconds by mutableStateOf(prefs.getInt("refresh_seconds", 3))
        private set
    var confirmRestart by mutableStateOf(prefs.getBoolean("confirm_restart", true))
        private set
    var confirmAi by mutableStateOf(prefs.getBoolean("confirm_ai", false))
        private set
    var confirmPower by mutableStateOf(prefs.getBoolean("confirm_power", false))
        private set

    /** The theme being edited, previewed across the app until it's saved or cancelled. */
    var draft by mutableStateOf<CustomTheme?>(null)
        private set
    private var draftIsNew = false

    val activeTheme: ActiveTheme
        get() {
            draft?.let { return ActiveTheme(it.accent, it.background, it.dim) }
            customThemes.firstOrNull { it.id == themeId }?.let { return ActiveTheme(it.accent, it.background, it.dim) }
            val preset = PRESETS.firstOrNull { it.first == themeId }?.second?.second
            return ActiveTheme(preset, "", 0)
        }

    fun backgroundFile(name: String) = File(backgrounds, name)

    private fun loadThemes(): List<CustomTheme> {
        val array = JSONArray(prefs.getString("custom_themes", "[]"))
        return List(array.length()) { i ->
            val item = array.getJSONObject(i)
            CustomTheme(
                item.getString("id"),
                item.getString("name"),
                item.getString("accent"),
                item.optString("background"),
                item.optInt("dim", DEFAULT_DIM),
            )
        }
    }

    private fun saveThemes() {
        val array = JSONArray()
        customThemes.forEach {
            array.put(
                JSONObject()
                    .put("id", it.id)
                    .put("name", it.name)
                    .put("accent", it.accent)
                    .put("background", it.background)
                    .put("dim", it.dim),
            )
        }
        prefs.edit { putString("custom_themes", array.toString()) }
    }

    fun setMode(mode: String) {
        themeMode = mode
        prefs.edit { putString("theme_mode", mode) }
    }

    fun selectTheme(id: String) {
        themeId = id
        prefs.edit { putString("theme", id) }
    }

    fun setRefresh(seconds: Int) {
        refreshSeconds = seconds
        prefs.edit { putInt("refresh_seconds", seconds) }
    }

    fun updateConfirmRestart(on: Boolean) {
        confirmRestart = on
        prefs.edit { putBoolean("confirm_restart", on) }
    }

    fun updateConfirmAi(on: Boolean) {
        confirmAi = on
        prefs.edit { putBoolean("confirm_ai", on) }
    }

    fun updateConfirmPower(on: Boolean) {
        confirmPower = on
        prefs.edit { putBoolean("confirm_power", on) }
    }

    fun startNewTheme() {
        draftIsNew = true
        draft = CustomTheme(UUID.randomUUID().toString(), "", DEFAULT_ACCENT, "", DEFAULT_DIM)
    }

    fun startEditing(theme: CustomTheme) {
        draftIsNew = false
        draft = theme
    }

    fun updateDraft(change: (CustomTheme) -> CustomTheme) {
        draft = draft?.let(change)
    }

    private fun savedBackground(id: String) = customThemes.firstOrNull { it.id == id }?.background.orEmpty()

    /** Removes an image the draft no longer uses, unless the saved theme still needs it. */
    private fun discard(name: String, keep: String) {
        if (name.isNotBlank() && name != keep) backgroundFile(name).delete()
    }

    /** Copies the chosen picture into private storage, scaled down, and puts it on the draft. */
    suspend fun importBackground(uri: Uri) {
        val name = "${UUID.randomUUID()}.jpg"
        val copied = withContext(Dispatchers.IO) {
            runCatching {
                val source = ImageDecoder.createSource(context.contentResolver, uri)
                val bitmap = ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
                    val largest = max(info.size.width, info.size.height)
                    decoder.setTargetSampleSize(max(1, largest / MAX_IMAGE_SIDE))
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                }
                backgroundFile(name).outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 88, it) }
            }.getOrDefault(false)
        }
        if (!copied) return
        val current = draft ?: return
        discard(current.background, savedBackground(current.id))
        updateDraft { it.copy(background = name) }
    }

    fun clearDraftBackground() {
        val current = draft ?: return
        discard(current.background, savedBackground(current.id))
        updateDraft { it.copy(background = "") }
    }

    fun saveDraft() {
        val current = draft ?: return
        val theme = current.copy(name = current.name.trim().ifBlank { "My theme" })
        discard(savedBackground(theme.id), theme.background)
        customThemes = customThemes.filter { it.id != theme.id } + theme
        saveThemes()
        selectTheme(theme.id)
        draft = null
    }

    fun cancelDraft() {
        val current = draft ?: return
        discard(current.background, savedBackground(current.id))
        draft = null
    }

    fun deleteDraft() {
        val current = draft ?: return
        discard(current.background, "")
        discard(savedBackground(current.id), "")
        customThemes = customThemes.filter { it.id != current.id }
        saveThemes()
        if (themeId == current.id) selectTheme(MATERIAL_YOU)
        draft = null
    }

    val editingExisting: Boolean get() = !draftIsNew
}

private fun colourOf(hex: String) = Color(android.graphics.Color.parseColor("#$hex"))

/** A light or dark colour scheme whose tones are all derived from one accent colour. */
fun schemeFromAccent(hex: String, dark: Boolean): ColorScheme {
    val hsl = FloatArray(3)
    ColorUtils.colorToHSL(colourOf(hex).toArgb(), hsl)
    val hue = hsl[0]
    val vivid = hsl[1].coerceIn(0.35f, 0.9f)
    val muted = vivid * 0.3f
    fun tone(lightness: Float, saturation: Float = vivid) =
        Color(ColorUtils.HSLToColor(floatArrayOf(hue, saturation, lightness)))

    return if (dark) {
        darkColorScheme(
            primary = tone(0.76f),
            onPrimary = tone(0.14f),
            primaryContainer = tone(0.28f),
            onPrimaryContainer = tone(0.9f),
            secondary = tone(0.74f, muted),
            onSecondary = tone(0.15f, muted),
            secondaryContainer = tone(0.26f, muted),
            onSecondaryContainer = tone(0.88f, muted),
            tertiary = tone(0.8f, muted),
            background = tone(0.07f, muted),
            onBackground = tone(0.9f, muted),
            surface = tone(0.07f, muted),
            onSurface = tone(0.9f, muted),
            surfaceVariant = tone(0.2f, muted),
            onSurfaceVariant = tone(0.78f, muted),
            outline = tone(0.55f, muted),
            outlineVariant = tone(0.28f, muted),
            surfaceContainerLowest = tone(0.05f, muted),
            surfaceContainerLow = tone(0.1f, muted),
            surfaceContainer = tone(0.12f, muted),
            surfaceContainerHigh = tone(0.15f, muted),
            surfaceContainerHighest = tone(0.19f, muted),
        )
    } else {
        lightColorScheme(
            primary = tone(0.36f),
            onPrimary = tone(0.98f),
            primaryContainer = tone(0.88f),
            onPrimaryContainer = tone(0.14f),
            secondary = tone(0.36f, muted),
            onSecondary = tone(0.98f, muted),
            secondaryContainer = tone(0.88f, muted),
            onSecondaryContainer = tone(0.15f, muted),
            tertiary = tone(0.32f, muted),
            background = tone(0.97f, muted),
            onBackground = tone(0.1f, muted),
            surface = tone(0.97f, muted),
            onSurface = tone(0.1f, muted),
            surfaceVariant = tone(0.89f, muted),
            onSurfaceVariant = tone(0.3f, muted),
            outline = tone(0.5f, muted),
            outlineVariant = tone(0.8f, muted),
            surfaceContainerLowest = tone(1f, muted),
            surfaceContainerLow = tone(0.95f, muted),
            surfaceContainer = tone(0.93f, muted),
            surfaceContainerHigh = tone(0.91f, muted),
            surfaceContainerHighest = tone(0.89f, muted),
        )
    }
}

/** Draws the theme's picture behind [content], dimmed towards the background colour so text stays readable. */
@Composable
fun ThemedBackground(store: SettingsStore, theme: ActiveTheme, content: @Composable BoxScope.() -> Unit) {
    val image by produceState<ImageBitmap?>(null, theme.background) {
        value = if (theme.background.isBlank()) {
            null
        } else {
            withContext(Dispatchers.IO) {
                BitmapFactory.decodeFile(store.backgroundFile(theme.background).path)?.asImageBitmap()
            }
        }
    }
    val base = MaterialTheme.colorScheme.background
    Box(Modifier.fillMaxSize().background(base)) {
        image?.let {
            Image(it, contentDescription = null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            Box(Modifier.fillMaxSize().background(base.copy(alpha = theme.dim / 100f)))
        }
        content()
    }
}

@Composable
private fun SettingsSection(title: String, content: @Composable () -> Unit) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun SegmentedChoice(options: List<Pair<String, String>>, selected: String, onPick: (String) -> Unit) {
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        options.forEachIndexed { index, (value, label) ->
            SegmentedButton(
                selected = value == selected,
                onClick = { onPick(value) },
                shape = SegmentedButtonDefaults.itemShape(index, options.size),
            ) { Text(label) }
        }
    }
}

@Composable
private fun ThemeRow(
    name: String,
    swatch: Color?,
    selected: Boolean,
    onClick: () -> Unit,
    onEdit: (() -> Unit)? = null,
) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        val fill = swatch ?: MaterialTheme.colorScheme.primary
        val ring = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outlineVariant
        Box(
            Modifier
                .size(28.dp)
                .clip(CircleShape)
                .background(fill)
                .border(if (selected) 3.dp else 1.dp, ring, CircleShape),
        )
        Text(name, Modifier.weight(1f))
        if (selected) Text("In use", style = MaterialTheme.typography.bodySmall)
        if (onEdit != null) TextButton(onEdit) { Text("Edit") }
    }
}

@Composable
private fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked, onChange)
    }
}

@Composable
fun SettingsTab(store: SettingsStore) {
    SettingsSection("Appearance") {
        SegmentedChoice(THEME_MODES, store.themeMode) { store.setMode(it) }
    }
    SettingsSection("Theme") {
        ThemeRow("Material You", null, store.themeId == MATERIAL_YOU, { store.selectTheme(MATERIAL_YOU) })
        PRESETS.forEach { (id, details) ->
            ThemeRow(details.first, colourOf(details.second), store.themeId == id, { store.selectTheme(id) })
        }
        store.customThemes.sortedBy { it.name.lowercase() }.forEach { theme ->
            ThemeRow(
                theme.name,
                colourOf(theme.accent),
                store.themeId == theme.id,
                { store.selectTheme(theme.id) },
                { store.startEditing(theme) },
            )
        }
        Button({ store.startNewTheme() }) { Text("New custom theme") }
    }
    SettingsSection("Refresh") {
        Text("How often readings update while the app is open. Slower uses less battery.")
        SegmentedChoice(REFRESH_CHOICES.map { "$it" to "${it}s" }, "${store.refreshSeconds}") {
            store.setRefresh(it.toInt())
        }
    }
    SettingsSection("Ask before") {
        SwitchRow("Restarting a container", store.confirmRestart) { store.updateConfirmRestart(it) }
        SwitchRow("Switching the AI engine", store.confirmAi) { store.updateConfirmAi(it) }
        SwitchRow("Changing power or charging", store.confirmPower) { store.updateConfirmPower(it) }
    }
}

private fun hueOf(hex: String): Float {
    val hsl = FloatArray(3)
    ColorUtils.colorToHSL(colourOf(hex).toArgb(), hsl)
    return hsl[0]
}

private fun hexFromHue(hue: Float) =
    "%06x".format(android.graphics.Color.HSVToColor(floatArrayOf(hue, 0.75f, 0.85f)) and 0xFFFFFF)

/** Full-screen editor. Every change previews across the app, and only Save keeps it. */
@Composable
fun ThemeEditor(store: SettingsStore, theme: CustomTheme) {
    val scope = rememberCoroutineScope()
    var hex by androidx.compose.runtime.saveable.rememberSaveable(theme.id) { mutableStateOf(theme.accent) }
    val pickImage = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) scope.launch { store.importBackground(uri) }
    }
    LaunchedEffect(theme.accent) { if (HEX_COLOUR.matches(hex) && hex != theme.accent) hex = theme.accent }
    BackHandler { store.cancelDraft() }

    Text(if (store.editingExisting) "Edit theme" else "New theme", style = MaterialTheme.typography.headlineMedium)
    OutlinedTextField(
        theme.name,
        { value -> store.updateDraft { it.copy(name = value) } },
        Modifier.fillMaxWidth(),
        label = { Text("Name") },
        singleLine = true,
    )
    SettingsSection("Accent colour") {
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PALETTE.forEach { colour ->
                val selected = colour == theme.accent
                val colours = MaterialTheme.colorScheme
                val ring = if (selected) colours.onSurface else colours.outlineVariant
                Box(
                    Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(colourOf(colour))
                        .border(if (selected) 3.dp else 1.dp, ring, CircleShape)
                        .clickable {
                            hex = colour
                            store.updateDraft { it.copy(accent = colour) }
                        },
                )
            }
        }
        Slider(
            hueOf(theme.accent),
            { hue ->
                val colour = hexFromHue(hue)
                hex = colour
                store.updateDraft { it.copy(accent = colour) }
            },
            valueRange = 0f..359f,
        )
        OutlinedTextField(
            hex,
            { typed ->
                hex = typed.trim().removePrefix("#").lowercase().take(6)
                if (HEX_COLOUR.matches(hex)) store.updateDraft { it.copy(accent = hex) }
            },
            Modifier.fillMaxWidth(),
            label = { Text("Hex colour") },
            singleLine = true,
            isError = !HEX_COLOUR.matches(hex),
        )
    }
    SettingsSection("Background picture") {
        Text(if (theme.background.isBlank()) "None" else "Picture chosen")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button({
                pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            }) { Text("Choose picture") }
            if (theme.background.isNotBlank()) TextButton({ store.clearDraftBackground() }) { Text("Remove") }
        }
        if (theme.background.isNotBlank()) {
            Text("Dimming: ${theme.dim}%")
            Slider(
                theme.dim.toFloat(),
                { value -> store.updateDraft { it.copy(dim = value.toInt()) } },
                valueRange = 0f..95f,
            )
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button({ store.saveDraft() }, enabled = HEX_COLOUR.matches(hex)) { Text("Save") }
        TextButton({ store.cancelDraft() }) { Text("Cancel") }
        if (store.editingExisting) TextButton({ store.deleteDraft() }) { Text("Delete") }
    }
}
