package com.lxseek.chat.adb

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.net.nsd.NsdManager
import android.os.Build
import android.provider.Settings
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.BufferedReader
import java.io.File
import java.io.PrintStream
import java.util.concurrent.TimeUnit

/**
 * Manages a local ADB server + interactive shell process for non-root devices.
 *
 * Ported from LADB's ADB.kt. Key differences:
 *  - [adbPath] points to the downloaded binary in `context.filesDir/adb` instead of a
 *    bundled `libadb.so` in nativeLibraryDir.
 *  - SharedPreferences keys are hardcoded instead of referencing R.string resources.
 *  - Adds [isPaired], [isRunning], and [sendCommand] for integration with the shell tool.
 */
class LadbManager private constructor(private val context: Context) {

    companion object {
        const val MAX_OUTPUT_BUFFER_SIZE = 1024 * 16
        const val OUTPUT_BUFFER_DELAY_MS = 100L

        private const val TAG = "LadbManager"

        // SharedPreferences keys (hardcoded — no R.string indirection).
        private const val KEY_AUTO_SHELL = "ladb_auto_shell"
        private const val KEY_PAIRED = "ladb_paired"
        private const val KEY_BUFFER_SIZE = "ladb_buffer_size"

        @SuppressLint("StaticFieldLeak")
        @Volatile
        private var instance: LadbManager? = null
        fun getInstance(context: Context): LadbManager = instance ?: synchronized(this) {
            instance ?: LadbManager(context.applicationContext).also { instance = it }
        }
    }

    private val sharedPrefs = context.getSharedPreferences("ladb_prefs", Context.MODE_PRIVATE)

    /** Path to the downloaded adb binary. */
    private val adbPath = "${context.filesDir.absolutePath}/adb"

    /**
     * Is the shell ready to handle commands?
     */
    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running

    private var tryingToPair = false

    /**
     * Is the shell closed for any reason?
     */
    private val _closed = MutableStateFlow(false)
    val closed: StateFlow<Boolean> = _closed

    /**
     * Where shell output is stored
     */
    val outputBufferFile: File = File.createTempFile("buffer", ".txt").also {
        it.deleteOnExit()
    }

    /**
     * Single shell instance where we can pipe commands to
     */
    private var shellProcess: Process? = null

    /** NSD manager for port discovery. */
    private val nsdManager: NsdManager by lazy {
        context.getSystemService(Context.NSD_SERVICE) as NsdManager
    }

    /**
     * Returns the user buffer size if valid, else the default
     */
    fun getOutputBufferSize(): Int {
        val userValue = sharedPrefs.getString(KEY_BUFFER_SIZE, "16384")!!
        return try {
            Integer.parseInt(userValue)
        } catch (_: NumberFormatException) {
            MAX_OUTPUT_BUFFER_SIZE
        }
    }

    /** True if the adb binary exists at [adbPath]. */
    fun isBinaryInstalled(): Boolean = File(adbPath).exists() && File(adbPath).canExecute()

    /** True if the shell process is alive and ready. */
    fun isRunning(): Boolean = _running.value == true

    /** True if pairing has been completed at least once (persisted). */
    fun isPaired(): Boolean = sharedPrefs.getBoolean(KEY_PAIRED, false)

    /**
     * Get a list of connected devices.
     */
    fun getDevices(): List<String> {
        val devicesProcess = adb(false, listOf("devices"))
        devicesProcess.waitFor()

        /* Get result of the command. */
        val linesRaw = BufferedReader(devicesProcess.inputStream.reader()).readLines()

        /* Remove "List of devices attached" line if it exists (it should). */
        val deviceLines = linesRaw.filterNot { it ->
            it.contains("List of devices attached")
        }

        /* Just get first part with device name/IP and port. */
        var deviceNames = deviceLines.map { it ->
            it.split("\t").first()
        }

        /* Remove any empty lines. */
        deviceNames = deviceNames.filterNot { it ->
            it.isEmpty()
        }

        for (name in deviceNames) {
            Log.d("LINES", "<<<$name>>>")
        }

        return deviceNames
    }

    /**
     * Start the ADB server
     */
    fun initServer(): Boolean {
        if (_running.value == true || tryingToPair)
            return true

        tryingToPair = true

        val autoShell = sharedPrefs.getBoolean(KEY_AUTO_SHELL, true)

        val secureSettingsGranted =
            context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

        if (autoShell) {
            /* Only do wireless debugging steps on compatible versions */
            if (secureSettingsGranted) {
                disableMobileDataAlwaysOn()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    cycleWirelessDebugging()
                } else if (!isUSBDebuggingEnabled()) {
                    debug("Turning on USB debugging...")
                    Settings.Global.putInt(
                        context.contentResolver,
                        Settings.Global.ADB_ENABLED,
                        1
                    )

                    Thread.sleep(5_000)
                }
            }

            /* Check again... */
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                if (!isWirelessDebuggingEnabled()) {
                    debug("Wireless debugging is not enabled!")
                    debug("Settings -> Developer options -> Wireless debugging")
                    debug("Waiting for wireless debugging...")

                    while (!isWirelessDebuggingEnabled()) {
                        Thread.sleep(1_000)
                    }
                }
            } else {
                if (!isUSBDebuggingEnabled()) {
                    debug("USB debugging is not enabled!")
                    debug("Settings -> Developer options -> USB debugging")
                    debug("Waiting for USB debugging...")

                    while (!isUSBDebuggingEnabled()) {
                        Thread.sleep(1_000)
                    }
                }
            }

            val nowTime = System.currentTimeMillis()
            val maxTimeoutTime = nowTime + 10_000L // 10 seconds
            val minDnsScanTime = (AdbPortDiscover.aliveTime ?: nowTime) + 3_000L
            while (true) {
                val nowTime = System.currentTimeMillis()
                val pendingResolves = AdbPortDiscover.pendingResolves.get()

                // Wait for pending DNS resolves to finish and the minimum scan time to elapse...
                if (nowTime >= minDnsScanTime && !pendingResolves) {
                    debug("DNS resolver done...")
                    break
                }

                // Or if 10 seconds pass...
                if (nowTime >= maxTimeoutTime) {
                    debug("DNS resolver took too long! Skipping...")
                    break
                }

                debug("Awaiting DNS resolver...")

                Thread.sleep(1_000)
            }

            val adbPort = AdbPortDiscover.adbPort
            if (adbPort != null)
                debug("Best ADB port discovered: $adbPort")
            else
                debug("No ADB port discovered, fallback...")

            debug("Starting ADB server...")
            adb(false, listOf("start-server")).waitFor(1, TimeUnit.MINUTES)

            val waitProcess = if (adbPort != null)
                adb(false, listOf("connect", "localhost:$adbPort")).waitFor(1, TimeUnit.MINUTES)
            else
                adb(false, listOf("wait-for-device")).waitFor(1, TimeUnit.MINUTES)

            if (!waitProcess) {
                debug("Your device didn't connect to LADB")
                debug("If a reboot doesn't work, please contact support")

                if (isMobileDataAlwaysOnEnabled()) {
                    debug("Please disable 'Mobile data always on' in Developer Settings!")
                    Thread.sleep(5_000)
                }

                tryingToPair = false
                return false
            }
        }

        val deviceList = getDevices()
        Log.d("DEVICES", "Devices: $deviceList")

        shellProcess = if (autoShell) {
            var argList = listOf("shell")

            /* Uh oh, multiple possible devices... */
            if (deviceList.size > 1) {
                Log.w("DEVICES", "Multiple devices detected...")
                val localDevices = deviceList.filter { it ->
                    it.contains("localhost")
                }

                /* Choose the first local device (hopefully the only). */
                if (localDevices.isNotEmpty()) {
                    val serialId = localDevices.first()
                    Log.w("DEVICES", "Choosing first local device: $serialId")
                    argList = listOf("-s", serialId, "shell")
                } else {
                    /*
                     * If no local devices to use, try to filter out
                     * any emulator devices and choose the first remaining result.
                     */

                    val nonEmulators = deviceList.filterNot { it ->
                        it.contains("emulator")
                    }

                    /* Choose the first non emulator device (hopefully the only). */
                    if (nonEmulators.isNotEmpty()) {
                        val serialId = nonEmulators.first()
                        Log.w("DEVICES", "Choosing first non-emulator device: $serialId")
                        argList = listOf("-s", serialId, "shell")
                    } else {
                        /* Otherwise, we're screwed, just choose the first device. */
                        val serialId = deviceList.first()
                        Log.w("DEVICES", "Choosing first unrecognized device: $serialId")
                        argList = listOf("-s", serialId, "shell")
                    }
                }
            }

            adb(true, argList)
        } else {
            shell(true, listOf("sh", "-l"))
        }

        sendToShellProcess("alias adb=\"$adbPath\"")

        if (!secureSettingsGranted) {
            sendToShellProcess("pm grant ${context.packageName} android.permission.WRITE_SECURE_SETTINGS &> /dev/null")
        }

        if (autoShell)
            sendToShellProcess("echo 'Entered adb shell'")
        else
            sendToShellProcess("echo 'Entered non-adb shell'")

        _running.value = true
        tryingToPair = false

        return true
    }

    private fun isWirelessDebuggingEnabled() =
        Settings.Global.getInt(context.contentResolver, "adb_wifi_enabled", 0) == 1

    private fun isUSBDebuggingEnabled() =
        Settings.Global.getInt(context.contentResolver, Settings.Global.ADB_ENABLED, 0) == 1

    private fun isMobileDataAlwaysOnEnabled() =
        Settings.Global.getInt(context.contentResolver, "mobile_data_always_on", 0) == 1

    /**
     * Settings.Global.MOBILE_DATA_ALWAYS_ON creates a bug
     * with the DNS resolver.
     */
    fun disableMobileDataAlwaysOn() {
        val secureSettingsGranted =
            context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

        if (secureSettingsGranted) {
            // Only turn it off if it's already on.
            if (isMobileDataAlwaysOnEnabled()) {
                debug("Disabling 'Mobile data always on'...")
                Settings.Global.putInt(
                    context.contentResolver,
                    "mobile_data_always_on",
                    0
                )
                Thread.sleep(3_000)
            }
        }
    }

    /**
     * Cycles wireless debugging to get a new port to scan.
     *
     * For whatever reason, Wireless Debugging needs to be
     * cycled twice to broadcast a valid port.
     */
    fun cycleWirelessDebugging() {
        val secureSettingsGranted =
            context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED

        if (secureSettingsGranted) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                debug("Cycling wireless debugging, please wait...")
                // Only turn it off if it's already on.
                if (isWirelessDebuggingEnabled()) {
                    debug("Turning off wireless debugging...")
                    Settings.Global.putInt(
                        context.contentResolver,
                        "adb_wifi_enabled",
                        0
                    )
                    Thread.sleep(3_000)
                }

                debug("Turning on wireless debugging...")
                Settings.Global.putInt(
                    context.contentResolver,
                    "adb_wifi_enabled",
                    1
                )
                Thread.sleep(3_000)

                debug("Turning off wireless debugging...")
                Settings.Global.putInt(
                    context.contentResolver,
                    "adb_wifi_enabled",
                    0
                )
                Thread.sleep(3_000)

                debug("Turning on wireless debugging...")
                Settings.Global.putInt(
                    context.contentResolver,
                    "adb_wifi_enabled",
                    1
                )
                Thread.sleep(3_000)
            }
        }
    }

    /** Returns whether wireless debugging is currently enabled. */
    fun isWirelessDebuggingEnabledPublic(): Boolean = isWirelessDebuggingEnabled()

    /**
     * Wait restart the shell once it dies
     */
    fun waitForDeathAndReset() {
        while (true) {
            /* Do not falsely claim the shell is dead if we haven't even initialized it yet */
            if (tryingToPair) continue

            shellProcess?.waitFor()
            _running.value = false
            debug("Shell is dead, resetting...")
            adb(false, listOf("kill-server")).waitFor()

            Thread.sleep(3_000)
            initServer()
        }
    }

    /**
     * Ask the device to pair on Android 11+ devices
     */
    fun pair(port: String, pairingCode: String): Boolean {
        val pairShell = adb(false, listOf("pair", "localhost:$port"))

        /* Sleep to allow shell to catch up */
        Thread.sleep(5000)

        /* Pipe pairing code */
        PrintStream(pairShell.outputStream).apply {
            println(pairingCode)
            flush()
        }

        /* Continue once finished pairing (or 10s elapses) */
        pairShell.waitFor(10, TimeUnit.SECONDS)
        pairShell.destroyForcibly().waitFor()

        val killShell = adb(false, listOf("kill-server"))
        killShell.waitFor(3, TimeUnit.SECONDS)
        killShell.destroyForcibly()

        val success = pairShell.exitValue() == 0
        if (success) {
            sharedPrefs.edit().putBoolean(KEY_PAIRED, true).apply()
        }
        return success
    }

    /**
     * Send a raw ADB command
     */
    private fun adb(redirect: Boolean, command: List<String>): Process {
        val commandList = command.toMutableList().also {
            it.add(0, adbPath)
        }
        return shell(redirect, commandList)
    }

    /**
     * Send a raw shell command
     */
    private fun shell(redirect: Boolean, command: List<String>): Process {
        val processBuilder = ProcessBuilder(command)
            .directory(context.filesDir)
            .apply {
                if (redirect) {
                    redirectErrorStream(true)
                    redirectOutput(outputBufferFile)
                }

                environment().apply {
                    put("HOME", context.filesDir.path)
                    put("TMPDIR", context.cacheDir.path)
                }
            }

        return processBuilder.start()!!
    }

    /**
     * Send commands directly to the shell process
     */
    fun sendToShellProcess(msg: String) {
        if (shellProcess == null || shellProcess?.outputStream == null)
            return
        PrintStream(shellProcess!!.outputStream!!).apply {
            println(msg)
            flush()
        }
    }

    /**
     * Send a command to the shell process and return its output.
     *
     * Uses a sentinel marker to delimit the command's output so we can extract just
     * the relevant portion from the shared output buffer file.
     */
    fun sendCommand(cmd: String): String {
        if (shellProcess == null) return "Error: shell process not initialized"
        val marker = "__LXADB_MARKER_${System.currentTimeMillis()}_"
        sendToShellProcess("$cmd ; echo $marker")
        // Give the shell time to produce output.
        Thread.sleep(OUTPUT_BUFFER_DELAY_MS)
        return readOutputBuffer(marker)
    }

    /**
     * Reads the output buffer file and extracts content up to the last sentinel marker.
     */
    private fun readOutputBuffer(marker: String): String {
        synchronized(outputBufferFile) {
            if (!outputBufferFile.exists()) return ""
            val content = outputBufferFile.readText()
            // Find the last occurrence of the marker and return everything before it
            // that wasn't part of a previous command.
            val markerIdx = content.lastIndexOf(marker)
            if (markerIdx < 0) return content.take(MAX_OUTPUT_BUFFER_SIZE)
            // Find the previous marker (if any) to isolate this command's output.
            val prevMarkerRegex = "__LXADB_MARKER_\\d+_".toRegex()
            val beforeMarker = content.substring(0, markerIdx)
            val prevMatch = prevMarkerRegex.findAll(beforeMarker).lastOrNull()
            val start = prevMatch?.range?.last?.plus(1) ?: 0
            return beforeMarker.substring(start).trimEnd()
        }
    }

    /**
     * Write a debug message to the user
     */
    fun debug(msg: String) {
        synchronized(outputBufferFile) {
            Log.d(TAG, msg)
            if (outputBufferFile.exists())
                outputBufferFile.appendText("* $msg" + System.lineSeparator())
        }
    }

    /** Starts the mDNS port discovery scan. Call before [initServer]. */
    fun startPortDiscovery() {
        AdbPortDiscover.reset()
        AdbPortDiscover.getInstance(context, nsdManager).scanAdbPorts()
    }

    /** Kills the ADB server and shell process. */
    fun kill() {
        try {
            shellProcess?.destroyForcibly()
        } catch (_: Exception) {}
        shellProcess = null
        _running.value = false
        try {
            adb(false, listOf("kill-server")).waitFor(3, TimeUnit.SECONDS)
        } catch (_: Exception) {}
    }
}