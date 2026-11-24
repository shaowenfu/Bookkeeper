package com.shaowenfu.bookkeeper.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface SampleDao {
    @Insert
    suspend fun insert(sample: NotificationSample): Long

    @Query("SELECT * FROM notification_sample WHERE package_name = :pkg AND sbn_key = :key ORDER BY id DESC LIMIT 1")
    suspend fun latestForKey(pkg: String, key: String): NotificationSample?

    @Query(
        "SELECT * FROM notification_sample WHERE package_name = :pkg AND content_hash = :hash " +
            "AND sbn_key != :key AND post_time >= :since ORDER BY id ASC LIMIT 1"
    )
    suspend fun firstSameContentOtherKey(pkg: String, hash: String, key: String, since: Long): NotificationSample?

    @Query("UPDATE notification_sample SET repost_count = repost_count + 1, last_seen_at = :at WHERE id = :id")
    suspend fun bumpRepost(id: Long, at: Long)

    @Query(
        "UPDATE notification_sample SET removed_at = :at, removed_reason = :reason " +
            "WHERE id = (SELECT id FROM notification_sample WHERE package_name = :pkg AND sbn_key = :key ORDER BY id DESC LIMIT 1)"
    )
    suspend fun markRemoved(pkg: String, key: String, at: Long, reason: Int)

    @Query(
        "SELECT * FROM notification_sample WHERE (:pkg IS NULL OR package_name = :pkg) " +
            "ORDER BY post_time DESC, id DESC LIMIT :limit"
    )
    fun observeRecent(pkg: String?, limit: Int): Flow<List<NotificationSample>>

    @Query(
        "SELECT package_name, COUNT(*) AS total, SUM(is_update) AS updates, " +
            "SUM(CASE WHEN possible_duplicate_of IS NULL THEN 0 ELSE 1 END) AS possible_dups, " +
            "SUM(repost_count) AS reposts, MAX(post_time) AS last_post_time " +
            "FROM notification_sample GROUP BY package_name"
    )
    fun observeCounts(): Flow<List<PackageCount>>

    @Query("SELECT COUNT(*) FROM notification_sample WHERE received_at >= :since")
    fun observeCountSince(since: Long): Flow<Int>

    @Query("SELECT * FROM notification_sample ORDER BY post_time ASC, id ASC")
    suspend fun all(): List<NotificationSample>
}

@Dao
interface ListenerEventDao {
    @Insert
    suspend fun insert(event: ListenerEvent): Long

    @Query("SELECT * FROM listener_event ORDER BY at DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<ListenerEvent>>

    @Query("SELECT * FROM listener_event ORDER BY at ASC")
    suspend fun all(): List<ListenerEvent>
}

@Dao
interface SeenPackageDao {
    @Query("SELECT * FROM seen_package WHERE package_name = :pkg")
    suspend fun get(pkg: String): SeenPackage?

    @Insert
    suspend fun insert(p: SeenPackage)

    @Query("UPDATE seen_package SET count = count + 1, last_seen_at = :at WHERE package_name = :pkg")
    suspend fun bump(pkg: String, at: Long)

    @Query("DELETE FROM seen_package WHERE package_name = :pkg")
    suspend fun delete(pkg: String)

    @Query("SELECT * FROM seen_package ORDER BY count DESC")
    fun observeAll(): Flow<List<SeenPackage>>

    @Query("SELECT * FROM seen_package ORDER BY count DESC")
    suspend fun all(): List<SeenPackage>
}
