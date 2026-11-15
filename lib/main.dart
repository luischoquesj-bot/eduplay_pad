import 'dart:async';
import 'dart:math' as math;
import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

void main() async {
  WidgetsFlutterBinding.ensureInitialized();
  await SystemChrome.setEnabledSystemUIMode(SystemUiMode.immersiveSticky);
  await SystemChrome.setPreferredOrientations([
    DeviceOrientation.landscapeLeft,
    DeviceOrientation.landscapeRight,
  ]);
  runApp(const GamePadX7App());
}

class GamePadX7App extends StatelessWidget {
  const GamePadX7App({super.key});

  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'GamePad_X7',
      debugShowCheckedModeBanner: false,
      theme: ThemeData.dark().copyWith(
        scaffoldBackgroundColor: const Color(0xFF161619),
        colorScheme: const ColorScheme.dark(
          primary: Color(0xFF4FC3F7),
          secondary: Color(0xFF00E676),
          surface: Color(0xFF26262B),
        ),
      ),
      home: const GamePadScreen(),
    );
  }
}

enum LeftControlMode { dpad, joystick }

enum PadAction {
  up,
  down,
  left,
  right,
  a,
  b,
  x,
  y,
  lb,
  rb,
  lt,
  rt,
  coin,
  select,
  start,
  extraZ,
}

class PlayerKeyMap {
  final int playerNumber;
  final Color accentColor;
  final Map<PadAction, int> codes;
  final Map<PadAction, String> keyLabels;

  const PlayerKeyMap({
    required this.playerNumber,
    required this.accentColor,
    required this.codes,
    required this.keyLabels,
  });

  int codeFor(PadAction action) => codes[action]!;
  String labelFor(PadAction action) => keyLabels[action] ?? '';
}

/// Mapeo Multimando P1 a P4 (sin colisiones entre jugadores y con COIN separado de SELECT)
const Map<int, PlayerKeyMap> kPlayerMappings = {
  1: PlayerKeyMap(
    playerNumber: 1,
    accentColor: Color(0xFF38BDF8),
    codes: {
      PadAction.up: 0x1A, // W
      PadAction.left: 0x04, // A
      PadAction.down: 0x16, // S
      PadAction.right: 0x07, // D
      PadAction.a: 0x0D, // J
      PadAction.b: 0x0E, // K
      PadAction.x: 0x0F, // L
      PadAction.y: 0x18, // U
      PadAction.lb: 0x14, // Q
      PadAction.rb: 0x08, // E
      PadAction.lt: 0x1E, // 1
      PadAction.rt: 0x1F, // 2
      PadAction.coin: 0x22, // 5 (Exclusivo COIN / Ficha)
      PadAction.select: 0x2A, // Backspace (Select / Pausa independiente)
      PadAction.start: 0x28, // Enter (Start)
      PadAction.extraZ: 0x2E, // = (Boton extra Z para N64 / Juegos Flutter)
    },
    keyLabels: {
      PadAction.up: 'W',
      PadAction.left: 'A',
      PadAction.down: 'S',
      PadAction.right: 'D',
      PadAction.a: 'J',
      PadAction.b: 'K',
      PadAction.x: 'L',
      PadAction.y: 'U',
      PadAction.lb: 'Q',
      PadAction.rb: 'E',
      PadAction.lt: '1',
      PadAction.rt: '2',
      PadAction.coin: '5',
      PadAction.select: 'SEL',
      PadAction.start: 'START',
      PadAction.extraZ: 'Z-N64',
    },
  ),
  2: PlayerKeyMap(
    playerNumber: 2,
    accentColor: Color(0xFF4ADE80),
    codes: {
      PadAction.up: 0x52, // Flecha Arriba
      PadAction.left: 0x50, // Flecha Izq
      PadAction.down: 0x51, // Flecha Abajo
      PadAction.right: 0x4F, // Flecha Der
      PadAction.a: 0x1D, // Z
      PadAction.b: 0x1B, // X
      PadAction.x: 0x06, // C
      PadAction.y: 0x19, // V
      PadAction.lb: 0x05, // B
      PadAction.rb: 0x11, // N
      PadAction.lt: 0x20, // 3
      PadAction.rt: 0x21, // 4
      PadAction.coin: 0x23, // 6 (Exclusivo COIN / Ficha)
      PadAction.select: 0x2B, // Tab (Select / Pausa independiente)
      PadAction.start: 0x2C, // Espacio (Start)
      PadAction.extraZ: 0x2F, // [ (Boton extra Z para N64 / Juegos Flutter)
    },
    keyLabels: {
      PadAction.up: '↑',
      PadAction.left: '←',
      PadAction.down: '↓',
      PadAction.right: '→',
      PadAction.a: 'Z',
      PadAction.b: 'X',
      PadAction.x: 'C',
      PadAction.y: 'V',
      PadAction.lb: 'B',
      PadAction.rb: 'N',
      PadAction.lt: '3',
      PadAction.rt: '4',
      PadAction.coin: '6',
      PadAction.select: 'SEL',
      PadAction.start: 'START',
      PadAction.extraZ: 'Z-N64',
    },
  ),
  3: PlayerKeyMap(
    playerNumber: 3,
    accentColor: Color(0xFFFBBF24),
    codes: {
      PadAction.up: 0x17, // T
      PadAction.left: 0x09, // F
      PadAction.down: 0x0A, // G
      PadAction.right: 0x0B, // H
      PadAction.a: 0x15, // R
      PadAction.b: 0x1C, // Y
      PadAction.x: 0x0C, // I
      PadAction.y: 0x12, // O
      PadAction.lb: 0x13, // P
      PadAction.rb: 0x10, // M
      PadAction.lt: 0x24, // 7
      PadAction.rt: 0x25, // 8
      PadAction.coin: 0x26, // 9 (Exclusivo COIN / Ficha)
      PadAction.select: 0x2D, // - (Select / Pausa independiente)
      PadAction.start: 0x27, // 0 (Start)
      PadAction.extraZ: 0x30, // ] (Boton extra Z para N64 / Juegos Flutter)
    },
    keyLabels: {
      PadAction.up: 'T',
      PadAction.left: 'F',
      PadAction.down: 'G',
      PadAction.right: 'H',
      PadAction.a: 'R',
      PadAction.b: 'Y',
      PadAction.x: 'I',
      PadAction.y: 'O',
      PadAction.lb: 'P',
      PadAction.rb: 'M',
      PadAction.lt: '7',
      PadAction.rt: '8',
      PadAction.coin: '9',
      PadAction.select: 'SEL',
      PadAction.start: 'START',
      PadAction.extraZ: 'Z-N64',
    },
  ),
  4: PlayerKeyMap(
    playerNumber: 4,
    accentColor: Color(0xFFF472B6),
    codes: {
      PadAction.up: 0x60, // Numpad 8
      PadAction.left: 0x5C, // Numpad 4
      PadAction.down: 0x5D, // Numpad 5
      PadAction.right: 0x5E, // Numpad 6
      PadAction.a: 0x59, // Numpad 1
      PadAction.b: 0x5A, // Numpad 2
      PadAction.x: 0x5B, // Numpad 3
      PadAction.y: 0x62, // Numpad 0
      PadAction.lb: 0x5F, // Numpad 7
      PadAction.rb: 0x61, // Numpad 9
      PadAction.lt: 0x55, // Numpad *
      PadAction.rt: 0x57, // Numpad +
      PadAction.coin: 0x56, // Numpad - (Exclusivo COIN / Ficha)
      PadAction.select: 0x54, // Numpad / (Select / Pausa independiente)
      PadAction.start: 0x58, // Numpad Enter (Start)
      PadAction.extraZ: 0x63, // Numpad . (Boton extra Z para N64 / Juegos Flutter)
    },
    keyLabels: {
      PadAction.up: 'NP8',
      PadAction.left: 'NP4',
      PadAction.down: 'NP5',
      PadAction.right: 'NP6',
      PadAction.a: 'NP1',
      PadAction.b: 'NP2',
      PadAction.x: 'NP3',
      PadAction.y: 'NP0',
      PadAction.lb: 'NP7',
      PadAction.rb: 'NP9',
      PadAction.lt: 'NP*',
      PadAction.rt: 'NP+',
      PadAction.coin: 'NP-',
      PadAction.select: 'SEL',
      PadAction.start: 'START',
      PadAction.extraZ: 'Z-N64',
    },
  ),
};

class GamePadScreen extends StatefulWidget {
  const GamePadScreen({super.key});

  @override
  State<GamePadScreen> createState() => _GamePadScreenState();
}

class _GamePadScreenState extends State<GamePadScreen>
    with WidgetsBindingObserver {
  static const MethodChannel _hidChannel = MethodChannel('com.magcubic.x7/hid');

  int _selectedPlayer = 1;
  LeftControlMode _leftMode = LeftControlMode.dpad;

  String? _connectedDeviceName;
  bool _isHidRegistered = false;
  List<Map<String, String>> _pairedDevices = [];
  Timer? _statusTimer;

  /// Estado de Pausa con efecto de parpadeo en rojo para el boton superior
  bool _isPaused = false;
  bool _pauseBlinkOn = false;
  Timer? _pauseBlinkTimer;

  /// Si es false (por defecto), el segundo boton superior envia SELECT y activa
  /// el indicador "EN PAUSA" parpadeando en rojo. Si una consola (ej. N64 Mario Kart
  /// o juego Flutter) requiere un boton extra adicional, puede conmutarse a Z-N64.
  bool _useTopAuxAsExtraZ = false;

  /// Multitouch real: asocia cada pointerId activo con las teclas HID que presiona
  final Map<int, Set<int>> _pointerActiveKeys = {};

  /// Rastrea que punteros estan presionando cada boton fisico individual en pantalla
  /// para que los botones de arriba y los del centro sean 100% independientes visualmente
  final Map<String, Set<int>> _buttonActivePointers = {};

  /// Conjunto consolidado de codigos HID activos en este instante
  Set<int> _activeKeys = <int>{};

  /// Estado visual del joystick izquierdo (offset normalizado -1..1)
  Offset _joystickNormalizedOffset = Offset.zero;
  int? _leftZonePointerId;

  /// Punteros activos en el rombo de botones derecho (Y, X, B, A)
  final Map<int, Set<PadAction>> _diamondPointerActions = {};

  PlayerKeyMap get _currentMap => kPlayerMappings[_selectedPlayer]!;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addObserver(this);
    _hidChannel.setMethodCallHandler(_handleNativeCallback);
    _initializeBluetoothHid();
    _statusTimer = Timer.periodic(const Duration(seconds: 3), (_) {
      _refreshConnectedDevice();
    });
  }

  @override
  void dispose() {
    WidgetsBinding.instance.removeObserver(this);
    _statusTimer?.cancel();
    _pauseBlinkTimer?.cancel();
    super.dispose();
  }

  void _togglePauseIndicator() {
    setState(() {
      _isPaused = !_isPaused;
      if (_isPaused) {
        _pauseBlinkOn = true;
        _pauseBlinkTimer?.cancel();
        _pauseBlinkTimer = Timer.periodic(
          const Duration(milliseconds: 450),
          (_) {
            if (!mounted || !_isPaused) return;
            setState(() {
              _pauseBlinkOn = !_pauseBlinkOn;
            });
          },
        );
      } else {
        _pauseBlinkOn = false;
        _pauseBlinkTimer?.cancel();
        _pauseBlinkTimer = null;
      }
    });
  }

  @override
  void didChangeAppLifecycleState(AppLifecycleState state) {
    if (state == AppLifecycleState.resumed) {
      SystemChrome.setEnabledSystemUIMode(SystemUiMode.immersiveSticky);
      _refreshConnectedDevice();
    } else if (state == AppLifecycleState.paused ||
        state == AppLifecycleState.inactive) {
      _clearAllInputs();
    }
  }

  Future<dynamic> _handleNativeCallback(MethodCall call) async {
    if (call.method == 'onConnectionChanged') {
      final args = call.arguments as Map?;
      if (!mounted) return;
      setState(() {
        _connectedDeviceName = args?['connectedDevice'] as String?;
        _isHidRegistered = (args?['isRegistered'] as bool?) ?? false;
      });
    }
  }

  Future<void> _initializeBluetoothHid({bool makeDiscoverable = false}) async {
    try {
      final granted =
          await _hidChannel.invokeMethod<bool>('requestPermissions') ?? false;
      if (!granted && makeDiscoverable) {
        return;
      }
      final res = await _hidChannel.invokeMapMethod<String, dynamic>(
        'initHid',
        {'discoverable': makeDiscoverable},
      );
      if (!mounted || res == null) return;
      final pairedRaw = res['pairedDevices'] as List?;
      setState(() {
        _connectedDeviceName = res['connectedDevice'] as String?;
        _isHidRegistered = (res['isRegistered'] as bool?) ?? false;
        if (pairedRaw != null) {
          _pairedDevices = pairedRaw
              .whereType<Map>()
              .map(
                (m) => {
                  'name': (m['name'] ?? 'Dispositivo').toString(),
                  'address': (m['address'] ?? '').toString(),
                },
              )
              .toList();
        }
      });
    } catch (_) {
      // Ignorar en entornos de prueba sin Bluetooth nativo
    }
  }

  Future<void> _refreshConnectedDevice() async {
    try {
      final name = await _hidChannel.invokeMethod<String>('getConnectedDevice');
      if (!mounted) return;
      if (name != _connectedDeviceName) {
        setState(() {
          _connectedDeviceName = name;
        });
      }
    } catch (_) {}
  }

  void _updatePointerKeys(
    int pointerId,
    Set<int> newKeys, {
    bool haptic = true,
  }) {
    final previousForPointer = _pointerActiveKeys[pointerId] ?? const <int>{};
    if (setEquals(previousForPointer, newKeys)) {
      return;
    }

    // Vibracion haptica instantanea cuando se activa al menos una tecla nueva
    if (haptic && newKeys.difference(previousForPointer).isNotEmpty) {
      HapticFeedback.lightImpact();
    }

    if (newKeys.isEmpty) {
      _pointerActiveKeys.remove(pointerId);
    } else {
      _pointerActiveKeys[pointerId] = newKeys;
    }

    _syncActiveKeysAndSend();
  }

  void _removePointer(int pointerId) {
    for (final pointerSet in _buttonActivePointers.values) {
      pointerSet.remove(pointerId);
    }
    if (_pointerActiveKeys.remove(pointerId) != null) {
      _syncActiveKeysAndSend();
    } else {
      setState(() {});
    }
  }

  void _clearAllInputs() {
    _pointerActiveKeys.clear();
    _buttonActivePointers.clear();
    _diamondPointerActions.clear();
    _leftZonePointerId = null;
    _joystickNormalizedOffset = Offset.zero;
    _syncActiveKeysAndSend();
  }

  void _syncActiveKeysAndSend() {
    final consolidated = <int>{};
    for (final keySet in _pointerActiveKeys.values) {
      consolidated.addAll(keySet);
    }

    if (!setEquals(_activeKeys, consolidated)) {
      setState(() {
        _activeKeys = consolidated;
      });
      // Envio inmediato de latencia cero (hasta 6 teclas simultaneas)
      final payload = consolidated.take(6).toList(growable: false);
      _hidChannel.invokeMethod('sendKeys', {'keys': payload}).catchError((_) {});
    } else {
      setState(() {});
    }
  }

  bool _isActionActive(PadAction action) {
    return _activeKeys.contains(_currentMap.codeFor(action));
  }

  void _switchPlayer(int newPlayer) {
    if (_selectedPlayer == newPlayer) return;
    HapticFeedback.lightImpact();
    _clearAllInputs();
    setState(() {
      _selectedPlayer = newPlayer;
    });
  }

  void _toggleLeftMode(LeftControlMode mode) {
    if (_leftMode == mode) return;
    HapticFeedback.lightImpact();
    if (_leftZonePointerId != null) {
      _removePointer(_leftZonePointerId!);
      _leftZonePointerId = null;
    }
    setState(() {
      _joystickNormalizedOffset = Offset.zero;
      _leftMode = mode;
    });
  }

  Future<void> _showBluetoothDialog() async {
    HapticFeedback.lightImpact();
    try {
      final raw = await _hidChannel.invokeListMethod('getPairedDevices');
      if (raw != null && mounted) {
        setState(() {
          _pairedDevices = raw
              .whereType<Map>()
              .map(
                (m) => {
                  'name': (m['name'] ?? 'Dispositivo').toString(),
                  'address': (m['address'] ?? '').toString(),
                },
              )
              .toList();
        });
      }
    } catch (_) {}

    if (!mounted) return;
    showDialog(
      context: context,
      builder: (ctx) {
        final accent = _currentMap.accentColor;
        return AlertDialog(
          backgroundColor: const Color(0xFF222227),
          shape: RoundedRectangleBorder(
            borderRadius: BorderRadius.circular(16),
            side: BorderSide(color: accent.withValues(alpha: 0.4), width: 1.2),
          ),
          title: Row(
            children: [
              Icon(
                _connectedDeviceName != null
                    ? Icons.bluetooth_connected
                    : Icons.bluetooth_searching,
                color: _connectedDeviceName != null
                    ? const Color(0xFF4ADE80)
                    : accent,
              ),
              const SizedBox(width: 10),
              const Expanded(
                child: Text(
                  'Bluetooth 5.0 HID - GamePad_X7',
                  style: TextStyle(fontSize: 16, fontWeight: FontWeight.w700),
                ),
              ),
            ],
          ),
          content: SizedBox(
            width: 420,
            child: SingleChildScrollView(
              child: Column(
                mainAxisSize: MainAxisSize.min,
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  Container(
                    padding: const EdgeInsets.all(10),
                    decoration: BoxDecoration(
                      color: const Color(0xFF18181C),
                      borderRadius: BorderRadius.circular(10),
                      border: Border.all(color: Colors.white12),
                    ),
                    child: Row(
                      children: [
                        Container(
                          width: 10,
                          height: 10,
                          decoration: BoxDecoration(
                            shape: BoxShape.circle,
                            color: _connectedDeviceName != null
                                ? const Color(0xFF4ADE80)
                                : Colors.amber,
                          ),
                        ),
                        const SizedBox(width: 10),
                        Expanded(
                          child: Text(
                            _connectedDeviceName != null
                                ? 'Conectado a: $_connectedDeviceName'
                                : (_isHidRegistered
                                      ? 'HID activo (esperando host / proyector)'
                                      : 'Servicio HID desconectado'),
                            style: const TextStyle(
                              fontSize: 13,
                              color: Colors.white,
                            ),
                          ),
                        ),
                      ],
                    ),
                  ),
                  const SizedBox(height: 12),
                  const Text(
                    'Dispositivos vinculados (toca para conectar):',
                    style: TextStyle(
                      fontSize: 12,
                      color: Colors.white70,
                      fontWeight: FontWeight.w600,
                    ),
                  ),
                  const SizedBox(height: 6),
                  if (_pairedDevices.isEmpty)
                    const Padding(
                      padding: EdgeInsets.symmetric(vertical: 8),
                      child: Text(
                        'No se encontraron dispositivos vinculados. Activa la visibilidad y vincula "GamePad_X7" desde el proyector Magcubic X7.',
                        style: TextStyle(fontSize: 12, color: Colors.white54),
                      ),
                    )
                  else
                    ..._pairedDevices.map(
                      (dev) => ListTile(
                        dense: true,
                        contentPadding: const EdgeInsets.symmetric(
                          horizontal: 8,
                        ),
                        leading: const Icon(
                          Icons.tv,
                          color: Colors.white70,
                          size: 20,
                        ),
                        title: Text(
                          dev['name'] ?? 'Dispositivo',
                          style: const TextStyle(
                            fontSize: 13,
                            fontWeight: FontWeight.w600,
                          ),
                        ),
                        subtitle: Text(
                          dev['address'] ?? '',
                          style: const TextStyle(
                            fontSize: 11,
                            color: Colors.white38,
                          ),
                        ),
                        trailing: ElevatedButton(
                          style: ElevatedButton.styleFrom(
                            backgroundColor: accent.withValues(alpha: 0.2),
                            foregroundColor: accent,
                            padding: const EdgeInsets.symmetric(horizontal: 12),
                          ),
                          onPressed: () async {
                            Navigator.of(ctx).pop();
                            await _hidChannel.invokeMethod('connectDevice', {
                              'address': dev['address'],
                            });
                            await Future.delayed(
                              const Duration(milliseconds: 600),
                            );
                            _refreshConnectedDevice();
                          },
                          child: const Text('Conectar'),
                        ),
                      ),
                    ),
                ],
              ),
            ),
          ),
          actions: [
            TextButton.icon(
              onPressed: () {
                Navigator.of(ctx).pop();
                _initializeBluetoothHid(makeDiscoverable: true);
              },
              icon: const Icon(Icons.wifi_tethering, size: 18),
              label: const Text('Hacer Visible (300s)'),
            ),
            TextButton(
              onPressed: () => Navigator.of(ctx).pop(),
              child: const Text('Cerrar'),
            ),
          ],
        );
      },
    );
  }

  // ===========================================================================
  // LOGICA DE DIRECCIONES (8 VIAS PARA D-PAD Y JOYSTICK)
  // ===========================================================================
  void _handleDirectionalPointer(
    Offset localPosition,
     double diameter,
    int pointerId, {
    required bool isJoystick,
  }) {
    final radius = diameter / 2.0;
    final center = Offset(radius, radius);
    final delta = localPosition - center;
    final distance = delta.distance;

    // Zona muerta porcentual (16% del radio)
    final deadZone = radius * 0.16;

    if (distance < deadZone) {
      if (isJoystick) {
        setState(() {
          _joystickNormalizedOffset = Offset.zero;
        });
      }
      _updatePointerKeys(pointerId, const <int>{});
      return;
    }

    if (isJoystick) {
      final maxKnobTravel = radius * 0.56;
      final clampedDist = math.min(distance, maxKnobTravel);
      final unit = delta / distance;
      final knobOffset = (unit * clampedDist) / maxKnobTravel;
      setState(() {
        _joystickNormalizedOffset = knobOffset;
      });
    }

    // Angulo en grados (-180 a 180): 0 = Derecha, -90 = Arriba, 90 = Abajo, +-180 = Izquierda
    final angleDeg = math.atan2(delta.dy, delta.dx) * 180.0 / math.pi;
    final keys = <int>{};

    // Sectores de 45 grados para 8 direcciones (incluyendo diagonales reales de 2 teclas)
    if (angleDeg >= -22.5 && angleDeg < 22.5) {
      keys.add(_currentMap.codeFor(PadAction.right));
    } else if (angleDeg >= 22.5 && angleDeg < 67.5) {
      keys.add(_currentMap.codeFor(PadAction.right));
      keys.add(_currentMap.codeFor(PadAction.down));
    } else if (angleDeg >= 67.5 && angleDeg < 112.5) {
      keys.add(_currentMap.codeFor(PadAction.down));
    } else if (angleDeg >= 112.5 && angleDeg < 157.5) {
      keys.add(_currentMap.codeFor(PadAction.down));
      keys.add(_currentMap.codeFor(PadAction.left));
    } else if (angleDeg >= 157.5 || angleDeg < -157.5) {
      keys.add(_currentMap.codeFor(PadAction.left));
    } else if (angleDeg >= -157.5 && angleDeg < -112.5) {
      keys.add(_currentMap.codeFor(PadAction.left));
      keys.add(_currentMap.codeFor(PadAction.up));
    } else if (angleDeg >= -112.5 && angleDeg < -67.5) {
      keys.add(_currentMap.codeFor(PadAction.up));
    } else if (angleDeg >= -67.5 && angleDeg < -22.5) {
      keys.add(_currentMap.codeFor(PadAction.up));
      keys.add(_currentMap.codeFor(PadAction.right));
    }

    _updatePointerKeys(pointerId, keys);
  }

  // ===========================================================================
  // LOGICA DEL ROMBO DE ACCION DERECHO (Y, X, B, A) CON SOPORTE MULTIBOTON
  // ===========================================================================
  void _handleDiamondPointer(
    Offset localPosition,
    double containerSize,
    int pointerId,
  ) {
    final center = Offset(containerSize / 2.0, containerSize / 2.0);
    final delta = localPosition - center;
    final dist = delta.distance;
    final maxRadius = containerSize * 0.52;

    if (dist > maxRadius || dist < containerSize * 0.06) {
      _diamondPointerActions.remove(pointerId);
      _updatePointerKeys(pointerId, const <int>{});
      return;
    }

    // Centros relativos de los 4 botones del rombo:
    // Y (arriba), X (izquierda), B (derecha), A (abajo)
    final offsetFromCenter = containerSize * 0.27;
    final buttonCenters = <PadAction, Offset>{
      PadAction.y: Offset(center.dx, center.dy - offsetFromCenter),
      PadAction.x: Offset(center.dx - offsetFromCenter, center.dy),
      PadAction.b: Offset(center.dx + offsetFromCenter, center.dy),
      PadAction.a: Offset(center.dx, center.dy + offsetFromCenter),
    };

    // Radio de activacion generoso para permitir presionar 1 boton o 2 adyacentes con el pulgar
    final hitRadius = containerSize * 0.235;
    final activeActions = <PadAction>{};
    final activeCodes = <int>{};

    buttonCenters.forEach((action, btnCenter) {
      if ((localPosition - btnCenter).distance <= hitRadius) {
        activeActions.add(action);
        activeCodes.add(_currentMap.codeFor(action));
      }
    });

    // Si el toque esta dentro del rombo pero entre bordes, elegir el mas cercano
    if (activeActions.isEmpty) {
      PadAction? closest;
      double minDistance = double.infinity;
      buttonCenters.forEach((action, btnCenter) {
        final d = (localPosition - btnCenter).distance;
        if (d < minDistance) {
          minDistance = d;
          closest = action;
        }
      });
      if (closest != null) {
        activeActions.add(closest!);
        activeCodes.add(_currentMap.codeFor(closest!));
      }
    }

    _diamondPointerActions[pointerId] = activeActions;
    _updatePointerKeys(pointerId, activeCodes);
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      body: SafeArea(
        left: false,
        right: false,
        top: false,
        bottom: false,
        child: LayoutBuilder(
          builder: (context, constraints) {
            // Calculos 100% basados en porcentajes de las dimensiones de la pantalla movil
            final screenW = constraints.maxWidth;
            final screenH = constraints.maxHeight;

            // Barra superior: ~14% de la altura de la pantalla
            final topBarHeight = (screenH * 0.145).clamp(46.0, 68.0);
            final bodyHeight = screenH - topBarHeight;

            // Margenes laterales y verticales en porcentajes
            final horizontalPad = screenW * 0.025;
            final shoulderRowHeight = (bodyHeight * 0.20).clamp(52.0, 88.0);

            // Tamaño grande adaptable en porcentaje para el D-Pad / Joystick izquierdo
            // y para el rombo derecho de botones (Y, X, B, A), cumpliendo y superando
            // la escala logica de 230x230 y 80x80 por boton.
            final mainControlDiameter = math
                .min(bodyHeight * 0.74, screenW * 0.34)
                .clamp(210.0, 340.0);

            final actionDiamondSize = math
                .min(bodyHeight * 0.75, screenW * 0.35)
                .clamp(215.0, 345.0);

            // Ancho de columnas laterales y columna central en porcentajes
            final sideColumnWidth = screenW * 0.38;
            final centerColumnWidth =
                screenW - (sideColumnWidth * 2) - (horizontalPad * 2);

            return Container(
              width: screenW,
              height: screenH,
              decoration: const BoxDecoration(
                gradient: RadialGradient(
                  center: Alignment.center,
                  radius: 1.15,
                  colors: [
                    Color(0xFF232328),
                    Color(0xFF17171A),
                    Color(0xFF111114),
                  ],
                ),
              ),
              child: Column(
                children: [
                  // ===========================================================
                  // BARRA SUPERIOR RESPONSIVA
                  // ===========================================================
                  SizedBox(
                    width: screenW,
                    height: topBarHeight,
                    child: _buildTopBar(screenW, topBarHeight),
                  ),

                  // ===========================================================
                  // CUERPO PRINCIPAL DEL MANDO (IZQUIERDA | CENTRO | DERECHA)
                  // ===========================================================
                  Expanded(
                    child: Padding(
                      padding: EdgeInsets.symmetric(horizontal: horizontalPad),
                      child: Row(
                        crossAxisAlignment: CrossAxisAlignment.stretch,
                        children: [
                          // ---------------------------------------------------
                          // LADO IZQUIERDO: LT + LB ARRIBA, D-PAD O JOYSTICK DEBAJO
                          // ---------------------------------------------------
                          SizedBox(
                            width: sideColumnWidth,
                            child: Column(
                              mainAxisAlignment: MainAxisAlignment.spaceBetween,
                              children: [
                                SizedBox(
                                  height: shoulderRowHeight,
                                  child: _buildShoulderRow(
                                    leftSide: true,
                                    width: sideColumnWidth,
                                    height: shoulderRowHeight,
                                  ),
                                ),
                                Expanded(
                                  child: Center(
                                    child: _leftMode == LeftControlMode.dpad
                                        ? _buildLargeCircularDPad(
                                            mainControlDiameter,
                                          )
                                        : _buildLargeAnalogJoystick(
                                            mainControlDiameter,
                                          ),
                                  ),
                                ),
                              ],
                            ),
                          ),

                          // ---------------------------------------------------
                          // PANEL CENTRAL (ESTILO CONSOLA + ACCESO RAPIDO)
                          // ---------------------------------------------------
                          SizedBox(
                            width: math.max(centerColumnWidth, 90.0),
                            child: _buildCenterConsoleHub(
                              math.max(centerColumnWidth, 90.0),
                              bodyHeight,
                            ),
                          ),

                          // ---------------------------------------------------
                          // LADO DERECHO: RT + RB ARRIBA, ROMBO GRANDE Y/X/B/A DEBAJO
                          // (SIN JOYSTICK DERECHO)
                          // ---------------------------------------------------
                          SizedBox(
                            width: sideColumnWidth,
                            child: Column(
                              mainAxisAlignment: MainAxisAlignment.spaceBetween,
                              children: [
                                SizedBox(
                                  height: shoulderRowHeight,
                                  child: _buildShoulderRow(
                                    leftSide: false,
                                    width: sideColumnWidth,
                                    height: shoulderRowHeight,
                                  ),
                                ),
                                Expanded(
                                  child: Center(
                                    child: _buildLargeActionDiamond(
                                      actionDiamondSize,
                                    ),
                                  ),
                                ),
                              ],
                            ),
                          ),
                        ],
                      ),
                    ),
                  ),
                ],
              ),
            );
          },
        ),
      ),
    );
  }

  // ===========================================================================
  // BARRA SUPERIOR: P1..P4 | TOGGLE D-PAD/JOYSTICK | SELECT & START | BLUETOOTH
  // ===========================================================================
  Widget _buildTopBar(double screenW, double barH) {
    final accent = _currentMap.accentColor;
    final compact = screenW < 720;

    return Container(
      margin: EdgeInsets.symmetric(
        horizontal: screenW * 0.02,
        vertical: barH * 0.08,
      ),
      padding: EdgeInsets.symmetric(horizontal: screenW * 0.015),
      decoration: BoxDecoration(
        color: const Color(0xFF1F1F24),
        borderRadius: BorderRadius.circular(14),
        border: Border.all(color: Colors.white.withValues(alpha: 0.08)),
        boxShadow: const [
          BoxShadow(
            color: Colors.black54,
            blurRadius: 8,
            offset: Offset(0, 3),
          ),
        ],
      ),
      child: Row(
        mainAxisAlignment: MainAxisAlignment.spaceBetween,
        children: [
          // 1. Selector segmentado P1 | P2 | P3 | P4
          Row(
            mainAxisSize: MainAxisSize.min,
            children: List.generate(4, (index) {
              final p = index + 1;
              final isSelected = _selectedPlayer == p;
              final pColor = kPlayerMappings[p]!.accentColor;
              return Listener(
                onPointerDown: (_) => _switchPlayer(p),
                child: AnimatedContainer(
                  duration: const Duration(milliseconds: 120),
                  margin: const EdgeInsets.symmetric(horizontal: 2.5),
                  padding: EdgeInsets.symmetric(
                    horizontal: compact ? 9 : 13,
                    vertical: barH * 0.14,
                  ),
                  decoration: BoxDecoration(
                    color: isSelected
                        ? pColor.withValues(alpha: 0.22)
                        : const Color(0xFF2A2A30),
                    borderRadius: BorderRadius.circular(9),
                    border: Border.all(
                      color: isSelected ? pColor : Colors.white12,
                      width: 1.4,
                    ),
                  ),
                  child: Text(
                    'P$p',
                    style: TextStyle(
                      color: isSelected ? pColor : Colors.white60,
                      fontWeight: FontWeight.w800,
                      fontSize: compact ? 11.5 : 13,
                      letterSpacing: 0.5,
                    ),
                  ),
                ),
              );
            }),
          ),

          // 2. Interruptor Toggle D-PAD / JOYSTICK para el lado izquierdo
          Container(
            padding: const EdgeInsets.all(3),
            decoration: BoxDecoration(
              color: const Color(0xFF16161A),
              borderRadius: BorderRadius.circular(10),
              border: Border.all(color: Colors.white12),
            ),
            child: Row(
              mainAxisSize: MainAxisSize.min,
              children: [
                _buildModeToggleChip(
                  label: 'D-PAD',
                  icon: Icons.gamepad_outlined,
                  selected: _leftMode == LeftControlMode.dpad,
                  accent: accent,
                  compact: compact,
                  onTap: () => _toggleLeftMode(LeftControlMode.dpad),
                ),
                const SizedBox(width: 4),
                _buildModeToggleChip(
                  label: 'JOYSTICK',
                  icon: Icons.radio_button_checked,
                  selected: _leftMode == LeftControlMode.joystick,
                  accent: accent,
                  compact: compact,
                  onTap: () => _toggleLeftMode(LeftControlMode.joystick),
                ),
              ],
            ),
          ),

          // 3. Botones superiores independientes: COIN + SELECT (con opcion de Z-N64 o indicador EN PAUSA parpadeante)
          Row(
            mainAxisSize: MainAxisSize.min,
            children: [
              _buildHoldButton(
                buttonId: 'top_coin',
                action: PadAction.coin,
                label: 'COIN',
                subLabel: _currentMap.labelFor(PadAction.coin),
                width: compact ? 68 : 84,
                height: barH * 0.68,
                borderRadius: 9,
                icon: Icons.monetization_on_outlined,
              ),
              const SizedBox(width: 8),
              _buildTopSelectPauseOrExtraButton(
                width: compact ? 82 : 106,
                height: barH * 0.68,
              ),
            ],
          ),

          // 4. Boton de Estado Bluetooth (activar visibilidad / reconectar)
          Listener(
            onPointerDown: (_) => _showBluetoothDialog(),
            child: Container(
              padding: EdgeInsets.symmetric(
                horizontal: compact ? 9 : 12,
                vertical: barH * 0.14,
              ),
              decoration: BoxDecoration(
                color: _connectedDeviceName != null
                    ? const Color(0xFF143824)
                    : const Color(0xFF2B2B32),
                borderRadius: BorderRadius.circular(10),
                border: Border.all(
                  color: _connectedDeviceName != null
                      ? const Color(0xFF4ADE80)
                      : Colors.white24,
                  width: 1.2,
                ),
              ),
              child: Row(
                mainAxisSize: MainAxisSize.min,
                children: [
                  Icon(
                    _connectedDeviceName != null
                        ? Icons.bluetooth_connected
                        : Icons.bluetooth,
                    size: compact ? 15 : 17,
                    color: _connectedDeviceName != null
                        ? const Color(0xFF4ADE80)
                        : accent,
                  ),
                  const SizedBox(width: 5),
                  ConstrainedBox(
                    constraints: BoxConstraints(maxWidth: screenW * 0.13),
                    child: Text(
                      _connectedDeviceName ?? 'BT HID',
                      overflow: TextOverflow.ellipsis,
                      style: TextStyle(
                        fontSize: compact ? 11 : 12,
                        fontWeight: FontWeight.w700,
                        color: _connectedDeviceName != null
                            ? const Color(0xFF4ADE80)
                            : Colors.white70,
                      ),
                    ),
                  ),
                ],
              ),
            ),
          ),
        ],
      ),
    );
  }

  Widget _buildTopSelectPauseOrExtraButton({
    required double width,
    required double height,
  }) {
    final accent = _currentMap.accentColor;
    final action = _useTopAuxAsExtraZ ? PadAction.extraZ : PadAction.select;
    final usageCode = _currentMap.codeFor(action);
    final isPhysicallyPressed =
        (_buttonActivePointers['top_aux']?.isNotEmpty ?? false);

    // Estado visual cuando esta en modo Pausa (rojo parpadeante)
    final isPauseActive = !_useTopAuxAsExtraZ && _isPaused;
    final pauseRed = const Color(0xFFEF4444);

    Color borderColor;
    List<Color> gradientColors;

    if (_useTopAuxAsExtraZ) {
      borderColor = isPhysicallyPressed ? const Color(0xFFA855F7) : const Color(0xFF553C75);
      gradientColors = isPhysicallyPressed
          ? [
              const Color(0xFFA855F7).withValues(alpha: 0.50),
              const Color(0xFFA855F7).withValues(alpha: 0.25),
            ]
          : const [
              Color(0xFF382946),
              Color(0xFF281E32),
            ];
    } else if (isPauseActive) {
      final glowColor = _pauseBlinkOn ? pauseRed : const Color(0xFF7F1D1D);
      borderColor = glowColor;
      gradientColors = _pauseBlinkOn
          ? [
              pauseRed.withValues(alpha: 0.60),
              const Color(0xFF991B1B).withValues(alpha: 0.40),
            ]
          : const [
              Color(0xFF3B1D22),
              Color(0xFF221316),
            ];
    } else {
      borderColor = isPhysicallyPressed ? accent : const Color(0xFF45454E);
      gradientColors = isPhysicallyPressed
          ? [
              accent.withValues(alpha: 0.42),
              accent.withValues(alpha: 0.22),
            ]
          : const [
              Color(0xFF35353C),
              Color(0xFF27272C),
            ];
    }

    return Listener(
      behavior: HitTestBehavior.opaque,
      onPointerDown: (e) {
        _buttonActivePointers.putIfAbsent('top_aux', () => {}).add(e.pointer);
        if (!_useTopAuxAsExtraZ) {
          _togglePauseIndicator();
        }
        _updatePointerKeys(e.pointer, {usageCode});
      },
      onPointerMove: (e) {
        if (e.localPosition.dx >= -12 &&
            e.localPosition.dx <= width + 12 &&
            e.localPosition.dy >= -12 &&
            e.localPosition.dy <= height + 12) {
          _buttonActivePointers.putIfAbsent('top_aux', () => {}).add(e.pointer);
          _updatePointerKeys(e.pointer, {usageCode}, haptic: false);
        } else {
          _buttonActivePointers['top_aux']?.remove(e.pointer);
          _removePointer(e.pointer);
        }
      },
      onPointerUp: (e) {
        _buttonActivePointers['top_aux']?.remove(e.pointer);
        _removePointer(e.pointer);
      },
      onPointerCancel: (e) {
        _buttonActivePointers['top_aux']?.remove(e.pointer);
        _removePointer(e.pointer);
      },
      child: GestureDetector(
        onLongPress: () {
          // Permite alternar manteniendo presionado si el juego requiere el boton extra Z (ej. N64 Mario Kart)
          HapticFeedback.heavyImpact();
          setState(() {
            _useTopAuxAsExtraZ = !_useTopAuxAsExtraZ;
            if (_useTopAuxAsExtraZ && _isPaused) {
              _isPaused = false;
              _pauseBlinkTimer?.cancel();
            }
          });
        },
        child: AnimatedContainer(
          duration: const Duration(milliseconds: 60),
          width: width,
          height: height,
          decoration: BoxDecoration(
            gradient: LinearGradient(
              begin: Alignment.topLeft,
              end: Alignment.bottomRight,
              colors: gradientColors,
            ),
            borderRadius: BorderRadius.circular(9),
            border: Border.all(
              color: borderColor,
              width: (isPauseActive || isPhysicallyPressed) ? 1.8 : 1.3,
            ),
            boxShadow: [
              if (isPauseActive && _pauseBlinkOn)
                BoxShadow(
                  color: pauseRed.withValues(alpha: 0.65),
                  blurRadius: 14,
                  spreadRadius: 2,
                )
              else if (isPhysicallyPressed)
                BoxShadow(
                  color: accent.withValues(alpha: 0.35),
                  blurRadius: 10,
                  spreadRadius: 1,
                )
              else
                const BoxShadow(
                  color: Colors.black87,
                  blurRadius: 6,
                  offset: Offset(0, 3),
                ),
            ],
          ),
          child: Padding(
            padding: const EdgeInsets.symmetric(horizontal: 4, vertical: 2),
            child: FittedBox(
              fit: BoxFit.scaleDown,
              child: Row(
                mainAxisSize: MainAxisSize.min,
                children: [
                  Icon(
                    _useTopAuxAsExtraZ
                        ? Icons.bolt_rounded
                        : (isPauseActive ? Icons.pause_circle_filled : Icons.pause_rounded),
                    size: height * 0.38,
                    color: _useTopAuxAsExtraZ
                        ? const Color(0xFFD8B4FE)
                        : (isPauseActive
                            ? (_pauseBlinkOn ? Colors.white : pauseRed)
                            : Colors.white70),
                  ),
                  const SizedBox(width: 4),
                  Column(
                    mainAxisSize: MainAxisSize.min,
                    children: [
                      Text(
                        _useTopAuxAsExtraZ
                            ? 'Z-N64'
                            : (isPauseActive ? 'PAUSA' : 'PAUSE'),
                        style: TextStyle(
                          fontSize: (height * 0.28).clamp(10.0, 15.0),
                          fontWeight: FontWeight.w800,
                          color: _useTopAuxAsExtraZ
                              ? Colors.white
                              : (isPauseActive
                                  ? (_pauseBlinkOn ? Colors.white : pauseRed)
                                  : Colors.white),
                          letterSpacing: 0.5,
                          height: 1.05,
                        ),
                      ),
                      Text(
                        _useTopAuxAsExtraZ
                            ? 'BOTON EXTRA'
                            : (isPauseActive
                                ? (_pauseBlinkOn ? 'EN PAUSA' : 'SELECT')
                                : 'SELECT (PAUSA)'),
                        style: TextStyle(
                          fontSize: (height * 0.16).clamp(7.5, 10.0),
                          fontWeight: FontWeight.w700,
                          color: isPauseActive
                              ? (_pauseBlinkOn ? Colors.white : pauseRed)
                              : Colors.white54,
                          height: 1.05,
                        ),
                      ),
                    ],
                  ),
                ],
              ),
            ),
          ),
        ),
      ),
    );
  }

  Widget _buildModeToggleChip({
    required String label,
    required IconData icon,
    required bool selected,
    required Color accent,
    required bool compact,
    required VoidCallback onTap,
  }) {
    return Listener(
      onPointerDown: (_) => onTap(),
      child: AnimatedContainer(
        duration: const Duration(milliseconds: 120),
        padding: EdgeInsets.symmetric(
          horizontal: compact ? 8 : 11,
          vertical: 5,
        ),
        decoration: BoxDecoration(
          color: selected
              ? accent.withValues(alpha: 0.24)
              : Colors.transparent,
          borderRadius: BorderRadius.circular(7),
          border: Border.all(
            color: selected ? accent : Colors.transparent,
            width: 1.2,
          ),
        ),
        child: Row(
          mainAxisSize: MainAxisSize.min,
          children: [
            Icon(
              icon,
              size: compact ? 13 : 15,
              color: selected ? accent : Colors.white54,
            ),
            const SizedBox(width: 4),
            Text(
              label,
              style: TextStyle(
                fontSize: compact ? 10.5 : 11.5,
                fontWeight: FontWeight.w800,
                color: selected ? Colors.white : Colors.white54,
              ),
            ),
          ],
        ),
      ),
    );
  }

  // ===========================================================================
  // BOTONES SUPERIORES DE HOMBRO (LT + LB A LA IZQUIERDA, RT + RB A LA DERECHA)
  // ===========================================================================
  Widget _buildShoulderRow({
    required bool leftSide,
    required double width,
    required double height,
  }) {
    final btnWidth = (width * 0.45).clamp(80.0, 175.0);
    final btnHeight = (height * 0.88).clamp(44.0, 74.0);

    final firstAction = leftSide ? PadAction.lt : PadAction.rb;
    final firstLabel = leftSide ? 'LT' : 'RB';
    final secondAction = leftSide ? PadAction.lb : PadAction.rt;
    final secondLabel = leftSide ? 'LB' : 'RT';

    return Row(
      mainAxisAlignment: MainAxisAlignment.spaceEvenly,
      children: [
        _buildHoldButton(
          action: firstAction,
          label: firstLabel,
          subLabel: _currentMap.labelFor(firstAction),
          width: btnWidth,
          height: btnHeight,
          borderRadius: 16,
        ),
        _buildHoldButton(
          action: secondAction,
          label: secondLabel,
          subLabel: _currentMap.labelFor(secondAction),
          width: btnWidth,
          height: btnHeight,
          borderRadius: 16,
        ),
      ],
    );
  }

  // ===========================================================================
  // BOTON INDIVIDUAL CON LISTENER PURO (LATENCIA CERO)
  // ===========================================================================
  Widget _buildHoldButton({
    String? buttonId,
    required PadAction action,
    required String label,
    required String subLabel,
    required double width,
    required double height,
    double borderRadius = 14,
    IconData? icon,
  }) {
    final effectiveId = buttonId ?? 'btn_${action.name}';
    final isPhysicallyPressed =
        (_buttonActivePointers[effectiveId]?.isNotEmpty ?? false);
    final isPressed = isPhysicallyPressed || _isActionActive(action);
    final accent = _currentMap.accentColor;
    final usageCode = _currentMap.codeFor(action);

    return Listener(
      behavior: HitTestBehavior.opaque,
      onPointerDown: (e) {
        _buttonActivePointers.putIfAbsent(effectiveId, () => {}).add(e.pointer);
        _updatePointerKeys(e.pointer, {usageCode});
      },
      onPointerMove: (e) {
        if (e.localPosition.dx >= -12 &&
            e.localPosition.dx <= width + 12 &&
            e.localPosition.dy >= -12 &&
            e.localPosition.dy <= height + 12) {
          _buttonActivePointers.putIfAbsent(effectiveId, () => {}).add(e.pointer);
          _updatePointerKeys(e.pointer, {usageCode}, haptic: false);
        } else {
          _buttonActivePointers[effectiveId]?.remove(e.pointer);
          _removePointer(e.pointer);
        }
      },
      onPointerUp: (e) {
        _buttonActivePointers[effectiveId]?.remove(e.pointer);
        _removePointer(e.pointer);
      },
      onPointerCancel: (e) {
        _buttonActivePointers[effectiveId]?.remove(e.pointer);
        _removePointer(e.pointer);
      },
      child: AnimatedContainer(
        duration: const Duration(milliseconds: 50),
        width: width,
        height: height,
        decoration: BoxDecoration(
          gradient: LinearGradient(
            begin: Alignment.topLeft,
            end: Alignment.bottomRight,
            colors: isPressed
                ? [
                    accent.withValues(alpha: 0.42),
                    accent.withValues(alpha: 0.22),
                  ]
                : const [
                    Color(0xFF35353C),
                    Color(0xFF27272C),
                  ],
          ),
          borderRadius: BorderRadius.circular(borderRadius),
          border: Border.all(
            color: isPressed ? accent : const Color(0xFF45454E),
            width: 1.5,
          ),
          boxShadow: isPressed
              ? [
                  BoxShadow(
                    color: accent.withValues(alpha: 0.35),
                    blurRadius: 12,
                    spreadRadius: 1,
                  ),
                ]
              : const [
                  BoxShadow(
                    color: Colors.black87,
                    blurRadius: 8,
                    offset: Offset(0, 4),
                  ),
                ],
        ),
        child: Padding(
          padding: const EdgeInsets.symmetric(horizontal: 4, vertical: 2),
          child: FittedBox(
            fit: BoxFit.scaleDown,
            child: Row(
              mainAxisAlignment: MainAxisAlignment.center,
              mainAxisSize: MainAxisSize.min,
              children: [
                if (icon != null) ...[
                  Icon(
                    icon,
                    size: height * 0.34,
                    color: isPressed ? Colors.white : const Color(0xFFD4D4DC),
                  ),
                  const SizedBox(width: 4),
                ],
                Column(
                  mainAxisSize: MainAxisSize.min,
                  children: [
                    Text(
                      label,
                      style: TextStyle(
                        fontSize: (height * 0.29).clamp(10.5, 19.0),
                        fontWeight: FontWeight.w800,
                        color: isPressed
                            ? Colors.white
                            : const Color(0xFFE4E4EC),
                        letterSpacing: 0.5,
                        height: 1.05,
                      ),
                    ),
                    if (subLabel.isNotEmpty && height >= 44)
                      Text(
                        subLabel,
                        style: TextStyle(
                          fontSize: (height * 0.16).clamp(8.0, 10.5),
                          fontWeight: FontWeight.w600,
                          color: isPressed ? accent : Colors.white38,
                          height: 1.05,
                        ),
                      ),
                  ],
                ),
              ],
            ),
          ),
        ),
      ),
    );
  }

  // ===========================================================================
  // PANEL CENTRAL DE REFERENCIA VISUAL (COMO EN LA IMAGEN DE REFERENCIA)
  // ===========================================================================
  Widget _buildCenterConsoleHub(double width, double height) {
    final accent = _currentMap.accentColor;
    final btnSize = (width * 0.34).clamp(44.0, 64.0);

    return Column(
      mainAxisAlignment: MainAxisAlignment.center,
      children: [
        // Indicador estilo mando consola
        Container(
          padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
          decoration: BoxDecoration(
            color: const Color(0xFF232329),
            borderRadius: BorderRadius.circular(14),
            border: Border.all(color: Colors.white10),
            boxShadow: const [
              BoxShadow(
                color: Colors.black54,
                blurRadius: 8,
                offset: Offset(0, 4),
              ),
            ],
          ),
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              Icon(
                Icons.sports_esports_rounded,
                color: accent,
                size: (btnSize * 0.56).clamp(22.0, 32.0),
              ),
              const SizedBox(height: 2),
              Text(
                'JUGADOR $_selectedPlayer',
                style: TextStyle(
                  fontSize: 10.5,
                  fontWeight: FontWeight.w800,
                  color: accent,
                  letterSpacing: 0.8,
                ),
              ),
            ],
          ),
        ),
        SizedBox(height: height * 0.04),

        // Botones centrales estilo imagen (Ventanas = SELECT, Menu = START)
        Row(
          mainAxisAlignment: MainAxisAlignment.spaceEvenly,
          children: [
            _buildHoldButton(
              buttonId: 'center_select',
              action: PadAction.select,
              label: 'SELECT',
              subLabel: _currentMap.labelFor(PadAction.select),
              width: btnSize,
              height: btnSize,
              borderRadius: 15,
            ),
            _buildHoldButton(
              buttonId: 'center_start',
              action: PadAction.start,
              label: 'START',
              subLabel: _currentMap.labelFor(PadAction.start),
              width: btnSize,
              height: btnSize,
              borderRadius: 15,
            ),
          ],
        ),
        SizedBox(height: height * 0.04),

        // Boton rapido central para alternar D-PAD <-> JOYSTICK con un toque
        Listener(
          onPointerDown: (_) {
            _toggleLeftMode(
              _leftMode == LeftControlMode.dpad
                  ? LeftControlMode.joystick
                  : LeftControlMode.dpad,
            );
          },
          child: Container(
            padding: const EdgeInsets.symmetric(horizontal: 12, vertical: 8),
            decoration: BoxDecoration(
              color: const Color(0xFF28282F),
              borderRadius: BorderRadius.circular(12),
              border: Border.all(color: accent.withValues(alpha: 0.35)),
            ),
            child: Row(
              mainAxisSize: MainAxisSize.min,
              children: [
                Icon(Icons.swap_horiz_rounded, color: accent, size: 16),
                const SizedBox(width: 4),
                Text(
                  _leftMode == LeftControlMode.dpad
                      ? 'MODO: D-PAD'
                      : 'MODO: STICK',
                  style: const TextStyle(
                    fontSize: 10.5,
                    fontWeight: FontWeight.w700,
                    color: Colors.white70,
                  ),
                ),
              ],
            ),
          ),
        ),
      ],
    );
  }

  // ===========================================================================
  // LADO IZQUIERDO MODO 1: CRUCETA CIRCULAR GRANDE DE 8 DIRECCIONES (D-PAD)
  // ===========================================================================
  Widget _buildLargeCircularDPad(double diameter) {
    final upActive = _isActionActive(PadAction.up);
    final downActive = _isActionActive(PadAction.down);
    final leftActive = _isActionActive(PadAction.left);
    final rightActive = _isActionActive(PadAction.right);
    final accent = _currentMap.accentColor;

    return Listener(
      behavior: HitTestBehavior.opaque,
      onPointerDown: (e) {
        _leftZonePointerId = e.pointer;
        _handleDirectionalPointer(
          e.localPosition,
          diameter,
          e.pointer,
          isJoystick: false,
        );
      },
      onPointerMove: (e) {
        _handleDirectionalPointer(
          e.localPosition,
          diameter,
          e.pointer,
          isJoystick: false,
        );
      },
      onPointerUp: (e) {
        if (_leftZonePointerId == e.pointer) {
          _leftZonePointerId = null;
        }
        _removePointer(e.pointer);
      },
      onPointerCancel: (e) {
        if (_leftZonePointerId == e.pointer) {
          _leftZonePointerId = null;
        }
        _removePointer(e.pointer);
      },
      child: SizedBox(
        width: diameter,
        height: diameter,
        child: CustomPaint(
          painter: _CircularDPadPainter(
            upActive: upActive,
            downActive: downActive,
            leftActive: leftActive,
            rightActive: rightActive,
            accentColor: accent,
          ),
          child: Stack(
            alignment: Alignment.center,
            children: [
              // Flecha Arriba
              Positioned(
                top: diameter * 0.10,
                child: _buildDirectionLabel(
                  icon: Icons.keyboard_arrow_up_rounded,
                  keyHint: _currentMap.labelFor(PadAction.up),
                  active: upActive,
                  accent: accent,
                  size: diameter * 0.15,
                ),
              ),
              // Flecha Abajo
              Positioned(
                bottom: diameter * 0.10,
                child: _buildDirectionLabel(
                  icon: Icons.keyboard_arrow_down_rounded,
                  keyHint: _currentMap.labelFor(PadAction.down),
                  active: downActive,
                  accent: accent,
                  size: diameter * 0.15,
                ),
              ),
              // Flecha Izquierda
              Positioned(
                left: diameter * 0.10,
                child: _buildDirectionLabel(
                  icon: Icons.keyboard_arrow_left_rounded,
                  keyHint: _currentMap.labelFor(PadAction.left),
                  active: leftActive,
                  accent: accent,
                  size: diameter * 0.15,
                ),
              ),
              // Flecha Derecha
              Positioned(
                right: diameter * 0.10,
                child: _buildDirectionLabel(
                  icon: Icons.keyboard_arrow_right_rounded,
                  keyHint: _currentMap.labelFor(PadAction.right),
                  active: rightActive,
                  accent: accent,
                  size: diameter * 0.15,
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }

  Widget _buildDirectionLabel({
    required IconData icon,
    required String keyHint,
    required bool active,
    required Color accent,
    required double size,
  }) {
    return Column(
      mainAxisSize: MainAxisSize.min,
      children: [
        Icon(
          icon,
          size: size,
          color: active ? Colors.white : const Color(0xFFD4D4DC),
        ),
        Text(
          keyHint,
          style: TextStyle(
            fontSize: (size * 0.28).clamp(9.0, 12.0),
            fontWeight: FontWeight.w700,
            color: active ? accent : Colors.white38,
          ),
        ),
      ],
    );
  }

  // ===========================================================================
  // LADO IZQUIERDO MODO 2: JOYSTICK ANALOGICO GRANDE DE 8 VIAS
  // ===========================================================================
  Widget _buildLargeAnalogJoystick(double diameter) {
    final accent = _currentMap.accentColor;
    final upActive = _isActionActive(PadAction.up);
    final downActive = _isActionActive(PadAction.down);
    final leftActive = _isActionActive(PadAction.left);
    final rightActive = _isActionActive(PadAction.right);
    final anyActive = upActive || downActive || leftActive || rightActive;

    final maxKnobTravel = (diameter / 2.0) * 0.50;
    final knobDiameter = diameter * 0.46;
    final knobDx = _joystickNormalizedOffset.dx * maxKnobTravel;
    final knobDy = _joystickNormalizedOffset.dy * maxKnobTravel;

    return Listener(
      behavior: HitTestBehavior.opaque,
      onPointerDown: (e) {
        _leftZonePointerId = e.pointer;
        _handleDirectionalPointer(
          e.localPosition,
          diameter,
          e.pointer,
          isJoystick: true,
        );
      },
      onPointerMove: (e) {
        _handleDirectionalPointer(
          e.localPosition,
          diameter,
          e.pointer,
          isJoystick: true,
        );
      },
      onPointerUp: (e) {
        if (_leftZonePointerId == e.pointer) {
          _leftZonePointerId = null;
        }
        setState(() {
          _joystickNormalizedOffset = Offset.zero;
        });
        _removePointer(e.pointer);
      },
      onPointerCancel: (e) {
        if (_leftZonePointerId == e.pointer) {
          _leftZonePointerId = null;
        }
        setState(() {
          _joystickNormalizedOffset = Offset.zero;
        });
        _removePointer(e.pointer);
      },
      child: SizedBox(
        width: diameter,
        height: diameter,
        child: CustomPaint(
          painter: _JoystickBasePainter(
            accentColor: accent,
            isActive: anyActive,
          ),
          child: Center(
            child: Transform.translate(
              offset: Offset(knobDx, knobDy),
              child: Container(
                width: knobDiameter,
                height: knobDiameter,
                decoration: BoxDecoration(
                  shape: BoxShape.circle,
                  gradient: RadialGradient(
                    center: const Alignment(-0.25, -0.25),
                    radius: 0.85,
                    colors: anyActive
                        ? [
                            const Color(0xFF4A4A55),
                            const Color(0xFF282830),
                            const Color(0xFF19191E),
                          ]
                        : const [
                            Color(0xFF3E3E46),
                            Color(0xFF25252B),
                            Color(0xFF18181C),
                          ],
                  ),
                  border: Border.all(
                    color: anyActive ? accent : const Color(0xFF52525C),
                    width: anyActive ? 2.4 : 1.6,
                  ),
                  boxShadow: [
                    BoxShadow(
                      color: Colors.black.withValues(alpha: 0.75),
                      blurRadius: 16,
                      offset: const Offset(0, 8),
                    ),
                    if (anyActive)
                      BoxShadow(
                        color: accent.withValues(alpha: 0.30),
                        blurRadius: 14,
                        spreadRadius: 2,
                      ),
                  ],
                ),
                child: Center(
                  child: Container(
                    width: knobDiameter * 0.68,
                    height: knobDiameter * 0.68,
                    decoration: BoxDecoration(
                      shape: BoxShape.circle,
                      gradient: const LinearGradient(
                        begin: Alignment.topLeft,
                        end: Alignment.bottomRight,
                        colors: [
                          Color(0xFF1D1D22),
                          Color(0xFF2F2F37),
                        ],
                      ),
                      border: Border.all(
                        color: Colors.white.withValues(alpha: 0.08),
                        width: 1.0,
                      ),
                    ),
                  ),
                ),
              ),
            ),
          ),
        ),
      ),
    );
  }

  // ===========================================================================
  // LADO DERECHO: ROMBO GRANDE CON LOS 4 BOTONES (Y ARRIBA, X IZQ, B DER, A ABAJO)
  // ===========================================================================
  Widget _buildLargeActionDiamond(double containerSize) {
    // Cada boton dentro del rombo tiene un tamaño grande responsivo (~80x80 o superior)
    final buttonSize = (containerSize * 0.34).clamp(74.0, 118.0);
    final centerOffset = containerSize * 0.27;

    return Listener(
      behavior: HitTestBehavior.opaque,
      onPointerDown: (e) {
        _handleDiamondPointer(e.localPosition, containerSize, e.pointer);
      },
      onPointerMove: (e) {
        _handleDiamondPointer(e.localPosition, containerSize, e.pointer);
      },
      onPointerUp: (e) {
        _diamondPointerActions.remove(e.pointer);
        _removePointer(e.pointer);
      },
      onPointerCancel: (e) {
        _diamondPointerActions.remove(e.pointer);
        _removePointer(e.pointer);
      },
      child: SizedBox(
        width: containerSize,
        height: containerSize,
        child: Stack(
          alignment: Alignment.center,
          children: [
            // Base del rombo estilo imagen de referencia (rotada 45 grados)
            Transform.rotate(
              angle: math.pi / 4,
              child: Container(
                width: containerSize * 0.72,
                height: containerSize * 0.72,
                decoration: BoxDecoration(
                  color: const Color(0xFF1A1A1E),
                  borderRadius: BorderRadius.circular(containerSize * 0.11),
                  border: Border.all(
                    color: const Color(0xFF0E0E11),
                    width: 3.0,
                  ),
                  boxShadow: const [
                    BoxShadow(
                      color: Colors.black87,
                      blurRadius: 14,
                      offset: Offset(0, 6),
                    ),
                  ],
                ),
              ),
            ),

            // Boton Y (Arriba)
            Transform.translate(
              offset: Offset(0, -centerOffset),
              child: _buildDiamondCap(
                action: PadAction.y,
                title: 'Y',
                size: buttonSize,
              ),
            ),

            // Boton X (Izquierda)
            Transform.translate(
              offset: Offset(-centerOffset, 0),
              child: _buildDiamondCap(
                action: PadAction.x,
                title: 'X',
                size: buttonSize,
              ),
            ),

            // Boton B (Derecha)
            Transform.translate(
              offset: Offset(centerOffset, 0),
              child: _buildDiamondCap(
                action: PadAction.b,
                title: 'B',
                size: buttonSize,
              ),
            ),

            // Boton A (Abajo)
            Transform.translate(
              offset: Offset(0, centerOffset),
              child: _buildDiamondCap(
                action: PadAction.a,
                title: 'A',
                size: buttonSize,
              ),
            ),
          ],
        ),
      ),
    );
  }

  Widget _buildDiamondCap({
    required PadAction action,
    required String title,
    required double size,
  }) {
    final isPressed = _isActionActive(action);
    final accent = _currentMap.accentColor;
    final keyHint = _currentMap.labelFor(action);

    return Transform.rotate(
      angle: math.pi / 4,
      child: AnimatedContainer(
        duration: const Duration(milliseconds: 45),
        width: size,
        height: size,
        decoration: BoxDecoration(
          gradient: LinearGradient(
            begin: Alignment.topLeft,
            end: Alignment.bottomRight,
            colors: isPressed
                ? [
                    accent.withValues(alpha: 0.48),
                    accent.withValues(alpha: 0.24),
                  ]
                : const [
                    Color(0xFF36363E),
                    Color(0xFF28282E),
                  ],
          ),
          borderRadius: BorderRadius.circular(size * 0.22),
          border: Border.all(
            color: isPressed ? accent : const Color(0xFF474752),
            width: 1.8,
          ),
          boxShadow: isPressed
              ? [
                  BoxShadow(
                    color: accent.withValues(alpha: 0.40),
                    blurRadius: 12,
                    spreadRadius: 1,
                  ),
                ]
              : const [
                  BoxShadow(
                    color: Colors.black54,
                    blurRadius: 6,
                    offset: Offset(2, 3),
                  ),
                ],
        ),
        child: Transform.rotate(
          angle: -math.pi / 4,
          child: Padding(
            padding: const EdgeInsets.all(4),
            child: FittedBox(
              fit: BoxFit.scaleDown,
              child: Column(
                mainAxisSize: MainAxisSize.min,
                children: [
                  Text(
                    title,
                    style: TextStyle(
                      fontSize: (size * 0.34).clamp(20.0, 32.0),
                      fontWeight: FontWeight.w800,
                      color: isPressed ? Colors.white : const Color(0xFFE6E6F0),
                      height: 1.05,
                    ),
                  ),
                  Text(
                    keyHint,
                    style: TextStyle(
                      fontSize: (size * 0.15).clamp(9.5, 13.0),
                      fontWeight: FontWeight.w700,
                      color: isPressed ? accent : Colors.white38,
                      height: 1.05,
                    ),
                  ),
                ],
              ),
            ),
          ),
        ),
      ),
    );
  }
}

// =============================================================================
// CUSTOM PAINTER: D-PAD CIRCULAR SEGMENTADO (ESTILO IMAGEN DE REFERENCIA)
// =============================================================================
class _CircularDPadPainter extends CustomPainter {
  final bool upActive;
  final bool downActive;
  final bool leftActive;
  final bool rightActive;
  final Color accentColor;

  const _CircularDPadPainter({
    required this.upActive,
    required this.downActive,
    required this.leftActive,
    required this.rightActive,
    required this.accentColor,
  });

  @override
  void paint(Canvas canvas, Size size) {
    final center = Offset(size.width / 2, size.height / 2);
    final outerRadius = size.width / 2;
    final innerRadius = outerRadius * 0.31;

    // Sombra exterior y anillo base oscuro
    final basePaint = Paint()
      ..color = const Color(0xFF141417)
      ..style = PaintingStyle.fill;
    canvas.drawCircle(center, outerRadius, basePaint);

    final sectorRect = Rect.fromCircle(
      center: center,
      radius: outerRadius * 0.94,
    );

    void drawSector(double startAngleDeg, bool active) {
      final startRad = startAngleDeg * math.pi / 180.0;
      const sweepRad = 86.0 * math.pi / 180.0;

      final paint = Paint()
        ..shader = RadialGradient(
          colors: active
              ? [
                  accentColor.withValues(alpha: 0.50),
                  accentColor.withValues(alpha: 0.22),
                ]
              : const [
                  Color(0xFF37373F),
                  Color(0xFF28282E),
                ],
        ).createShader(sectorRect)
        ..style = PaintingStyle.fill;

      canvas.drawArc(sectorRect, startRad, sweepRad, true, paint);

      final borderPaint = Paint()
        ..color = active ? accentColor : const Color(0xFF45454F)
        ..style = PaintingStyle.stroke
        ..strokeWidth = active ? 2.2 : 1.2;
      canvas.drawArc(sectorRect, startRad, sweepRad, true, borderPaint);
    }

    // 4 sectores como en la imagen: Derecha (-43..43), Abajo (47..133), Izquierda (137..223), Arriba (227..313)
    drawSector(-43.0, rightActive);
    drawSector(47.0, downActive);
    drawSector(137.0, leftActive);
    drawSector(227.0, upActive);

    // Lineas diagonales de separacion oscuras (estilo biselado de la imagen)
    final dividerPaint = Paint()
      ..color = const Color(0xFF151519)
      ..strokeWidth = size.width * 0.022
      ..strokeCap = StrokeCap.round;

    for (final angleDeg in [-45.0, 45.0, 135.0, 225.0]) {
      final rad = angleDeg * math.pi / 180.0;
      final p1 = Offset(
        center.dx + math.cos(rad) * innerRadius * 0.9,
        center.dy + math.sin(rad) * innerRadius * 0.9,
      );
      final p2 = Offset(
        center.dx + math.cos(rad) * outerRadius * 0.95,
        center.dy + math.sin(rad) * outerRadius * 0.95,
      );
      canvas.drawLine(p1, p2, dividerPaint);
    }

    // Circulo central concavo oscuro
    final centerCirclePaint = Paint()
      ..shader = const LinearGradient(
        begin: Alignment.topLeft,
        end: Alignment.bottomRight,
        colors: [
          Color(0xFF1C1C21),
          Color(0xFF2B2B33),
        ],
      ).createShader(Rect.fromCircle(center: center, radius: innerRadius));
    canvas.drawCircle(center, innerRadius, centerCirclePaint);

    final centerRingPaint = Paint()
      ..color = const Color(0xFF121215)
      ..style = PaintingStyle.stroke
      ..strokeWidth = 3.0;
    canvas.drawCircle(center, innerRadius, centerRingPaint);
  }

  @override
  bool shouldRepaint(covariant _CircularDPadPainter oldDelegate) {
    return oldDelegate.upActive != upActive ||
        oldDelegate.downActive != downActive ||
        oldDelegate.leftActive != leftActive ||
        oldDelegate.rightActive != rightActive ||
        oldDelegate.accentColor != accentColor;
  }
}

// =============================================================================
// CUSTOM PAINTER: BASE DEL JOYSTICK CON ANILLO Y MARCAS AXIALES (ESTILO IMAGEN)
// =============================================================================
class _JoystickBasePainter extends CustomPainter {
  final Color accentColor;
  final bool isActive;

  const _JoystickBasePainter({
    required this.accentColor,
    required this.isActive,
  });

  @override
  void paint(Canvas canvas, Size size) {
    final center = Offset(size.width / 2, size.height / 2);
    final outerRadius = size.width * 0.46;
    final innerWellRadius = size.width * 0.34;

    // Anillo guia exterior fino con marcas axiales (arriba, abajo, izquierda, derecha)
    final guideRingPaint = Paint()
      ..color = isActive
          ? accentColor.withValues(alpha: 0.45)
          : Colors.white.withValues(alpha: 0.14)
      ..style = PaintingStyle.stroke
      ..strokeWidth = 1.4;
    canvas.drawCircle(center, outerRadius, guideRingPaint);

    final tickLen = size.width * 0.045;
    for (final angle in [0.0, math.pi / 2, math.pi, 3 * math.pi / 2]) {
      final start = Offset(
        center.dx + math.cos(angle) * (outerRadius - tickLen * 0.5),
        center.dy + math.sin(angle) * (outerRadius - tickLen * 0.5),
      );
      final end = Offset(
        center.dx + math.cos(angle) * (outerRadius + tickLen * 0.5),
        center.dy + math.sin(angle) * (outerRadius + tickLen * 0.5),
      );
      canvas.drawLine(start, end, guideRingPaint);
    }

    // Cavidad oscura central donde se mueve el stick
    final wellPaint = Paint()
      ..shader = RadialGradient(
        colors: [
          const Color(0xFF0E0E11),
          const Color(0xFF1B1B20),
          const Color(0xFF282830),
        ],
        stops: const [0.0, 0.72, 1.0],
      ).createShader(Rect.fromCircle(center: center, radius: innerWellRadius));
    canvas.drawCircle(center, innerWellRadius, wellPaint);

    final wellBorderPaint = Paint()
      ..color = isActive
          ? accentColor.withValues(alpha: 0.55)
          : const Color(0xFF383842)
      ..style = PaintingStyle.stroke
      ..strokeWidth = 2.0;
    canvas.drawCircle(center, innerWellRadius, wellBorderPaint);
  }

  @override
  bool shouldRepaint(covariant _JoystickBasePainter oldDelegate) {
    return oldDelegate.isActive != isActive ||
        oldDelegate.accentColor != accentColor;
  }
}
