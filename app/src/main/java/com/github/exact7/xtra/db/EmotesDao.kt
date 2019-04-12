package com.github.exact7.xtra.db

import androidx.lifecycle.LiveData
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.github.exact7.xtra.model.kraken.user.Emote

@Dao
interface EmotesDao {

    @Query("SELECT * FROM emotes ORDER BY code ASC")
    fun getAll(): LiveData<List<Emote>>

    @Insert
    fun insertAll(emotes: List<Emote>)
}