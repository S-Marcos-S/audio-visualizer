# Guia Técnico Definitivo: Visualizador de Áudio Real (Session 0) com Balística de Precisão e Renderização em Jetpack Compose

Este documento destrincha minuciosamente toda a arquitetura, física acústica, matemática balística, comunicação entre processos (IPC), ciclo de vida e renderização gráfica do visualizador de áudio implementado no **Viagara Launcher**.

Com este guia e seus códigos-fonte, qualquer desenvolvedor é capaz de recriar esta mesma funcionalidade em outro aplicativo Android, obtendo exatamente o mesmo comportamento, performance com zero consumo ocioso e a movimentação líquida e fluida das barras.

---

## 1. Visão Geral e o Desafio Técnico do Android

### 1.1 O Desafio da Sessão Global de Áudio no Android Moderno
A API oficial do Android possui a classe `android.media.audiofx.Visualizer`. Teoricamente, passar o identificador de sessão `0` (`Visualizer(0)`) conecta o visualizador à saída de áudio global do dispositivo, permitindo capturar o som emitido por **qualquer** aplicativo (Spotify, YouTube, Deezer, Apple Music, navegadores web, reprodutores locais, jogos, etc.).

No entanto, a partir do Android 9 (Pie) e refinado nas versões 10, 11, 12, 13, 14 e 15, o Android impõe restrições severas de segurança:
1. Aplicativos normais de usuário (executando sob UIDs comuns como `u0_a123`) são terminantemente proibidos de instanciar `Visualizer(0)`.
2. Se um app convencional tentar instanciar `Visualizer(0)`, a API lançará uma exceção imediata (`java.lang.UnsupportedOperationException` ou falhará nativamente com `Visualizer.ERROR_INVALID_OPERATION`), pois exige permissões protegidas de nível de sistema como `android.permission.MODIFY_AUDIO_ROUTING`.

### 1.2 A Solução Arquitetural: Arquitetura Cliente-Servidor Desacoplada
Para capturar o áudio global sem exigir ROM modificada ou módulos Xposed/LSPosed invasivos, utiliza-se a arquitetura de **Daemon Privilegiado**:
1. **Daemon em Background (`AudioVisualizerDaemon`):**
   - Um arquivo DEX autônomo (`visualizer_daemon.dex`) é executado diretamente pelo binário nativo `/system/bin/app_process64` sob **UID 0 (Root)** via shell `su`.
   - Executando como Root, o daemon tem permissões absolutas de áudio nativo (`AudioFlinger`) e instancia `Visualizer(0)` com sucesso.
   - Ele processa o espectro FFT em tempo real a 60 FPS, calcula as bandas acústicas e envia os dados via socket UNIX local.
2. **Canal de Comunicação IPC (`LocalSocket` Abstrato):**
   - O aplicativo abre um servidor de socket local UNIX no namespace abstrato Linux (`LocalServerSocket("viagara_audio_viz")`).
   - Sockets abstratos começam com o caractere nulo (`\0`) no Linux e residem estritamente em memória do kernel, não gerando arquivos temporários nem problemas de permissão de sistema de arquivos entre processos com UIDs diferentes (Root vs Usuário do App).
   - Comunicação bidirecional: o daemon transmite os quadros binários para o app, e o app envia comandos de controle de 1 byte (`P` para Pausar, `R` para Retomar, `S` para Finalizar).
3. **Gerenciador de Ciclo de Vida e Balística (`AudioVisualizerManager`):**
   - Responsável por extrair o `.dex`, inicializar e gerenciar o processo daemon.
   - Aplica filtros de balística assimétrica, física de picos flutuantes, compensação de curva acústica de ruído rosa (1/f) e limpeza de ruído residual de subgraves.
   - Suspende o consumo de CPU imediatamente quando o app vai para segundo plano ou a música é pausada.
4. **Camada Visual Reativa (`AudioVisualizerView`):**
   - Desenhada em Jetpack Compose através de um componente `Canvas` de alta performance acelerado por hardware.
   - Emula 14 tiras estéreo duplas (canais esquerdo e direito independentes) com marcadores de pico flutuantes (*floating peak caps*) e cantos arredondados ultra-suaves.

```
┌────────────────────────────────────────────────────────┐
│                   DISPOSITIVO ANDROID                  │
│                                                        │
│  [Spotify / YouTube / Deezer / Player de Música]       │
│                         │                              │
│                    (Áudio PCM)                         │
│                         ▼                              │
│             [Android AudioFlinger (Session 0)]         │
│                         │                              │
│ ┌───────────────────────┼────────────────────────────┐ │
│ │ DAEMON ROOT (UID 0)   │                            │ │
│ │                       ▼                            │ │
│ │  app_process64: Visualizer(0)                      │ │
│ │         │                                          │ │
│ │         ├──> FFT 1024 bins                         │ │
│ │         ├──> 14 Bandas Logarítmicas (Parseval RMS) │ │
│ │         └──> Fast EMA (alpha=0.35)                 │ │
│ │                     │                              │ │
│ │                     ▼                              │ │
│ │       LocalSocket ("viagara_audio_viz")            │ │
│ └─────────────────────┼──────────────────────────────┘ │
│                       │ Pacotes Binários (65 bytes)    │
│                       │ Comandos ('P', 'R', 'S')       │
│ ┌─────────────────────┼──────────────────────────────┐ │
│ │ SEU APLICATIVO      ▼                              │ │
│ │                                                    │ │
│ │  AudioVisualizerManager:                           │ │
│ │   ├── Desempacotamento Little-Endian               │ │
│ │   ├── Equalização Espectral (Pink Noise 1/f)       │ │
│ │   ├── Corte de Piso de Subgrave (Clean Floor)      │ │
│ │   ├── Balística Assimétrica (Subida Rápida /       │ │
│ │   │                          Descida Amortecida)   │ │
│ │   └── Física de Picos Flutuantes (Asymptotic Fall) │ │
│ │                     │                              │ │
│ │                     ▼ StateFlow<VisualizerFrame>   │ │
│ │                                                    │ │
│ │  AudioVisualizerView (Jetpack Compose Canvas):     │ │
│ │   ├── 14 Tiras Estéreo Duplas (Left + Right)       │ │
│ │   ├── Marcadores de Pico Flutuantes                │ │
│ │   └── Fade-in / Fade-out com animateFloatAsState   │ │
│ └────────────────────────────────────────────────────┘ │
└────────────────────────────────────────────────────────┘
```

---

## 2. A Física Acústica e o Motor Espectral (`AudioVisualizerDaemon`)

### 2.1 Coleta da FFT e Configuração da Sessão
O daemon instancia o visualizador no áudio global e define o tamanho da captura:
```kotlin
visualizer = Visualizer(0)
visualizer.captureSize = 1024
visualizer.enabled = true
```
Com `captureSize = 1024`, a FFT do Android retorna 512 frequências discretas (*bins* complexos).

### 2.2 Cálculo da Largura de Banda por Bin
O cálculo da resolução em frequência depende da taxa de amostragem real da interface de áudio do dispositivo:
```kotlin
val rawSampleRate = visualizer.samplingRate
val sampleRateHz = if (rawSampleRate > 0) rawSampleRate / 1000 else 48000
val binWidth = sampleRateHz.toFloat() / actualCaptureSize.toFloat()
// Em 48 kHz com tamanho 1024: binWidth = 48000 / 1024 = 46.875 Hz por bin
```

### 2.3 As 14 Bandas Logarítmicas Acústicas
O ouvido humano não percebe frequências de forma linear, mas sim logarítmica (escalas de oitavas e bandas críticas de Bark). Por isso, dividir a FFT em intervalos lineares simples faz com que quase toda a informação musical fique concentrada nos primeiros 2 ou 3 pontos, deixando o restante imóvel.

No nosso pipeline, as 14 bandas são distribuídas exatamente cobrindo de **25 Hz a 18.500 Hz**:

| Banda | Faixa de Frequência | Faixa Musical / Instrumento Alvo | Papel no Visualizador |
|---|---|---|---|
| **0** | 25 Hz – 65 Hz | Subgrave profundo (*Sub-bass*, graves eletrônicos 808) | Vibração profunda de ar e peso |
| **1** | 65 Hz – 115 Hz | Bumbo da bateria (*Kick drum*) | Batida principal, impacto rítmico |
| **2** | 115 Hz – 175 Hz | Ataque de percussão (*Punch*) | Transição rítmica entre bumbo e baixo |
| **3** | 175 Hz – 260 Hz | Linha de contrabaixo (*Bassline*) | Notas melódicas do baixo e guitarras pesadas |
| **4** | 260 Hz – 390 Hz | Corpo harmônico inferior (*Warmth / Mud zone*) | Calor e corpo acústico da música |
| **5** | 390 Hz – 580 Hz | Caixa da bateria (*Snare body*), voz grave | Fundamental da caixa e voz masculina |
| **6** | 580 Hz – 870 Hz | Harmônicos médios (Voz principal, guitarras) | Região central de melodias |
| **7** | 870 Hz – 1.300 Hz | Clareza e inteligibilidade vocal, sintetizadores | Brilho dos médios melódicos |
| **8** | 1.300 Hz – 2.000 Hz | Presença e ataque melódico | Destaque e nitidez nos vocais |
| **9** | 2.000 Hz – 3.200 Hz | Estalo da caixa (*Snare snap*), transientes | Ataque de instrumentos percussivos |
| **10** | 3.200 Hz – 5.000 Hz | Agudos inferiores, pratos de ataque (*Crashes*) | Abertura espacial do som |
| **11** | 5.000 Hz – 8.000 Hz | Pratos de condução (*Rides*), sibilância vocal | Destaque rítmico de pratos |
| **12** | 8.000 Hz – 12.500 Hz | Chimbal fechado/aberto (*Hi-hats*), estalo metálico | Marcação rápida dos hi-hats |
| **13** | 12.500 Hz – 18.500 Hz | Ambiência e ar (*Shimmer / Ultra-high air*) | Sensação de alta-fidelidade e reverberação |

O array de limites:
```kotlin
val bandFreqs = floatArrayOf(
    25f, 65f, 115f, 175f, 260f, 390f, 580f, 870f,
    1300f, 2000f, 3200f, 5000f, 8000f, 12500f, 18500f
)
```

Mapeamento contíguo dos índices da FFT (*bins*), garantindo que nenhum bin seja ignorado:
```kotlin
val bandStartBins = IntArray(NUM_BANDS)
val bandEndBins = IntArray(NUM_BANDS)
var lastEndBin = 0
for (b in 0 until NUM_BANDS) {
    val start = max(lastEndBin + 1, (bandFreqs[b] / binWidth).roundToInt())
    val end = max(start, (bandFreqs[b + 1] / binWidth).roundToInt())
    bandStartBins[b] = start
    bandEndBins[b] = min(511, end)
    lastEndBin = bandEndBins[b]
}
```

### 2.4 Teorema de Parseval e Cálculo de Energia Espectral RMS
O buffer de FFT retornado pelo Android (`visualizer.getFft(fftBuffer)`) é composto por bytes com sinal (`Byte` de -128 a 127):
- `fft[0]`: Componente DC (tensão contínua, frequência 0 Hz, imaginário = 0).
- `fft[1]`: Componente de Nyquist (frequência limite `sampleRate / 2`, imaginário = 0).
- Para cada bin harmônico $k \ge 1$:
  - $Parte\ Real: Re = fft[2k] / 128.0f$
  - $Parte\ Imaginária: Im = fft[2k+1] / 128.0f$

Pelo **Teorema de Parseval**, a energia total contida em uma faixa de frequências é a soma das energias quadráticas de seus harmônicos:
$$\text{Potência}(k) = Re^2 + Im^2$$

Para converter amplitude de pico complexa em amplitude eficaz RMS (Root Mean Square), dividimos a soma da potência por 2 ($\sqrt{2}$ na amplitude equivale a divisão por 2 na potência):
$$\text{RMS}_{\text{banda}} = \sqrt{\frac{\sum (Re^2 + Im^2)}{2}}$$

Implementação em código:
```kotlin
private fun computeBandRms(fft: ByteArray, startBin: Int, endBin: Int): Float {
    var sumPower = 0.0
    val maxK = min(511, endBin)

    for (k in startBin..maxK) {
        val re: Float
        val im: Float
        if (k == 0) {
            re = fft[0].toFloat() / 128.0f
            im = 0.0f
        } else {
            re = fft[2 * k].toFloat() / 128.0f
            im = if (2 * k + 1 < fft.size) fft[2 * k + 1].toFloat() / 128.0f else 0.0f
        }
        sumPower += (re * re + im * im).toDouble()
    }

    return sqrt(sumPower / 2.0).toFloat()
}
```

### 2.5 Filtragem Prévia no Daemon (Fast EMA)
Para evitar picos de ruído elétrico espúrios e dar punch aos transientes percussivos (bumbo e caixa), o daemon aplica uma Média Móvel Exponencial (EMA) rápida com $\alpha = 0.35$ e ganho uniforme calibrado de $1.25\times$:
```kotlin
// EMA no daemon: absorve ruído espúrio mas reage velozmente a bumbos de 0.5s
emaBands[b] = emaBands[b] * 0.65f + rawRms * 0.35f
val level = min(1.0f, emaBands[b] * 1.25f)
magnitudes[b] = level
```

### 2.6 Formato do Pacote Binário na Rede IPC
A cada 16 milissegundos (~60 FPS), o daemon empacota os dados em um `ByteBuffer` Little-Endian compacto de **65 bytes**:
- **Bytes 0..3 (4 bytes):** Assinatura mágica ASCII: `'V'`, `'I'`, `'Z'`, `'1'`.
- **Byte 4 (1 byte):** Quantidade de bandas (`14`).
- **Bytes 5..8 (4 bytes):** Float32 com o RMS global da forma de onda (*waveform*).
- **Bytes 9..64 (56 bytes):** 14 valores Float32 com as magnitudes de cada banda.

Esse pacote binário direto elimina serializações JSON ou strings, com sobrecarga computacional praticamente nula.

---

## 3. O Segredo do Movimento: Balística de Precisão e Acústica (`AudioVisualizerManager`)

A razão pela qual muitos visualizadores de áudio parecem "duros", "tremidos" ou "lentos" é a falta de uma física balística adequada. O `AudioVisualizerManager` aplica um modelo balístico assimétrico com calibrações acústicas de alta fidelidade:

### 3.1 Curva de Velocidade Dinâmica
O usuário pode ajustar a velocidade de resposta entre 10% e 100% (`0.10f` a `1.00f`). A velocidade é normalizada e curvada exponencialmente para proporcionar um controle suave nas baixas velocidades e extrema agressividade nas altas:
```kotlin
val normSpeed = ((currentSpeed - 0.10f) / 0.90f).coerceIn(0f, 1f)
val speedCurve = normSpeed.pow(1.4f)

val baseRiseAlpha = (0.045f + 0.455f * speedCurve).coerceIn(0.035f, 0.60f)
val baseFallAlpha = (0.010f + 0.150f * speedCurve).coerceIn(0.008f, 0.20f)
val peakFallAlpha = (0.0018f + 0.012f * speedCurve).coerceIn(0.0015f, 0.018f)
```

### 3.2 O Princípio da Balística Assimétrica (Asymmetric Ballistics)
Observe as taxas de variação ($\alpha$):
- **Taxa de Subida (`baseRiseAlpha`):** Varia de **0.035 a 0.60**.
- **Taxa de Descida (`baseFallAlpha`):** Varia de **0.008 a 0.20**.

> [!IMPORTANT]
> **A Regra de Ouro do Movimento:**
> A taxa de subida é propositalmente **3 a 4 vezes mais rápida** do que a taxa de descida.
> Quando um bumbo ou prato atinge o microfone, as barras **disparam para cima instantaneamente** acompanhando o impacto. Quando o som cessa, as barras **não caem bruscamente**: elas descem de forma amortecida, suave e líquida, simulando a inércia física dos ponteiros de medidores VU analógicos de alta gama.

```kotlin
val current = currentLevels[i]
val alpha = if (target >= current) activeRiseAlpha else activeFallAlpha
currentLevels[i] = current + (target - current) * alpha
if (currentLevels[i] < 0.002f) currentLevels[i] = 0f
```

### 3.3 Compensação Espectral de Ruído Rosa (Pink Noise 1/f Tilt)
Na física sonora de gravações comerciais, o espectro musical segue uma distribuição aproximadamente de **ruído rosa** ($1/f$): a energia na região dos graves e subgraves é imensamente maior do que a energia nos agudos. Se os valores brutos da FFT fossem exibidos diretamente:
- As barras de graves viveriam coladas no topo (100%).
- As barras de agudos mal se moveriam (10% a 15%).

Para solucionar isso e dar vida a todo o espectro, aplica-se o array de pesos de equalização espectral:
```kotlin
private val SPECTRAL_WEIGHTS = floatArrayOf(
    0.88f, // Banda 0:  25 - 65 Hz   (Subgrave)
    0.86f, // Banda 1:  65 - 115 Hz  (Bumbo)
    0.88f, // Banda 2:  115 - 175 Hz (Punch)
    0.91f, // Banda 3:  175 - 260 Hz (Contrabaixo)
    0.94f, // Banda 4:  260 - 390 Hz (Corpo)
    0.97f, // Banda 5:  390 - 580 Hz (Caixa)
    1.00f, // Banda 6:  580 - 870 Hz (Médios)
    1.03f, // Banda 7:  870 - 1300 Hz (Clareza vocal)
    1.06f, // Banda 8:  1300 - 2000 Hz (Presença)
    1.08f, // Banda 9:  2000 - 3200 Hz (Estalo)
    1.10f, // Banda 10: 3200 - 5000 Hz (Agudos)
    1.12f, // Banda 11: 5000 - 8000 Hz (Pratos)
    1.14f, // Banda 12: 8000 - 12500 Hz (Chimbal)
    1.14f, // Banda 13: 12500 - 18500 Hz (Ar / Ambiência)
)
```

### 3.4 Limpeza de Piso Contínuo e Não-Linearidade nos Graves (Sustain Floor Cleaning)
Instrumentos de grave (como baixos elétricos ou sub-graves de sintetizadores) possuem ressonâncias contínuas que mantêm o sinal sempre acima de 10% a 15%. Sem um tratamento especial, as primeiras barras nunca retornariam à base, parecendo "presas":
- **Banda 0 (Subgrave 25-65 Hz):** Subtrai-se o piso de 0.11f e aplica-se curva exponencial $x^{1.25}$:
  ```kotlin
  val cleaned = ((rawTarget - 0.11f) / 0.89f).coerceIn(0f, 1f)
  (cleaned.pow(1.25f) * 0.88f).coerceIn(0f, 1f)
  ```
  Isso faz com que entre duas batidas consecutivas de bumbo, a barra desça completamente até a base, criando a sensação visual de "pulsação e respiração" da música.
- **Banda 1 (Bumbo 65-115 Hz):** Piso de 0.08f e curva $x^{1.22}$:
  ```kotlin
  val cleaned = ((rawTarget - 0.08f) / 0.92f).coerceIn(0f, 1f)
  (cleaned.pow(1.22f) * 0.85f).coerceIn(0f, 1f)
  ```
- **Banda 2 (Punch 115-175 Hz):** Piso de 0.06f e curva $x^{1.18}$:
  ```kotlin
  val cleaned = ((rawTarget - 0.06f) / 0.94f).coerceIn(0f, 1f)
  (cleaned.pow(1.18f) * 0.86f).coerceIn(0f, 1f)
  ```
- **Bandas 3 a 13 (Médios e Agudos):** Piso mínimo de 0.04f com aplicação direta do peso de ruído rosa:
  ```kotlin
  val weight = SPECTRAL_WEIGHTS[i]
  val cleaned = ((rawTarget - 0.04f) / 0.96f).coerceIn(0f, 1f)
  (cleaned * weight * 0.84f).coerceIn(0f, 1f)
  ```

### 3.5 Física dos Picos Flutuantes (Floating Peak Indicators)
Acima de cada barra existe um pequeno marcador retangular de pico que simula medidores de estúdio profissionais:
1. **Subida Instantânea:** Se o valor atual da barra ultrapassar o pico anterior (`v >= currentPeaks[i]`), o marcador sobe instantaneamente com a barra:
   ```kotlin
   currentPeaks[i] = v
   ```
2. **Descida Assintótica Contínua:** Quando a barra cai, o marcador não cai com ela: ele desce suavemente a uma velocidade extremamente lenta (`peakFallAlpha` entre 0.0015 e 0.018):
   ```kotlin
   currentPeaks[i] += (v - currentPeaks[i]) * activePeakFallAlpha
   ```
   Isso produz o efeito visual característico onde o marcador parece "flutuar" no ar por frações de segundo antes de descer lentamente em direção à barra.

---

## 4. A Renderização Visual em Jetpack Compose (`AudioVisualizerView`)

### 4.1 Geometria das Tiras Estéreo Duplas
Em vez de desenhar uma única barra quadrada para cada frequência, cada uma das 14 faixas é subdividida em **dois canais estéreo (Left e Right)** lado a lado:
- A largura de cada tira principal é calculada dinamicamente:
  ```kotlin
  val gap = 3.5f * density
  val stripW = ((w - gap * (strips - 1) - gap * 2) / strips).coerceAtLeast(3f * density)
  val half = (stripW / 2f - 1f * density).coerceAtLeast(1.5f * density)
  ```
- **Canal Esquerdo:** Desenha na posição `x` com largura `half`.
- **Canal Direito:** Desenha na posição `rx = x + stripW / 2f + 1f * density` com largura `half`.
- **Atenuação Estéreo Suave:** O canal direito recebe uma ligeira atenuação de magnitude (`rv = v * 0.92f`) e ligeira redução de opacidade (`rightStripAlpha = (0.35f + rv * 0.65f) * 0.9f`). Essa micro-diferença simula espacialidade e profundidade estética tridimensional.

### 4.2 Cantos Arredondados e Opacidade Dinâmica
- Os cantos das barras são arredondados com raio `cr = stripW * 0.4f`.
- A opacidade de cada barra varia dinamicamente conforme sua altura: `stripAlpha = (0.35f + v * 0.65f)`. Em momentos de silêncio ou batidas fracas, as barras ficam mais sutis e etéreas; em impactos fortes, elas brilham intensamente.
- O marcador de pico é renderizado como uma pílula arredondada de altura `3.dp` e raio `1.5.dp`, posicionada exatamente `2.dp` acima do topo do pico:
  ```kotlin
  val peakTop = (baselineY - pkH - 2f * density).coerceAtLeast(0f)
  ```

### 4.3 Transições com Zero Flickering
Quando a música começa ou para, o visualizador utiliza uma animação de fade suave de 350ms através de `animateFloatAsState(targetValue = if (frame.hasAudio) 1f else 0f, tween(350))`. Se o valor for próximo de zero (`alphaAnim <= 0.001f`), o `Canvas` interrompe imediatamente qualquer desenho, economizando 100% da GPU.

---

## 5. Como Compilar o Daemon Autônomo (`visualizer_daemon.dex`)

O daemon é um arquivo Kotlin/Java independente que precisa ser compilado para o formato bytecode do Dalvik/ART (`.dex`).

### 5.1 Estrutura do Arquivo Fonte
Crie o arquivo `AudioVisualizerDaemon.kt` com o pacote `dev.viagaralauncher.visualizer` (ou altere para o pacote do seu app, mantendo a consistência no script de lançamento).

### 5.2 Compilação via Linha de Comando (com Android SDK)
Se você tiver o Android SDK instalado (ou via script Gradle):

```bash
# 1. Defina o caminho do android.jar do seu SDK (ex: API 34 ou 35)
ANDROID_JAR="$ANDROID_HOME/platforms/android-34/android.jar"

# 2. Compile o código Kotlin gerando um JAR intermediário
kotlinc AudioVisualizerDaemon.kt \
    -cp "$ANDROID_JAR" \
    -no-stdlib \
    -d daemon_classes.jar

# 3. Converta o bytecode Java/Kotlin para DEX usando o compilador d8 do Android SDK
$ANDROID_HOME/build-tools/34.0.0/d8 \
    daemon_classes.jar \
    --min-api 26 \
    --output ./dex_output/

# 4. Renomeie o classes.dex gerado para visualizer_daemon.dex
mv ./dex_output/classes.dex app/src/main/assets/visualizer_daemon.dex
```

Coloque o arquivo `visualizer_daemon.dex` resultante diretamente dentro da pasta `app/src/main/assets/` do seu projeto Android.

---

## 6. Códigos-Fonte Prontos para Implementação

Abaixo estão os 3 arquivos de código completos e auto-contidos que você pode copiar diretamente para o seu projeto:

### 6.1 `AudioVisualizerDaemon.kt`
*Arquivo que será compilado no DEX e executado pelo app_process64.*

```kotlin
// SPDX-License-Identifier: Apache-2.0
package dev.viagaralauncher.visualizer

import android.media.audiofx.Visualizer
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.charset.StandardCharsets
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Daemon autônomo executado sob UID 0 (root) através do /system/bin/app_process64.
 * Captura a saída de áudio global do sistema através da Sessão 0,
 * decompõe o sinal em 14 bandas acústicas logarítmicas de 25 Hz a 18.500 Hz,
 * aplica a integração espectral de Parseval (RMS) e transmite os dados via LocalSocket.
 */
object AudioVisualizerDaemon {

    private const val TAG = "VIZ_DAEMON"
    private const val SOCKET_NAME = "viagara_audio_viz"
    private const val CAPTURE_SIZE = 1024
    private const val NUM_BANDS = 14
    private const val FRAME_INTERVAL_MS = 16L // ~60 FPS

    @JvmStatic
    fun main(args: Array<String>) {
        var visualizer: Visualizer? = null
        var socket: LocalSocket? = null
        var outStream: OutputStream? = null
        var inStream: InputStream? = null

        try {
            socket = LocalSocket()
            var connected = false
            for (i in 0 until 50) {
                try {
                    socket.connect(LocalSocketAddress(SOCKET_NAME, LocalSocketAddress.Namespace.ABSTRACT))
                    connected = true
                    break
                } catch (_: Throwable) {
                    Thread.sleep(100)
                }
            }

            if (!connected) return

            socket.soTimeout = 500
            outStream = socket.outputStream
            inStream = socket.inputStream

            // Inicialização da Sessão Global 0 com permissão root
            visualizer = Visualizer(0)
            visualizer.captureSize = CAPTURE_SIZE
            visualizer.enabled = true

            val actualCaptureSize = visualizer.captureSize
            val rawSampleRate = visualizer.samplingRate
            val sampleRateHz = if (rawSampleRate > 0) rawSampleRate / 1000 else 48000
            val binWidth = sampleRateHz.toFloat() / actualCaptureSize.toFloat()

            // 14 Bandas Logarítmicas cobrindo todo o espectro auditivo humano:
            val bandFreqs = floatArrayOf(
                25f, 65f, 115f, 175f, 260f, 390f, 580f, 870f,
                1300f, 2000f, 3200f, 5000f, 8000f, 12500f, 18500f
            )

            val bandStartBins = IntArray(NUM_BANDS)
            val bandEndBins = IntArray(NUM_BANDS)
            var lastEndBin = 0
            for (b in 0 until NUM_BANDS) {
                val start = max(lastEndBin + 1, (bandFreqs[b] / binWidth).roundToInt())
                val end = max(start, (bandFreqs[b + 1] / binWidth).roundToInt())
                bandStartBins[b] = start
                bandEndBins[b] = min(511, end)
                lastEndBin = bandEndBins[b]
            }

            val uniformGain = 1.25f
            val emaBands = FloatArray(NUM_BANDS)
            val fftBuffer = ByteArray(CAPTURE_SIZE)
            val waveBuffer = ByteArray(CAPTURE_SIZE)
            val magnitudes = FloatArray(NUM_BANDS)

            // Pacote Little-Endian: 4 bytes mágica + 1 byte bandas + 4 bytes rms + (14 * 4 bytes)
            val packetBuffer = ByteBuffer.allocate(4 + 1 + 4 + (NUM_BANDS * 4)).apply {
                order(ByteOrder.LITTLE_ENDIAN)
            }

            var isPaused = false

            while (true) {
                // Leitura de comandos de controle do app
                while (inStream.available() > 0) {
                    val cmd = inStream.read()
                    if (cmd == -1 || cmd == 'S'.code || cmd == 'Q'.code) {
                        return // Encerramento gracioso
                    } else if (cmd == 'P'.code) {
                        if (!isPaused) {
                            isPaused = true
                            try { visualizer.enabled = false } catch (_: Throwable) {}
                        }
                    } else if (cmd == 'R'.code) {
                        if (isPaused) {
                            isPaused = false
                            try { visualizer.enabled = true } catch (_: Throwable) {}
                        }
                    }
                }

                if (isPaused) {
                    Thread.sleep(50)
                    continue
                }

                val loopStartTime = System.currentTimeMillis()

                val fftResult = visualizer.getFft(fftBuffer)
                val waveResult = visualizer.getWaveForm(waveBuffer)
                var rms = 0f

                if (fftResult == Visualizer.SUCCESS) {
                    var totalLevelSum = 0f
                    for (b in 0 until NUM_BANDS) {
                        val rawRms = computeBandRms(fftBuffer, bandStartBins[b], bandEndBins[b])
                        // Fast EMA (alpha = 0.35)
                        emaBands[b] = emaBands[b] * 0.65f + rawRms * 0.35f
                        val level = min(1.0f, emaBands[b] * uniformGain)
                        magnitudes[b] = level
                        totalLevelSum += level
                    }

                    rms = if (waveResult == Visualizer.SUCCESS) {
                        computeWaveRms(waveBuffer)
                    } else {
                        (totalLevelSum / NUM_BANDS).coerceIn(0f, 1f)
                    }
                } else {
                    magnitudes.fill(0f)
                    emaBands.fill(0f)
                    if (waveResult == Visualizer.SUCCESS) {
                        rms = computeWaveRms(waveBuffer)
                    }
                }

                // Serialização do pacote VIZ1
                packetBuffer.clear()
                packetBuffer.put('V'.code.toByte())
                packetBuffer.put('I'.code.toByte())
                packetBuffer.put('Z'.code.toByte())
                packetBuffer.put('1'.code.toByte())
                packetBuffer.put(NUM_BANDS.toByte())
                packetBuffer.putFloat(rms)
                for (b in 0 until NUM_BANDS) {
                    packetBuffer.putFloat(magnitudes[b])
                }

                outStream.write(packetBuffer.array())
                outStream.flush()

                val elapsed = System.currentTimeMillis() - loopStartTime
                val sleepTime = FRAME_INTERVAL_MS - elapsed
                if (sleepTime > 0) {
                    Thread.sleep(sleepTime)
                }
            }
        } catch (_: Throwable) {
        } finally {
            try { visualizer?.enabled = false } catch (_: Throwable) {}
            try { visualizer?.release() } catch (_: Throwable) {}
            try { inStream?.close() } catch (_: Throwable) {}
            try { outStream?.close() } catch (_: Throwable) {}
            try { socket?.close() } catch (_: Throwable) {}
        }
    }

    private fun computeBandRms(fft: ByteArray, startBin: Int, endBin: Int): Float {
        var sumPower = 0.0
        val maxK = min(511, endBin)
        for (k in startBin..maxK) {
            val re: Float
            val im: Float
            if (k == 0) {
                re = fft[0].toFloat() / 128.0f
                im = 0.0f
            } else {
                re = fft[2 * k].toFloat() / 128.0f
                im = if (2 * k + 1 < fft.size) fft[2 * k + 1].toFloat() / 128.0f else 0.0f
            }
            sumPower += (re * re + im * im).toDouble()
        }
        return sqrt(sumPower / 2.0).toFloat()
    }

    private fun computeWaveRms(wave: ByteArray): Float {
        var sumSquares = 0.0
        for (b in wave) {
            val v = (b.toInt() and 0xFF) - 128
            sumSquares += (v * v).toDouble()
        }
        val mean = sumSquares / wave.size
        return min(1.0, sqrt(mean) / 128.0).toFloat()
    }
}
```

---

### 6.2 `AudioVisualizerManager.kt`
*Gerenciador executado dentro do aplicativo Android.*

```kotlin
// SPDX-License-Identifier: Apache-2.0
package dev.viagaralauncher.visualizer

import android.content.Context
import android.net.LocalServerSocket
import android.net.LocalSocket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.pow

data class VisualizerFrame(
    val bands: FloatArray = FloatArray(14),
    val peaks: FloatArray = FloatArray(14),
    val rms: Float = 0f,
    val hasAudio: Boolean = false,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as VisualizerFrame
        if (!bands.contentEquals(other.bands)) return false
        if (!peaks.contentEquals(other.peaks)) return false
        if (rms != other.rms) return false
        if (hasAudio != other.hasAudio) return false
        return true
    }

    override fun hashCode(): Int {
        var result = bands.contentHashCode()
        result = 31 * result + peaks.contentHashCode()
        result = 31 * result + rms.hashCode()
        result = 31 * result + hasAudio.hashCode()
        return result
    }
}

private enum class VisualizerState {
    STOPPED, STARTING, RUNNING, PAUSED, STOPPING
}

object AudioVisualizerManager {

    private const val SOCKET_NAME = "viagara_audio_viz"
    private const val NUM_BANDS = 14

    private val _frameFlow = MutableStateFlow(VisualizerFrame())
    val frameFlow: StateFlow<VisualizerFrame> = _frameFlow.asStateFlow()

    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private val lifecycleMutex = Mutex()
    private var currentState = VisualizerState.STOPPED
    private var currentGeneration = 0L

    private var supervisorJob: Job? = null
    private var readerJob: Job? = null

    private var serverSocket: LocalServerSocket? = null
    private var clientSocket: LocalSocket? = null
    private var clientOut: OutputStream? = null

    private var daemonProcess: Process? = null
    private var daemonPid: Int = -1

    private val currentLevels = FloatArray(NUM_BANDS)
    private val currentPeaks = FloatArray(NUM_BANDS)

    // Curva de Equalização de Ruído Rosa (1/f)
    private val SPECTRAL_WEIGHTS = floatArrayOf(
        0.88f, 0.86f, 0.88f, 0.91f, 0.94f, 0.97f, 1.00f,
        1.03f, 1.06f, 1.08f, 1.10f, 1.12f, 1.14f, 1.14f
    )

    @Volatile
    private var currentSpeed: Float = 0.50f

    @Volatile
    private var isAppForeground = true
    @Volatile
    private var lastEnabledSetting = false
    @Volatile
    private var lastHasSession = false
    @Volatile
    private var lastIsPlaying = false
    @Volatile
    private var appContext: Context? = null
    private var stopJob: Job? = null

    fun setSpeed(speed: Float) {
        currentSpeed = speed.coerceIn(0.10f, 1.00f)
    }

    fun updatePlaybackState(context: Context, enabled: Boolean, isPlaying: Boolean, hasSession: Boolean) {
        appContext = context.applicationContext
        lastEnabledSetting = enabled
        lastHasSession = hasSession
        lastIsPlaying = isPlaying
        scope.launch { evaluateStateAndAct() }
    }

    private suspend fun evaluateStateAndAct() {
        val shouldBeRunning = isAppForeground && lastEnabledSetting && lastHasSession && lastIsPlaying
        val isPausedSession = isAppForeground && lastEnabledSetting && lastHasSession && !lastIsPlaying

        if (shouldBeRunning) {
            stopJob?.cancel()
            stopJob = null
            if (currentState == VisualizerState.PAUSED) {
                resumeInternal()
            } else if (currentState == VisualizerState.STOPPED) {
                appContext?.let { startInternal(it) }
            }
        } else if (isPausedSession) {
            // Em pausa: suspende o envio imediatamente via 'P' e espera 30 segundos antes de matar o processo
            if (currentState == VisualizerState.RUNNING) {
                pauseInternal()
            }
            if (currentState != VisualizerState.STOPPED && stopJob == null) {
                stopJob = scope.launch {
                    delay(30_000L)
                    stopInternal()
                    stopJob = null
                }
            }
        } else {
            stopJob?.cancel()
            stopJob = null
            if (!isAppForeground && currentState == VisualizerState.RUNNING) {
                pauseInternal()
            } else if (!lastEnabledSetting || !lastHasSession) {
                stopInternal()
            }
        }
    }

    private suspend fun pauseInternal() {
        lifecycleMutex.withLock {
            if (currentState == VisualizerState.RUNNING) {
                currentState = VisualizerState.PAUSED
                sendControlCommand('P')
                withContext(Dispatchers.Default) {
                    currentLevels.fill(0f)
                    currentPeaks.fill(0f)
                    _frameFlow.value = VisualizerFrame()
                }
            }
        }
    }

    private suspend fun resumeInternal() {
        lifecycleMutex.withLock {
            if (currentState == VisualizerState.PAUSED) {
                currentState = VisualizerState.RUNNING
                sendControlCommand('R')
            }
        }
    }

    private suspend fun startInternal(context: Context) {
        lifecycleMutex.withLock {
            if (currentState != VisualizerState.STOPPED) return@withLock
            currentState = VisualizerState.STARTING
            val gen = ++currentGeneration

            supervisorJob = scope.launch {
                runSupervisor(context.applicationContext, gen)
            }
        }
    }

    private suspend fun stopInternal() {
        lifecycleMutex.withLock {
            if (currentState == VisualizerState.STOPPED || currentState == VisualizerState.STOPPING) return@withLock
            currentState = VisualizerState.STOPPING
            val gen = currentGeneration

            supervisorJob?.cancel()
            supervisorJob = null

            cleanupDaemonLocked(gen)

            withContext(Dispatchers.Default) {
                currentLevels.fill(0f)
                currentPeaks.fill(0f)
                _frameFlow.value = VisualizerFrame()
            }

            currentState = VisualizerState.STOPPED
        }
    }

    fun onResume(context: Context? = null) {
        isAppForeground = true
        if (context != null) appContext = context.applicationContext
        scope.launch { evaluateStateAndAct() }
    }

    fun onPause() {
        isAppForeground = false
        scope.launch { evaluateStateAndAct() }
    }

    fun killAnyOrphanDaemon() {
        scope.launch(Dispatchers.IO) {
            try {
                Runtime.getRuntime().exec(arrayOf("su", "-c", "pkill -f dev.viagaralauncher.visualizer.AudioVisualizerDaemon || true")).waitFor()
            } catch (_: Throwable) {}
        }
    }

    private fun sendControlCommand(cmd: Char) {
        try {
            clientOut?.let {
                it.write(cmd.code)
                it.flush()
            }
        } catch (_: Throwable) {}
    }

    private suspend fun runSupervisor(context: Context, generation: Long) = withContext(Dispatchers.IO) {
        var localDaemonPid = -1
        var localProcess: Process? = null
        var localClient: LocalSocket? = null
        try {
            try {
                serverSocket = LocalServerSocket(SOCKET_NAME)
            } catch (_: Throwable) {
                serverSocket?.close()
                serverSocket = LocalServerSocket(SOCKET_NAME)
            }

            lifecycleMutex.withLock {
                if (generation != currentGeneration) return@withContext
            }

            val dexPath = extractOrPrepareDex(context) ?: run {
                lifecycleMutex.withLock {
                    if (generation == currentGeneration) {
                        cleanupDaemonLocked(generation)
                        currentState = VisualizerState.STOPPED
                    }
                }
                return@withContext
            }

            // Executa o daemon como Root via app_process64
            val launchScript = """
                export CLASSPATH="$dexPath"
                /system/bin/app_process64 /data/local/tmp dev.viagaralauncher.visualizer.AudioVisualizerDaemon &
                echo "PID:${'$'}!"
                wait
            """.trimIndent()

            val process = ProcessBuilder("su", "-c", launchScript).start()
            localProcess = process

            val reader = process.inputStream.bufferedReader()
            var extractedPid = -1
            val timeoutMillis = System.currentTimeMillis() + 2000L
            while (System.currentTimeMillis() < timeoutMillis) {
                if (reader.ready()) {
                    val line = reader.readLine() ?: break
                    if (line.startsWith("PID:")) {
                        extractedPid = line.substring(4).trim().toIntOrNull() ?: -1
                        break
                    }
                } else {
                    delay(40)
                }
            }
            localDaemonPid = extractedPid

            lifecycleMutex.withLock {
                if (generation != currentGeneration || currentState != VisualizerState.STARTING) {
                    terminateProcessAndPid(process, localDaemonPid)
                    return@withContext
                }
                daemonProcess = process
                daemonPid = extractedPid
            }

            val client = serverSocket?.accept() ?: run {
                lifecycleMutex.withLock {
                    if (generation == currentGeneration) {
                        cleanupDaemonLocked(generation)
                        currentState = VisualizerState.STOPPED
                    }
                }
                return@withContext
            }
            localClient = client

            lifecycleMutex.withLock {
                if (generation != currentGeneration || currentState != VisualizerState.STARTING) {
                    try { client.close() } catch (_: Throwable) {}
                    if (generation == currentGeneration) {
                        cleanupDaemonLocked(generation)
                    } else {
                        terminateProcessAndPid(process, localDaemonPid)
                    }
                    return@withLock
                }
                clientSocket = client
                clientOut = client.outputStream
                currentState = VisualizerState.RUNNING
            }

            startReaderLoop(client.inputStream)
        } catch (_: Throwable) {
            lifecycleMutex.withLock {
                if (generation == currentGeneration) {
                    cleanupDaemonLocked(generation)
                    currentState = VisualizerState.STOPPED
                } else {
                    try { localClient?.close() } catch (_: Throwable) {}
                    if (localDaemonPid > 0 && localDaemonPid != daemonPid) {
                        terminateProcessAndPid(localProcess, localDaemonPid)
                    }
                }
            }
        }
    }

    private fun startReaderLoop(inputStream: InputStream) {
        readerJob?.cancel()
        readerJob = scope.launch(Dispatchers.Default) {
            val packetSize = 4 + 1 + 4 + (NUM_BANDS * 4) // 65 bytes
            val rawBytes = ByteArray(packetSize)
            val byteBuffer = ByteBuffer.wrap(rawBytes).order(ByteOrder.LITTLE_ENDIAN)
            val incomingBands = FloatArray(NUM_BANDS)

            while (isActive && (currentState == VisualizerState.RUNNING || currentState == VisualizerState.PAUSED)) {
                if (currentState == VisualizerState.PAUSED) {
                    delay(100)
                    continue
                }

                var totalRead = 0
                while (totalRead < packetSize && isActive) {
                    val read = inputStream.read(rawBytes, totalRead, packetSize - totalRead)
                    if (read == -1) break
                    totalRead += read
                }
                if (totalRead < packetSize) break

                if (rawBytes[0] != 'V'.code.toByte() ||
                    rawBytes[1] != 'I'.code.toByte() ||
                    rawBytes[2] != 'Z'.code.toByte() ||
                    rawBytes[3] != '1'.code.toByte()
                ) continue

                byteBuffer.position(5)
                val rms = byteBuffer.float
                for (b in 0 until NUM_BANDS) {
                    incomingBands[b] = byteBuffer.float
                }

                var activeSignal = false
                val bandsOut = FloatArray(NUM_BANDS)
                val peaksOut = FloatArray(NUM_BANDS)

                // Cálculo da balística
                val normSpeed = ((currentSpeed - 0.10f) / 0.90f).coerceIn(0f, 1f)
                val speedCurve = normSpeed.pow(1.4f)

                val baseRiseAlpha = (0.045f + 0.455f * speedCurve).coerceIn(0.035f, 0.60f)
                val baseFallAlpha = (0.010f + 0.150f * speedCurve).coerceIn(0.008f, 0.20f)
                val peakFallAlpha = (0.0018f + 0.012f * speedCurve).coerceIn(0.0015f, 0.018f)

                for (i in 0 until NUM_BANDS) {
                    val rawTarget = incomingBands[i].coerceIn(0f, 1f)
                    if (rawTarget > 0.01f) activeSignal = true

                    val target = when (i) {
                        0 -> {
                            val cleaned = ((rawTarget - 0.11f) / 0.89f).coerceIn(0f, 1f)
                            (cleaned.pow(1.25f) * 0.88f).coerceIn(0f, 1f)
                        }
                        1 -> {
                            val cleaned = ((rawTarget - 0.08f) / 0.92f).coerceIn(0f, 1f)
                            (cleaned.pow(1.22f) * 0.85f).coerceIn(0f, 1f)
                        }
                        2 -> {
                            val cleaned = ((rawTarget - 0.06f) / 0.94f).coerceIn(0f, 1f)
                            (cleaned.pow(1.18f) * 0.86f).coerceIn(0f, 1f)
                        }
                        else -> {
                            val weight = SPECTRAL_WEIGHTS[i]
                            val cleaned = ((rawTarget - 0.04f) / 0.96f).coerceIn(0f, 1f)
                            (cleaned * weight * 0.84f).coerceIn(0f, 1f)
                        }
                    }

                    // 1. Balística assimétrica de subida ágil e descida amortecida
                    val current = currentLevels[i]
                    val alpha = if (target >= current) baseRiseAlpha else baseFallAlpha
                    currentLevels[i] = current + (target - current) * alpha
                    if (currentLevels[i] < 0.002f) currentLevels[i] = 0f
                    bandsOut[i] = currentLevels[i]

                    // 2. Física dos picos flutuantes
                    val v = currentLevels[i]
                    if (v >= currentPeaks[i]) {
                        currentPeaks[i] = v
                    } else {
                        currentPeaks[i] += (v - currentPeaks[i]) * peakFallAlpha
                    }
                    if (currentPeaks[i] < 0.002f) currentPeaks[i] = 0f
                    peaksOut[i] = currentPeaks[i]
                }

                _frameFlow.value = VisualizerFrame(
                    bands = bandsOut,
                    peaks = peaksOut,
                    rms = rms,
                    hasAudio = activeSignal || rms > 0.015f,
                )
            }
        }
    }

    private suspend fun cleanupDaemonLocked(expectedGeneration: Long) = withContext(Dispatchers.IO) {
        if (expectedGeneration != currentGeneration) return@withContext

        readerJob?.cancel()
        readerJob = null

        try {
            clientOut?.write('S'.code)
            clientOut?.flush()
        } catch (_: Throwable) {}

        try { clientSocket?.close() } catch (_: Throwable) {}
        clientSocket = null
        clientOut = null

        try { serverSocket?.close() } catch (_: Throwable) {}
        serverSocket = null

        val targetPid = daemonPid
        daemonPid = -1
        val proc = daemonProcess
        daemonProcess = null

        terminateProcessAndPid(proc, targetPid)
    }

    private fun terminateProcessAndPid(proc: Process?, pid: Int) {
        if (proc != null) {
            val exited = runCatching {
                var count = 0
                while (count < 6) {
                    if (!proc.isAlive) return@runCatching true
                    Thread.sleep(50)
                    count++
                }
                false
            }.getOrDefault(false)

            if (!exited) {
                proc.destroy()
                try { Thread.sleep(100) } catch (_: Throwable) {}
                if (proc.isAlive) proc.destroyForcibly()
            }
        }

        if (pid > 0) {
            try {
                Runtime.getRuntime().exec(arrayOf("su", "-c", "kill -TERM $pid || true")).waitFor()
                Thread.sleep(80)
                Runtime.getRuntime().exec(arrayOf("su", "-c", "kill -0 $pid && kill -KILL $pid || true")).waitFor()
            } catch (_: Throwable) {}
        }

        try {
            Runtime.getRuntime().exec(arrayOf("su", "-c", "pkill -f dev.viagaralauncher.visualizer.AudioVisualizerDaemon || true")).waitFor()
        } catch (_: Throwable) {}
    }

    private fun extractOrPrepareDex(context: Context): String? {
        val targetFile = File(context.filesDir, "visualizer_daemon.dex")
        try {
            context.assets.open("visualizer_daemon.dex").use { input ->
                targetFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            targetFile.setReadable(true, false)
            return targetFile.absolutePath
        } catch (_: Throwable) {
            val fallback = File("/data/local/tmp/visualizer_daemon.dex")
            if (fallback.exists()) return fallback.absolutePath
        }
        return null
    }
}
```

---

### 6.3 `AudioVisualizerView.kt`
*Componente visual em Jetpack Compose.*

```kotlin
// SPDX-License-Identifier: Apache-2.0
package dev.viagaralauncher.visualizer

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Visualizador de espectro em Jetpack Compose com renderização de alta fidelidade:
 * - 14 tiras duplas (Canais Esquerdo e Direito) com atenuação estéreo no canal direito
 * - Marcadores de pico flutuantes independentes (Instant rise + Asymptotic descent)
 * - Transição suave de fade-in e fade-out (350ms)
 * - Compatível com cores dinâmicas do Material 3
 */
@Composable
fun AudioVisualizerView(
    modifier: Modifier = Modifier,
    height: Dp = Dp.Unspecified,
    barColor: Color = MaterialTheme.colorScheme.primary,
    peakColor: Color = MaterialTheme.colorScheme.tertiary,
    trackColor: Color = barColor.copy(alpha = 0.05f),
) {
    val frame by AudioVisualizerManager.frameFlow.collectAsState()

    val alphaAnim by animateFloatAsState(
        targetValue = if (frame.hasAudio) 1f else 0f,
        animationSpec = tween(durationMillis = 350),
        label = "visualizerFade",
    )

    if (alphaAnim <= 0.001f) return

    val boxModifier = if (height != Dp.Unspecified) {
        modifier.fillMaxWidth().height(height)
    } else {
        modifier
    }

    Box(modifier = boxModifier) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = alphaAnim }
        ) {
            val bands = frame.bands
            val peaks = frame.peaks
            val strips = bands.size
            if (strips == 0) return@Canvas

            val w = size.width
            val h = size.height
            if (w <= 0f || h <= 0f) return@Canvas

            val gap = 3.5f * density
            val stripW = ((w - gap * (strips - 1) - gap * 2) / strips).coerceAtLeast(3f * density)
            val cr = stripW * 0.4f
            val half = (stripW / 2f - 1f * density).coerceAtLeast(1.5f * density)
            val peakBlockHeight = 3f * density
            val peakCr = 1.5f * density

            val totalWidth = strips * stripW + (strips - 1) * gap
            val startX = (w - totalWidth) / 2f

            val bottomMargin = 4f * density
            val baselineY = h - bottomMargin
            val maxBarHeight = (baselineY - 4f * density).coerceAtLeast(stripW)

            for (i in 0 until strips) {
                val v = bands[i]
                val pk = peaks[i]

                val x = startX + i * (stripW + gap)
                val rx = x + stripW / 2f + 1f * density

                // 1. Guias de fundo transparentes
                if (trackColor.alpha > 0f) {
                    val trackAlpha = trackColor.alpha * 0.4f
                    drawRoundRect(
                        color = trackColor.copy(alpha = trackAlpha),
                        topLeft = Offset(x, baselineY - maxBarHeight),
                        size = Size(half, maxBarHeight),
                        cornerRadius = CornerRadius(cr, cr),
                    )
                    drawRoundRect(
                        color = trackColor.copy(alpha = trackAlpha * 0.9f),
                        topLeft = Offset(rx, baselineY - maxBarHeight),
                        size = Size(half, maxBarHeight),
                        cornerRadius = CornerRadius(cr, cr),
                    )
                }

                // 2. Barra principal do Canal Esquerdo
                val lh = (v * maxBarHeight).coerceAtLeast(0f)
                if (lh > 1f) {
                    val stripAlpha = (0.35f + v * 0.65f).coerceIn(0.2f, 0.95f)
                    drawRoundRect(
                        color = barColor.copy(alpha = barColor.alpha * stripAlpha),
                        topLeft = Offset(x, baselineY - lh),
                        size = Size(half, lh),
                        cornerRadius = CornerRadius(cr, cr),
                    )
                }

                // 3. Marcador de pico do Canal Esquerdo
                val pkH = (pk * maxBarHeight).coerceAtLeast(0f)
                if (pk > 0.03f && pkH > 2f) {
                    val peakTop = (baselineY - pkH - 2f * density).coerceAtLeast(0f)
                    drawRoundRect(
                        color = peakColor.copy(alpha = 0.88f),
                        topLeft = Offset(x, peakTop),
                        size = Size(half, peakBlockHeight),
                        cornerRadius = CornerRadius(peakCr, peakCr),
                    )
                }

                // 4. Barra principal do Canal Direito (atenuação 0.92f)
                val rv = v * 0.92f
                val rh = (rv * maxBarHeight).coerceAtLeast(0f)
                if (rh > 1f) {
                    val rightStripAlpha = (0.35f + rv * 0.65f) * 0.9f
                    drawRoundRect(
                        color = barColor.copy(alpha = (barColor.alpha * rightStripAlpha).coerceIn(0.18f, 0.85f)),
                        topLeft = Offset(rx, baselineY - rh),
                        size = Size(half, rh),
                        cornerRadius = CornerRadius(cr, cr),
                    )
                }

                // 5. Marcador de pico do Canal Direito
                val rpk = (pk * 0.92f).coerceIn(0f, 1f)
                val rpkH = (rpk * maxBarHeight).coerceAtLeast(0f)
                if (rpk > 0.03f && rpkH > 2f) {
                    val rightPeakTop = (baselineY - rpkH - 2f * density).coerceAtLeast(0f)
                    drawRoundRect(
                        color = peakColor.copy(alpha = 0.78f),
                        topLeft = Offset(rx, rightPeakTop),
                        size = Size(half, peakBlockHeight),
                        cornerRadius = CornerRadius(peakCr, peakCr),
                    )
                }
            }
        }
    }
}
```

---

## 7. Integração e Ciclo de Vida no seu App

### 7.1 Declaração no `AndroidManifest.xml`
O app hospedeiro precisa das seguintes permissões básicas:
```xml
<uses-permission android:name="android.permission.RECORD_AUDIO" />
<uses-permission android:name="android.permission.MODIFY_AUDIO_SETTINGS" />
```
*(Lembre-se: O comando `su` utilizado pelo `AudioVisualizerManager` executará o script com permissões de Superusuário para acessar a Sessão 0 nativa).*

### 7.2 Vincular ao Ciclo de Vida da Activity
Na sua `MainActivity` (ou Activity principal):
```kotlin
override fun onResume() {
    super.onResume()
    AudioVisualizerManager.onResume(this)
}

override fun onPause() {
    super.onPause()
    AudioVisualizerManager.onPause()
}
```

### 7.3 Atualizar o Estado de Reprodução (Ex: via `MediaSessionManager`)
Sempre que detectar início ou fim de música:
```kotlin
AudioVisualizerManager.updatePlaybackState(
    context = context,
    enabled = isVisualizerEnabledByUser,
    isPlaying = isMediaPlaying,
    hasSession = activeMediaSession != null
)
```

### 7.4 Inserir no seu Layout Jetpack Compose
No seu Composable de tela ou player de mídia:
```kotlin
Box(modifier = Modifier.fillMaxWidth().height(160.dp)) {
    // Visualizador posicionado como camada de fundo
    AudioVisualizerView(
        modifier = Modifier.fillMaxSize(),
        barColor = MaterialTheme.colorScheme.primary,
        peakColor = MaterialTheme.colorScheme.tertiary,
    )

    // Conteúdo frontal (capa do álbum, botões, título da faixa, etc.)
    PlayerControls(...)
}
```

---

## 8. Resumo das Fórmulas Matemáticas de Referência Rápida

| Conceito | Equação / Expressão | Significado |
|---|---|---|
| **Resolução em Frequência** | $\Delta f = \frac{f_s}{N} = \frac{48000}{1024} = 46.875\text{ Hz}$ | Largura de cada bin harmônico |
| **Energia Harmônica (Parseval)** | $P(k) = \left(\frac{Re}{128}\right)^2 + \left(\frac{Im}{128}\right)^2$ | Potência normalizada do bin $k$ |
| **RMS da Banda Espectral** | $RMS = \sqrt{\frac{\sum P(k)}{2}}$ | Amplitude eficaz da faixa de frequências |
| **Pre-EMA do Daemon** | $E_t = 0.65 \cdot E_{t-1} + 0.35 \cdot RMS$ | Absorção de transientes de alta velocidade |
| **Curva de Velocidade** | $S_{curv} = \left(\frac{S - 0.10}{0.90}\right)^{1.4}$ | Mapeamento ergonômico do slider de velocidade |
| **Balística de Subida** | $\alpha_{up} = 0.045 + 0.455 \cdot S_{curv}$ | Resposta imediata nos ataques ($0.035 \dots 0.60$) |
| **Balística de Descida** | $\alpha_{down} = 0.010 + 0.150 \cdot S_{curv}$ | Descida amortecida e fluida ($0.008 \dots 0.20$) |
| **Descida do Pico Flutuante** | $\alpha_{peak} = 0.0018 + 0.012 \cdot S_{curv}$ | Descida assintótica ultra-lenta do marcador |
| **Limpeza de Piso de Subgrave** | $y = \left(\frac{x - 0.11}{0.89}\right)^{1.25} \cdot 0.88$ | Queda completa a zero nos intervalos de bumbo |
| **Atenuação Estéreo Direito** | $v_R = v_L \cdot 0.92$ | Sensação de profundidade tridimensional |
