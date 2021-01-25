package io.rebble.cobble.bridges.background

import android.content.Context
import android.content.Intent
import android.content.pm.ResolveInfo
import android.service.notification.StatusBarNotification
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import io.rebble.cobble.bridges.FlutterBridge
import io.rebble.cobble.data.NotificationAction
import io.rebble.cobble.data.NotificationMessage
import io.rebble.cobble.data.TimelineAttribute
import io.rebble.cobble.pigeons.BooleanWrapper
import io.rebble.cobble.pigeons.ListWrapper
import io.rebble.cobble.pigeons.Pigeons
import io.rebble.libpebblecommon.packets.blobdb.BlobCommand
import io.rebble.libpebblecommon.packets.blobdb.BlobResponse
import io.rebble.libpebblecommon.packets.blobdb.TimelineItem
import io.rebble.libpebblecommon.services.blobdb.BlobDBService
import io.rebble.libpebblecommon.structmapper.SUUID
import io.rebble.libpebblecommon.structmapper.StructMapper
import kotlinx.coroutines.*
import timber.log.Timber
import java.util.*
import javax.inject.Inject
import kotlin.random.Random


class NotificationsFlutterBridge @Inject constructor(
        private val context: Context,
        private val flutterBackgroundController: FlutterBackgroundController,
        private val moshi: Moshi,
        private val blobDBService: BlobDBService,
) : FlutterBridge {
    private val cacheLifetime = 1000*60*10
    private var lastPackages: MutableMap<String, List<String>> = mutableMapOf()
    private var lastRefresh: MutableMap<String, Long> = mutableMapOf()
    val activeNotifs: MutableMap<UUID, StatusBarNotification> = mutableMapOf()

    private fun getAppsWithCategory(category: String): List<String> {
        if (lastPackages[category] == null || System.currentTimeMillis() - (lastRefresh[category] ?: 0) > cacheLifetime) {
            lastRefresh[category] = System.currentTimeMillis()
            val intent = Intent()
            intent.addCategory(Intent.CATEGORY_APP_EMAIL)
            val resolveInfos: List<ResolveInfo> = context.packageManager.queryIntentActivities(intent, 0)
            lastPackages[category] = resolveInfos.map { it.resolvePackageName }
        }
        return lastPackages[category] ?: listOf()
    }
    private val notifUtils = object : Pigeons.NotificationUtils {
        override fun dismissNotificationWatch(arg: Pigeons.StringWrapper?) {
            val id = UUID.fromString(arg?.value)
            val command = BlobCommand.DeleteCommand(Random.nextInt(0, UShort.MAX_VALUE.toInt()).toUShort(), BlobCommand.BlobDatabase.Notification, SUUID(StructMapper(), id).toBytes())
            GlobalScope.launch {
                var blobResult = blobDBService.send(command)
                while (blobResult.responseValue == BlobResponse.BlobStatus.TryLater) {
                    delay(1000)
                    command.token.set(Random.nextInt(0, UShort.MAX_VALUE.toInt()).toUShort())
                    blobResult = blobDBService.send(command)
                }
            }
        }

        override fun getSMSPackages(): Pigeons.ListWrapper = ListWrapper(getAppsWithCategory(Intent.CATEGORY_APP_MESSAGING))
        override fun dismissNotification(arg: Pigeons.StringWrapper?, result: Pigeons.Result<Pigeons.BooleanWrapper>?) {
            if (arg != null) {
                val id = UUID.fromString(arg.value)
                activeNotifs.remove(id)?.notification?.deleteIntent?.send()
                val command = BlobCommand.DeleteCommand(Random.nextInt(0, UShort.MAX_VALUE.toInt()).toUShort(), BlobCommand.BlobDatabase.Notification, SUUID(StructMapper(), id).toBytes())
                GlobalScope.launch {
                    var blobResult = blobDBService.send(command)
                    while (blobResult.responseValue == BlobResponse.BlobStatus.TryLater) {
                        delay(1000)
                        command.token.set(Random.nextInt(0, UShort.MAX_VALUE.toInt()).toUShort())
                        blobResult = blobDBService.send(command)
                    }
                    withContext(Dispatchers.Main.immediate) {
                        result?.success(BooleanWrapper(blobResult.responseValue == BlobResponse.BlobStatus.Success))
                    }
                }
            }
        }

        override fun getMailPackages(): Pigeons.ListWrapper = ListWrapper(getAppsWithCategory(Intent.CATEGORY_APP_EMAIL))
    }

    private var notifListening: Pigeons.NotificationListening? = null

    @OptIn(ExperimentalStdlibApi::class)
    suspend fun handleNotification(packageId: String,
                                   notifId: Long, tagId: String?, tagName: String?, title: String,
                                   text: String, messages: List<NotificationMessage>,
                                   actions: List<NotificationAction>): Pair<TimelineItem, BlobResponse.BlobStatus> {
        if (notifListening == null) {
            val flutterEngine = flutterBackgroundController.getBackgroundFlutterEngine()
            if (flutterEngine != null) {
                Pigeons.NotificationUtils.setup(
                        flutterEngine.dartExecutor.binaryMessenger,
                        notifUtils
                )
                notifListening = Pigeons.NotificationListening(flutterEngine.dartExecutor.binaryMessenger)
            }
        }

        val notif = Pigeons.NotificationPigeon()
        notif.packageId = packageId
        notif.appName = context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(packageId, 0)) as String
        notif.notifId = notifId
        notif.tagId = tagId
        notif.tagName = tagName
        notif.title = title
        notif.text = text
        notif.actionsJson = moshi
                .adapter<List<NotificationAction>>(
                        Types.newParameterizedType(List::class.java, NotificationAction::class.java)
                ).toJson(actions)
        notif.messagesJson = moshi
                .adapter<List<NotificationMessage>>(
                        Types.newParameterizedType(List::class.java, NotificationMessage::class.java)
                ).toJson(messages)

        val result = CompletableDeferred<Pair<TimelineItem, BlobResponse.BlobStatus>>()
        if (notifListening == null) {
            Timber.w("Notification listening pigeon null")
        }
        notifListening?.handleNotification(notif) { notifToSend ->
            val parsedAttributes = moshi
                    .adapter<List<TimelineAttribute>>(
                            Types.newParameterizedType(List::class.java, TimelineAttribute::class.java)
                    )
                    .fromJson(notifToSend.attributesJson) ?: emptyList()

            val parsedActions = moshi
                    .adapter<List<io.rebble.cobble.data.TimelineAction>>(
                            Types.newParameterizedType(List::class.java, io.rebble.cobble.data.TimelineAction::class.java)
                    )
                    .fromJson(notifToSend.actionsJson) ?: emptyList()

            val itemId = UUID.fromString(notifToSend.itemId)
            val timelineItem = TimelineItem(
                    itemId,
                    UUID.fromString(notifToSend.parentId),
                    notifToSend.timestamp.toUInt(),
                    notifToSend.duration.toUShort(),
                    TimelineItem.Type.Notification,
                    TimelineItem.Flag.makeFlags(listOf()),
                    notifToSend.layout.toUByte(),
                    parsedAttributes.map { it.toProtocolAttribute() },
                    parsedActions.map { it.toProtocolAction() }
            )
            val packet = BlobCommand.InsertCommand(
                    Random.nextInt(0, UShort.MAX_VALUE.toInt()).toUShort(),
                    BlobCommand.BlobDatabase.Notification,
                    SUUID(StructMapper(), itemId).toBytes(),
                    timelineItem.toBytes(),
            )
            GlobalScope.launch {
                result.complete(Pair(timelineItem, blobDBService.send(packet).responseValue))
            }
        }
        return result.await()
    }

    fun dismiss(uuid: UUID) {
        val id = Pigeons.StringWrapper()
        id.value = uuid.toString()
        notifListening?.dismissNotification(id) {}
    }
}