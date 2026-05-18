package com.medtroniclabs.microcoaching.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.medtroniclabs.microcoaching.data.db.dao.BehaviouralGapDao
import com.medtroniclabs.microcoaching.data.db.dao.ChatMessageDao
import com.medtroniclabs.microcoaching.data.db.dao.ChwGapProfileDao
import com.medtroniclabs.microcoaching.data.db.dao.ChwModuleCompletionDao
import com.medtroniclabs.microcoaching.data.db.dao.CoachingEventDao
import com.medtroniclabs.microcoaching.data.db.dao.ConfigThresholdDao
import com.medtroniclabs.microcoaching.data.db.dao.DigitalProficiencyEventDao
import com.medtroniclabs.microcoaching.data.db.dao.LlmTraceDao
import com.medtroniclabs.microcoaching.data.db.dao.ModuleDao
import com.medtroniclabs.microcoaching.data.db.dao.MorningCardCacheDao
import com.medtroniclabs.microcoaching.data.db.dao.ModuleTriggerBindingDao
import com.medtroniclabs.microcoaching.data.db.dao.TriggerDefinitionDao
import com.medtroniclabs.microcoaching.data.db.entity.BehaviouralGapEntity
import com.medtroniclabs.microcoaching.data.db.entity.ChatMessageEntity
import com.medtroniclabs.microcoaching.data.db.entity.ChwGapProfileEntity
import com.medtroniclabs.microcoaching.data.db.entity.ChwModuleCompletionEntity
import com.medtroniclabs.microcoaching.data.db.entity.CoachingEventEntity
import com.medtroniclabs.microcoaching.data.db.entity.MorningCardCacheEntity
import com.medtroniclabs.microcoaching.data.db.entity.ConfigThresholdEntity
import com.medtroniclabs.microcoaching.data.db.entity.DigitalProficiencyEventEntity
import com.medtroniclabs.microcoaching.data.db.entity.LlmTraceEntity
import com.medtroniclabs.microcoaching.data.db.entity.ModuleEntity
import com.medtroniclabs.microcoaching.data.db.entity.ModuleTriggerBindingEntity
import com.medtroniclabs.microcoaching.data.db.entity.TriggerDefinitionEntity

/**
 * SDK-owned Room database. Completely separate from SPICE's NCDMergerDatabase.
 *
 * Database name: `microcoaching.db`
 * Version: 13 (W5-A — chw_gap_profile_local primary key column renamed
 *           scenario_id → behavioural_gap_id; v12: morning_card_cache table;
 *           v11: behavioural_gap_id on coaching_event; v10: backend-shape alignment.)
 *
 * Migration strategy: destructive re-creation for pre-release versions.
 */
@Database(
    entities = [
        ChatMessageEntity::class,
        CoachingEventEntity::class,
        LlmTraceEntity::class,
        DigitalProficiencyEventEntity::class,
        ChwGapProfileEntity::class,
        ModuleEntity::class,
        BehaviouralGapEntity::class,
        TriggerDefinitionEntity::class,
        ModuleTriggerBindingEntity::class,
        ConfigThresholdEntity::class,
        ChwModuleCompletionEntity::class,
        MorningCardCacheEntity::class,
    ],
    version = 13,
    exportSchema = false,
)
abstract class MicroCoachingDatabase : RoomDatabase() {

    abstract fun chatMessageDao(): ChatMessageDao
    abstract fun coachingEventDao(): CoachingEventDao
    abstract fun llmTraceDao(): LlmTraceDao
    abstract fun digitalProficiencyEventDao(): DigitalProficiencyEventDao
    abstract fun chwGapProfileDao(): ChwGapProfileDao
    abstract fun moduleDao(): ModuleDao
    abstract fun behaviouralGapDao(): BehaviouralGapDao
    abstract fun triggerDefinitionDao(): TriggerDefinitionDao
    abstract fun moduleTriggerBindingDao(): ModuleTriggerBindingDao
    abstract fun configThresholdDao(): ConfigThresholdDao
    abstract fun chwModuleCompletionDao(): ChwModuleCompletionDao
    abstract fun morningCardCacheDao(): MorningCardCacheDao

    companion object {
        private const val DATABASE_NAME = "microcoaching.db"

        @Volatile
        private var instance: MicroCoachingDatabase? = null

        fun getInstance(context: Context): MicroCoachingDatabase =
            instance ?: synchronized(this) {
                instance ?: buildDatabase(context).also { instance = it }
            }

        private fun buildDatabase(context: Context): MicroCoachingDatabase =
            Room.databaseBuilder(
                context.applicationContext,
                MicroCoachingDatabase::class.java,
                DATABASE_NAME,
            )
                .fallbackToDestructiveMigration(dropAllTables = true)
                .build()
    }
}
