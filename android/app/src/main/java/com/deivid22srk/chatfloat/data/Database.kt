package com.deivid22srk.chatfloat.data

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "messages")
data class MessageEntity(
    @PrimaryKey val id: Long,
    @ColumnInfo(name = "text") val text: String,
    @ColumnInfo(name = "sender_name") val senderName: String,
    @ColumnInfo(name = "sender_token") val senderToken: String?,
    @ColumnInfo(name = "sender_avatar") val senderAvatar: String?,
    @ColumnInfo(name = "timestamp") val timestamp: Long,
    @ColumnInfo(name = "is_outgoing") val isOutgoing: Boolean
)

@Entity(tableName = "known_accounts")
data class KnownAccountEntity(
    @PrimaryKey val token: String,
    @ColumnInfo(name = "username") val username: String,
    @ColumnInfo(name = "avatar_base64") val avatarBase64: String?,
    @ColumnInfo(name = "first_seen") val firstSeen: Long
)

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages ORDER BY timestamp ASC")
    fun observeAll(): Flow<List<MessageEntity>>

    @Query("SELECT * FROM messages ORDER BY timestamp ASC")
    suspend fun getAll(): List<MessageEntity>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(messages: List<MessageEntity>)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(message: MessageEntity)

    @Query("SELECT COUNT(*) FROM messages")
    suspend fun count(): Int

    @Query("DELETE FROM messages")
    suspend fun clear()
}

@Dao
interface KnownAccountDao {
    @Query("SELECT * FROM known_accounts WHERE token = :token")
    suspend fun findByToken(token: String): KnownAccountEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(account: KnownAccountEntity)

    @Query("SELECT * FROM known_accounts ORDER BY first_seen DESC")
    suspend fun getAll(): List<KnownAccountEntity>
}

@Database(
    entities = [MessageEntity::class, KnownAccountEntity::class],
    version = 1,
    exportSchema = false
)
abstract class ChatFloatDatabase : RoomDatabase() {
    abstract fun messageDao(): MessageDao
    abstract fun knownAccountDao(): KnownAccountDao

    companion object {
        @Volatile
        private var INSTANCE: ChatFloatDatabase? = null

        fun get(context: Context): ChatFloatDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    ChatFloatDatabase::class.java,
                    "chatfloat.db"
                ).fallbackToDestructiveMigration().build().also {
                    INSTANCE = it
                }
            }
    }
}

// Convenience extension to convert entity -> domain
fun MessageEntity.toDomain() = ChatMessage(
    id = id,
    text = text,
    senderName = senderName,
    senderToken = senderToken,
    senderAvatar = senderAvatar,
    timestamp = timestamp,
    isOutgoing = isOutgoing
)

fun ChatMessage.toEntity() = MessageEntity(
    id = id,
    text = text,
    senderName = senderName,
    senderToken = senderToken,
    senderAvatar = senderAvatar,
    timestamp = timestamp,
    isOutgoing = isOutgoing
)
