package com.example.vray

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings as SettingsIcon
import androidx.compose.material.icons.filled.SupportAgent
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.vray.core.ConnectionState
import com.example.vray.core.ProxyVpnService
import com.example.vray.core.TrafficStats
import com.example.vray.data.*
import com.example.vray.ui.*
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit

class MainActivity : ComponentActivity() {

    private lateinit var repo: Repository
    private var scannedLink by mutableStateOf<String?>(null)

    private val qrScanLauncher = registerForActivityResult(ScanContract()) { result ->
        result.contents?.let { scannedLink = it }
    }

    private val vpnPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            pendingConnectProfileId?.let { startVpn(it) }
        }
        pendingConnectProfileId = null
    }
    private var pendingConnectProfileId: String? = null

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { /* no-op either way — foreground service still works, notification just may not show */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        repo = Repository(this)

        // Android 13+ requires this at runtime or startForeground() can throw/silently
        // fail to show the ongoing "connected" notification.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        setContent {
            VRayTheme {
                CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Rtl) {
                    var crashText by remember { mutableStateOf(repo.getLastCrash(this)) }
                    var onboardingDone by remember { mutableStateOf(repo.isOnboardingDone()) }

                    when {
                        crashText != null -> CrashScreen(
                            trace = crashText!!,
                            onDismiss = {
                                repo.clearLastCrash(this)
                                crashText = null
                            },
                            onShare = {
                                val send = Intent(Intent.ACTION_SEND).apply {
                                    type = "text/plain"
                                    putExtra(Intent.EXTRA_TEXT, crashText)
                                }
                                startActivity(Intent.createChooser(send, "ارسال گزارش خطا"))
                            }
                        )
                        !onboardingDone -> OnboardingScreen(onDone = {
                            repo.setOnboardingDone()
                            onboardingDone = true
                        })
                        else -> AppRoot(
                            repo = repo,
                            scannedLink = scannedLink,
                            onScannedConsumed = { scannedLink = null },
                            onScanQr = { launchQrScan() },
                            onConnect = { profileId -> requestConnect(profileId) },
                            onDisconnect = { disconnect() }
                        )
                    }
                }
            }
        }
    }

    private fun launchQrScan() {
        qrScanLauncher.launch(
            ScanOptions()
                .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                .setPrompt("QR کد سرور را اسکن کن")
                .setBeepEnabled(false)
                .setOrientationLocked(true)
        )
    }

    private fun requestConnect(profileId: String) {
        val intent = VpnService.prepare(this)
        if (intent != null) {
            pendingConnectProfileId = profileId
            vpnPermissionLauncher.launch(intent)
        } else {
            startVpn(profileId)
        }
    }

    private fun startVpn(profileId: String) {
        val svc = Intent(this, ProxyVpnService::class.java).apply {
            action = ProxyVpnService.ACTION_CONNECT
            putExtra(ProxyVpnService.EXTRA_PROFILE_ID, profileId)
        }
        startForegroundService(svc)
    }

    private fun disconnect() {
        val svc = Intent(this, ProxyVpnService::class.java).apply {
            action = ProxyVpnService.ACTION_DISCONNECT
        }
        startService(svc)
    }
}

private sealed class Screen {
    object Main : Screen()
    object AppPicker : Screen()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppRoot(
    repo: Repository,
    scannedLink: String?,
    onScannedConsumed: () -> Unit,
    onScanQr: () -> Unit,
    onConnect: (String) -> Unit,
    onDisconnect: () -> Unit
) {
    var profiles by remember { mutableStateOf(repo.loadProfiles()) }
    var subscriptions by remember { mutableStateOf(repo.loadSubscriptions()) }
    var selectedId by remember { mutableStateOf(repo.loadSelectedProfileId()) }
    var settings by remember { mutableStateOf(repo.loadSettings()) }

    val connState by ProxyVpnService.state.collectAsState()
    val trafficStats by ProxyVpnService.stats.collectAsState()
    val lastError by ProxyVpnService.lastError.collectAsState()

    var showAddDialog by remember { mutableStateOf(false) }
    var showSettingsSheet by remember { mutableStateOf(false) }
    var showSupportDialog by remember { mutableStateOf(false) }
    var editingProfile by remember { mutableStateOf<ProxyProfile?>(null) }
    var screen by rememberSaveable { mutableStateOf<Screen>(Screen.Main) }
    var quickConnecting by remember { mutableStateOf(false) }

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    LaunchedEffect(lastError) {
        lastError?.let { snackbarHostState.showSnackbar(it) }
    }

    LaunchedEffect(scannedLink) {
        scannedLink?.let { link ->
            val parsed = LinkParser.parse(link)
            if (parsed != null) {
                profiles = (profiles + parsed).toMutableList()
                repo.saveProfiles(profiles)
                if (selectedId == null) {
                    selectedId = parsed.id
                    repo.saveSelectedProfileId(parsed.id)
                }
                snackbarHostState.showSnackbar("سرور «${parsed.name}» اضافه شد")
            } else {
                snackbarHostState.showSnackbar("کد QR قابل خواندن نبود")
            }
            onScannedConsumed()
        }
    }

    if (screen is Screen.AppPicker) {
        AppPickerScreen(
            initiallySelected = settings.selectedApps,
            onBack = { screen = Screen.Main },
            onConfirm = {
                settings = settings.copy(selectedApps = it)
                repo.saveSettings(settings)
            }
        )
        return
    }

    // Dedup key so a subscription refresh / re-add doesn't create duplicate servers.
    fun dedupeKey(p: ProxyProfile) = "${p.address}:${p.port}:${p.userId}"

    fun addSubscription(label: String, url: String) {
        scope.launch {
            val result = SubscriptionManager.fetch(url)
            result.onSuccess { (fetched, meta) ->
                val subId = UUID.randomUUID().toString()
                val existingKeys = profiles.map(::dedupeKey).toSet()
                val tagged = fetched
                    .filter { dedupeKey(it) !in existingKeys }
                    .map { it.copy(subscriptionId = subId) }
                profiles = (profiles + tagged).toMutableList()
                repo.saveProfiles(profiles)

                val sub = Subscription(
                    id = subId, label = label.ifBlank { "اشتراک ${subscriptions.size + 1}" }, url = url,
                    lastUpdatedEpochMs = System.currentTimeMillis(),
                    expireAtEpochSec = meta.expireAtEpochSec,
                    dataLimitBytes = meta.dataLimitBytes,
                    dataUsedBytes = meta.dataUsedBytes
                )
                subscriptions = (subscriptions + sub).toMutableList()
                repo.saveSubscriptions(subscriptions)
                snackbarHostState.showSnackbar("${tagged.size} سرور از «${sub.label}» اضافه شد")
            }.onFailure {
                snackbarHostState.showSnackbar("افزودن اشتراک ناموفق بود — لینک را بررسی کن")
            }
        }
    }

    fun refreshSubscription(sub: Subscription) {
        scope.launch {
            val result = SubscriptionManager.fetch(sub.url)
            result.onSuccess { (fetched, meta) ->
                // Full sync for this group: out with the old, in with the new.
                val wasSelectedInGroup = profiles.firstOrNull { it.id == selectedId }?.subscriptionId == sub.id
                val remaining = profiles.filterNot { it.subscriptionId == sub.id }
                val tagged = fetched.map { it.copy(subscriptionId = sub.id) }
                profiles = (remaining + tagged).toMutableList()
                repo.saveProfiles(profiles)

                if (wasSelectedInGroup) {
                    selectedId = tagged.firstOrNull()?.id
                    selectedId?.let { repo.saveSelectedProfileId(it) }
                }

                subscriptions = subscriptions.map {
                    if (it.id == sub.id) it.copy(
                        lastUpdatedEpochMs = System.currentTimeMillis(),
                        expireAtEpochSec = meta.expireAtEpochSec,
                        dataLimitBytes = meta.dataLimitBytes,
                        dataUsedBytes = meta.dataUsedBytes
                    ) else it
                }.toMutableList()
                repo.saveSubscriptions(subscriptions)
                snackbarHostState.showSnackbar("«${sub.label}» بروزرسانی شد \u00b7 ${tagged.size} سرور")
            }.onFailure {
                snackbarHostState.showSnackbar("بروزرسانی «${sub.label}» ناموفق بود")
            }
        }
    }

    fun deleteSubscription(sub: Subscription) {
        val wasSelectedInGroup = profiles.firstOrNull { it.id == selectedId }?.subscriptionId == sub.id
        profiles = profiles.filterNot { it.subscriptionId == sub.id }.toMutableList()
        repo.saveProfiles(profiles)
        subscriptions = subscriptions.filterNot { it.id == sub.id }.toMutableList()
        repo.saveSubscriptions(subscriptions)
        if (wasSelectedInGroup) selectedId = null
    }

    val groupedProfiles: List<Pair<Subscription?, List<ProxyProfile>>> = remember(profiles, subscriptions) {
        val bySub = profiles.groupBy { it.subscriptionId }
        val subGroups = subscriptions.mapNotNull { sub -> bySub[sub.id]?.let { sub to it } }
        val manual = bySub[null]?.takeIf { it.isNotEmpty() }?.let { null to it }
        if (manual != null) subGroups + listOf(manual) else subGroups
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("پرواز") },
                actions = {
                    IconButton(onClick = { showSupportDialog = true }) {
                        Icon(Icons.Filled.SupportAgent, contentDescription = "پشتیبانی")
                    }
                    IconButton(onClick = { showSettingsSheet = true }) {
                        Icon(Icons.Filled.SettingsIcon, contentDescription = "تنظیمات")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAddDialog = true }) {
                Icon(Icons.Filled.Add, contentDescription = "افزودن سرور")
            }
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState())) {

            ConnectCard(
                state = connState,
                stats = trafficStats,
                hasSelection = selectedId != null,
                quickConnecting = quickConnecting,
                killSwitchActive = settings.killSwitch && connState == ConnectionState.ERROR,
                onConnect = { selectedId?.let { onConnect(it) } },
                onDisconnect = onDisconnect,
                onQuickConnect = {
                    if (profiles.isEmpty()) {
                        scope.launch { snackbarHostState.showSnackbar("اول یک سرور اضافه کن") }
                    } else {
                        quickConnecting = true
                        scope.launch {
                            val best = coroutineScope {
                                profiles.map { p -> async { p.id to PingTester.measure(p) } }.awaitAll()
                            }.filter { it.second in 0..60000 }.minByOrNull { it.second }
                            quickConnecting = false
                            if (best != null) {
                                selectedId = best.first
                                repo.saveSelectedProfileId(best.first)
                                onConnect(best.first)
                            } else {
                                snackbarHostState.showSnackbar("هیچ سروری جواب نداد")
                            }
                        }
                    }
                }
            )

            subscriptions.filter { it.expireAtEpochSec != null || (it.dataLimitBytes ?: 0) > 0 }.forEach { sub ->
                Spacer(Modifier.height(12.dp))
                SubscriptionStatusCard(sub)
            }

            Spacer(Modifier.height(16.dp))
            Text("سرورها", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(4.dp))

            if (groupedProfiles.isEmpty()) {
                Text(
                    "هنوز سروری اضافه نکردی",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(vertical = 12.dp)
                )
            }

            groupedProfiles.forEach { (sub, list) ->
                Text(
                    sub?.label ?: "افزوده شده دستی",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 10.dp, bottom = 4.dp)
                )
                list.forEach { profile ->
                    ServerRow(
                        profile = profile,
                        selected = profile.id == selectedId,
                        onSelect = {
                            selectedId = profile.id
                            repo.saveSelectedProfileId(profile.id)
                        },
                        onEdit = { editingProfile = profile },
                        onDelete = {
                            profiles = profiles.filterNot { it.id == profile.id }.toMutableList()
                            repo.saveProfiles(profiles)
                            if (selectedId == profile.id) selectedId = null
                        }
                    )
                }
            }
        }
    }

    if (showAddDialog) {
        AddServerDialog(
            onDismiss = { showAddDialog = false },
            onScanQr = { showAddDialog = false; onScanQr() },
            onAdd = { link ->
                val parsed = LinkParser.parse(link)
                if (parsed != null) {
                    profiles = (profiles + parsed).toMutableList()
                    repo.saveProfiles(profiles)
                    if (selectedId == null) {
                        selectedId = parsed.id
                        repo.saveSelectedProfileId(parsed.id)
                    }
                    showAddDialog = false
                    true
                } else {
                    scope.launch { snackbarHostState.showSnackbar("این لینک خوانده نشد — فرمتش رو بررسی کن") }
                    false
                }
            }
        )
    }

    editingProfile?.let { profile ->
        EditServerDialog(
            profile = profile,
            onDismiss = { editingProfile = null },
            onSave = { updated ->
                profiles = profiles.map { if (it.id == updated.id) updated else it }.toMutableList()
                repo.saveProfiles(profiles)
                editingProfile = null
            }
        )
    }

    if (showSettingsSheet) {
        SettingsSheet(
            settings = settings,
            subscriptions = subscriptions,
            onDismiss = { showSettingsSheet = false },
            onSave = {
                settings = it
                repo.saveSettings(it)
                showSettingsSheet = false
            },
            onChooseApps = {
                showSettingsSheet = false
                screen = Screen.AppPicker
            },
            onOpenSystemVpnSettings = {
                try { context.startActivity(Intent(Settings.ACTION_VPN_SETTINGS)) } catch (_: Exception) {}
            },
            onAddSubscription = { label, url -> addSubscription(label, url) },
            onRefreshSubscription = { sub -> refreshSubscription(sub) },
            onDeleteSubscription = { sub -> deleteSubscription(sub) }
        )
    }

    if (showSupportDialog) {
        AlertDialog(
            onDismissRequest = { showSupportDialog = false },
            title = { Text("پشتیبانی") },
            text = {
                Text("برای دریافت سرور جدید یا کمک، به آیدی تلگرام @YourSupportID پیام بده.\n(این آیدی رو توی کد جایگزین کن.)")
            },
            confirmButton = { TextButton(onClick = { showSupportDialog = false }) { Text("باشه") } }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubscriptionStatusCard(sub: Subscription) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(sub.label, style = MaterialTheme.typography.labelLarge)
            sub.expireAtEpochSec?.let { expireSec ->
                val daysLeft = TimeUnit.SECONDS.toDays(expireSec - System.currentTimeMillis() / 1000)
                val text = if (daysLeft >= 0) "$daysLeft روز تا پایان اشتراک" else "اشتراک منقضی شده"
                Text(text, style = MaterialTheme.typography.bodyMedium)
            }
            if (sub.dataLimitBytes != null && sub.dataLimitBytes > 0) {
                val used = sub.dataUsedBytes ?: 0L
                val fraction = (used.toFloat() / sub.dataLimitBytes.toFloat()).coerceIn(0f, 1f)
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(4.dp))
                Text(
                    "${formatBytes(used)} از ${formatBytes(sub.dataLimitBytes)} مصرف‌شده",
                    style = MaterialTheme.typography.bodySmall
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ConnectCard(
    state: ConnectionState,
    stats: TrafficStats,
    hasSelection: Boolean,
    quickConnecting: Boolean,
    killSwitchActive: Boolean,
    onConnect: () -> Unit,
    onDisconnect: () -> Unit,
    onQuickConnect: () -> Unit
) {
    val (label, color) = when {
        killSwitchActive -> "قطع \u00b7 Kill Switch فعاله" to StatusError
        state == ConnectionState.CONNECTED -> "متصل" to StatusConnected
        state == ConnectionState.CONNECTING -> "در حال اتصال…" to StatusConnecting
        state == ConnectionState.ERROR -> "اتصال ناموفق بود" to StatusError
        else -> "قطع" to StatusDisconnected
    }

    ElevatedCard(Modifier.fillMaxWidth()) {
        Column(
            Modifier.padding(24.dp).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .padding(end = 8.dp)
                        .size(10.dp)
                        .background(color, shape = androidx.compose.foundation.shape.CircleShape)
                )
                Text(label, style = MaterialTheme.typography.titleLarge)
            }

            if (state == ConnectionState.CONNECTED) {
                Spacer(Modifier.height(8.dp))
                Text(
                    "↑ ${formatBytes(stats.uplinkBytes)}   ↓ ${formatBytes(stats.downlinkBytes)}",
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            Spacer(Modifier.height(12.dp))

            if (state == ConnectionState.CONNECTING) {
                CircularProgressIndicator(modifier = Modifier.size(32.dp))
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { if (state == ConnectionState.CONNECTED) onDisconnect() else onConnect() },
                        enabled = state == ConnectionState.CONNECTED || hasSelection
                    ) {
                        Text(if (state == ConnectionState.CONNECTED) "قطع اتصال" else "اتصال")
                    }
                    if (killSwitchActive) {
                        OutlinedButton(onClick = onDisconnect) { Text("رها کردن اتصال") }
                    }
                }

                if (state != ConnectionState.CONNECTED) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = onQuickConnect, enabled = !quickConnecting) {
                        if (quickConnecting) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(Modifier.width(8.dp))
                            Text("در حال یافتن بهترین سرور…")
                        } else {
                            Icon(Icons.Filled.Bolt, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(4.dp))
                            Text("اتصال سریع (بهترین سرور)")
                        }
                    }
                }
            }

            if (!hasSelection && state == ConnectionState.DISCONNECTED) {
                Spacer(Modifier.height(8.dp))
                Text("اول یک سرور اضافه و انتخاب کن", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = arrayOf("KB", "MB", "GB")
    var value = bytes / 1024.0
    var unitIndex = 0
    while (value >= 1024 && unitIndex < units.size - 1) {
        value /= 1024; unitIndex++
    }
    return String.format(Locale.US, "%.1f %s", value, units[unitIndex])
}

@Composable
fun ServerRow(
    profile: ProxyProfile,
    selected: Boolean,
    onSelect: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    var pingResult by remember(profile.id) { mutableStateOf<String?>(null) }
    var pinging by remember(profile.id) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    ListItem(
        headlineContent = { Text(profile.name) },
        supportingContent = {
            Text(
                "${profile.protocol.uppercase()} · ${profile.address}:${profile.port}" +
                    (pingResult?.let { "  ·  $it" } ?: "")
            )
        },
        leadingContent = { RadioButton(selected = selected, onClick = onSelect) },
        trailingContent = {
            Row {
                IconButton(onClick = {
                    if (!pinging) {
                        pinging = true
                        pingResult = null
                        scope.launch {
                            val ms = PingTester.measure(profile)
                            pingResult = if (ms in 0..60000) "${ms} ms" else "بدون پاسخ"
                            pinging = false
                        }
                    }
                }) {
                    if (pinging) CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    else Icon(Icons.Filled.Speed, contentDescription = "تست پینگ")
                }
                IconButton(onClick = onEdit) {
                    Icon(Icons.Filled.Edit, contentDescription = "ویرایش")
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Filled.Delete, contentDescription = "حذف")
                }
            }
        },
        modifier = Modifier.clickable(onClick = onSelect)
    )
    Divider()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddServerDialog(onDismiss: () -> Unit, onScanQr: () -> Unit, onAdd: (String) -> Boolean) {
    var text by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("افزودن سرور") },
        text = {
            Column {
                Text(
                    "لینک vmess:// vless:// trojan:// یا ss:// را وارد کن، یا کد QR رو اسکن کن.",
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = onScanQr, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.QrCodeScanner, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text("اسکن QR")
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it; error = null },
                    modifier = Modifier.fillMaxWidth(),
                    isError = error != null,
                    minLines = 3
                )
                error?.let {
                    Spacer(Modifier.height(4.dp))
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (!onAdd(text)) error = "این لینک خوانده نشد — فرمتش رو بررسی کن"
            }) { Text("افزودن") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("انصراف") } }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditServerDialog(profile: ProxyProfile, onDismiss: () -> Unit, onSave: (ProxyProfile) -> Unit) {
    var name by remember { mutableStateOf(profile.name) }
    var address by remember { mutableStateOf(profile.address) }
    var port by remember { mutableStateOf(profile.port.toString()) }
    var userId by remember { mutableStateOf(profile.userId) }
    var network by remember { mutableStateOf(profile.network) }
    var path by remember { mutableStateOf(profile.path) }
    var host by remember { mutableStateOf(profile.host) }
    var tls by remember { mutableStateOf(profile.tls) }
    var sni by remember { mutableStateOf(profile.sni) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("ویرایش سرور") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("نام") }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(value = address, onValueChange = { address = it }, label = { Text("آدرس") }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(value = port, onValueChange = { port = it.filter(Char::isDigit) }, label = { Text("پورت") }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(value = userId, onValueChange = { userId = it }, label = { Text("UUID یا پسورد") }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(value = network, onValueChange = { network = it }, label = { Text("شبکه (tcp/ws/grpc/http)") }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(value = path, onValueChange = { path = it }, label = { Text("مسیر / نام سرویس") }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(value = host, onValueChange = { host = it }, label = { Text("هدر Host") }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(value = tls, onValueChange = { tls = it }, label = { Text("TLS (none/tls/reality)") }, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(value = sni, onValueChange = { sni = it }, label = { Text("SNI") }, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(
                    profile.copy(
                        name = name, address = address, port = port.toIntOrNull() ?: profile.port,
                        userId = userId, network = network, path = path, host = host, tls = tls, sni = sni
                    )
                )
            }) { Text("ذخیره") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("انصراف") } }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsSheet(
    settings: AppSettings,
    subscriptions: List<Subscription>,
    onDismiss: () -> Unit,
    onSave: (AppSettings) -> Unit,
    onChooseApps: () -> Unit,
    onOpenSystemVpnSettings: () -> Unit,
    onAddSubscription: (label: String, url: String) -> Unit,
    onRefreshSubscription: (Subscription) -> Unit,
    onDeleteSubscription: (Subscription) -> Unit
) {
    var s by remember { mutableStateOf(settings) }
    var showAddSubDialog by remember { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(20.dp).verticalScroll(rememberScrollState())) {
            Text("تنظیمات", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(16.dp))

            Text("اشتراک‌ها", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            if (subscriptions.isEmpty()) {
                Text("هنوز اشتراکی اضافه نشده", style = MaterialTheme.typography.bodySmall)
            }
            subscriptions.forEach { sub ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(sub.label, style = MaterialTheme.typography.bodyLarge)
                        Text(sub.url, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                    }
                    IconButton(onClick = { onRefreshSubscription(sub) }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "بروزرسانی")
                    }
                    IconButton(onClick = { onDeleteSubscription(sub) }) {
                        Icon(Icons.Filled.Delete, contentDescription = "حذف اشتراک")
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            OutlinedButton(onClick = { showAddSubDialog = true }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Filled.Add, contentDescription = null)
                Spacer(Modifier.width(6.dp))
                Text("افزودن اشتراک جدید")
            }

            Spacer(Modifier.height(20.dp))
            Divider()
            Spacer(Modifier.height(16.dp))

            OutlinedTextField(
                value = s.socksPort.toString(),
                onValueChange = { s = s.copy(socksPort = it.toIntOrNull() ?: s.socksPort) },
                label = { Text("پورت SOCKS") }, modifier = Modifier.fillMaxWidth()
            )
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = s.httpPort.toString(),
                onValueChange = { s = s.copy(httpPort = it.toIntOrNull() ?: s.httpPort) },
                label = { Text("پورت HTTP") }, modifier = Modifier.fillMaxWidth()
            )

            SwitchRow("فعال‌سازی UDP", s.enableUdp) { s = s.copy(enableUdp = it) }
            SwitchRow("فعال‌سازی Mux (مالتی‌پلکس)", s.muxEnabled) { s = s.copy(muxEnabled = it) }
            SwitchRow("ترافیک شبکه محلی مستقیم بره", s.bypassLan) { s = s.copy(bypassLan = it) }

            Spacer(Modifier.height(12.dp))
            Text("اتصال هوشمند", style = MaterialTheme.typography.titleMedium)
            SwitchRow("اتصال مجدد خودکار", s.autoReconnect) { s = s.copy(autoReconnect = it) }
            SwitchRow("سوییچ خودکار به سرور دیگر", s.autoFailover) { s = s.copy(autoFailover = it) }
            SwitchRow("Kill Switch (قطع اینترنت هنگام افت اتصال)", s.killSwitch) { s = s.copy(killSwitch = it) }
            TextButton(onClick = onOpenSystemVpnSettings) {
                Text("قفل کامل‌تر: تنظیمات VPN سیستم را باز کن")
            }

            Spacer(Modifier.height(12.dp))
            Text("مسیریابی", style = MaterialTheme.typography.titleMedium)
            val routingLabels = mapOf(
                RoutingMode.GLOBAL to "همه ترافیک از تانل",
                RoutingMode.BYPASS_IRAN to "ایران مستقیم (Bypass)",
                RoutingMode.BYPASS_CHINA to "چین مستقیم (Bypass)"
            )
            RoutingMode.entries.forEach { mode ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = s.routingMode == mode, onClick = { s = s.copy(routingMode = mode) })
                    Text(routingLabels[mode] ?: mode.name)
                }
            }

            Spacer(Modifier.height(12.dp))
            Text("تانل کردن اپ‌ها", style = MaterialTheme.typography.titleMedium)
            val perAppLabels = mapOf(
                PerAppMode.ALL to "همه اپ‌ها",
                PerAppMode.ONLY_SELECTED to "فقط اپ‌های انتخابی",
                PerAppMode.EXCEPT_SELECTED to "همه به‌جز انتخابی‌ها"
            )
            PerAppMode.entries.forEach { mode ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = s.perAppMode == mode, onClick = { s = s.copy(perAppMode = mode) })
                    Text(perAppLabels[mode] ?: mode.name)
                }
            }
            if (s.perAppMode != PerAppMode.ALL) {
                OutlinedButton(onClick = onChooseApps, modifier = Modifier.padding(top = 4.dp)) {
                    Text("انتخاب اپ‌ها (${s.selectedApps.size} انتخاب‌شده)")
                }
            }

            Spacer(Modifier.height(16.dp))
            OutlinedTextField(
                value = s.customDns.joinToString(","),
                onValueChange = { s = s.copy(customDns = it.split(",").map(String::trim).filter(String::isNotEmpty)) },
                label = { Text("سرورهای DNS (با کاما جدا کن)") }, modifier = Modifier.fillMaxWidth()
            )

            Spacer(Modifier.height(20.dp))
            Button(onClick = { onSave(s) }, modifier = Modifier.fillMaxWidth()) { Text("ذخیره") }
            Spacer(Modifier.height(12.dp))
        }
    }

    if (showAddSubDialog) {
        AddSubscriptionDialog(
            onDismiss = { showAddSubDialog = false },
            onAdd = { label, url ->
                onAddSubscription(label, url)
                showAddSubDialog = false
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddSubscriptionDialog(onDismiss: () -> Unit, onAdd: (label: String, url: String) -> Unit) {
    var label by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("افزودن اشتراک") },
        text = {
            Column {
                Text(
                    "هر اشتراک یه گروه جدا از سرورها می‌سازه — بعداً می‌تونی فقط همون گروه رو بروزرسانی یا حذف کنی.",
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = label, onValueChange = { label = it },
                    label = { Text("نام گروه (اختیاری، مثلاً «مشتری VIP»)") },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = url, onValueChange = { url = it },
                    label = { Text("آدرس Subscription") },
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { if (url.isNotBlank()) onAdd(label, url) }) { Text("افزودن") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("انصراف") } }
    )
}

@Composable
fun SwitchRow(label: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label)
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
