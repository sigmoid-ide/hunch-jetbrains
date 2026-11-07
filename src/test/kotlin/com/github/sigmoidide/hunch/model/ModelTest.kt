package com.github.sigmoidide.hunch.model

import com.github.sigmoidide.hunch.ui.HunchState
import com.github.sigmoidide.hunch.ui.Scope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Reads src/test/testData/store, written by the real hunch CLI (see src/test/fixtures/make_store.py):
 * baseline, then warmup (kept, with a note), a milestone covering both, then from warmup a quick
 * crash "typo" and "higher lr".
 */
class ModelTest {
    private val store = HunchStore(Paths.get("src/test/testData/store"))
    private val runs = store.runs()
    private fun run(why: String) = runs.single { it.why == why }

    @Test
    fun `reads every run with its why, status, parent and verdict`() {
        assertEquals(listOf("baseline", "warmup", "typo", "higher lr"), runs.map { it.why })
        assertEquals(listOf("finished", "finished", "crashed", "finished"), runs.map { it.status })
        assertEquals(run("baseline").id, run("warmup").parent)
        assertEquals(run("warmup").id, run("higher lr").parent)
        assertEquals("keep", run("warmup").verdict)
        assertEquals("warmup helps, приятно", run("warmup").conclusion)
        assertEquals(3L, run("typo").exitCode)
        assertTrue(run("typo").trivial)
        assertFalse(run("baseline").trivial) // quick, but it finished
    }

    @Test
    fun `reads metrics like hunch log does`() {
        val warmup = run("warmup")
        assertEquals("val_loss", warmup.mainMetric(null))
        assertEquals("loss", warmup.mainMetric("loss"))
        assertEquals(listOf("loss", "val_loss"), warmup.metricKeys)
        assertEquals(5, warmup.spark("val_loss").size)
        assertEquals("val_loss 0.3333", Format.result(warmup, null))
        assertEquals("crashed, exit 3; val_loss 0.3333", Format.result(run("typo"), null))
    }

    @Test
    fun `lays out the tree like hunch log, hiding quick crashes`() {
        val all = RunGraph.rows(runs)
        assertEquals(listOf("higher lr", "typo", "warmup", "baseline"), all.map { it.run.why })
        assertEquals(listOf(0, 1, 0, 0), all.map { it.lane })
        assertEquals(listOf(0, 1), all[2].joining) // both children end at warmup
        assertFalse(all[3].hasParent)

        val shown = RunGraph.rows(RunGraph.visible(runs, showAll = false))
        assertEquals(listOf("higher lr", "warmup", "baseline"), shown.map { it.run.why })
        assertTrue(shown.all { it.lane == 0 })
    }

    @Test
    fun `reads milestones and splits runs by them`() {
        val milestones = store.milestones()
        val m = milestones.single()
        assertEquals("warmup beats baseline", m.title)
        assertEquals("the spike is gone", m.description)
        assertEquals(listOf(run("baseline").id, run("warmup").id), m.runs)
        assertEquals(run("warmup").id, m.best)
        assertEquals("val_loss", m.metric)
        assertTrue(m.summary.startsWith("2 runs (1 kept)"))

        val state = HunchState(store, runs, milestones, null, loaded = true)
        val scopes = Scope.all(state)
        assertEquals(listOf("latest", m.id, "all"), scopes.map { it.key })
        assertEquals("M1", (scopes[1] as Scope.Of).number)
        assertEquals(listOf("typo", "higher lr"), Scope.Latest.runs(state).map { it.why })
        assertEquals(listOf("baseline", "warmup"), scopes[1].runs(state).map { it.why })
        assertEquals(emptyList<Scope>(), Scope.all(state.copy(milestones = emptyList())))
    }

    @Test
    fun `reads code snapshots and finds what changed`() {
        val changes = Snapshot.changes(store, run("baseline"), run("warmup"))!!
        assertEquals(listOf("train.py"), changes.map { it.path })
        assertTrue("step + 1" in changes[0].before!!)
        assertTrue("step + 2" in changes[0].after!!)
        assertEquals(emptyList<Snapshot.Change>(), Snapshot.changes(store, run("warmup"), run("higher lr")))
    }

    @Test
    fun `annotations are appended the way hunch writes them`() {
        val copy = Files.createTempDirectory("hunch-store")
        copyTree(store.root, copy)
        val copied = HunchStore(copy)
        val baseline = copied.runs().single { it.why == "baseline" }
        copied.annotate(baseline, "note", "line one still one")
        copied.annotate(baseline, "verdict", "drop")
        copied.annotate(baseline, "why", "a better why")

        val reread = copied.runs().single { it.id == baseline.id }
        assertEquals("line one still one", reread.conclusion)
        assertEquals("drop", reread.verdict)
        assertEquals("a better why", reread.why)
        assertEquals("baseline", reread.meta["why"]) // the record itself never changes
    }

    @Test
    fun `the main metric setting round-trips`() {
        val copy = Files.createTempDirectory("hunch-store")
        copyTree(store.root, copy)
        val copied = HunchStore(copy)
        copied.setMainMetric("loss")
        assertEquals("loss", copied.mainMetric())
        copied.setMainMetric(null)
        assertNull(copied.mainMetric())
    }

    @Test
    fun `finds the store from a subfolder`() {
        val found = HunchStore.find(store.root.resolve(".hunch").resolve("runs"))
        assertEquals(store.root.toAbsolutePath(), found.root.toAbsolutePath())
        assertNotNull(store.signature())
    }

    @Test
    fun `a run Ferry copies from another machine stays running while this computer sleeps`() {
        val copy = Files.createTempDirectory("hunch-store")
        copyTree(store.root, copy)
        val copied = HunchStore(copy)
        val folder = copied.runs().single { it.why == "higher lr" }.dir
        Files.delete(folder.resolve("end.json"))
        val heartbeat = folder.resolve("heartbeat")
        if (!Files.exists(heartbeat)) Files.createFile(heartbeat)
        Files.setLastModifiedTime(heartbeat, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() - 3 * 3_600_000))
        fun reread() = copied.runs().single { it.why == "higher lr" }
        assertEquals("lost", reread().status)

        Files.writeString(folder.resolve("remote.json"), """{"machine": "gpu-box", "dir": "/home/me/.ferry/x", "synced_at": "2026-11-07T09:00:00+05:00"}""")
        val remote = reread()
        assertEquals("running", remote.status)
        assertEquals("gpu-box", remote.remote?.machine)
        assertEquals(java.time.OffsetDateTime.parse("2026-11-07T09:00:00+05:00"), remote.remote?.syncedAt)
        assertTrue(Format.result(remote, null).startsWith("running ")) // then the live metric, as in hunch log
        assertTrue(" on gpu-box" in Format.result(remote, null))
    }

    @Test
    fun `search finds runs by hypothesis, conclusion, verdict, status, command, config and id, every word`() {
        fun found(query: String) = runs.filter { Search.matches(it, Search.words(query), store.root) }.map { it.why }
        assertEquals(listOf("higher", "lr"), Search.words("  Higher   LR "))
        assertEquals(listOf("higher lr"), found("HIGHER"))
        assertEquals(listOf("warmup"), found("приятно")) // its conclusion
        assertEquals(listOf("warmup"), found("keep"))
        assertEquals(listOf("typo"), found("crashed"))
        assertEquals(listOf("typo"), found("train.py fail")) // its command
        assertEquals(listOf("higher lr"), found("lr = 0.3")) // its config
        assertEquals(listOf("baseline"), found(run("baseline").id.takeLast(4)))
        assertEquals(emptyList<String?>(), found("warmup baseline"))
    }

    private fun copyTree(from: Path, to: Path) {
        Files.walk(from).use { paths ->
            paths.forEach { source ->
                val target = to.resolve(from.relativize(source).toString())
                if (Files.isDirectory(source)) Files.createDirectories(target) else Files.copy(source, target)
            }
        }
    }
}

class JsonTest {
    @Test
    fun `reads python's json, including NaN and infinity`() {
        val value = Json.parse("""{"a": [1, 2.5, -3e2, NaN, Infinity, -Infinity], "s": "café 😀", "n": null, "t": true}""").asMap()
        val numbers = value["a"].asList()
        assertEquals(1L, numbers[0])
        assertEquals(2.5, numbers[1])
        assertEquals(-300.0, numbers[2])
        assertTrue((numbers[3] as Double).isNaN())
        assertEquals(Double.POSITIVE_INFINITY, numbers[4])
        assertEquals(Double.NEGATIVE_INFINITY, numbers[5])
        assertEquals("café 😀", value["s"])
        assertNull(value["n"])
        assertEquals(true, value["t"])
    }

    @Test
    fun `writes json that reads back, escaping line separators`() {
        val original = linkedMapOf("text" to "a\"b\\c\nd e", "list" to listOf(1L, 2.5, null, false), "nan" to Double.NaN)
        val written = Json.write(original)
        assertFalse(' ' in written)
        val back = Json.parse(written).asMap()
        assertEquals(original["text"], back["text"])
        assertEquals(original["list"], back["list"])
        assertTrue((back["nan"] as Double).isNaN())
    }
}

class FormatTest {
    @Test
    fun `numbers look like python's 4 significant digits`() {
        assertEquals("2.3", Format.value(2.3))
        assertEquals("0.3333", Format.value(1.0 / 3))
        assertEquals("1", Format.value(1.0))
        assertEquals("1235", Format.value(1234.5))
        assertEquals("nan", Format.value(Double.NaN))
        assertEquals("7", Format.value(7L))
    }

    @Test
    fun `short ids grow only when the last four characters collide`() {
        val ids = listOf("20261105-1723-aa1234", "20261105-1723-bb1234", "20261105-1724-cc5678")
        assertEquals(listOf("aa1234", "bb1234", "5678"), Format.shortIds(ids).values.toList())
    }

    @Test
    fun `commands read the way you typed them, without full paths`() {
        val root = Files.createTempDirectory("project")
        val python = root.resolve("venv").resolve("python").toString()
        val meta = mapOf("started_at" to "2026-11-05T10:00:00+00:00", "command" to listOf(python, root.resolve("train.py").toString(), "--lr", "3e-4"))
        val run = Run(root.resolve("run"), meta, null, emptyList(), null, emptyList())
        assertEquals("python train.py --lr 3e-4", Format.command(run, root))
    }

    @Test
    fun durations() {
        assertEquals("41s", Format.duration(41.2))
        assertEquals("12m 3s", Format.duration(723.0))
        assertEquals("2h 5m", Format.duration(7500.0))
    }
}
