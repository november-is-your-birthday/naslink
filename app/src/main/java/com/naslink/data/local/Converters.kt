package com.naslink.data.local

import androidx.room.TypeConverter
import com.naslink.data.model.ConnectionProtocol
import com.naslink.data.model.FileSource

class Converters {
    @TypeConverter
    fun fromConnectionProtocol(value: ConnectionProtocol): String = value.name

    @TypeConverter
    fun toConnectionProtocol(value: String): ConnectionProtocol = ConnectionProtocol.valueOf(value)

    @TypeConverter
    fun fromFileSource(value: FileSource): String = value.name

    @TypeConverter
    fun toFileSource(value: String): FileSource = FileSource.valueOf(value)
}
