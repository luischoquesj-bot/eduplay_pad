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
    }

    private var bluetoothAdapter: BluetoothAdapter? = null
    private var hidService: HidForegroundService? = null
    private var isServiceBound = false
    @Volatile private var pendingDiscoverableOnGrant = false

    private val mainHandler = Handler(Looper.getMainLooper())
    private var methodChannel: MethodChannel? = null
    private var pendingPermissionResult: MethodChannel.Result? = null

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
                        pendingPermissionResult = result
                        requestRequiredPermissions()
                    }
                }

                "initHid" -> {
                    val makeDiscoverable = call.argument<Boolean>("discoverable") ?: false
                    if (!hasBluetoothPermissions()) {
                        pendingDiscoverableOnGrant = makeDiscoverable
                        requestRequiredPermissions()
                        result.success(buildFallbackStatusMap(HidForegroundService.STATUS_NO_PERMISSION))
                        return@setMethodCallHandler
                    }
                    val status = initializeOrAttachHidService(makeDiscoverable)
                    result.success(status)
                }

                "sendKeys" -> {
                    val keys = call.argument<List<Int>>("keys")
                        ?: (call.arguments as? List<*>)?.mapNotNull { (it as? Number)?.toInt() }
                        ?: emptyList()
                    val service = activeService()
                    val statusCode = if (!hasBluetoothPermissions()) {
                        HidForegroundService.STATUS_NO_PERMISSION
                    } else if (service == null) {
                        HidForegroundService.STATUS_NOT_REGISTERED
                    } else {
                        service.sendKeyboardReport(keys)
                    }
                    result.success(statusCode)
                }

                "getConnectedDevice" -> {
                    result.success(activeService()?.getActiveHostName())
                }

                "getPairedDevices" -> {
                    if (!hasBluetoothPermissions()) {
                        result.success(emptyList<Map<String, String>>())
                    } else {
                        val list = activeService()?.getBondedDevicesList() ?: getBondedDevicesFallback()
                        result.success(list)
                    }
                }

                "connectDevice" -> {
                    val address = call.argument<String>("address")
                    val service = activeService()
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
        // Si el servicio ya existe o tenemos permisos, revincular sin reiniciar la sesion Bluetooth
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
            notifyConnectionStateChanged()
        }
    }

    // IMPORTANTE: No se llama a unregisterApp() ni se cierra el proxy en onPause(), onStop() ni onDestroy()
    // para que la conexion Bluetooth HID con el proyector se mantenga viva en segundo plano.

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
                PackageManager.PERMISSION_GRANTED
        } else {
            ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED ||
                ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestRequiredPermissions() {
        val permissions = mutableListOf<String>()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
            permissions.add(Manifest.permission.BLUETOOTH_ADVERTISE)
            permissions.add(Manifest.permission.BLUETOOTH_SCAN)
        } else {
            permissions.add(Manifest.permission.ACCESS_FINE_LOCATION)
            permissions.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        ActivityCompat.requestPermissions(
            this,
            permissions.toTypedArray(),
            REQUEST_PERMISSIONS_CODE
        )
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_PERMISSIONS_CODE) {
            val granted = hasBluetoothPermissions()
            pendingPermissionResult?.success(granted)
            pendingPermissionResult = null
            if (granted) {
                val shouldDiscover = pendingDiscoverableOnGrant
                pendingDiscoverableOnGrant = false
                initializeOrAttachHidService(makeDiscoverable = shouldDiscover)
                notifyConnectionStateChanged()
            }
        }
    }

    private fun getBondedDevicesFallback(): List<Map<String, String>> {
        if (!hasBluetoothPermissions()) return emptyList()
        val bonded = bluetoothAdapter?.bondedDevices ?: emptySet()
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
                ?: if (hasBluetoothPermissions()) {
                    HidForegroundService.STATUS_NOT_REGISTERED
                } else {
                    HidForegroundService.STATUS_NO_PERMISSION
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
