import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

import '../l10n/app_localizations.dart';
import 'business_api.dart';
import 'business_command.dart';

enum _PhotoReviewResult { attached, retake, cancelled }

class TaskConversationScreen extends ConsumerStatefulWidget {
  const TaskConversationScreen({
    super.key,
    required this.jobId,
    required this.taskId,
    required this.taskTitle,
  });
  final String jobId, taskId, taskTitle;

  @override
  ConsumerState<TaskConversationScreen> createState() =>
      _TaskConversationScreenState();
}

class _TaskConversationScreenState
    extends ConsumerState<TaskConversationScreen> {
  static const _photoChannel = MethodChannel('clockrove/documents/v1');
  final _body = TextEditingController();
  late Future<TaskConversation> _data = _read();
  String? _editing;
  String? _notice;

  Future<TaskConversation> _read() => ref
      .read(businessApiProvider)
      .taskConversation(widget.jobId, widget.taskId);

  void _refresh() {
    setState(() {
      _data = _read();
      _notice = null;
    });
  }

  @override
  void dispose() {
    _body.dispose();
    super.dispose();
  }

  String _date(int millis) {
    final value = DateTime.fromMillisecondsSinceEpoch(millis).toLocal();
    final localizations = MaterialLocalizations.of(context);
    return '${localizations.formatMediumDate(value)} · '
        '${localizations.formatTimeOfDay(TimeOfDay.fromDateTime(value))}';
  }

  Future<void> _choosePhoto(
    TaskConversation conversation, {
    String? commentId,
    required bool camera,
  }) async {
    if (conversation.archived) return;
    while (mounted) {
      List<Uint8List>? images;
      try {
        images = await _photoChannel.invokeListMethod<Uint8List>(
          camera ? 'captureTaskPhoto' : 'pickTaskPhoto',
        );
      } on PlatformException catch (_) {
        if (mounted) {
          setState(() => _notice = AppMessage.taskPhotoOpenFailed.of(context));
        }
        return;
      }
      if (!mounted || images == null || images.isEmpty) return;
      final selectedImages = images;
      var reviewIndex = 0;
      final reviewController = PageController();
      final decision = await showDialog<_PhotoReviewResult>(
        context: context,
        barrierDismissible: false,
        builder: (dialogContext) => StatefulBuilder(
          builder: (dialogContext, setDialogState) => AlertDialog(
            title: Text(
              selectedImages.length == 1
                  ? AppMessage.taskPhotoReviewOne.of(dialogContext)
                  : AppMessage.taskPhotoReviewMany.format(dialogContext, {
                      'count': MaterialLocalizations.of(dialogContext)
                          .formatDecimal(selectedImages.length),
                    }),
            ),
            content: ConstrainedBox(
              constraints: BoxConstraints(
                maxHeight: MediaQuery.sizeOf(dialogContext).height * .55,
                maxWidth: 520,
              ),
              child: SizedBox(
                height: MediaQuery.sizeOf(dialogContext).height * .48,
                width: 480,
                child: Column(
                  children: [
                    if (selectedImages.length > 1) ...[
                      Text(
                        AppMessage.taskPhotoPage.format(dialogContext, {
                          'page': MaterialLocalizations.of(dialogContext)
                              .formatDecimal(reviewIndex + 1),
                          'total': MaterialLocalizations.of(dialogContext)
                              .formatDecimal(selectedImages.length),
                        }),
                      ),
                      const SizedBox(height: 8),
                    ],
                    Expanded(
                      child: PageView.builder(
                        controller: reviewController,
                        itemCount: selectedImages.length,
                        onPageChanged: (index) =>
                            setDialogState(() => reviewIndex = index),
                        itemBuilder: (context, index) => Image.memory(
                          selectedImages[index],
                          fit: BoxFit.contain,
                        ),
                      ),
                    ),
                  ],
                ),
              ),
            ),
            actions: [
              TextButton(
                onPressed: () =>
                    Navigator.pop(dialogContext, _PhotoReviewResult.cancelled),
                child: Text(AppMessage.cancel.of(dialogContext)),
              ),
              if (camera)
                TextButton.icon(
                  onPressed: () =>
                      Navigator.pop(dialogContext, _PhotoReviewResult.retake),
                  icon: const Icon(Icons.refresh),
                  label: Text(AppMessage.taskPhotoRetake.of(dialogContext)),
                ),
              BusinessCommand(
                label: selectedImages.length == 1
                    ? AppMessage.taskPhotoAttachOne.of(dialogContext)
                    : AppMessage.taskPhotoAttachMany.of(dialogContext),
                create: () {
                  final id = newBusinessId();
                  final attachments = [
                    for (final image in selectedImages)
                      MapEntry(newBusinessId(), image),
                  ];
                  final api = ref.read(businessApiProvider);
                  return () => api.attachTaskImages(
                    id,
                    conversation,
                    attachments,
                    commentId,
                  );
                },
                onCommitted: () {
                  Navigator.pop(dialogContext, _PhotoReviewResult.attached);
                },
              ),
            ],
          ),
        ),
      );
      reviewController.dispose();
      if (decision == _PhotoReviewResult.retake && camera) continue;
      if (decision == _PhotoReviewResult.attached && mounted) {
        // Dismissing the review route can restore focus to the comment editor.
        // Clear it only after the route is gone so the keyboard cannot remain
        // over the newly attached image or consume the next Back action.
        FocusManager.instance.primaryFocus?.unfocus();
        _refresh();
        final count = MaterialLocalizations.of(context)
            .formatDecimal(selectedImages.length);
        final attached = commentId == null
            ? selectedImages.length == 1
                  ? AppMessage.taskPhotoAttachedTaskOne.of(context)
                  : AppMessage.taskPhotoAttachedTaskMany.format(context, {
                      'count': count,
                    })
            : selectedImages.length == 1
            ? AppMessage.taskPhotoAttachedCommentOne.of(context)
            : AppMessage.taskPhotoAttachedCommentMany.format(context, {
                'count': count,
              });
        ScaffoldMessenger.of(context)
            .showSnackBar(SnackBar(content: Text(attached)));
      }
      return;
    }
  }

  Future<void> _viewPhoto(
    TaskConversation conversation,
    TaskAttachmentInfo attachment,
  ) async {
    final related =
        <TaskAttachmentInfo>[
          ...conversation.taskAttachments,
          for (final comment in conversation.comments) ...comment.attachments,
        ]..sort((left, right) {
          final byTime = left.createdMillis.compareTo(right.createdMillis);
          return byTime != 0 ? byTime : left.id.compareTo(right.id);
        });
    final initialIndex = related.indexWhere((item) => item.id == attachment.id);
    if (!mounted || initialIndex < 0) return;
    await Navigator.push<void>(
      context,
      MaterialPageRoute<void>(
        builder: (_) =>
            _TaskPhotoViewer(attachments: related, initialIndex: initialIndex),
      ),
    );
  }

  void _edit(TaskComment comment) {
    setState(() {
      _editing = comment.id;
      _body.text = comment.body;
      _notice = null;
    });
  }

  void _cancelEdit() {
    setState(() {
      _editing = null;
      _body.clear();
    });
  }

  Widget _attachments(
    TaskConversation conversation,
    List<TaskAttachmentInfo> attachments,
  ) => Wrap(
    spacing: 8,
    runSpacing: 8,
    children: [
      for (final attachment in attachments)
        _TaskAttachmentThumb(
          attachment: attachment,
          onTap: () => _viewPhoto(conversation, attachment),
          onRemove: conversation.archived
              ? null
              : () => _confirmRemovePhoto(conversation, attachment),
        ),
    ],
  );

  Future<void> _confirmRemovePhoto(
    TaskConversation conversation,
    TaskAttachmentInfo attachment,
  ) => showDialog<void>(
    context: context,
    barrierDismissible: false,
    builder: (dialogContext) => AlertDialog(
      title: Text(AppMessage.taskPhotoRemoveTitle.of(dialogContext)),
      content: Text(AppMessage.taskPhotoRemoveBody.of(dialogContext)),
      actions: [
        TextButton(
          onPressed: () => Navigator.pop(dialogContext),
          child: Text(AppMessage.cancel.of(dialogContext)),
        ),
        BusinessCommand(
          label: AppMessage.taskPhotoRemove.of(dialogContext),
          create: () {
            final id = newBusinessId();
            final api = ref.read(businessApiProvider);
            return () => api.removeTaskImage(id, conversation, attachment.id);
          },
          onCommitted: () {
            Navigator.pop(dialogContext);
            _refresh();
          },
        ),
      ],
    ),
  );

  Future<void> _confirmRemoveComment(
    TaskConversation conversation,
    TaskComment comment,
  ) => showDialog<void>(
    context: context,
    barrierDismissible: false,
    builder: (dialogContext) => AlertDialog(
      title: Text(AppMessage.taskCommentDeleteTitle.of(dialogContext)),
      content: Text(AppMessage.taskCommentDeleteBody.of(dialogContext)),
      actions: [
        TextButton(
          onPressed: () => Navigator.pop(dialogContext),
          child: Text(AppMessage.cancel.of(dialogContext)),
        ),
        BusinessCommand(
          label: AppMessage.taskCommentDelete.of(dialogContext),
          create: () {
            final id = newBusinessId();
            final api = ref.read(businessApiProvider);
            return () => api.removeTaskComment(id, conversation, comment.id);
          },
          onCommitted: () {
            Navigator.pop(dialogContext);
            if (_editing == comment.id) _cancelEdit();
            _refresh();
          },
        ),
      ],
    ),
  );

  @override
  Widget build(BuildContext context) => Scaffold(
    appBar: AppBar(title: Text(AppMessage.taskConversationTitle.of(context))),
    body: FutureBuilder<TaskConversation>(
      future: _data,
      builder: (context, snapshot) {
        if (snapshot.hasError) {
          return Center(
            child: TextButton.icon(
              onPressed: _refresh,
              icon: const Icon(Icons.refresh),
              label: Text(AppMessage.taskConversationRetry.of(context)),
            ),
          );
        }
        if (!snapshot.hasData) {
          return const Center(child: CircularProgressIndicator());
        }
        final conversation = snapshot.data!;
        final readOnly = conversation.archived;
        final comments = conversation.comments;
        return ListView(
          padding: const EdgeInsets.fromLTRB(20, 12, 20, 32),
          children: [
            Text(
              widget.taskTitle,
              style: Theme.of(context).textTheme.headlineSmall,
            ),
            const SizedBox(height: 8),
            Text(
              readOnly
                  ? AppMessage.taskConversationArchivedHelp.of(context)
                  : AppMessage.taskConversationPrivacyHelp.of(context),
            ),
            if (_notice != null) ...[
              const SizedBox(height: 12),
              Text(
                _notice!,
                style: TextStyle(color: Theme.of(context).colorScheme.error),
              ),
            ],
            const SizedBox(height: 20),
            Text(
              AppMessage.taskConversationPhotosTitle.of(context),
              style: Theme.of(context).textTheme.titleLarge,
            ),
            const SizedBox(height: 8),
            _attachments(conversation, conversation.taskAttachments),
            if (!readOnly) ...[
              const SizedBox(height: 8),
              Wrap(
                spacing: 8,
                children: [
                  OutlinedButton.icon(
                    onPressed: () => _choosePhoto(conversation, camera: false),
                    icon: const Icon(Icons.photo_library_outlined),
                    label: Text(
                      AppMessage.taskConversationChoosePhotos.of(context),
                    ),
                  ),
                  OutlinedButton.icon(
                    onPressed: () => _choosePhoto(conversation, camera: true),
                    icon: const Icon(Icons.camera_alt_outlined),
                    label: Text(
                      AppMessage.taskConversationTakePhoto.of(context),
                    ),
                  ),
                ],
              ),
            ],
            const SizedBox(height: 24),
            Text(
              AppMessage.taskConversationCommentsTitle.of(context),
              style: Theme.of(context).textTheme.titleLarge,
            ),
            const SizedBox(height: 8),
            if (comments.isEmpty)
              Card(
                child: Padding(
                  padding: const EdgeInsets.all(16),
                  child: Text(
                    AppMessage.taskConversationNoComments.of(context),
                  ),
                ),
              ),
            for (final comment in comments)
              Card(
                child: Padding(
                  padding: const EdgeInsets.all(16),
                  child: Column(
                    crossAxisAlignment: CrossAxisAlignment.stretch,
                    children: [
                      Row(
                        children: [
                          Expanded(
                            child: Text(
                              _date(comment.createdMillis),
                              style: Theme.of(context).textTheme.labelMedium,
                            ),
                          ),
                          if (comment.updatedMillis > comment.createdMillis)
                            Padding(
                              padding: const EdgeInsets.only(right: 8),
                              child: Text(
                                AppMessage.taskConversationEdited.of(context),
                              ),
                            ),
                          if (!readOnly)
                            IconButton(
                              tooltip: AppMessage.taskConversationEditComment
                                  .of(context),
                              onPressed: () => _edit(comment),
                              icon: const Icon(Icons.edit_outlined),
                            ),
                          if (!readOnly)
                            IconButton(
                              tooltip: AppMessage.taskCommentDelete.of(context),
                              onPressed: () =>
                                  _confirmRemoveComment(conversation, comment),
                              icon: const Icon(Icons.delete_outline),
                            ),
                        ],
                      ),
                      SelectableText(comment.body),
                      if (comment.attachments.isNotEmpty) ...[
                        const SizedBox(height: 12),
                        _attachments(conversation, comment.attachments),
                      ],
                      if (!readOnly)
                        Wrap(
                          spacing: 8,
                          children: [
                            TextButton.icon(
                              onPressed: () => _choosePhoto(
                                conversation,
                                commentId: comment.id,
                                camera: false,
                              ),
                              icon: const Icon(Icons.photo_library_outlined),
                              label: Text(
                                AppMessage.taskConversationChooseCommentPhotos
                                    .of(context),
                              ),
                            ),
                            TextButton.icon(
                              onPressed: () => _choosePhoto(
                                conversation,
                                commentId: comment.id,
                                camera: true,
                              ),
                              icon: const Icon(Icons.add_a_photo_outlined),
                              label: Text(
                                AppMessage.taskConversationTakeCommentPhoto.of(
                                  context,
                                ),
                              ),
                            ),
                          ],
                        ),
                    ],
                  ),
                ),
              ),
            if (!readOnly) ...[
              const SizedBox(height: 12),
              Semantics(
                identifier: 'task-comment-body',
                child: TextField(
                  controller: _body,
                  minLines: 3,
                  maxLines: 7,
                  maxLength: 2000,
                  onChanged: (_) => setState(() {}),
                  decoration: InputDecoration(
                    labelText: _editing == null
                        ? AppMessage.taskConversationAddCommentField.of(context)
                        : AppMessage.taskConversationEditComment.of(context),
                    hintText: AppMessage.taskConversationWriteUpdate.of(
                      context,
                    ),
                    border: const OutlineInputBorder(),
                  ),
                ),
              ),
              if (_editing != null)
                Align(
                  alignment: Alignment.centerRight,
                  child: TextButton(
                    onPressed: _cancelEdit,
                    child: Text(
                      AppMessage.taskConversationCancelEdit.of(context),
                    ),
                  ),
                ),
              BusinessCommand(
                label: _editing == null
                    ? AppMessage.taskConversationAddComment.of(context)
                    : AppMessage.taskConversationSaveComment.of(context),
                enabled:
                    _body.text.trim().isNotEmpty && _body.text.length <= 2000,
                onPendingChanged: (_) {},
                create: () {
                  final commandId = newBusinessId();
                  final commentId = _editing ?? newBusinessId();
                  final body = _body.text;
                  final api = ref.read(businessApiProvider);
                  return () => api.saveTaskComment(
                    commandId,
                    conversation,
                    commentId,
                    body,
                  );
                },
                onCommitted: () {
                  _body.clear();
                  _editing = null;
                  _refresh();
                },
              ),
            ],
            if (conversation.events.isNotEmpty) ...[
              const SizedBox(height: 24),
              Text(
                AppMessage.taskConversationRecentActivity.of(context),
                style: Theme.of(context).textTheme.titleMedium,
              ),
              for (final event in conversation.events)
                ListTile(
                  contentPadding: EdgeInsets.zero,
                  dense: true,
                  leading: Icon(switch (event.action) {
                    'ADDED' => Icons.add_circle_outline,
                    'EDITED' => Icons.edit_outlined,
                    _ => Icons.delete_outline,
                  }),
                  title: Text(switch (event.action) {
                    'ADDED' => AppMessage.taskConversationCommentAdded.of(
                      context,
                    ),
                    'EDITED' => AppMessage.taskConversationCommentEdited.of(
                      context,
                    ),
                    _ => AppMessage.taskConversationCommentDeleted.of(context),
                  }),
                  subtitle: Text(_date(event.recordedMillis)),
                ),
            ],
          ],
        );
      },
    ),
  );
}

class _TaskPhotoViewer extends ConsumerStatefulWidget {
  const _TaskPhotoViewer({
    required this.attachments,
    required this.initialIndex,
  });
  final List<TaskAttachmentInfo> attachments;
  final int initialIndex;

  @override
  ConsumerState<_TaskPhotoViewer> createState() => _TaskPhotoViewerState();
}

class _TaskPhotoViewerState extends ConsumerState<_TaskPhotoViewer> {
  late final PageController _controller;
  final Map<String, Future<Uint8List?>> _images = {};
  late int _index;

  @override
  void initState() {
    super.initState();
    _index = widget.initialIndex;
    _controller = PageController(initialPage: _index);
  }

  Future<Uint8List?> _load(String id) => _images.putIfAbsent(
    id,
    () => ref.read(businessApiProvider).taskAttachment(id),
  );

  @override
  void dispose() {
    _controller.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final count = widget.attachments.length;
    return Scaffold(
      backgroundColor: Colors.black,
      appBar: AppBar(
        backgroundColor: Colors.black,
        foregroundColor: Colors.white,
        title: Text(
          count == 1
              ? AppMessage.taskPhotoViewerOne.of(context)
              : AppMessage.taskPhotoPage.format(context, {
                  'page': MaterialLocalizations.of(context)
                      .formatDecimal(_index + 1),
                  'total': MaterialLocalizations.of(context)
                      .formatDecimal(count),
                }),
        ),
      ),
      body: PageView.builder(
        controller: _controller,
        itemCount: count,
        onPageChanged: (index) => setState(() => _index = index),
        itemBuilder: (context, index) {
          final attachment = widget.attachments[index];
          return FutureBuilder<Uint8List?>(
            future: _load(attachment.id),
            builder: (context, snapshot) {
              if (snapshot.hasError ||
                  (snapshot.hasData && snapshot.data == null)) {
                return Center(
                  child: Text(
                    AppMessage.taskPhotoUnavailable.of(context),
                    style: TextStyle(color: Colors.white),
                  ),
                );
              }
              if (!snapshot.hasData) {
                return const Center(
                  child: CircularProgressIndicator(color: Colors.white),
                );
              }
              return Center(
                child: Semantics(
                  image: true,
                  label: AppMessage.taskPhotoSemantics.format(context, {
                    'page': MaterialLocalizations.of(context)
                        .formatDecimal(index + 1),
                    'total': MaterialLocalizations.of(context)
                        .formatDecimal(count),
                  }),
                  child: Image.memory(snapshot.data!, fit: BoxFit.contain),
                ),
              );
            },
          );
        },
      ),
    );
  }
}

class _TaskAttachmentThumb extends ConsumerWidget {
  const _TaskAttachmentThumb({
    required this.attachment,
    required this.onTap,
    required this.onRemove,
  });
  final TaskAttachmentInfo attachment;
  final VoidCallback onTap;
  final VoidCallback? onRemove;

  @override
  Widget build(BuildContext context, WidgetRef ref) =>
      FutureBuilder<Uint8List?>(
        future: ref.read(businessApiProvider).taskAttachment(attachment.id),
        builder: (context, snapshot) => InkWell(
          key: ValueKey('task-attachment-${attachment.id}'),
          onTap: snapshot.data == null ? null : onTap,
          borderRadius: BorderRadius.circular(12),
          child: Container(
            width: 76,
            height: 76,
            clipBehavior: Clip.antiAlias,
            decoration: BoxDecoration(
              color: Theme.of(context).colorScheme.surfaceContainerHighest,
              borderRadius: BorderRadius.circular(12),
            ),
            child: Stack(
              fit: StackFit.expand,
              children: [
                if (snapshot.hasData)
                  Semantics(
                    label: attachment.commentId == null
                        ? AppMessage.taskPhotoTaskAttachment.of(context)
                        : AppMessage.taskPhotoCommentAttachment.of(context),
                    button: true,
                    child: Image.memory(snapshot.data!, fit: BoxFit.cover),
                  )
                else
                  const Center(child: Icon(Icons.image_outlined)),
                if (onRemove != null)
                  Positioned(
                    right: 0,
                    top: 0,
                    child: IconButton.filledTonal(
                      tooltip: AppMessage.taskPhotoRemove.of(context),
                      visualDensity: VisualDensity.compact,
                      iconSize: 18,
                      onPressed: onRemove,
                      icon: const Icon(Icons.close),
                    ),
                  ),
              ],
            ),
          ),
        ),
      );
}
