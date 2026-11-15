// SPDX-License-Identifier: Apache-2.0
package dev.viagaralauncher.visualizer;

import android.media.audiofx.Visualizer;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.Looper;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Daemon autônomo executado sob UID 0 (root) através do app_process.
 * Captura a saída de áudio global do sistema através da Sessão 0,
 * decompõe o sinal em 14 bandas acústicas logarítmicas de 25 Hz a 18.500 Hz,
 * aplica a integração espectral de Parseval (RMS) e transmite os dados
 * via stdout (pipe direto do processo) e LocalSocket.
 */
public final class AudioVisualizerDaemon {

    private static final String SOCKET_NAME = "viagara_audio_viz";
    private static final int CAPTURE_SIZE = 1024;
    private static final int NUM_BANDS = 14;
    private static final long FRAME_INTERVAL_MS = 16L; // ~60 FPS

    private AudioVisualizerDaemon() {}

    public static void main(String[] args) {
        System.err.println("VIZ_DAEMON: Iniciando daemon (UID=" + android.os.Process.myUid() + ", PID=" + android.os.Process.myPid() + ")");

        // 1. Inicializa Looper para dar suporte a chamadas de Handler e AudioEffect
        try {
            if (Looper.myLooper() == null) {
                Looper.prepareMainLooper();
            }
        } catch (Throwable t) {
            try {
                Looper.prepare();
            } catch (Throwable ignored) {}
        }

        // 2. Conexão opcional com LocalSocket (se o servidor LocalServerSocket estiver disponível)
        LocalSocket socket = null;
        OutputStream socketOut = null;
        InputStream socketIn = null;
        try {
            socket = new LocalSocket();
            socket.connect(new LocalSocketAddress(SOCKET_NAME, LocalSocketAddress.Namespace.ABSTRACT));
            socket.setSoTimeout(500);
            socketOut = socket.getOutputStream();
            socketIn = socket.getInputStream();
            System.err.println("VIZ_DAEMON: Conectado ao LocalSocket abstrato: " + SOCKET_NAME);
        } catch (Throwable t) {
            System.err.println("VIZ_DAEMON: LocalSocket indisponivel (streaming principal via stdout): " + t.getMessage());
            try { if (socket != null) socket.close(); } catch (Throwable ignored) {}
            socket = null;
        }

        // 3. Inicialização da Sessão Global 0 com permissão root
        Visualizer visualizer = null;
        for (int attempt = 1; attempt <= 10; attempt++) {
            try {
                visualizer = new Visualizer(0);
                visualizer.setCaptureSize(CAPTURE_SIZE);
                visualizer.setEnabled(true);
                System.err.println("VIZ_DAEMON: Visualizer(0) inicializado com sucesso! captureSize=" 
                        + visualizer.getCaptureSize() + ", sampleRate=" + visualizer.getSamplingRate());
                break;
            } catch (Throwable t) {
                System.err.println("VIZ_DAEMON: Tentativa " + attempt + " de criar Visualizer(0) falhou: " + t.getMessage());
                try {
                    Thread.sleep(300);
                } catch (Throwable ignored) {}
            }
        }

        if (visualizer == null) {
            System.err.println("VIZ_DAEMON: FATAL - Nao foi possivel inicializar Visualizer na Sessao 0 apos 10 tentativas.");
            return;
        }

        int actualCaptureSize = visualizer.getCaptureSize();
        int rawSampleRate = visualizer.getSamplingRate();
        int sampleRateHz = (rawSampleRate > 0) ? (rawSampleRate / 1000) : 48000;
        float binWidth = (float) sampleRateHz / (float) actualCaptureSize;

        // 14 Bandas Logarítmicas (25 Hz – 18.5 kHz)
        float[] bandFreqs = new float[] {
            25f, 65f, 115f, 175f, 260f, 390f, 580f, 870f,
            1300f, 2000f, 3200f, 5000f, 8000f, 12500f, 18500f
        };

        int[] bandStartBins = new int[NUM_BANDS];
        int[] bandEndBins = new int[NUM_BANDS];
        int lastEndBin = 0;
        for (int b = 0; b < NUM_BANDS; b++) {
            int start = Math.max(lastEndBin + 1, Math.round(bandFreqs[b] / binWidth));
            int end = Math.max(start, Math.round(bandFreqs[b + 1] / binWidth));
            bandStartBins[b] = start;
            bandEndBins[b] = Math.min(511, end);
            lastEndBin = bandEndBins[b];
        }

        float uniformGain = 1.40f;
        byte[] fftBuffer = new byte[CAPTURE_SIZE];
        float[] magnitudes = new float[NUM_BANDS];

        ByteBuffer packetBuffer = ByteBuffer.allocate(4 + 1 + 4 + (NUM_BANDS * 4));
        packetBuffer.order(ByteOrder.LITTLE_ENDIAN);

        boolean isPaused = false;

        try {
            while (true) {
                // Leitura de comandos de controle via LocalSocket (se conectado)
                if (socketIn != null && socketIn.available() > 0) {
                    int cmd = socketIn.read();
                    if (cmd == -1 || cmd == 'S' || cmd == 'Q') {
                        System.err.println("VIZ_DAEMON: Comando de parada recebido via socket.");
                        return;
                    } else if (cmd == 'P') {
                        isPaused = true;
                        try { visualizer.setEnabled(false); } catch (Throwable ignored) {}
                    } else if (cmd == 'R') {
                        isPaused = false;
                        try { visualizer.setEnabled(true); } catch (Throwable ignored) {}
                    }
                }

                // Leitura de comandos de controle via stdin (pipe direto do processo pai)
                if (System.in.available() > 0) {
                    int cmd = System.in.read();
                    if (cmd == -1 || cmd == 'S' || cmd == 'Q') {
                        System.err.println("VIZ_DAEMON: Comando de parada recebido via stdin.");
                        return;
                    } else if (cmd == 'P') {
                        isPaused = true;
                        try { visualizer.setEnabled(false); } catch (Throwable ignored) {}
                    } else if (cmd == 'R') {
                        isPaused = false;
                        try { visualizer.setEnabled(true); } catch (Throwable ignored) {}
                    }
                }

                if (isPaused) {
                    try {
                        int cmd = System.in.read();
                        if (cmd == -1 || cmd == 'S' || cmd == 'Q') {
                            System.err.println("VIZ_DAEMON: Comando de parada recebido via stdin durante pausa.");
                            return;
                        } else if (cmd == 'R') {
                            isPaused = false;
                            try { visualizer.setEnabled(true); } catch (Throwable ignored) {}
                        }
                    } catch (Throwable t) {
                        System.err.println("VIZ_DAEMON: Erro na leitura stdin durante pausa: " + t.getMessage());
                        return;
                    }
                    continue;
                }

                long loopStartTime = System.currentTimeMillis();

                int fftResult = visualizer.getFft(fftBuffer);
                float rms = 0f;

                if (fftResult == Visualizer.SUCCESS) {
                    float totalLevelSum = 0f;
                    for (int b = 0; b < NUM_BANDS; b++) {
                        float rawRms = computeBandRms(fftBuffer, bandStartBins[b], bandEndBins[b]);
                        float level = Math.min(1.0f, rawRms * uniformGain);
                        magnitudes[b] = level;
                        totalLevelSum += level;
                    }
                    rms = Math.max(0f, Math.min(1f, totalLevelSum / NUM_BANDS));
                } else {
                    for (int b = 0; b < NUM_BANDS; b++) {
                        magnitudes[b] = 0f;
                    }
                }

                packetBuffer.clear();
                packetBuffer.put((byte) 'V');
                packetBuffer.put((byte) 'I');
                packetBuffer.put((byte) 'Z');
                packetBuffer.put((byte) '1');
                packetBuffer.put((byte) NUM_BANDS);
                packetBuffer.putFloat(rms);
                for (int b = 0; b < NUM_BANDS; b++) {
                    packetBuffer.putFloat(magnitudes[b]);
                }

                byte[] packetData = packetBuffer.array();

                // Transmissão via stdout (Canal de Pipe Principal - Imune a restrições de SELinux de socket)
                System.out.write(packetData);
                System.out.flush();

                // Transmissão via LocalSocket secundário (se ativo)
                if (socketOut != null) {
                    try {
                        socketOut.write(packetData);
                        socketOut.flush();
                    } catch (Throwable t) {
                        socketOut = null;
                    }
                }

                long elapsed = System.currentTimeMillis() - loopStartTime;
                long sleepTime = FRAME_INTERVAL_MS - elapsed;
                if (sleepTime > 0) {
                    Thread.sleep(sleepTime);
                }
            }
        } catch (Throwable t) {
            System.err.println("VIZ_DAEMON: Erro no loop de captura: " + t.getMessage());
            t.printStackTrace(System.err);
        } finally {
            System.err.println("VIZ_DAEMON: Finalizando recursos do daemon...");
            try { if (visualizer != null) visualizer.setEnabled(false); } catch (Throwable ignored) {}
            try { if (visualizer != null) visualizer.release(); } catch (Throwable ignored) {}
            try { if (socketIn != null) socketIn.close(); } catch (Throwable ignored) {}
            try { if (socketOut != null) socketOut.close(); } catch (Throwable ignored) {}
            try { if (socket != null) socket.close(); } catch (Throwable ignored) {}
        }
    }

    private static float computeBandRms(byte[] fft, int startBin, int endBin) {
        double sumPower = 0.0;
        int maxK = Math.min(511, endBin);
        for (int k = startBin; k <= maxK; k++) {
            float re;
            float im;
            if (k == 0) {
                re = (float) fft[0] / 128.0f;
                im = 0.0f;
            } else {
                re = (float) fft[2 * k] / 128.0f;
                im = (2 * k + 1 < fft.length) ? ((float) fft[2 * k + 1] / 128.0f) : 0.0f;
            }
            sumPower += (re * re + im * im);
        }
        return (float) Math.sqrt(sumPower / 2.0);
    }

    private static float computeWaveRms(byte[] wave) {
        double sumSquares = 0.0;
        for (byte b : wave) {
            int v = (b & 0xFF) - 128;
            sumSquares += (v * v);
        }
        double mean = sumSquares / wave.length;
        return (float) Math.min(1.0, Math.sqrt(mean) / 128.0);
    }
}
