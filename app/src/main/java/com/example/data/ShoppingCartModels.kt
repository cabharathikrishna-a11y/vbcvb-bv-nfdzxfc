package com.example.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "shopping_lists")
data class ShoppingList(
    @PrimaryKey val id: String = java.util.UUID.randomUUID().toString(),
    val name: String,
    val icon: String = "🛒",
    val colorHex: String = "#00E5FF",
    val budget: Double = 0.0,
    val createdAt: Long = System.currentTimeMillis(),
    val isArchived: Boolean = false,
    val orderIndex: Int = 0
)

@Entity(
    tableName = "shopping_items",
    foreignKeys = [
        ForeignKey(
            entity = ShoppingList::class,
            parentColumns = ["id"],
            childColumns = ["listId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["listId"])]
)
data class ShoppingItem(
    @PrimaryKey val id: String = java.util.UUID.randomUUID().toString(),
    val listId: String,
    val name: String,                    // Display name (user editable anytime)
    val originalTitle: String = "",       // Original parsed product title
    val cost: Double = 0.0,               // Unit price
    val units: Int = 1,                   // Quantity / units
    val imageUrl: String = "",            // Product image URL
    val productUrl: String = "",          // Source link (e.g. Amazon URL)
    val isPurchased: Boolean = false,     // Checked / Bought / In Cart
    val category: String = "General",     // Category tag
    val notes: String = "",               // Additional specs or notes
    val createdAt: Long = System.currentTimeMillis()
) {
    val totalCost: Double
        get() = cost * units
}

@Dao
interface ShoppingDao {
    // Shopping Lists
    @Query("SELECT * FROM shopping_lists WHERE isArchived = 0 ORDER BY orderIndex ASC, createdAt ASC")
    fun getAllActiveLists(): Flow<List<ShoppingList>>

    @Query("SELECT * FROM shopping_lists ORDER BY orderIndex ASC, createdAt ASC")
    fun getAllLists(): Flow<List<ShoppingList>>

    @Query("SELECT * FROM shopping_lists WHERE id = :listId LIMIT 1")
    suspend fun getListById(listId: String): ShoppingList?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertList(list: ShoppingList)

    @Update
    suspend fun updateList(list: ShoppingList)

    @Delete
    suspend fun deleteList(list: ShoppingList)

    @Query("DELETE FROM shopping_lists WHERE id = :listId")
    suspend fun deleteListById(listId: String)

    // Shopping Items
    @Query("SELECT * FROM shopping_items WHERE listId = :listId ORDER BY isPurchased ASC, createdAt DESC")
    fun getItemsForList(listId: String): Flow<List<ShoppingItem>>

    @Query("SELECT * FROM shopping_items ORDER BY createdAt DESC")
    fun getAllItems(): Flow<List<ShoppingItem>>

    @Query("SELECT * FROM shopping_items WHERE listId = :listId")
    suspend fun getItemsForListDirect(listId: String): List<ShoppingItem>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertItem(item: ShoppingItem)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertItems(items: List<ShoppingItem>)

    @Update
    suspend fun updateItem(item: ShoppingItem)

    @Delete
    suspend fun deleteItem(item: ShoppingItem)

    @Query("DELETE FROM shopping_items WHERE id = :itemId")
    suspend fun deleteItemById(itemId: String)

    @Query("UPDATE shopping_items SET isPurchased = :isPurchased WHERE id = :itemId")
    suspend fun updateItemPurchasedState(itemId: String, isPurchased: Boolean)

    @Query("UPDATE shopping_items SET units = :units WHERE id = :itemId")
    suspend fun updateItemUnits(itemId: String, units: Int)

    @Query("UPDATE shopping_items SET name = :name WHERE id = :itemId")
    suspend fun updateItemName(itemId: String, name: String)

    @Query("DELETE FROM shopping_items WHERE listId = :listId AND isPurchased = 1")
    suspend fun clearPurchasedItems(listId: String)
}
