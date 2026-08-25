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
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Share
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
import androidx.lifecycle.lifecycleScope
import com.example.vray.core.ConnectionState
import com.example.vray.core.OpenVpnManager
import com.example.vray.core.ProxyVpnService
import com.example.vray.core.TrafficStats
import com.example.vray.core.WireGuardManager
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
    private var wgFileText by mutableStateOf<String?>(null)
    private var ovpnFileText by mutableStateOf<String?>(null)
    private var backupFileText by mutableStateOf<String?>(null)

    private val qrScanLauncher = registerForActivityResult(ScanContract()) { result ->
        result.contents?.let { scannedLink = it }
    }

    private val wgFilePickerLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { readFileText(it)?.let { text -> wgFileText = text } }
    }

    private val ovpnFilePickerLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { readFileText(it)?.let { text -> ovpnFileText = text } }
    }

    private val backupFilePickerLauncher = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { readFileText(it)?.let { text -> backupFileText = text } }
    }

    private fun shareText(text: String, title: String) {
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
        }
        startActivity(Intent.createChooser(send, title))
    }

    private fun readFileText(uri: android.net.Uri): String? = try {
        contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
    } catch (e: Exception) {
        null
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
            var themeMode by remember { mutableStateOf(repo.loadSettings().themeMode) }
            VRayTheme(themeMode = themeMode) {
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
                            wgFileText = wgFileText,
                            onWgFileConsumed = { wgFileText = null },
                            onPickWgFile = { wgFilePickerLauncher.launch("*/*") },
                            ovpnFileText = ovpnFileText,
                            onOvpnFileConsumed = { ovpnFileText = null },
                            onPickOvpnFile = { ovpnFilePickerLauncher.launch("*/*") },
                            backupFileText = backupFileText,
                            onBackupFileConsumed = { backupFileText = null },
                            onPickBackupFile = { backupFilePickerLauncher.launch("*/*") },
                            onShareText = { text, title -> shareText(text, title) },
                            onThemeModeChanged = { themeMode = it },
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
        val profile = repo.loadProfiles().firstOrNull { it.id == profileId } ?: return
        if (profile.protocol == "wireguard") {
            lifecycleScope.launch {
                WireGuardManager.connect(this@MainActivity, profile.name, profile.wgConfigText)
            }
            return
        }
        if (profile.protocol == "openvpn") {
            lifecycleScope.launch {
                OpenVpnManager.connect(this@MainActivity, profile.name, profile.ovpnConfigText)
            }
            return
        }
        val svc = Intent(this, ProxyVpnService::class.java).apply {
            action = ProxyVpnService.ACTION_CONNECT
            putExtra(ProxyVpnService.EXTRA_PROFILE_ID, profileId)
        }
        startForegroundService(svc)
    }

    private fun disconnect() {
        val activeProfile = repo.loadProfiles().firstOrNull { it.id == repo.loadSelectedProfileId() }
        if (activeProfile?.protocol == "wireguard") {
            lifecycleScope.launch { WireGuardManager.disconnect(this@MainActivity) }
            return
        }
        if (activeProfile?.protocol == "openvpn") {
            OpenVpnManager.disconnect(this@MainActivity)
            return
        }
        val svc = Intent(this, ProxyVpnService::class.java).apply {
            action = ProxyVpnService.ACTION_DISCONNECT
        }
        startService(svc)
    }
}

private enum class Screen { Main, AppPicker, Info }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppRoot(
    repo: Repository,
    scannedLink: String?,
    onScannedConsumed: () -> Unit,
    onScanQr: () -> Unit,
    wgFileText: String?,
    onWgFileConsumed: () -> Unit,
    onPickWgFile: () -> Unit,
    ovpnFileText: String?,
    onOvpnFileConsumed: () -> Unit,
    onPickOvpnFile: () -> Unit,
    backupFileText: String?,
    onBackupFileConsumed: () -> Unit,
    onPickBackupFile: () -> Unit,
    onShareText: (text: String, title: String) -> Unit,
    onThemeModeChanged: (ThemeMode) -> Unit,
    onConnect: (String) -> Unit,
    onDisconnect: () -> Unit
) {
    var profiles by remember { mutableStateOf(repo.loadProfiles()) }
    var subscriptions by remember { mutableStateOf(repo.loadSubscriptions()) }
    var selectedId by remember { mutableStateOf(repo.loadSelectedProfileId()) }
    var settings by remember { mutableStateOf(repo.loadSettings()) }
    var searchQuery by remember { mutableStateOf("") }

    val groupedProfiles: List<Pair<Subscription?, List<ProxyProfile>>> = remember(profiles, subscriptions) {
        val bySub = profiles.groupBy { it.subscriptionId }
        val subGroups = subscriptions.mapNotNull { sub -> bySub[sub.id]?.let { sub to it } }
        val manual = bySub[null]?.takeIf { it.isNotEmpty() }?.let { null to it }
        if (manual != null) subGroups + listOf(manual) else subGroups
    }

    val visibleGroups = remember(groupedProfiles, searchQuery) {
        if (searchQuery.isBlank()) groupedProfiles
        else groupedProfiles.mapNotNull { (sub, list) ->
            val filtered = list.filter {
                it.name.contains(searchQuery, ignoreCase = true) || it.address.contains(searchQuery, ignoreCase = true)
            }
            if (filtered.isEmpty()) null else sub to filtered
        }
    }

    val selectedProtocol = profiles.firstOrNull { it.id == selectedId }?.protocol

    val xrayState by ProxyVpnService.state.collectAsState()
    val wgState by WireGuardManager.state.collectAsState()
    val ovpnState by OpenVpnManager.state.collectAsState()
    val connState = when (selectedProtocol) {
        "wireguard" -> wgState
        "openvpn" -> ovpnState
        else -> xrayState
    }

    val trafficStats by ProxyVpnService.stats.collectAsState() // only wired up for Xray so far

    val xrayError by ProxyVpnService.lastError.collectAsState()
    val wgError by WireGuardManager.lastError.collectAsState()
    val ovpnError by OpenVpnManager.lastError.collectAsState()
    val lastError = when (selectedProtocol) {
        "wireguard" -> wgError
        "openvpn" -> ovpnError
        else -> xrayError
    }

    var showAddDialog by remember { mutableStateOf(false) }
    var showSettingsSheet by remember { mutableStateOf(false) }
    var showSupportDialog by remember { mutableStateOf(false) }
    var showErrorDetail by remember { mutableStateOf(false) }
    var editingProfile by remember { mutableStateOf<ProxyProfile?>(null) }
    var deletingProfile by remember { mutableStateOf<ProxyProfile?>(null) }
    var screen by rememberSaveable { mutableStateOf<Screen>(Screen.Main) }
    var quickConnecting by remember { mutableStateOf(false) }
    var pingingIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var pingResults by remember { mutableStateOf<Map<String, Long>>(emptyMap()) }
    var expandedGroups by rememberSaveable { mutableStateOf<Set<String>>(emptySet()) }
    var ads by remember { mutableStateOf<List<AdItem>>(emptyList()) }
    var showFirstLaunchAd by remember { mutableStateOf(false) }

    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    fun openAdLink(ad: AdItem, placement: String) {
        AdManager.reportClick(ad.id, placement)
        try {
            context.startActivity(Intent(Intent.ACTION_VIEW, android.net.Uri.parse(ad.link)))
        } catch (_: Exception) {
        }
    }

    LaunchedEffect(Unit) {
        val fetched = AdManager.fetchAds()
        ads = fetched
        val forFirstLaunch = fetched.forPlacement("first_launch")
        val forMainCard = fetched.forPlacement("main_card")
        forMainCard.firstOrNull()?.let { AdManager.reportImpression(it.id, "main_card") }
        if (forFirstLaunch.isNotEmpty() && !repo.isFirstLaunchAdShown()) {
            showFirstLaunchAd = true
            repo.setFirstLaunchAdShown()
        }
    }

    fun pingOne(profile: ProxyProfile) {
        if (profile.id in pingingIds) return
        pingingIds = pingingIds + profile.id
        scope.launch {
            val ms = PingTester.measure(profile)
            pingingIds = pingingIds - profile.id
            pingResults = pingResults + (profile.id to ms)
        }
    }

    fun pingAllVisible() {
        groupedProfiles.flatMap { it.second }
            .filter { it.protocol != "wireguard" && it.protocol != "openvpn" }
            .forEach { pingOne(it) }
    }

    LaunchedEffect(lastError) {
        lastError?.let {
            val result = snackbarHostState.showSnackbar(it, actionLabel = "جزئیات")
            if (result == SnackbarResult.ActionPerformed) showErrorDetail = true
        }
    }

    // Runs once per app launch (this composable is created fresh each time MainActivity
    // starts). Only fires if nothing is already connecting/connected, so re-composition
    // from unrelated state changes won't repeatedly trigger it.
    LaunchedEffect(Unit) {
        if (settings.autoConnectOnLaunch && selectedId != null &&
            connState == ConnectionState.DISCONNECTED
        ) {
            onConnect(selectedId!!)
        }
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

    LaunchedEffect(wgFileText) {
        wgFileText?.let { raw ->
            val parsed = WireGuardParser.parse(raw)
            if (parsed != null) {
                profiles = (profiles + parsed).toMutableList()
                repo.saveProfiles(profiles)
                if (selectedId == null) {
                    selectedId = parsed.id
                    repo.saveSelectedProfileId(parsed.id)
                }
                snackbarHostState.showSnackbar("سرور «${parsed.name}» اضافه شد")
            } else {
                snackbarHostState.showSnackbar("این فایل WireGuard خوانده نشد")
            }
            onWgFileConsumed()
        }
    }

    LaunchedEffect(ovpnFileText) {
        ovpnFileText?.let { raw ->
            val parsed = OpenVpnParser.parse(raw)
            if (parsed != null) {
                profiles = (profiles + parsed).toMutableList()
                repo.saveProfiles(profiles)
                if (selectedId == null) {
                    selectedId = parsed.id
                    repo.saveSelectedProfileId(parsed.id)
                }
                snackbarHostState.showSnackbar("سرور «${parsed.name}» اضافه شد")
            } else {
                snackbarHostState.showSnackbar("این فایل OpenVPN خوانده نشد")
            }
            onOvpnFileConsumed()
        }
    }

    LaunchedEffect(backupFileText) {
        backupFileText?.let { json ->
            if (repo.importBackupJson(json)) {
                profiles = repo.loadProfiles()
                subscriptions = repo.loadSubscriptions()
                snackbarHostState.showSnackbar("بازیابی با موفقیت انجام شد")
            } else {
                snackbarHostState.showSnackbar("این فایل پشتیبان خوانده نشد")
            }
            onBackupFileConsumed()
        }
    }

    if (screen == Screen.AppPicker) {
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

    if (screen == Screen.Info) {
        val infoAds = ads.forPlacement("info_screen")
        LaunchedEffect(infoAds) {
            infoAds.forEach { AdManager.reportImpression(it.id, "info_screen") }
        }
        InfoScreen(ads = infoAds, onAdClick = { openAdLink(it, "info_screen") }, onBack = { screen = Screen.Main })
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

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("VELOX VPN") },
                actions = {
                    IconButton(onClick = { screen = Screen.Info }) {
                        Icon(Icons.Filled.Info, contentDescription = "راهنما")
                    }
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
                killSwitchActive = selectedProtocol != "wireguard" && selectedProtocol != "openvpn" &&
                    settings.killSwitch && connState == ConnectionState.ERROR,
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

            ads.forPlacement("main_card").firstOrNull()?.let { ad ->
                Spacer(Modifier.height(12.dp))
                AdCard(ad = ad, onClick = { openAdLink(ad, "main_card") })
            }

            subscriptions.filter { it.expireAtEpochSec != null || (it.dataLimitBytes ?: 0) > 0 }.forEach { sub ->
                Spacer(Modifier.height(12.dp))
                SubscriptionStatusCard(sub, onRefresh = { refreshSubscription(sub) })
            }

            Spacer(Modifier.height(16.dp))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("سرورها", style = MaterialTheme.typography.titleMedium)
                if (groupedProfiles.isNotEmpty()) {
                    TextButton(onClick = { pingAllVisible() }) {
                        Icon(Icons.Filled.Speed, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("پینگ همه")
                    }
                }
            }

            if (groupedProfiles.size > 1 || groupedProfiles.sumOf { it.second.size } > 5) {
                Spacer(Modifier.height(6.dp))
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("جستجوی سرور…") },
                    singleLine = true,
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(Icons.Filled.Close, contentDescription = "پاک کردن")
                            }
                        }
                    }
                )
            }
            Spacer(Modifier.height(4.dp))

            if (groupedProfiles.isEmpty()) {
                Text(
                    "هنوز سروری اضافه نکردی",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(vertical = 12.dp)
                )
            } else if (visibleGroups.isEmpty()) {
                Text(
                    "چیزی با این جستجو پیدا نشد",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(vertical = 12.dp)
                )
            }

            visibleGroups.forEach { (sub, list) ->
                val groupKey = sub?.id ?: "manual"
                val isExpanded = groupKey in expandedGroups
                Spacer(Modifier.height(10.dp))
                ElevatedCard(Modifier.fillMaxWidth()) {
                    Column {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    expandedGroups = if (isExpanded) expandedGroups - groupKey else expandedGroups + groupKey
                                }
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    Modifier
                                        .padding(end = 8.dp)
                                        .size(8.dp)
                                        .background(
                                            if (sub != null) MaterialTheme.colorScheme.primary
                                            else MaterialTheme.colorScheme.tertiary,
                                            shape = androidx.compose.foundation.shape.CircleShape
                                        )
                                )
                                Text(
                                    sub?.label ?: "افزوده شده دستی",
                                    style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Surface(
                                    shape = androidx.compose.foundation.shape.RoundedCornerShape(50),
                                    color = MaterialTheme.colorScheme.surfaceVariant
                                ) {
                                    Text(
                                        "${list.size} سرور",
                                        style = MaterialTheme.typography.labelSmall,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                                    )
                                }
                                Spacer(Modifier.width(6.dp))
                                Icon(
                                    if (isExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                                    contentDescription = if (isExpanded) "بستن" else "باز کردن"
                                )
                            }
                        }
                        if (isExpanded) {
                            Divider()
                            list.forEach { profile ->
                                ServerRow(
                                    profile = profile,
                                    selected = profile.id == selectedId,
                                    pinging = profile.id in pingingIds,
                                    pingResult = pingResults[profile.id],
                                    onSelect = {
                                        selectedId = profile.id
                                        repo.saveSelectedProfileId(profile.id)
                                    },
                                    onPing = { pingOne(profile) },
                                    onEdit = { editingProfile = profile },
                                    onShare = {
                                        val text = ConfigExporter.toShareableText(profile)
                                        if (text.isNotBlank()) onShareText(text, "اشتراک‌گذاری «${profile.name}»")
                                        else scope.launch { snackbarHostState.showSnackbar("چیزی برای اشتراک‌گذاری نیست") }
                                    },
                                    onDelete = { deletingProfile = profile }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showFirstLaunchAd) {
        ads.forPlacement("first_launch").firstOrNull()?.let { ad ->
            AlertDialog(
                onDismissRequest = { showFirstLaunchAd = false },
                title = { Text(ad.title) },
                text = { Text(ad.body) },
                confirmButton = {
                    TextButton(onClick = { showFirstLaunchAd = false; openAdLink(ad, "first_launch") }) { Text("مشاهده") }
                },
                dismissButton = { TextButton(onClick = { showFirstLaunchAd = false }) { Text("بعداً") } }
            )
        }
    }

    if (showErrorDetail) {
        AlertDialog(
            onDismissRequest = { showErrorDetail = false },
            title = { Text("جزئیات خطای اتصال") },
            text = {
                val isXray = selectedProtocol != "wireguard" && selectedProtocol != "openvpn"
                val detail = (if (isXray) repo.getLastConnectError(context) else null) ?: lastError ?: "جزئیاتی ثبت نشده"
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    SelectionContainer {
                        Text(detail, style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val isXray = selectedProtocol != "wireguard" && selectedProtocol != "openvpn"
                    val detail = (if (isXray) repo.getLastConnectError(context) else null) ?: lastError ?: ""
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_TEXT, detail)
                    }
                    context.startActivity(Intent.createChooser(send, "ارسال گزارش خطا"))
                }) { Text("اشتراک‌گذاری") }
            },
            dismissButton = { TextButton(onClick = { showErrorDetail = false }) { Text("بستن") } }
        )
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
            },
            onAddWireGuard = { raw ->
                val parsed = WireGuardParser.parse(raw)
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
                    scope.launch { snackbarHostState.showSnackbar("این کانفیگ WireGuard خوانده نشد") }
                    false
                }
            },
            onPickWgFile = { showAddDialog = false; onPickWgFile() },
            onAddOpenVpn = { raw ->
                val parsed = OpenVpnParser.parse(raw)
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
                    scope.launch { snackbarHostState.showSnackbar("این کانفیگ OpenVPN خوانده نشد") }
                    false
                }
            },
            onPickOvpnFile = { showAddDialog = false; onPickOvpnFile() }
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

    deletingProfile?.let { profile ->
        AlertDialog(
            onDismissRequest = { deletingProfile = null },
            title = { Text("حذف سرور") },
            text = { Text("سرور «${profile.name}» حذف بشه؟") },
            confirmButton = {
                TextButton(onClick = {
                    profiles = profiles.filterNot { it.id == profile.id }.toMutableList()
                    repo.saveProfiles(profiles)
                    if (selectedId == profile.id) selectedId = null
                    deletingProfile = null
                }) { Text("حذف", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deletingProfile = null }) { Text("انصراف") } }
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
                onThemeModeChanged(it.themeMode)
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
            onRenameSubscription = { sub, newLabel ->
                subscriptions = subscriptions.map { if (it.id == sub.id) it.copy(label = newLabel) else it }.toMutableList()
                repo.saveSubscriptions(subscriptions)
            },
            onDeleteSubscription = { sub -> deleteSubscription(sub) },
            onExportBackup = { onShareText(repo.exportBackupJson(), "خروجی پشتیبان از سرورها") },
            onPickBackupFile = onPickBackupFile
        )
    }

    if (showSupportDialog) {
        AlertDialog(
            onDismissRequest = { showSupportDialog = false },
            title = { Text("پشتیبانی") },
            text = {
                Text("برای دریافت سرور جدید یا کمک، به آیدی تلگرام @dfge2_u پیام بده.")
            },
            confirmButton = { TextButton(onClick = { showSupportDialog = false }) { Text("باشه") } }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdCard(ad: AdItem, onClick: () -> Unit) {
    OutlinedCard(
        Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(ad.title, style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(4.dp))
            Text(ad.body, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubscriptionStatusCard(sub: Subscription, onRefresh: () -> Unit) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(sub.label, style = MaterialTheme.typography.labelLarge)
                IconButton(onClick = onRefresh, modifier = Modifier.size(28.dp)) {
                    Icon(Icons.Filled.Refresh, contentDescription = "بروزرسانی وضعیت اشتراک")
                }
            }
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
    pinging: Boolean,
    pingResult: Long?,
    onSelect: () -> Unit,
    onPing: () -> Unit,
    onEdit: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit
) {
    val pingSupported = profile.protocol != "wireguard" && profile.protocol != "openvpn"
    val pingLabel = when {
        !pingSupported -> null
        pingResult == null -> null
        pingResult in 0..60000 -> "${pingResult} ms"
        else -> "بدون پاسخ"
    }
    var showMenu by remember { mutableStateOf(false) }

    ListItem(
        headlineContent = { Text(profile.name) },
        supportingContent = {
            Text(
                "${profile.protocol.uppercase()} · ${profile.address}:${profile.port}" +
                    (pingLabel?.let { "  ·  $it" } ?: "")
            )
        },
        leadingContent = { RadioButton(selected = selected, onClick = onSelect) },
        trailingContent = {
            Row {
                if (pingSupported) {
                    IconButton(onClick = onPing, enabled = !pinging) {
                        if (pinging) CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                        else Icon(Icons.Filled.Speed, contentDescription = "تست پینگ")
                    }
                }
                Box {
                    IconButton(onClick = { showMenu = true }) {
                        Icon(Icons.Filled.MoreVert, contentDescription = "بیشتر")
                    }
                    DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                        DropdownMenuItem(
                            text = { Text("ویرایش") },
                            leadingIcon = { Icon(Icons.Filled.Edit, contentDescription = null) },
                            onClick = { showMenu = false; onEdit() }
                        )
                        DropdownMenuItem(
                            text = { Text("اشتراک‌گذاری") },
                            leadingIcon = { Icon(Icons.Filled.Share, contentDescription = null) },
                            onClick = { showMenu = false; onShare() }
                        )
                        DropdownMenuItem(
                            text = { Text("حذف") },
                            leadingIcon = { Icon(Icons.Filled.Delete, contentDescription = null) },
                            onClick = { showMenu = false; onDelete() }
                        )
                    }
                }
            }
        },
        modifier = Modifier.clickable(onClick = onSelect)
    )
    Divider()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddServerDialog(
    onDismiss: () -> Unit,
    onScanQr: () -> Unit,
    onAdd: (String) -> Boolean,
    onAddWireGuard: (String) -> Boolean,
    onPickWgFile: () -> Unit,
    onAddOpenVpn: (String) -> Boolean,
    onPickOvpnFile: () -> Unit
) {
    var mode by remember { mutableStateOf("link") } // "link" | "wireguard" | "openvpn"
    var text by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    val clipboardManager = androidx.compose.ui.platform.LocalClipboardManager.current

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("افزودن سرور") },
        text = {
            Column {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = mode == "link",
                        onClick = { mode = "link"; error = null },
                        label = { Text("لینک") }
                    )
                    FilterChip(
                        selected = mode == "wireguard",
                        onClick = { mode = "wireguard"; error = null },
                        label = { Text("WireGuard") }
                    )
                    FilterChip(
                        selected = mode == "openvpn",
                        onClick = { mode = "openvpn"; error = null },
                        label = { Text("OpenVPN") }
                    )
                }
                Spacer(Modifier.height(10.dp))

                when (mode) {
                    "link" -> {
                        Text(
                            "لینک vmess:// vless:// trojan:// یا ss:// را وارد کن، یا کد QR رو اسکن کن.",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = onScanQr, modifier = Modifier.weight(1f)) {
                                Icon(Icons.Filled.QrCodeScanner, contentDescription = null)
                                Spacer(Modifier.width(6.dp))
                                Text("اسکن QR")
                            }
                            OutlinedButton(
                                onClick = { clipboardManager.getText()?.text?.let { text = it; error = null } },
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Filled.ContentPaste, contentDescription = null)
                                Spacer(Modifier.width(6.dp))
                                Text("چسباندن")
                            }
                        }
                    }
                    "wireguard" -> {
                        Text(
                            "متن کانفیگ WireGuard (شامل [Interface] و [Peer]) را پیست کن یا فایل .conf رو انتخاب کن.",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = onPickWgFile, modifier = Modifier.weight(1f)) {
                                Icon(Icons.Filled.FileOpen, contentDescription = null)
                                Spacer(Modifier.width(6.dp))
                                Text("انتخاب فایل")
                            }
                            OutlinedButton(
                                onClick = { clipboardManager.getText()?.text?.let { text = it; error = null } },
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Filled.ContentPaste, contentDescription = null)
                                Spacer(Modifier.width(6.dp))
                                Text("چسباندن")
                            }
                        }
                    }
                    else -> {
                        Text(
                            "متن کانفیگ OpenVPN (.ovpn) را پیست کن یا فایلش رو انتخاب کن.",
                            style = MaterialTheme.typography.bodySmall
                        )
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(onClick = onPickOvpnFile, modifier = Modifier.weight(1f)) {
                                Icon(Icons.Filled.FileOpen, contentDescription = null)
                                Spacer(Modifier.width(6.dp))
                                Text("انتخاب فایل")
                            }
                            OutlinedButton(
                                onClick = { clipboardManager.getText()?.text?.let { text = it; error = null } },
                                modifier = Modifier.weight(1f)
                            ) {
                                Icon(Icons.Filled.ContentPaste, contentDescription = null)
                                Spacer(Modifier.width(6.dp))
                                Text("چسباندن")
                            }
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it; error = null },
                    modifier = Modifier.fillMaxWidth(),
                    isError = error != null,
                    minLines = if (mode == "link") 3 else 6
                )
                error?.let {
                    Spacer(Modifier.height(4.dp))
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val ok = when (mode) {
                    "wireguard" -> onAddWireGuard(text)
                    "openvpn" -> onAddOpenVpn(text)
                    else -> onAdd(text)
                }
                if (!ok) error = when (mode) {
                    "wireguard" -> "این کانفیگ خوانده نشد — [Interface] و [Peer] رو چک کن"
                    "openvpn" -> "این کانفیگ خوانده نشد — باید خط remote داشته باشه"
                    else -> "این لینک خوانده نشد — فرمتش رو بررسی کن"
                }
            }) { Text("افزودن") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("انصراف") } }
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditServerDialog(profile: ProxyProfile, onDismiss: () -> Unit, onSave: (ProxyProfile) -> Unit) {
    if (profile.protocol == "wireguard") {
        var name by remember { mutableStateOf(profile.name) }
        var wgText by remember { mutableStateOf(profile.wgConfigText) }
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("ویرایش سرور WireGuard") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("نام") }, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = wgText, onValueChange = { wgText = it },
                        label = { Text("متن کانفیگ") }, modifier = Modifier.fillMaxWidth(), minLines = 8
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { onSave(profile.copy(name = name, wgConfigText = wgText)) }) { Text("ذخیره") }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text("انصراف") } }
        )
        return
    }

    if (profile.protocol == "openvpn") {
        var name by remember { mutableStateOf(profile.name) }
        var ovpnText by remember { mutableStateOf(profile.ovpnConfigText) }
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("ویرایش سرور OpenVPN") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("نام") }, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = ovpnText, onValueChange = { ovpnText = it },
                        label = { Text("متن کانفیگ") }, modifier = Modifier.fillMaxWidth(), minLines = 8
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { onSave(profile.copy(name = name, ovpnConfigText = ovpnText)) }) { Text("ذخیره") }
            },
            dismissButton = { TextButton(onClick = onDismiss) { Text("انصراف") } }
        )
        return
    }

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
    onRenameSubscription: (Subscription, String) -> Unit,
    onDeleteSubscription: (Subscription) -> Unit,
    onExportBackup: () -> Unit,
    onPickBackupFile: () -> Unit
) {
    var s by remember { mutableStateOf(settings) }
    var showAddSubDialog by remember { mutableStateOf(false) }
    var renamingSub by remember { mutableStateOf<Subscription?>(null) }
    var deletingSub by remember { mutableStateOf<Subscription?>(null) }

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
                    IconButton(onClick = { renamingSub = sub }) {
                        Icon(Icons.Filled.Edit, contentDescription = "تغییر نام")
                    }
                    IconButton(onClick = { onRefreshSubscription(sub) }) {
                        Icon(Icons.Filled.Refresh, contentDescription = "بروزرسانی")
                    }
                    IconButton(onClick = { deletingSub = sub }) {
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

            Text("پشتیبان‌گیری", style = MaterialTheme.typography.titleMedium)
            Text(
                "همه‌ی سرورها و اشتراک‌ها رو به یه فایل بگیر، یا از یه فایل قبلی برگردون.",
                style = MaterialTheme.typography.bodySmall
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onExportBackup, modifier = Modifier.weight(1f)) {
                    Text("خروجی گرفتن")
                }
                OutlinedButton(onClick = onPickBackupFile, modifier = Modifier.weight(1f)) {
                    Text("بازیابی از فایل")
                }
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
            if (s.muxEnabled) {
                Text(
                    "تعداد Mux Concurrency: ${s.muxConcurrency}",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(top = 4.dp)
                )
                Slider(
                    value = s.muxConcurrency.toFloat(),
                    onValueChange = { s = s.copy(muxConcurrency = it.toInt()) },
                    valueRange = 1f..16f,
                    steps = 14
                )
            }
            SwitchRow("ترافیک شبکه محلی مستقیم بره", s.bypassLan) { s = s.copy(bypassLan = it) }

            Spacer(Modifier.height(12.dp))
            Text("اتصال هوشمند", style = MaterialTheme.typography.titleMedium)
            Text(
                "این بخش و مسیریابی/تانل اپ‌ها فقط روی سرورهای VMess/VLESS/Trojan/SS اثر دارن، نه WireGuard",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(4.dp))
            SwitchRow("اتصال مجدد خودکار", s.autoReconnect) { s = s.copy(autoReconnect = it) }
            SwitchRow("سوییچ خودکار به سرور دیگر", s.autoFailover) { s = s.copy(autoFailover = it) }
            SwitchRow("Kill Switch (قطع اینترنت هنگام افت اتصال)", s.killSwitch) { s = s.copy(killSwitch = it) }
            TextButton(onClick = onOpenSystemVpnSettings) {
                Text("قفل کامل‌تر: تنظیمات VPN سیستم را باز کن")
            }

            Spacer(Modifier.height(12.dp))
            Divider()
            Spacer(Modifier.height(12.dp))
            Text("تنظیمات پیشرفته", style = MaterialTheme.typography.titleMedium)

            SwitchRow("اتصال خودکار هنگام باز شدن اپ", s.autoConnectOnLaunch) { s = s.copy(autoConnectOnLaunch = it) }
            SwitchRow("اتصال خودکار بعد از روشن شدن گوشی", s.autoConnectOnBoot) { s = s.copy(autoConnectOnBoot = it) }
            Text(
                "برای اتصال خودکار بعد از روشن شدن گوشی، باید حداقل یک‌بار قبلش مجوز VPN رو داده باشی.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.height(12.dp))
            Text("ظاهر برنامه", style = MaterialTheme.typography.bodyLarge)
            val themeLabels = mapOf(
                ThemeMode.SYSTEM to "مطابق سیستم",
                ThemeMode.LIGHT to "روشن",
                ThemeMode.DARK to "تاریک"
            )
            ThemeMode.entries.forEach { mode ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = s.themeMode == mode, onClick = { s = s.copy(themeMode = mode) })
                    Text(themeLabels[mode] ?: mode.name)
                }
            }

            Spacer(Modifier.height(12.dp))
            Text("MTU: ${s.mtu}", style = MaterialTheme.typography.bodyLarge)
            Text(
                "پیش‌فرض ۱۵۰۰ خوبه؛ اگه اینترنتت روی بعضی شبکه‌ها قطع‌وصل می‌شه، عددهای کمتر (مثلاً ۱۴۰۰) رو امتحان کن.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Slider(
                value = s.mtu.toFloat(),
                onValueChange = { s = s.copy(mtu = it.toInt()) },
                valueRange = 1280f..1500f
            )

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

    renamingSub?.let { sub ->
        var newLabel by remember(sub.id) { mutableStateOf(sub.label) }
        AlertDialog(
            onDismissRequest = { renamingSub = null },
            title = { Text("تغییر نام گروه") },
            text = {
                OutlinedTextField(
                    value = newLabel, onValueChange = { newLabel = it },
                    modifier = Modifier.fillMaxWidth(), singleLine = true
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    if (newLabel.isNotBlank()) onRenameSubscription(sub, newLabel)
                    renamingSub = null
                }) { Text("ذخیره") }
            },
            dismissButton = { TextButton(onClick = { renamingSub = null }) { Text("انصراف") } }
        )
    }

    deletingSub?.let { sub ->
        AlertDialog(
            onDismissRequest = { deletingSub = null },
            title = { Text("حذف اشتراک") },
            text = { Text("اشتراک «${sub.label}» و همه‌ی سرورهاش حذف بشن؟") },
            confirmButton = {
                TextButton(onClick = {
                    onDeleteSubscription(sub)
                    deletingSub = null
                }) { Text("حذف", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { deletingSub = null }) { Text("انصراف") } }
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
