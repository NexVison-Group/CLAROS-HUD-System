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
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.pm.PackageManager
import android.os.Build
import android.os.ParcelUuid
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
private const val SCAN_TIMEOUT_MS = 15_000L

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
 *
 * FIX: scan using TWO parallel callbacks:
 *   a) Filtered scan by NUS service UUID — catches devices that advertise
 *      the service UUID but may NOT include a local name in the ad packet.
 *   b) Unfiltered scan — catches everything else (name in scan response).
 * Both feeds are merged into the same device list.
 * Devices with no name fall back to showing their MAC address so the user
 * can still identify and pick them.
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
     *
     * We run TWO scans simultaneously:
     *  1. Filtered by NUS service UUID — catches ESP32 devices that advertise
     *     the service UUID in their ad packet (even without a name).
     *  2. Unfiltered — catches devices whose name is only in the scan response.
     *
     * Both callbacks feed into [addScannedDevice].
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

        val scanner = adapter.bluetoothLeScanner
        if (scanner == null) { setError("BLE scanner unavailable."); return }

        // Scan 1: filtered by NUS service UUID (catches devices advertising the UUID
        // without a name — very common on ESP32 with certain BLE libraries)
        val nusFilter = ScanFilter.Builder()
            .setServiceUuid(ParcelUuid(NUS_SERVICE_UUID))
            .build()
        try {
            scanner.startScan(listOf(nusFilter), settings, filteredScanCallback)
            Log.i("EspBLE", "Started filtered (NUS UUID) scan")
        } catch (e: Exception) {
            Log.w("EspBLE", "Filtered scan start failed: ${e.message}")
        }

        // Scan 2: unfiltered — catches everything (name in scan response, etc.)
        try {
            scanner.startScan(null, settings, unfilteredScanCallback)
            Log.i("EspBLE", "Started unfiltered scan")
        } catch (e: Exception) {
            Log.w("EspBLE", "Unfiltered scan start failed: ${e.message}")
        }

        // auto-stop after timeout
        scanTimeoutJob = viewModelScope.launch {
            delay(SCAN_TIMEOUT_MS)
            if (_uiState.value.status == EspConnectionStatus.SCANNING) {
                stopScan()
                if (_uiState.value.scannedDevices.isEmpty()) {
                    setError("No BLE devices found nearby. Make sure the ESP32-HUD is powered on.")
                }
            }
        }
    }

    private fun stopScan() {
        scanTimeoutJob?.cancel()
        val scanner = bluetoothAdapter?.bluetoothLeScanner
        try { scanner?.stopScan(filteredScanCallback) } catch (_: Exception) {}
        try { scanner?.stopScan(unfilteredScanCallback) } catch (_: Exception) {}
        if (_uiState.value.status == EspConnectionStatus.SCANNING) {
            _uiState.update { it.copy(status = EspConnectionStatus.DISCONNECTED) }
        }
    }

    /**
     * Extract the best available display name from a scan result.
     *
     * Priority:
     *  1. device.name  (populated if the device included a Complete/Shortened Local Name
     *     in either the advertising packet or the scan response)
     *  2. ScanRecord.deviceName  (same source, but sometimes populated when device.name isn't)
     *  3. The MAC address as a readable fallback so the user can still pick the device.
     */
    private fun bestName(result: ScanResult): String {
        result.device.name?.takeIf { it.isNotBlank() }?.let { return it }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            result.scanRecord?.deviceName?.takeIf { it.isNotBlank() }?.let { return it }
        }
        return result.device.address   // MAC as last-resort label
    }

    /**
     * Add (or update) a scanned device in the state list.
     * Prefers the entry with the most informative name — so if the filtered
     * scan found the device by UUID (with only a MAC label) and later the
     * unfiltered scan returns the same address with a proper name, we upgrade.
     */
    private fun addScannedDevice(result: ScanResult) {
        val address = result.device.address
        val name    = bestName(result)
        val item    = BleDeviceItem(name = name, address = address)

        _uiState.update { state ->
            val existing = state.scannedDevices.find { it.address == address }
            when {
                existing == null ->
                    // New device — add it
                    state.copy(scannedDevices = state.scannedDevices + item)
                existing.name == address && name != address ->
                    // We previously only had a MAC; now we have a real name — upgrade
                    state.copy(scannedDevices = state.scannedDevices.map {
                        if (it.address == address) item else it
                    })
                else -> state  // No improvement — keep existing entry
            }
        }
    }

    /** Callback for the filtered (NUS UUID) scan. */
    private val filteredScanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            Log.d("EspBLE", "Filtered hit: addr=${result.device.address} name=${result.device.name}")
            addScannedDevice(result)
        }
        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            results.forEach { addScannedDevice(it) }
        }
        override fun onScanFailed(errorCode: Int) {
            Log.w("EspBLE", "Filtered scan failed (code=$errorCode)")
            // Don't setError here — unfiltered scan may still be running
        }
    }

    /** Callback for the unfiltered scan. */
    private val unfilteredScanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            // Only add devices that look like they could be our target:
            // either they have a real name, or the filtered scan already
            // found them by NUS UUID (address already in the list).
            val address = result.device.address
            val name    = bestName(result)
            val alreadyFoundByUuid = _uiState.value.scannedDevices.any { it.address == address }

            if (name != address || alreadyFoundByUuid) {
                addScannedDevice(result)
            }
        }
        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            results.forEach { r ->
                val address = r.device.address
                val name    = bestName(r)
                val alreadyFound = _uiState.value.scannedDevices.any { it.address == address }
                if (name != address || alreadyFound) addScannedDevice(r)
            }
        }
        override fun onScanFailed(errorCode: Int) {
            Log.w("EspBLE", "Unfiltered scan failed (code=$errorCode)")
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

        val displayName = _uiState.value.scannedDevices
            .find { it.address == address }?.name
            ?.takeIf { it != address }  // prefer real name over MAC
            ?: device.name
            ?: address

        _uiState.update { it.copy(
            status           = EspConnectionStatus.CONNECTING,
            showDevicePicker = false,
            errorMessage     = null,
            deviceName       = displayName
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