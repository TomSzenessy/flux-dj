package app.flux.dj

import app.flux.dj.audio.Keys
import java.io.InputStream
import java.text.Normalizer
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

/** One track from a rekordbox collection.xml. Times are in seconds. */
class RbTrack(
    val id: String, val name: String, val artist: String, val genre: String, val totalSec: Int,
    val bpm: Float, val key: Int, val fileStem: String, val rating: Int,
    val tempoSecs: DoubleArray, val tempoBpms: DoubleArray, val firstBattito: Int,
    /** hot cues: slot (0..7) → start seconds */
    val hotCues: Map<Int, Double>,
    /** memory cues (start seconds), in order */
    val memoryCues: List<Double>,
)

/** A playlist with its folder path, e.g. ["Sets"] + "Set 1 Dance". */
class RbPlaylist(val folders: List<String>, val name: String, val trackIds: List<String>) {
    val path: String get() = (folders + name).joinToString(" / ")
}

class RbLibrary(val tracks: Map<String, RbTrack>, val playlists: List<RbPlaylist>, val product: String)

object Rekordbox {

    fun parse(input: InputStream): RbLibrary {
        val f = DocumentBuilderFactory.newInstance()
        f.isNamespaceAware = false
        val doc = f.newDocumentBuilder().parse(input)
        val root = doc.documentElement
        val prod = root.child("PRODUCT")?.let { "${it.getAttribute("Name")} ${it.getAttribute("Version")}" } ?: "rekordbox"
        val tracks = LinkedHashMap<String, RbTrack>()
        root.child("COLLECTION")?.children("TRACK")?.forEach { t -> parseTrack(t)?.let { tracks[it.id] = it } }
        val lists = ArrayList<RbPlaylist>()
        root.child("PLAYLISTS")?.child("NODE")?.let { walk(it, emptyList(), lists, isRoot = true) }
        return RbLibrary(tracks, lists, prod)
    }

    private fun parseTrack(t: Element): RbTrack? {
        val id = t.getAttribute("TrackID").ifEmpty { return null }
        val tempos = t.children("TEMPO").mapNotNull { e ->
            val s = e.getAttribute("Inizio").toDoubleOrNull(); val b = e.getAttribute("Bpm").toDoubleOrNull()
            if (s == null || b == null || b <= 0) null else Triple(s, b, e.getAttribute("Battito").toIntOrNull() ?: 1)
        }.sortedBy { it.first }
        val hot = HashMap<Int, Double>(); val mem = ArrayList<Double>()
        t.children("POSITION_MARK").forEach { p ->
            val num = p.getAttribute("Num").toIntOrNull() ?: return@forEach
            val start = p.getAttribute("Start").toDoubleOrNull() ?: return@forEach
            val type = p.getAttribute("Type").toIntOrNull() ?: 0
            if (type != 0) return@forEach                     // loops etc.: positions only for now
            if (num in 0..7) hot[num] = start else if (num < 0) mem.add(start)
        }
        return RbTrack(
            id, t.getAttribute("Name"), t.getAttribute("Artist"), t.getAttribute("Genre"),
            t.getAttribute("TotalTime").toIntOrNull() ?: 0,
            t.getAttribute("AverageBpm").toFloatOrNull() ?: 0f,
            parseKey(t.getAttribute("Tonality")),
            fileStem(t.getAttribute("Location")),
            (t.getAttribute("Rating").toIntOrNull() ?: 0) / 51,   // 0,51,102,153,204,255 → 0..5 stars
            DoubleArray(tempos.size) { tempos[it].first }, DoubleArray(tempos.size) { tempos[it].second },
            tempos.firstOrNull()?.third ?: 1, hot, mem.sorted(),
        )
    }

    private fun walk(node: Element, folders: List<String>, out: MutableList<RbPlaylist>, isRoot: Boolean = false) {
        for (n in node.children("NODE")) {
            val name = n.getAttribute("Name")
            if (n.getAttribute("Type") == "1") {
                out.add(RbPlaylist(folders, name, n.children("TRACK").map { it.getAttribute("Key") }.filter { it.isNotEmpty() }))
            } else walk(n, if (isRoot && name == "ROOT") folders else folders + name, out)
        }
    }

    /** "file://localhost/…/Artist%20-%20Title.mp3" → "Artist - Title" */
    fun fileStem(location: String): String {
        val last = location.substringAfterLast('/')
        val dec = try { java.net.URLDecoder.decode(last.replace("+", "%2B"), "UTF-8") } catch (_: Exception) { last }
        return dec.substringBeforeLast('.')
    }

    private val sharps = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
    private val flats = arrayOf("C", "Db", "D", "Eb", "E", "F", "Gb", "G", "Ab", "A", "Bb", "B")

    /** Camelot ("9A", "11B") or classic ("Am", "F#m", "Db") → FLUX key (pc + 12 if minor), -1 if unknown. */
    fun parseKey(s: String): Int {
        val k = s.trim()
        if (k.isEmpty()) return -1
        Regex("^(\\d{1,2})([ABab])$").find(k)?.let { m ->
            val num = m.groupValues[1].toInt(); val minor = m.groupValues[2].equals("A", true)
            for (key in 0 until 24) if ((key >= 12) == minor && Keys.camelot(key) == "$num${if (minor) "A" else "B"}") return key
            return -1
        }
        val minor = k.endsWith("m") && !k.endsWith("maj", true)
        val note = k.removeSuffix("m")
        val pc = sharps.indexOfFirst { it.equals(note, true) }.takeIf { it >= 0 } ?: flats.indexOfFirst { it.equals(note, true) }
        return if (pc < 0) -1 else pc + if (minor) 12 else 0
    }

    // ------------------------------------------------------------------------------------- matching

    fun norm(s: String): String {
        val n = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
        return n.replace("&", " and ").replace(Regex("[^a-z0-9]+"), " ").trim()
    }

    class Candidate(val id: String, val title: String, val artist: String, val durSec: Int, val fileStem: String)

    /**
     * rekordbox TrackID → candidate id. Order: same file name; then same title + artist with a similar length;
     * then same title with a similar length when that is unambiguous.
     */
    fun match(lib: RbLibrary, cands: List<Candidate>): Map<String, String> {
        val byStem = HashMap<String, MutableList<Candidate>>()
        val byTitle = HashMap<String, MutableList<Candidate>>()
        for (c in cands) {
            if (c.fileStem.isNotEmpty()) byStem.getOrPut(norm(c.fileStem)) { ArrayList() }.add(c)
            byTitle.getOrPut(norm(c.title)) { ArrayList() }.add(c)
        }
        val used = HashSet<String>()
        val out = HashMap<String, String>()
        fun close(c: Candidate, t: RbTrack) = c.durSec <= 0 || t.totalSec <= 0 || kotlin.math.abs(c.durSec - t.totalSec) <= 3
        fun artistOk(c: Candidate, t: RbTrack): Boolean {
            val a = norm(c.artist); val b = norm(t.artist)
            return a.isEmpty() || b.isEmpty() || a == b || a.contains(b) || b.contains(a) ||
                a.split(" ").firstOrNull() == b.split(" ").firstOrNull()
        }
        // pass 1: file name
        for (t in lib.tracks.values) {
            val hit = byStem[norm(t.fileStem)]?.filter { it.id !in used && close(it, t) }?.singleOrNull() ?: continue
            out[t.id] = hit.id; used.add(hit.id)
        }
        // pass 2: title + artist (+ length)
        for (t in lib.tracks.values) {
            if (t.id in out) continue
            val hits = byTitle[norm(t.name)]?.filter { it.id !in used && close(it, t) && artistOk(it, t) } ?: continue
            val hit = hits.singleOrNull() ?: continue
            out[t.id] = hit.id; used.add(hit.id)
        }
        // pass 3: "Artist - Title" file stems vs title, or title alone when unique
        for (t in lib.tracks.values) {
            if (t.id in out) continue
            val stemHits = byStem[norm("${t.artist} - ${t.name}")]?.filter { it.id !in used && close(it, t) }
            val hit = stemHits?.singleOrNull()
                ?: byTitle[norm(t.name)]?.filter { it.id !in used && close(it, t) }?.singleOrNull()
                ?: continue
            out[t.id] = hit.id; used.add(hit.id)
        }
        // pass 4: one title contains the other ("Stereo Love" ↔ "Stereo Love (Remastered Version)"),
        // only with a near-identical length and a single candidate
        for (t in lib.tracks.values) {
            if (t.id in out || t.totalSec <= 0) continue
            val nt = norm(t.name); if (nt.length < 5) continue
            val hits = cands.filter { c ->
                c.id !in used && c.durSec > 0 && kotlin.math.abs(c.durSec - t.totalSec) <= 2 &&
                    norm(c.title).let { ct -> ct.length >= 5 && (ct.contains(nt) || nt.contains(ct)) }
            }
            val hit = hits.singleOrNull() ?: continue
            out[t.id] = hit.id; used.add(hit.id)
        }
        return out
    }
}

private fun Element.child(tag: String): Element? {
    val nl = childNodes
    for (i in 0 until nl.length) { val n = nl.item(i); if (n is Element && n.tagName == tag) return n }
    return null
}

private fun Element.children(tag: String): List<Element> {
    val nl = childNodes; val out = ArrayList<Element>()
    for (i in 0 until nl.length) { val n = nl.item(i); if (n is Element && n.tagName == tag) out.add(n) }
    return out
}
