# Audio Visualizer 🎵⚡

Aplicativo Android moderno em Kotlin e Jetpack Compose para visualização de áudio real do sistema (Session 0) com física acústica, balística assimétrica de alta precisão, marcadores de pico flutuantes e suporte completo a widgets (Tela Inicial e Overlay Flutuante).

Baseado no guia técnico em `GUIA_COMPLETO_VISUALIZADOR_DE_AUDIO.md`.

---

## 🚀 Funcionalidades Principais

- 🎛️ **Captura Global de Áudio (Session 0):** Daemon privilegiado executado sob UID 0 (Root) via `/system/bin/app_process64`, capturando o áudio de qualquer reprodutor (Spotify, YouTube, Deezer, navegadores, etc.).
- 📊 **14 Bandas Logarítmicas Acústicas:** Mapeamento de 25 Hz a 18.500 Hz com cálculo de energia RMS pelo Teorema de Parseval.
- ⚡ **Balística Assimétrica:** Subida instantânea nos ataques percussivos (bumbo e transientes) e amortecimento suave na descida, simulando medidores VU analógicos.
- 📍 **Marcadores de Pico Flutuantes (*Floating Peak Indicators*):** Indicadores de topo independentes com descida assintótica.
- 🎨 **Personalização Completa de Cores:** Presets modernos (Neon Cyan, Matrix Green, Electric Violet, Sunset Blaze, etc.) e ajuste fino contínuo de matriz de cores.
- 🏎️ **Controle de Velocidade Dinâmica:** Slider de resposta de 10% a 100% com curva ergonômica não-linear.
- 📱 **Widgets:**
  - **Widget de Tela Inicial (Home Screen):** Exibição no launcher com as cores configuradas.
  - **Widget Flutuante (Overlay):** Visualizador de 60 FPS sobreposto na tela em qualquer aplicativo.
- 🛡️ **Verificação de Root em Tempo Real:** Detecção de Magisk, KernelSU e APatch com status claro de concessão de UID 0.
- 📦 **Compactação e Otimização com R8:** Minificação completa de bytecode e recursos para tamanho mínimo e máxima performance.

---

## 🛠️ Arquitetura do Sistema

```
Dispositivo Android (Spotify / YouTube / Música)
       │
       ▼
Android AudioFlinger (Session 0)
       │
       ▼
Daemon Root (UID 0) [AudioVisualizerDaemon]
  ├── FFT 1024 bins
  ├── 14 Bandas Logarítmicas (Parseval RMS)
  └── Fast EMA (alpha=0.35)
       │
       ▼
LocalSocket Abstrato ("viagara_audio_viz")
       │
       ▼
Aplicativo Android [AudioVisualizerManager]
  ├── Desempacotamento Little-Endian (65 bytes)
  ├── Compensação de Ruído Rosa (1/f)
  ├── Limpeza de Piso de Subgrave
  ├── Balística Assimétrica e Picos Flutuantes
  └── Renderização em Jetpack Compose Canvas (60 FPS)
```

---

## 📋 Requisitos

- Android 8.0+ (API 26+)
- Acesso Root (Magisk, KernelSU ou APatch) para captura da Sessão 0 global do Android.
