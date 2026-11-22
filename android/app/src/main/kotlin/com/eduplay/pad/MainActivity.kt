package com.eduplay.pad

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel

@SuppressLint("MissingPermission", "NewApi")
class MainActivity : FlutterActivity() {

    companion object {
        private const val CHANNEL = "com.eduplay.pad/hid"
        private const val REQUEST_PERMISSIONS_CODE = 1001
        private const val REQUEST_DISCOVERABLE_CODE = 1002
        private const val REQUEST_ENABLE_BT_CODE = 1003
    }

    private var bluetoothAdapter: BluetoothAdapter? = null
    private var hidService: HidForegroundService? = null
    private var isServiceBound = false
    @Volatile private var pendingDiscoverableOnGrant = false
    @Volatile private var isRequestingPermissions = false

    private val mainHandler = Handler(Looper.getMainLooper())
    private var methodChannel: MethodChannel? = null
    private val pendingPermissionResults = mutableListOf<MethodChannel.Result>()

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            val localBinder = binder as? HidForegroundService.LocalBinder
            val service = localBinder?.getService() ?: HidForegroundService.instance
            hidService = service
            isServiceBound = service != null
            service?.onStateChangedListener = {
                notifyConnectionStateChanged()
            }
            service?.ensureHidInitialized()
            notifyConnectionStateChanged()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            isServiceBound = false
            hidService = null
            notifyConnectionStateChanged()
        }
    }

    private fun activeService(): HidForegroundService? {
        return hidService ?: HidForegroundService.instance
    }

    private fun isBluetoothEnabled(): Boolean {
        val adapter = bluetoothAdapter ?: (getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        bluetoothAdapter = adapter
        return try {
            adapter?.isEnabled == true
        } catch (_: Exception) {
            false
        }
    }

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)

        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        bluetoothAdapter = bluetoothManager?.adapter

        // Reconectar listener si el servicio ya estaba corriendo en segundo plano
        HidForegroundService.instance?.let { existing ->
            hidService = existing
            existing.onStateChangedListener = {
                notifyConnectionStateChanged()
            }
        }

        methodChannel = MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CHANNEL)
        methodChannel?.setMethodCallHandler { call, result ->
            when (call.method) {
                "requestPermissions" -> {
                    if (hasBluetoothPermissions()) {
                        result.success(true)
                    } else {
                        pendingPermissionResults.add(result)
                        requestRequiredPermissions()
                    }
                }

                "initHid" -> {
                    val makeDiscoverable = call.argument<Boolean>("discoverable") ?: false
                    if (!hasBluetoothPermissions()) {
                        pendingDiscoverableOnGrant = pendingDiscoverableOnGrant || makeDiscoverable
                        requestRequiredPermissions()
                        result.success(buildFallbackStatusMap(HidForegroundService.STATUS_NO_PERMISSION))
                        return@setMethodCallHandler
                    }
                    if (!isBluetoothEnabled()) {
                        try {
                            val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
                            startActivityForResult(enableBtIntent, REQUEST_ENABLE_BT_CODE)
                        } catch (_: Exception) {
                        }
                        result.success(buildFallbackStatusMap(HidForegroundService.STATUS_BT_OFF))
                        return@setMethodCallHandler
                    }
                    val status = initializeOrAttachHidService(makeDiscoverable)
                    result.success(status)
                }

                "sendKeys" -> {
                    val keys = call.argument<List<Int>>("keys")
                        ?: (call.arguments as? List<*>)?.mapNotNull { (it as? Number)?.toInt() }
                        ?: emptyList()
                    if (!hasBluetoothPermissions()) {
                        requestRequiredPermissions()
                        result.success(HidForegroundService.STATUS_NO_PERMISSION)
                        return@setMethodCallHandler
                    }
                    if (!isBluetoothEnabled()) {
                        result.success(HidForegroundService.STATUS_BT_OFF)
                        return@setMethodCallHandler
                    }
                    val service = activeService()
                    val statusCode = if (service == null) {
                        startAndBindHidService()
                        HidForegroundService.STATUS_NOT_REGISTERED
                    } else {
                        if (!service.isAppRegistered) {
                            service.ensureHidInitialized()
                        }
                        service.sendKeyboardReport(keys)
                    }
                    result.success(statusCode)
                }

                "getConnectedDevice" -> {
                    val service = activeService()
                    if (service != null && hasBluetoothPermissions() && isBluetoothEnabled() && !service.isAppRegistered) {
                        service.ensureHidInitialized()
                    }
                    result.success(service?.getActiveHostName())
                }

                "getHidStatus" -> {
                    val autoInit = call.argument<Boolean>("autoInit") ?: true
                    if (!hasBluetoothPermissions()) {
                        result.success(buildFallbackStatusMap(HidForegroundService.STATUS_NO_PERMISSION))
                        return@setMethodCallHandler
                    }
                    if (!isBluetoothEnabled()) {
                        result.success(buildFallbackStatusMap(HidForegroundService.STATUS_BT_OFF))
                        return@setMethodCallHandler
                    }
                    val service = activeService()
                    if (service != null) {
                        if (autoInit && !service.isAppRegistered) {
                            result.success(service.ensureHidInitialized())
                        } else {
                            result.success(service.buildHidStatusMap())
                        }
                    } else {
                        if (autoInit) {
                            startAndBindHidService()
                        }
                        result.success(buildFallbackStatusMap(HidForegroundService.STATUS_NOT_REGISTERED))
                    }
                }

                "getPairedDevices" -> {
                    if (!hasBluetoothPermissions() || !isBluetoothEnabled()) {
                        result.success(emptyList<Map<String, String>>())
                    } else {
                        val list = activeService()?.getBondedDevicesList() ?: getBondedDevicesFallback()
                        result.success(list)
                    }
                }

                "connectDevice" -> {
                    val address = call.argument<String>("address")
                    if (!hasBluetoothPermissions()) {
                        requestRequiredPermissions()
                        result.success(false)
                        return@setMethodCallHandler
                    }
                    val service = activeService()
                    if (service != null && !service.isAppRegistered) {
                        service.ensureHidInitialized()
                    }
                    val connected = service?.connectToBondedDevice(address) ?: false
                    result.success(connected)
                }

                "disconnectHid" -> {
                    disconnectAndStopHidService()
                    result.success(buildFallbackStatusMap(HidForegroundService.STATUS_NOT_REGISTERED))
                }

                else -> result.notImplemented()
            }
        }
    }

    override fun onStart() {
        super.onStart()
        // Si el servicio ya existe, revincular sin reiniciar la sesion Bluetooth
        if (HidForegroundService.instance != null) {
            bindToHidServiceIfNeeded()
            HidForegroundService.instance?.onStateChangedListener = {
                notifyConnectionStateChanged()
            }
            notifyConnectionStateChanged()
        }
    }

    override fun onResume() {
        super.onResume()
        activeService()?.let { service ->
            service.onStateChangedListener = {
                notifyConnectionStateChanged()
            }
            if (hasBluetoothPermissions() && isBluetoothEnabled() && !service.isAppRegistered) {
                service.ensureHidInitialized()
            }
            notifyConnectionStateChanged()
        }
    }

    // IMPORTANTE: No se llama a unregisterApp() ni se cierra el proxy en onPause(), onStop() ni onDestroy()
    // para que la conexion Bluetooth HID con el host se mantenga viva en segundo plano.

    override fun onDestroy() {
        activeService()?.onStateChangedListener = null
        if (isServiceBound) {
            try {
                unbindService(serviceConnection)
            } catch (_: Exception) {
            }
            isServiceBound = false
        }
        super.onDestroy()
    }

    private fun startAndBindHidService() {
        val serviceIntent = Intent(this, HidForegroundService::class.java).apply {
            action = HidForegroundService.ACTION_START_HID
        }
        try {
            ContextCompat.startForegroundService(this, serviceIntent)
        } catch (_: Exception) {
            try {
                startService(serviceIntent)
            } catch (_: Exception) {
            }
        }
        bindToHidServiceIfNeeded()
    }

    private fun bindToHidServiceIfNeeded() {
        if (!isServiceBound) {
            val bindIntent = Intent(this, HidForegroundService::class.java)
            try {
                isServiceBound = bindService(bindIntent, serviceConnection, Context.BIND_AUTO_CREATE)
            } catch (_: Exception) {
            }
        }
    }

    private fun initializeOrAttachHidService(makeDiscoverable: Boolean): Map<String, Any?> {
        if (!hasBluetoothPermissions()) {
            requestRequiredPermissions()
            return buildFallbackStatusMap(HidForegroundService.STATUS_NO_PERMISSION)
        }
        if (!isBluetoothEnabled()) {
            return buildFallbackStatusMap(HidForegroundService.STATUS_BT_OFF)
        }

        if (makeDiscoverable) {
            try {
                val discoverableIntent = Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).apply {
                    putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300)
                }
                startActivityForResult(discoverableIntent, REQUEST_DISCOVERABLE_CODE)
            } catch (_: Exception) {
            }
        }

        val existing = activeService()
        if (existing != null) {
            existing.onStateChangedListener = {
                notifyConnectionStateChanged()
            }
            bindToHidServiceIfNeeded()
            return existing.ensureHidInitialized()
        }

        startAndBindHidService()
        val afterStart = activeService()
        return afterStart?.ensureHidInitialized()
            ?: buildFallbackStatusMap(HidForegroundService.STATUS_NOT_REGISTERED)
    }

    private fun disconnectAndStopHidService() {
        val service = activeService()
        if (isServiceBound) {
            try {
                unbindService(serviceConnection)
            } catch (_: Exception) {
            }
            isServiceBound = false
        }
        hidService = null
        service?.disconnectAndStop()
        notifyConnectionStateChanged()
    }

    private fun hasBluetoothPermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) ==
                PackageManager.PERMISSION_GRANTED &&
                ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADVERTISE) ==
                PackageManager.PERMISSION_GRANTED &&
                ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED ||
                ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestRequiredPermissions() {
        if (isRequestingPermissions) return
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
            }
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADVERTISE) != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.BLUETOOTH_ADVERTISE)
            }
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.BLUETOOTH_SCAN)
            }
        } else {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.ACCESS_COARSE_LOCATION)
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ActivityCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                permissions.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        if (permissions.isEmpty()) {
            flushPendingPermissionResults(true)
            return
        }

        isRequestingPermissions = true
        ActivityCompat.requestPermissions(
            this,
            permissions.toTypedArray(),
            REQUEST_PERMISSIONS_CODE
        )
    }

    private fun flushPendingPermissionResults(granted: Boolean) {
        val callbacks = ArrayList(pendingPermissionResults)
        pendingPermissionResults.clear()
        for (cb in callbacks) {
            try {
                cb.success(granted)
            } catch (_: Exception) {
            }
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_PERMISSIONS_CODE) {
            isRequestingPermissions = false
            val granted = hasBluetoothPermissions()
            flushPendingPermissionResults(granted)
            if (granted) {
                val shouldDiscover = pendingDiscoverableOnGrant
                pendingDiscoverableOnGrant = false
                initializeOrAttachHidService(makeDiscoverable = shouldDiscover)
                notifyConnectionStateChanged()
            } else {
                notifyConnectionStateChanged()
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_ENABLE_BT_CODE || requestCode == REQUEST_DISCOVERABLE_CODE) {
            if (hasBluetoothPermissions() && isBluetoothEnabled()) {
                initializeOrAttachHidService(makeDiscoverable = false)
            }
            notifyConnectionStateChanged()
        }
    }

    private fun getBondedDevicesFallback(): List<Map<String, String>> {
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

    private fun buildFallbackStatusMap(statusCode: String): Map<String, Any?> {
        val service = activeService()
        if (service != null) {
            return service.buildHidStatusMap(statusCode)
        }
        return mapOf(
            "state" to statusCode,
            "statusCode" to statusCode,
            "connectedDevice" to null,
            "isRegistered" to false,
            "pairedDevices" to getBondedDevicesFallback()
        )
    }

    private fun notifyConnectionStateChanged() {
        mainHandler.post {
            val service = activeService()
            val hostName = service?.getActiveHostName()
            val isRegistered = service?.isAppRegistered ?: false
            val statusCode = service?.currentDiagnosticStatus()
                ?: when {
                    !hasBluetoothPermissions() -> HidForegroundService.STATUS_NO_PERMISSION
                    !isBluetoothEnabled() -> HidForegroundService.STATUS_BT_OFF
                    else -> HidForegroundService.STATUS_NOT_REGISTERED
                }
            methodChannel?.invokeMethod(
                "onConnectionChanged",
                mapOf(
                    "connectedDevice" to hostName,
                    "isRegistered" to isRegistered,
                    "statusCode" to statusCode
                )
            )
        }
    }
}
