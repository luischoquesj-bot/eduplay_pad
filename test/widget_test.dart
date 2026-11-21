import 'package:flutter/material.dart';
import 'package:flutter_test/flutter_test.dart';
import 'package:eduplay_pad/main.dart';

void main() {
  testWidgets('EduPlay Pad app launches smoke test', (WidgetTester tester) async {
    // El mando EduPlay Pad esta disenado exclusivamente en modo horizontal (Landscape).
    // Configuramos la resolucion de prueba en pantalla horizontal (p. ej. 840x390 - resolucion movil).
    tester.view.physicalSize = const Size(840, 390);
    tester.view.devicePixelRatio = 1.0;
    addTearDown(() {
      tester.view.resetPhysicalSize();
      tester.view.resetDevicePixelRatio();
    });

    // Construye la aplicacion EduPlay Pad y renderiza el primer cuadro.
    await tester.pumpWidget(const GamePadX7App());
    await tester.pump();

    // Verifica que el componente principal del mando este en pantalla.
    expect(find.byType(GamePadScreen), findsOneWidget);
  });
}
