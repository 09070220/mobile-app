import 'package:cobble/background/notification/notification_manager.dart';
import 'package:cobble/domain/calendar/calendar_pin_convert.dart';
import 'package:cobble/domain/calendar/calendar_syncer.db.dart';
import 'package:cobble/domain/connection/connection_state_provider.dart';
import 'package:cobble/domain/db/dao/notification_channel_dao.dart';
import 'package:cobble/domain/db/dao/timeline_pin_dao.dart';
import 'package:cobble/domain/db/models/notification_channel.dart';
import 'package:cobble/domain/db/models/timeline_pin.dart';
import 'package:cobble/domain/entities/pebble_device.dart';
import 'package:cobble/domain/logging.dart';
import 'package:cobble/domain/timeline/watch_timeline_syncer.dart';
import 'package:cobble/infrastructure/datasources/preferences.dart';
import 'package:cobble/infrastructure/pigeons/pigeons.g.dart';
import 'package:cobble/util/container_extensions.dart';
import 'package:flutter/widgets.dart';
import 'package:hooks_riverpod/all.dart';
import 'package:uuid_type/uuid_type.dart';

import 'actions/master_action_handler.dart';

void main_background() {
  WidgetsFlutterBinding.ensureInitialized();

  BackgroundReceiver();
}

class BackgroundReceiver implements CalendarCallbacks, TimelineCallbacks, NotificationListening {
  final container = ProviderContainer();
  late CalendarSyncer calendarSyncer;
  late WatchTimelineSyncer watchTimelineSyncer;
  Future<Preferences>? preferences;
  late TimelinePinDao timelinePinDao;
  late MasterActionHandler masterActionHandler;
  late NotificationManager notificationManager;
  late NotificationChannelDao _notificationChannelDao;

  late ProviderSubscription<WatchConnectionState> connectionSubscription;

  BackgroundReceiver() {
    init();
  }

  void init() async {
    await BackgroundControl().notifyFlutterBackgroundStarted();

    calendarSyncer = container.listen(calendarSyncerProvider!).read();
    notificationManager = container.listen(notificationManagerProvider).read();
    watchTimelineSyncer = container.listen(watchTimelineSyncerProvider!).read();
    timelinePinDao = container.listen(timelinePinDaoProvider!).read();
    preferences = Future.microtask(() async {
      final asyncValue =
          await container.readUntilFirstSuccessOrError(preferencesProvider);

      return asyncValue.data!.value;
    });
    masterActionHandler = container.read(masterActionHandlerProvider);
    _notificationChannelDao = container.listen(notifChannelDaoProvider).read();

    connectionSubscription = container.listen(
      connectionStateProvider!.state,
      mayHaveChanged: (sub) {
        final currentConnectedWatch = sub.read().currentConnectedWatch;
        if (isConnectedToWatch()! && currentConnectedWatch!.name!.isNotEmpty) {
          onWatchConnected(currentConnectedWatch);
        }
      },
    );

    CalendarCallbacks.setup(this);
    TimelineCallbacks.setup(this);
    NotificationListening.setup(this);
  }

  @override
  Future<void> doFullCalendarSync() async {
    await calendarSyncer.syncDeviceCalendarsToDb();
    await syncTimelineToWatch();
  }

  void onWatchConnected(PebbleDevice watch) async {
    final lastConnectedWatch =
        (await preferences)!.getLastConnectedWatchAddress();
    if (lastConnectedWatch != watch.address) {
      Log.d("Different watch connected than the last one. Resetting DB...");
      await watchTimelineSyncer.clearAllPinsFromWatchAndResync();
    } else if (watch.isUnfaithful!) {
      Log.d("Connected watch has beein unfaithful (tsk, tsk tsk). Reset DB...");
      await watchTimelineSyncer.clearAllPinsFromWatchAndResync();
    } else {
      await syncTimelineToWatch();
    }

    (await preferences)!.setLastConnectedWatchAddress(watch.address!);
  }

  Future syncTimelineToWatch() async {
    if (isConnectedToWatch()!) {
      await watchTimelineSyncer.syncPinDatabaseWithWatch();
    }
  }

  bool? isConnectedToWatch() {
    return connectionSubscription.read().isConnected;
  }

  @override
  Future<void> deleteCalendarPinsFromWatch() async {
    await timelinePinDao.markAllPinsFromAppForDeletion(calendarWatchappId);
    await syncTimelineToWatch();
  }

  @override
  Future<ActionResponsePigeon> handleTimelineAction(ActionTrigger arg) async {
    return (await masterActionHandler.handleTimelineAction(arg))!.toPigeon();
  }

  @override
  Future<TimelinePinPigeon?> handleNotification(NotificationPigeon arg) async {
    TimelinePin notif = await notificationManager.handleNotification(arg);

    return notif.toPigeon();
  }

  @override
  void dismissNotification(StringWrapper arg) {
    notificationManager.dismissNotification(Uuid(arg.value!));
  }

  @override
  Future<BooleanWrapper> shouldNotify(NotifChannelPigeon arg) async {
    NotificationChannel? channel = await _notificationChannelDao.getNotifChannelByIds(arg.channelId, arg.packageId);
    return BooleanWrapper()..value=channel?.shouldNotify ?? true;
  }

  @override
  void updateChannel(NotifChannelPigeon arg) {
    if (arg.delete) {
      _notificationChannelDao.deleteNotifChannelByIds(arg.channelId, arg.packageId);
    }else {
      _notificationChannelDao.getNotifChannelByIds(arg.channelId, arg.packageId).then((existing) {
        final shouldNotify = existing?.shouldNotify ?? true;
        final channel = NotificationChannel(arg.packageId, arg.channelId, shouldNotify, name: arg.channelName, description: arg.channelDesc);
        _notificationChannelDao.insertOrUpdateNotificationChannel(channel);
      });
    }
  }
}
