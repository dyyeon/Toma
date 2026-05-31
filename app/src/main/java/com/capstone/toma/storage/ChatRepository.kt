package com.capstone.toma.storage

import android.content.Context
import kotlinx.coroutines.flow.Flow

class ChatRepository private constructor(private val dao: ChatStorageDao) {

    fun observeSessions(): Flow<List<ChatSessionEntity>> = dao.observeSessions()

    suspend fun createSession(id: String, title: String, createdAt: Long) {
        dao.insertSession(ChatSessionEntity(id, title, createdAt, createdAt))
    }

    suspend fun saveMessage(message: ChatMessageEntity) = dao.insertMessage(message)

    suspend fun getMessages(sessionId: String): List<ChatMessageEntity> = dao.getMessages(sessionId)

    suspend fun deleteSession(sessionId: String) {
        dao.deleteMessagesBySession(sessionId)
        dao.deleteSession(sessionId)
    }

    suspend fun touchSession(sessionId: String) {
        dao.touchSession(sessionId, System.currentTimeMillis())
    }

    suspend fun saveMessageRecipeContext(messageId: String, contextJson: String) {
        dao.updateMessageRecipeContext(messageId, contextJson)
    }

    companion object {
        @Volatile
        private var instance: ChatRepository? = null

        fun getInstance(context: Context): ChatRepository =
            instance ?: synchronized(this) {
                instance ?: ChatRepository(
                    RecipeStorageDatabase.getInstance(context).chatStorageDao()
                ).also { instance = it }
            }
    }
}
