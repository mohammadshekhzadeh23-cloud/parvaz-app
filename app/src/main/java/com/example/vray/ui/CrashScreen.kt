package com.example.vray.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun CrashScreen(trace: String, onDismiss: () -> Unit, onShare: () -> Unit) {
    Scaffold(
        topBar = { TopAppBar(title = { Text("برنامه با خطا بسته شد") }) }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize().padding(16.dp)) {
            Text(
                "این متن دقیق خطاست — می‌تونی بفرستیش تا مشکل برطرف بشه.",
                style = MaterialTheme.typography.bodyMedium
            )
            Spacer(Modifier.height(12.dp))

            OutlinedCard(Modifier.weight(1f).fillMaxWidth()) {
                SelectionContainer {
                    Text(
                        trace,
                        modifier = Modifier.padding(12.dp).verticalScroll(rememberScrollState()),
                        fontFamily = FontFamily.Monospace,
                        fontSize = 11.sp
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onShare, modifier = Modifier.weight(1f)) { Text("اشتراک‌گذاری متن خطا") }
                OutlinedButton(onClick = onDismiss, modifier = Modifier.weight(1f)) { Text("بستن و ادامه") }
            }
        }
    }
}
