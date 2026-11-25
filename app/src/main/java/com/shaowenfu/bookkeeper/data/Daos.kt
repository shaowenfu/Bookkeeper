package com.shaowenfu.bookkeeper.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface CaptureDao {
    @Insert suspend fun insert(c: Capture): Long
    @Update suspend fun update(c: Capture)
    @Query("SELECT * FROM capture WHERE id = :id") suspend fun get(id: Long): Capture?
    @Query("SELECT * FROM capture WHERE id = :id") fun observe(id: Long): Flow<Capture?>
    @Query("SELECT * FROM capture ORDER BY created_at DESC") fun observeAll(): Flow<List<Capture>>
    @Query("SELECT * FROM capture ORDER BY created_at ASC") suspend fun all(): List<Capture>
    @Query("DELETE FROM capture WHERE id = :id") suspend fun delete(id: Long)

    @Query("UPDATE capture SET text_model = :model, text_result = :result, text_latency_ms = :ms, text_error = :error WHERE id = :id")
    suspend fun setTextResult(id: Long, model: String, result: String?, ms: Long?, error: String?)

    @Query("UPDATE capture SET image_model = :model, image_result = :result, image_latency_ms = :ms, image_error = :error, image_parts = :parts WHERE id = :id")
    suspend fun setImageResult(id: Long, model: String, result: String?, ms: Long?, error: String?, parts: Int?)

    @Query("UPDATE capture SET page_label = :label, true_amount = :amount, text_ok = :textOk, image_ok = :imageOk, note = :note WHERE id = :id")
    suspend fun setLabels(id: Long, label: String?, amount: String?, textOk: Boolean?, imageOk: Boolean?, note: String?)
}

@Dao
interface ServiceEventDao {
    @Insert suspend fun insert(e: ServiceEvent)
    @Query("SELECT * FROM service_event ORDER BY at DESC LIMIT 200") fun observeRecent(): Flow<List<ServiceEvent>>
    @Query("SELECT * FROM service_event ORDER BY at ASC") suspend fun all(): List<ServiceEvent>
}
