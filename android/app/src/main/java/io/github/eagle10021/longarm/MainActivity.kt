@file:OptIn(ExperimentalMaterial3Api::class)

package io.github.eagle10021.longarm

import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

private val EFFECT_NAMES = mapOf(
    "static" to "Static", "breath" to "Breath", "wave" to "Wave", "hue" to "Hue", "off" to "Off",
    "Police" to "Police", "Scanner" to "Scanner", "Heartbeat" to "Heartbeat", "Fire" to "Fire",
    "Battery" to "Battery", "Soft Wave" to "Soft wave",
)
private val PALETTE = listOf(
    "ff0000", "ff7a00", "ffd000", "39c5bb", "00ff00", "00b7ff", "0000ff", "8000ff", "ff00c8", "ffffff",
)
private val CHARGE_MODES = listOf(
    Triple("normal", "Normal Charging", "Normal"),
    Triple("conservation", "Conservation Mode", "Conserve"),
    Triple("rapid", "Rapid Charge", "Rapid"),
)
private val POWER_NAMES = mapOf(
    "low-power" to ("quiet" to "Quiet"),
    "balanced" to ("balanced" to "Balanced"),
    "performance" to ("performance" to "Performance"),
)

/** What fills the screen below the menu button. */
private sealed interface Page {
    data object Main : Page
    data object Settings : Page
    data object AddDevice : Page
    data class EditDevice(val device: Device) : Page
}

class MainActivity : ComponentActivity() {
    private val model: DeviceViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { LongarmApp(model) }
    }
}

@Composable
private fun LongarmApp(model: DeviceViewModel) {
    val context = LocalContext.current
    val settings = model.settings
    val dark = when (settings.themeMode) {
        "dark" -> true
        "light" -> false
        else -> isSystemInDarkTheme()
    }
    val theme = settings.activeTheme
    val colors = theme.accent?.let { schemeFromAccent(it, dark) }
        ?: if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    var page by remember { mutableStateOf<Page>(Page.Main) }
    val showPage = { next: Page ->
        model.clearError()
        page = next
    }
    MaterialTheme(colorScheme = colors) {
        ThemedBackground(settings, theme) {
            val drawer = rememberDrawerState(DrawerValue.Closed)
            val scope = rememberCoroutineScope()
            BackHandler(drawer.isOpen) { scope.launch { drawer.close() } }
            ModalNavigationDrawer(
                drawerContent = {
                    Menu(model, page) { action ->
                        action(showPage)
                        scope.launch { drawer.close() }
                    }
                },
                drawerState = drawer,
                gesturesEnabled = settings.draft == null,
            ) {
                Scaffold(
                    Modifier.fillMaxSize(),
                    containerColor = Color.Transparent,
                    contentColor = MaterialTheme.colorScheme.onBackground,
                ) { padding ->
                    Column(
                        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(padding).padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        AppContent(model, page, showPage) { scope.launch { drawer.open() } }
                    }
                }
            }
        }
    }
}

/** The hamburger menu: devices to switch between, adding and editing them, and Settings. */
@Composable
private fun Menu(model: DeviceViewModel, page: Page, choose: (((Page) -> Unit) -> Unit) -> Unit) {
    val current = model.current
    ModalDrawerSheet(Modifier.width(300.dp)) {
        Text("Longarm", Modifier.padding(24.dp), style = MaterialTheme.typography.titleLarge)
        Text(
            "Devices",
            Modifier.padding(horizontal = 24.dp, vertical = 8.dp),
            style = MaterialTheme.typography.labelLarge,
        )
        model.devices.sortedBy { it.name.lowercase() }.forEach { device ->
            MenuItem(device.name, page == Page.Main && device.id == current?.id) {
                choose { show ->
                    model.select(device)
                    show(Page.Main)
                }
            }
        }
        MenuItem("Add a device", page == Page.AddDevice) { choose { it(Page.AddDevice) } }
        if (current != null) {
            MenuItem("Edit ${current.name}", page is Page.EditDevice) { choose { it(Page.EditDevice(current)) } }
        }
        HorizontalDivider(Modifier.padding(vertical = 8.dp))
        MenuItem("Settings", page == Page.Settings) { choose { it(Page.Settings) } }
    }
}

@Composable
private fun MenuItem(label: String, selected: Boolean, onClick: () -> Unit) {
    NavigationDrawerItem(
        label = { Text(label) },
        selected = selected,
        onClick = onClick,
        modifier = Modifier.padding(horizontal = 12.dp),
    )
}

@Composable
private fun TopRow(title: String, onMenu: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconButton(onMenu, Modifier.semantics { contentDescription = "Open menu" }) {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                repeat(3) {
                    Box(Modifier.size(width = 20.dp, height = 2.dp).background(MaterialTheme.colorScheme.onBackground))
                }
            }
        }
        Text(title, Modifier.padding(start = 4.dp), style = MaterialTheme.typography.headlineSmall)
    }
}

@Composable
private fun AppContent(model: DeviceViewModel, page: Page, showPage: (Page) -> Unit, onMenu: () -> Unit) {
    val draft = model.settings.draft
    if (draft != null) {
        ThemeEditor(model.settings, draft)
        return
    }
    val toMain = { showPage(Page.Main) }
    when {
        page == Page.Settings -> {
            BackHandler(onBack = toMain)
            TopRow("Settings", onMenu)
            SettingsTab(model.settings)
        }
        model.devices.isEmpty() -> {
            TopRow("Add a device", onMenu)
            AddDevice(model, onClose = null)
        }
        page == Page.AddDevice -> {
            TopRow("Add a device", onMenu)
            AddDevice(model, onClose = toMain)
        }
        page is Page.EditDevice -> {
            TopRow("Edit ${page.device.name}", onMenu)
            DeviceForm(model, page.device, onClose = toMain)
        }
        else -> {
            TopRow(model.current?.name ?: "Longarm", onMenu)
            Controls(model)
        }
    }
}

private fun networkNames(addresses: List<String>) = addresses.map(::networkLabel).distinct()

@Composable
private fun AddDevice(model: DeviceViewModel, onClose: (() -> Unit)?) {
    var manual by rememberSaveable { mutableStateOf("") }
    val askPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { model.scan() }
    val context = LocalContext.current
    val findDevices = {
        val permission = "android.permission.ACCESS_LOCAL_NETWORK"
        if (ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED) {
            model.scan()
        } else {
            askPermission.launch(permission)
        }
    }
    LaunchedEffect(Unit) { findDevices() }
    if (onClose != null) BackHandler(onBack = onClose)

    Text(
        "Devices running Legion Control or the Longarm agent appear here. Tailscale devices " +
            "are listed once at least one device is added.",
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(if (model.scanning) "Searching" else "${model.found.size} found", Modifier.weight(1f))
        TextButton(findDevices, enabled = !model.scanning) { Text("Search again") }
    }
    model.found.forEach { item ->
        val paired = model.isPaired(item)
        ElevatedCard(Modifier.fillMaxWidth().clickable(enabled = !paired) { model.pair(item) { onClose?.invoke() } }) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(item.name, style = MaterialTheme.typography.titleMedium)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    networkNames(item.addresses).forEach { Label(it) }
                    if (paired) Text("Added", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
    Text("Or connect by hand", style = MaterialTheme.typography.titleMedium)
    OutlinedTextField(
        manual,
        { manual = it },
        Modifier.fillMaxWidth(),
        label = { Text("Address, such as 100.x.x.x, or a pairing code") },
    )
    model.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            {
                if (manual.trim().startsWith("longarm:")) {
                    model.save(null, "", manual) { onClose?.invoke() }
                } else {
                    val address = withDefaultPort(manual)
                    model.pair(Found(address, "", listOf(address))) { onClose?.invoke() }
                }
            },
            enabled = manual.isNotBlank() && !model.connecting,
        ) { Text("Connect") }
        if (onClose != null) TextButton(onClose) { Text("Cancel") }
    }
    model.pairing?.let { progress ->
        AlertDialog(
            onDismissRequest = {},
            title = { Text("Connect to ${progress.name}") },
            text = {
                Text(
                    if (progress.waiting) {
                        "Check that ${progress.name} shows this code, then allow it there.\n\n" +
                            "${progress.code.take(3)} ${progress.code.drop(3)}"
                    } else {
                        "Contacting ${progress.name}"
                    },
                )
            },
            confirmButton = { TextButton({ model.cancelPairing() }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun Label(text: String) {
    Text(
        text,
        Modifier
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .padding(horizontal = 10.dp, vertical = 3.dp),
        color = MaterialTheme.colorScheme.onSecondaryContainer,
        style = MaterialTheme.typography.labelMedium,
    )
}

@Composable
private fun DeviceForm(model: DeviceViewModel, device: Device, onClose: () -> Unit) {
    var name by rememberSaveable(device.id) { mutableStateOf(device.name) }
    var confirmRemove by remember { mutableStateOf(false) }
    BackHandler(onBack = onClose)

    OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("Name") }, singleLine = true)
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { networkNames(device.addresses).forEach { Label(it) } }
    model.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Button({ model.save(device, name, "", onClose) }, enabled = name.isNotBlank() && !model.connecting) {
            Text(if (model.connecting) "Connecting" else "Save")
        }
        TextButton(onClose) { Text("Cancel") }
        TextButton({ confirmRemove = true }) { Text("Remove") }
    }
    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text("Remove ${device.name}?") },
            text = { Text("The device itself isn't changed. It would need approving again to add it back.") },
            confirmButton = {
                TextButton({
                    model.remove(device)
                    confirmRemove = false
                    onClose()
                }) { Text("Remove") }
            },
            dismissButton = { TextButton({ confirmRemove = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun Controls(model: DeviceViewModel) {
    val owner = LocalLifecycleOwner.current
    val device = model.current
    LaunchedEffect(owner, device?.id) {
        owner.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                model.refresh()
                delay(model.settings.refreshSeconds * 1000L)
            }
        }
    }
    model.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
    val address = model.connectedAddress
    Text(
        if (address.isBlank()) "Connecting" else "Connected over ${networkLabel(address)}",
        style = MaterialTheme.typography.bodySmall,
    )
    val state = model.state
    var selectedTab by rememberSaveable(device?.id) { mutableStateOf(0) }
    val tabs = listOfNotNull("Controls", "Specs", "AI".takeIf { state?.ai != null })
    val tab = selectedTab.coerceAtMost(tabs.lastIndex)
    PrimaryTabRow(selectedTabIndex = tab) {
        tabs.forEachIndexed { index, title ->
            Tab(selected = index == tab, onClick = { selectedTab = index }, text = { Text(title) })
        }
    }
    when {
        tabs[tab] == "Specs" -> Specs(model)
        tabs[tab] == "AI" && state?.ai != null -> AiTab(model, state.ai)
        state == null -> Text(if (model.error == null) "Connecting" else "Waiting for this device")
        else -> ControlSections(model, state)
    }
}

@Composable
private fun Specs(model: DeviceViewModel) {
    val device = model.current
    LaunchedEffect(device?.id) { model.loadSpecs() }
    val rows = model.specs
    if (rows == null) {
        Text("Loading")
    } else {
        Section("About this device") { rows.forEach { Reading(it.label, it.value) } }
    }
}

@Composable
private fun AiTab(model: DeviceViewModel, ai: AiState) {
    Section("Local AI") {
        Text(
            if (ai.busy) "Switching" else "Only one engine runs at a time, since they share the graphics card.",
            style = MaterialTheme.typography.bodySmall,
        )
        val options = listOf("off" to "Off") + ai.engines.map { it.id to it.label }
        val ask = rememberConfirmer()
        Choices(options, ai.active ?: "off") { engine ->
            if (!ai.busy && engine != (ai.active ?: "off")) {
                val label = options.first { it.first == engine }.second
                ask(model.settings.confirmAi, "Switch local AI to $label?") { model.setAiEngine(engine) }
            }
        }
    }
}

@Composable
private fun ControlSections(model: DeviceViewModel, state: DeviceState) {
    state.battery?.let { Battery(it) }
    state.system?.let { System(it) }
    state.containers?.let { Containers(model, it) }
    state.cards.forEach { InfoCard(it) }
    Screens(model, state)
    Sound(model, state)
    state.keyboard?.let { Keyboard(model, it) }
    state.power?.let { Power(model, it) }
}

/** Returns a function that runs an action, first asking in a dialog when the first argument is true. */
@Composable
private fun rememberConfirmer(): (Boolean, String, () -> Unit) -> Unit {
    var question by remember { mutableStateOf<Pair<String, () -> Unit>?>(null) }
    question?.let { (title, action) ->
        AlertDialog(
            onDismissRequest = { question = null },
            title = { Text(title) },
            confirmButton = {
                TextButton({
                    action()
                    question = null
                }) { Text("Yes") }
            },
            dismissButton = { TextButton({ question = null }) { Text("Cancel") } },
        )
    }
    return { needed, title, action -> if (needed) question = title to action else action() }
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@Composable
private fun Battery(battery: BatteryState) {
    Section("Battery") {
        Text("${battery.percent}% · ${battery.status}" + if (battery.watts != 0) " · ${battery.watts} W" else "")
        if (battery.estimate.isNotBlank()) Text(battery.estimate, style = MaterialTheme.typography.bodySmall)
    }
}

private fun uptimeText(seconds: Long): String {
    val days = seconds / 86400
    val hours = seconds % 86400 / 3600
    val minutes = seconds % 3600 / 60
    return if (days > 0) "${days}d ${hours}h" else "${hours}h ${minutes}m"
}

@Composable
private fun Reading(label: String, value: String, valueColor: Color = Color.Unspecified) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, Modifier.padding(end = 16.dp))
        Text(value, Modifier.weight(1f), color = valueColor, textAlign = TextAlign.End)
    }
}

@Composable
private fun System(system: SystemState) {
    Section("System") {
        Reading("CPU", "${system.cpuPercent}%" + (system.temperature?.let { " · $it °C" } ?: ""))
        Reading("Memory", "${system.memoryPercent}%")
        system.disks.forEach { Reading("Disk ${it.path}", "${it.percent}%") }
        Reading("Up for", uptimeText(system.uptimeSeconds))
    }
}

@Composable
private fun InfoCard(card: Card) {
    Section(card.title) {
        card.rows.forEach { row ->
            Reading(row.label, row.value, if (row.warning) MaterialTheme.colorScheme.error else Color.Unspecified)
        }
    }
}

@Composable
private fun Containers(model: DeviceViewModel, containers: List<Container>) {
    var expanded by rememberSaveable { mutableStateOf(false) }
    var confirm by remember { mutableStateOf<Container?>(null) }
    val running = containers.count { it.running }
    val stopped = containers.filter { !it.running }
    Section("Containers") {
        Reading("Running", "$running of ${containers.size}")
        if (stopped.isNotEmpty()) {
            Text("Stopped: " + stopped.joinToString { it.name }, color = MaterialTheme.colorScheme.error)
        }
        TextButton({ expanded = !expanded }) { Text(if (expanded) "Hide containers" else "Show containers") }
        if (expanded) {
            containers.forEach { container ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(container.name)
                        Text(container.status, style = MaterialTheme.typography.bodySmall)
                    }
                    val restart = {
                        if (model.settings.confirmRestart) confirm = container else model.restartContainer(container)
                    }
                    TextButton(restart, enabled = !container.restarting) {
                        Text(if (container.restarting) "Restarting" else "Restart")
                    }
                }
            }
        }
    }
    confirm?.let { container ->
        AlertDialog(
            onDismissRequest = { confirm = null },
            title = { Text("Restart ${container.name}?") },
            text = { Text("Anything using it will be interrupted for a moment.") },
            confirmButton = {
                TextButton({
                    model.restartContainer(container)
                    confirm = null
                }) { Text("Restart") }
            },
            dismissButton = { TextButton({ confirm = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun PercentSlider(label: String, percent: Int, onChange: (Int) -> Unit) {
    Reading(label, "$percent%")
    Slider(percent.toFloat(), { onChange(it.toInt()) }, valueRange = 0f..100f)
}

@Composable
private fun Screens(model: DeviceViewModel, state: DeviceState) {
    if (state.screens.isEmpty()) return
    Section("Screens") {
        state.screens.forEach { screen ->
            val label = if (screen.internal) "Built-in screen" else screen.label
            PercentSlider(label, screen.percent) { model.setBrightness(screen, it) }
        }
    }
}

@Composable
private fun Sound(model: DeviceViewModel, state: DeviceState) {
    val level = state.volumePercent ?: return
    Section("Sound") {
        PercentSlider("Volume", level) { model.setVolume(it) }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("Mute", Modifier.weight(1f))
            Switch(state.muted, { model.setMuted(it) })
        }
    }
}

@Composable
private fun Choices(options: List<Pair<String, String>>, selected: String?, onPick: (String) -> Unit) {
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
private fun ChipRow(options: List<Pair<String, String>>, selected: String?, onPick: (String) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEach { (value, label) ->
            FilterChip(value == selected, { onPick(value) }, { Text(label) })
        }
    }
}

private fun parse(hex: String) = Color(android.graphics.Color.parseColor("#$hex"))

private fun hueHex(hue: Float) =
    "%06x".format(android.graphics.Color.HSVToColor(floatArrayOf(hue, 1f, 1f)) and 0xFFFFFF)

@Composable
private fun Swatch(hex: String, selected: Boolean, onClick: () -> Unit) {
    val ring = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
    Box(
        Modifier.size(40.dp).clip(CircleShape).background(parse(hex))
            .border(if (selected) 3.dp else 1.dp, ring, CircleShape).clickable(onClick = onClick),
    )
}

@Composable
private fun Keyboard(model: DeviceViewModel, keyboard: KeyboardState) {
    var zone by remember { mutableStateOf<Int?>(null) }
    var hue by remember { mutableStateOf(180f) }
    Section("Keyboard") {
        Choices(listOf("Off" to "Off", "Low" to "Low", "High" to "High"), keyboard.brightness) {
            model.setKeyboardBrightness(it)
        }
        Text("Effect", style = MaterialTheme.typography.labelLarge)
        val effects = keyboard.effects.filter { it != "off" }.map { it to (EFFECT_NAMES[it] ?: it) }
        ChipRow(effects, keyboard.effect) { model.setEffect(it) }
        Text("Speed", style = MaterialTheme.typography.labelLarge)
        Choices((1..4).map { "$it" to "$it" }, "${keyboard.speed}") { model.setSpeed(it.toInt()) }
        Text("Zones", style = MaterialTheme.typography.labelLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            FilterChip(zone == null, { zone = null }, { Text("All") })
            keyboard.colors.forEachIndexed { i, hex -> Swatch(hex, zone == i) { zone = i } }
        }
        Text("Colour", style = MaterialTheme.typography.labelLarge)
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            PALETTE.forEach { hex -> Swatch(hex, false) { model.setColor(zone, hex) } }
        }
        Slider(
            hue,
            {
                hue = it
                model.setColor(zone, hueHex(it))
            },
            valueRange = 0f..359f,
        )
        if (keyboard.profiles.size > 1) {
            Text("Profile", style = MaterialTheme.typography.labelLarge)
            ChipRow(keyboard.profiles.map { it to it }, keyboard.profile) { model.setProfile(it) }
        }
    }
}

@Composable
private fun Power(model: DeviceViewModel, power: PowerState) {
    val ask = rememberConfirmer()
    val askFirst = model.settings.confirmPower
    Section("Power") {
        val profiles = power.profiles.mapNotNull { value -> POWER_NAMES[value]?.let { value to it } }
        if (profiles.isNotEmpty()) {
            Text("Performance", style = MaterialTheme.typography.labelLarge)
            Choices(profiles.map { (value, names) -> value to names.second }, power.profile) { value ->
                val (word, label) = POWER_NAMES.getValue(value)
                ask(askFirst, "Change performance to $label?") { model.setPowerProfile(word, value) }
            }
        }
        Text("Charging", style = MaterialTheme.typography.labelLarge)
        Choices(CHARGE_MODES.map { it.second to it.third }, power.chargeMode) { label ->
            val mode = CHARGE_MODES.first { it.second == label }
            ask(askFirst, "Change charging to ${mode.third}?") { model.setChargeMode(mode.first, label) }
        }
    }
}
