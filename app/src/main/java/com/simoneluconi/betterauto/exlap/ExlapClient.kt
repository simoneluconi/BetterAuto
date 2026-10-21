package com.simoneluconi.betterauto.exlap

import com.simoneluconi.betterauto.EventLog
import com.simoneluconi.betterauto.car.VehicleDemand
import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.StringReader
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64
import java.util.Locale
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Client side of VW's Exlap (vehicle data as XML), read only: with [credentials] it logs in, lists what
 * the car offers to that user, reads every object once (logged, to see what a car really sends) and
 * then follows, by subscription, only the objects the screens need now ([setDemand]): who is aboard
 * always, the overview's values while the vehicle screen is open, everything while its full list is.
 * It never calls the car's actions. Without credentials it only asks what the server is.
 *
 * Every answer is matched to its request by id, so a user with fewer rights (objects answered with
 * noMatchingUrl, failed subscriptions) only gets less data, never a stuck session.
 *
 * Over Android Auto the protocol is wrapped: ExlapConnectionRequest / ExlapConnectionReturn set up a
 * session id, then ExlapStatement elements of that session carry it (Status Init, Req / Rsp, Dat).
 * The transport ([send], [onText]) is the car maker's vendor channel.
 */
class ExlapClient(
    private val credentials: Pair<String, String>?,
    private val onStatus: (ExlapStatus) -> Unit,
    private val send: (String) -> Unit,
    private val data: ExlapData = ExlapData,
    private val sessionId: String = randomSessionId(),
) {
    private enum class Step { PROTOCOL, CHALLENGE, LOGIN, DIR, INTERFACE, GET, SUBSCRIBE, UNSUBSCRIBE, BYE }

    private class Pending(val step: Step, val url: String? = null)

    private val documents = DocumentBuilderFactory.newInstance().newDocumentBuilder()
    private val pending = HashMap<Int, Pending>()
    private var nextRequestId = 1
    private var beacons = 0
    private val members = ArrayList<ExlapValue>()
    private val objects = ArrayList<String>()
    private var refusedObjects = 0
    private val subscribed = LinkedHashSet<String>()
    private val refusedSubscriptions = HashSet<String>()
    private var loggedRefusals = 0
    private var demand = VehicleDemand.NONE
    /** Every object has been read once: from now on subscriptions follow the demand. */
    private var ready = false
    private var buffer = ""

    @Synchronized
    fun start() {
        onStatus(ExlapStatus(if (credentials == null) ExlapState.NO_CREDENTIALS else ExlapState.CONNECTING))
        EventLog.log("Exlap: connecting${if (credentials == null) ", no credentials (probe only)" else ""}")
        send("<ExlapConnectionRequest session_id=\"$sessionId\" />")
    }

    fun stop() = data.clear()

    /** What the screens need now; the subscriptions change to match once the first read is done. */
    @Synchronized
    fun setDemand(value: VehicleDemand) {
        if (value == demand) return
        demand = value
        if (ready) follow()
    }

    /** Text from the channel: it may hold several top-level elements, or only part of one. */
    @Synchronized
    fun onText(text: String) {
        buffer += text
        while (true) {
            val match = TOP_LEVEL.find(buffer) ?: break
            buffer = buffer.substring(match.range.last + 1)
            runCatching { onElement(match.value.trim()) }
                .onFailure { EventLog.log("Exlap: cannot read a message: $it") }
        }
        if (buffer.length > MAX_BUFFER) buffer = ""
    }

    private fun onElement(xml: String) {
        when {
            xml.startsWith("<ExlapBeacon") -> if (beacons++ == 0) EventLog.log("Exlap: ${attr(xml, "service") ?: "server"} active")
            xml.startsWith("<ExlapConnectionReturn") -> if (!xml.contains("connected=\"true\"")) {
                EventLog.log("Exlap: the car refused the connection")
                onStatus(ExlapStatus(ExlapState.NOT_ANSWERING))
            }
            xml.startsWith("<ExlapStatement") && xml.contains(sessionId) -> {
                var child = documents.parse(InputSource(StringReader(xml))).documentElement.firstChild
                while (child != null) {
                    (child as? Element)?.let(::onStatement)
                    child = child.nextSibling
                }
            }
        }
    }

    private fun onStatement(element: Element) {
        when (element.tagName) {
            // The server greets each new session; then the protocol and its capabilities are negotiated
            "Status" -> if (element.getElementsByTagName("Init").length > 0) request(Step.PROTOCOL, "<Protocol version=\"1\" returnCapabilities=\"true\"/>")
            "Rsp" -> {
                val request = element.getAttribute("id").toIntOrNull()?.let { pending.remove(it) } ?: return
                onResponse(request, element, element.getAttribute("status").ifEmpty { null })
            }
            // Subscribed values, pushed as they change (at most every SUBSCRIPTION_INTERVAL_MS)
            "Dat" -> onData(element)
        }
    }

    private fun onResponse(request: Pending, rsp: Element, error: String?) {
        fun first(tag: String) = rsp.getElementsByTagName(tag).item(0) as Element?
        when (request.step) {
            Step.PROTOCOL -> {
                first("Capabilities")?.let { EventLog.log("Exlap: ${it.getAttribute("service")} ${it.getAttribute("version")}") }
                if (credentials == null) request(Step.BYE, "<Bye/>") else request(Step.CHALLENGE, "<Authenticate phase=\"challenge\"/>")
            }
            Step.CHALLENGE -> first("Challenge")?.let(::onChallenge) ?: refused(error ?: "no challenge")
            // An empty answer to the response phase means logged in
            Step.LOGIN -> if (error != null || rsp.hasChildElements()) refused(error ?: "denied") else {
                EventLog.log("Exlap: logged in")
                onStatus(ExlapStatus(ExlapState.LOGGED_IN))
                request(Step.DIR, "<Dir/>")
            }
            Step.DIR -> onUrlList(first("UrlList"), error)
            Step.INTERFACE -> onInterface(rsp, error)
            Step.GET -> {
                val found = rsp.getElementsByTagName("ObjectData")
                for (i in 0 until found.length) (found.item(i) as Element).let { readValues(it.getAttribute("url").ifEmpty { request.url.orEmpty() }, it) }
                if (pending.values.none { it.step == Step.GET }) firstReadDone()
            }
            Step.SUBSCRIBE -> {
                // Refused once, not asked again in this session
                if (error != null) request.url?.let {
                    subscribed -= it
                    refusedSubscriptions += it
                }
                if (pending.values.none { it.step == Step.SUBSCRIBE } && refusedSubscriptions.size > loggedRefusals) {
                    loggedRefusals = refusedSubscriptions.size
                    EventLog.log("Exlap: $loggedRefusals subscriptions refused for this user")
                }
            }
            Step.UNSUBSCRIBE, Step.BYE -> Unit
        }
    }

    private fun refused(reason: String) {
        EventLog.log("Exlap: login refused ($reason)")
        onStatus(ExlapStatus(ExlapState.LOGIN_REFUSED))
        request(Step.BYE, "<Bye/>")
    }

    private fun onChallenge(challenge: Element) {
        val (user, password) = credentials ?: return
        val nonce = Base64.getEncoder().encodeToString(Base64.getMimeDecoder().decode(challenge.getAttribute("nonce")))
        val cnonce = Base64.getEncoder().encodeToString(ByteArray(16).also { SecureRandom().nextBytes(it) })
        val digest = digest(user, password, nonce, cnonce)
        // Logged as a step only: the request carries a digest of the password
        EventLog.log("Exlap: logging in")
        request(Step.LOGIN, "<Authenticate phase=\"response\" user=\"${escape(user)}\" cnonce=\"$cnonce\" digest=\"$digest\"/>", log = false)
    }

    private fun onUrlList(urlList: Element?, error: String?) {
        val matches = urlList?.getElementsByTagName("Match")
        val all = (0 until (matches?.length ?: 0)).map { matches!!.item(it) as Element }.filter { it.getAttribute("url").isNotEmpty() }
        // Functions are the car's actions: never called, so not even described
        val (functions, data) = all.partition { it.getAttribute("type") == "function" }
        val urls = data.map { it.getAttribute("url") }
        EventLog.log("Exlap: ${urls.size} objects offered, ${functions.size} functions${error?.let { " ($it)" } ?: ""}: ${urls.joinToString()}")
        if (urls.isEmpty()) return finishSchema()
        // Each object's members and units first, then one read of everything
        urls.forEach { request(Step.INTERFACE, "<Interface url=\"${escape(it)}\"/>", log = false) }
    }

    private fun onInterface(rsp: Element, error: String?) {
        val found = rsp.getElementsByTagName("Object")
        // Outside this user's rights the car answers noMatchingUrl: skip the object, keep going
        if (error != null || found.length == 0) refusedObjects++
        for (i in 0 until found.length) {
            val obj = found.item(i) as Element
            val url = obj.getAttribute("url")
            objects += url
            var member = obj.firstChild
            while (member != null) {
                (member as? Element)?.takeIf { it.getAttribute("name").isNotEmpty() }?.let {
                    members += ExlapValue(
                        url, it.getAttribute("name"), null, it.getAttribute("unit").ifEmpty { null },
                        it.getAttribute("description").ifEmpty { null }, it.tagName,
                    )
                }
                member = member.nextSibling
            }
        }
        if (pending.values.none { it.step == Step.INTERFACE }) finishSchema()
    }

    private fun finishSchema() {
        data.setSchema(members)
        val refused = if (refusedObjects > 0) ", $refusedObjects not readable with this user" else ""
        EventLog.log("Exlap: reading ${objects.size} objects once, ${members.size} values$refused")
        onStatus(ExlapStatus(ExlapState.LOGGED_IN, members.size))
        if (objects.isEmpty()) return firstReadDone()
        // Get only reads, it changes nothing
        objects.forEach { request(Step.GET, "<Get url=\"${escape(it)}\"/>", log = false, url = it) }
    }

    private fun firstReadDone() {
        data.flush()
        logFirstValues()
        ready = true
        follow()
    }

    /** Subscribes to what the demand needs and drops the rest. Subscribe only asks the car to push updates. */
    private fun follow() {
        val wanted = objects.filter { ExlapMapping.neededAt(it) <= demand && it !in refusedSubscriptions }.toSet()
        val added = wanted - subscribed
        val removed = subscribed - wanted
        if (added.isEmpty() && removed.isEmpty()) return
        for (url in removed) request(Step.UNSUBSCRIBE, "<Unsubscribe url=\"${escape(url)}\"/>", log = false, url = url)
        for (url in added) request(Step.SUBSCRIBE, "<Subscribe url=\"${escape(url)}\" ival=\"$SUBSCRIPTION_INTERVAL_MS\"/>", log = false, url = url)
        subscribed -= removed
        subscribed += added
        EventLog.log("Exlap: following ${subscribed.size} objects (${demand.name.lowercase(Locale.ROOT)})")
    }

    /**
     * Once per session: the first value of every object, those we turn into vehicle data apart from the
     * rest, so a log from a drive shows what this car sends. Objects that may hold personal data
     * (places, VIN, phone, media) are named without their values.
     */
    private fun logFirstValues() {
        val byUrl = data.current().values.groupBy { it.url }
        val (mapped, other) = objects.partition { ExlapMapping.isMapped(it) }
        logLines("first values, mapped", mapped.map { describe(it, byUrl[it].orEmpty()) })
        logLines("first values, not mapped yet", other.map { describe(it, byUrl[it].orEmpty()) })
    }

    private fun describe(url: String, values: List<ExlapValue>): String {
        if (ExlapMapping.isPrivate(url)) return "$url=(hidden)"
        fun text(v: ExlapValue) = (v.value?.take(MAX_LOGGED_VALUE) ?: "-") + (v.unit?.let { " $it" } ?: "")
        val single = values.singleOrNull()
        return when {
            values.isEmpty() -> "$url=?"
            single != null && single.name.equals(url, ignoreCase = true) -> "$url=${text(single)}"
            else -> "$url{${values.joinToString { "${it.name}=${text(it)}" }}}"
        }
    }

    private fun logLines(title: String, items: List<String>) {
        if (items.isEmpty()) return
        val lines = ArrayList<StringBuilder>()
        for (item in items) {
            val last = lines.lastOrNull()
            if (last == null || last.length + item.length > MAX_LOG_LINE) lines += StringBuilder(item) else last.append("; ").append(item)
        }
        lines.forEachIndexed { i, line -> EventLog.log("Exlap: $title (${i + 1}/${lines.size}): $line") }
    }

    private fun onData(dat: Element) {
        readValues(dat.getAttribute("url"), dat)
        val nested = dat.getElementsByTagName("Dat")
        for (i in 0 until nested.length) (nested.item(i) as Element).let { readValues(it.getAttribute("url"), it) }
        data.flush()
    }

    private fun readValues(url: String, container: Element) {
        if (url.isNotEmpty()) readMembers(url, container, "")
    }

    /** Simple members by name; objects and lists inside an object flattened as "obj.member", "list[0].member". */
    private fun readMembers(url: String, parent: Element, prefix: String) {
        var child = parent.firstChild
        while (child != null) {
            (child as? Element)?.let { e ->
                val name = e.getAttribute("name")
                when {
                    name.isEmpty() -> Unit
                    e.tagName == "List" -> {
                        var index = 0
                        var item = e.firstChild
                        while (item != null) {
                            if (item is Element && item.tagName == "Elem") readMembers(url, item, "$prefix$name[${index++}].")
                            item = item.nextSibling
                        }
                    }
                    e.tagName == "Obj" || e.tagName == "Alt" -> readMembers(url, e, "$prefix$name.")
                    else -> {
                        val state = e.getAttribute("state")
                        val value = if (state == "nodata" || state == "error") null else e.getAttribute("val").ifEmpty { null }
                        data.update(url, prefix + name, value)
                    }
                }
            }
            child = child.nextSibling
        }
    }

    private fun request(step: Step, body: String, log: Boolean = true, url: String? = null) {
        val id = nextRequestId++
        pending[id] = Pending(step, url)
        if (log) EventLog.log("Exlap: -> $body")
        send("<ExlapStatement session_id=\"$sessionId\"><Req id=\"$id\">$body</Req></ExlapStatement>")
    }

    private fun Element.hasChildElements(): Boolean {
        var child = firstChild
        while (child != null) {
            if (child is Element) return true
            child = child.nextSibling
        }
        return false
    }

    private fun attr(xml: String, name: String) = Regex("""$name="([^"]*)"""").find(xml)?.groupValues?.get(1)

    private fun escape(s: String) = s.replace("&", "&amp;").replace("\"", "&quot;").replace("<", "&lt;")

    companion object {
        private const val MAX_BUFFER = 1 shl 20
        /** Fast objects (speed, rpm) are pushed at most 4 times a second; slow ones come as they change. */
        private const val SUBSCRIPTION_INTERVAL_MS = 250
        private const val MAX_LOG_LINE = 900
        private const val MAX_LOGGED_VALUE = 40
        private val TOP_LEVEL = Regex("""<ExlapStatement\b[\s\S]*?</ExlapStatement>|<ExlapBeacon\b[\s\S]*?</ExlapBeacon>|<ExlapConnection\w+\b[^>]*/>""")

        /** SHA-256 of user:password:nonce:cnonce, each field cut at 44 characters as the server does. */
        internal fun digest(user: String, password: String, nonce: String, cnonce: String): String {
            val material = String.format(Locale.ROOT, "%.44s:%.44s:%.44s:%.44s", user, password, nonce, cnonce)
            return Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(material.toByteArray(Charsets.US_ASCII)))
        }

        private fun randomSessionId(): String {
            val alphabet = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
            val random = SecureRandom()
            return String(CharArray(32) { alphabet[random.nextInt(alphabet.length)] })
        }
    }
}
