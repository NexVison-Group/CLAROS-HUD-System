package com.daklok.claroshudsystem.ble

import android.Manifest
import android.app.Application
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.OutputStream
import java.util.UUID

/**
 * Bluetooth Classic (SPP) device name to match.
 */
const val ESP32_DEVICE_NAME = "ESP32-HUD"

/**
 * Standard SPP UUID for Bluetooth Classic Serial Port Profile.
 */
private val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

// ─────────────────────────────────────────────────────────────────────────────

enum class EspConnectionStatus {
    DISCONNECTED,
    SCANNING,
    CONNECTING,
    CONNECTED,
    ERROR
}

data class EspConnectionUiState(
    val status: EspConnectionStatus = EspConnectionStatus.DISCONNECTED,
    val deviceName: String = ESP32_DEVICE_NAME,
    val lastPayloadSent: String = "",
    val lastPayloadAt: Long = 0L,
    val payloadLog: List<String> = emptyList(),
    val errorMessage: String? = null
)

// ─────────────────────────────────────────────────────────────────────────────

/**
 * Bluetooth Classic (SPP) implementation.
 *
 * Flow:
 *  1. [connect] — searches bonded devices for "HUD-ESP32".
 *  2. Found → opens RFCOMM socket and attempts to [connect] on IO thread.
 *  3. Success → sets status to CONNECTED, opens OutputStream.
 *  4. [sendPayload] — writes data to the OutputStream.
 *  5. [disconnect] — closes socket and stream.
 */
class EspConnectionViewModel(application: Application) : AndroidViewModel(application) {

    private val _uiState = MutableStateFlow(EspConnectionUiState())
    val uiState: StateFlow<EspConnectionUiState> = _uiState.asStateFlow()

    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        getApplication<Application>()
            .getSystemService(BluetoothManager::class.java)
            ?.adapter
    }

    private var bluetoothSocket: BluetoothSocket? = null
    private var outputStream: OutputStream? = null
    private var connectionJob: Job? = null

    // ── Permissions ───────────────────────────────────────────────────────────

    private fun hasConnectPermission(): Boolean {
        val ctx = getApplication<Application>()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.BLUETOOTH_CONNECT) ==
                    PackageManager.PERMISSION_GRANTED
        } else {
            // Below Android 12, BLUETOOTH and BLUETOOTH_ADMIN are usually enough,
            // and they are declared in the manifest.
            true
        }
    }

    // ── Public API ────────────────────────────────────────────────────────────

    fun connect() {
        val status = _uiState.value.status
        if (status == EspConnectionStatus.CONNECTING || status == EspConnectionStatus.CONNECTED) return

        val adapter = bluetoothAdapter
        if (adapter == null) { setError("Bluetooth not available."); return }
        if (!adapter.isEnabled) { setError("Bluetooth is disabled."); return }
        if (!hasConnectPermission()) { setError("Bluetooth permission not granted."); return }

        _uiState.value = _uiState.value.copy(
            status = EspConnectionStatus.CONNECTING,
            errorMessage = null
        )

        connectionJob = viewModelScope.launch {
            try {
                val pairedDevices = adapter.bondedDevices
                val device = pairedDevices.find { it.name == ESP32_DEVICE_NAME }

                if (device != null) {
                    withContext(Dispatchers.IO) {
                        val socket = device.createRfcommSocketToServiceRecord(SPP_UUID)
                        socket.connect()
                        bluetoothSocket = socket
                        outputStream = socket.outputStream
                    }
                    Log.i("EspBT", "Connected to $ESP32_DEVICE_NAME")
                    _uiState.value = _uiState.value.copy(status = EspConnectionStatus.CONNECTED)
                } else {
                    setError("Device \"$ESP32_DEVICE_NAME\" not paired in settings.")
                }
            } catch (e: SecurityException) {
                setError("Bluetooth connect permission denied.")
            } catch (e: IOException) {
                Log.e("EspBT", "Connection failed: ${e.message}")
                setError("Connection failed. Is the device on and in range?")
                closeConnection()
            } catch (e: Exception) {
                setError("Unexpected error: ${e.message}")
                closeConnection()
            }
        }
    }

    fun disconnect() {
        connectionJob?.cancel()
        closeConnection()
        _uiState.value = _uiState.value.copy(
            status = EspConnectionStatus.DISCONNECTED,
            errorMessage = null
        )
    }

    private fun closeConnection() {
        try {
            outputStream?.close()
            bluetoothSocket?.close()
        } catch (_: Exception) {}
        outputStream = null
        bluetoothSocket = null
    }

    private fun setError(msg: String) {
        _uiState.value = _uiState.value.copy(status = EspConnectionStatus.ERROR, errorMessage = msg)
    }

    // ── Payload log ───────────────────────────────────────────────────────────

    fun sendPayload(payload: String) {
        if (payload.isBlank()) return
        if (payload == _uiState.value.lastPayloadSent) return
        
        val newLog = (_uiState.value.payloadLog + payload).takeLast(60)
        _uiState.value = _uiState.value.copy(
            lastPayloadSent = payload,
            lastPayloadAt = System.currentTimeMillis(),
            payloadLog = newLog
        )
        
        val stream = outputStream
        if (stream != null && _uiState.value.status == EspConnectionStatus.CONNECTED) {
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    // Append a newline as is common for serial protocols (SPP)
                    val data = if (payload.endsWith("\n")) payload else "$payload\n"
                    stream.write(data.toByteArray())
                    stream.flush()
                } catch (e: IOException) {
                    Log.e("EspBT", "Write failed: ${e.message}")
                    withContext(Dispatchers.Main) {
                        setError("Connection lost.")
                        closeConnection()
                    }
                }
            }
        }
    }

    fun clearLog() {
        _uiState.value = _uiState.value.copy(payloadLog = emptyList())
    }

    override fun onCleared() {
        super.onCleared()
        disconnect()
    }
}
