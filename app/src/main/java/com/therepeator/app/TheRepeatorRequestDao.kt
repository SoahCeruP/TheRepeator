package com.therepeator.app

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface TheRepeatorRequestDao {
    @Query("SELECT id, method, url, host, path, statusCode, protocol, timestamp, isIntercepted, bodyLength, headersJson FROM requests ORDER BY timestamp DESC")
    fun getAllRequestsSummary(): Flow<List<HistoryItemSummary>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRequest(request: TheRepeatorRequest): Long

    @Update
    suspend fun updateRequest(request: TheRepeatorRequest)

    @Query("DELETE FROM requests")
    suspend fun deleteAll()

    @Query("DELETE FROM requests WHERE id IN (:ids)")
    suspend fun deleteRequests(ids: List<Int>)

    @Query("SELECT * FROM requests WHERE id = :id")
    suspend fun getRequestById(id: Int): TheRepeatorRequest?
}

@Dao
interface IntruderResultDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertResult(result: IntruderResult)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertResults(results: List<IntruderResult>)

    @Query("SELECT * FROM intruder_results WHERE attackId = :attackId ORDER BY timestamp DESC LIMIT 1000")
    fun getResultsForAttack(attackId: String): Flow<List<IntruderResult>>

    @Query("DELETE FROM intruder_results WHERE attackId = :attackId")
    suspend fun deleteResultsForAttack(attackId: String)

    @Query("DELETE FROM intruder_results")
    suspend fun deleteAll()
}

@Dao
interface BrowserHistoryDao {
    @Query("SELECT * FROM browser_history ORDER BY timestamp DESC")
    fun getAllHistory(): Flow<List<BrowserHistoryItem>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: BrowserHistoryItem)

    @Query("DELETE FROM browser_history")
    suspend fun deleteAll()
}
