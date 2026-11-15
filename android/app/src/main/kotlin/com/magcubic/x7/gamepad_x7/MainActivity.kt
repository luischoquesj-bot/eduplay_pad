package com.magcubic.x7.gamepad_x7

import android.Manifest
import android.annotation.SuppressLint
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
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import java.util.concurrent.Executors

@SuppressLint("MissingPermission", "NewApi")
class MainActivity : FlutterActivity() {

    companion object {
        private const val CHANNEL = "com.magcubic.x7/hid"
        private const val REQUEST_PERMISSIONS_CODE = 1001
        private const val REQUEST_DISCOVERABLE_CODE = 1002
        private const val KEYBOARD_REPORT_ID = 1

        // Report Descriptor de Teclado USB/BT HID estandar de 8 bytes:
        // [Byte 0: Modifiers, Byte 1: Reserved, Bytes 2..7: 6 Key Rollover (0x00..0x65)]
        // Compatible con proyectores Android AOSP (Magcubic X7) y emuladores (Neo.emu, RetroArch, MAME).
        private val KEYBOARD_REPORT_DESCRIPTOR = byteArrayOf(
            0x05.toByte(), 0x01.toByte(), // Usage Page (Generic Desktop)
            0x09.toByte(), 0x06.toByte(), // Usage (Keyboard)
            0xA1.toByte(), 0x01.toByte(), // Collection (Application)
            0x85.toByte(), KEYBOARD_REPORT_ID.toByte(), // Report ID (1)
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

    private var bluetoothAdapter: BluetoothAdapter? = null
    private var hidDevice: BluetoothHidDevice? = null
    private var connectedHost: BluetoothDevice? = null
    private var isAppRegistered = false
    private val hidExecutor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())
    private var methodChannel: MethodChannel? = null
    private var pendingPermissionResult: MethodChannel.Result? = null

    private val sdpSettings by lazy {
        BluetoothHidDeviceAppSdpSettings(
            "GamePad_X7",
            "Multi-Player Arcade & Console Bluetooth 5.0 HID Controller",
            "Magcubic_X7",
            BluetoothHidDevice.SUBCLASS1_KEYBOARD,
            KEYBOARD_REPORT_DESCRIPTOR
        )
    }

    // Configuracion QoS optimizada para baja latencia en Bluetooth 5.0+
    private val outQos by lazy {
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
                if (pluggedDevice != null) {
                    connectedHost = pluggedDevice
                    hidDevice?.connect(pluggedDevice)
                } else {
                    autoConnectPairedDevice()
                }
            } else {
                connectedHost = null
            }
            notifyConnectionStateChanged()
        }

        override fun onConnectionStateChanged(device: BluetoothDevice?, state: Int) {
            when (state) {
                BluetoothProfile.STATE_CONNECTED -> {
                    connectedHost = device
                    // Enviar reporte vacio inicial para limpiar estado de teclas en el host
                    sendKeyboardReport(emptyList())
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    if (connectedHost?.address == device?.address) {
                        connectedHost = null
                    }
                }
            }
            notifyConnectionStateChanged()
        }

        override fun onGetReport(device: BluetoothDevice?, type: Byte, id: Byte, bufferSize: Int) {
            if (type == BluetoothHidDevice.REPORT_TYPE_INPUT) {
                hidDevice?.replyReport(device, type, KEYBOARD_REPORT_ID.toByte(), ByteArray(8))
            }
        }

        override fun onSetProtocol(device: BluetoothDevice?, protocol: Byte) {
            super.onSetProtocol(device, protocol)
        }
    }

    private val profileListener = object : BluetoothProfile.ServiceListener {
        override fun onServiceConnected(profile: Int, proxy: BluetoothProfile) {
            if (profile == BluetoothProfile.HID_DEVICE) {
                hidDevice = proxy as BluetoothHidDevice
                hidDevice?.registerApp(
                    sdpSettings,
                    null,
                    outQos,
                    hidExecutor,
                    hidCallback
                )
            }
        }

        override fun onServiceDisconnected(profile: Int) {
            if (profile == BluetoothProfile.HID_DEVICE) {
                hidDevice = null
                connectedHost = null
                isAppRegistered = false
                notifyConnectionStateChanged()
            }
        }
    }

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)

        val bluetoothManager = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        bluetoothAdapter = bluetoothManager?.adapter

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
                    val makeDiscoverable = call.argument<Boolean>("discoverable") ?: true
                    if (!hasBluetoothPermissions()) {
                        requestRequiredPermissions()
                        result.success(buildHidStatusMap("PERMISSIONS_REQUIRED"))
                        return@setMethodCallHandler
                    }
                    val status = initializeHidProfile(makeDiscoverable)
                    result.success(status)
                }

                "sendKeys" -> {
                    val keys = call.argument<List<Int>>("keys")
                        ?: (call.arguments as? List<*>)?.mapNotNull { (it as? Number)?.toInt() }
                        ?: emptyList()
                    val sent = sendKeyboardReport(keys)
                    result.success(sent)
                }

                "getConnectedDevice" -> {
                    result.success(getActiveHostName())
                }

                "getPairedDevices" -> {
                    if (!hasBluetoothPermissions()) {
                        result.success(emptyList<Map<String, String>>())
                    } else {
                        result.success(getBondedDevicesList())
                    }
                }

                "connectDevice" -> {
                    val address = call.argument<String>("address")
                    val connected = connectToBondedDevice(address)
                    result.success(connected)
                }

                else -> result.notImplemented()
            }
        }
    }

    private fun hasBluetoothPermissions(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) ==
                PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADVERTISE) ==
                PackageManager.PERMISSION_GRANTED &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestRequiredPermissions() {
        val permissions = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            arrayOf(
                Manifest.permission.BLUETOOTH_CONNECT,
                Manifest.permission.BLUETOOTH_SCAN,
                Manifest.permission.BLUETOOTH_ADVERTISE
            )
        } else {
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
        }
        ActivityCompat.requestPermissions(this, permissions, REQUEST_PERMISSIONS_CODE)
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_PERMISSIONS_CODE) {
            val granted = grantResults.isNotEmpty() &&
                grantResults.all { it == PackageManager.PERMISSION_GRANTED }
            pendingPermissionResult?.success(granted)
            pendingPermissionResult = null
            if (granted) {
                initializeHidProfile(makeDiscoverable = false)
            }
        }
    }

    private fun initializeHidProfile(makeDiscoverable: Boolean): Map<String, Any?> {
        val adapter = bluetoothAdapter
            ?: return buildHidStatusMap("BLUETOOTH_UNAVAILABLE")

        if (makeDiscoverable) {
            try {
                val discoverableIntent = Intent(BluetoothAdapter.ACTION_REQUEST_DISCOVERABLE).apply {
                    putExtra(BluetoothAdapter.EXTRA_DISCOVERABLE_DURATION, 300)
                }
                startActivityForResult(discoverableIntent, REQUEST_DISCOVERABLE_CODE)
            } catch (_: Exception) {
                // Algunos dispositivos manejan visibilidad automaticamente en ajustes Bluetooth
            }
        }

        if (hidDevice == null) {
            adapter.getProfileProxy(this, profileListener, BluetoothProfile.HID_DEVICE)
        } else if (!isAppRegistered) {
            hidDevice?.registerApp(
                sdpSettings,
                null,
                outQos,
                hidExecutor,
                hidCallback
            )
        } else if (connectedHost == null) {
            autoConnectPairedDevice()
        }

        return buildHidStatusMap(
            if (connectedHost != null) "CONNECTED" else "READY"
        )
    }

    private fun autoConnectPairedDevice() {
        val service = hidDevice ?: return
        val adapter = bluetoothAdapter ?: return
        val alreadyConnected = service.connectedDevices
        if (!alreadyConnected.isNullOrEmpty()) {
            connectedHost = alreadyConnected.first()
            return
        }
        val bonded = adapter.bondedDevices ?: return
        // Priorizar proyectores Magcubic / X7 / Android TV si estan vinculados
        val preferred = bonded.firstOrNull { device ->
            val name = (device.name ?: "").lowercase()
            name.contains("x7") || name.contains("magcubic") || name.contains("projector") || name.contains("tv")
        } ?: bonded.firstOrNull()

        if (preferred != null) {
            service.connect(preferred)
        }
    }

    private fun connectToBondedDevice(address: String?): Boolean {
        if (address.isNullOrBlank()) return false
        val service = hidDevice ?: return false
        val device = bluetoothAdapter?.bondedDevices?.firstOrNull { it.address == address }
            ?: return false
        return service.connect(device)
    }

    private fun sendKeyboardReport(activeKeys: List<Int>): Boolean {
        val service = hidDevice ?: return false
        val host = connectedHost ?: service.connectedDevices?.firstOrNull()?.also {
            connectedHost = it
        } ?: return false

        // Construir el reporte HID estandar de 8 bytes:
        // [0]: Modifiers (0x00)
        // [1]: Reserved (0x00)
        // [2..7]: Hasta 6 codigos HID Usage ID simultaneos
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
            service.sendReport(host, KEYBOARD_REPORT_ID, report)
        } catch (_: Exception) {
            false
        }
    }

    private fun getActiveHostName(): String? {
        if (!hasBluetoothPermissions()) return null
        val service = hidDevice
        val activeDevice = connectedHost ?: service?.connectedDevices?.firstOrNull()
        if (activeDevice != null && service?.getConnectionState(activeDevice) == BluetoothProfile.STATE_CONNECTED) {
            connectedHost = activeDevice
            return activeDevice.name ?: activeDevice.address
        }
        connectedHost = null
        return null
    }

    private fun getBondedDevicesList(): List<Map<String, String>> {
        val bonded = bluetoothAdapter?.bondedDevices ?: emptySet()
        return bonded.map { device ->
            mapOf(
                "name" to (device.name ?: "Dispositivo BT"),
                "address" to (device.address ?: "")
            )
        }
    }

    private fun buildHidStatusMap(state: String): Map<String, Any?> {
        val connectedName = getActiveHostName()
        return mapOf(
            "state" to if (connectedName != null) "CONNECTED" else state,
            "connectedDevice" to connectedName,
            "isRegistered" to isAppRegistered,
            "pairedDevices" to if (hasBluetoothPermissions()) getBondedDevicesList() else emptyList<Map<String, String>>()
        )
    }

    private fun notifyConnectionStateChanged() {
        mainHandler.post {
            val hostName = getActiveHostName()
            methodChannel?.invokeMethod(
                "onConnectionChanged",
                mapOf(
                    "connectedDevice" to hostName,
                    "isRegistered" to isAppRegistered
                )
            )
        }
    }

    override fun onDestroy() {
        try {
            hidDevice?.unregisterApp()
            if (hidDevice != null) {
                bluetoothAdapter?.closeProfileProxy(BluetoothProfile.HID_DEVICE, hidDevice)
            }
        } catch (_: Exception) {
        }
        hidExecutor.shutdownNow()
        super.onDestroy()
    }
}
