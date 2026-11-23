import 'dart:io';

import 'package:analyzer/dart/analysis/utilities.dart';
import 'package:analyzer/dart/ast/ast.dart';
import 'package:analyzer/dart/ast/visitor.dart';

const _localizedDart = 'lib/l10n/app_localizations.dart';
final _words = RegExp(r'[A-Za-z]{2,}');
final _technical = RegExp(r'^(?:[A-Z][A-Z0-9_]+|[a-z][a-z0-9_]*)$');
final _kotlinString = RegExp(r'"((?:\\.|[^"\\])*)"');
final _machineDatePattern = RegExp(r"^[yMdHhmsSZX'T:./+ -]+$");
final _androidText = RegExp(
  r'android:(?:text|hint|contentDescription|label)="([^"@?][^"]*)"',
);
final _androidString = RegExp(
  r'<(?:string|plurals|string-array)\s+name="([^"]+)"([^>]*)>',
);

void main(List<String> arguments) {
  var count = 0;
  void report(String location, String message) {
    stdout.writeln('$location: $message');
    count++;
  }

  for (final file in _files('lib', '.dart')) {
    for (final candidate in auditDart(file.path, file.readAsStringSync())) {
      report('${file.path}:${candidate.line}', candidate.text);
    }
  }
  for (final file in _files('android/app/src/main/kotlin', '.kt')) {
    final lines = file.readAsLinesSync();
    for (var index = 0; index < lines.length; index++) {
      if (lines[index].trimLeft().startsWith('//')) continue;
      for (final match in _kotlinString.allMatches(lines[index])) {
        final text = match[1]!;
        if (_looksLikeCopy(text)) report('${file.path}:${index + 1}', text);
      }
    }
  }
  for (final file in _files('android/app/src/main', '.xml')) {
    final lines = file.readAsLinesSync();
    for (var index = 0; index < lines.length; index++) {
      for (final match in _androidText.allMatches(lines[index])) {
        report('${file.path}:${index + 1}', match[1]!);
      }
    }
  }

  final resourceRoot = 'android/app/src/main/res';
  final defaultKeys = _resourceKeys('$resourceRoot/values');
  for (final yaml in _files('localizations', '.yaml')) {
    if (yaml.parent.path != 'localizations') continue;
    final tag = yaml.uri.pathSegments.last.replaceFirst('.yaml', '');
    if (tag == 'en') continue;
    final qualifier = tag.contains('-') ? 'b+${tag.replaceAll('-', '+')}' : tag;
    final missing = defaultKeys.difference(
      _resourceKeys('$resourceRoot/values-$qualifier'),
    );
    if (missing.isNotEmpty) {
      final sorted = missing.toList()..sort();
      report('Android resources ($tag)', sorted.join(', '));
    }
  }
  stdout.writeln('$count localization audit findings.');
  if (arguments.contains('--strict') && count != 0) exitCode = 1;
}

Iterable<({int line, String text})> auditDart(String path, String source) {
  if (path == _localizedDart || path.endsWith('.g.dart')) return const [];
  final parsed = parseString(content: source, path: path);
  final visitor = _StringVisitor();
  parsed.unit.accept(visitor);
  return [
    for (final (offset, text) in visitor.strings)
      if (_looksLikeCopy(text))
        (line: parsed.lineInfo.getLocation(offset).lineNumber, text: text),
  ];
}

Iterable<({int line, String text})> auditKotlinPresentation(String source) sync* {
  final lines = source.split('\n');
  for (var index = 0; index < lines.length; index++) {
    final line = lines[index];
    final trimmed = line.trimLeft();
    if (trimmed.startsWith('//') ||
        line.contains('result.error(') ||
        line.contains('FileNotFoundException(') ||
        line.contains('throw ') ||
        line.contains('check(') ||
        line.contains('require(') ||
        (trimmed.startsWith('"') && trimmed.contains('->'))) {
      continue;
    }
    for (final match in _kotlinString.allMatches(line)) {
      final text = match[1]!;
      final staticText = text.replaceAll(
        RegExp(r'\$\{[^}]*\}|\$[A-Za-z_][A-Za-z0-9_]*'),
        ' ',
      );
      if (!_machineDatePattern.hasMatch(text) && _looksLikeCopy(staticText)) {
        yield (line: index + 1, text: text);
      }
    }
  }
}

Iterable<({int line, String text})> auditAndroidPresentationXml(
  String source,
) sync* {
  final lines = source.split('\n');
  for (var index = 0; index < lines.length; index++) {
    for (final match in _androidText.allMatches(lines[index])) {
      yield (line: index + 1, text: match[1]!);
    }
  }
}

class _StringVisitor extends RecursiveAstVisitor<void> {
  final strings = <(int, String)>[];

  @override
  void visitSimpleStringLiteral(SimpleStringLiteral node) {
    strings.add((node.offset, node.value));
  }

  @override
  void visitAdjacentStrings(AdjacentStrings node) {
    final value = node.stringValue;
    if (value == null) {
      super.visitAdjacentStrings(node);
    } else {
      strings.add((node.offset, value));
    }
  }

  @override
  void visitStringInterpolation(StringInterpolation node) {
    final text = node.elements
        .whereType<InterpolationString>()
        .map((element) => element.value)
        .join(' ');
    strings.add((node.offset, text));
    super.visitStringInterpolation(node);
  }
}

bool _looksLikeCopy(String value) {
  final text = value.trim();
  if (text.isEmpty || text.contains('://')) return false;
  return !_technical.hasMatch(text) && _words.hasMatch(text);
}

Iterable<File> _files(String directory, String extension) {
  final root = Directory(directory);
  if (!root.existsSync()) return const [];
  final files = root
      .listSync(recursive: true, followLinks: false)
      .whereType<File>()
      .where((file) => file.path.endsWith(extension))
      .toList();
  return files..sort((left, right) => left.path.compareTo(right.path));
}

Set<String> _resourceKeys(String directory) => {
  for (final file in _files(directory, '.xml'))
    for (final match in _androidString.allMatches(file.readAsStringSync()))
      if (!RegExp(r'translatable\s*=\s*"false"').hasMatch(match[2]!)) match[1]!,
};
