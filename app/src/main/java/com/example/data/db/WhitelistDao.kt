package com.example.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface WhitelistDao {
    @Query("SELECT * FROM whitelisted_apps ORDER BY appName ASC")
    fun getAllWhitelisted(): Flow<List<WhitelistedAppEntity>>

    @Query("SELECT * FROM whitelisted_apps ORDER BY appName ASC")
    suspend fun getAllWhitelistedList(): List<WhitelistedAppEntity>

    @Query("SELECT packageName FROM whitelisted_apps")
    fun getAllWhitelistedPackageNames(): Flow<List<String>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(app: WhitelistedAppEntity)

    @Query("DELETE FROM whitelisted_apps WHERE packageName = :packageName")
    suspend fun delete(packageName: String)

    @Query("SELECT EXISTS(SELECT 1 FROM whitelisted_apps WHERE packageName = :packageName)")
    suspend fun isWhitelisted(packageName: String): Boolean
}
