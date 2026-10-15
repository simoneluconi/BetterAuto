package com.simoneluconi.betterauto.phone

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageDecoder
import android.net.Uri
import android.provider.CallLog
import android.provider.ContactsContract
import android.provider.ContactsContract.CommonDataKinds.Phone
import android.telephony.PhoneNumberUtils
import android.util.LruCache
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.simoneluconi.betterauto.EventLog
import com.simoneluconi.betterauto.settings.NameOrder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.Locale

data class Contact(
    val id: Long,
    val name: String,
    /** Small photo, for lists. */
    val photo: Uri?,
    val starred: Boolean,
    /** Full-size photo, for big avatars; null when the contact has no photo. */
    val photoLarge: Uri? = null,
    val company: String? = null,
    /** Type of the number that matched a lookup ("Mobile", "Work"...). */
    val numberLabel: String? = null,
)

data class PhoneNumber(val number: String, val label: String, val primary: Boolean)

/** Calls from/to the same number in a row are grouped, like every dialer does. */
data class RecentCall(
    val number: String,
    val name: String?,
    val numberLabel: String?,
    val type: Int,
    val date: Long,
    val count: Int,
    val photo: Uri?,
    val contactId: Long?,
)

/** Contacts, favorites and call log, read through the phone's providers. */
object PhoneBook {
    private const val MAX_RECENTS = 150
    private const val MAX_PHOTO_PX = 400
    private val photos = LruCache<String, ImageBitmap>(120)

    fun canRead(context: Context) =
        context.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    fun canReadCallLog(context: Context) =
        context.checkSelfPermission(Manifest.permission.READ_CALL_LOG) == PackageManager.PERMISSION_GRANTED

    fun canCall(context: Context) =
        context.checkSelfPermission(Manifest.permission.CALL_PHONE) == PackageManager.PERMISSION_GRANTED

    /** Contacts with at least one phone number, sorted the way their names are shown. */
    suspend fun contacts(context: Context, order: NameOrder, starredOnly: Boolean = false): List<Contact> =
        withContext(Dispatchers.IO) {
            if (!canRead(context)) return@withContext emptyList()
            val nameColumn = order.nameColumn
            val selection = buildString {
                append("${ContactsContract.Contacts.HAS_PHONE_NUMBER} = 1")
                if (starredOnly) append(" AND ${ContactsContract.Contacts.STARRED} = 1")
            }
            val result = ArrayList<Contact>()
            runCatching {
                context.contentResolver.query(
                    ContactsContract.Contacts.CONTENT_URI,
                    arrayOf(
                        ContactsContract.Contacts._ID, nameColumn,
                        ContactsContract.Contacts.PHOTO_THUMBNAIL_URI, ContactsContract.Contacts.STARRED,
                        ContactsContract.Contacts.PHOTO_URI,
                    ),
                    selection, null, "${order.sortColumn} COLLATE LOCALIZED ASC",
                )?.use { c ->
                    while (c.moveToNext()) {
                        val name = c.getString(1)?.takeIf { it.isNotBlank() } ?: continue
                        result += Contact(
                            c.getLong(0), name, c.getString(2)?.let(Uri::parse), c.getInt(3) == 1,
                            photoLarge = c.getString(4)?.let(Uri::parse),
                        )
                    }
                }
            }.onFailure { EventLog.log("Phone: contacts query failed: $it") }
            val companies = companies(context)
            result.map { it.copy(company = companies[it.id]) }
        }

    /** Company of each contact that has one, by contact id. */
    private fun companies(context: Context): Map<Long, String> {
        val map = HashMap<Long, String>()
        runCatching {
            context.contentResolver.query(
                ContactsContract.Data.CONTENT_URI,
                arrayOf(ContactsContract.Data.CONTACT_ID, ContactsContract.CommonDataKinds.Organization.COMPANY),
                "${ContactsContract.Data.MIMETYPE} = ?",
                arrayOf(ContactsContract.CommonDataKinds.Organization.CONTENT_ITEM_TYPE), null,
            )?.use { c ->
                while (c.moveToNext()) {
                    val company = c.getString(1)?.takeIf { it.isNotBlank() } ?: continue
                    map.putIfAbsent(c.getLong(0), company)
                }
            }
        }.onFailure { EventLog.log("Phone: companies query failed: $it") }
        return map
    }

    suspend fun numbers(context: Context, contactId: Long): List<PhoneNumber> = withContext(Dispatchers.IO) {
        val result = ArrayList<PhoneNumber>()
        runCatching {
            context.contentResolver.query(
                Phone.CONTENT_URI,
                arrayOf(Phone.NUMBER, Phone.TYPE, Phone.LABEL, Phone.IS_SUPER_PRIMARY, Phone.NORMALIZED_NUMBER),
                "${Phone.CONTACT_ID} = ?", arrayOf(contactId.toString()), "${Phone.IS_SUPER_PRIMARY} DESC",
            )?.use { c ->
                val seen = HashSet<String>()
                while (c.moveToNext()) {
                    val number = c.getString(0)?.takeIf { it.isNotBlank() } ?: continue
                    // The same number is often stored twice (formatted and not, or by two accounts)
                    if (!seen.add(c.getString(4) ?: PhoneNumberUtils.normalizeNumber(number))) continue
                    val label = Phone.getTypeLabel(context.resources, c.getInt(1), c.getString(2)).toString()
                    result += PhoneNumber(number, label, c.getInt(3) == 1)
                }
            }
        }.onFailure { EventLog.log("Phone: numbers query failed: $it") }
        result
    }

    suspend fun recents(context: Context, order: NameOrder): List<RecentCall> = withContext(Dispatchers.IO) {
        if (!canReadCallLog(context)) return@withContext emptyList()
        val byNumber = if (canRead(context)) numberIndex(context, order) else emptyMap()
        val result = ArrayList<RecentCall>()
        runCatching {
            context.contentResolver.query(
                CallLog.Calls.CONTENT_URI,
                arrayOf(
                    CallLog.Calls.NUMBER, CallLog.Calls.CACHED_NAME, CallLog.Calls.TYPE, CallLog.Calls.DATE,
                    CallLog.Calls.CACHED_NORMALIZED_NUMBER, CallLog.Calls.CACHED_NUMBER_TYPE, CallLog.Calls.CACHED_NUMBER_LABEL,
                ),
                null, null, "${CallLog.Calls.DATE} DESC",
            )?.use { c ->
                while (c.moveToNext() && result.size < MAX_RECENTS) {
                    val number = c.getString(0).orEmpty()
                    val type = c.getInt(2)
                    val key = c.getString(4) ?: normalize(number)
                    val last = result.lastOrNull()
                    if (last != null && last.type == type && normalize(last.number) == normalize(number)) {
                        result[result.lastIndex] = last.copy(count = last.count + 1)
                        continue
                    }
                    val contact = byNumber[key] ?: byNumber[normalize(number)]
                    val label = if (c.isNull(5)) null else Phone.getTypeLabel(context.resources, c.getInt(5), c.getString(6)).toString()
                    result += RecentCall(
                        number = number,
                        name = contact?.name ?: c.getString(1)?.takeIf { it.isNotBlank() },
                        numberLabel = label.takeIf { contact != null || c.getString(1) != null },
                        type = type,
                        date = c.getLong(3),
                        count = 1,
                        photo = contact?.photo,
                        contactId = contact?.id,
                    )
                }
            }
        }.onFailure { EventLog.log("Phone: call log query failed: $it") }
        result
    }

    /** Name and photo for a number, for the call screen. */
    suspend fun lookup(context: Context, number: String, order: NameOrder): Contact? = withContext(Dispatchers.IO) {
        if (!canRead(context) || number.isBlank()) return@withContext null
        runCatching {
            val uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number))
            context.contentResolver.query(
                uri,
                arrayOf(
                    ContactsContract.PhoneLookup._ID, ContactsContract.PhoneLookup.PHOTO_THUMBNAIL_URI,
                    ContactsContract.PhoneLookup.PHOTO_URI, ContactsContract.PhoneLookup.TYPE, ContactsContract.PhoneLookup.LABEL,
                ),
                null, null, null,
            )?.use { c ->
                if (!c.moveToFirst()) return@use null
                val id = c.getLong(0)
                val label = if (c.isNull(3)) null else Phone.getTypeLabel(context.resources, c.getInt(3), c.getString(4)).toString()
                Contact(
                    id, nameOf(context, id, order) ?: return@use null, c.getString(1)?.let(Uri::parse), false,
                    photoLarge = c.getString(2)?.let(Uri::parse),
                    company = companyOf(context, id),
                    numberLabel = label,
                )
            }
        }.onFailure { EventLog.log("Phone: lookup failed: $it") }.getOrNull()
    }

    suspend fun photo(context: Context, uri: Uri): ImageBitmap? {
        photos.get(uri.toString())?.let { return it }
        return withContext(Dispatchers.IO) {
            runCatching {
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(context.contentResolver, uri)) { decoder, info, _ ->
                    decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
                    val side = minOf(info.size.width, info.size.height)
                    if (side > MAX_PHOTO_PX) decoder.setTargetSampleSize(side / MAX_PHOTO_PX)
                }.asImageBitmap()
            }.getOrNull()?.also { photos.put(uri.toString(), it) }
        }
    }

    private fun companyOf(context: Context, contactId: Long): String? =
        context.contentResolver.query(
            ContactsContract.Data.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Organization.COMPANY),
            "${ContactsContract.Data.CONTACT_ID} = ? AND ${ContactsContract.Data.MIMETYPE} = ?",
            arrayOf(contactId.toString(), ContactsContract.CommonDataKinds.Organization.CONTENT_ITEM_TYPE), null,
        )?.use { c -> if (c.moveToFirst()) c.getString(0)?.takeIf { it.isNotBlank() } else null }

    private fun nameOf(context: Context, contactId: Long, order: NameOrder): String? =
        context.contentResolver.query(
            ContactsContract.Contacts.CONTENT_URI, arrayOf(order.nameColumn),
            "${ContactsContract.Contacts._ID} = ?", arrayOf(contactId.toString()), null,
        )?.use { c -> if (c.moveToFirst()) c.getString(0) else null }

    /** Every stored number of every contact, by normalized number. */
    private fun numberIndex(context: Context, order: NameOrder): Map<String, Contact> {
        val nameColumn = if (order == NameOrder.LAST_FIRST) Phone.DISPLAY_NAME_ALTERNATIVE else Phone.DISPLAY_NAME_PRIMARY
        val map = HashMap<String, Contact>()
        runCatching {
            context.contentResolver.query(
                Phone.CONTENT_URI,
                arrayOf(Phone.NUMBER, Phone.NORMALIZED_NUMBER, Phone.CONTACT_ID, nameColumn, Phone.PHOTO_THUMBNAIL_URI),
                null, null, null,
            )?.use { c ->
                while (c.moveToNext()) {
                    val name = c.getString(3) ?: continue
                    val contact = Contact(c.getLong(2), name, c.getString(4)?.let(Uri::parse), false)
                    c.getString(1)?.let { map[it] = contact }
                    c.getString(0)?.let { map[normalize(it)] = contact }
                }
            }
        }
        return map
    }

    private fun normalize(number: String): String =
        PhoneNumberUtils.formatNumberToE164(number, Locale.getDefault().country) ?: PhoneNumberUtils.normalizeNumber(number)
}

private val NameOrder.nameColumn
    get() = if (this == NameOrder.LAST_FIRST) ContactsContract.Contacts.DISPLAY_NAME_ALTERNATIVE
    else ContactsContract.Contacts.DISPLAY_NAME_PRIMARY

private val NameOrder.sortColumn
    get() = if (this == NameOrder.LAST_FIRST) ContactsContract.Contacts.SORT_KEY_ALTERNATIVE
    else ContactsContract.Contacts.SORT_KEY_PRIMARY
