package com.example.vray.ui

import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class InstalledApp(val packageName: String, val label: String, val isSystem: Boolean)

private suspend fun loadInstalledApps(context: Context): List<InstalledApp> = withContext(Dispatchers.IO) {
    val pm = context.packageManager
    pm.getInstalledApplications(PackageManager.GET_META_DATA)
        .map {
            InstalledApp(
                packageName = it.packageName,
                label = pm.getApplicationLabel(it).toString(),
                isSystem = (it.flags and ApplicationInfo.FLAG_SYSTEM) != 0
            )
        }
        .sortedBy { it.label.lowercase() }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppPickerScreen(
    initiallySelected: Set<String>,
    onBack: () -> Unit,
    onConfirm: (Set<String>) -> Unit
) {
    val context = LocalContext.current
    var apps by remember { mutableStateOf<List<InstalledApp>>(emptyList()) }
    var selected by remember { mutableStateOf(initiallySelected) }
    var query by remember { mutableStateOf("") }
    var showSystemApps by remember { mutableStateOf(false) }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        apps = loadInstalledApps(context)
        loading = false
    }

    val visible = apps
        .filter { showSystemApps || !it.isSystem }
        .filter { it.label.contains(query, ignoreCase = true) || it.packageName.contains(query, ignoreCase = true) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("انتخاب اپ‌ها (${selected.size} انتخاب‌شده)") },
                navigationIcon = {
                    IconButton(onClick = { onConfirm(selected); onBack() }) {
                        Icon(Icons.Filled.ArrowBack, contentDescription = "بازگشت")
                    }
                }
            )
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            Column(Modifier.padding(16.dp, 8.dp)) {
                OutlinedTextField(
                    value = query, onValueChange = { query = it },
                    label = { Text("جستجوی اپ") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Row(
                    Modifier.fillMaxWidth().padding(top = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("نمایش اپ‌های سیستمی", style = MaterialTheme.typography.bodyMedium)
                    Switch(checked = showSystemApps, onCheckedChange = { showSystemApps = it })
                }
            }

            if (loading) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else {
                LazyColumn {
                    items(visible, key = { it.packageName }) { app ->
                        ListItem(
                            headlineContent = { Text(app.label) },
                            supportingContent = { Text(app.packageName, style = MaterialTheme.typography.bodySmall) },
                            trailingContent = {
                                Checkbox(
                                    checked = selected.contains(app.packageName),
                                    onCheckedChange = { checked ->
                                        selected = if (checked) selected + app.packageName
                                        else selected - app.packageName
                                    }
                                )
                            }
                        )
                        Divider()
                    }
                }
            }
        }
    }
}
