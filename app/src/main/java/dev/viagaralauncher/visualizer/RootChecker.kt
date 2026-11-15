// SPDX-License-Identifier: Apache-2.0
package dev.viagaralauncher.visualizer

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

enum class RootStatus {
    CHECKING,
    GRANTED,
    DENIED,
    NOT_AVAILABLE
}

data class RootInfo(
    val status: RootStatus = RootStatus.CHECKING,
    val details: String = "",
    val manager: String = ""
)

object RootChecker {

    suspend fun checkRoot(): RootInfo = withContext(Dispatchers.IO) {
        val suPaths = arrayOf(
            "/system/bin/su",
            "/system/xbin/su",
            "/sbin/su",
            "/system/su",
            "/system/bin/.ext/.su",
            "/system/usr/we-need-root/su-backup",
            "/data/local/xbin/su",
            "/data/local/bin/su",
            "/data/local/su"
        )

        var suBinaryFound = false
        for (path in suPaths) {
            if (File(path).exists()) {
                suBinaryFound = true
                break
            }
        }

        val managerName = detectRootManager()

        try {
            val process = ProcessBuilder("su", "-c", "id").start()
            val reader = BufferedReader(InputStreamReader(process.inputStream))
            val output = reader.readLine() ?: ""
            val exitCode = process.waitFor()

            if (exitCode == 0 && output.contains("uid=0")) {
                RootInfo(
                    status = RootStatus.GRANTED,
                    details = "UID 0 (Root) ativo e verificado com sucesso.",
                    manager = managerName
                )
            } else {
                RootInfo(
                    status = RootStatus.DENIED,
                    details = "Permissão de root negada pelo gerenciador de superusuário.",
                    manager = managerName
                )
            }
        } catch (_: Throwable) {
            RootInfo(
                status = if (suBinaryFound) RootStatus.DENIED else RootStatus.NOT_AVAILABLE,
                details = if (suBinaryFound) {
                    "Binário 'su' encontrado, mas a execução falhou ou foi rejeitada."
                } else {
                    "Dispositivo não rooteado ou comando 'su' ausente."
                },
                manager = managerName
            )
        }
    }

    private fun detectRootManager(): String {
        return when {
            File("/data/adb/ksu").exists() || File("/data/adb/ksud").exists() -> "KernelSU"
            File("/data/adb/ap").exists() || File("/data/adb/apd").exists() -> "APatch"
            File("/data/adb/magisk").exists() || File("/sbin/.magisk").exists() -> "Magisk"
            File("/system/app/SuperSU").exists() -> "SuperSU"
            else -> "Superusuário Padrão"
        }
    }
}
