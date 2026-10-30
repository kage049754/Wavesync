package com.wavesync.app

import android.Manifest
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioManager
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.ToneGenerator
import android.media.audiofx.Equalizer
import android.media.audiofx.Virtualizer
import android.os.Handler
import android.os.Looper
import java.util.UUID
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat

private val Ink = Color(0xFF090D16)
private val Panel = Color(0xFF151D2B)
private val Panel2 = Color(0xFF1C2738)
private val Cyan = Color(0xFF62E6D4)
private val Violet = Color(0xFF9B8CFF)
private val Muted = Color(0xFF8E9AAF)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = android.graphics.Color.rgb(9, 13, 22)
        window.navigationBarColor = android.graphics.Color.rgb(9, 13, 22)
        setContent { WaveSyncApp(this) }
    }
}

@Composable
private fun WaveSyncApp(activity: MainActivity) {
    var hasPermission by remember { mutableStateOf(hasBluetoothPermission(activity)) }
    var deviceName by remember { mutableStateOf("No earbuds selected") }
    var connected by remember { mutableStateOf(false) }
    val preferences = remember { activity.getSharedPreferences("wavesync_audio", Context.MODE_PRIVATE) }
    var autoReconnect by remember { mutableStateOf(preferences.getBoolean("auto_reconnect", true)) }
    var eqEnabled by remember { mutableStateOf(preferences.getBoolean("eq_enabled", true)) }
    var ambience by remember { mutableFloatStateOf(preferences.getFloat("ambience", 0.25f)) }
    var bass by remember { mutableFloatStateOf(preferences.getFloat("bass", 0.35f)) }
    var clarity by remember { mutableFloatStateOf(preferences.getFloat("clarity", 0.55f)) }
    var selectedPreset by remember { mutableStateOf(preferences.getString("preset", "Custom") ?: "Custom") }
    var message by remember { mutableStateOf("Pair your earbuds to get started") }
    var showDevices by remember { mutableStateOf(false) }
    var pairedDevices by remember { mutableStateOf(listOf<String>()) }
    var equalizerStatus by remember { mutableStateOf("Audio effects not initialized") }
    var leftBattery by remember { mutableStateOf<Int?>(if (preferences.contains("battery_left")) preferences.getInt("battery_left", -1).takeIf { it >= 0 } else null) }
    var rightBattery by remember { mutableStateOf<Int?>(if (preferences.contains("battery_right")) preferences.getInt("battery_right", -1).takeIf { it >= 0 } else null) }
    var caseBattery by remember { mutableStateOf<Int?>(if (preferences.contains("battery_case")) preferences.getInt("battery_case", -1).takeIf { it >= 0 } else null) }
    var genericBattery by remember { mutableStateOf<Int?>(if (preferences.contains("battery_generic")) preferences.getInt("battery_generic", -1).takeIf { it >= 0 } else null) }
    var batteryDeviceName by remember { mutableStateOf(preferences.getString("battery_device_name", "") ?: "") }
    var batteryStatus by remember { mutableStateOf("Tap SCAN to check standard Bluetooth battery reporting") }
    val batteryGattConnections = remember { mutableListOf<BluetoothGatt>() }
    val batteryServiceUuid = remember { UUID.fromString("0000180F-0000-1000-8000-00805F9B34FB") }
    val batteryLevelUuid = remember { UUID.fromString("00002A19-0000-1000-8000-00805F9B34FB") }
    val audioManager = remember { activity.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    val audioFx = remember { AudioFxController() }

    fun refreshAudioRoute() {
        val route = try {
            audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).firstOrNull { device ->
                device.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP ||
                    device.type == AudioDeviceInfo.TYPE_BLUETOOTH_SCO ||
                    (Build.VERSION.SDK_INT >= 31 && device.type == AudioDeviceInfo.TYPE_BLE_HEADSET)
            }
        } catch (_: Exception) { null }
        connected = route != null
        if (route != null) deviceName = route.productName?.toString()?.takeIf { it.isNotBlank() } ?: "Bluetooth audio device"
        else if (deviceName != "No earbuds selected") deviceName = "No active Bluetooth audio"
    }

    DisposableEffect(audioManager) {
        val callback = object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) { refreshAudioRoute() }
            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) { refreshAudioRoute() }
        }
        audioManager.registerAudioDeviceCallback(callback, Handler(Looper.getMainLooper()))
        refreshAudioRoute()
        onDispose {
            audioManager.unregisterAudioDeviceCallback(callback)
            audioFx.release()
            batteryGattConnections.toList().forEach { try { it.disconnect(); it.close() } catch (_: Exception) {} }
            batteryGattConnections.clear()
        }
    }

    LaunchedEffect(bass, clarity, ambience, selectedPreset, eqEnabled, autoReconnect) {
        preferences.edit()
            .putFloat("bass", bass)
            .putFloat("clarity", clarity)
            .putFloat("ambience", ambience)
            .putString("preset", selectedPreset)
            .putBoolean("eq_enabled", eqEnabled)
            .putBoolean("auto_reconnect", autoReconnect)
            .apply()
    }

    LaunchedEffect(eqEnabled, bass, clarity, ambience) {
        equalizerStatus = if (audioFx.apply(eqEnabled, bass, clarity, ambience)) {
            if (eqEnabled) "System output equalizer active where supported" else "Equalizer disabled"
        } else "This phone does not expose a compatible system audio effect"
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        hasPermission = hasBluetoothPermission(activity)
        if (hasPermission) message = "Bluetooth access enabled"
    }

    fun startBatteryDiscovery() {
        if (!hasPermission) {
            batteryStatus = "Bluetooth permission is required to read battery data"
            val permissions = if (Build.VERSION.SDK_INT >= 31) arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN) else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
            permissionLauncher.launch(permissions)
            return
        }
        leftBattery = null
        rightBattery = null
        caseBattery = null
        genericBattery = null
        batteryDeviceName = ""
        batteryStatus = "Checking paired earbuds for the standard Bluetooth Battery Service…"
        try {
            val adapter = (activity.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter
            val bonded = adapter?.bondedDevices.orEmpty()
            val scanner = adapter?.bluetoothLeScanner
            if (scanner == null) {
                batteryStatus = "Bluetooth LE scanning is unavailable on this phone"
                return
            }
            val bondedAddresses = bonded.map { it.address }.toSet()
            val callback = object : ScanCallback() {
                override fun onScanResult(callbackType: Int, result: ScanResult) {
                    val device = result.device
                    if (device.address !in bondedAddresses) return
                    if (batteryGattConnections.any { it.device.address == device.address }) return
                    try {
                        val gatt = device.connectGatt(activity, false, object : BluetoothGattCallback() {
                            override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
                                if (status == BluetoothGatt.GATT_SUCCESS && newState == BluetoothProfile.STATE_CONNECTED) {
                                    gatt.discoverServices()
                                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                                    try { gatt.close() } catch (_: Exception) {}
                                }
                            }
                            override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
                                if (status != BluetoothGatt.GATT_SUCCESS) return
                                val characteristic = gatt.getService(batteryServiceUuid)?.getCharacteristic(batteryLevelUuid)
                                if (characteristic != null) {
                                    try { gatt.readCharacteristic(characteristic) } catch (_: Exception) {}
                                }
                            }
                            fun publishBattery(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
                                if (status != BluetoothGatt.GATT_SUCCESS || characteristic.uuid != batteryLevelUuid || value.isEmpty()) return
                                val level = value[0].toInt() and 0xFF
                                if (level !in 0..100) return
                                val name = try { gatt.device.name ?: "Bluetooth device" } catch (_: Exception) { "Bluetooth device" }
                                activity.runOnUiThread {
                                    batteryDeviceName = name
                                    val savedBattery = preferences.edit().putString("battery_device_name", name)
                                    when {
                                        name.contains("left", true) -> { leftBattery = level; savedBattery.putInt("battery_left", level) }
                                        name.contains("right", true) -> { rightBattery = level; savedBattery.putInt("battery_right", level) }
                                        name.contains("case", true) -> { caseBattery = level; savedBattery.putInt("battery_case", level) }
                                        else -> { genericBattery = level; savedBattery.putInt("battery_generic", level) }
                                    }
                                    savedBattery.apply()
                                    batteryStatus = "Battery Service reported ${level}% from $name"
                                }
                            }
                            @Deprecated("Deprecated by Android")
                            override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
                                publishBattery(gatt, characteristic, characteristic.value ?: byteArrayOf(), status)
                            }
                            override fun onCharacteristicRead(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
                                publishBattery(gatt, characteristic, value, status)
                            }
                        }, BluetoothDevice.TRANSPORT_LE)
                        batteryGattConnections.add(gatt)
                    } catch (_: SecurityException) {
                        batteryStatus = "Allow Nearby devices permission to read battery data"
                    } catch (_: Exception) { }
                }
                override fun onScanFailed(errorCode: Int) {
                    batteryStatus = "Bluetooth scan failed (code $errorCode)"
                }
            }
            scanner.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), callback)
            Handler(Looper.getMainLooper()).postDelayed({
                try { scanner.stopScan(callback) } catch (_: Exception) {}
                if (genericBattery == null && leftBattery == null && rightBattery == null && caseBattery == null) {
                    batteryStatus = "No standard battery data received. Some earbuds only expose battery through their own companion app."
                }
            }, 12000)
        } catch (_: SecurityException) {
            batteryStatus = "Bluetooth permission is required"
        } catch (_: Exception) {
            batteryStatus = "Could not start Bluetooth battery discovery"
        }
    }

    fun loadDevices() {
        if (!hasPermission) {
            val permissions = if (Build.VERSION.SDK_INT >= 31) arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN) else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
            permissionLauncher.launch(permissions)
            return
        }
        try {
            val manager = activity.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
            val adapter = manager.adapter
            pairedDevices = adapter?.bondedDevices?.map { it.name ?: it.address }?.sorted() ?: emptyList()
            showDevices = true
            message = if (pairedDevices.isEmpty()) "No paired devices. Pair earbuds in Android Bluetooth settings." else "Choose a paired audio device"
        } catch (_: SecurityException) { message = "Bluetooth permission is required" }
    }

    MaterialTheme(colorScheme = darkColorScheme(primary = Cyan, secondary = Violet, background = Ink, surface = Panel, onBackground = Color.White, onSurface = Color.White)) {
        androidx.compose.foundation.lazy.LazyColumn(
            modifier = Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF111B2A), Ink, Ink))),
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(18.dp)
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Box(Modifier.size(42.dp).clip(RoundedCornerShape(14.dp)).background(Brush.linearGradient(listOf(Cyan, Violet))), contentAlignment = Alignment.Center) {
                        Icon(Icons.Rounded.GraphicEq, null, tint = Ink, modifier = Modifier.size(25.dp))
                    }
                    Spacer(Modifier.width(11.dp))
                    Column(Modifier.weight(1f)) {
                        Text("WaveSync", fontSize = 23.sp, fontWeight = FontWeight.Bold, color = Color.White)
                        Text("YOUR AUDIO, IN SYNC", fontSize = 9.sp, color = Muted, letterSpacing = 2.sp)
                    }
                    IconButton(onClick = { activity.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }) {
                        Icon(Icons.Rounded.SettingsBluetooth, "Bluetooth settings", tint = Muted)
                    }
                }
            }
            item {
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(28.dp)).background(Brush.linearGradient(listOf(Color(0xFF26364A), Color(0xFF172334), Color(0xFF24213C)))).padding(20.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(color = if (connected) Cyan.copy(alpha = .14f) else Color.White.copy(alpha = .08f), shape = CircleShape) {
                            Row(Modifier.padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                                Box(Modifier.size(7.dp).clip(CircleShape).background(if (connected) Cyan else Muted))
                                Spacer(Modifier.width(7.dp))
                                Text(if (connected) "SELECTED DEVICE" else "NOT CONNECTED", color = if (connected) Cyan else Muted, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                            }
                        }
                        Spacer(Modifier.weight(1f))
                        Icon(Icons.Rounded.Bluetooth, null, tint = Cyan)
                    }
                    Spacer(Modifier.height(16.dp))
                    EarbudArt(Modifier.fillMaxWidth().height(128.dp))
                    Spacer(Modifier.height(12.dp))
                    Text(deviceName, fontSize = 21.sp, fontWeight = FontWeight.SemiBold)
                    Text(if (connected) "Detected from Android audio output" else "Pair your earbuds in Android Bluetooth settings", color = Muted, fontSize = 12.sp)
                    Spacer(Modifier.height(18.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Button(onClick = { loadDevices() }, modifier = Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = Cyan, contentColor = Ink), shape = RoundedCornerShape(14.dp)) {
                            Icon(Icons.Rounded.BluetoothSearching, null, modifier = Modifier.size(18.dp)); Spacer(Modifier.width(7.dp)); Text("Find device")
                        }
                        OutlinedButton(onClick = { activity.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }, modifier = Modifier.weight(1f), shape = RoundedCornerShape(14.dp), colors = ButtonDefaults.outlinedButtonColors(contentColor = Color.White)) { Text("Pair device") }
                    }
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    MetricCard("LEFT BUD", leftBattery?.let { "$it%" } ?: "—", if (leftBattery != null) "Reported" else "Not reported", Modifier.weight(1f))
                    MetricCard("RIGHT BUD", rightBattery?.let { "$it%" } ?: "—", if (rightBattery != null) "Reported" else "Not reported", Modifier.weight(1f))
                    MetricCard("CASE", caseBattery?.let { "$it%" } ?: "—", if (caseBattery != null) "Reported" else "Not reported", Modifier.weight(1f))
                }
            }
            item {
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(16.dp)).background(Panel).padding(14.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Battery diagnostics", fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                            Text(genericBattery?.let { "$batteryDeviceName reported $it% (single battery value)" } ?: batteryStatus, color = Muted, fontSize = 10.sp, lineHeight = 14.sp)
                        }
                        TextButton(onClick = { startBatteryDiscovery() }) { Text("SCAN", color = Cyan, fontSize = 10.sp) }
                    }
                }
            }
            item {
                SectionHeader("Notification controls", "DEVICE")
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(Panel).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("WaveSync does not read or capture notifications from other apps. Playback controls appear in the notification shade and on the lock screen when enabled below.", color = Muted, fontSize = 11.sp, lineHeight = 16.sp)
                    Text("The app does not request notification-listener access.", color = Cyan, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
                }
            }
            item {
                SectionHeader("Lock-screen controls", "MEDIA")
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Panel).padding(16.dp)) {
                    Text("Playback shortcuts in the lock screen and notification shade.", color = Muted, fontSize = 11.sp)
                    Spacer(Modifier.height(8.dp))
                    Button(onClick = { showLockScreenMediaNotification(activity) }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = Cyan, contentColor = Ink)) {
                        Icon(Icons.Rounded.Lock, null); Spacer(Modifier.width(8.dp)); Text("Enable lock-screen controls")
                    }
                }
            }
            item {
                SectionHeader("Quick controls", "MEDIA")
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
                    QuickButton(Icons.Rounded.SkipPrevious, "Previous", Modifier.weight(1f)) { sendMediaKey(activity, android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS) }
                    QuickButton(Icons.Rounded.PlayArrow, "Play / pause", Modifier.weight(1.3f), true) { sendMediaKey(activity, android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) }
                    QuickButton(Icons.Rounded.SkipNext, "Next", Modifier.weight(1f)) { sendMediaKey(activity, android.view.KeyEvent.KEYCODE_MEDIA_NEXT) }
                }
            }
            item {
                SectionHeader("Sound studio", "EQUALIZER")
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(Panel).padding(18.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) { Text("Personal sound", fontWeight = FontWeight.SemiBold, fontSize = 16.sp); Text("Software audio profile", color = Muted, fontSize = 11.sp) }
                        Switch(checked = eqEnabled, onCheckedChange = { eqEnabled = it; preferences.edit().putBoolean("eq_enabled", it).apply() })
                    }
                    Text("Sound profiles", color = Muted, fontSize = 11.sp)
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.fillMaxWidth()) {
                        listOf("Flat", "Bass", "Vocal", "Immersive", "Custom").forEach { preset ->
                            FilterChip(
                                selected = selectedPreset == preset,
                                onClick = {
                                    selectedPreset = preset
                                    when (preset) {
                                        "Flat" -> { bass = .5f; clarity = .5f; ambience = 0f }
                                        "Bass" -> { bass = .85f; clarity = .48f; ambience = .22f }
                                        "Vocal" -> { bass = .38f; clarity = .82f; ambience = .12f }
                                        "Immersive" -> { bass = .62f; clarity = .62f; ambience = .85f }
                                    }
                                },
                                label = { Text(preset, fontSize = 10.sp) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = Cyan.copy(alpha = .18f),
                                    selectedLabelColor = Cyan
                                )
                            )
                        }
                    }
                    EqSlider("Bass", bass, { bass = it; selectedPreset = "Custom" }, eqEnabled)
                    EqSlider("Clarity", clarity, { clarity = it; selectedPreset = "Custom" }, eqEnabled)
                    EqSlider("Immersion", ambience, { ambience = it; selectedPreset = "Custom" }, eqEnabled)
                    Text(equalizerStatus, color = if (equalizerStatus.contains("active")) Cyan else Muted, fontSize = 10.sp, lineHeight = 15.sp)
                }
            }
            item {
                SectionHeader("Earbud features", "DEVICE TOOLS")
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    FeatureRow(Icons.Rounded.LocationSearching, "Find my earbuds", "Send a locator tone to the active audio route", "PLAY") {
                        try { ToneGenerator(AudioManager.STREAM_MUSIC, 85).startTone(ToneGenerator.TONE_CDMA_ALERT_CALL_GUARD, 1800); message = "Locator tone sent to the current audio route" } catch (_: Exception) { message = "Could not play locator tone" }
                    }
                    FeatureRow(Icons.Rounded.NoiseControlOff, "Noise cancellation", "Requires earbud manufacturer protocol", "CHECK") { message = "ANC control is not exposed through standard Bluetooth APIs" }
                    FeatureRow(Icons.Rounded.SportsEsports, "Gaming mode", "Low-latency mode requires firmware support", "CHECK") { message = "Gaming mode is not exposed through standard Bluetooth APIs" }
                    FeatureRow(Icons.Rounded.TouchApp, "Touch controls", "Standard media buttons; remapping is vendor-specific", "TEST") { sendMediaKey(activity, android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE); message = "Sent standard play/pause media command" }
                }
            }
            item {
                Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(22.dp)).background(Panel).padding(18.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Automatic reconnect", fontWeight = FontWeight.SemiBold)
                            Text("Android reconnects previously paired devices when available", color = Muted, fontSize = 10.sp, lineHeight = 14.sp)
                        }
                        Switch(checked = autoReconnect, onCheckedChange = { autoReconnect = it; preferences.edit().putBoolean("auto_reconnect", it).apply() })
                    }
                }
            }
            item {
                Text(message, color = Cyan, fontSize = 12.sp)
                Text("Proprietary ANC, touch remapping, earbud battery and gaming controls need compatible vendor protocols. WaveSync will not fake unsupported hardware features.", color = Muted, fontSize = 10.sp, lineHeight = 15.sp)
            }
        }
        if (showDevices) AlertDialog(
            onDismissRequest = { showDevices = false },
            title = { Text("Paired Bluetooth devices") },
            text = { Column { if (pairedDevices.isEmpty()) Text("No paired devices found. Use Pair device to connect one.") else pairedDevices.forEach { name -> TextButton(onClick = { showDevices = false; activity.startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)); message = "Choose $name in Android Bluetooth settings. WaveSync detects the active audio route automatically." }) { Text(name) } } } },
            confirmButton = { TextButton(onClick = { showDevices = false }) { Text("Close") } },
            containerColor = Panel
        )
    }
}

private class AudioFxController {
    private var equalizer: Equalizer? = null
    private var virtualizer: Virtualizer? = null

    fun apply(enabled: Boolean, bass: Float, clarity: Float, immersion: Float): Boolean {
        return try {
            if (equalizer == null) equalizer = Equalizer(0, 0)
            if (virtualizer == null) virtualizer = try { Virtualizer(0, 0) } catch (_: Exception) { null }
            val eq = equalizer ?: return false
            eq.enabled = enabled
            virtualizer?.enabled = enabled
            if (enabled) {
                val bands = eq.numberOfBands.toInt()
                if (bands > 0) {
                    val range = eq.bandLevelRange
                    val min = range[0].toInt()
                    val max = range[1].toInt()
                    val lowLevel = (min + (max - min) * bass).toInt().coerceIn(min, max)
                    val presenceLevel = (min + (max - min) * clarity).toInt().coerceIn(min, max)
                    eq.setBandLevel(0.toShort(), lowLevel.toShort())
                    eq.setBandLevel(minOf(3, bands - 1).toShort(), presenceLevel.toShort())
                }
                virtualizer?.let { effect ->
                    effect.setStrength((immersion * 1000).toInt().coerceIn(0, 1000).toShort())
                }
            }
            true
        } catch (_: Exception) { false }
    }

    fun release() {
        try { equalizer?.release() } catch (_: Exception) {}
        try { virtualizer?.release() } catch (_: Exception) {}
        equalizer = null
        virtualizer = null
    }
}

private fun hasBluetoothPermission(context: Context): Boolean =
    Build.VERSION.SDK_INT < 31 || ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

private fun sendMediaKey(context: Context, keyCode: Int) {
    val manager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    manager.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, keyCode))
    manager.dispatchMediaKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, keyCode))
}

@Composable
private fun EarbudArt(modifier: Modifier = Modifier) {
    Canvas(modifier) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        drawCircle(Cyan.copy(alpha = .14f), size.minDimension * .35f, Offset(cx, cy))
        val body = Brush.linearGradient(listOf(Color(0xFFF4F8FA), Color(0xFF8FA5B8), Color(0xFFE4EDF2)))
        fun bud(x: Float, y: Float) {
            drawRoundRect(body, Offset(x - 22f, y - 35f), androidx.compose.ui.geometry.Size(44f, 68f), androidx.compose.ui.geometry.CornerRadius(22f))
            drawRoundRect(body, Offset(x - 10f, y + 15f), androidx.compose.ui.geometry.Size(20f, 53f), androidx.compose.ui.geometry.CornerRadius(10f))
            drawCircle(Color(0xFF536579), 5f, Offset(x, y - 12f))
            drawCircle(Cyan.copy(alpha = .8f), 2.5f, Offset(x, y - 12f))
        }
        bud(cx - 54f, cy - 7f); bud(cx + 54f, cy - 7f)
        drawLine(Cyan.copy(alpha = .5f), Offset(cx - 110f, cy + 61f), Offset(cx + 110f, cy + 61f), 1.5f, cap = StrokeCap.Round)
    }
}

@Composable
private fun MetricCard(title: String, value: String, subtitle: String, modifier: Modifier = Modifier) {
    Column(modifier.clip(RoundedCornerShape(18.dp)).background(Panel).padding(12.dp)) {
        Text(title, color = Muted, fontSize = 8.sp, fontWeight = FontWeight.Bold, letterSpacing = .7.sp)
        Spacer(Modifier.height(8.dp))
        Text(value, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = if (value == "—") Muted else Cyan)
        Text(subtitle, color = Muted, fontSize = 9.sp)
    }
}
@Composable
private fun SectionHeader(title: String, tag: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(title, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Spacer(Modifier.weight(1f))
        Text(tag, color = Violet, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
    }
}
@Composable
private fun QuickButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, modifier: Modifier = Modifier, primary: Boolean = false, onClick: () -> Unit) {
    Column(modifier.clip(RoundedCornerShape(16.dp)).background(if (primary) Cyan else Panel).padding(vertical = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        IconButton(onClick = onClick) { Icon(icon, null, tint = if (primary) Ink else Color.White, modifier = Modifier.size(25.dp)) }
        Text(label, color = if (primary) Ink else Muted, fontSize = 9.sp, fontWeight = FontWeight.Medium)
    }
}
@Composable
private fun EqSlider(label: String, value: Float, onChange: (Float) -> Unit, enabled: Boolean) {
    Column {
        Row { Text(label, fontSize = 12.sp); Spacer(Modifier.weight(1f)); Text("${(value * 100).toInt()}", color = Cyan, fontSize = 11.sp) }
        Slider(value = value, onValueChange = onChange, enabled = enabled, colors = SliderDefaults.colors(thumbColor = Cyan, activeTrackColor = Cyan, inactiveTrackColor = Panel2))
    }
}
@Composable
private fun FeatureRow(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, subtitle: String, action: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(Panel).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(42.dp).clip(RoundedCornerShape(13.dp)).background(Panel2), contentAlignment = Alignment.Center) { Icon(icon, null, tint = Cyan) }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) { Text(title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold); Text(subtitle, color = Muted, fontSize = 10.sp, lineHeight = 14.sp) }
        TextButton(onClick = onClick) { Text(action, fontSize = 9.sp, color = Violet, fontWeight = FontWeight.Bold) }
    }
}

private fun showLockScreenMediaNotification(context: Context) {
    if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
        androidx.core.app.ActivityCompat.requestPermissions(context as ComponentActivity, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 4101)
        return
    }
    val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    val channelId = "wavesync_media_controls"
    if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(NotificationChannel(channelId, "WaveSync playback controls", NotificationManager.IMPORTANCE_LOW).apply { lockscreenVisibility = Notification.VISIBILITY_PUBLIC })
    fun action(label: String, icon: Int, key: Int, request: Int): Notification.Action {
        val intent = Intent(context, MediaActionReceiver::class.java).setAction("com.wavesync.MEDIA_" + key)
        val pending = PendingIntent.getBroadcast(context, request, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Action.Builder(icon, label, pending).build()
    }
    val open = PendingIntent.getActivity(context, 0, Intent(context, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    val notification = Notification.Builder(context, channelId)
        .setSmallIcon(android.R.drawable.ic_media_play)
        .setContentTitle("WaveSync audio controls")
        .setContentText("Control the active music player")
        .setContentIntent(open)
        .setVisibility(Notification.VISIBILITY_PUBLIC)
        .setCategory(Notification.CATEGORY_TRANSPORT)
        .setOngoing(true)
        .addAction(action("Previous", android.R.drawable.ic_media_previous, android.view.KeyEvent.KEYCODE_MEDIA_PREVIOUS, 1))
        .addAction(action("Play / pause", android.R.drawable.ic_media_play, android.view.KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE, 2))
        .addAction(action("Next", android.R.drawable.ic_media_next, android.view.KeyEvent.KEYCODE_MEDIA_NEXT, 3))
        .build()
    manager.notify(4101, notification)
}
