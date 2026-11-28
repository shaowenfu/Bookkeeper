package com.shaowenfu.bookkeeper.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

@Dao
interface RawEventDao {
    @Insert suspend fun insert(e: RawEvent): Long
    @Update suspend fun update(e: RawEvent)
    @Query("SELECT * FROM raw_event WHERE id = :id") suspend fun get(id: Long): RawEvent?
    @Query("SELECT * FROM raw_event WHERE id = :id") fun observe(id: Long): Flow<RawEvent?>
    @Query("SELECT * FROM raw_event ORDER BY created_at DESC LIMIT 300") fun observeRecent(): Flow<List<RawEvent>>
    @Query("SELECT * FROM raw_event ORDER BY created_at ASC") suspend fun all(): List<RawEvent>
    /** 待识别：pending，或失败但还没用完重试次数 */
    @Query("SELECT * FROM raw_event WHERE status = 'pending' OR (status = 'failed' AND attempts < :maxAttempts) ORDER BY created_at ASC")
    suspend fun todo(maxAttempts: Int = RawEvent.MAX_ATTEMPTS): List<RawEvent>
    @Query("SELECT COUNT(*) FROM raw_event WHERE status = 'pending' OR (status = 'failed' AND attempts < :maxAttempts)")
    suspend fun todoCount(maxAttempts: Int = RawEvent.MAX_ATTEMPTS): Int
    @Query("SELECT r.* FROM raw_event r JOIN tx_event te ON te.raw_event_id = r.id WHERE te.tx_id = :txId ORDER BY r.created_at")
    suspend fun forTx(txId: Long): List<RawEvent>
}

data class MonthTotal(val expense: Long, val refund: Long, val count: Int)

@Dao
interface TxDao {
    @Insert suspend fun insert(t: Tx): Long
    @Update suspend fun update(t: Tx)
    @Query("SELECT * FROM tx WHERE id = :id") suspend fun get(id: Long): Tx?
    @Query("SELECT * FROM tx WHERE id = :id") fun observe(id: Long): Flow<Tx?>
    @Query("SELECT * FROM tx WHERE status != 'void' ORDER BY occurred_at DESC") fun observeActive(): Flow<List<Tx>>
    @Query("SELECT * FROM tx ORDER BY occurred_at ASC") suspend fun all(): List<Tx>

    /** 去重候选：同金额、同方向、时间在窗口内、未作废 */
    @Query(
        "SELECT * FROM tx WHERE status != 'void' AND amount_cents = :amount AND direction = :direction " +
            "AND occurred_at BETWEEN :from AND :to ORDER BY ABS(occurred_at - :at) ASC"
    )
    suspend fun candidates(amount: Long, direction: String, at: Long, from: Long, to: Long): List<Tx>

    @Query(
        "SELECT COALESCE(SUM(CASE WHEN direction = 'expense' THEN amount_cents ELSE 0 END), 0) AS expense, " +
            "COALESCE(SUM(CASE WHEN direction = 'refund' THEN amount_cents ELSE 0 END), 0) AS refund, " +
            "COUNT(*) AS count FROM tx WHERE status != 'void' AND occurred_at >= :from AND occurred_at < :to"
    )
    fun observeTotal(from: Long, to: Long): Flow<MonthTotal>

    @Query("UPDATE tx SET status = 'void', updated_at = :now WHERE id = :id") suspend fun voidTx(id: Long, now: Long = System.currentTimeMillis())
}

@Dao
interface TxEventDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insert(e: TxEvent)
    @Query("SELECT * FROM tx_event") suspend fun all(): List<TxEvent>
    @Query("SELECT tx_id FROM tx_event WHERE raw_event_id = :rawId") suspend fun txIdsFor(rawId: Long): List<Long>
}

@Dao
interface LineItemDao {
    @Insert suspend fun insertAll(items: List<LineItem>)
    @Query("SELECT * FROM line_item WHERE tx_id = :txId ORDER BY id") suspend fun forTx(txId: Long): List<LineItem>
    @Query("SELECT * FROM line_item WHERE tx_id = :txId ORDER BY id") fun observeForTx(txId: Long): Flow<List<LineItem>>
    @Query("SELECT COUNT(*) FROM line_item WHERE tx_id = :txId") suspend fun count(txId: Long): Int
    @Query("SELECT * FROM line_item ORDER BY tx_id, id") suspend fun all(): List<LineItem>
}

@Dao
interface ServiceEventDao {
    @Insert suspend fun insert(e: ServiceEvent)
    @Query("SELECT * FROM service_event ORDER BY at DESC LIMIT 100") fun observeRecent(): Flow<List<ServiceEvent>>
    @Query("SELECT * FROM service_event ORDER BY at ASC") suspend fun all(): List<ServiceEvent>
}
