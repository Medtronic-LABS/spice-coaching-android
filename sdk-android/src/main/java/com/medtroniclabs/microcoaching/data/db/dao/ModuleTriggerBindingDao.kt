package com.medtroniclabs.microcoaching.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.medtroniclabs.microcoaching.data.db.entity.ModuleTriggerBindingEntity

@Dao
interface ModuleTriggerBindingDao {

    @Query("SELECT * FROM module_trigger_binding")
    suspend fun getAll(): List<ModuleTriggerBindingEntity>

    @Query("SELECT * FROM module_trigger_binding WHERE trigger_definition_id = :triggerId ORDER BY priority_weight DESC")
    suspend fun getByTrigger(triggerId: String): List<ModuleTriggerBindingEntity>

    @Query("SELECT * FROM module_trigger_binding WHERE module_family_id = :moduleFamilyId")
    suspend fun getByModule(moduleFamilyId: String): List<ModuleTriggerBindingEntity>

    @Query("SELECT COUNT(*) FROM module_trigger_binding")
    suspend fun count(): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(bindings: List<ModuleTriggerBindingEntity>)

    @Query("DELETE FROM module_trigger_binding WHERE binding_id IN (:ids)")
    suspend fun deleteByIds(ids: List<String>)

    @Query("DELETE FROM module_trigger_binding")
    suspend fun deleteAll()
}
