package com.github.sigmoidide.hunch.model

import com.github.sigmoidide.hunch.ui.HunchState
import com.github.sigmoidide.hunch.ui.Scope
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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
    fun `a run on a rented machine knows where it ran and what it cost`() {
        val copy = Files.createTempDirectory("hunch-store")
        copyTree(store.root, copy)
        val copied = HunchStore(copy)
        val folder = copied.runs().single { it.why == "higher lr" }.dir
        fun reread() = copied.runs().single { it.why == "higher lr" }
        assertNull(reread().cost)
        Files.writeString(folder.resolve("cost.json"), """{"estimate": 0.0""")
        assertNull(reread().cost) // a broken cost.json is no cost

        val signature = copied.signature()
        Files.writeString(folder.resolve("remote.json"), """{"machine": "vast-59870289", "dir": "/root/.ferry/x", "synced_at": "2026-11-09T14:03:00+05:00", "complete": true,
            "provider": "vast", "tier": "verified", "country": "PL", "price": 0.0674}""")
        Files.writeString(folder.resolve("cost.json"), """{"provider": "vast", "machine": "vast-59870289", "gpu": "NVIDIA GeForce RTX 3060", "rate": 0.06740740740740742,
            "disk_rate": 0.007407, "running_seconds": 48, "stopped_seconds": 0, "estimate": 0.0009, "currency": "USD", "billed": null}""")
        assertNotEquals(signature, copied.signature())
        val rented = reread()
        assertEquals("finished", rented.status)
        assertEquals(Cost(0.0009, billed = false), rented.cost)
        assertEquals("vast-59870289 (Vast.ai, PL)", Format.machine(rented.remote!!))
        assertEquals("under a cent", Format.cost(rented.cost!!))

        Files.writeString(folder.resolve("cost.json"), """{"estimate": 0.0213, "billed": 0.0241}""")
        assertEquals(Cost(0.0241, billed = true), reread().cost)
        assertEquals("\$0.02", Format.cost(reread().cost!!))
    }

    @Test
    fun `search finds runs by hypothesis, conclusion, verdict, status, command, config and id, every word`() {
        fun found(query: String) = Search.filter(runs, query, store.root).map { it.why }
        assertEquals(listOf("higher lr"), found("  HIGHER   "))
        assertEquals(listOf("warmup"), found("приятно")) // its conclusion
        assertEquals(listOf("warmup"), found("keep"))
        assertEquals(listOf("typo"), found("crashed"))
        assertEquals(listOf("typo"), found("train.py fail")) // its command
        assertEquals(listOf("higher lr"), found("lr = 0.3")) // its config, as the details show it
        assertEquals(listOf("baseline"), found(run("baseline").id.takeLast(4)))
        assertEquals(emptyList<String?>(), found("warmup baseline"))
    }

    @Test
    fun `filters narrow a search by status, verdict, time, lineage, metrics and config`() {
        val started = run("baseline").started.toInstant()
        fun found(query: String, now: java.time.Instant = started.plusSeconds(3600)) = Search.filter(runs, query, store.root, now).map { it.why }
        val all = listOf("baseline", "warmup", "typo", "higher lr")
        assertEquals(listOf("warmup"), found("is:kept"))
        assertEquals(listOf("baseline", "typo", "higher lr"), found("-is:kept"))
        assertEquals(listOf("baseline", "higher lr"), found("is:unreviewed")) // a quick crash is nothing to review
        assertEquals(listOf("typo"), found("is:crashed,stopped"))
        assertEquals(listOf("baseline", "higher lr"), found("IS:Finished -is:kept"))
        assertEquals(listOf("warmup", "typo"), found("val_loss<0.35"))
        assertEquals(listOf("baseline", "higher lr"), found("val_loss>=0.4"))
        assertEquals(listOf("baseline"), found("val_loss=0.4")) // as the table shows it: 0.4000000000000001 is 0.4
        assertEquals(listOf("higher lr"), found("lr=3e-1")) // config, in any notation
        assertEquals(listOf("baseline", "warmup"), found("LR<0.2 is:finished"))
        assertEquals(listOf("warmup", "typo", "higher lr"), found("from:${run("warmup").id.takeLast(4)}"))
        assertEquals(all, found("since:1d"))
        assertEquals(emptyList<String?>(), found("since:1d", started.plusSeconds(3 * 24 * 3600)))
        assertEquals(all, found("since:2026-11-04"))
        assertEquals(emptyList<String?>(), found("since:2026-11-07"))
        assertEquals(emptyList<String?>(), found("since:soon")) // not a filter, so a word no run has
        assertEquals(emptyList<String?>(), found("nosuch<1"))

        assertEquals("warmup", Search.withFilter("warmup  is:kept", "is:kept", false))
        assertEquals("warmup since:7d", Search.withFilter("warmup", "since:7d", true))
        assertTrue(Search.hasFilter("warmup since:7d", "since:7d") && !Search.hasFilter("warmup", "since:7d"))
    }

    @Test
    fun `filters for where a run ran and the branch it started on`() {
        val copy = Files.createTempDirectory("hunch-store")
        copyTree(store.root, copy)
        val copied = HunchStore(copy)
        fun dirOf(why: String) = copied.runs().single { it.why == why }.dir
        Files.writeString(dirOf("higher lr").resolve("remote.json"), """{"machine": "gpu-box"}""")
        val runJson = dirOf("baseline").resolve("run.json")
        val meta = LinkedHashMap(Json.parse(Files.readString(runJson)).asMap())
        meta["line"] = "feature/warmup"
        Files.writeString(runJson, Json.write(meta))
        fun found(query: String) = Search.filter(copied.runs(), query, copied.root).map { it.why }
        assertEquals(listOf("higher lr"), found("is:remote"))
        assertEquals(listOf("higher lr"), found("on:GPU"))
        assertEquals(listOf("baseline"), found("branch:feature"))
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

    @Test
    fun `costs and machines read the way Ferry says them`() {
        assertEquals(
            listOf("about \$0.02", "about \$1.24", "\$0.02", "under a cent"),
            listOf(Cost(0.0213, false), Cost(1.237, false), Cost(0.0213, true), Cost(0.004, true)).map(Format::cost),
        )
        assertEquals("gpu-box", Format.machine(Remote("gpu-box", null)))
        assertEquals("box (other)", Format.machine(Remote("box", null, provider = "other", country = "")))
    }
}

class MachinesTest {
    @Test
    fun `reads Ferry's machines, and tells a missing Ferry from an old one`() {
        val listed = MachinesAnswer.of(true, """[{"name": "gpu", "host": "10.0.0.5", "user": "me", "port": 22, "gpu": "RTX 3060"}, {"name": "cpu", "host": "box"}, {"host": "x"}]""", "")
        assertEquals(MachinesAnswer.Found(listOf(Machine("gpu", "10.0.0.5", "me", "RTX 3060"), Machine("cpu", "box", null, null))), listed)
        assertEquals(MachinesAnswer.Found(emptyList()), MachinesAnswer.of(true, "[]\n", ""))
        assertEquals(MachinesAnswer.NoFerry, MachinesAnswer.of(false, "", "C:\\py\\python.exe: No module named ferry\n"))
        assertEquals(MachinesAnswer.OldFerry, MachinesAnswer.of(false, "", "usage: ferry [-h]\nferry: error: unrecognized arguments: --json\n"))
        assertEquals(MachinesAnswer.Failed("the machines file is broken"), MachinesAnswer.of(false, "", "Traceback...\nferry: the machines file is broken\n"))
        assertTrue(MachinesAnswer.of(true, "not json", "") is MachinesAnswer.Failed)
    }
}

class OffersTest {
    @Test
    fun `reads the GPUs Ferry can rent, and tells an old Ferry`() {
        val listed = OffersAnswer.of(true, """{"understood": "RTX 3060, in Canada", "offers": [{"id": 59812282, "machine": 1, "gpu": "RTX 3060", "count": 1, "memory": 12.0, "price": 0.0644,
            "reliability": 0.996, "tier": "verified", "place": "Quebec, CA", "download": 1465.2, "up_cost": 0.0065},
            {"id": 7, "gpu": "RTX 4090", "count": 2, "memory": 24, "price": 0.8}, {"gpu": "no id"}]}""", "")
        val offers = (listed as OffersAnswer.Found).offers
        assertEquals("RTX 3060, in Canada", listed.understood)
        assertEquals(listOf(59812282L, 7L), offers.map { it.id })
        assertEquals("Quebec, CA", offers[0].place)
        assertEquals(0.0065, offers[0].upCost, 1e-9)
        assertEquals("2x RTX 4090", offers[1].label)
        assertEquals(OffersAnswer.OldFerry, OffersAnswer.of(false, "", "usage: ferry\nferry: error: argument command: invalid choice: 'gpus'\n"))
        assertEquals(OffersAnswer.Failed("could not reach Vast.ai: timed out"), OffersAnswer.of(false, "", "ferry: could not reach Vast.ai: timed out\n"))
        assertTrue(OffersAnswer.of(true, "not json", "") is OffersAnswer.Failed)
    }

    @Test
    fun `reads the time a rented machine may run as Ferry does, and what it can cost`() {
        assertEquals(7200, Rental.seconds("2h"))
        assertEquals(5400, Rental.seconds(" 1h30m "))
        assertEquals(5400, Rental.seconds("90m"))
        assertEquals(5400, Rental.seconds("1.5H"))
        for (bad in listOf("", "2", "5m", "2 hours", "h")) assertNull(bad, Rental.seconds(bad))
        val offer = Offer(1, "RTX 3060", 1, 12.0, 0.0644, 1.0, "verified", "?", 0.0, 0.0)
        assertEquals("\$0.13", Rental.dollars(Rental.most(offer, 7200)))
        assertEquals("under a cent", Rental.dollars(0.004))
        assertEquals(listOf(1800, 3600, 7200, 14400, 28800, 43200, 86400), Rental.TIMES.map { Rental.seconds(it.first) })
        assertEquals("at most \$0.13, data free", Rental.cost(offer, 7200))
        assertEquals("at most \$0.13, plus \$0.0065 per GB you send it", Rental.cost(offer.copy(upCost = 0.0065), 7200))
    }
}
