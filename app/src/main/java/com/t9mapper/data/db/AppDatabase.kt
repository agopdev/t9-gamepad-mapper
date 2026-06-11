package com.t9mapper.data.db

import android.content.Context
import androidx.room.*
import androidx.sqlite.db.SupportSQLiteDatabase
import com.t9mapper.data.model.AppProfileAssignment
import com.t9mapper.data.model.KeyMapping
import com.t9mapper.data.model.MappingType
import com.t9mapper.data.model.Profile
import kotlinx.coroutines.flow.Flow

// ──────────────────────────────────────────────
// Type Converters
// ──────────────────────────────────────────────

class Converters {
    @TypeConverter fun fromMappingType(value: MappingType): String = value.name

    @TypeConverter
    fun toMappingType(value: String): MappingType =
            runCatching { MappingType.valueOf(value) }.getOrDefault(MappingType.BUTTON)
}

// ──────────────────────────────────────────────
// DAOs
// ──────────────────────────────────────────────

@Dao
interface ProfileDao {

    @Query("SELECT * FROM profiles ORDER BY isDefault DESC, name ASC")
    fun getAllProfiles(): Flow<List<Profile>>

    @Query("SELECT * FROM profiles WHERE id = :id") suspend fun getProfileById(id: Long): Profile?

    @Query("SELECT * FROM profiles WHERE isDefault = 1 LIMIT 1")
    suspend fun getDefaultProfile(): Profile?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertProfile(profile: Profile): Long

    @Update suspend fun updateProfile(profile: Profile)

    @Delete suspend fun deleteProfile(profile: Profile)

    /** Asegurar que solo un perfil sea default */
    @Query("UPDATE profiles SET isDefault = 0 WHERE id != :id")
    suspend fun clearOtherDefaults(id: Long)

    @Transaction
    suspend fun setAsDefault(profileId: Long) {
        clearOtherDefaults(profileId)
        val profile = getProfileById(profileId) ?: return
        updateProfile(profile.copy(isDefault = true))
    }
}

@Dao
interface KeyMappingDao {

    @Query("SELECT * FROM key_mappings WHERE profileId = :profileId ORDER BY keyCode ASC")
    fun getMappingsForProfile(profileId: Long): Flow<List<KeyMapping>>

    @Query("SELECT * FROM key_mappings WHERE profileId = :profileId ORDER BY keyCode ASC")
    suspend fun getMappingsForProfileSync(profileId: Long): List<KeyMapping>

    @Query("SELECT * FROM key_mappings WHERE profileId = :profileId AND keyCode = :keyCode LIMIT 1")
    suspend fun getMappingForKey(profileId: Long, keyCode: Int): KeyMapping?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMapping(mapping: KeyMapping): Long

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMappings(mappings: List<KeyMapping>)

    @Update suspend fun updateMapping(mapping: KeyMapping)

    @Delete suspend fun deleteMapping(mapping: KeyMapping)

    @Query("DELETE FROM key_mappings WHERE profileId = :profileId AND keyCode = :keyCode")
    suspend fun deleteMappingByKey(profileId: Long, keyCode: Int)

    @Query("DELETE FROM key_mappings WHERE profileId = :profileId")
    suspend fun deleteAllMappingsForProfile(profileId: Long)
}

@Dao
interface AppProfileAssignmentDao {

    @Query("SELECT * FROM app_profile_assignments ORDER BY appName ASC")
    fun getAllAssignments(): Flow<List<AppProfileAssignment>>

    @Query("SELECT * FROM app_profile_assignments WHERE packageName = :packageName LIMIT 1")
    suspend fun getAssignmentForPackage(packageName: String): AppProfileAssignment?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAssignment(assignment: AppProfileAssignment): Long

    @Update suspend fun updateAssignment(assignment: AppProfileAssignment)

    @Delete suspend fun deleteAssignment(assignment: AppProfileAssignment)

    @Query("DELETE FROM app_profile_assignments WHERE packageName = :packageName")
    suspend fun deleteAssignmentByPackage(packageName: String)

    @Query(
            "UPDATE app_profile_assignments SET isEnabled = :enabled WHERE packageName = :packageName"
    )
    suspend fun setEnabled(packageName: String, enabled: Boolean)
}

// ──────────────────────────────────────────────
// Database
// ──────────────────────────────────────────────

@Database(
        entities = [Profile::class, KeyMapping::class, AppProfileAssignment::class],
        version = 1,
        exportSchema = true
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {

    abstract fun profileDao(): ProfileDao
    abstract fun keyMappingDao(): KeyMappingDao
    abstract fun appProfileAssignmentDao(): AppProfileAssignmentDao

    companion object {
        @Volatile private var INSTANCE: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase {
            return INSTANCE
                    ?: synchronized(this) {
                        val instance =
                                Room.databaseBuilder(
                                                context.applicationContext,
                                                AppDatabase::class.java,
                                                "t9gamepad.db"
                                        )
                                        .addCallback(
                                                object : RoomDatabase.Callback() {
                                                    override fun onCreate(
                                                            db: SupportSQLiteDatabase
                                                    ) {
                                                        super.onCreate(db)
                                                        // Default profile on create
                                                        db.execSQL(
                                                                """
                                                                INSERT INTO profiles (name, isDefault, description, createdAt, analogMode, rampStep, isActive, deviceType) VALUES ('Teclado de Fábrica', 1, 'El teclado original del teléfono (sin mapeos)', ${System.currentTimeMillis()}, 0, 4096, 1, 0)
                                                                """.trimIndent()
                                                        )
                                                    }
                                                }
                                        )
                                        .build()
                        INSTANCE = instance
                        instance
                    }
        }
    }
}
