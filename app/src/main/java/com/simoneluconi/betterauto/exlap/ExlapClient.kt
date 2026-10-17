package com.simoneluconi.betterauto.exlap

import com.simoneluconi.betterauto.EventLog
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
 * the car offers to that user, subscribes to all of it and feeds [data]. It never calls the car's actions.
 * Without credentials it only asks what the server is.
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
    private enum class Step { PROTOCOL, CHALLENGE, LOGIN, DIR, INTERFACE, SUBSCRIBE, BYE }

    private val documents = DocumentBuilderFactory.newInstance().newDocumentBuilder()
    private val pending = HashMap<Int, Step>()
    private var nextRequestId = 1
    private var beacons = 0
    private val members = ArrayList<ExlapValue>()
    private val objects = ArrayList<String>()
    private var refusedObjects = 0
    private var refusedSubscriptions = 0
    private var buffer = ""

    fun start() {
        onStatus(ExlapStatus(if (credentials == null) ExlapState.NO_CREDENTIALS else ExlapState.CONNECTING))
        EventLog.log("Exlap: connecting${if (credentials == null) ", no credentials (probe only)" else ""}")
        send("<ExlapConnectionRequest session_id=\"$sessionId\" />")
    }

    fun stop() = data.clear()

    /** Text from the channel: it may hold several top-level elements, or only part of one. */
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
                val step = element.getAttribute("id").toIntOrNull()?.let { pending.remove(it) } ?: return
                onResponse(step, element, element.getAttribute("status").ifEmpty { null })
            }
            // Subscribed values, pushed up to 10 times a second
            "Dat" -> onData(element)
        }
    }

    private fun onResponse(step: Step, rsp: Element, error: String?) {
        fun first(tag: String) = rsp.getElementsByTagName(tag).item(0) as Element?
        when (step) {
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
            Step.SUBSCRIBE -> {
                if (error != null) refusedSubscriptions++
                if (pending.values.none { it == Step.SUBSCRIBE } && refusedSubscriptions > 0) {
                    EventLog.log("Exlap: $refusedSubscriptions subscriptions refused for this user")
                }
            }
            Step.BYE -> Unit
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
        val urls = (0 until (matches?.length ?: 0)).map { (matches!!.item(it) as Element).getAttribute("url") }.filter { it.isNotEmpty() }
        EventLog.log("Exlap: ${urls.size} objects offered${error?.let { " ($it)" } ?: ""}: ${urls.joinToString()}")
        if (urls.isEmpty()) return finishSchema()
        // Each object's members and units first, then the subscriptions
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
                    members += ExlapValue(url, it.getAttribute("name"), null, it.getAttribute("unit").ifEmpty { null }, it.getAttribute("description").ifEmpty { null })
                }
                member = member.nextSibling
            }
        }
        if (pending.values.none { it == Step.INTERFACE }) finishSchema()
    }

    private fun finishSchema() {
        data.setSchema(members)
        val refused = if (refusedObjects > 0) ", $refusedObjects not readable with this user" else ""
        EventLog.log("Exlap: subscribing to ${objects.size} objects, ${members.size} values$refused")
        onStatus(ExlapStatus(ExlapState.LOGGED_IN, members.size))
        // Subscribe only asks the car to push updates, it changes nothing
        objects.forEach { request(Step.SUBSCRIBE, "<Subscribe url=\"${escape(it)}\" timeStamp=\"true\"/>", log = false) }
    }

    private fun onData(dat: Element) {
        readValues(dat)
        val nested = dat.getElementsByTagName("Dat")
        for (i in 0 until nested.length) readValues(nested.item(i) as Element)
        data.flush()
    }

    private fun readValues(dat: Element) {
        val url = dat.getAttribute("url")
        var child = dat.firstChild
        while (child != null) {
            (child as? Element)?.takeIf { it.getAttribute("name").isNotEmpty() }?.let {
                val state = it.getAttribute("state")
                val value = if (state == "nodata" || state == "error") null else it.getAttribute("val").ifEmpty { null }
                data.update(url, it.getAttribute("name"), value)
            }
            child = child.nextSibling
        }
    }

    private fun request(step: Step, body: String, log: Boolean = true) {
        val id = nextRequestId++
        pending[id] = step
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
