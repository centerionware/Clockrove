import 'dart:io';
import 'dart:ui' as ui;

import 'package:clockrove/maps/map_provider_settings.dart';
import 'package:flutter/foundation.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter_map/flutter_map.dart';
import 'package:flutter/services.dart';
import 'package:flutter/widgets.dart';
import 'package:flutter_test/flutter_test.dart';

class TestMapProviderSettingsApi implements MapProviderSettingsApi {
  const TestMapProviderSettingsApi();

  @override
  Future<MapProviderSettings> read() async => const MapProviderSettings();

  @override
  Future<MapProviderSettings> reset() async => const MapProviderSettings();

  @override
  Future<MapProviderSettings> save(
    MapProviderSettings settings, {
    String? authSecret,
    bool removeCredential = false,
  }) async => settings;

  @override
  Future<Map<String, String>> tileAuthHeaders() async => const {};
}

void captureMapTestStateErrorStacks() {
  final previous = FlutterError.onError;
  FlutterError.onError = (details) {
    if (details.exceptionAsString().contains('No element')) {
      debugPrint('Map test framework error:\n${details.toString()}');
    }
    previous?.call(details);
  };
  addTearDown(() => FlutterError.onError = previous);
}

Future<void> loadMapPreviewFonts() async {
  final sdk = Platform.environment['FLUTTER_ROOT'];
  if (sdk == null) {
    throw StateError('FLUTTER_ROOT must identify the pinned SDK');
  }
  final text = FontLoader('Roboto');
  for (final name in [
    'Roboto-Regular.ttf',
    'Roboto-Medium.ttf',
    'Roboto-Bold.ttf',
  ]) {
    text.addFont(
      File('$sdk/bin/cache/artifacts/material_fonts/$name')
          .readAsBytes()
          .then(ByteData.sublistView),
    );
  }
  await text.load();
  final icons = FontLoader('MaterialIcons');
  icons.addFont(
    File('$sdk/bin/cache/artifacts/material_fonts/MaterialIcons-Regular.otf')
        .readAsBytes()
        .then(ByteData.sublistView),
  );
  await icons.load();
}

Future<void> saveMapPreview(GlobalKey key, String name) async {
  final boundary =
      key.currentContext!.findRenderObject()! as RenderRepaintBoundary;
  final image = await boundary.toImage(pixelRatio: 2);
  final bytes = await image.toByteData(format: ui.ImageByteFormat.png);
  final directory = Directory('build/ui-preview')..createSync(recursive: true);
  await File('${directory.path}/$name.png')
      .writeAsBytes(bytes!.buffer.asUint8List());
  image.dispose();
}

class SyntheticTiles extends TileProvider {
  SyntheticTiles({this.fail = false});
  final bool fail;
  @override
  ImageProvider getImage(TileCoordinates coordinates, TileLayer options) =>
      MemoryImage(fail ? Uint8List(0) : TileProvider.transparentImage);
}
