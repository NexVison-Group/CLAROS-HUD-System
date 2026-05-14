package com.daklok.claroshudsystem.ble

import android.Manifest
import android.annotation.SuppressLint
import android.app.Application
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.UUID

// ─────────────────────────────────────────────────────────────────────────────
// BLE / Nordic UART Service constants
// ─────────────────────────────────────────────────────────────────────────────

/** The advertised local name that the ESP32 firmware uses. */
const val ESP32_DEVICE_NAME = "ESP32-HUD"

/** Nordic UART Service (NUS) — widely used for ESP32 serial-over-BLE. */
private val NUS_SERVICE_UUID: UUID = UUID.fromString("6E400001-B5A3-F393-E0A9-E50E24DCCA9E")

/** NUS TX characteristic — the app *writes* navigation data to this. */
private val NUS_TX_CHAR_UUID: UUID  = UUID.fromString("6E400002-B5A3-F393-E0A9-E50E24DCCA9E")

/** How long to scan before giving up (ms). */
private const val SCAN_TIMEOUT_MS = 10_000L

// ─────────────────────────────────────────────────────────────────────────────

enum class EspConnectionStatus {
    DISCONNECTED,
    SCANNING,
    CONNECTING,
    CONNECTED,
    ERROR
}

/** A BLE device found during scanning. */
data class BleDeviceItem(
    val name: String,
    val address: String
)

data class EspConnectionUiState(
    val status: EspConnectionStatus = EspConnectionStatus.DISCONNECTED,
    val deviceName: String = ESP32_DEVICE_NAME,
    val lastPayloadSent: String = "",
    val lastPayloadAt: Long = 0L,
    val payloadLog: List<String> = emptyList(),
    val errorMessage: String? = null,
    /** Non-empty while the device-picker sheet should be shown. */
    val scannedDevices: List<BleDeviceItem> = emptyList(),
    val showDevicePicker: Boolean = false
)

// ─────────────────────────────────────────────────────────────────────────────

/**
 * BLE (GATT / Nordic UART Service) implementation.
 *
 * Flow:
 *  1. [startScan]  -> sets status SCANNING, populates [scannedDevices].
 *  2. User picks a device from the in-app picker  ->  [connectToDevice].
 *  3. GATT callback  ->  status CONNECTED when NUS service is found.
 *  4. [sendPayload] -> write-without-response to NUS TX characteristic.
 *  5. [disconnect]  -> close GATT.
 *
 * BLE devices do NOT appear in the system Bluetooth settings unless paired,
 * so we must scan and present results inside the app.
 */
@SuppressLint("MissingPermission") // permissions are checked at call-site
class EspConnectionViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(EspConnectionUiState())
    val uiState: StateFlow<EspConnectionUiState> = _uiState.asStateFlow()

    private val bluetoothAdapter by lazy {
        getApplication<Application>()
            .getSystemService(BluetoothManager::class.java)
            ?.adapter
    }

    private var gatt: BluetoothGatt? = null
    private var txChar: BluetoothGattCharacteristic? = null
    private var scanTimeoutJob: Job? = null

    // -- Permissions -----------------------------------------------------------

    private fun hasScanPermission(): Boolean {
        val ctx = getApplication<Application>()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.BLUETOOTH_SCAN) ==
                    PackageManager.PERMISSION_GRANTED
        } else true
    }

    private fun hasConnectPermission(): Boolean {
        val ctx = getApplication<Application>()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.BLUETOOTH_CONNECT) ==
                    PackageManager.PERMISSION_GRANTED
        } else true
    }

    // -- BLE Scan -------------------------------------------------------------

    /**
     * Start a BLE scan and show a device-picker sheet in the UI.
     * Called when the user taps CONNECT while disconnected.
     */
    fun startScan() {
        val adapter = bluetoothAdapter
        if (adapter == null)          { setError("Bluetooth not available."); return }
        if (!adapter.isEnabled)       { setError("Bluetooth is disabled."); return }
        if (!hasScanPermission())     { setError("Bluetooth scan permission not granted."); return }
        if (_uiState.value.status == EspConnectionStatus.SCANNING) return

        _uiState.update { it.copy(
            status           = EspConnectionStatus.SCANNING,
            scannedDevices   = emptyList(),
            showDevicePicker = true,
            errorMessage     = null
        )}

        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        adapter.bluetoothLeScanner?.startScan(null, settings, leScanCallback)
            ?: run { setError("BLE scanner unavailable."); return }

        // auto-stop after timeout
        scanTimeoutJob = viewModelScope.launch {
            delay(SCAN_TIMEOUT_MS)
            if (_uiState.value.status == EspConnectionStatus.SCANNING) {
                stopScan()
                if (_uiState.value.scannedDevices.isEmpty()) {
                    setError("No BLE devices found nearby.")
                }
            }
        }
    }

    private fun stopScan() {
        scanTimeoutJob?.cancel()
        try { bluetoothAdapter?.bluetoothLeScanner?.stopScan(leScanCallback) } catch (_: Exception) {}
        if (_uiState.value.status == EspConnectionStatus.SCANNING) {
            _uiState.update { it.copy(status = EspConnectionStatus.DISCONNECTED) }
        }
    }

    private val leScanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val name    = result.device.name?.takeIf { it.isNotBlank() } ?: return
            val address = result.device.address
            val item    = BleDeviceItem(name = name, address = address)

            _uiState.update { state ->
                if (state.scannedDevices.any { it.address == address }) state
                else state.copy(scannedDevices = state.scannedDevices + item)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            setError("BLE scan failed (code $errorCode).")
        }
    }

    // -- Connect to a specific device -----------------------------------------

    /** Called when the user picks a device from the in-app picker. */
    fun connectToDevice(address: String) {
        stopScan()
        if (!hasConnectPermission()) { setError("Bluetooth connect permission not granted."); return }

        val device: BluetoothDevice = try {
            bluetoothAdapter?.getRemoteDevice(address)
                ?: run { setError("Could not find device."); return }
        } catch (e: IllegalArgumentException) {
            setError("Invalid BLE address."); return
        }

        _uiState.update { it.copy(
            status           = EspConnectionStatus.CONNECTING,
            showDevicePicker = false,
            errorMessage     = null,
            deviceName       = device.name ?: address
        )}

        gatt = device.connectGatt(
            getApplication(),
            /* autoConnect = */ false,
            gattCallback,
            BluetoothDevice.TRANSPORT_LE
        )
    }

    /** Dismiss the picker without connecting. */
    fun cancelScan() {
        stopScan()
        _uiState.update { it.copy(showDevicePicker = false) }
    }

    // -- GATT callback --------------------------------------------------------

    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            when (newState) {
                BluetoothGatt.STATE_CONNECTED -> {
                    Log.i("EspBLE", "GATT connected -- discovering services...")
                    gatt.discoverServices()
                }
                BluetoothGatt.STATE_DISCONNECTED -> {
                    Log.i("EspBLE", "GATT disconnected (status=$status)")
                    txChar = null
                    val wasConnected = _uiState.value.status == EspConnectionStatus.CONNECTED
                    _uiState.update { it.copy(
                        status       = if (wasConnected) EspConnectionStatus.ERROR
                        else EspConnectionStatus.DISCONNECTED,
                        errorMessage = if (wasConnected) "Connection lost." else null
                    )}
                    gatt.close()
                }
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                setError("Service discovery failed (status=$status)."); gatt.disconnect(); return
            }

            val nusService = gatt.getService(NUS_SERVICE_UUID)
            if (nusService == null) {
                setError("NUS service not found on device."); gatt.disconnect(); return
            }

            txChar = nusService.getCharacteristic(NUS_TX_CHAR_UUID)
            if (txChar == null) {
                setError("NUS TX characteristic not found."); gatt.disconnect(); return
            }

            Log.i("EspBLE", "NUS service found -- connected!")
            _uiState.update { it.copy(status = EspConnectionStatus.CONNECTED, errorMessage = null) }
        }

        override fun onCharacteristicWrite(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            if (status != BluetoothGatt.GATT_SUCCESS) {
                Log.e("EspBLE", "Write failed: status=$status")
            }
        }
    }

    // -- Public API -----------------------------------------------------------

    /**
     * Entry-point called by the CONNECT button.
     * Starts BLE scan -> shows device picker in UI.
     */
    fun connect() = startScan()

    fun disconnect() {
        stopScan()
        txChar = null
        try { gatt?.disconnect(); gatt?.close() } catch (_: Exception) {}
        gatt = null
        _uiState.update { it.copy(
            status           = EspConnectionStatus.DISCONNECTED,
            showDevicePicker = false,
            errorMessage     = null
        )}
    }

    fun sendPayload(payload: String) {
        if (payload.isBlank()) return
        if (payload == _uiState.value.lastPayloadSent) return

        val newLog = (_uiState.value.payloadLog + payload).takeLast(60)
        _uiState.update { it.copy(
            lastPayloadSent = payload,
            lastPayloadAt   = System.currentTimeMillis(),
            payloadLog      = newLog
        )}

        val char = txChar ?: return
        if (_uiState.value.status != EspConnectionStatus.CONNECTED) return

        val data = if (payload.endsWith("\n")) payload else "$payload\n"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            gatt?.writeCharacteristic(
                char,
                data.toByteArray(),
                BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            )
        } else {
            @Suppress("DEPRECATION")
            char.writeType = BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            @Suppress("DEPRECATION")
            char.value = data.toByteArray()
            @Suppress("DEPRECATION")
            gatt?.writeCharacteristic(char)
        }
    }

    fun clearLog() {
        _uiState.update { it.copy(payloadLog = emptyList()) }
    }

    private fun setError(msg: String) {
        _uiState.update { it.copy(status = EspConnectionStatus.ERROR, errorMessage = msg) }
    }

    override fun onCleared() {
        super.onCleared()
        disconnect()
    }
}