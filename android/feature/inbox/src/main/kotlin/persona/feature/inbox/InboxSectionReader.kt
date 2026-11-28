package persona.feature.inbox

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalResources
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import persona.core.domain.inbox.InboxGroup
import persona.core.domain.inbox.InboxRowType
import persona.core.inbox.*

internal class InboxSectionReader(
    val busy: Boolean,
    val notice: String?,
    val retry: (() -> Unit)?,
    val hasUnread: (InboxGroup) -> Boolean,
    val read: (InboxGroup) -> Unit,
)

/** Read the actual hub-section items, including folded ones. Read receipts never file pending items. */
@Composable
internal fun rememberInboxSectionReader(
    controller: InboxConversations?,
    conversations: InboxConversationList,
    records: Map<String, InboxRecord>,
): InboxSectionReader {
    val res = LocalResources.current
    val scope = rememberCoroutineScope()
    val rows = remember(conversations) { conversations.rows.associateBy { it.key } }
    var busy by remember(controller) { mutableStateOf(false) }
    var notice by remember(controller) { mutableStateOf<String?>(null) }
    var retry by remember(controller) { mutableStateOf<(() -> Unit)?>(null) }

    fun row(record: InboxRecord): InboxConversationRow? =
        controller?.let { inboxConversationKey(it.scope, record) }?.let(rows::get)

    fun sectionItems(group: InboxGroup): List<InboxRecord> =
        group.rows.filter { it.type == InboxRowType.Item }.mapNotNull { records[it.id] }

    // Only source chat conversations support read receipts; mail, docs, moments and other non-chat items stay untouched.
    // A source receipt is conversation-wide, while whether this item still needs one is reconciled independently of its hub section.
    fun unread(group: InboxGroup): List<InboxRecord> = sectionItems(group)
        .filter { it.supportsConversationRead() && !it.read && row(it)?.unread != false }

    fun read(keys: Set<InboxConversationKey>, unloaded: Boolean, hasUnsupported: Boolean) {
        if (busy) return
        if (controller == null) {
            notice = res.getString(R.string.inbox_conversations_read_skipped)
            retry = null
            return
        }
        if (keys.isEmpty()) {
            if (unloaded) {
                notice = res.getString(R.string.inbox_conversations_read_skipped)
                retry = null
            }
            return
        }
        busy = true
        retry = null
        scope.launch {
            try {
                // A hub Action section can contain an item whose original tier was FYI, so accept both unread row states.
                val result = controller.readAll(setOf(InboxConversationSection.Action, InboxConversationSection.Fyi), keys)
                val failed = result.skipped.toSet() + result.failed.map { it.key }
                notice = res.getString(when {
                    failed.isNotEmpty() -> R.string.inbox_conversations_read_failed
                    unloaded -> R.string.inbox_conversations_read_skipped
                    hasUnsupported -> R.string.inbox_conversations_read_chats_only
                    else -> R.string.inbox_conversations_read_success
                })
                if (failed.isNotEmpty()) retry = { read(failed, unloaded = false, hasUnsupported = hasUnsupported) }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) {
                notice = res.getString(R.string.inbox_conversations_read_failed)
                retry = { read(keys, unloaded, hasUnsupported) }
            } finally { busy = false }
        }
    }

    return InboxSectionReader(busy, notice, retry,
        hasUnread = { group -> unread(group).isNotEmpty() },
        read = { group ->
            val items = sectionItems(group)
            val pending = unread(group)
            if (pending.isEmpty()) return@InboxSectionReader
            val keys = pending.mapNotNull { record ->
                controller?.let { inboxConversationKey(it.scope, record) }?.takeIf { rows[it]?.unread == true }
            }.toSet()
            val unloaded = pending.any { row(it) == null }
            val hasUnsupported = items.any { !it.supportsConversationRead() && !it.read }
            read(keys, unloaded, hasUnsupported)
        },
    )
}
