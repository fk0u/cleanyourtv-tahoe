package tech.kousozo.tahoetv.desktop

import java.io.File
import tech.kousozo.tahoetv.core.DiagLog

/**
 * Installs or removes a user-level background daemon launching `Tahoe TV --recorder` (#53),
 * so scheduled DVR recordings run automatically even when the Desktop GUI window is closed.
 */
object BackgroundRecorder {
    private const val LABEL = "tech.kousozo.tahoetv.recorder"

    private fun osName() = System.getProperty("os.name").orEmpty().lowercase()

    fun isSupported(): Boolean {
        val os = osName()
        return "mac" in os || "darwin" in os || "win" in os
    }

    private fun launchAgentFile(): File =
        File(System.getProperty("user.home"), "Library/LaunchAgents/$LABEL.plist")

    private fun detectExecutable(): List<String>? {
        val pkgExe = ProcessHandle.current().info().command().orElse("").takeIf { it.isNotBlank() } ?: return null
        val name = File(pkgExe).name.lowercase()
        return if (name.startsWith("tahoe")) {
            listOf(pkgExe, "--recorder")
        } else {
            // Dev mode (running from Gradle/JVM): re-launch current jar classpath
            val cp = System.getProperty("java.class.path").orEmpty()
            if (cp.isBlank()) null else listOf(pkgExe, "-cp", cp, "tech.kousozo.tahoetv.desktop.MainKt", "--recorder")
        }
    }

    fun isInstalled(): Boolean {
        val os = osName()
        return when {
            "mac" in os || "darwin" in os -> launchAgentFile().isFile
            "win" in os -> runCatching {
                val p = ProcessBuilder("schtasks", "/Query", "/TN", "TahoeTVRecorder").redirectErrorStream(true).start()
                p.waitFor() == 0
            }.getOrDefault(false)
            else -> false
        }
    }

    fun install(): Result<String> = runCatching {
        val cmd = detectExecutable() ?: error("Tidak dapat mendeteksi jalur executable Tahoe TV.")
        val os = osName()
        when {
            "mac" in os || "darwin" in os -> {
                val plist = launchAgentFile()
                plist.parentFile.mkdirs()
                val argsXml = cmd.joinToString("\n") { "    <string>${xmlEsc(it)}</string>" }
                val logPath = xmlEsc(File(System.getProperty("user.home"), ".tahoetv/recorder-bg.log").absolutePath)
                plist.writeText(
                    """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <!DOCTYPE plist PUBLIC "-//Apple//DTD PLIST 1.0//EN" "http://www.apple.com/DTDs/PropertyList-1.0.dtd">
                    <plist version="1.0">
                    <dict>
                      <key>Label</key><string>$LABEL</string>
                      <key>ProgramArguments</key>
                      <array>
                    $argsXml
                      </array>
                      <key>RunAtLoad</key><true/>
                      <key>KeepAlive</key><true/>
                      <key>StandardOutPath</key><string>$logPath</string>
                      <key>StandardErrorPath</key><string>$logPath</string>
                    </dict>
                    </plist>
                    """.trimIndent()
                )
                runCatching { ProcessBuilder("launchctl", "unload", plist.absolutePath).start().waitFor() }
                ProcessBuilder("launchctl", "load", "-w", plist.absolutePath).start().waitFor()
                "LaunchAgent $LABEL aktif"
            }
            "win" in os -> {
                val tr = cmd.joinToString(" ") { "\"$it\"" }
                val p = ProcessBuilder("schtasks", "/Create", "/F", "/SC", "ONLOGON", "/TN", "TahoeTVRecorder", "/TR", tr)
                    .redirectErrorStream(true).start()
                val out = p.inputStream.bufferedReader().readText()
                check(p.waitFor() == 0) { out.ifBlank { "schtasks gagal" } }
                runCatching { ProcessBuilder(cmd).start() }
                "Scheduled task TahoeTVRecorder aktif"
            }
            else -> error("OS belum didukung untuk layanan latar otomatis.")
        }
    }.onFailure { DiagLog.error("recorder", "install bg service gagal", it) }

    fun remove(): Result<String> = runCatching {
        val os = osName()
        when {
            "mac" in os || "darwin" in os -> {
                val plist = launchAgentFile()
                runCatching { ProcessBuilder("launchctl", "unload", "-w", plist.absolutePath).start().waitFor() }
                plist.delete()
                "LaunchAgent $LABEL dinonaktifkan"
            }
            "win" in os -> {
                val p = ProcessBuilder("schtasks", "/Delete", "/F", "/TN", "TahoeTVRecorder").redirectErrorStream(true).start()
                p.waitFor()
                "Scheduled task TahoeTVRecorder dihapus"
            }
            else -> "Tidak ada layanan latar"
        }
    }

    private fun xmlEsc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
}
