import 'package:flutter/foundation.dart';
import 'package:flutter/services.dart';

/// Moonfin-Auto's link to the Android Auto car screen (native CarScreen.kt).
///
/// While the app is drawing on the car, the native side reports how much
/// smaller than the phone's the car's pixel density is, as [uiScale], so the
/// app can lay out for the car (see CarUiScale). It also reports when the car
/// starts moving and when the car screen closes.
class CarScreenBridge {
  CarScreenBridge._();

  static const _channel = MethodChannel('org.moonfin.androidtv/car');

  /// Car density / phone density while on the car screen, otherwise null.
  static final ValueNotifier<double?> uiScale = ValueNotifier<double?>(null);

  static final List<Future<void> Function(String method, Object? arguments)>
      _listeners = [];
  static bool _initialized = false;

  static void ensureInitialized() {
    if (_initialized) return;
    _initialized = true;
    _channel.setMethodCallHandler((call) async {
      if (call.method == 'uiScale') {
        final value = call.arguments;
        uiScale.value = value is num && value > 0 ? value.toDouble() : null;
        return;
      }
      for (final listener in List.of(_listeners)) {
        await listener(call.method, call.arguments);
      }
    });
  }

  static void addListener(
    Future<void> Function(String method, Object? arguments) listener,
  ) {
    ensureInitialized();
    _listeners.add(listener);
  }
}
