package com.simoneluconi.betterauto.audio

import android.content.AttributionSource
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.ContextWrapper
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Binder
import android.os.ParcelFileDescriptor
import android.os.Process
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.concurrent.thread
import kotlin.system.exitProcess

/**
 * Runs in a Shizuku user service, as the shell user, which holds MODIFY_AUDIO_ROUTING: registers an
 * audio policy whose mix takes the phone's media (music, games, unknown) away from the speaker, the way
 * the system Android Auto does, and streams it to the app through a pipe. The policy dies with this
 * process, so the phone never stays silent if the app goes away.
 *
 * AudioPolicy / AudioMix are system APIs, reached by reflection.
 */
class AudioRouterService(private val context: Context) : IAudioRouter.Stub() {
    private var policy: Any? = null
    private var record: AudioRecord? = null
    private var pump: Thread? = null
    @Volatile
    private var running = false
    @Volatile
    private var lastStatus = "idle"

    // Shizuku calls this when it creates the service; the no-arg form is for older Shizuku versions
    @Suppress("unused")
    constructor() : this(Class.forName("android.app.ActivityThread").getMethod("currentApplication").invoke(null) as Context)

    @Synchronized
    override fun start(sampleRate: Int, channels: Int, encoding: Int, uids: IntArray, usages: IntArray): ParcelFileDescriptor? =
        asShell { startRouting(sampleRate, channels, encoding, uids, usages) }

    @Synchronized
    override fun stop() = asShell { stopRouting() }

    /**
     * AudioPolicy checks MODIFY_AUDIO_ROUTING on the binder caller, which is the app: run as this
     * process (the shell user, which holds it) instead.
     */
    private inline fun <T> asShell(block: () -> T): T {
        val identity = Binder.clearCallingIdentity()
        try {
            return block()
        } finally {
            Binder.restoreCallingIdentity(identity)
        }
    }

    private fun startRouting(sampleRate: Int, channels: Int, encoding: Int, uids: IntArray, usages: IntArray): ParcelFileDescriptor? {
        stopRouting()
        return try {
            val format = AudioFormat.Builder()
                .setEncoding(encoding)
                .setSampleRate(sampleRate)
                .setChannelMask(if (channels == 1) AudioFormat.CHANNEL_IN_MONO else AudioFormat.CHANNEL_IN_STEREO)
                .build()
            val mix = buildMix(format, uids, if (usages.isEmpty()) intArrayOf(AudioAttributes.USAGE_MEDIA) else usages)
            val newPolicy = buildPolicy(mix)
            val audio = context.getSystemService(AudioManager::class.java)
            val result = AudioManager::class.java.getMethod("registerAudioPolicy", POLICY).invoke(audio, newPolicy) as Int
            if (result != 0) {
                lastStatus = "registerAudioPolicy failed: $result"
                return null
            }
            policy = newPolicy
            val newRecord = buildRecord(mix, format)
            record = newRecord
            val (readEnd, writeEnd) = ParcelFileDescriptor.createPipe()
            running = true
            newRecord.startRecording()
            pump = thread(name = "audio-router") { pump(newRecord, writeEnd) }
            lastStatus = "routing ${if (uids.isEmpty()) "all media" else "${uids.size} apps"}, $sampleRate Hz ${channels}ch ${if (encoding == AudioFormat.ENCODING_PCM_FLOAT) "float" else "16-bit"}"
            readEnd
        } catch (e: Throwable) {
            lastStatus = "start failed: ${(e.cause ?: e)}"
            stopRouting()
            null
        }
    }

    /** AudioRecord -> pipe until stopped or until the app closes its end. */
    private fun pump(source: AudioRecord, sink: ParcelFileDescriptor) {
        val float = source.audioFormat == AudioFormat.ENCODING_PCM_FLOAT
        val bytes = ByteArray(BUFFER_BYTES)
        val samples = FloatArray(BUFFER_BYTES / 4)
        val floatBytes = ByteBuffer.allocate(BUFFER_BYTES).order(ByteOrder.LITTLE_ENDIAN)
        ParcelFileDescriptor.AutoCloseOutputStream(sink).use { out ->
            try {
                while (running) {
                    if (float) {
                        // Float keeps the mix untouched; the app dithers it down to the car's bit depth
                        val n = source.read(samples, 0, samples.size, AudioRecord.READ_BLOCKING)
                        if (n < 0) break
                        floatBytes.clear()
                        floatBytes.asFloatBuffer().put(samples, 0, n)
                        out.write(floatBytes.array(), 0, n * 4)
                    } else {
                        val n = source.read(bytes, 0, bytes.size)
                        if (n > 0) out.write(bytes, 0, n) else if (n < 0) break
                    }
                }
            } catch (_: IOException) {
                // The app closed its end: nothing more to send
            }
        }
    }

    private fun stopRouting() {
        running = false
        runCatching { record?.stop() }
        pump?.join(500)
        runCatching { record?.release() }
        record = null
        pump = null
        policy?.let { p ->
            val audio = context.getSystemService(AudioManager::class.java)
            runCatching { AudioManager::class.java.getMethod("unregisterAudioPolicy", POLICY).invoke(audio, p) }
        }
        policy = null
        if (lastStatus.startsWith("routing")) lastStatus = "stopped"
    }

    override fun status(): String = lastStatus

    override fun bluetoothAddress(): String? = runCatching {
        val process = ProcessBuilder("settings", "get", "secure", "bluetooth_address").redirectErrorStream(true).start()
        process.inputStream.bufferedReader().readText().trim().also { process.waitFor() }
    }.getOrNull()?.takeIf { BluetoothAdapter.checkBluetoothAddress(it) }

    /** Needs BLUETOOTH_PRIVILEGED, which the shell user holds: called as this process, with the shell's identity. */
    override fun confirmPairing(address: String, accept: Boolean): Boolean = asShell {
        runCatching {
            shellBluetooth().getRemoteDevice(address).setPairingConfirmation(accept)
        }.onSuccess { lastStatus = "pairing answered: $it" }
            .onFailure { lastStatus = "pairing not answered: $it" }
            .getOrDefault(false)
    }

    /** System APIs (BLUETOOTH_PRIVILEGED): by reflection, like the audio policy ones. */
    override fun allowCarAccess(address: String): Boolean = asShell {
        runCatching {
            val device = shellBluetooth().getRemoteDevice(address)
            val allowed = BluetoothDevice::class.java.getField("ACCESS_ALLOWED").getInt(null)
            listOf("setPhonebookAccessPermission", "setMessageAccessPermission").map { name ->
                BluetoothDevice::class.java.getMethod(name, Int::class.javaPrimitiveType).invoke(device, allowed) == true
            }.all { it }
        }.onSuccess { lastStatus = "car access allowed: $it" }
            .onFailure { lastStatus = "car access not allowed: $it" }
            .getOrDefault(false)
    }

    /**
     * The Bluetooth adapter as the shell user. A process started by Shizuku skips the app startup that hooks
     * Bluetooth up ("BluetoothServiceManager is null"), so that is done here once; and the manager is built
     * on the shell's identity, which the Bluetooth service checks against the calling uid.
     */
    private fun shellBluetooth(): BluetoothAdapter {
        if (!bluetoothHooked) {
            runCatching {
                val serviceManager = Class.forName("android.os.BluetoothServiceManager")
                Class.forName("android.bluetooth.BluetoothFrameworkInitializer")
                    .getMethod("setBluetoothServiceManager", serviceManager)
                    .invoke(null, serviceManager.getDeclaredConstructor().newInstance())
            } // already hooked up by this process: fine
            bluetoothHooked = true
        }
        val manager = BluetoothManager::class.java.getDeclaredConstructor(Context::class.java).newInstance(shellContext)
        return manager.adapter ?: error("no Bluetooth adapter")
    }

    private var bluetoothHooked = false

    /** `cmd wifi` takes it from the shell user, which this process is; the phone forgets it when it restarts. */
    override fun setDualWifi(on: Boolean): String = runCatching {
        val change = if (on) arrayOf("enabled", "true") else arrayOf("disabled", "false")
        val process = ProcessBuilder("cmd", "wifi", "force-overlay-config-value", "bool", DUAL_WIFI_OVERLAY, *change)
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText().trim()
        "exit ${process.waitFor()}${if (output.isNotEmpty()) ", $output" else ""}"
    }.getOrElse { "failed: $it" }

    override fun destroy() {
        stop()
        exitProcess(0)
    }

    /**
     * A mix of the media the car should play, kept from the speaker (loop back only). Rules of one kind
     * match any of their values, and kinds combine: music usage AND one of these apps.
     */
    private fun buildMix(format: AudioFormat, uids: IntArray, usages: IntArray): Any {
        val ruleBuilder = Class.forName("android.media.audiopolicy.AudioMixingRule\$Builder").getConstructor().newInstance()
        val addRule = ruleBuilder.javaClass.getMethod("addRule", AudioAttributes::class.java, Int::class.javaPrimitiveType)
        for (usage in usages) {
            addRule.invoke(ruleBuilder, AudioAttributes.Builder().setUsage(usage).build(), RULE_MATCH_ATTRIBUTE_USAGE)
        }
        if (uids.isNotEmpty()) {
            val addMixRule = ruleBuilder.javaClass.getMethod("addMixRule", Int::class.javaPrimitiveType, Any::class.java)
            for (uid in uids) addMixRule.invoke(ruleBuilder, RULE_MATCH_UID, uid)
        }
        val rule = ruleBuilder.javaClass.getMethod("build").invoke(ruleBuilder)
        val mixBuilder = Class.forName("android.media.audiopolicy.AudioMix\$Builder").getConstructor(rule.javaClass).newInstance(rule)
        mixBuilder.javaClass.getMethod("setFormat", AudioFormat::class.java).invoke(mixBuilder, format)
        mixBuilder.javaClass.getMethod("setRouteFlags", Int::class.javaPrimitiveType).invoke(mixBuilder, ROUTE_FLAG_LOOP_BACK)
        return mixBuilder.javaClass.getMethod("build").invoke(mixBuilder)
    }

    /**
     * The recorder that reads the mix, as AudioPolicy.createAudioRecordSink builds it, but attributed to the
     * shell package: this process is the shell user, and recording for another package's name is refused.
     */
    private fun buildRecord(mix: Any, format: AudioFormat): AudioRecord {
        val address = MIX.getMethod("getRegistration").invoke(mix) as String
        val attributes = AudioAttributes.Builder()
        AudioAttributes.Builder::class.java.getMethod("setInternalCapturePreset", Int::class.javaPrimitiveType)
            .invoke(attributes, MediaRecorder.AudioSource.REMOTE_SUBMIX)
        val addTag = AudioAttributes.Builder::class.java.getMethod("addTag", String::class.java)
        addTag.invoke(attributes, "addr=$address")
        // AudioRecord.SUBMIX_FIXED_VOLUME: the mix is captured at full scale, whatever the phone's volume
        addTag.invoke(attributes, "fixedVolume")
        val minBuffer = AudioRecord.getMinBufferSize(format.sampleRate, format.channelMask, format.encoding)
        val builder = AudioRecord.Builder()
        AudioRecord.Builder::class.java.getMethod("setAudioAttributes", AudioAttributes::class.java).invoke(builder, attributes.build())
        val record = builder
            .setAudioFormat(format)
            .setBufferSizeInBytes(maxOf(minBuffer, BUFFER_BYTES) * 4)
            .setContext(shellContext)
            .build()
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            throw IllegalStateException("the mix recorder did not initialize")
        }
        return record
    }

    /** This process as the audio server must see it: the shell user, with the shell package's name. */
    private val shellContext = object : ContextWrapper(context) {
        override fun getOpPackageName() = SHELL_PACKAGE

        override fun getAttributionSource(): AttributionSource =
            AttributionSource.Builder(Process.myUid()).setPackageName(SHELL_PACKAGE).setPid(Process.myPid()).build()
    }

    private fun buildPolicy(mix: Any): Any {
        val builder = Class.forName("android.media.audiopolicy.AudioPolicy\$Builder").getConstructor(Context::class.java).newInstance(context)
        builder.javaClass.getMethod("addMix", MIX).invoke(builder, mix)
        return builder.javaClass.getMethod("build").invoke(builder)
    }

    private companion object {
        /** The WiFi setting for a second connection to a local-only network (a head unit's or dongle's). */
        private const val DUAL_WIFI_OVERLAY = "config_wifiMultiStaLocalOnlyConcurrencyEnabled"
        val POLICY: Class<*> = Class.forName("android.media.audiopolicy.AudioPolicy")
        val MIX: Class<*> = Class.forName("android.media.audiopolicy.AudioMix")

        /** AudioMixingRule.RULE_MATCH_ATTRIBUTE_USAGE and AudioMix.ROUTE_FLAG_LOOP_BACK (system API constants). */
        const val RULE_MATCH_ATTRIBUTE_USAGE = 0x1
        const val RULE_MATCH_UID = 0x4
        const val ROUTE_FLAG_LOOP_BACK = 0x2

        const val BUFFER_BYTES = 4096

        /** The package of the shell user this process runs as. */
        const val SHELL_PACKAGE = "com.android.shell"
    }
}
