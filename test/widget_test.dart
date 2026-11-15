import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:gamepad_x7/main.dart';

void main() {
  testWidgets('GamePad_X7 app launches smoke test', (WidgetTester tester) async {
    // El mando GamePad_X7 esta disenado exclusivamente en modo horizontal (Landscape).
    // Configuramos la resolucion de prueba en pantalla horizontal (p. ej. 840x390 - resolucion movil).
    tester.view.physicalSize = const Size(840, 390);
    tester.view.devicePixelRatio = 1.0;
    addTearDown(() {
      tester.view.resetPhysicalSize();
      tester.view.resetDevicePixelRatio();
    });

    // Construye la aplicacion GamePad_X7 y renderiza el primer cuadro.
    await tester.pumpWidget(const GamePadX7App());
    await tester.pump();

    // Verifica que el componente principal del mando este en pantalla.
    expect(find.byType(GamePadScreen), findsOneWidget);
  });
}
