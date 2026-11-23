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
    for (final candidate in auditKotlinPresentation(file.readAsStringSync())) {
      report('${file.path}:${candidate.line}', candidate.text);
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
      if (!visitor.machineValueOffsets.contains(offset) &&
          _looksLikeCopy(text))
        (line: parsed.lineInfo.getLocation(offset).lineNumber, text: text),
  ];
}

Iterable<({int line, String text})> auditKotlinPresentation(
  String source,
) sync* {
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
      final regexPattern = line
          .substring(0, match.start)
          .trimRight()
          .endsWith('Regex(');
      final staticText = text.replaceAll(
        RegExp(r'\$\{[^}]*\}|\$[A-Za-z_][A-Za-z0-9_]*'),
        ' ',
      );
      final localizedResourceLookupFragment = text.startsWith(
        r'${strings.getString(R.string.',
      );
      final machineValue =
          text == 'application/pdf' ||
          text.endsWith('.invoices') ||
          text.endsWith('.pdf') ||
          text == 'task-captures' ||
          text.startsWith('io.github.') ||
          RegExp(r'^clockrove/[a-z0-9_-]+/v\d+$').hasMatch(text) ||
          RegExp(r'^[a-z]+(?:[A-Z][A-Za-z0-9]*)+$').hasMatch(text) ||
          RegExp(
            r'^[a-z0-9.+-]+/(?:\*|[a-z0-9.+-]+)$',
            caseSensitive: false,
          ).hasMatch(text) ||
          (text == 'Quote' && line.contains('call.method.endsWith')) ||
          line.contains('setClassName(') ||
          line.contains('createTempFile(');
      if (!localizedResourceLookupFragment &&
          !regexPattern &&
          !machineValue &&
          !_machineDatePattern.hasMatch(text) &&
          _looksLikeCopy(staticText)) {
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
  final machineValueOffsets = <int>{};

  @override
  void visitSimpleStringLiteral(SimpleStringLiteral node) {
    strings.add((node.offset, node.value));
    if (_isMachineValueArgument(node)) machineValueOffsets.add(node.offset);
  }

  @override
  void visitAdjacentStrings(AdjacentStrings node) {
    final value = node.stringValue;
    if (value == null) {
      super.visitAdjacentStrings(node);
    } else {
      strings.add((node.offset, value));
      if (_isMachineValueArgument(node)) machineValueOffsets.add(node.offset);
    }
  }

  @override
  void visitStringInterpolation(StringInterpolation node) {
    final text = node.elements
        .whereType<InterpolationString>()
        .map((element) => element.value)
        .join(' ');
    strings.add((node.offset, text));
    if (_isMachineValueArgument(node)) machineValueOffsets.add(node.offset);
    super.visitStringInterpolation(node);
  }
}

bool _isMachineValueArgument(AstNode node) {
  final parent = node.parent;
  if (parent is ArgumentList) {
    final invocation = parent.parent;
    return parent.arguments.isNotEmpty &&
        identical(parent.arguments.first, node) &&
        invocation is MethodInvocation &&
        invocation.methodName.name == 'https' &&
        invocation.target?.toSource() == 'Uri';
  }
  if (parent is! NamedExpression || parent.parent is! ArgumentList) {
    return false;
  }
  final arguments = parent.parent as ArgumentList;
  final invocation = arguments.parent;
  final name = parent.name.label.name;
  return (name == 'fragment' &&
          invocation is MethodInvocation &&
          invocation.methodName.name == 'replace') ||
      (name == 'userAgentPackageName' &&
          invocation is InstanceCreationExpression);
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
