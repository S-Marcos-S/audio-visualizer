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
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material.icons.filled.Wallpaper
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import dev.viagaralauncher.visualizer.update.DownloadStatus
import dev.viagaralauncher.visualizer.update.RootInstaller
import dev.viagaralauncher.visualizer.update.UpdateInfo
import dev.viagaralauncher.visualizer.update.UpdateManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT)
        )
        prefs = VisualizerPreferences(this)
        playbackTracker = MediaPlaybackTracker(this)

        UpdateManager.init(this)
        UpdateManager.cleanupDownloadedApk(this)
        UpdateManager.checkForUpdates(this, force = false)

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

    val updateInfo by UpdateManager.updateAvailable.collectAsState()
    val isCheckingUpdates by UpdateManager.isCheckingUpdates.collectAsState()
    val downloadStatus by UpdateManager.downloadStatus.collectAsState()
    var isBetaTester by remember { mutableStateOf(UpdateManager.isBetaTesterEnabled(context)) }
    val isRoot = remember { RootInstaller.isRootAvailable() }

    var rootInfo by remember { mutableStateOf(RootInfo(RootStatus.CHECKING, "Verificando permissões de root...", "")) }
    var isCheckingRoot by remember { mutableStateOf(false) }
    var hasCustomWallpaper by remember { mutableStateOf(WallpaperHelper.hasCustomWallpaper(context)) }
    var hasSystemWallpaper by remember { mutableStateOf(WallpaperHelper.hasSystemWallpaper(context)) }

    fun refreshWallpaperState() {
        hasCustomWallpaper = WallpaperHelper.hasCustomWallpaper(context)
        hasSystemWallpaper = WallpaperHelper.hasSystemWallpaper(context)
    }

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri != null) {
            scope.launch(Dispatchers.IO) {
                val success = WallpaperHelper.saveCustomWallpaper(context, uri)
                withContext(Dispatchers.Main) {
                    refreshWallpaperState()
                    val msg = if (success) "Papel de parede personalizado aplicado!" else "Falha ao carregar imagem"
                    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    fun refreshRoot() {
        isCheckingRoot = true
        scope.launch {
            rootInfo = onCheckRoot()
            isCheckingRoot = false
        }
    }

    LaunchedEffect(Unit) {
        refreshRoot()
        scope.launch(Dispatchers.IO) {
            WallpaperHelper.ensureWallpaperCaptured(context)
            withContext(Dispatchers.Main) {
                refreshWallpaperState()
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
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
                        val newBar = Color(android.graphics.Color.HSVToColor(floatArrayOf(it, 1.0f, 1.0f)))
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
                        val newPeak = Color(android.graphics.Color.HSVToColor(floatArrayOf(it, 0.85f, 1.0f)))
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

        // 5. Papel de Parede Animado (Home Screen - Camada abaixo dos ícones)
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
                        imageVector = Icons.Default.Wallpaper,
                        contentDescription = null,
                        tint = settings.barColor,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.wallpaper_card_title),
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        color = Color.White
                    )
                }

                Spacer(modifier = Modifier.height(6.dp))

                Text(
                    text = stringResource(R.string.wallpaper_card_desc),
                    fontSize = 12.sp,
                    color = Color(0xFF888888),
                    lineHeight = 16.sp
                )

                Spacer(modifier = Modifier.height(14.dp))

                // Botão Aplicar como Papel de Parede
                Button(
                    onClick = {
                        scope.launch(Dispatchers.IO) {
                            WallpaperHelper.ensureWallpaperCaptured(context)
                            withContext(Dispatchers.Main) {
                                refreshWallpaperState()
                                WallpaperHelper.openLiveWallpaperChooser(context)
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = settings.barColor,
                        contentColor = Color.Black
                    )
                ) {
                    Icon(
                        imageVector = Icons.Default.Wallpaper,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.action_apply_wallpaper),
                        fontWeight = FontWeight.Bold,
                        fontSize = 13.sp
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Fundo do Papel de Parede
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = stringResource(R.string.wallpaper_bg_title),
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color.White
                    )

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(7.dp)
                                .clip(CircleShape)
                                .background(if (hasCustomWallpaper || hasSystemWallpaper) Color(0xFF00E676) else Color(0xFFFF9100))
                        )
                        Spacer(modifier = Modifier.width(5.dp))
                        Text(
                            text = when {
                                hasCustomWallpaper -> "Foto da Galeria"
                                hasSystemWallpaper -> "Wallpaper do Sistema"
                                else -> "Gradiente Escuro"
                            },
                            fontSize = 11.sp,
                            color = Color(0xFFAAAAAA)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Button(
                        onClick = {
                            photoPickerLauncher.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = Color(0xFF2E2E2E),
                            contentColor = Color.White
                        ),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp, horizontal = 4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Image,
                            contentDescription = null,
                            modifier = Modifier.size(16.dp),
                            tint = settings.barColor
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = stringResource(R.string.action_pick_image),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1
                        )
                    }

                    Button(
                        onClick = {
                            scope.launch(Dispatchers.IO) {
                                val success = WallpaperHelper.extractSystemWallpaperWithRoot(context)
                                withContext(Dispatchers.Main) {
                                    refreshWallpaperState()
                                    val msg = if (success) "Wallpaper do sistema capturado com sucesso!" else "Não foi possível extrair o wallpaper"
                                    Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                                }
                            }
                        },
                        modifier = Modifier.weight(1f),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = Color(0xFF2E2E2E),
                            contentColor = Color.White
                        ),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp, horizontal = 4.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.action_capture_system_wallpaper),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1
                        )
                    }
                }

                if (hasCustomWallpaper) {
                    Spacer(modifier = Modifier.height(8.dp))
                    Button(
                        onClick = {
                            WallpaperHelper.removeCustomWallpaper(context)
                            refreshWallpaperState()
                            Toast.makeText(context, "Imagem customizada removida. Usando papel de parede do sistema.", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                        colors = ButtonDefaults.filledTonalButtonColors(
                            containerColor = Color(0xFF262626),
                            contentColor = Color(0xFFFF5252)
                        ),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 6.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.action_clear_custom_image),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Slider de Posição Vertical
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = stringResource(R.string.wallpaper_vertical_pos_label),
                        fontSize = 13.sp,
                        color = Color(0xFFCCCCCC)
                    )
                    Text(
                        text = "${(settings.wallpaperVerticalPosition * 100).roundToInt()}%",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = settings.barColor
                    )
                }

                Slider(
                    value = settings.wallpaperVerticalPosition,
                    onValueChange = { prefs.setWallpaperVerticalPosition(it) },
                    valueRange = 0.10f..0.95f,
                    colors = SliderDefaults.colors(
                        thumbColor = settings.barColor,
                        activeTrackColor = settings.barColor,
                        inactiveTrackColor = Color(0xFF333333)
                    )
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Slider de Altura das Barras
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = stringResource(R.string.wallpaper_height_label),
                        fontSize = 13.sp,
                        color = Color(0xFFCCCCCC)
                    )
                    Text(
                        text = "${settings.wallpaperHeightDp} dp",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = settings.barColor
                    )
                }

                Slider(
                    value = settings.wallpaperHeightDp.toFloat(),
                    onValueChange = { prefs.setWallpaperHeight(it.roundToInt()) },
                    valueRange = 40f..240f,
                    colors = SliderDefaults.colors(
                        thumbColor = settings.barColor,
                        activeTrackColor = settings.barColor,
                        inactiveTrackColor = Color(0xFF333333)
                    )
                )

                Spacer(modifier = Modifier.height(8.dp))

                // Slider de Escurecimento (Dim / Contraste)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = stringResource(R.string.wallpaper_dim_label),
                        fontSize = 13.sp,
                        color = Color(0xFFCCCCCC)
                    )
                    Text(
                        text = "${(settings.wallpaperDim * 100).roundToInt()}%",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = settings.barColor
                    )
                }

                Slider(
                    value = settings.wallpaperDim,
                    onValueChange = { prefs.setWallpaperDim(it) },
                    valueRange = 0.00f..0.70f,
                    colors = SliderDefaults.colors(
                        thumbColor = settings.barColor,
                        activeTrackColor = settings.barColor,
                        inactiveTrackColor = Color(0xFF333333)
                    )
                )

                Spacer(modifier = Modifier.height(12.dp))

                // Switch de Mostrar Trilhas de Fundo
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = stringResource(R.string.wallpaper_show_guides),
                        fontSize = 13.sp,
                        color = Color.White
                    )

                    Switch(
                        checked = settings.wallpaperShowGuides,
                        onCheckedChange = { prefs.setWallpaperShowGuides(it) },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = settings.barColor,
                            checkedTrackColor = settings.barColor.copy(alpha = 0.4f)
                        )
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                // Prévia ao vivo da Home Screen
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(110.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFF141414))
                        .border(1.dp, Color(0xFF2E2E2E), RoundedCornerShape(12.dp))
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black.copy(alpha = settings.wallpaperDim))
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .align(Alignment.BottomCenter)
                            .padding(horizontal = 10.dp, vertical = (8 * settings.wallpaperVerticalPosition).dp)
                    ) {
                        AudioVisualizerView(
                            height = (settings.wallpaperHeightDp * 0.40f).dp,
                            barColor = settings.barColor,
                            peakColor = settings.peakColor,
                            trackColor = if (settings.wallpaperShowGuides) settings.barColor.copy(alpha = 0.28f) else Color.Transparent
                        )
                    }
                    Text(
                        text = "Pré-visualização do Live Wallpaper",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color(0xFF888888),
                        modifier = Modifier.padding(8.dp)
                    )
                }
            }
        }

        // 6. Configurações de Widgets e Overlay
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

        // 6. Atualizações do Aplicativo
        UpdateStatusCard(
            updateInfo = updateInfo,
            isChecking = isCheckingUpdates,
            downloadStatus = downloadStatus,
            isRoot = isRoot,
            accentColor = settings.barColor,
            onCheckAgain = {
                UpdateManager.checkForUpdates(context, force = true)
            },
            onDownload = { info ->
                UpdateManager.downloadUpdate(context, info)
            },
            onInstall = { uri, useRoot ->
                UpdateManager.installUpdate(context, uri, useRoot)
            }
        )

        // 7. Canal Beta / Teste
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E)),
            shape = RoundedCornerShape(18.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.settings_beta_tester_title),
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        color = Color.White
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = stringResource(R.string.settings_beta_tester_desc),
                        fontSize = 11.sp,
                        color = Color(0xFF888888),
                        lineHeight = 16.sp
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Switch(
                    checked = isBetaTester,
                    onCheckedChange = { checked ->
                        isBetaTester = checked
                        UpdateManager.setBetaTesterEnabled(context, checked)
                        UpdateManager.checkForUpdates(context, force = true)
                    },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = settings.barColor,
                        checkedTrackColor = settings.barColor.copy(alpha = 0.4f)
                    )
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))
    }
}

@Composable
private fun UpdateStatusCard(
    updateInfo: UpdateInfo?,
    isChecking: Boolean,
    downloadStatus: DownloadStatus,
    isRoot: Boolean,
    accentColor: Color,
    onCheckAgain: () -> Unit,
    onDownload: (UpdateInfo) -> Unit,
    onInstall: (Uri, Boolean) -> Unit
) {
    var isChangelogExpanded by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E)),
        shape = RoundedCornerShape(18.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            if (updateInfo != null && updateInfo.hasUpdate) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = stringResource(R.string.update_available_title),
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                color = accentColor
                            )
                            if (updateInfo.isBeta) {
                                Spacer(modifier = Modifier.width(6.dp))
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(Color(0xFFFF9100).copy(alpha = 0.2f))
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                ) {
                                    Text(
                                        text = stringResource(R.string.update_beta_badge),
                                        fontSize = 9.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFFFF9100)
                                    )
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "${stringResource(R.string.update_version_label)}: ${updateInfo.displayVersion}",
                            fontSize = 12.sp,
                            color = Color(0xFFAAAAAA)
                        )
                    }

                    Icon(
                        imageVector = Icons.Filled.SystemUpdate,
                        contentDescription = null,
                        tint = accentColor,
                        modifier = Modifier.size(24.dp)
                    )
                }

                if (updateInfo.changelog.isNotBlank()) {
                    val formattedChangelog = remember(updateInfo.changelog, accentColor) {
                        formatMarkdownToAnnotated(updateInfo.changelog, accentColor)
                    }

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color(0xFF141414))
                            .padding(10.dp)
                    ) {
                        if (isChangelogExpanded) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .heightIn(max = 240.dp)
                                    .verticalScroll(rememberScrollState())
                            ) {
                                Text(
                                    text = formattedChangelog,
                                    fontSize = 12.sp,
                                    color = Color(0xFFDDDDDD),
                                    lineHeight = 16.sp
                                )
                            }
                        } else {
                            Text(
                                text = formattedChangelog,
                                fontSize = 12.sp,
                                color = Color(0xFFDDDDDD),
                                maxLines = 4,
                                overflow = TextOverflow.Ellipsis,
                                lineHeight = 16.sp
                            )
                        }

                        Row(
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .clickable { isChangelogExpanded = !isChangelogExpanded }
                                .padding(vertical = 4.dp, horizontal = 2.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                imageVector = if (isChangelogExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                                contentDescription = null,
                                tint = accentColor,
                                modifier = Modifier.size(16.dp)
                            )
                            Text(
                                text = stringResource(if (isChangelogExpanded) R.string.action_show_less else R.string.action_show_more),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = accentColor
                            )
                        }
                    }
                }

                when (val status = downloadStatus) {
                    is DownloadStatus.Idle -> {
                        Button(
                            onClick = { onDownload(updateInfo) },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = accentColor,
                                contentColor = Color.Black
                            ),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.action_download_update),
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                    is DownloadStatus.Downloading -> {
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            LinearProgressIndicator(
                                progress = { status.progressPercent / 100f },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(6.dp)
                                    .clip(RoundedCornerShape(3.dp)),
                                color = accentColor,
                                trackColor = Color(0xFF333333)
                            )
                            Text(
                                text = "${status.progressPercent}%",
                                fontSize = 11.sp,
                                color = accentColor,
                                modifier = Modifier.align(Alignment.End),
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                    is DownloadStatus.Finished -> {
                        val uri = status.fileUri
                        if (uri != null) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                if (isRoot) {
                                    Button(
                                        onClick = { onInstall(uri, true) },
                                        modifier = Modifier.weight(1f),
                                        shape = RoundedCornerShape(10.dp),
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = accentColor,
                                            contentColor = Color.Black
                                        ),
                                        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp)
                                    ) {
                                        Text(
                                            text = stringResource(R.string.action_install_root),
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold,
                                            maxLines = 1
                                        )
                                    }
                                }
                                Button(
                                    onClick = { onInstall(uri, false) },
                                    modifier = Modifier.weight(1f),
                                    shape = RoundedCornerShape(10.dp),
                                    colors = if (isRoot) {
                                        ButtonDefaults.filledTonalButtonColors(
                                            containerColor = Color(0xFF2E2E2E),
                                            contentColor = Color.White
                                        )
                                    } else {
                                        ButtonDefaults.buttonColors(
                                            containerColor = accentColor,
                                            contentColor = Color.Black
                                        )
                                    },
                                    contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp)
                                ) {
                                    Text(
                                        text = stringResource(R.string.action_install),
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1
                                    )
                                }
                            }
                        }
                    }
                    is DownloadStatus.InstallingRoot -> {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp,
                                color = accentColor
                            )
                            Text(
                                text = stringResource(R.string.update_installing_root),
                                fontSize = 12.sp,
                                color = Color.White
                            )
                        }
                    }
                    is DownloadStatus.Failed -> {
                        Text(
                            text = "${stringResource(R.string.update_download_failed)}: ${status.error}",
                            fontSize = 12.sp,
                            color = Color(0xFFFF5252)
                        )
                        Button(
                            onClick = { onDownload(updateInfo) },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(10.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFF2E2E2E),
                                contentColor = Color.White
                            ),
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.action_retry),
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = if (isChecking) stringResource(R.string.update_checking) else stringResource(R.string.update_up_to_date),
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "${stringResource(R.string.update_current_version)}: v${BuildConfig.VERSION_NAME}",
                            fontSize = 12.sp,
                            color = Color(0xFF888888)
                        )
                    }

                    if (isChecking) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = accentColor
                        )
                    } else {
                        IconButton(onClick = onCheckAgain) {
                            Icon(
                                imageVector = Icons.Filled.Refresh,
                                contentDescription = stringResource(R.string.action_check_updates),
                                tint = accentColor
                            )
                        }
                    }
                }
            }
        }
    }
}

private fun formatMarkdownToAnnotated(text: String, primaryColor: Color): AnnotatedString {
    return buildAnnotatedString {
        val lines = text.lines()
        lines.forEachIndexed { index, rawLine ->
            val trimmed = rawLine.trim()
            when {
                trimmed.startsWith("---") || trimmed.startsWith("***") -> {
                    // subtle spacing
                }
                trimmed.startsWith("###") -> {
                    val content = trimmed.removePrefix("###").trim()
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = primaryColor)) {
                        append(content)
                    }
                }
                trimmed.startsWith("##") -> {
                    val content = trimmed.removePrefix("##").trim()
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = primaryColor)) {
                        append(content)
                    }
                }
                trimmed.startsWith("#") -> {
                    val content = trimmed.removePrefix("#").trim()
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = primaryColor)) {
                        append(content)
                    }
                }
                trimmed.startsWith("- ") || trimmed.startsWith("* ") -> {
                    append("• ")
                    parseInlineMarkdown(trimmed.substring(2).trim())
                }
                else -> {
                    parseInlineMarkdown(trimmed)
                }
            }

            if (index < lines.lastIndex && trimmed.isNotBlank()) {
                append("\n")
            }
        }
    }
}

private fun AnnotatedString.Builder.parseInlineMarkdown(line: String) {
    val tokenRegex = Regex("""(\*\*(.+?)\*\*|\*(.+?)\*|`(.+?)`)""")
    var lastIndex = 0
    for (match in tokenRegex.findAll(line)) {
        if (match.range.first > lastIndex) {
            append(line.substring(lastIndex, match.range.first))
        }
        val fullMatch = match.value
        when {
            fullMatch.startsWith("**") && fullMatch.endsWith("**") -> {
                val inner = fullMatch.substring(2, fullMatch.length - 2)
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                    append(inner)
                }
            }
            fullMatch.startsWith("*") && fullMatch.endsWith("*") -> {
                val inner = fullMatch.substring(1, fullMatch.length - 1)
                withStyle(SpanStyle(fontStyle = FontStyle.Italic)) {
                    append(inner)
                }
            }
            fullMatch.startsWith("`") && fullMatch.endsWith("`") -> {
                val inner = fullMatch.substring(1, fullMatch.length - 1)
                withStyle(SpanStyle(fontFamily = FontFamily.Monospace, background = Color(0xFF2A2A2A))) {
                    append(inner)
                }
            }
            else -> append(fullMatch)
        }
        lastIndex = match.range.last + 1
    }
    if (lastIndex < line.length) {
        append(line.substring(lastIndex))
    }
}
