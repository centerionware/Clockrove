import 'package:clockrove/automation/automation_api.dart';
import 'package:clockrove/tracking/timer_api.dart';
import 'package:flutter/services.dart';

/// Synthetic UI state only; never evidence of platform registration or delivery.
class FixtureAutomationApi extends AutomationApi {
  int version = 1;
  SiteSettings settings = const SiteSettings();
  final sites = <WorkSite>[];
  List<WorkReview> reviews = [];
  List<WorkReview> acceptedReviews = [];
  DateTime? departed;
  DateTime? deadline;
  bool precise = true;
  bool background = true;
  bool loseNextResponse = false;
  int preciseRequests = 0;
  int backgroundRequests = 0;
  int settingsVisits = 0;
  int positionRequests = 0;
  int positionCancellations = 0;
  Future<SitePosition> Function()? positionResult;
  @override
  Future<SitePosition> currentPosition() async {
    positionRequests++;
    return positionResult == null ? SitePosition(51.5, -0.1, 25, DateTime.utc(2026, 11, 11, 12)) : await positionResult!();
  }
  @override
  Future<void> cancelPosition() async { positionCancellations++; }

  final attempts = <(String, int)>[];
  final _receipts = <String, TimerOutcome>{};
  @override
  Stream<String> get health => const Stream.empty();
  @override
  Future<AutomationSnapshot> snapshot(String jobId) async => AutomationSnapshot(
    version: version,
    settings: settings,
    sites: List.unmodifiable(sites),
    precise: precise,
    background: background,
    backgroundLabel: 'Allow all the time',
    health: settings.enabled ? 'READY' : 'IDLE',
    armed: true,
    suppressed: false,
    working: departed != null,
    departed: departed,
    deadline: deadline,
    reviews: List.unmodifiable(reviews),
  );
  Future<TimerOutcome> _commit(
    String id,
    int expected,
    void Function() apply,
  ) async {
    attempts.add((id, expected));
    if (_receipts[id] case final prior?) return prior;
    if (expected != version) return TimerOutcome(version, 'STALE_STATE');
    apply();
    final outcome = TimerOutcome(++version, null);
    _receipts[id] = outcome;
    if (loseNextResponse) {
      loseNextResponse = false;
      throw PlatformException(code: 'SYNTHETIC_RESPONSE_LOST');
    }
    return outcome;
  }

  @override
  Future<TimerOutcome> saveSite(
    String id,
    int version,
    String jobId,
    WorkSite site,
  ) => _commit(id, version, () {
    sites.removeWhere((value) => value.id == site.id);
    sites.add(site);
  });
  @override
  Future<TimerOutcome> removeSite(String id, int version, String siteId) =>
      _commit(
        id,
        version,
        () => sites.removeWhere((site) => site.id == siteId),
      );
  @override
  Future<TimerOutcome> saveSettings(
    String id,
    int version,
    String jobId,
    SiteSettings value,
  ) => _commit(id, version, () => settings = value);
  @override
  Future<TimerOutcome> acceptReview(String id, int version, String jobId) =>
      _commit(id, version, () {
        acceptedReviews = List.of(reviews);
        reviews = [];
        departed = null;
        deadline = null;
      });
  @override
  Future<void> requestPrecise() async {
    preciseRequests++;
    precise = true;
  }

  @override
  Future<void> requestBackground() async {
    backgroundRequests++;
    background = true;
  }

  @override
  Future<void> openSettings() async {
    settingsVisits++;
  }
}
