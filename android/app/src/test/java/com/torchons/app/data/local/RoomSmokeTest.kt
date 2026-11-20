package com.torchons.app.data.local

import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Proves the real-Room tooling (Robolectric + in-memory AppDatabase) runs on this host.
 * Paths by which "the tooling reads back what it wrote" can break: Robolectric sandbox
 * (SDK jar download, Application onCreate), Room schema creation, the DAO round trip.
 * Not covered: migrations (exportSchema = false, no schema files), the minified build.
 */
@RunWith(AndroidJUnit4::class)
class RoomSmokeTest {
    private lateinit var db: AppDatabase

    @Before fun setUp() { db = newInMemoryDb() }

    @After fun tearDown() { db.close() }

    @Test
    fun a_row_written_through_a_real_dao_is_read_back() = runBlocking {
        db.taskDao().upsert(taskRow("t1", title = "Vacuum"))
        assertEquals("Vacuum", db.taskDao().getById("t1")?.title)
    }
}
