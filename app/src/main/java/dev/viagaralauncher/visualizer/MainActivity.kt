// SPDX-License-Identifier: Apache-2.0
package dev.viagaralauncher.visualizer

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

class MainActivity : ComponentActivity() {

    private lateinit var prefs: VisualizerPreferences
    private var playbackTracker: MediaPlaybackTracker? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        AudioVisualizerManager.onResume(this)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = VisualizerPreferences(this)
        playbackTracker = MediaPlaybackTracker(this)

        requestAudioPermissionIfNeeded()

        setContent {
            AudioVisualizerTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color(0xFF121212)
                ) {
                    MainScreen(
                        prefs = prefs,
                        onCheckRoot = { checkRootStatus() },
                        onRequestOverlayPermission = { requestOverlayPermission() }
                    )
                }
            }
        }
    }

    private fun requestAudioPermissionIfNeeded() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun requestOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && !Settings.canDrawOverlays(this)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
        }
    }

    private suspend fun checkRootStatus(): RootInfo {
        return RootChecker.checkRoot()
    }

    override fun onResume() {
        super.onResume()
        AudioVisualizerManager.onResume(this)
        playbackTracker?.startTracking()
    }

    override fun onPause() {
        super.onPause()
        AudioVisualizerManager.onPause()
        playbackTracker?.stopTracking()
    }

    override fun onDestroy() {
        super.onDestroy()
        AudioVisualizerManager.killAnyOrphanDaemon()
    }
}

@Composable
fun AudioVisualizerTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = MaterialTheme.colorScheme.copy(
            primary = Color(0xFF00E5FF),
            secondary = Color(0xFF00E676),
            tertiary = Color(0xFFFF4081),
            background = Color(0xFF121212),
            surface = Color(0xFF1E1E1E),
            onPrimary = Color.Black,
            onBackground = Color(0xFFEEEEEE),
            onSurface = Color(0xFFE0E0E0),
        ),
        content = content
    )
}

@Composable
fun MainScreen(
    prefs: VisualizerPreferences,
    onCheckRoot: suspend () -> RootInfo,
    onRequestOverlayPermission: () -> Unit
) {
    val context = LocalContext.current
    val settings by prefs.settings.collectAsState()
    val frame by AudioVisualizerManager.frameFlow.collectAsState()
    val scope = rememberCoroutineScope()

    var rootInfo by remember { mutableStateOf(RootInfo(RootStatus.CHECKING, "Verificando permissões de root...", "")) }
    var isCheckingRoot by remember { mutableStateOf(false) }

    fun refreshRoot() {
        isCheckingRoot = true
        scope.launch {
            rootInfo = onCheckRoot()
            isCheckingRoot = false
        }
    }

    LaunchedEffect(Unit) {
        refreshRoot()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp)
    ) {
        // Cabeçalho minimalista
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column {
                Text(
                    text = "Audio Visualizer",
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
                Text(
                    text = "Session 0 • Balística de Alta Fidelidade",
                    fontSize = 12.sp,
                    color = Color(0xFF888888)
                )
            }
            Icon(
                imageVector = Icons.Default.GraphicEq,
                contentDescription = null,
                tint = settings.barColor,
                modifier = Modifier.size(32.dp)
            )
        }

        // 1. Live Preview do Visualizador
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF181818)),
            shape = RoundedCornerShape(20.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "PREVIEW AO VIVO",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFFAAAAAA),
                        letterSpacing = 1.sp
                    )

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(if (frame.hasAudio) Color(0xFF00E676) else Color(0xFFFFB300))
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (frame.hasAudio) "60 FPS • Áudio Ativo" else "Aguardando Som",
                            fontSize = 11.sp,
                            color = if (frame.hasAudio) Color(0xFF00E676) else Color(0xFFFFB300)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(130.dp)
                        .background(Color(0xFF101010), RoundedCornerShape(12.dp))
                        .padding(8.dp),
                    contentAlignment = Alignment.BottomCenter
                ) {
                    AudioVisualizerView(
                        modifier = Modifier.fillMaxSize(),
                        barColor = settings.barColor,
                        peakColor = settings.peakColor,
                        trackColor = settings.barColor.copy(alpha = settings.trackAlpha)
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))

                // RMS meter
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "14 Bandas Logarítmicas (25 Hz – 18.5 kHz)",
                        fontSize = 11.sp,
                        color = Color(0xFF666666)
                    )
                    Text(
                        text = "RMS: ${(frame.rms * 100).roundToInt()}%",
                        fontSize = 11.sp,
                        color = settings.barColor,
                        fontWeight = FontWeight.Medium
                    )
                }
            }
        }

        // 2. Status de Root (UID 0)
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = when (rootInfo.status) {
                    RootStatus.GRANTED -> Color(0xFF0A2615)
                    RootStatus.DENIED -> Color(0xFF2E1010)
                    else -> Color(0xFF1E1E1E)
                }
            ),
            shape = RoundedCornerShape(18.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = when (rootInfo.status) {
                            RootStatus.GRANTED -> Icons.Default.CheckCircle
                            RootStatus.DENIED -> Icons.Default.Warning
                            else -> Icons.Default.Security
                        },
                        contentDescription = null,
                        tint = when (rootInfo.status) {
                            RootStatus.GRANTED -> Color(0xFF00E676)
                            RootStatus.DENIED -> Color(0xFFFF5252)
                            else -> Color(0xFF888888)
                        },
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = when (rootInfo.status) {
                                RootStatus.GRANTED -> "Permissão de Root Concedida"
                                RootStatus.DENIED -> "Permissão de Root Negada"
                                RootStatus.NOT_AVAILABLE -> "Root Não Encontrado"
                                RootStatus.CHECKING -> "Verificando Root..."
                            },
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp,
                            color = Color.White
                        )
                        if (rootInfo.manager.isNotEmpty()) {
                            Text(
                                text = "Gerenciador: ${rootInfo.manager}",
                                fontSize = 11.sp,
                                color = Color(0xFFAAAAAA)
                            )
                        }
                    }

                    if (isCheckingRoot) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = Color.White
                        )
                    } else {
                        Button(
                            onClick = { refreshRoot() },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF2A2A2A),
                                contentColor = Color.White
                            ),
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = "Verificar",
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Verificar", fontSize = 12.sp)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = rootInfo.details,
                    fontSize = 12.sp,
                    color = Color(0xFFCCCCCC)
                )
            }
        }

        // 3. Velocidade do Visualizador
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E)),
            shape = RoundedCornerShape(18.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Speed,
                            contentDescription = null,
                            tint = settings.barColor,
                            modifier = Modifier.size(20.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Velocidade da Balística",
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp,
                            color = Color.White
                        )
                    }

                    Text(
                        text = "${(settings.speed * 100).roundToInt()}%",
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        color = settings.barColor
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                Slider(
                    value = settings.speed,
                    onValueChange = { prefs.setSpeed(it) },
                    valueRange = 0.10f..1.00f,
                    colors = SliderDefaults.colors(
                        thumbColor = settings.barColor,
                        activeTrackColor = settings.barColor,
                        inactiveTrackColor = Color(0xFF333333)
                    )
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("10% (VU Analógico)", fontSize = 11.sp, color = Color(0xFF777777))
                    Text(
                        text = when {
                            settings.speed < 0.35f -> "Suave & Amortecido"
                            settings.speed < 0.75f -> "Equilibrado & Líquido"
                            else -> "Agressivo & Ultra-Rápido"
                        },
                        fontSize = 11.sp,
                        color = Color(0xFFAAAAAA),
                        fontWeight = FontWeight.Medium
                    )
                    Text("100% (Instantâneo)", fontSize = 11.sp, color = Color(0xFF777777))
                }
            }
        }

        // 4. Opções de Escolha de Cor
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E)),
            shape = RoundedCornerShape(18.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Palette,
                        contentDescription = null,
                        tint = settings.peakColor,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Paleta de Cores",
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        color = Color.White
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Presets horizontais
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(VisualizerPreferences.PRESETS) { preset ->
                        val isSelected = settings.barColor == preset.barColor && settings.peakColor == preset.peakColor
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (isSelected) Color(0xFF2C2C2C) else Color(0xFF161616))
                                .border(
                                    width = if (isSelected) 1.5.dp else 1.dp,
                                    color = if (isSelected) preset.barColor else Color(0xFF333333),
                                    shape = RoundedCornerShape(12.dp)
                                )
                                .clickable {
                                    prefs.setColors(preset.barColor, preset.peakColor)
                                    AudioVisualizerWidgetReceiver.updateAllWidgets(context)
                                }
                                .padding(horizontal = 12.dp, vertical = 8.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(12.dp)
                                        .clip(CircleShape)
                                        .background(preset.barColor)
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .clip(CircleShape)
                                        .background(preset.peakColor)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = preset.name,
                                    fontSize = 12.sp,
                                    color = if (isSelected) Color.White else Color(0xFFAAAAAA),
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                )
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Custom Color Sliders
                Text(
                    text = "Ajuste Personalizado (Cor das Barras)",
                    fontSize = 12.sp,
                    color = Color(0xFFAAAAAA),
                    fontWeight = FontWeight.SemiBold
                )

                var hueBar by remember { mutableFloatStateOf(187f) }
                Slider(
                    value = hueBar,
                    onValueChange = {
                        hueBar = it
                        val newBar = Color.hsv(it, 1.0f, 1.0f)
                        prefs.setColors(newBar, settings.peakColor)
                        AudioVisualizerWidgetReceiver.updateAllWidgets(context)
                    },
                    valueRange = 0f..360f,
                    colors = SliderDefaults.colors(
                        thumbColor = settings.barColor,
                        activeTrackColor = settings.barColor,
                        inactiveTrackColor = Color(0xFF333333)
                    )
                )

                Text(
                    text = "Ajuste Personalizado (Cor dos Picos Flutuantes)",
                    fontSize = 12.sp,
                    color = Color(0xFFAAAAAA),
                    fontWeight = FontWeight.SemiBold
                )

                var huePeak by remember { mutableFloatStateOf(340f) }
                Slider(
                    value = huePeak,
                    onValueChange = {
                        huePeak = it
                        val newPeak = Color.hsv(it, 0.85f, 1.0f)
                        prefs.setColors(settings.barColor, newPeak)
                        AudioVisualizerWidgetReceiver.updateAllWidgets(context)
                    },
                    valueRange = 0f..360f,
                    colors = SliderDefaults.colors(
                        thumbColor = settings.peakColor,
                        activeTrackColor = settings.peakColor,
                        inactiveTrackColor = Color(0xFF333333)
                    )
                )
            }
        }

        // 5. Configurações de Widgets e Overlay
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E)),
            shape = RoundedCornerShape(18.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Layers,
                        contentDescription = null,
                        tint = settings.barColor,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Widgets & Overlay Flutuante",
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        color = Color.White
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Switch de Overlay Flutuante
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Widget Flutuante (Overlay)",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White
                        )
                        Text(
                            text = "Exibe o visualizador em 60 FPS sobreposto na tela",
                            fontSize = 11.sp,
                            color = Color(0xFF888888)
                        )
                    }

                    Switch(
                        checked = settings.isFloatingEnabled,
                        onCheckedChange = { enabled ->
                            if (enabled) {
                                onRequestOverlayPermission()
                                prefs.setFloatingEnabled(true)
                                FloatingVisualizerService.start(context)
                            } else {
                                prefs.setFloatingEnabled(false)
                                FloatingVisualizerService.stop(context)
                            }
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = settings.barColor,
                            checkedTrackColor = settings.barColor.copy(alpha = 0.4f)
                        )
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Informação do Widget de Tela Inicial
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFF141414))
                        .padding(12.dp)
                ) {
                    Column {
                        Text(
                            text = "📱 Widget de Tela Inicial Disponível",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Pressione e segure o dedo na tela inicial do seu celular para adicionar o widget 'Visualizador de Áudio'. Ele utilizará as cores e a balística configuradas aqui.",
                            fontSize = 11.sp,
                            color = Color(0xFFAAAAAA),
                            lineHeight = 16.sp
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
    }
}
