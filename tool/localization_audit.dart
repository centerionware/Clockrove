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
    for (final (offset, end, text) in visitor.strings)
      if (!_isDartDirectiveString(source, offset) &&
          !_isMachineDartValue(source, offset, end, text) &&
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
        line.contains('android.util.Log.') ||
        line.contains('protected.replace(') ||
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
          RegExp(r'^clockrove-[a-z0-9-]+$').hasMatch(text) ||
          text.startsWith('widget-stop-') ||
          text.startsWith('/widget-') ||
          text.startsWith('io.github.') ||
          text.startsWith(r'invoice:$') ||
          text.startsWith(r'copy:$') ||
          text.startsWith(r'quote:$') ||
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
  final strings = <(int, int, String)>[];

  @override
  void visitSimpleStringLiteral(SimpleStringLiteral node) {
    strings.add((node.offset, node.end, node.value));
  }

  @override
  void visitAdjacentStrings(AdjacentStrings node) {
    final value = node.stringValue;
    if (value == null) {
      super.visitAdjacentStrings(node);
    } else {
      strings.add((node.offset, node.end, value));
    }
  }

  @override
  void visitStringInterpolation(StringInterpolation node) {
    final text = node.elements
        .whereType<InterpolationString>()
        .map((element) => element.value)
        .join(' ');
    strings.add((node.offset, node.end, text));
    super.visitStringInterpolation(node);
  }
}

bool _isMachineDartValue(String source, int offset, int end, String text) {
  final prefix = source.substring(0, offset);
  final contextStart = (prefix.length - 256).clamp(0, prefix.length).toInt();
  final context = prefix.substring(contextStart);
  final value = text.trim();
  final isCamelCaseIdentifier =
      RegExp(r'^[a-z][A-Za-z0-9_]*$').hasMatch(value) &&
      RegExp(r'[A-Z0-9_]').hasMatch(value);
  final formatStart = context.lastIndexOf('.format(');
  final formatMapStart = context.lastIndexOf('{');
  final isFormatParameter =
      isCamelCaseIdentifier &&
      source.substring(end).trimLeft().startsWith(':') &&
      formatStart >= 0 &&
      formatMapStart > formatStart;
  final isBridgeField =
      isCamelCaseIdentifier &&
      (RegExp(r'config\s*\[\s*$').hasMatch(context) ||
          (source.substring(end).trimLeft().startsWith(':') &&
              context.contains('invokeMapMethod')));
  final isWidgetConfigurationField =
      const {'jobId', 'taskOnly'}.contains(value) &&
      context.contains('widgetChannel');
  final isChannelMethod =
      const {
        'companyLogo',
        'pickLogo',
        'applyLogo',
        'removeLogo',
      }.contains(value) &&
      (context.contains('invokeMapMethod') || context.contains('_change('));
  final isProtocolChannel = RegExp(r'^clockrove/[a-z0-9_-]+/v\d+$')
      .hasMatch(value);
  final isBusinessRecordKey = RegExp(
    r'^(?:quote-invoice/|void-|balance-|receipt-|task/|scope/)[A-Za-z0-9_/\- ]*$',
  ).hasMatch(value);
  final isUriHost =
      RegExp(r'^[A-Za-z0-9.-]+\.[A-Za-z]{2,}$').hasMatch(value) &&
      RegExp(r'Uri\s*\.\s*https\s*\(\s*$').hasMatch(context);
  final isUriFragment =
      value.startsWith('map=17/') &&
      RegExp(r'\.replace\s*\(\s*fragment\s*:\s*$').hasMatch(context);
  final isPlatformPackage =
      value == 'io.github.centerionware.clockrove' &&
      RegExp(r'userAgentPackageName\s*:\s*$').hasMatch(context);
  return isFormatParameter ||
      isBridgeField ||
      isWidgetConfigurationField ||
      isChannelMethod ||
      isProtocolChannel ||
      isBusinessRecordKey ||
      isUriHost ||
      isUriFragment ||
      isPlatformPackage;
}

bool _isDartDirectiveString(String source, int offset) {
  final lineBreak = offset == 0 ? -1 : source.lastIndexOf('\n', offset - 1);
  final lineStart = lineBreak + 1;
  final prefix = source.substring(lineStart, offset).trimLeft();
  return prefix.startsWith('import ') ||
      prefix.startsWith('export ') ||
      prefix.startsWith('part ');
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
