package dev.ktmnav.link

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

/** Android Bluetooth access, permission-checked. Only bonded devices: the bike pairs in system settings. */
class Bluetooth(private val context: Context) {

    private val adapter: BluetoothAdapter? =
        context.getSystemService(BluetoothManager::class.java)?.adapter

    val isAvailable: Boolean get() = adapter != null

    fun hasConnectPermission(): Boolean =
        Build.VERSION.SDK_INT < 31 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED

    @SuppressLint("MissingPermission")
    fun isEnabled(): Boolean = hasConnectPermission() && adapter?.isEnabled == true

    @SuppressLint("MissingPermission")
    fun bondedDevices(): List<BikeDevice> {
        if (!hasConnectPermission()) return emptyList()
        return adapter?.bondedDevices.orEmpty()
            .map { BikeDevice(it.address, it.name ?: it.address) }
            .sortedWith(compareByDescending<BikeDevice> { looksLikeBike(it.name) }.thenBy { it.name })
    }

    fun isBonded(address: String): Boolean = bondedDevices().any { it.address == address }

    /** The state to show when no connection is running. */
    fun idleState(saved: BikeDevice?): LinkState = when {
        !isAvailable -> LinkState.BluetoothUnavailable
        !hasConnectPermission() -> LinkState.NoPermission
        !isEnabled() -> LinkState.BluetoothOff
        saved == null -> LinkState.NotPaired
        saved.isSimulator -> LinkState.Paired(saved)
        !isBonded(saved.address) -> LinkState.NotPaired
        else -> LinkState.Paired(saved)
    }

    fun transportFor(device: BikeDevice, uuid: UUID, insecure: Boolean): Transport =
        RfcommTransport(adapter, device.address, uuid, insecure, ::hasConnectPermission)

    /**
     * Watches the adapter and the bike's ACL link, so a bike that comes back into range
     * is retried at once instead of waiting out the backoff.
     */
    fun watch(onChange: () -> Unit, onDeviceConnected: (String) -> Unit): () -> Unit {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) {
                when (intent.action) {
                    BluetoothDevice.ACTION_ACL_CONNECTED -> {
                        val device: BluetoothDevice? = if (Build.VERSION.SDK_INT >= 33) {
                            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                        } else {
                            @Suppress("DEPRECATION")
                            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                        }
                        device?.address?.let(onDeviceConnected)
                    }
                    else -> onChange()
                }
            }
        }
        val filter = IntentFilter().apply {
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
        }
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_EXPORTED)
        return { runCatching { context.unregisterReceiver(receiver) } }
    }

    companion object {
        fun looksLikeBike(name: String): Boolean =
            Regex("""ktm|my ?ride|390|adventure|tft""", RegexOption.IGNORE_CASE).containsMatchIn(name)
    }
}

/** Bluetooth Classic serial socket to the MY RIDE service UUID. */
private class RfcommTransport(
    private val adapter: BluetoothAdapter?,
    private val address: String,
    private val uuid: UUID,
    private val insecure: Boolean,
    private val permitted: () -> Boolean,
) : Transport {
    @Volatile private var socket: BluetoothSocket? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null

    @SuppressLint("MissingPermission")
    override fun open() {
        if (!permitted()) throw IOException("Nearby devices permission not granted")
        val a = adapter ?: throw IOException("no Bluetooth adapter")
        if (!a.isEnabled) throw IOException("Bluetooth is off")
        val device = a.getRemoteDevice(address)
        val s = if (insecure) device.createInsecureRfcommSocketToServiceRecord(uuid)
        else device.createRfcommSocketToServiceRecord(uuid)
        socket = s
        // Discovery slows connects down a lot.
        runCatching { a.cancelDiscovery() }
        s.connect()
        input = s.inputStream
        output = s.outputStream
    }

    override fun write(bytes: ByteArray) {
        val out = output ?: throw IOException("not connected")
        out.write(bytes)
        out.flush()
    }

    override fun read(buffer: ByteArray): Int {
        val inp = input ?: throw IOException("not connected")
        return inp.read(buffer)
    }

    override fun close() {
        runCatching { socket?.close() }
        socket = null
        input = null
        output = null
    }
}
