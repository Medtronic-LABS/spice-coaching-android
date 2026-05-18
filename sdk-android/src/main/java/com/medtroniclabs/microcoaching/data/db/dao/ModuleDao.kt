package com.medtroniclabs.microcoaching.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.medtroniclabs.microcoaching.data.db.entity.ModuleEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface ModuleDao {

    /**
     * Backend only ships published modules through `/sync/modules`, so every
     * row in the cache is active. Sorted for stable UI ordering.
     */
    @Query("SELECT * FROM module_cache ORDER BY domain, title_bn")
    fun getAllActive(): Flow<List<ModuleEntity>>

    /** One-shot read for joins — used by [MicroCoachingSDK.resolveFromCache]. */
    @Query("SELECT * FROM module_cache ORDER BY domain, title_bn")
    suspend fun getAllOrderedOnce(): List<ModuleEntity>

    /** Look up by module version id (the primary key). */
    @Query("SELECT * FROM module_cache WHERE module_id = :moduleId")
    suspend fun getById(moduleId: String): ModuleEntity?

    /**
     * Look up the latest version of a module family. When multiple versions of
     * the same family are cached (after an update), this returns the highest
     * version number.
     */
    @Query("SELECT * FROM module_cache WHERE module_family_id = :familyId ORDER BY version DESC LIMIT 1")
    suspend fun getByFamilyId(familyId: String): ModuleEntity?

    @Query("SELECT * FROM module_cache WHERE domain = :domain ORDER BY title_bn")
    fun getByDomain(domain: String): Flow<List<ModuleEntity>>

    @Query("SELECT COUNT(*) FROM module_cache")
    suspend fun countActive(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(modules: List<ModuleEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(module: ModuleEntity)

    /** Remove specific module versions. */
    @Query("DELETE FROM module_cache WHERE module_id IN (:moduleIds)")
    suspend fun deleteByIds(moduleIds: List<String>)

    /**
     * Drop every cached version of a family except the latest. Used by sync
     * after a new published version arrives.
     */
    @Query(
        """
        DELETE FROM module_cache
        WHERE module_family_id = :familyId AND version < (
            SELECT MAX(version) FROM module_cache WHERE module_family_id = :familyId
        )
        """,
    )
    suspend fun pruneOldVersions(familyId: String)

    @Query("DELETE FROM module_cache")
    suspend fun deleteAll()
}
