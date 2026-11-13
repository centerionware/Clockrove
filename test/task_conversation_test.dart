import 'dart:convert';

import 'package:clockrove/business/business_api.dart';
import 'package:clockrove/business/task_conversation_screen.dart';
import 'package:clockrove/tracking/timer_api.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';

final _pixel = base64Decode(
  'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAIAAACQd1PeAAAADUlEQVR4nGP4z8AAAAMBAQDJ/pLvAAAAAElFTkSuQmCC',
);

class _Business extends BusinessApi {
  int _version = 1;
  int _at = 100;
  final _comments = <TaskComment>[];
  final _events = <TaskCommentEvent>[];
  final _taskAttachments = <TaskAttachmentInfo>[];
  final _images = <String, Uint8List>{};

  TaskConversation _snapshot() => TaskConversation(
    version: _version,
    jobId: 'job',
    taskId: 'task',
    archived: false,
    taskAttachments: List.unmodifiable(_taskAttachments),
    comments: List.unmodifiable(_comments),
    events: List.unmodifiable(_events),
  );

  @override
  Future<TaskConversation> taskConversation(
    String jobId,
    String taskId,
  ) async => _snapshot();

  @override
  Future<Uint8List?> taskAttachment(String attachmentId) async =>
      _images[attachmentId];

  @override
  Future<TimerOutcome> saveTaskComment(
    String commandId,
    TaskConversation conversation,
    String commentId,
    String body,
  ) async {
    final index = _comments.indexWhere((comment) => comment.id == commentId);
    final at = _at++;
    if (index < 0) {
      _comments.add(
        TaskComment(
          id: commentId,
          body: body.trim(),
          createdMillis: at,
          updatedMillis: at,
          attachments: const [],
        ),
      );
      _events.add(TaskCommentEvent(commentId, 'ADDED', at));
    } else {
      final previous = _comments[index];
      _comments[index] = TaskComment(
        id: commentId,
        body: body.trim(),
        createdMillis: previous.createdMillis,
        updatedMillis: at,
        attachments: previous.attachments,
      );
      _events.add(TaskCommentEvent(commentId, 'EDITED', at));
    }
    _version++;
    return TimerOutcome(_version, null);
  }

  @override
  Future<TimerOutcome> removeTaskComment(
    String commandId,
    TaskConversation conversation,
    String commentId,
  ) async {
    final index = _comments.indexWhere((item) => item.id == commentId);
    if (index >= 0) {
      for (final attachment in _comments[index].attachments) {
        _images.remove(attachment.id);
      }
      _comments.removeAt(index);
    }
    _events.add(TaskCommentEvent(commentId, 'DELETED', _at++));
    _version++;
    return TimerOutcome(_version, null);
  }

  @override
  Future<TimerOutcome> attachTaskImage(
    String commandId,
    TaskConversation conversation,
    String attachmentId,
    String? commentId,
    Uint8List bytes,
  ) async {
    _images[attachmentId] = bytes;
    final info = TaskAttachmentInfo(
      id: attachmentId,
      createdMillis: _at++,
      bytes: bytes.length,
      commentId: commentId,
    );
    if (commentId == null) {
      _taskAttachments.add(info);
    } else {
      final index = _comments.indexWhere((comment) => comment.id == commentId);
      final previous = _comments[index];
      _comments[index] = TaskComment(
        id: previous.id,
        body: previous.body,
        createdMillis: previous.createdMillis,
        updatedMillis: previous.updatedMillis,
        attachments: [...previous.attachments, info],
      );
    }
    _version++;
    return TimerOutcome(_version, null);
  }

  @override
  Future<TimerOutcome> removeTaskImage(
    String commandId,
    TaskConversation conversation,
    String attachmentId,
  ) async {
    _images.remove(attachmentId);
    _taskAttachments.removeWhere((item) => item.id == attachmentId);
    for (var index = 0; index < _comments.length; index++) {
      final previous = _comments[index];
      _comments[index] = TaskComment(
        id: previous.id,
        body: previous.body,
        createdMillis: previous.createdMillis,
        updatedMillis: previous.updatedMillis,
        attachments: previous.attachments
            .where((item) => item.id != attachmentId)
            .toList(),
      );
    }
    _version++;
    return TimerOutcome(_version, null);
  }
}

void main() {
  const photoChannel = MethodChannel('clockrove/documents/v1');

  Future<void> show(WidgetTester tester, _Business api) async {
    tester.view.physicalSize = const Size(420, 850);
    tester.view.devicePixelRatio = 1;
    addTearDown(tester.view.resetPhysicalSize);
    addTearDown(tester.view.resetDevicePixelRatio);
    await tester.pumpWidget(
      ProviderScope(
        overrides: [businessApiProvider.overrideWithValue(api)],
        child: const MaterialApp(
          home: TaskConversationScreen(
            jobId: 'job',
            taskId: 'task',
            taskTitle: 'Install floor tile',
          ),
        ),
      ),
    );
    await tester.pumpAndSettle();
  }

  Future<void> reveal(WidgetTester tester, Finder target) async {
    final list = find.byType(ListView).first;
    for (final direction in [-1.0, 1.0]) {
      var attempt = 0;
      while (attempt < 16 && target.evaluate().isEmpty) {
        await tester.drag(list, Offset(0, direction * 280));
        await tester.pumpAndSettle();
        attempt++;
      }
      if (target.evaluate().isNotEmpty) break;
    }
    expect(target, findsOneWidget);
    await tester.ensureVisible(target);
  }

  testWidgets('task comments can be added, edited and deleted', (tester) async {
    final api = _Business();
    await show(tester, api);
    await tester.ensureVisible(find.byType(TextField));
    await tester.pumpAndSettle();
    await tester.enterText(find.byType(TextField), 'Tiles are on site.');
    await tester.tap(find.text('Add comment'));
    await tester.pumpAndSettle();
    expect(find.text('Tiles are on site.'), findsOneWidget);

    final edit = find.byTooltip('Edit comment');
    await reveal(tester, edit);
    await tester.tap(edit);
    await tester.pumpAndSettle();
    final editor = find.byType(TextField);
    await reveal(tester, editor);
    await tester.enterText(editor, 'Tiles are ready to install.');
    await reveal(tester, find.text('Save comment'));
    await tester.tap(find.text('Save comment'));
    await tester.pumpAndSettle();
    expect(find.text('Tiles are ready to install.'), findsOneWidget);
    expect(api._events.map((event) => event.action), ['ADDED', 'EDITED']);

    final delete = find.byTooltip('Delete comment');
    await reveal(tester, delete);
    await tester.tap(delete);
    await tester.pumpAndSettle();
    await tester.tap(find.widgetWithText(FilledButton, 'Delete comment'));
    await tester.pumpAndSettle();
    expect(find.text('Tiles are ready to install.'), findsNothing);
    expect(api._events.last.action, 'DELETED');
  });

  testWidgets(
    'camera photos require review and can be retaken before attaching',
    (tester) async {
      final api = _Business();
      var captures = 0;
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(photoChannel, (call) async {
            expect(call.method, 'captureTaskPhoto');
            captures++;
            return Uint8List.fromList(_pixel);
          });
      addTearDown(
        () => TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
            .setMockMethodCallHandler(photoChannel, null),
      );
      await show(tester, api);

      await tester.tap(find.widgetWithText(OutlinedButton, 'Take photo'));
      await tester.pumpAndSettle();
      expect(find.text('Review photo'), findsOneWidget);
      expect(api._images, isEmpty);
      await tester.tap(find.text('Cancel'));
      await tester.pumpAndSettle();
      expect(api._images, isEmpty);

      await tester.tap(find.widgetWithText(OutlinedButton, 'Take photo'));
      await tester.pumpAndSettle();
      await tester.tap(find.text('Retake'));
      await tester.pumpAndSettle();
      expect(captures, 3);
      expect(find.text('Review photo'), findsOneWidget);
      expect(api._images, isEmpty);
      await tester.tap(find.widgetWithText(FilledButton, 'Attach photo'));
      await tester.pumpAndSettle();
      expect(api._taskAttachments, hasLength(1));
      expect(api._images, hasLength(1));
    },
  );

  testWidgets('gallery photo can be attached to a task comment', (
    tester,
  ) async {
    final api = _Business();
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(photoChannel, (call) async {
          expect(call.method, 'pickTaskPhoto');
          return Uint8List.fromList(_pixel);
        });
    addTearDown(
      () => TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(photoChannel, null),
    );
    await show(tester, api);
    await tester.ensureVisible(find.byType(TextField));
    await tester.pumpAndSettle();
    await tester.enterText(find.byType(TextField), 'Before repair.');
    await tester.tap(find.text('Add comment'));
    await tester.pumpAndSettle();

    final choosePhoto = find.text('Choose photo for comment');
    await reveal(tester, choosePhoto);
    await tester.tap(choosePhoto);
    await tester.pumpAndSettle();
    await tester.tap(find.widgetWithText(FilledButton, 'Attach photo'));
    await tester.pumpAndSettle();
    expect(api._comments.single.attachments, hasLength(1));
    expect(api._taskAttachments, isEmpty);
  });

  testWidgets(
    'unavailable gallery explains the problem without saving a photo',
    (tester) async {
      final api = _Business();
      TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(photoChannel, (call) async {
            throw PlatformException(
              code: 'NO_DOCUMENT_PICKER',
              message: 'No image picker is installed',
            );
          });
      addTearDown(
        () => TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
            .setMockMethodCallHandler(photoChannel, null),
      );
      await show(tester, api);
      await tester.tap(find.widgetWithText(OutlinedButton, 'Choose photo'));
      await tester.pumpAndSettle();
      expect(find.text('No image picker is installed'), findsOneWidget);
      expect(api._images, isEmpty);
    },
  );
}
