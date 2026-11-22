package com.eduplay.pad

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHidDevice
import android.bluetooth.BluetoothHidDeviceAppQosSettings
import android.bluetooth.BluetoothHidDeviceAppSdpSettings
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Binder
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import java.util.Arrays
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

        private const val PREFS_NAME = "eduplay_pad_hid_prefs"
        private const val KEY_LAST_HOST_ADDRESS = "last_connected_host_address"

        const val STATUS_HID_OK = "HID_OK"
        const val STATUS_NO_HOST = "NO_HOST"
        const val STATUS_NOT_REGISTERED = "NOT_REGISTERED"
        const val STATUS_NO_PERMISSION = "NO_PERMISSION"
        const val STATUS_BT_OFF = "BT_OFF"
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
            0x25.toByte(), 0x65.toByte(), // Logical Maximum (101 - incluye Numpad completo y F1-F12)
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
    private val mainHandler = Handler(Looper.getMainLooper())
    private val registrationLock = Any()

    private var bluetoothAdapter: BluetoothAdapter? = null
    @Volatile var hidDevice: BluetoothHidDevice? = null
        private set
    @Volatile var connectedHost: BluetoothDevice? = null
        private set
    @Volatile var isAppRegistered: Boolean = false
        private set

    @Volatile private var isGettingProfileProxy: Boolean = false
    @Volatile private var lastProxyRequestMs: Long = 0L
    @Volatile private var isRegisteringApp: Boolean = false
    @Volatile private var lastRegisterAttemptMs: Long = 0L
    @Volatile private var isReceiverRegistered: Boolean = false
    @Volatile private var lastSentReport: ByteArray? = null

    // Hilo dedicado de alta prioridad para envio HID sin bloquear UI ni saturar L2CAP en multimando (4+ dispositivos)
    private val hidExecutor = Executors.newSingleThreadExecutor { runnable ->
        Thread(runnable, "EduPlayPad-HidTx").apply {
            priority = Thread.MAX_PRIORITY
        }
    }
    @Volatile var onStateChangedListener: (() -> Unit)? = null

    // SUBCLASS1_NONE evita bloqueos en capas OEM estrictas (Xiaomi HyperOS/MIUI, Infinix XOS, Tecno HiOS, Honor)
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

    private fun saveLastConnectedHostAddress(address: String?) {
        if (address.isNullOrBlank()) return
        try {
            getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_LAST_HOST_ADDRESS, address)
                .apply()
        } catch (_: Exception) {
        }
    }

    private fun getLastConnectedHostAddress(): String? {
        return try {
            getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .getString(KEY_LAST_HOST_ADDRESS, null)
        } catch (_: Exception) {
            null
        }
    }

    private val bluetoothReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                BluetoothAdapter.ACTION_STATE_CHANGED -> {
                    val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                    when (state) {
                        BluetoothAdapter.STATE_ON -> {
                            mainHandler.postDelayed({
                                ensureHidInitialized()
                                onStateChangedListener?.invoke()
                            }, 300L)
                        }
                        BluetoothAdapter.STATE_OFF,
                        BluetoothAdapter.STATE_TURNING_OFF -> {
                            connectedHost = null
                            lastSentReport = null
                            isAppRegistered = false
                            isRegisteringApp = false
                            isGettingProfileProxy = false
                            updateForegroundNotification()
                            onStateChangedListener?.invoke()
                        }
                    }
                }
                BluetoothDevice.ACTION_BOND_STATE_CHANGED -> {
                    val bondState = intent.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.ERROR)
                    val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    }
                    if (bondState == BluetoothDevice.BOND_BONDED && device != null) {
                        saveLastConnectedHostAddress(device.address)
                        mainHandler.postDelayed({
                            ensureHidInitialized()
                            if (isAppRegistered && resolveConnectedHost() == null) {
                                try {
                                    hidDevice?.connect(device)
                                } catch (_: Exception) {
                                }
                            }
                            onStateChangedListener?.invoke()
                        }, 250L)
                        mainHandler.postDelayed({
                            if (isAppRegistered && resolveConnectedHost() == null) {
                                try {
                                    hidDevice?.connect(device)
                                } catch (_: Exception) {
                                }
                            }
                            onStateChangedListener?.invoke()
                        }, 1100L)
                    }
                }
                BluetoothDevice.ACTION_ACL_CONNECTED -> {
                    val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                    }
                    if (device != null) {
                        mainHandler.postDelayed({
                            if (isAppRegistered && resolveConnectedHost() == null) {
                                try {
                                    if (device.bondState == BluetoothDevice.BOND_BONDED) {
                                        hidDevice?.connect(device)
                                    }
                                } catch (_: Exception) {
                                }
                            }
                            onStateChangedListener?.invoke()
                        }, 350L)
                    }
                }
            }
        }
    }

    private val hidCallback = object : BluetoothHidDevice.Callback() {
        override fun onAppStatusChanged(pluggedDevice: BluetoothDevice?, registered: Boolean) {
            synchronized(registrationLock) {
                isRegisteringApp = false
                isAppRegistered = registered
            }
            if (registered) {
                val active = resolveConnectedHost()
                if (active != null) {
                    connectedHost = active
                    saveLastConnectedHostAddress(active.address)
                } else {
                    if (pluggedDevice != null) {
                        connectedHost = pluggedDevice
                        saveLastConnectedHostAddress(pluggedDevice.address)
                        try {
                            hidDevice?.connect(pluggedDevice)
                        } catch (_: Exception) {
                        }
                    }
                    autoConnectPairedDevice()
                    // Reintento automatico escalonado por si la pila SDP del host tarda unos milisegundos en estar lista
                    mainHandler.postDelayed({
                        if (isAppRegistered && resolveConnectedHost() == null) {
                            autoConnectPairedDevice()
                        }
                        onStateChangedListener?.invoke()
                    }, 900L)
                }
            } else {
                connectedHost = null
                lastSentReport = null
            }
            updateForegroundNotification()
            onStateChangedListener?.invoke()
        }

        override fun onConnectionStateChanged(device: BluetoothDevice?, state: Int) {
            when (state) {
                BluetoothProfile.STATE_CONNECTED -> {
                    connectedHost = device
                    lastSentReport = null
                    saveLastConnectedHostAddress(device?.address)
                    sendKeyboardReport(emptyList())
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    if (connectedHost?.address == device?.address) {
                        connectedHost = null
                        lastSentReport = null
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
                try {
                    hidDevice?.replyReport(device, type, reportId, ByteArray(8))
                } catch (_: Exception) {
                }
            }
        }

        override fun onSetProtocol(device: BluetoothDevice?, protocol: Byte) {
            super.onSetProtocol(device, protocol)
        }
    }

    private val profileListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            if (profile == BluetoothProfile.HID_DEVICE) {
                synchronized(registrationLock) {
                    isGettingProfileProxy = false
                    val oldProxy = hidDevice
                    val newProxy = proxy as BluetoothHidDevice
                    if (oldProxy != null && oldProxy !== newProxy) {
                        try {
                            bluetoothAdapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, oldProxy)
                        } catch (_: Exception) {
                        }
                    }
                    hidDevice = newProxy
                }
                registerHidAppIfNeeded()
                updateForegroundNotification()
                onStateChangedListener?.invoke()
            }
        }

        override fun onServiceDisconnected(profile: Int) {
            if (profile == BluetoothProfile.HID_DEVICE) {
                synchronized(registrationLock) {
                    hidDevice = null
                    connectedHost = null
                    lastSentReport = null
                    isAppRegistered = false
                    isRegisteringApp = false
                    isGettingProfileProxy = false
                }
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
        registerBluetoothStateReceiver()
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

    private fun registerBluetoothStateReceiver() {
        if (isReceiverRegistered) return
        try {
            val filter = IntentFilter().apply {
                addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
                addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
                addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(bluetoothReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(bluetoothReceiver, filter)
            }
            isReceiverRegistered = true
        } catch (_: Exception) {
        }
    }

    private fun unregisterBluetoothStateReceiver() {
        if (!isReceiverRegistered) return
        try {
            unregisterReceiver(bluetoothReceiver)
        } catch (_: Exception) {
        }
        isReceiverRegistered = false
    }

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

    fun isBluetoothEnabled(): Boolean {
        val adapter = bluetoothAdapter ?: (getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        bluetoothAdapter = adapter
        return try {
            adapter?.isEnabled == true
        } catch (_: Exception) {
            false
        }
    }

    private fun registerHidAppIfNeeded() {
        val service = hidDevice ?: return
        if (!hasBluetoothPermissions() || !isBluetoothEnabled()) return

        val now = SystemClock.elapsedRealtime()
        synchronized(registrationLock) {
            if (isAppRegistered) return
            if (isRegisteringApp && (now - lastRegisterAttemptMs) < 4000L) {
                return
            }
            isRegisteringApp = true
            lastRegisterAttemptMs = now
        }

        try {
            hidExecutor.execute {
                try {
                    val initiated = service.registerApp(
                        sdpSettings,
                        null,
                        qosSettings,
                        hidExecutor,
                        hidCallback
                    )
                    if (!initiated) {
                        // Limpiar posible registro zombie previo en la pila Bluetooth del fabricante y reintentar
                        try {
                            service.unregisterApp()
                        } catch (_: Exception) {
                        }
                        synchronized(registrationLock) {
                            isRegisteringApp = false
                        }
                        mainHandler.postDelayed({
                            retryRegisterHidAppOnce()
                        }, 500L)
                    }
                } catch (_: Exception) {
                    synchronized(registrationLock) {
                        isRegisteringApp = false
                    }
                }
            }
        } catch (_: Exception) {
            synchronized(registrationLock) {
                isRegisteringApp = false
            }
        }
    }

    private fun retryRegisterHidAppOnce() {
        val service = hidDevice ?: return
        if (!hasBluetoothPermissions() || !isBluetoothEnabled() || isAppRegistered) return
        synchronized(registrationLock) {
            if (isRegisteringApp) return
            isRegisteringApp = true
            lastRegisterAttemptMs = SystemClock.elapsedRealtime()
        }
        try {
            hidExecutor.execute {
                try {
                    val ok = service.registerApp(
                        sdpSettings,
                        null,
                        qosSettings,
                        hidExecutor,
                        hidCallback
                    )
                    if (!ok) {
                        synchronized(registrationLock) {
                            isRegisteringApp = false
                        }
                    }
                } catch (_: Exception) {
                    synchronized(registrationLock) {
                        isRegisteringApp = false
                    }
                }
            }
        } catch (_: Exception) {
            synchronized(registrationLock) {
                isRegisteringApp = false
            }
        }
    }

    fun ensureHidInitialized(): Map<String, Any?> {
        if (!hasBluetoothPermissions()) {
            return buildHidStatusMap(STATUS_NO_PERMISSION)
        }
        if (!isBluetoothEnabled()) {
            return buildHidStatusMap(STATUS_BT_OFF)
        }
        val adapter = bluetoothAdapter ?: return buildHidStatusMap(STATUS_BT_OFF)

        val now = SystemClock.elapsedRealtime()
        if (hidDevice == null) {
            var shouldRequestProxy = false
            synchronized(registrationLock) {
                if (!isGettingProfileProxy || (now - lastProxyRequestMs) > 4000L) {
                    isGettingProfileProxy = true
                    lastProxyRequestMs = now
                    shouldRequestProxy = true
                }
            }
            if (shouldRequestProxy) {
                val requested = try {
                    adapter.getProfileProxy(applicationContext, profileListener, BluetoothProfile.HID_DEVICE)
                } catch (_: Exception) {
                    false
                }
                if (!requested) {
                    synchronized(registrationLock) {
                        isGettingProfileProxy = false
                    }
                }
            }
        } else if (!isAppRegistered) {
            registerHidAppIfNeeded()
        } else if (resolveConnectedHost() == null) {
            autoConnectPairedDevice()
        }

        updateForegroundNotification()
        return buildHidStatusMap(currentDiagnosticStatus())
    }

    fun resolveConnectedHost(): BluetoothDevice? {
        if (!hasBluetoothPermissions() || !isBluetoothEnabled()) return null
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

        if (matching != null) {
            connectedHost = matching
            saveLastConnectedHostAddress(matching.address)
        } else {
            connectedHost = null
        }
        return matching
    }

    /**
     * Reconexion automatica al iniciar la app o al registrarse el servicio SDP:
     * 1) Prioriza el ultimo host/proyector conectado guardado en SharedPreferences.
     * 2) Busca en bluetoothAdapter.bondedDevices proyectores/TV/PC conocidos.
     * 3) Si hay cualquier dispositivo vinculado, llama a hidDevice.connect(device).
     */
    fun autoConnectPairedDevice() {
        if (!hasBluetoothPermissions() || !isBluetoothEnabled()) return
        val service = hidDevice ?: return
        val adapter = bluetoothAdapter ?: return

        if (resolveConnectedHost() != null) return

        val bonded = try {
            adapter.bondedDevices
        } catch (_: Exception) {
            null
        } ?: return

        if (bonded.isEmpty()) return

        val lastAddress = getLastConnectedHostAddress()
        val lastHost = if (!lastAddress.isNullOrBlank()) {
            bonded.firstOrNull { it.address.equals(lastAddress, ignoreCase = true) }
        } else {
            null
        }

        val preferred = lastHost ?: bonded.firstOrNull { device ->
            val name = (device.name ?: "").lowercase()
            name.contains("x7") ||
                name.contains("magcubic") ||
                name.contains("projector") ||
                name.contains("proyector") ||
                name.contains("hy300") ||
                name.contains("hy320") ||
                name.contains("wanbo") ||
                name.contains("tv") ||
                name.contains("android") ||
                name.contains("box") ||
                name.contains("pc") ||
                name.contains("desktop") ||
                name.contains("laptop")
        } ?: bonded.firstOrNull { device ->
            val major = try {
                device.bluetoothClass?.majorDeviceClass
            } catch (_: Exception) {
                null
            }
            major == BluetoothClass.Device.Major.COMPUTER
        } ?: bonded.firstOrNull()

        if (preferred != null) {
            try {
                service.connect(preferred)
            } catch (_: Exception) {
            }
        }
    }

    fun connectToBondedDevice(address: String?): Boolean {
        if (!hasBluetoothPermissions() || !isBluetoothEnabled() || address.isNullOrBlank()) return false
        val service = hidDevice ?: return false
        val device = try {
            bluetoothAdapter?.bondedDevices?.firstOrNull { it.address == address }
        } catch (_: Exception) {
            null
        } ?: return false

        saveLastConnectedHostAddress(device.address)

        return try {
            val currentState = service.getConnectionState(device)
            if (currentState == BluetoothProfile.STATE_CONNECTED) {
                connectedHost = device
                updateForegroundNotification()
                onStateChangedListener?.invoke()
                true
            } else {
                service.connect(device)
            }
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Envio HID optimizado de latencia cero para multimando (4+ dispositivos simultaneos):
     * - Ruta rapida sin llamadas IPC innecesarias (PackageManager/BluetoothManager/getConnectionState) en cada pulsacion.
     * - Deduplicacion de reportes identicos no vacios para no saturar el canal L2CAP.
     * - Respaldo automatico con getDevicesMatchingConnectionStates si connectedHost es null o el envio falla.
     */
    fun sendKeyboardReport(activeKeys: List<Int>): String {
        val service = hidDevice
        if (service == null || !isAppRegistered) {
            if (!hasBluetoothPermissions()) return STATUS_NO_PERMISSION
            if (!isBluetoothEnabled()) return STATUS_BT_OFF
            return STATUS_NOT_REGISTERED
        }

        var host = connectedHost
        if (host == null) {
            host = try {
                service.getDevicesMatchingConnectionStates(
                    intArrayOf(BluetoothProfile.STATE_CONNECTED)
                )?.firstOrNull()
            } catch (_: Exception) {
                null
            } ?: resolveConnectedHost()
            connectedHost = host
        }

        val targetHost = host ?: return STATUS_NO_HOST

        val report = ByteArray(8)
        var slot = 2
        for (usageId in activeKeys) {
            if (slot >= 8) break
            val code = usageId and 0xFF
            if (code > 0) {
                report[slot] = code.toByte()
                slot++
            }
        }

        val isReleaseReport = (slot == 2)
        val previous = lastSentReport
        if (!isReleaseReport && previous != null && Arrays.equals(previous, report)) {
            return STATUS_HID_OK
        }

        return try {
            val future = hidExecutor.submit(Callable {
                var ok = service.sendReport(targetHost, KEYBOARD_REPORT_ID, report)
                if (!ok) {
                    val refreshedHost = try {
                        service.getDevicesMatchingConnectionStates(
                            intArrayOf(BluetoothProfile.STATE_CONNECTED)
                        )?.firstOrNull()
                    } catch (_: Exception) {
                        null
                    }
                    if (refreshedHost != null) {
                        connectedHost = refreshedHost
                        ok = service.sendReport(refreshedHost, KEYBOARD_REPORT_ID, report)
                    } else {
                        connectedHost = null
                    }
                }
                if (ok) {
                    lastSentReport = report
                }
                ok
            })
            val sent = future.get(250, TimeUnit.MILLISECONDS)
            if (sent) STATUS_HID_OK else STATUS_SEND_FAILED
        } catch (_: Exception) {
            STATUS_SEND_FAILED
        }
    }

    fun currentDiagnosticStatus(): String {
        if (!hasBluetoothPermissions()) return STATUS_NO_PERMISSION
        if (!isBluetoothEnabled()) return STATUS_BT_OFF
        if (hidDevice == null || !isAppRegistered) return STATUS_NOT_REGISTERED
        return if (resolveConnectedHost() != null) STATUS_HID_OK else STATUS_NO_HOST
    }

    fun getActiveHostName(): String? {
        val activeDevice = resolveConnectedHost() ?: return null
        return try {
            activeDevice.name ?: activeDevice.address
        } catch (_: Exception) {
            activeDevice.address
        }
    }

    fun getBondedDevicesList(): List<Map<String, String>> {
        if (!hasBluetoothPermissions() || !isBluetoothEnabled()) return emptyList()
        val bonded = try {
            bluetoothAdapter?.bondedDevices
        } catch (_: Exception) {
            null
        } ?: emptySet()
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
     * Cierra explicitamente la sesion Bluetooth HID solo cuando el usuario pulsa "Desconectar BT"
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
        synchronized(registrationLock) {
            hidDevice = null
            connectedHost = null
            lastSentReport = null
            isAppRegistered = false
            isRegisteringApp = false
            isGettingProfileProxy = false
        }
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
        unregisterBluetoothStateReceiver()
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
