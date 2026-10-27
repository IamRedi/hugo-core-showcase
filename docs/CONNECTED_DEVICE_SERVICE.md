# Connected-device service policy

T020 uses a user-started `connectedDevice` foreground service for a visible BLE session. The app does not
use Companion Device Manager yet because it has no completed device association flow. It does not auto-start
on boot, from a receiver, or after process recreation.

- Android 8+ starts the service with `startForegroundService`; it promotes immediately with a low-importance notification.
- Android 12+ starts it only from a visible user action; background starts are not a retry path.
- Android 14+ declares `connectedDevice`, `FOREGROUND_SERVICE`, and `FOREGROUND_SERVICE_CONNECTED_DEVICE`.
- Runtime `BLUETOOTH_CONNECT` remains required before connection; a foreground promotion security failure stops the service.
- Service stop, task removal, and destruction request an idempotent GATT disconnect. No target, protocol readiness, lease, or action is persisted.
- Camera and microphone are not service types and remain separate user-visible lifecycles.

References: [foreground services](https://developer.android.com/develop/background-work/services/fgs),
[launch restrictions](https://developer.android.com/develop/background-work/services/fgs/launch), and
[connected-device type](https://developer.android.com/develop/background-work/services/fgs/service-types#connected-device).
