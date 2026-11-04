import 'package:flutter/material.dart';

import '../platform/car_screen_bridge.dart';

/// Lays the app out at the car screen's own density while it is drawing on
/// Android Auto.
///
/// The car's Flutter view reports the phone's density, because the native
/// video player (a platform view owned by the phone's activity) is sized with
/// it. Laid out at that density the app would be enormous on the car, so this
/// lays it out on the canvas the car's density would give and scales it down.
/// Platform views undo the scale for themselves through UnscaledPlatformView,
/// the same way they do for TvUiScale.
class CarUiScale extends StatelessWidget {
  const CarUiScale({super.key, required this.child});

  final Widget child;

  @override
  Widget build(BuildContext context) {
    CarScreenBridge.ensureInitialized();
    return ValueListenableBuilder<double?>(
      valueListenable: CarScreenBridge.uiScale,
      child: child,
      builder: (context, factor, child) {
        if (factor == null || (factor - 1.0).abs() < 0.01) return child!;
        final mq = MediaQuery.of(context);
        final realSize = mq.size;
        final logicalSize = Size(realSize.width / factor, realSize.height / factor);
        EdgeInsets scaleInsets(EdgeInsets insets) => EdgeInsets.fromLTRB(
              insets.left / factor,
              insets.top / factor,
              insets.right / factor,
              insets.bottom / factor,
            );
        return FittedBox(
          fit: BoxFit.fill,
          child: SizedBox(
            width: logicalSize.width,
            height: logicalSize.height,
            child: MediaQuery(
              data: mq.copyWith(
                size: logicalSize,
                devicePixelRatio: mq.devicePixelRatio * factor,
                padding: scaleInsets(mq.padding),
                viewPadding: scaleInsets(mq.viewPadding),
                viewInsets: scaleInsets(mq.viewInsets),
              ),
              child: child!,
            ),
          ),
        );
      },
    );
  }
}
