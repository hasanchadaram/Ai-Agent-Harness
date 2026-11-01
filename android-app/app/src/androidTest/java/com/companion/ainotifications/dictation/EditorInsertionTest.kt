package com.companion.ainotifications.dictation

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Synthetic text only, no microphone or network. Requires user-enabled accessibility. */
@RunWith(AndroidJUnit4::class)
class EditorInsertionTest {
    private lateinit var scenario: ActivityScenario<DictationEditorFixture>
    private lateinit var service: DictationAccessibilityService
    private lateinit var controller: DictationController
    private var destination: DictationController.InsertionTarget? = null
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Before fun setup() = runBlocking {
        val connected = withTimeoutOrNull(5000) { DictationAccessibilityService.connection.filterNotNull().first() }
        assumeTrue("Enable Private dictation controls on the phone first", connected != null)
        service = connected!!
        controller = DictationController.get(instrumentation.targetContext)
        onMain { controller.cancel() }
        scenario = ActivityScenario.launch(DictationEditorFixture::class.java)
        repeat(40) {
            if (destination == null) {
                instrumentation.waitForIdleSync()
                onMain {
                    val root = service.rootInActiveWindow
                    val focused = root?.findFocus(android.view.accessibility.AccessibilityNodeInfo.FOCUS_INPUT)
                    try {
                        if (focused?.packageName?.toString() == instrumentation.targetContext.packageName &&
                            focused.text?.toString() == "Hello world!") {
                            destination = service.captureInsertionTarget()
                        }
                    } finally { focused?.recycle(); root?.recycle() }
                }
                if (destination == null) delay(100)
            }
        }
        assertNotNull("Fixture editor must be exposed through accessibility", destination)
        onMain { controller.prepare(destination) }
    }

    @After fun cleanup() {
        if (::controller.isInitialized) onMain { controller.cancel() }
        if (::scenario.isInitialized) scenario.close()
    }

    @Test fun insertsAtCursorAndPreservesExistingText() {
        val outcome = insert("wonderful ")
        assertTrue(outcome!!.message, outcome!!.inserted)
        scenario.onActivity {
            assertEquals("Hello wonderful world!", it.first.text.toString())
            assertEquals("Keep this field unchanged.", it.second.text.toString())
            assertEquals(16, it.first.selectionStart)
        }
    }

    @Test fun rejectsAChangedField() {
        scenario.onActivity { it.second.requestFocus() }
        instrumentation.waitForIdleSync()
        val outcome = insert("Must not insert")
        assertFalse(outcome!!.inserted)
        scenario.onActivity {
            assertEquals("Hello world!", it.first.text.toString())
            assertEquals("Keep this field unchanged.", it.second.text.toString())
        }
    }

    @Test fun rejectsConcurrentTextEditsAndCursorMoves() {
        scenario.onActivity { it.first.setText("User changed this."); it.first.setSelection(3) }
        instrumentation.waitForIdleSync()
        val outcome = insert("Must not insert")
        assertFalse(outcome!!.inserted)
        scenario.onActivity { assertEquals("User changed this.", it.first.text.toString()) }
    }

    @Test fun changingFieldsAndReturningStillInvalidatesTheDestination() = runBlocking {
        scenario.onActivity { it.second.requestFocus() }
        instrumentation.waitForIdleSync(); delay(250)
        scenario.onActivity { it.first.requestFocus(); it.first.setSelection(6) }
        instrumentation.waitForIdleSync(); delay(250)
        val outcome = insert("Must not insert")
        assertFalse("Returning to the original editor must not revive a stale destination", outcome!!.inserted)
    }

    private fun insert(text: String) = runBlocking {
        withContext(Dispatchers.Main) { destination!!.insert(text) }
    }

    private fun onMain(action: () -> Unit) { instrumentation.runOnMainSync(action) }
}
