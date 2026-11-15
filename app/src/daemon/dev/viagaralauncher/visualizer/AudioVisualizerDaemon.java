// SPDX-License-Identifier: Apache-2.0
package dev.viagaralauncher.visualizer;

import android.media.audiofx.Visualizer;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;

/**
 * Daemon autônomo executado sob UID 0 (root) através do /system/bin/app_process64.
 * Captura a saída de áudio global do sistema através da Sessão 0.
 */
public final class AudioVisualizerDaemon {

    private static final String SOCKET_NAME = "viagara_audio_viz";
    private static final int CAPTURE_SIZE = 1024;
    private static final int NUM_BANDS = 14;
    private static final long FRAME_INTERVAL_MS = 16L; // ~60 FPS

    private AudioVisualizerDaemon() {}

    public static void main(String[] args) {
        Visualizer visualizer = null;
        LocalSocket socket = null;
        OutputStream outStream = null;
        InputStream inStream = null;

        try {
            socket = new LocalSocket();
            boolean connected = false;
            for (int i = 0; i < 50; i++) {
                try {
                    socket.connect(new LocalSocketAddress(SOCKET_NAME, LocalSocketAddress.Namespace.ABSTRACT));
                    connected = true;
                    break;
                } catch (Throwable t) {
                    Thread.sleep(100);
                }
            }

            if (!connected) return;

            socket.setSoTimeout(500);
            outStream = socket.getOutputStream();
            inStream = socket.getInputStream();

            // Inicialização da Sessão Global 0 com permissão root
            visualizer = new Visualizer(0);
            visualizer.setCaptureSize(CAPTURE_SIZE);
            visualizer.setEnabled(true);

            int actualCaptureSize = visualizer.getCaptureSize();
            int rawSampleRate = visualizer.getSamplingRate();
            int sampleRateHz = (rawSampleRate > 0) ? (rawSampleRate / 1000) : 48000;
            float binWidth = (float) sampleRateHz / (float) actualCaptureSize;

            // 14 Bandas Logarítmicas
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

            float uniformGain = 1.25f;
            float[] emaBands = new float[NUM_BANDS];
            byte[] fftBuffer = new byte[CAPTURE_SIZE];
            byte[] waveBuffer = new byte[CAPTURE_SIZE];
            float[] magnitudes = new float[NUM_BANDS];

            ByteBuffer packetBuffer = ByteBuffer.allocate(4 + 1 + 4 + (NUM_BANDS * 4));
            packetBuffer.order(ByteOrder.LITTLE_ENDIAN);

            boolean isPaused = false;

            while (true) {
                while (inStream.available() > 0) {
                    int cmd = inStream.read();
                    if (cmd == -1 || cmd == 'S' || cmd == 'Q') {
                        return;
                    } else if (cmd == 'P') {
                        if (!isPaused) {
                            isPaused = true;
                            try { visualizer.setEnabled(false); } catch (Throwable ignored) {}
                        }
                    } else if (cmd == 'R') {
                        if (isPaused) {
                            isPaused = false;
                            try { visualizer.setEnabled(true); } catch (Throwable ignored) {}
                        }
                    }
                }

                if (isPaused) {
                    Thread.sleep(50);
                    continue;
                }

                long loopStartTime = System.currentTimeMillis();

                int fftResult = visualizer.getFft(fftBuffer);
                int waveResult = visualizer.getWaveForm(waveBuffer);
                float rms = 0f;

                if (fftResult == Visualizer.SUCCESS) {
                    float totalLevelSum = 0f;
                    for (int b = 0; b < NUM_BANDS; b++) {
                        float rawRms = computeBandRms(fftBuffer, bandStartBins[b], bandEndBins[b]);
                        emaBands[b] = emaBands[b] * 0.65f + rawRms * 0.35f;
                        float level = Math.min(1.0f, emaBands[b] * uniformGain);
                        magnitudes[b] = level;
                        totalLevelSum += level;
                    }

                    rms = (waveResult == Visualizer.SUCCESS)
                        ? computeWaveRms(waveBuffer)
                        : Math.max(0f, Math.min(1f, totalLevelSum / NUM_BANDS));
                } else {
                    for (int b = 0; b < NUM_BANDS; b++) {
                        magnitudes[b] = 0f;
                        emaBands[b] = 0f;
                    }
                    if (waveResult == Visualizer.SUCCESS) {
                        rms = computeWaveRms(waveBuffer);
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

                outStream.write(packetBuffer.array());
                outStream.flush();

                long elapsed = System.currentTimeMillis() - loopStartTime;
                long sleepTime = FRAME_INTERVAL_MS - elapsed;
                if (sleepTime > 0) {
                    Thread.sleep(sleepTime);
                }
            }
        } catch (Throwable ignored) {
        } finally {
            try { if (visualizer != null) visualizer.setEnabled(false); } catch (Throwable ignored) {}
            try { if (visualizer != null) visualizer.release(); } catch (Throwable ignored) {}
            try { if (inStream != null) inStream.close(); } catch (Throwable ignored) {}
            try { if (outStream != null) outStream.close(); } catch (Throwable ignored) {}
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
