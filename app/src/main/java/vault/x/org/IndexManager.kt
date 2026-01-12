package vault.x.org

import android.content.Context
import com.google.gson.Gson
import java.io.File

class IndexManager(private val context: Context, private val crypto: CryptoManager) {

    private val gson = Gson()
    private val indexFile = File(crypto.getVaultDir(), "index.dat")

    // Holds the live data
    var index: VaultIndex = VaultIndex()

    // Load the Index from Disk (Decrypt it)
    fun loadIndex(pin: String) {
        if (!indexFile.exists()) {
            index = VaultIndex()
            return
        }
        try {
            val json = crypto.readEncryptedTextFile(pin, indexFile)
            if (json != null) {
                index = gson.fromJson(json, VaultIndex::class.java)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            index = VaultIndex() // Fallback if corrupt
        }
    }

    // Save the Index to Disk (Encrypt it)
    fun saveIndex(pin: String) {
        val json = gson.toJson(index)
        crypto.writeEncryptedTextFile(pin, indexFile, json)
    }

    // --- OPERATIONS ---

    fun createFolder(name: String, parentId: String?) {
        val folder = VaultItem(
            name = name,
            parentId = parentId,
            type = ItemType.FOLDER
        )
        index.items.add(folder)
    }

    fun addFile(item: VaultItem) {
        index.items.add(item)
    }

    fun deleteItems(items: List<VaultItem>, pin: String) {
        items.forEach { item ->
            // If folder, delete children recursively (logic simplified for flat list)
            if (item.type == ItemType.FOLDER) {
                val children = index.items.filter { it.parentId == item.id }
                deleteItems(children, pin)
            }
            // Delete actual file on disk
            val file = File(crypto.getStorageDir(), "${item.id}.enc")
            if (file.exists()) file.delete()

            // Delete thumbnail
            val thumb = File(crypto.getStorageDir(), "${item.id}.thumb")
            if (thumb.exists()) thumb.delete()

            index.items.remove(item)
        }
        saveIndex(pin)
    }

    fun renameItem(item: VaultItem, newName: String, pin: String) {
        val target = index.items.find { it.id == item.id }
        target?.name = newName
        saveIndex(pin)
    }

    fun moveItems(items: List<VaultItem>, newParentId: String?, pin: String) {
        items.forEach { item ->
            // Prevent moving a folder into itself
            if (item.id != newParentId) {
                val target = index.items.find { it.id == item.id }
                target?.parentId = newParentId
            }
        }
        saveIndex(pin)
    }
}