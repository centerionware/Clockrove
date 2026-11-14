import 'dart:convert';

import 'package:clockrove/business/business_api.dart';
import 'package:clockrove/business/task_conversation_screen.dart';
import 'package:clockrove/tracking/timer_api.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';
import 'package:flutter_test/flutter_test.dart';

final _pixel = base64Decode(
  'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAAEElEQVR4AQEFAPr/AP8AAP8FAAH/+lyI0QAAAABJRU5ErkJggg==',
);

class _Business extends BusinessApi {
  int _version = 1;
  int _at = 100;
  final _comments = <TaskComment>[];
  final _events = <TaskCommentEvent>[];
  final _taskAttachments = <TaskAttachmentInfo>[];
  final _images = <String, Uint8List>{};

  void seedComment(String body) {
    _comments.add(
      TaskComment(
        id: 'existing-comment',
        body: body,
        createdMillis: 1,
        updatedMillis: 1,
        attachments: const [],
      ),
    );
  }

  void seedTaskPhoto(String id, int createdMillis) {
    _taskAttachments.add(
      TaskAttachmentInfo(
        id: id,
        createdMillis: createdMillis,
        bytes: _pixel.length,
      ),
    );
    _images[id] = Uint8List.fromList(_pixel);
  }

  void seedCommentPhoto(String commentId, String id, int createdMillis) {
    final index = _comments.indexWhere((comment) => comment.id == commentId);
    final previous = _comments[index];
    final attachment = TaskAttachmentInfo(
      id: id,
      createdMillis: createdMillis,
      bytes: _pixel.length,
      commentId: commentId,
    );
    _comments[index] = TaskComment(
      id: previous.id,
      body: previous.body,
      createdMillis: previous.createdMillis,
      updatedMillis: previous.updatedMillis,
      attachments: [...previous.attachments, attachment],
    );
    _images[id] = Uint8List.fromList(_pixel);
  }

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
  Future<TimerOutcome> attachTaskImages(
    String commandId,
    TaskConversation conversation,
    List<MapEntry<String, Uint8List>> images,
    String? commentId,
  ) async {
    for (final image in images) {
      _images[image.key] = image.value;
      final info = TaskAttachmentInfo(
        id: image.key,
        createdMillis: _at++,
        bytes: image.value.length,
        commentId: commentId,
      );
      if (commentId == null) {
        _taskAttachments.add(info);
      } else {
        final index = _comments.indexWhere((item) => item.id == commentId);
        final previous = _comments[index];
        _comments[index] = TaskComment(
          id: previous.id,
          body: previous.body,
          createdMillis: previous.createdMillis,
          updatedMillis: previous.updatedMillis,
          attachments: [...previous.attachments, info],
        );
      }
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
    tester.view.physicalSize = const Size(420, 2600);
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

  testWidgets('task comment can be added', (tester) async {
    final api = _Business();
    await show(tester, api);
    await tester.enterText(find.byType(TextField), 'Tiles are on site.');
    await tester.pumpAndSettle();
    final add = find.widgetWithText(FilledButton, 'Add comment');
    expect(tester.widget<FilledButton>(add).onPressed, isNotNull);
    await tester.tap(add);
    await tester.pumpAndSettle();
    expect(find.text('Tiles are on site.'), findsOneWidget);
    expect(find.byType(TextField).evaluate(), hasLength(1));
    expect(
      tester.widget<TextField>(find.byType(TextField)).controller!.text,
      isEmpty,
    );
    expect(api._events.map((event) => event.action), ['ADDED']);
  });

  testWidgets('existing task comment can be edited', (tester) async {
    final api = _Business()..seedComment('Delivery arrived.');
    await show(tester, api);
    expect(find.text('Delivery arrived.'), findsOneWidget);
    final edit = find.byTooltip('Edit comment');
    await tester.tap(edit);
    await tester.pumpAndSettle();
    final editor = find.byType(TextField);
    await tester.enterText(editor, 'Tiles are ready to install.');
    await tester.tap(find.text('Save comment'));
    await tester.pumpAndSettle();
    expect(find.text('Tiles are ready to install.'), findsOneWidget);
    expect(api._events.map((event) => event.action), ['EDITED']);
  });

  testWidgets('existing task comment can be deleted with confirmation', (
    tester,
  ) async {
    final api = _Business()..seedComment('Remove this note.');
    await show(tester, api);
    final delete = find.byTooltip('Delete comment');
    await tester.tap(delete);
    await tester.pumpAndSettle();
    await tester.tap(find.widgetWithText(FilledButton, 'Delete comment'));
    await tester.pumpAndSettle();
    expect(find.text('Remove this note.'), findsNothing);
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
            return [Uint8List.fromList(_pixel)];
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
    final api = _Business()..seedComment('Before repair.');
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(photoChannel, (call) async {
          expect(call.method, 'pickTaskPhoto');
          return [Uint8List.fromList(_pixel)];
        });
    addTearDown(
      () => TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(photoChannel, null),
    );
    await show(tester, api);
    final choosePhoto = find.text('Choose photos for comment');
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
      await tester.tap(find.widgetWithText(OutlinedButton, 'Choose photos'));
      await tester.pumpAndSettle();
      expect(find.text('No image picker is installed'), findsOneWidget);
      expect(api._images, isEmpty);
    },
  );

  testWidgets('multiple gallery photos are reviewed and attached together', (
    tester,
  ) async {
    final api = _Business();
    TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
        .setMockMethodCallHandler(photoChannel, (call) async {
          expect(call.method, 'pickTaskPhoto');
          return [Uint8List.fromList(_pixel), Uint8List.fromList(_pixel)];
        });
    addTearDown(
      () => TestDefaultBinaryMessengerBinding.instance.defaultBinaryMessenger
          .setMockMethodCallHandler(photoChannel, null),
    );
    await show(tester, api);
    await tester.tap(find.widgetWithText(OutlinedButton, 'Choose photos'));
    await tester.pumpAndSettle();
    expect(find.text('Review 2 photos'), findsOneWidget);
    expect(find.text('Photo 1 of 2'), findsOneWidget);
    expect(api._images, isEmpty);
    await tester.drag(find.byType(PageView).last, const Offset(-300, 0));
    await tester.pumpAndSettle();
    expect(find.text('Photo 2 of 2'), findsOneWidget);
    await tester.tap(find.text('Attach photos'));
    await tester.pumpAndSettle();
    expect(api._taskAttachments, hasLength(2));
    expect(api._images, hasLength(2));
    expect(find.text('2 photos attached to this task.'), findsOneWidget);
  });

  testWidgets(
    'full-screen task photo swipes through task and comment images in time order',
    (tester) async {
      final api = _Business()
        ..seedTaskPhoto('task-photo-oldest', 10)
        ..seedComment('Progress update')
        ..seedCommentPhoto('existing-comment', 'comment-photo-middle', 20)
        ..seedTaskPhoto('task-photo-newest', 30);
      await show(tester, api);

      final commentPhoto = find.byKey(
        const ValueKey('task-attachment-comment-photo-middle'),
      );
      expect(tester.widget<InkWell>(commentPhoto).onTap, isNotNull);
      await tester.tapAt(
        tester.getCenter(commentPhoto) + const Offset(-20, 20),
      );
      await tester.pumpAndSettle();
      expect(find.text('Photo 2 of 3'), findsOneWidget);
      await tester.drag(find.byType(PageView).last, const Offset(-300, 0));
      await tester.pumpAndSettle();
      expect(find.text('Photo 1 of 3'), findsOneWidget);
      await tester.drag(find.byType(PageView).last, const Offset(300, 0));
      await tester.pumpAndSettle();
      expect(find.text('Photo 2 of 3'), findsOneWidget);
      await tester.drag(find.byType(PageView).last, const Offset(300, 0));
      await tester.pumpAndSettle();
      expect(find.text('Photo 3 of 3'), findsOneWidget);
    },
  );
}
