package com.limiter.app.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.limiter.app.model.UsageSnapshot
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

@OptIn(ExperimentalCoroutinesApi::class)
class UsageSnapshotStoreTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private val testDispatcher = UnconfinedTestDispatcher()
    private val testScope = TestScope(testDispatcher)
    private lateinit var store: UsageSnapshotStore

    @Before
    fun setup() {
        val file = tempFolder.newFile("test_snapshots.preferences_pb")
        val dataStore = PreferenceDataStoreFactory.create(
            scope = testScope,
            produceFile = { file }
        )
        store = UsageSnapshotStore(dataStore)
    }

    @Test
    fun decodeSnapshots_corruptLinesMixedWithValidLines_skipsCorruptLinesWithoutCrashing() {
        val mixedRaw = """
            com.example.valid1|1000|5000|10
            corrupt_line_without_pipes
            com.example.badfields|123
            com.example.nonnumeric|abc|5000|10
            com.example.negative|1000|-5000|10
            |1000|5000|10
            com.example.valid2|2000|4000|20
            corrupt_trailing_stuff|1|2|3|4|5
            com.example.valid3|0|60000|60
        """.trimIndent()

        val decoded = store.decodeSnapshots(mixedRaw)

        assertEquals("Should parse exactly 3 valid snapshots", 3, decoded.size)
        val s1 = decoded["com.example.valid1"]
        assertNotNull(s1)
        assertEquals(1000L, s1?.usedMillis)
        assertEquals(5000L, s1?.remainingMillis)
        assertEquals(10, s1?.limitMinutes)

        val s2 = decoded["com.example.valid2"]
        assertNotNull(s2)
        assertEquals(2000L, s2?.usedMillis)
        assertEquals(4000L, s2?.remainingMillis)
        assertEquals(20, s2?.limitMinutes)

        val s3 = decoded["com.example.valid3"]
        assertNotNull(s3)
        assertEquals(0L, s3?.usedMillis)
        assertEquals(60000L, s3?.remainingMillis)
        assertEquals(60, s3?.limitMinutes)
    }

    @Test
    fun decodeSnapshots_emptyOrBlank_returnsEmptyMap() {
        assertTrue(store.decodeSnapshots("").isEmpty())
        assertTrue(store.decodeSnapshots("   \n\n\t  ").isEmpty())
    }

    @Test
    fun publishAndFlow_roundTripSuccess() = runTest(testDispatcher) {
        val snapshots = mapOf(
            "com.app.a" to UsageSnapshot.Available("com.app.a", 1200L, 4800L, 10),
            "com.app.b" to UsageSnapshot.Available("com.app.b", 3000L, 3000L, 15)
        )
        store.publishAll(snapshots)

        val retrieved = store.snapshots.first()
        assertEquals(2, retrieved.size)
        assertEquals(1200L, retrieved["com.app.a"]?.usedMillis)
        assertEquals(3000L, retrieved["com.app.b"]?.usedMillis)
    }
}
