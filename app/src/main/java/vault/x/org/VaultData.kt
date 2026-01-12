package vault.x.org

import java.util.UUID

data class VaultIndex(
    val items: MutableList<VaultItem> = mutableListOf()
)

data class VaultItem(
    val id: String = UUID.randomUUID().toString(),
    var parentId: String? = null,
    var name: String,
    val type: ItemType,
    val dateModified: Long = System.currentTimeMillis(),
    val size: Long = 0L,
    var isSelected: Boolean = false,
    var tags: List<String> = emptyList(),
    var isAiProcessed: Boolean = false,
    var isDeleted: Boolean = false, // Recycle Bin Flag
    var deletedTimestamp: Long = 0L
)

enum class ItemType { IMAGE, VIDEO, AUDIO, PDF, FOLDER, UNKNOWN }
enum class SortOption { DATE_NEW, DATE_OLD, NAME_AZ, NAME_ZA }