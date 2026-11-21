package com.eduplay.pad

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppQosSettings
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.IBinder
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@SuppressLint("MissingPermission", "NewApi")
class HidForegroundService : Service() {

    companion object {
        const val ACTION_START_HID = "com.eduplay.pad.ACTION_START_HID"
        const val ACTION_STOP_AND_DISCONNECT = "com.eduplay.pad.ACTION_STOP_AND_DISCONNECT"

        private const val NOTIFICATION_CHANNEL_ID = "eduplay_pad_hid_channel"
        private const val NOTIFICATION_ID = 7007
        private const val KEYBOARD_REPORT_ID = 1

        const val STATUS_HID_OK = "HID_OK"
        const val STATUS_NO_HOST = "NO_HOST"
        const val STATUS_NOT_REGISTERED = "NOT_REGISTERED"
        const val STATUS_NO_PERMISSION = "NO_PERMISSION"
        const val STATUS_SEND_FAILED = "SEND_FAILED"

        @Volatile
        var instance: HidForegroundService? = null
            private set

        // Report Descriptor HID compuesto/teclado estandar con Report ID (1) explicito (8 bytes de datos)
        private val KEYBOARD_REPORT_DESCRIPTOR = byteArrayOf(
            0x05.toByte(), 0x01.toByte(), // Usage Page (Generic Desktop)
            0x09.toByte(), 0x06.toByte(), // Usage (Keyboard)
            0xA1.toByte(), 0x01.toByte(), // Collection (Application)
            0x85.toByte(), KEYBOARD_REPORT_ID.toByte(), // Report ID (1) explicito
            0x05.toByte(), 0x07.toByte(), // Usage Page (Key Codes)
            0x19.toByte(), 0xE0.toByte(), // Usage Minimum (224 - Left Control)
            0x29.toByte(), 0xE7.toByte(), // Usage Maximum (231 - Right GUI)
            0x15.toByte(), 0x00.toByte(), // Logical Minimum (0)
            0x25.toByte(), 0x01.toByte(), // Logical Maximum (1)
            0x75.toByte(), 0x01.toByte(), // Report Size (1)
            0x95.toByte(), 0x08.toByte(), // Report Count (8)
            0x81.toByte(), 0x02.toByte(), // Input (Data, Variable, Absolute) -> Byte 0: Modifiers
            0x95.toByte(), 0x01.toByte(), // Report Count (1)
            0x75.toByte(), 0x08.toByte(), // Report Size (8)
            0x81.toByte(), 0x01.toByte(), // Input (Constant) -> Byte 1: Reserved
            0x95.toByte(), 0x06.toByte(), // Report Count (6)
            0x75.toByte(), 0x08.toByte(), // Report Size (8)
            0x15.toByte(), 0x00.toByte(), // Logical Minimum (0)
            0x25.toByte(), 0x65.toByte(), // Logical Maximum (101 - incluye Numpad completo)
            0x05.toByte(), 0x07.toByte(), // Usage Page (Key Codes)
            0x19.toByte(), 0x00.toByte(), // Usage Minimum (0)
            0x29.toByte(), 0x65.toByte(), // Usage Maximum (101)
            0x81.toByte(), 0x00.toByte(), // Input (Data, Array) -> Bytes 2..7: 6 teclas simultaneas
            0xC0.toByte()                 // End Collection
        )
    }

    inner class LocalBinder : Binder() {
        fun getService(): HidForegroundService = this@HidForegroundService
    }

    private val binder = LocalBinder()
    private var bluetoothAdapter: BluetoothAdapter? = null
    @Volatile var hidDevice: BluetoothHidDevice? = null
        private set
    @Volatile var connectedHost: BluetoothDevice? = null
        private set
    @Volatile var isAppRegistered: Boolean = false
        private set

    private val hidExecutor = Executors.newSingleThreadExecutor()
    @Volatile var onStateChangedListener: (() -> Unit)? = null

    private val sdpSettings by lazy {
        BluetoothHidDeviceAppSdpSettings(
            "EduPlay Pad",
            "Mando Bluetooth HID oficial de EduPlay",
            "EduPlay",
            BluetoothHidDevice.SUBCLASS1_NONE,
            KEYBOARD_REPORT_DESCRIPTOR
        )
    }

    private val qosSettings by lazy {
        BluetoothHidDeviceAppQosSettings(
            BluetoothHidDeviceAppQosSettings.SERVICE_BEST_EFFORT,
            800,
            9,
            0,
            11250,
            BluetoothHidDeviceAppQosSettings.MAX
        )
    }

    private val hidCallback = object : BluetoothHidDevice.Callback() {
        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
            isAppRegistered = registered
            if (registered) {
                val target = pluggedDevice ?: resolveConnectedHost()
                if (target != null) {
                    connectedHost = target
                    hidDevice?.connect(target)
                } else {
                    autoConnectPairedDevice()
                }
            } else {
                connectedHost = null
            }
            updateForegroundNotification()
            onStateChangedListener?.invoke()
        }

        override fun onConnectionStateChanged(device: BluetoothDevice?, state: Int) {
            when (state) {
                BluetoothProfile.STATE_CONNECTED -> {
                    connectedHost = device
                    sendKeyboardReport(emptyList())
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    if (connectedHost?.address == device?.address) {
                        connectedHost = null
                    }
                    resolveConnectedHost()
                }
            }
            updateForegroundNotification()
            onStateChangedListener?.invoke()
        }

        override fun onGetReport(device: BluetoothDevice?, type: Byte, id: Byte, bufferSize: Int) {
            if (type == BluetoothHidDevice.REPORT_TYPE_INPUT) {
                val reportId = if (id.toInt() == 0) KEYBOARD_REPORT_ID.toByte() else id
                hidDevice?.replyReport(device, type, reportId, ByteArray(8))
            }
        }
    }

    private val profileListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            if (profile == BluetoothProfile.HID_DEVICE) {
                hidDevice = proxy as BluetoothHidDevice
                if (!isAppRegistered) {
                    hidDevice?.registerApp(
                        sdpSettings,
                        null,
                        qosSettings,
                        hidExecutor,
                        hidCallback
                    )
                }
            }
        }

        override fun onServiceDisconnected(profile: Int) {
            if (profile == BluetoothProfile.HID_DEVICE) {
                hidDevice = null
                connectedHost = null
                isAppRegistered = false
                updateForegroundNotification()
                onStateChangedListener?.invoke()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        bluetoothAdapter = bluetoothManager?.adapter
        createNotificationChannel()
        promoteToForeground()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP_AND_DISCONNECT -> {
                disconnectAndStop()
                return START_NOT_STICKY
            }
            else -> {
                promoteToForeground()
                ensureHidInitialized()
            }
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder = binder

    private fun promoteToForeground() {
        val notification = buildNotification()
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (_: Exception) {
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                NOTIFICATION_CHANNEL_ID,
                "EduPlay Pad Bluetooth HID",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Mantiene activa la conexion del mando Bluetooth HID en segundo plano"
                setShowBadge(false)
            }
            val nm = getSystemService(NotificationManager::class.java)
            nm?.createNotificationChannel(channel)
        }
    }

    private fun buildNotification(): Notification {
        val openAppIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val pendingOpen = PendingIntent.getActivity(
            this,
            0,
            openAppIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val disconnectIntent = Intent(this, HidForegroundService::class.java).apply {
            action = ACTION_STOP_AND_DISCONNECT
        }
        val pendingDisconnect = PendingIntent.getService(
            this,
            1,
            disconnectIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val hostName = getActiveHostName()
        val contentText = if (hostName != null) {
            "EduPlay Pad conectado por Bluetooth ($hostName)"
        } else if (isAppRegistered) {
            "EduPlay Pad activo en segundo plano (esperando host)"
        } else {
            "EduPlay Pad servicio Bluetooth HID preparado"
        }

        return NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("EduPlay Pad")
            .setContentText(contentText)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setContentIntent(pendingOpen)
            .addAction(
                android.R.drawable.ic_menu_close_clear_cancel,
                "Desconectar",
                pendingDisconnect
            )
            .build()
    }

    private fun updateForegroundNotification() {
        try {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            nm?.notify(NOTIFICATION_ID, buildNotification())
        } catch (_: Exception) {
        }
    }

    fun hasBluetoothPermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) ==
                PackageManager.PERMISSION_GRANTED &&
                ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADVERTISE) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED ||
                ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        }
    }

    fun ensureHidInitialized(): Map<String, Any?> {
        if (!hasBluetoothPermissions()) {
            return buildHidStatusMap(STATUS_NO_PERMISSION)
        }
        val adapter = bluetoothAdapter ?: return buildHidStatusMap(STATUS_NOT_REGISTERED)

        if (hidDevice == null) {
            adapter.getProfileProxy(this, profileListener, BluetoothProfile.HID_DEVICE)
        } else if (!isAppRegistered) {
            hidDevice?.registerApp(
                sdpSettings,
                null,
                qosSettings,
                hidExecutor,
                hidCallback
            )
        } else if (resolveConnectedHost() == null) {
            autoConnectPairedDevice()
        }

        updateForegroundNotification()
        return buildHidStatusMap(currentDiagnosticStatus())
    }

    fun resolveConnectedHost(): BluetoothDevice? {
        if (!hasBluetoothPermissions()) return null
        val service = hidDevice ?: return null

        val cached = connectedHost
        if (cached != null) {
            try {
                if (service.getConnectionState(cached) == BluetoothProfile.STATE_CONNECTED) {
                    return cached
                }
            } catch (_: Exception) {
            }
        }

        val matching = try {
            service.getDevicesMatchingConnectionStates(
                intArrayOf(BluetoothProfile.STATE_CONNECTED)
            )?.firstOrNull()
        } catch (_: Exception) {
            null
        } ?: try {
            service.connectedDevices?.firstOrNull()
        } catch (_: Exception) {
            null
        }

        connectedHost = matching
        return matching
    }

    fun autoConnectPairedDevice() {
        if (!hasBluetoothPermissions()) return
        val service = hidDevice ?: return
        val adapter = bluetoothAdapter ?: return

        if (resolveConnectedHost() != null) return

        val bonded = adapter.bondedDevices ?: return
        val preferred = bonded.firstOrNull { device ->
            val name = (device.name ?: "").lowercase()
            name.contains("x7") || name.contains("magcubic") || name.contains("projector") || name.contains("tv")
        } ?: bonded.firstOrNull()

        if (preferred != null) {
            try {
                service.connect(preferred)
            } catch (_: Exception) {
            }
        }
    }

    fun connectToBondedDevice(address: String?): Boolean {
        if (!hasBluetoothPermissions() || address.isNullOrBlank()) return false
        val service = hidDevice ?: return false
        val device = bluetoothAdapter?.bondedDevices?.firstOrNull { it.address == address }
            ?: return false
        return try {
            service.connect(device)
        } catch (_: Exception) {
            false
        }
    }

    fun sendKeyboardReport(activeKeys: List<Int>): String {
        if (!hasBluetoothPermissions()) {
            return STATUS_NO_PERMISSION
        }
        val service = hidDevice
        if (service == null || !isAppRegistered) {
            return STATUS_NOT_REGISTERED
        }
        val host = connectedHost ?: resolveConnectedHost()
            ?: return STATUS_NO_HOST

        val report = ByteArray(8)
        report[0] = 0x00
        report[1] = 0x00

        var slot = 2
        for (usageId in activeKeys) {
            if (slot >= 8) break
            val code = usageId and 0xFF
            if (code > 0) {
                report[slot] = code.toByte()
                slot++
            }
        }

        return try {
            val future = hidExecutor.submit(Callable {
                service.sendReport(host, KEYBOARD_REPORT_ID, report)
            })
            val sent = future.get(150, TimeUnit.MILLISECONDS)
            if (sent) STATUS_HID_OK else STATUS_SEND_FAILED
        } catch (_: Exception) {
            STATUS_SEND_FAILED
        }
    }

    fun currentDiagnosticStatus(): String {
        if (!hasBluetoothPermissions()) return STATUS_NO_PERMISSION
        if (hidDevice == null || !isAppRegistered) return STATUS_NOT_REGISTERED
        return if (resolveConnectedHost() != null) STATUS_HID_OK else STATUS_NO_HOST
    }

    fun getActiveHostName(): String? {
        val activeDevice = resolveConnectedHost() ?: return null
        return activeDevice.name ?: activeDevice.address
    }

    fun getBondedDevicesList(): List<Map<String, String>> {
        if (!hasBluetoothPermissions()) return emptyList()
        val bonded = bluetoothAdapter?.bondedDevices ?: emptySet()
        return bonded.map { device ->
            mapOf(
                "name" to (device.name ?: "Dispositivo BT"),
                "address" to (device.address ?: "")
            )
        }
    }

    fun buildHidStatusMap(statusCode: String = currentDiagnosticStatus()): Map<String, Any?> {
        val connectedName = getActiveHostName()
        val effectiveCode = if (connectedName != null && statusCode == STATUS_NO_HOST) {
            STATUS_HID_OK
        } else {
            statusCode
        }
        return mapOf(
            "state" to effectiveCode,
            "statusCode" to effectiveCode,
            "connectedDevice" to connectedName,
            "isRegistered" to isAppRegistered,
            "pairedDevices" to getBondedDevicesList()
        )
    }

    /**
     * Cierra explicitamente la sesion Bluetooth HID cuando el usuario pulsa "Desconectar BT"
     * o el boton "Desconectar" en la notificacion.
     */
    fun disconnectAndStop() {
        try {
            sendKeyboardReport(emptyList())
        } catch (_: Exception) {
        }
        try {
            val host = connectedHost ?: resolveConnectedHost()
            if (host != null) {
                hidDevice?.disconnect(host)
            }
        } catch (_: Exception) {
        }
        try {
            if (isAppRegistered) {
                hidDevice?.unregisterApp()
            }
        } catch (_: Exception) {
        }
        try {
            if (hidDevice != null) {
                bluetoothAdapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, hidDevice)
            }
        } catch (_: Exception) {
        }
        hidDevice = null
        connectedHost = null
        isAppRegistered = false
        onStateChangedListener?.invoke()

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                stopForeground(STOP_FOREGROUND_REMOVE)
            } else {
                @Suppress("DEPRECATION")
                stopForeground(true)
            }
        } catch (_: Exception) {
        }
        stopSelf()
    }

    override fun onDestroy() {
        if (instance === this) {
            instance = null
        }
        try {
            if (isAppRegistered) {
                hidDevice?.unregisterApp()
            }
            if (hidDevice != null) {
                bluetoothAdapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, hidDevice)
            }
        } catch (_: Exception) {
        }
        hidExecutor.shutdownNow()
        super.onDestroy()
    }
}
