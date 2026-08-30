package com.mangatranslator.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    sessionActive: Boolean,
    onStartSession: () -> Unit,
    onStopSession: () -> Unit,
    onOpenDictionary: () -> Unit,
    onOpenLanguagePacks: () -> Unit,
    onOpenSettings: () -> Unit
) {
    Scaffold(
        topBar = { TopAppBar(title = { Text("مترجم آفلاین مانگا") }) }
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(Modifier.height(24.dp))

            Icon(
                Icons.Filled.Translate,
                contentDescription = null,
                modifier = Modifier.size(72.dp),
                tint = MaterialTheme.colorScheme.primary
            )

            Spacer(Modifier.height(16.dp))

            Text(
                text = if (sessionActive)
                    "دکمه شناور فعال است. روی هر برنامه‌ای که باز کنید ظاهر می‌شود."
                else
                    "برای فعال‌سازی دکمه شناور و شروع ترجمه روی صفحه، دکمه زیر را بزنید.",
                style = MaterialTheme.typography.bodyLarge,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )

            Spacer(Modifier.height(24.dp))

            if (sessionActive) {
                Button(onClick = onStopSession, modifier = Modifier.fillMaxWidth()) {
                    Text("توقف ترجمه روی صفحه")
                }
            } else {
                Button(onClick = onStartSession, modifier = Modifier.fillMaxWidth()) {
                    Text("شروع ترجمه روی صفحه")
                }
            }

            Spacer(Modifier.height(32.dp))
            Divider()
            Spacer(Modifier.height(16.dp))

            OutlinedButton(
                onClick = onOpenLanguagePacks,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Filled.MenuBook, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("مدیریت بسته‌های زبان آفلاین")
            }

            Spacer(Modifier.height(12.dp))

            OutlinedButton(
                onClick = onOpenDictionary,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Filled.MenuBook, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("دیکشنری سفارشی من")
            }

            Spacer(Modifier.height(12.dp))

            OutlinedButton(
                onClick = onOpenSettings,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(Icons.Filled.Settings, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("تنظیمات")
            }

            Spacer(Modifier.height(24.dp))

            Text(
                text = "نکته: اولویت ترجمه همیشه با دیکشنری سفارشی شماست؛ در صورت نبود، از مدل آفلاین دانلود شده استفاده می‌شود.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center
            )
        }
    }
}
