package io.redi.robot.platform.transport.ble.gatt

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGatt.GATT_SUCCESS
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothDevice
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import io.redi.robot.body.looi.connection.ConnectionFailure
import io.redi.robot.body.looi.connection.ConnectionTarget
import io.redi.robot.body.looi.connection.GattOperation
import io.redi.robot.body.looi.connection.GattTransportAdapter
import io.redi.robot.body.looi.connection.GattCharacteristicInventory
import io.redi.robot.body.looi.connection.GattServiceInventory
import io.redi.robot.body.looi.connection.GattTransportDiagnosticEvent
import io.redi.robot.body.looi.connection.GattTransportDiagnosticsObserver
import io.redi.robot.body.looi.connection.GattTransportEvent
import io.redi.robot.body.looi.connection.GattTransportObserver
import io.redi.robot.body.looi.connection.GattNotificationEnvelope
import io.redi.robot.body.looi.connection.GattNotificationObserver
import io.redi.robot.body.looi.connection.ConnectionFailure.Unknown
import io.redi.robot.body.looi.connection.mapGattStatusToFailure
import io.redi.robot.body.looi.ble.queue.ActiveGattOperationTransport
import io.redi.robot.body.looi.ble.queue.GattOperationFailure
import io.redi.robot.body.looi.ble.queue.GattOperationFailureResult
import io.redi.robot.body.looi.ble.queue.GattOperationResult
import io.redi.robot.body.looi.ble.queue.GattOperationSuccess
import io.redi.robot.body.looi.ble.queue.GattQueueFailureReason
import io.redi.robot.body.looi.ble.queue.GattQueuedOperationRequest
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

private const val ANDROID_BLUETOOTH_CONNECT_PERMISSION = "android.permission.BLUETOOTH_CONNECT"

class AndroidGattTransportAdapter(
    private val context: Context,
    private val connectPermissionCheck: () -> Boolean = {
        Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            context.checkSelfPermission(ANDROID_BLUETOOTH_CONNECT_PERMISSION) == PackageManager.PERMISSION_GRANTED
    },
    private val bluetoothEnabledCheck: () -> Boolean = {
        val manager = context.getSystemService(BluetoothManager::class.java)
        manager?.adapter?.isEnabled == true
    },
    private val bluetoothManagerProvider: () -> BluetoothManager? = {
        context.getSystemService(BluetoothManager::class.java)
    },
    private val remoteDeviceProvider: (BluetoothAdapter, String) -> BluetoothDevice = { adapter, address ->
        adapter.getRemoteDevice(address)
    },
    private val connectCaller: (BluetoothDevice, BluetoothGattCallback) -> BluetoothGatt? = { device, callback ->
        @SuppressLint("MissingPermission")
        device.connectGatt(context, false, callback)
    },
    private val closeGatt: (BluetoothGatt) -> Unit = { gatt ->
        @SuppressLint("MissingPermission")
        gatt.close()
    },
    private val diagnosticsObserver: GattTransportDiagnosticsObserver? = null,
    private val notificationObserver: GattNotificationObserver? = null,
) : GattTransportAdapter {

    private val attempts = HashMap<Long, ActiveGattAttempt>()
    private val lock = Any()

    override fun connect(target: ConnectionTarget, generation: Long, callback: GattTransportObserver) {
        if (!connectPermissionCheck()) {
            callback.onTransportEvent(
                GattTransportEvent.Failure(
                    generation = generation,
                    failure = Unknown("Missing BLUETOOTH_CONNECT permission", null),
                ),
            )
            return
        }

        if (!bluetoothEnabledCheck()) {
            callback.onTransportEvent(
                GattTransportEvent.Failure(
                    generation = generation,
                    failure = Unknown("Bluetooth is disabled"),
                ),
            )
            return
        }

        val manager = bluetoothManagerProvider()
        if (manager == null) {
            callback.onTransportEvent(
                GattTransportEvent.Failure(
                    generation = generation,
                    failure = Unknown("Bluetooth manager unavailable"),
                ),
            )
            return
        }

        val bluetoothAdapter = manager.adapter
        if (bluetoothAdapter == null) {
            callback.onTransportEvent(
                GattTransportEvent.Failure(
                    generation = generation,
                    failure = Unknown("Bluetooth adapter unavailable"),
                ),
            )
            return
        }

        val address = target.deviceId
        if (!BluetoothAdapter.checkBluetoothAddress(address)) {
            callback.onTransportEvent(
                GattTransportEvent.Failure(
                    generation = generation,
                    failure = Unknown("Invalid BLE address"),
                ),
            )
            return
        }

        val remoteDevice = runCatching { remoteDeviceProvider(bluetoothAdapter, address) }.getOrElse { error ->
            callback.onTransportEvent(
                GattTransportEvent.Failure(
                    generation = generation,
                    failure = Unknown("Unable to resolve target device", error),
                ),
            )
            return
        }

        val attempt = ActiveGattAttempt(generation, callback, this)
        synchronized(lock) {
            if (attempts.containsKey(generation)) {
                return
            }
            attempts[generation] = attempt
        }
        attempt.connect(remoteDevice)
    }

    override fun disconnect(generation: Long) {
        val attempt = synchronized(lock) {
            attempts.remove(generation)
        } ?: return
        attempt.requestDisconnect()
    }

    /** Project-owned queue transport for the current generation; Android objects never cross this API. */
    fun operationTransportForGeneration(generation: Long): ActiveGattOperationTransport? = synchronized(lock) {
        attempts[generation]?.operationTransport()
    }

    private fun removeAttempt(generation: Long) {
        synchronized(lock) {
            attempts.remove(generation)
        }
    }

    private fun isCurrentAttempt(generation: Long, attempt: ActiveGattAttempt): Boolean = synchronized(lock) {
        attempts[generation] === attempt
    }

    private data class PendingOperation(
        val request: GattQueuedOperationRequest,
        val completion: (GattOperationResult) -> Unit,
    )

    private inner class ActiveGattAttempt(
        private val generation: Long,
        private val observer: GattTransportObserver,
        private val transport: AndroidGattTransportAdapter,
    ) {
        private val callback = TransportCallback(generation)
        private val translator = GattTransportCallbackTranslator(generation)
        private val closed = AtomicBoolean(false)
        private val discoveringServices = AtomicBoolean(false)
        private val gattLock = Any()
        private var currentGatt: BluetoothGatt? = null
        private var pendingOperation: PendingOperation? = null

        fun operationTransport(): ActiveGattOperationTransport = object : ActiveGattOperationTransport {
            override val generation: Long get() = this@ActiveGattAttempt.generation
            override fun isAvailable(): Boolean = isCurrentAttempt() && !closed.get() && currentGatt() != null
            override fun dispatch(operation: GattQueuedOperationRequest, onResult: (GattOperationResult) -> Unit) {
                dispatchOperation(operation, onResult)
            }
        }

        fun connect(device: BluetoothDevice) {
            observer.onTransportEvent(GattTransportEvent.Connecting(generation))
            if (closed.get()) return

            val gatt = runCatching { connectCaller(device, callback) }.getOrElse { error ->
                when (error) {
                    is SecurityException ->
                        emitFailure(Unknown("SecurityException during connect", error))
                    else ->
                        emitFailure(Unknown("Connect initiation failed", error))
                }
                return
            }
            if (gatt == null) {
                emitFailure(Unknown("connectGatt returned null"))
                return
            }

            synchronized(gattLock) {
                if (closed.get()) {
                    closeGatt(gatt)
                    return
                }
                currentGatt = gatt
            }
        }

        private fun isCurrentAttempt(): Boolean = transport.isCurrentAttempt(generation, this)

        private fun currentGatt(): BluetoothGatt? = synchronized(gattLock) { currentGatt }

        private fun clearGatt(): BluetoothGatt? = synchronized(gattLock) {
            val gatt = currentGatt
            currentGatt = null
            gatt
        }

        @SuppressLint("MissingPermission")
        private fun dispatchOperation(request: GattQueuedOperationRequest, completion: (GattOperationResult) -> Unit) {
            if (!isCurrentAttempt() || closed.get()) return completion(failure("GATT session unavailable"))
            if (request.generation != generation) return completion(failure("stale operation generation"))
            if (!connectPermissionCheck()) return completion(failure("Missing BLUETOOTH_CONNECT permission"))
            val gatt = currentGatt() ?: return completion(failure("GATT session unavailable"))
            if (pendingOperation != null) return completion(failure("operation already in flight"))
            val service = runCatching { gatt.getService(UUID.fromString(request.serviceUuid)) }.getOrNull()
                ?: return completion(failure("service not found"))
            val characteristic = service.getCharacteristic(UUID.fromString(request.characteristicUuid))
                ?: return completion(failure("characteristic not found"))
            pendingOperation = PendingOperation(request, completion)
            val startedResult = runCatching {
                when (request.kind) {
                    io.redi.robot.body.looi.ble.queue.GattOperationKind.READ -> gatt.readCharacteristic(characteristic)
                    io.redi.robot.body.looi.ble.queue.GattOperationKind.WRITE -> {
                        characteristic.value = request.payload.map(Int::toByte).toByteArray()
                        gatt.writeCharacteristic(characteristic)
                    }
                    io.redi.robot.body.looi.ble.queue.GattOperationKind.DESCRIPTOR_WRITE -> {
                        val descriptor = characteristic.getDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")) ?: return@runCatching false
                        if (!gatt.setCharacteristicNotification(characteristic, request.payload == listOf(1, 0))) return@runCatching false
                        descriptor.value = request.payload.map(Int::toByte).toByteArray()
                        gatt.writeDescriptor(descriptor)
                    }
                    io.redi.robot.body.looi.ble.queue.GattOperationKind.DESCRIPTOR_READ -> false
                }
            }
            val started = startedResult.getOrElse { error ->
                completePending(
                    failure(
                        if (error is SecurityException) "BLUETOOTH_CONNECT permission was revoked" else "Android GATT operation failed to start",
                    ),
                )
                return
            }
            if (!started) completePending(failure("Android GATT operation was not started"))
        }

        private fun completePending(result: GattOperationResult) {
            val pending = pendingOperation ?: return
            pendingOperation = null
            pending.completion(result)
        }

        private fun failure(detail: String) = GattOperationFailureResult(
            GattOperationFailure(GattQueueFailureReason.TRANSPORT_ERROR, detail),
        )

        fun requestDisconnect() {
            if (!closed.compareAndSet(false, true)) return
            completePending(failure("GATT session disconnected"))

            val gatt = clearGatt()
            if (gatt == null) {
                observer.onTransportEvent(GattTransportEvent.Disconnected(generation))
                transport.removeAttempt(generation)
                translator.close()
                return
            }

            val disconnectError = runCatching {
                @SuppressLint("MissingPermission")
                gatt.disconnect()
            }.exceptionOrNull()
            runCatching {
                @SuppressLint("MissingPermission")
                closeGatt(gatt)
            }
            diagnosticsObserver?.onDiagnosticEvent(
                GattTransportDiagnosticEvent.GattClosed(generation, "disconnect requested"),
            )
            translator.close()
            transport.removeAttempt(generation)

            when {
                disconnectError is SecurityException -> {
                    observer.onTransportEvent(
                        GattTransportEvent.Failure(
                            generation = generation,
                            failure = Unknown("SecurityException during disconnect", disconnectError),
                        ),
                    )
                }

                disconnectError != null -> {
                    observer.onTransportEvent(
                        GattTransportEvent.Failure(
                            generation = generation,
                            failure = Unknown("Disconnect failed", disconnectError),
                        ),
                    )
                }

                else -> {
                    observer.onTransportEvent(GattTransportEvent.Disconnected(generation))
                }
            }
        }

        private fun emitFailure(failure: ConnectionFailure) {
            if (!closed.compareAndSet(false, true)) return
            completePending(this@ActiveGattAttempt.failure("GATT transport failure"))
            translator.close()
            runCatching { currentGatt()?.let { closeGatt(it) } }
            diagnosticsObserver?.onDiagnosticEvent(
                GattTransportDiagnosticEvent.GattClosed(generation, "transport failure"),
            )
            transport.removeAttempt(generation)
            observer.onTransportEvent(GattTransportEvent.Failure(generation = generation, failure = failure))
        }

        private fun emitDisconnected() {
            if (!closed.compareAndSet(false, true)) return
            completePending(failure("GATT session disconnected"))
            transport.removeAttempt(generation)
            translator.close()
            observer.onTransportEvent(GattTransportEvent.Disconnected(generation))
            runCatching { currentGatt()?.let { closeGatt(it) } }
            diagnosticsObserver?.onDiagnosticEvent(
                GattTransportDiagnosticEvent.GattClosed(generation, "disconnect callback"),
            )
        }

        private fun discoverServicesIfNeeded(gatt: BluetoothGatt) {
            if (!discoveringServices.compareAndSet(false, true)) return
            val discovered = runCatching {
                @SuppressLint("MissingPermission")
                gatt.discoverServices()
            }.getOrElse { error ->
                emitFailure(
                    ConnectionFailure.Unknown("Discover services request failed", error),
                )
                false
            }
            if (!discovered) {
                emitFailure(
                    ConnectionFailure.Unknown("discoverServices returned false"),
                )
            } else {
                diagnosticsObserver?.onDiagnosticEvent(
                    GattTransportDiagnosticEvent.ServiceDiscoveryStarted(generation),
                )
            }
        }

        private fun handleConnectionStateChanged(status: Int, newState: Int) {
            if (!isCurrentAttempt() || closed.get()) return

            val event = translator.onConnectionStateChange(status, newState) ?: return
            observer.onTransportEvent(event)

            when (event) {
                is GattTransportEvent.Connected -> {
                    val gatt = currentGatt()
                    if (gatt == null) {
                        emitFailure(ConnectionFailure.Unknown("Missing GATT reference on connected event"))
                    } else {
                        discoverServicesIfNeeded(gatt)
                    }
                }

                is GattTransportEvent.Disconnected -> emitDisconnected()
                is GattTransportEvent.Failure -> emitFailure(event.failure)
                is GattTransportEvent.Connecting,
                is GattTransportEvent.Ready -> {}
            }
        }

        private fun handleServicesDiscovered(status: Int) {
            if (!isCurrentAttempt() || closed.get()) return

            val event = translator.onServicesDiscovered(status) ?: return
            observer.onTransportEvent(event)

            if (event is GattTransportEvent.Failure) {
                emitFailure(event.failure)
            }
        }

        private inner class TransportCallback(private val callbackGeneration: Long) : BluetoothGattCallback() {
            @Deprecated("Deprecated in API 33")
            override fun onCharacteristicChanged(
                gatt: BluetoothGatt?,
                characteristic: android.bluetooth.BluetoothGattCharacteristic?,
            ) {
                reportNotification(characteristic, characteristic?.value ?: return)
            }

            override fun onCharacteristicChanged(
                gatt: BluetoothGatt,
                characteristic: android.bluetooth.BluetoothGattCharacteristic,
                value: ByteArray,
            ) {
                reportNotification(characteristic, value)
            }

            override fun onCharacteristicRead(gatt: BluetoothGatt?, characteristic: android.bluetooth.BluetoothGattCharacteristic?, status: Int) {
                if (!isCurrentAttempt() || closed.get() || pendingOperation?.request?.kind != io.redi.robot.body.looi.ble.queue.GattOperationKind.READ) return
                completePending(if (status == GATT_SUCCESS) GattOperationSuccess(characteristic?.value?.map { it.toInt() and 0xff }) else failure("read status=$status"))
            }

            override fun onCharacteristicWrite(gatt: BluetoothGatt?, characteristic: android.bluetooth.BluetoothGattCharacteristic?, status: Int) {
                if (!isCurrentAttempt() || closed.get() || pendingOperation?.request?.kind != io.redi.robot.body.looi.ble.queue.GattOperationKind.WRITE) return
                completePending(if (status == GATT_SUCCESS) GattOperationSuccess() else failure("write status=$status"))
            }

            override fun onDescriptorWrite(gatt: BluetoothGatt?, descriptor: android.bluetooth.BluetoothGattDescriptor?, status: Int) {
                if (!isCurrentAttempt() || closed.get() || pendingOperation?.request?.kind != io.redi.robot.body.looi.ble.queue.GattOperationKind.DESCRIPTOR_WRITE) return
                completePending(if (status == GATT_SUCCESS) GattOperationSuccess() else failure("descriptor status=$status"))
            }
            override fun onConnectionStateChange(gatt: BluetoothGatt?, status: Int, newState: Int) {
                if (callbackGeneration != generation) return
                if (closed.get()) return
                if (!transport.isCurrentAttempt(callbackGeneration, this@ActiveGattAttempt)) return
                diagnosticsObserver?.onDiagnosticEvent(
                    GattTransportDiagnosticEvent.ConnectionStateChanged(generation, status, newState),
                )
                val activeGatt = gatt
                if (status == GATT_SUCCESS && activeGatt != null) {
                    synchronized(gattLock) {
                        currentGatt = activeGatt
                    }
                } else if (status != GATT_SUCCESS) {
                    emitFailure(
                        mapGattStatusToFailure(
                            status,
                            GattOperation.CONNECT,
                            "Connection callback failed",
                        ),
                    )
                    return
                } else {
                    emitFailure(ConnectionFailure.Unknown("Connection callback missing GATT reference"))
                    return
                }
                handleConnectionStateChanged(status, newState)
            }

            override fun onServicesDiscovered(gatt: BluetoothGatt?, status: Int) {
                if (callbackGeneration != generation) return
                if (closed.get()) return
                if (gatt == null) {
                    emitFailure(ConnectionFailure.Unknown("Services-discovered callback missing GATT reference"))
                    return
                }
                if (!transport.isCurrentAttempt(callbackGeneration, this@ActiveGattAttempt)) return
                diagnosticsObserver?.onDiagnosticEvent(
                    GattTransportDiagnosticEvent.ServicesDiscovered(
                        generation = generation,
                        status = status,
                        services = if (status == GATT_SUCCESS) inventoryFor(gatt) else emptyList(),
                    ),
                )
                if (status != GATT_SUCCESS) {
                    emitFailure(
                        mapGattStatusToFailure(
                            status,
                            GattOperation.READY_WAIT,
                            "Service discovery failed",
                        ),
                    )
                    return
                }
                synchronized(gattLock) {
                    currentGatt = gatt
                }
                handleServicesDiscovered(status)
            }

            private fun reportNotification(
                characteristic: android.bluetooth.BluetoothGattCharacteristic?,
                value: ByteArray,
            ) {
                if (callbackGeneration != generation || closed.get()) return
                if (!transport.isCurrentAttempt(callbackGeneration, this@ActiveGattAttempt)) return
                val serviceUuid = characteristic?.service?.uuid?.toString() ?: return
                val characteristicUuid = characteristic.uuid.toString()
                notificationObserver?.onNotification(
                    GattNotificationEnvelope(
                        generation = generation,
                        serviceUuid = serviceUuid,
                        characteristicUuid = characteristicUuid,
                        payload = value.map { it.toInt() and 0xff },
                    ),
                )
            }
        }

        @SuppressLint("MissingPermission")
        private fun inventoryFor(gatt: BluetoothGatt): List<GattServiceInventory> = gatt.services.map { service ->
            GattServiceInventory(
                uuid = service.uuid.toString(),
                characteristics = service.characteristics.map { characteristic ->
                    GattCharacteristicInventory(uuid = characteristic.uuid.toString())
                },
            )
        }

    }
}
