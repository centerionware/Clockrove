import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:flutter_riverpod/flutter_riverpod.dart';

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
    return '${value.year.toString().padLeft(4, '0')}-${value.month.toString().padLeft(2, '0')}-${value.day.toString().padLeft(2, '0')} ${value.hour.toString().padLeft(2, '0')}:${value.minute.toString().padLeft(2, '0')}';
  }

  Future<void> _choosePhoto(
    TaskConversation conversation, {
    String? commentId,
    required bool camera,
  }) async {
    if (conversation.archived) return;
    while (mounted) {
      Uint8List? image;
      try {
        image = await _photoChannel.invokeMethod<Uint8List>(
          camera ? 'captureTaskPhoto' : 'pickTaskPhoto',
        );
      } on PlatformException catch (error) {
        if (mounted) {
          setState(
            () => _notice = error.message ?? 'The photo could not be opened.',
          );
        }
        return;
      }
      if (!mounted || image == null) return;
      final decision = await showDialog<_PhotoReviewResult>(
        context: context,
        barrierDismissible: false,
        builder: (dialogContext) => AlertDialog(
          title: const Text('Review photo'),
          content: ConstrainedBox(
            constraints: BoxConstraints(
              maxHeight: MediaQuery.sizeOf(dialogContext).height * .55,
              maxWidth: 520,
            ),
            child: Image.memory(image!, fit: BoxFit.contain),
          ),
          actions: [
            TextButton(
              onPressed: () =>
                  Navigator.pop(dialogContext, _PhotoReviewResult.cancelled),
              child: const Text('Cancel'),
            ),
            if (camera)
              TextButton.icon(
                onPressed: () =>
                    Navigator.pop(dialogContext, _PhotoReviewResult.retake),
                icon: const Icon(Icons.refresh),
                label: const Text('Retake'),
              ),
            BusinessCommand(
              label: 'Attach photo',
              create: () {
                final id = newBusinessId();
                final attachmentId = newBusinessId();
                final api = ref.read(businessApiProvider);
                return () => api.attachTaskImage(
                  id,
                  conversation,
                  attachmentId,
                  commentId,
                  image!,
                );
              },
              onCommitted: () {
                Navigator.pop(dialogContext, _PhotoReviewResult.attached);
              },
            ),
          ],
        ),
      );
      if (decision == _PhotoReviewResult.retake && camera) continue;
      if (decision == _PhotoReviewResult.attached && mounted) {
        // Dismissing the review route can restore focus to the comment editor.
        // Clear it only after the route is gone so the keyboard cannot remain
        // over the newly attached image or consume the next Back action.
        FocusManager.instance.primaryFocus?.unfocus();
        _refresh();
        ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(
            content: Text(
              commentId == null
                  ? 'Photo attached to this task.'
                  : 'Photo attached to this comment.',
            ),
          ),
        );
      }
      return;
    }
  }

  Future<void> _viewPhoto(TaskAttachmentInfo attachment) async {
    final image = await ref
        .read(businessApiProvider)
        .taskAttachment(attachment.id);
    if (!mounted) return;
    if (image == null) {
      setState(
        () => _notice =
            'This attachment is no longer available. Refresh the task.',
      );
      return;
    }
    await Navigator.push<void>(
      context,
      MaterialPageRoute<void>(builder: (_) => _TaskPhotoViewer(image: image)),
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
          onTap: () => _viewPhoto(attachment),
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
      title: const Text('Remove photo?'),
      content: const Text(
        'This permanently removes the attached image from this task.',
      ),
      actions: [
        TextButton(
          onPressed: () => Navigator.pop(dialogContext),
          child: const Text('Cancel'),
        ),
        BusinessCommand(
          label: 'Remove photo',
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
      title: const Text('Delete comment?'),
      content: const Text('This also removes photos attached to this comment.'),
      actions: [
        TextButton(
          onPressed: () => Navigator.pop(dialogContext),
          child: const Text('Cancel'),
        ),
        BusinessCommand(
          label: 'Delete comment',
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
    appBar: AppBar(title: const Text('Task notes & photos')),
    body: FutureBuilder<TaskConversation>(
      future: _data,
      builder: (context, snapshot) {
        if (snapshot.hasError) {
          return Center(
            child: TextButton.icon(
              onPressed: _refresh,
              icon: const Icon(Icons.refresh),
              label: const Text('Retry task details'),
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
                  ? 'Archived tasks keep their notes and photos for reference.'
                  : 'Keep progress notes and job-site photos with this task. They stay private and are not printed on invoices.',
            ),
            if (_notice != null) ...[
              const SizedBox(height: 12),
              Text(
                _notice!,
                style: TextStyle(color: Theme.of(context).colorScheme.error),
              ),
            ],
            const SizedBox(height: 20),
            Text('Task photos', style: Theme.of(context).textTheme.titleLarge),
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
                    label: const Text('Choose photo'),
                  ),
                  OutlinedButton.icon(
                    onPressed: () => _choosePhoto(conversation, camera: true),
                    icon: const Icon(Icons.camera_alt_outlined),
                    label: const Text('Take photo'),
                  ),
                ],
              ),
            ],
            const SizedBox(height: 24),
            Text('Comments', style: Theme.of(context).textTheme.titleLarge),
            const SizedBox(height: 8),
            if (comments.isEmpty)
              const Card(
                child: Padding(
                  padding: EdgeInsets.all(16),
                  child: Text(
                    'No comments yet. Add a note about progress or materials.',
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
                            const Padding(
                              padding: EdgeInsets.only(right: 8),
                              child: Text('Edited'),
                            ),
                          if (!readOnly)
                            IconButton(
                              tooltip: 'Edit comment',
                              onPressed: () => _edit(comment),
                              icon: const Icon(Icons.edit_outlined),
                            ),
                          if (!readOnly)
                            IconButton(
                              tooltip: 'Delete comment',
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
                              label: const Text('Choose photo for comment'),
                            ),
                            TextButton.icon(
                              onPressed: () => _choosePhoto(
                                conversation,
                                commentId: comment.id,
                                camera: true,
                              ),
                              icon: const Icon(Icons.add_a_photo_outlined),
                              label: const Text('Take photo for comment'),
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
                        ? 'Add a comment'
                        : 'Edit comment',
                    hintText: 'Write a short update…',
                    border: const OutlineInputBorder(),
                  ),
                ),
              ),
              if (_editing != null)
                Align(
                  alignment: Alignment.centerRight,
                  child: TextButton(
                    onPressed: _cancelEdit,
                    child: const Text('Cancel edit'),
                  ),
                ),
              BusinessCommand(
                label: _editing == null ? 'Add comment' : 'Save comment',
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
                'Recent activity',
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
                    'ADDED' => 'Comment added',
                    'EDITED' => 'Comment edited',
                    _ => 'Comment deleted',
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

class _TaskPhotoViewer extends StatelessWidget {
  const _TaskPhotoViewer({required this.image});
  final Uint8List image;
  @override
  Widget build(BuildContext context) => Scaffold(
    appBar: AppBar(title: const Text('Task photo')),
    body: Center(
      child: InteractiveViewer(child: Image.memory(image, fit: BoxFit.contain)),
    ),
  );
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
                        ? 'Task photo attachment'
                        : 'Comment photo attachment',
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
                      tooltip: 'Remove photo',
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
