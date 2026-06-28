package dev.mokouliszt.overlayai

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 起動・権限・ログイン・オーバーレイ開始/停止・設定・スキル管理のダッシュボード。
 */
class MainActivity : ComponentActivity() {

    private val auth by lazy { CodexAuth(this) }
    private val skillManager by lazy { SkillManager(filesDir) }
    private var overlayOk by mutableStateOf(false)
    private var serviceRunning by mutableStateOf(false)
    private var skills by mutableStateOf<List<SkillManager.Skill>>(emptyList())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        overlayOk = canDrawOverlays()
        serviceRunning = OverlayService.isRunning
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Dashboard()
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        overlayOk = canDrawOverlays()
        serviceRunning = OverlayService.isRunning
        CodexAuth.loggedInFlow.value = auth.isLoggedIn()
        skills = runCatching { skillManager.list() }.getOrDefault(emptyList())
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }

    @Composable
    private fun Dashboard() {
        val loggedIn by CodexAuth.loggedInFlow.collectAsState()
        val error by CodexAuth.errorFlow.collectAsState()
        val ready = overlayOk && loggedIn

        Column(
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(
                "Overlay AI",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )

            if (!overlayOk) PermissionCard()
            if (overlayOk && !loggedIn) LoginCard()

            if (ready) {
                StatusCard()
                SettingsCard()
                SkillsCard()
            }

            error?.let {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Text(
                        "\u26a0 $it",
                        Modifier.padding(14.dp),
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }

    @Composable
    private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
        Card(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                content()
            }
        }
    }

    @Composable
    private fun PermissionCard() = Section("\u2460 \u8868\u793a\u6a29\u9650") {
        Text(
            "\u4ed6\u30a2\u30d7\u30ea\u306e\u4e0a\u306b\u30d0\u30d6\u30eb\u3092\u51fa\u3059\u305f\u3081\u306e\u8a31\u53ef\u304c\u5fc5\u8981\u3067\u3059\u3002",
            style = MaterialTheme.typography.bodyMedium
        )
        Button(onClick = { requestOverlay() }) { Text("\u6a29\u9650\u8a2d\u5b9a\u3092\u958b\u304f") }
    }

    @Composable
    private fun LoginCard() {
        var busy by remember { mutableStateOf(false) }
        Section("\u2461 ChatGPT \u30ed\u30b0\u30a4\u30f3") {
            Text(
                "ChatGPT \u30a2\u30ab\u30a6\u30f3\u30c8\u3067\u30ed\u30b0\u30a4\u30f3\u3057\u3066\u304f\u3060\u3055\u3044\u3002",
                style = MaterialTheme.typography.bodyMedium
            )
            if (busy) CircularProgressIndicator()
            else Button(onClick = {
                busy = true
                auth.startLogin(this@MainActivity) { busy = false }
            }) { Text("ChatGPT \u3067\u30ed\u30b0\u30a4\u30f3") }
        }
    }

    @Composable
    private fun StatusCard() = Section("\u30aa\u30fc\u30d0\u30fc\u30ec\u30a4") {
        Text(
            if (serviceRunning) "\u7a3c\u50cd\u4e2d\u3067\u3059\u3002\u753b\u9762\u4e0a\u306e\u9ed2\u4e38\u304b\u3089\u5229\u7528\u3067\u304d\u307e\u3059\u3002"
            else "\u958b\u59cb\u3059\u308b\u3068\u4ed6\u30a2\u30d7\u30ea\u306e\u4e0a\u306b\u30d0\u30d6\u30eb\u304c\u51fa\u307e\u3059\u3002",
            style = MaterialTheme.typography.bodyMedium
        )
        Button(
            onClick = {
                if (serviceRunning) { stopOverlay(); serviceRunning = false }
                else { startOverlay(); serviceRunning = true }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (serviceRunning) "\u30aa\u30fc\u30d0\u30fc\u30ec\u30a4\u3092\u505c\u6b62" else "\u30aa\u30fc\u30d0\u30fc\u30ec\u30a4\u3092\u958b\u59cb")
        }
    }

    @Composable
    private fun SettingsCard() {
        var resetWs by remember { mutableStateOf(readReset()) }
        Section("\u8a2d\u5b9a") {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("\u6b21\u56de\u958b\u59cb\u6642\u306b\u30ef\u30fc\u30af\u30b9\u30da\u30fc\u30b9\u3092\u30ea\u30bb\u30c3\u30c8")
                    Text(
                        "OFF \u3067\u524d\u56de\u306e\u4f5c\u696d\u30d5\u30a9\u30eb\u30c0\u5185\u5bb9\u3092\u5f15\u304d\u7d99\u304e",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Switch(checked = resetWs, onCheckedChange = { resetWs = it; writeReset(it) })
            }
        }
    }

    @Composable
    private fun SkillsCard() {
        val scope = rememberCoroutineScope()
        var msg by remember { mutableStateOf<String?>(null) }
        var working by remember { mutableStateOf(false) }

        val picker = rememberLauncherForActivityResult(
            ActivityResultContracts.OpenDocument()
        ) { uri: Uri? ->
            if (uri == null) return@rememberLauncherForActivityResult
            working = true; msg = null
            scope.launch {
                val result = withContext(Dispatchers.IO) {
                    runCatching {
                        contentResolver.openInputStream(uri)?.use { skillManager.install(it) }
                            ?: error("\u30d5\u30a1\u30a4\u30eb\u3092\u958b\u3051\u307e\u305b\u3093")
                    }
                }
                working = false
                result.onSuccess {
                    msg = "\u8ffd\u52a0\u3057\u307e\u3057\u305f: ${it.name}"
                    skills = skillManager.list()
                }.onFailure { msg = "\u5931\u6557: ${it.message}" }
            }
        }

        Section("\u30b9\u30ad\u30eb") {
            Text(
                "SKILL.md \u3092\u542b\u3080 .zip / .skill \u3092\u8ffd\u52a0\u3059\u308b\u3068\u3001AI \u304c\u5fc5\u8981\u306b\u5fdc\u3058\u3066\u53c2\u7167\u30fb\u5b9f\u884c\u3057\u307e\u3059\u3002",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (skills.isEmpty()) {
                Text(
                    "\u672a\u767b\u9332\u3067\u3059\u3002",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            } else {
                skills.forEachIndexed { i, s ->
                    if (i > 0) HorizontalDivider()
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(s.name, fontWeight = FontWeight.Medium)
                            if (s.description.isNotBlank()) Text(
                                s.description,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 3
                            )
                        }
                        TextButton(onClick = {
                            skillManager.delete(s.dir.name); skills = skillManager.list()
                        }) { Text("\u524a\u9664") }
                    }
                }
            }

            OutlinedButton(
                onClick = { picker.launch(arrayOf("application/zip", "application/octet-stream", "*/*")) },
                enabled = !working,
                modifier = Modifier.fillMaxWidth()
            ) { Text(if (working) "\u8ffd\u52a0\u4e2d\u2026" else "\u30b9\u30ad\u30eb\u3092\u8ffd\u52a0 (.zip / .skill)") }

            msg?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
            Text(
                "\u203b Android \u306e\u30b7\u30a7\u30eb\u306f toybox \u76f8\u5f53\u3067\u3001python/node \u7b49\u306f\u5b9f\u884c\u3067\u304d\u307e\u305b\u3093\u3002",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    private fun readReset(): Boolean =
        getSharedPreferences(OverlayService.PREFS, Context.MODE_PRIVATE)
            .getBoolean(OverlayService.KEY_RESET_WS, true)

    private fun writeReset(v: Boolean) {
        getSharedPreferences(OverlayService.PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(OverlayService.KEY_RESET_WS, v).apply()
    }

    private fun canDrawOverlays(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.M || Settings.canDrawOverlays(this)

    private fun requestOverlay() {
        startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName")))
    }

    private fun startOverlay() {
        startForegroundService(
            Intent(this, OverlayService::class.java).setAction(OverlayService.ACTION_START)
        )
    }

    private fun stopOverlay() {
        stopService(Intent(this, OverlayService::class.java))
    }
}
