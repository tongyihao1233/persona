package persona.feature.inbox

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.navigation3.runtime.NavKey
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import persona.core.contracts.api.ConnectionState
import persona.core.contracts.api.InboxBulkAction
import persona.core.contracts.api.InboxDoneTarget
import persona.core.contracts.api.InboxEntry
import persona.core.designsystem.PersonaTheme
import persona.core.domain.inbox.InboxItem
import persona.core.domain.inbox.buildInboxList
import persona.core.inbox.*
import persona.core.navigation.AppTab
import persona.core.navigation.Navigator

/**
 * Section-level "Mark all as read" operates exclusively on chat conversations that support source read receipts.
 * Non-chat items (mail, docs, moments, GitHub) never keep the action visible and never trigger unloaded-conversation notices.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [35], qualifiers = "en-w400dp-h1500dp-xhdpi")
class InboxSectionReaderTest {
    @get:Rule val rule = createComposeRule()
    private val owner = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val navigator = object : Navigator {
        override fun navigate(key: NavKey) = Unit
        override fun back() = Unit
        override fun selectTab(tab: AppTab) = Unit
    }
    private val res get() = ApplicationProvider.getApplicationContext<android.content.Context>().resources

    @After fun stop() { owner.cancel() }

    private fun chat(
        id: String,
        feedId: String = "room-1",
        tier: String = "action",
        read: Boolean = false,
        title: String = "Chat $id",
    ) = InboxRecord(
        InboxItem(id, account = "feishu", kind = "dm", title = title, sender = "Person $id", preview = "Preview $id",
            tier = tier, updatedAt = "2026-11-12T10:00:00Z", version = "v-$id"),
        sourceLabel = "Lark", source = "lark", feedId = feedId, read = read,
    )

    private fun mail(id: String, tier: String = "action", read: Boolean = false) = InboxRecord(
        InboxItem(id, account = "me@example.com", kind = "mail", title = "Mail $id", sender = "Sender $id",
            preview = "Mail preview", tier = tier, updatedAt = "2026-11-12T09:00:00Z", version = "v-$id"),
        source = "mail", read = read,
    )

    private fun doc(id: String, tier: String = "action") = InboxRecord(
        InboxItem(id, account = "feishu", kind = "doc", title = "Doc $id", sender = "Editor",
            preview = "Commented", tier = tier, updatedAt = "2026-11-12T09:05:00Z", version = "v-$id"),
        sourceLabel = "Lark", source = "lark", feedId = "doc-feed-$id",
        doc = InboxDocRef(url = "https://example.com/doc/$id"),
    )

    private fun moment(id: String, tier: String = "fyi") = InboxRecord(
        InboxItem(id, account = "feishu", kind = "moment", title = "Moment $id", sender = "Colleague",
            preview = "Posted", tier = tier, updatedAt = "2026-11-12T09:10:00Z", version = "v-$id"),
        sourceLabel = "Lark", source = "lark", feedId = "moment-feed-$id",
        moment = InboxMomentRef(postId = "post-$id"),
    )

    private fun github(id: String, tier: String = "fyi") = InboxRecord(
        InboxItem(id, account = "octocat", kind = "pr", title = "PR $id", sender = "GitHub",
            preview = "Review requested", tier = tier, updatedAt = "2026-11-12T09:15:00Z", version = "v-$id"),
        sourceLabel = "GitHub", source = "github", feedId = null,
    )

    private fun connectorChat(id: String, tier: String = "action", read: Boolean = false) = InboxRecord(
        InboxItem(id, account = "larkx-account", kind = "mention", title = "Connector mention $id", sender = "Teammate",
            preview = "Please take a look", tier = tier, updatedAt = "2026-11-12T09:20:00Z", version = "v-$id"),
        sourceLabel = "LarkX", source = "larkx", feedId = null, read = read,
        connector = InboxConnectorRef(thread = "chat-placeholder:msg-$id", label = "@me", canReply = true),
    )

    private class FakeInbox(records: List<InboxRecord>) : InboxRepository {
        override val entries = MutableStateFlow(emptyList<InboxEntry>())
        override val snapshot = MutableStateFlow(InboxSnapshot(records = records))
        override val connectionState = MutableStateFlow(ConnectionState.Online)
        override val preferences = MutableStateFlow(InboxPreferences(expanded = listOf("action", "fyi")))
        override suspend fun savePreferences(value: InboxPreferences) { preferences.value = value }
        override val conversationInbox = MutableStateFlow<InboxConversations?>(null)
        val reads = mutableListOf<String>()
        val bulkCalls = mutableListOf<Pair<List<InboxDoneTarget>, InboxBulkAction>>()
        var failRead = false

        override suspend fun markLatestRead(feedId: String): Boolean {
            reads += feedId
            if (failRead) return false
            snapshot.value = snapshot.value.copy(records = snapshot.value.records.map {
                if (it.feedId == feedId) it.copy(read = true) else it
            })
            return true
        }

        override suspend fun bulk(items: List<InboxDoneTarget>, action: InboxBulkAction): InboxBulkResult {
            bulkCalls += items to action
            return InboxBulkResult(changed = items.map { it.id })
        }

        override suspend fun refresh() = Unit
        override suspend fun refreshNow() = Unit
        override fun conversation(feedId: String, startPosition: Long?) = flowOf(LarkConversation())
        override suspend fun loadOlder(feedId: String) = Unit
        override suspend fun refreshConversation(feedId: String) = Unit
        override suspend fun setDone(itemId: String, done: Boolean) = Unit
        override suspend fun readDraft(target: ReplyTarget): ReplyDraft? = null
        override suspend fun saveDraft(target: ReplyTarget, draft: ReplyDraft) = Unit
        override suspend fun reply(target: ReplyTarget, request: ReplyRequest) = error("unused")
        override suspend fun downloadFile(file: LarkPart.File): String = error("unused")
    }

    private fun showScreen(inbox: FakeInbox, attachController: Boolean = true) {
        if (attachController) {
            inbox.conversationInbox.value = InboxConversations(inbox, "synthetic-pairing", owner, ReplyComposers(inbox, owner))
        }
        rule.setContent { PersonaTheme(reduceMotion = true) {
            Box(Modifier.background(PersonaTheme.palette.background)) { OnAll { InboxScreen(inbox, navigator) } }
        } }
        rule.waitForIdle()
    }

    @Test fun nonChatOnlySectionsHideMarkAllAsRead() {
        val inbox = FakeInbox(listOf(
            mail("m1", tier = "action"),
            doc("d1", tier = "action"),
            moment("mo1", tier = "fyi"),
            github("gh1", tier = "fyi"),
        ))
        showScreen(inbox)
        rule.onNodeWithText("Mail m1").assertExists()
        rule.onNodeWithText("PR gh1").assertExists()
        rule.onNodeWithText(res.getString(R.string.inbox_conversations_read_all)).assertDoesNotExist()
    }

    @Test fun connectorOnlyLarkxSectionsHideMarkAllAsRead() {
        // Real-world larkx connector items carry `connector` metadata without legacy `lark.feed_id`,
        // so source conversation read receipts do not apply and the section must not show a dead button.
        val inbox = FakeInbox(listOf(
            connectorChat("lx1", tier = "action"),
            connectorChat("lx2", tier = "action"),
            connectorChat("lx3", tier = "fyi"),
        ))
        showScreen(inbox)
        rule.onNodeWithText("Connector mention lx1").assertExists()
        rule.onNodeWithText("Connector mention lx3").assertExists()
        rule.onNodeWithText(res.getString(R.string.inbox_conversations_read_all)).assertDoesNotExist()
    }

    @Test fun pureChatSectionMarksAllReadShowsSuccessAndHidesButton() {
        val inbox = FakeInbox(listOf(
            chat("c1", feedId = "room-1"),
            chat("c2", feedId = "room-2"),
        ))
        showScreen(inbox)
        rule.onNodeWithText(res.getString(R.string.inbox_conversations_read_all)).performClick()
        rule.waitForIdle()
        rule.runOnIdle {
            assertEquals(setOf("room-1", "room-2"), inbox.reads.toSet())
            assertTrue(inbox.bulkCalls.isEmpty())
        }
        rule.onNodeWithText(res.getString(R.string.inbox_conversations_read_success)).assertExists()
        rule.onNodeWithText(res.getString(R.string.inbox_conversations_read_all)).assertDoesNotExist()
    }

    @Test fun mixedSectionMarksChatsReadReportsOtherItemsAndHidesButton() {
        val inbox = FakeInbox(listOf(
            chat("c1", feedId = "room-1"),
            connectorChat("lx1", tier = "action"),
            mail("m1"),
            doc("d1"),
            github("gh1", tier = "action"),
        ))
        showScreen(inbox)
        rule.onNodeWithText(res.getString(R.string.inbox_conversations_read_all)).performClick()
        rule.waitForIdle()
        rule.runOnIdle {
            assertEquals(listOf("room-1"), inbox.reads)
            assertTrue("Read receipts never file items", inbox.bulkCalls.isEmpty())
            assertTrue(inbox.snapshot.value.records.none { it.item.isDone })
        }
        rule.onNodeWithText(res.getString(R.string.inbox_conversations_read_chats_only)).assertExists()
        rule.onNodeWithText(res.getString(R.string.inbox_conversations_read_skipped)).assertDoesNotExist()
        rule.onNodeWithText(res.getString(R.string.inbox_conversations_read_all)).assertDoesNotExist()
    }

    @Test fun mixedSectionWithAlreadyReadMailShowsPlainSuccessNotice() {
        val inbox = FakeInbox(listOf(
            chat("c1", feedId = "room-1"),
            mail("m1", read = true),
        ))
        showScreen(inbox)
        rule.onNodeWithText(res.getString(R.string.inbox_conversations_read_all)).performClick()
        rule.waitForIdle()
        rule.onNodeWithText(res.getString(R.string.inbox_conversations_read_success)).assertExists()
        rule.onNodeWithText(res.getString(R.string.inbox_conversations_read_chats_only)).assertDoesNotExist()
        rule.onNodeWithText(res.getString(R.string.inbox_conversations_read_all)).assertDoesNotExist()
    }

    @Test fun controllerNullHidesButtonForNonChatOnlySection() {
        val inbox = FakeInbox(listOf(connectorChat("lx1"), mail("m1"), doc("d1"), github("gh1", tier = "action")))
        showScreen(inbox, attachController = false)
        rule.onNodeWithText(res.getString(R.string.inbox_conversations_read_all)).assertDoesNotExist()
    }

    @Test fun controllerNullShowsSkippedNoticeForUnreadChatWithoutRequests() {
        val inbox = FakeInbox(listOf(chat("c1", feedId = "room-1")))
        showScreen(inbox, attachController = false)
        rule.onNodeWithText(res.getString(R.string.inbox_conversations_read_all)).performClick()
        rule.waitForIdle()
        rule.runOnIdle { assertTrue(inbox.reads.isEmpty()) }
        rule.onNodeWithText(res.getString(R.string.inbox_conversations_read_skipped)).assertExists()
    }

    @Test fun unloadedConversationListReportsSkippedWithoutCallingReadAll() {
        val rec = chat("c1", feedId = "room-unloaded")
        val inbox = FakeInbox(listOf(rec))
        val controller = InboxConversations(inbox, "synthetic-pairing", owner, ReplyComposers(inbox, owner))
        val group = buildInboxList(listOf(rec.item)).groups.single { it.id == "action" }
        lateinit var reader: InboxSectionReader
        rule.setContent {
            reader = rememberInboxSectionReader(controller, InboxConversationList(), mapOf(rec.item.id to rec))
        }
        rule.waitForIdle()
        assertTrue(reader.hasUnread(group))
        rule.runOnIdle { reader.read(group) }
        rule.waitForIdle()
        assertEquals(res.getString(R.string.inbox_conversations_read_skipped), reader.notice)
        assertNull(reader.retry)
        assertTrue(inbox.reads.isEmpty())
    }

    @Test fun partiallyLoadedConversationListAcknowledgesLoadedAndReportsUnloaded() {
        val loaded = chat("c-loaded", feedId = "room-loaded")
        val unloaded = chat("c-unloaded", feedId = "room-unloaded")
        val inbox = FakeInbox(listOf(loaded, unloaded))
        val controller = InboxConversations(inbox, "synthetic-pairing", owner, ReplyComposers(inbox, owner))
        val records = mapOf(loaded.item.id to loaded, unloaded.item.id to unloaded)
        var conversationList by mutableStateOf(buildInboxConversations("synthetic-pairing", InboxSnapshot(records = listOf(loaded))))
        val group = buildInboxList(listOf(loaded.item, unloaded.item)).groups.single { it.id == "action" }
        lateinit var reader: InboxSectionReader
        rule.setContent {
            reader = rememberInboxSectionReader(controller, conversationList, records)
        }
        rule.waitForIdle()
        rule.runOnIdle { reader.read(group) }
        rule.waitForIdle()
        assertEquals(listOf("room-loaded"), inbox.reads)
        assertEquals(res.getString(R.string.inbox_conversations_read_skipped), reader.notice)
        assertTrue("Unloaded chat still needs reading", reader.hasUnread(group))
    }

    @Test fun failedMixedSectionReadRetriesAndFinishesWithChatsOnlyNotice() {
        val inbox = FakeInbox(listOf(
            chat("c1", feedId = "room-retry"),
            mail("m1"),
        )).apply { failRead = true }
        showScreen(inbox)
        rule.onNodeWithText(res.getString(R.string.inbox_conversations_read_all)).performClick()
        rule.waitForIdle()
        rule.onNodeWithText(res.getString(R.string.inbox_conversations_read_failed)).assertExists()
        rule.onNodeWithText(res.getString(R.string.inbox_conversations_read_all)).assertExists()

        inbox.failRead = false
        rule.onNodeWithText(res.getString(R.string.inbox_retry)).performClick()
        rule.waitForIdle()
        rule.runOnIdle { assertEquals(listOf("room-retry", "room-retry"), inbox.reads) }
        rule.onNodeWithText(res.getString(R.string.inbox_conversations_read_chats_only)).assertExists()
        rule.onNodeWithText(res.getString(R.string.inbox_conversations_read_all)).assertDoesNotExist()
        assertFalse(inbox.snapshot.value.records.any { it.item.isDone })
    }
}
