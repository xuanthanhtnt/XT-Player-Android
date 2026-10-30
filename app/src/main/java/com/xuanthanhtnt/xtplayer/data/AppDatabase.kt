package com.xuanthanhtnt.xtplayer.data

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json

class Converters {
    private val serializer = ListSerializer(String.serializer())

    @TypeConverter
    fun fromList(value: List<String>): String = Json.encodeToString(serializer, value)

    @TypeConverter
    fun toList(value: String): List<String> = runCatching { Json.decodeFromString(serializer, value) }.getOrDefault(emptyList())
}

@Database(
    entities = [MediaServer::class, LibraryFolder::class, LibraryItem::class, LibraryFile::class, MediaEntry::class],
    version = 1,
    exportSchema = false,
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun dao(): LibraryDao

    companion object {
        /** An incompatible store from an older build is replaced instead of crashing at launch. */
        fun open(context: Context): AppDatabase =
            Room.databaseBuilder(context, AppDatabase::class.java, "xtplayer.db")
                .fallbackToDestructiveMigration()
                .build()
    }
}

@Dao
interface LibraryDao {
    // Servers
    @Query("SELECT * FROM servers ORDER BY createdAt")
    fun serversFlow(): Flow<List<MediaServer>>

    @Query("SELECT * FROM servers ORDER BY createdAt")
    suspend fun servers(): List<MediaServer>

    @Query("SELECT * FROM servers WHERE id = :id LIMIT 1")
    suspend fun server(id: String): MediaServer?

    @Upsert
    suspend fun upsertServer(server: MediaServer)

    @Query("DELETE FROM servers WHERE id = :id")
    suspend fun deleteServer(id: String)

    // Library folders
    @Query("SELECT * FROM folders ORDER BY name")
    fun foldersFlow(): Flow<List<LibraryFolder>>

    @Query("SELECT * FROM folders ORDER BY name")
    suspend fun folders(): List<LibraryFolder>

    @Query("SELECT * FROM folders WHERE serverID = :serverID")
    suspend fun foldersOf(serverID: String): List<LibraryFolder>

    @Query("SELECT * FROM folders WHERE `key` = :key LIMIT 1")
    suspend fun folder(key: String): LibraryFolder?

    @Upsert
    suspend fun upsertFolder(folder: LibraryFolder)

    @Query("DELETE FROM folders WHERE `key` = :key")
    suspend fun deleteFolder(key: String)

    // Items
    @Query("SELECT * FROM items ORDER BY addedAt DESC")
    fun itemsFlow(): Flow<List<LibraryItem>>

    @Query("SELECT * FROM items")
    suspend fun items(): List<LibraryItem>

    @Query("SELECT * FROM items WHERE `key` = :key LIMIT 1")
    suspend fun item(key: String): LibraryItem?

    @Query("SELECT * FROM items WHERE `key` = :key LIMIT 1")
    fun itemFlow(key: String): Flow<LibraryItem?>

    @Upsert
    suspend fun upsertItem(item: LibraryItem)

    /** Drops movies / shows that no longer have any file. */
    @Query("DELETE FROM items WHERE `key` NOT IN (SELECT DISTINCT itemKey FROM files)")
    suspend fun deleteOrphanItems()

    // Files
    @Query("SELECT * FROM files")
    fun filesFlow(): Flow<List<LibraryFile>>

    @Query("SELECT * FROM files")
    suspend fun files(): List<LibraryFile>

    @Query("SELECT * FROM files WHERE itemKey = :itemKey")
    fun filesOfItemFlow(itemKey: String): Flow<List<LibraryFile>>

    @Query("SELECT * FROM files WHERE itemKey = :itemKey")
    suspend fun filesOfItem(itemKey: String): List<LibraryFile>

    @Query("SELECT * FROM files WHERE folderKey = :folderKey")
    suspend fun filesOfFolder(folderKey: String): List<LibraryFile>

    @Query("SELECT * FROM files WHERE `key` = :key LIMIT 1")
    suspend fun file(key: String): LibraryFile?

    @Upsert
    suspend fun upsertFile(file: LibraryFile)

    @Upsert
    suspend fun upsertFiles(files: List<LibraryFile>)

    @Query("DELETE FROM files WHERE `key` IN (:keys)")
    suspend fun deleteFiles(keys: List<String>)

    @Query("DELETE FROM files WHERE folderKey = :folderKey")
    suspend fun deleteFilesOfFolder(folderKey: String)

    // Continue Watching / resume points
    @Query("SELECT * FROM entries ORDER BY lastPlayedAt DESC")
    fun entriesFlow(): Flow<List<MediaEntry>>

    @Query("SELECT * FROM entries")
    suspend fun entries(): List<MediaEntry>

    @Query("SELECT * FROM entries WHERE `key` = :key LIMIT 1")
    suspend fun entry(key: String): MediaEntry?

    @Upsert
    suspend fun upsertEntry(entry: MediaEntry)

    @Upsert
    suspend fun upsertEntries(entries: List<MediaEntry>)

    @Query("DELETE FROM entries WHERE `key` = :key")
    suspend fun deleteEntry(key: String)
}
