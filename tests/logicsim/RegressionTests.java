package logicsim;

import gates.*;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.image.BufferedImage;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import javax.swing.SwingUtilities;

/** Dependency-free, headless integration and regression tests. Run with ant test. */
public final class RegressionTests {
    private interface Test { void run() throws Exception; }
    private static final Simulation SIM = Simulation.getInstance();
    private static final Wire INPUT = new Wire(null, null);
    private static final Path OUTPUT = Paths.get("results");
    private static int passed, failed;

    public static void main(String[] args) throws Exception {
        Files.createDirectories(OUTPUT);
        new I18N();
        registerGates();
        test("metadata roundtrip", RegressionTests::metadata);
        test("legacy metadata remains readable", RegressionTests::legacyMetadata);
        test("pin labels, empty overrides and inversion roundtrip", RegressionTests::pinProperties);
        test("failed open preserves document, filename and undo", RegressionTests::failedOpen);
        test("partial load preserves current document", RegressionTests::partialOpen);
        test("successful open replaces document", RegressionTests::successfulOpen);
        test("external XML entities are rejected", RegressionTests::externalEntity);
        test("SR latch holds, resets and handles forbidden inputs", RegressionTests::srLatch);
        test("new wires inherit NOT and flipflop levels without reset", RegressionTests::newWireLevel);
        test("new branch does not retrigger existing counters", RegressionTests::branchClock);
        test("logic gate truth tables", RegressionTests::truthTables);
        test("XOR does not clock a counter spuriously", RegressionTests::xorCounter);
        test("stopping a feedback loop remains responsive", () -> { for (int i = 0; i < 25; i++) feedback(); });
        test("NOR feedback is bounded and reports instability", RegressionTests::boundedFeedback);
        test("stopped NOR feedback recovers through switches and one play click", RegressionTests::recoverFeedback);
        test("old instability notification cannot deselect a restarted simulation", RegressionTests::staleInstabilityNotice);
        test("feedback module templates never schedule events", RegressionTests::moduleTemplate);
        test("clock pauses and resumes without painting", RegressionTests::clock);
        test("null events do not block propagation", RegressionTests::nullTarget);
        test("large fanout does not lose events", RegressionTests::fanout);
        test("OnDelay cancels obsolete HIGH", () -> cancelledDelay(new OnDelay(), true));
        test("OffDelay cancels obsolete LOW", () -> cancelledDelay(new OffDelay(), false));
        test("OnDelay passes sustained HIGH", () -> sustainedDelay(new OnDelay(), true));
        test("OffDelay delays LOW", () -> sustainedDelay(new OffDelay(), false));
        test("stopping invalidates pending timer across restart", RegressionTests::stoppedTimer);
        test("reset cancels delayed output", RegressionTests::resetTimer);
        test("monoflop pulse and reset", RegressionTests::monoflop);
        test("memory updates output after address changes", RegressionTests::memoryAddress);
        test("memory write/read roundtrip", RegressionTests::memoryWrite);
        test("program counter updates outputs, wraps and persists", RegressionTests::programCounter);
        test("jump takes priority over count enable", RegressionTests::counterJump);
        test("undo restores unfinished wire and its points", RegressionTests::unfinishedUndo);
        test("unfinished wire can be continued after undo", RegressionTests::continueWire);
        test("undo restores a branched circuit", RegressionTests::branchedUndo);
        test("deleted trunk leaves serializable connected branches", RegressionTests::deleteTrunk);
        test("branch loads before parent wire", RegressionTests::forwardBranch);
        test("unfinished wire save/load", RegressionTests::unfinishedSave);
        test("mirror marks changed and can be undone", RegressionTests::mirror);
        test("text pixels match hit area and retain legacy position", RegressionTests::textHitArea);
        test("autowire connects only at drop, never while crossing pins", RegressionTests::autoWireDrop);
        test("normal mode branches onto wires and existing junctions", RegressionTests::normalBranches);
        test("dialog buttons use application language", RegressionTests::dialogLanguage);
        test("test gate is hidden but legacy files remain readable", RegressionTests::hiddenTestGate);
        test("example module namespace and legacy aliases", RegressionTests::exampleModules);
        test("empty text can be painted and saved", RegressionTests::emptyText);
        try (DirectoryStream<Path> files = Files.newDirectoryStream(Paths.get("circuits"), "*.lsc")) {
            for (Path file : files) test("example roundtrip: " + file.getFileName(), () -> example(file));
        }
        test("half adder truth table", () -> adder("HA", 2));
        test("full adder truth table", () -> adder("FA", 3));
        SIM.stop();
        System.out.println("RESULT: " + passed + " passed, " + failed + " failed");
        if (failed != 0) System.exit(1);
    }

    private static void registerGates() throws Exception {
        if (Boolean.getBoolean("release.smoke")) {
            App.Running_From_Jar = true;
            java.lang.reflect.Method initialize = App.class.getDeclaredMethod("initializeGateCategories");
            initialize.setAccessible(true); initialize.invoke(null); idle();
            check(App.getGate("not") != null, "Packaged gates not discovered");
            for (Category category : App.cats) check(!"test".equals(category.title), "Test category visible in release");
            return;
        }
        Category category = new Category("test");
        App.cats.add(category);
        category.addGate(new MODIN()); category.addGate(new MODOUT());
        Path sources = Paths.get(System.getProperty("project.dir"), "src", "gates");
        try (DirectoryStream<Path> files = Files.newDirectoryStream(sources, "*.java")) {
            for (Path file : files) {
                String name = file.getFileName().toString().replace(".java", "");
                Gate gate = (Gate) Class.forName("gates." + name).getDeclaredConstructor().newInstance();
                gate.loadLanguage(); category.addGate(gate);
            }
        }
        App.reloadModules(); idle();
    }

    private static void test(String name, Test test) {
        try {
            SIM.stop(); idle();
            test.run(); idle();
            passed++; System.out.println("PASS: " + name);
        } catch (Throwable error) {
            failed++; System.err.println("FAIL: " + name); error.printStackTrace();
        } finally { SIM.stop(); }
    }
    private static void equal(Object expected, Object actual) {
        if (!Objects.equals(expected, actual)) throw new AssertionError("Expected " + expected + ", got " + actual);
    }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
    private static void idle() throws Exception { check(SIM.awaitIdle(2000), "Event processing did not settle"); }
    private static void input(Gate gate, int number, boolean value) {
        gate.getPin(number).changedLevel(new LSLevelEvent(INPUT, value));
    }
    private static int word(Gate gate, int start, int bits) {
        int value = 0;
        for (int i = 0; i < bits; i++) if (gate.getPin(start + i).getLevel()) value |= 1 << i;
        return value;
    }
    private static void tick(Gate gate, int number) { input(gate, number, false); input(gate, number, true); }
    private static LogicSimFile roundtrip(LogicSimFile file, String name) {
        file.fileName = OUTPUT.resolve(name + ".lsc").toString();
        XMLCreator.createXML(file);
        LogicSimFile loaded = XMLLoader.loadXmlFile(file.fileName);
        equal(null, loaded.getErrorString());
        return loaded;
    }
    private static Path xml(String name, String text) throws Exception {
        Path file = OUTPUT.resolve(name + ".lsc");
        Files.write(file, text.getBytes(StandardCharsets.UTF_8)); return file;
    }
    private static void rejects(Test test) throws Exception {
        try { test.run(); } catch (RuntimeException expected) { return; }
        throw new AssertionError("Invalid input was accepted");
    }
    private static void timers() throws Exception { SIM.schedule(() -> {}, 120).get(2, TimeUnit.SECONDS); idle(); }
    private static LogicSimFile circuit(Gate... gates) {
        LogicSimFile file = new LogicSimFile("original.lsc");
        for (Gate gate : gates) file.circuit.addGate(gate);
        return file;
    }
    private static Gate at(Gate gate, int x, int y) { gate.moveTo(x, y); return gate; }
    private static Wire connect(Circuit circuit, CircuitPart from, CircuitPart to) {
        Wire wire = new Wire(from, to); check(circuit.addWire(wire), "Wire rejected");
        from.connect(wire); if (to != null) to.connect(wire); return wire;
    }

    private static void metadata() {
        LogicSimFile file = circuit(); file.setLabel("Schaltung & <Test>"); file.setDescription("Grüße\nsecond line");
        LogicSimFile loaded = roundtrip(file, "metadata");
        equal(file.getLabel(), loaded.getLabel()); equal(file.getDescription(), loaded.getDescription());
    }
    private static void legacyMetadata() throws Exception {
        Path path = xml("legacy", "<logicsim version='3'><info><label>Old</label><description>Text</description></info></logicsim>");
        LogicSimFile file = XMLLoader.loadXmlFile(path.toString()); equal("Old", file.getLabel()); equal("Text", file.getDescription());
    }
    private static void pinProperties() {
        Gate gate = at(new AND(), 100, 100); gate.getPin(1).setProperty(CircuitPart.TEXT, "custom input");
        gate.getPin(0).setProperty(CircuitPart.TEXT, "custom output"); gate.getPin(0).setLevelType(Pin.INVERTED);
        Gate flip = at(new DFlipFlop(), 300, 100); flip.getPin(0).setProperty(CircuitPart.TEXT, "");
        LogicSimFile loaded = roundtrip(circuit(gate, flip), "pins");
        equal("custom input", loaded.getGates().get(0).getPin(1).text);
        equal("custom output", loaded.getGates().get(0).getPin(0).text);
        equal(Pin.INVERTED, loaded.getGates().get(0).getPin(0).levelType);
        equal("", loaded.getGates().get(1).getPin(0).text);
    }
    private static void failedOpen() throws Exception {
        LSPanel panel = new LSPanel(); LogicSimFile original = circuit(at(new AND(), 100, 100)); original.changed = true;
        panel.circuit = original.circuit; panel.recordUndoState(); panel.circuit.getGates().get(0).moveBy(10, 0);
        Path invalid = xml("invalid", "<logicsim version='wrong'/>");
        rejects(() -> panel.loadFile(invalid.toString()));
        equal(original.circuit, panel.circuit); equal("original.lsc", original.fileName); equal(true, original.changed);
        equal(110, panel.circuit.getGates().get(0).getX()); panel.undo(); equal(100, panel.circuit.getGates().get(0).getX());
    }
    private static void partialOpen() throws Exception {
        LSPanel panel = new LSPanel(); panel.circuit.addGate(new AND()); Circuit previous = panel.circuit;
        Path invalid = xml("partial", "<logicsim version='3'><wires><wire><from type='wire' id='missing'/><to type='point' x='10' y='10'/></wire></wires></logicsim>");
        rejects(() -> panel.loadFile(invalid.toString())); equal(previous, panel.circuit); equal(1, previous.getGates().size());
    }
    private static void successfulOpen() {
        LSPanel panel = new LSPanel(); panel.circuit.addGate(new AND());
        LogicSimFile saved = circuit(at(new OR(), 300, 200)); roundtrip(saved, "open");
        LogicSimFile loaded = panel.loadFile(saved.fileName); equal(loaded.circuit, panel.circuit);
        equal("or", panel.circuit.getGates().get(0).type); equal(300, panel.circuit.getGates().get(0).getX());
    }
    private static void externalEntity() throws Exception {
        Path marker = OUTPUT.resolve("public-marker.txt").toAbsolutePath(); Files.write(marker, Arrays.asList("PUBLIC_TEST_MARKER"));
        Path path = xml("entity", "<!DOCTYPE logicsim [<!ENTITY local SYSTEM '" + marker.toUri()
            + "'>]><logicsim version='3'><info><item key='label'>&local;</item></info></logicsim>");
        rejects(() -> XMLLoader.loadXmlFile(path.toString()));
    }
    private static void srLatch() {
        SRLatch latch = new SRLatch(); latch.reset();
        int[] inputs = {0, 1, 0, 2, 0, 1, 3, 2};
        int[] outputs = {2, 1, 1, 2, 2, 1, 0, 2};
        for (int i = 0; i < inputs.length; i++) {
            latch.getPin(0).setLevel((inputs[i] & 1) != 0);
            latch.getPin(1).setLevel((inputs[i] & 2) != 0);
            latch.changedLevel(new LSLevelEvent(latch.getPin(0), false));
            equal(outputs[i], word(latch, 2, 2));
        }
        latch.getPin(0).setLevel(false); latch.getPin(1).setLevel(false);
        latch.reset(); equal(2, word(latch, 2, 2));
    }

    private static void newWireLevel() throws Exception {
        LSPanel panel = new LSPanel(); NOT not = new NOT(); LED led = new LED();
        panel.circuit.addGate(not); panel.circuit.addGate(led);
        Wire wire = connect(panel.circuit, not.getPin(1), null);
        wire.setTo(led.getPin(0)); led.getPin(0).connect(wire); wire.finish();
        idle(); equal(true, wire.getLevel()); equal(true, led.getPin(0).getLevel());
        panel.startSimulation(); idle(); equal(true, led.getPin(0).getLevel()); SIM.stop();
        for (Gate flip : new Gate[]{new DFlipFlop(), new SRLatch()}) {
            LED indicator = new LED();
            Wire connection = connect(panel.circuit, flip.getPin(3), indicator.getPin(0));
            connection.initializeLevel(); idle(); equal(true, indicator.getPin(0).getLevel());
        }
    }

    private static void branchClock() throws Exception {
        NOT source = new NOT(); Counter counter = new Counter(); LED led = new LED();
        Circuit circuit = new Circuit();
        Wire trunk = connect(circuit, source.getPin(1), counter.getPin(0));
        trunk.addPoint(200, 100); trunk.initializeLevel(); idle(); equal(1, word(counter, 1, 8));
        Wire branch = connect(circuit, led.getPin(0), trunk.getPoints().get(0));
        branch.initializeLevel(); idle(); equal(true, led.getPin(0).getLevel());
        equal(1, word(counter, 1, 8));
        // A LOW source must also clear a previously HIGH destination.
        LED high = new LED(); high.getPin(0).setLevel(true);
        input(source, 0, true); idle();
        Wire low = connect(circuit, source.getPin(1), high.getPin(0)); low.initializeLevel();
        idle(); equal(false, high.getPin(0).getLevel());
    }

    private static void truthTables() {
        Gate[] gates = {new AND(), new OR(), new NAND(), new NOR(), new XOR(), new EQU()};
        for (int bits = 0; bits < 4; bits++) {
            boolean a = (bits & 1) != 0, b = (bits & 2) != 0;
            boolean[] expected = {a && b, a || b, !(a && b), !(a || b), a ^ b, a == b};
            for (int i = 0; i < gates.length; i++) {
                gates[i].getPin(1).setLevel(a); gates[i].getPin(2).setLevel(b); gates[i].simulate();
                equal(expected[i], gates[i].getPin(0).getLevel());
            }
        }
        NOT not = new NOT(); input(not, 0, true); equal(false, not.getPin(1).getLevel());
        input(not, 0, false); equal(true, not.getPin(1).getLevel());
    }
    private static void xorCounter() throws Exception {
        XOR xor = new XOR(); Counter counter = new Counter(); Circuit circuit = new Circuit();
        connect(circuit, xor.getPin(0), counter.getPin(0));
        xor.getPin(1).setLevel(true); xor.getPin(2).setLevel(true); xor.simulate(); idle(); equal(0, word(counter, 1, 8));
        input(xor, 2, false); idle(); equal(1, word(counter, 1, 8));
    }
    private static void feedback() throws Exception {
        SIM.start(); NOT gate = new NOT(); CountDownLatch activity = new CountDownLatch(1);
        Wire feedback = new Wire(gate.getPin(1), gate.getPin(0)) {
            @Override public void changedLevel(LSLevelEvent e) { super.changedLevel(e); activity.countDown(); }
        };
        gate.getPin(1).connect(feedback); gate.getPin(0).connect(feedback); gate.reset();
        check(activity.await(2, TimeUnit.SECONDS), "Feedback did not start");
        ExecutorService stopper = Executors.newSingleThreadExecutor(task -> { Thread t = new Thread(task); t.setDaemon(true); return t; });
        try { stopper.submit(() -> SIM.stop()).get(2, TimeUnit.SECONDS); } finally { stopper.shutdownNow(); }
		check(SIM.awaitIdle(2000), "Feedback did not stop"); CaptureWire capture = new CaptureWire();
        SIM.putEvent(new LSLevelEvent(gate, true, false, capture)); idle(); equal(1, capture.count.get());
        feedback.disconnect(null); gate.dispose();
    }
    private static void boundedFeedback() throws Exception {
        NOR a = new NOR(), b = new NOR(); Circuit circuit = new Circuit();
        circuit.addGate(a); circuit.addGate(b);
        connect(circuit, a.getPin(0), b.getPin(1)); connect(circuit, b.getPin(0), a.getPin(1));
        AtomicInteger notices = new AtomicInteger(); SIM.setInstabilityListener(notices::incrementAndGet);
        try {
            SIM.start(); circuit.reset(); idle();
            equal(false, SIM.isRunning()); equal(true, SIM.isOscillating()); equal(1, notices.get());
            CaptureWire capture = new CaptureWire();
            SIM.putEvent(new LSLevelEvent(a, true, false, capture)); idle(); equal(1, capture.count.get());
        } finally { SIM.setInstabilityListener(() -> {}); circuit.clear(); }
    }
    private static void recoverFeedback() throws Exception {
        // Recover into either stable state, using the same mouse path as the UI.
        for (boolean set : new boolean[] { false, true }) {
            SIM.stop();
            LSPanel panel = new LSPanel();
            NOR a = (NOR) at(new NOR(), 200, 100), b = (NOR) at(new NOR(), 350, 100);
            Switch r = (Switch) at(new Switch(), 20, 100), s = (Switch) at(new Switch(), 20, 200);
            Switch momentary = (Switch) at(new Switch(), 20, 300);
            Properties props = new Properties(); props.setProperty("type", "momentary"); momentary.setProperties(props);
            LED led = (LED) at(new LED(), 200, 300);
            SIM.withoutEvents(() -> {
                for (Gate gate : new Gate[] { a, b, r, s, momentary, led }) panel.circuit.addGate(gate);
                connect(panel.circuit, a.getPin(0), b.getPin(1)); connect(panel.circuit, b.getPin(0), a.getPin(1));
                connect(panel.circuit, r.getPin(0), a.getPin(2)); connect(panel.circuit, s.getPin(0), b.getPin(2));
                connect(panel.circuit, momentary.getPin(0), led.getPin(0));
                return null;
            });
            javax.swing.JToggleButton play = new javax.swing.JToggleButton(); AtomicInteger notices = new AtomicInteger();
            LSFrame.bindInstabilityHandler(play, () -> {
                check(SwingUtilities.isEventDispatchThread(), "Instability UI update outside EDT"); notices.incrementAndGet();
            });
            play.addActionListener(e -> {
                if (play.isSelected()) { panel.circuit.deselectAll(); panel.startSimulation(); }
                else SIM.stop();
            });
            try {
                SwingUtilities.invokeAndWait(play::doClick); idle();
                SwingUtilities.invokeAndWait(() -> { equal(false, play.isSelected()); equal(1, notices.get()); });
                equal(false, SIM.isRunning()); equal(true, SIM.isOscillating());
                boolean oldA = a.getPin(0).getLevel(), oldB = b.getPin(0).getLevel();
                Switch chosen = set ? s : r; Gate target = set ? b : a;
                CaptureWire capture = new CaptureWire(); chosen.getPin(0).addLevelListener(capture);
                SwingUtilities.invokeAndWait(() -> {
                    for (int i = 0; i < 3; i++) {
                        mouse(panel, java.awt.event.MouseEvent.MOUSE_PRESSED, chosen.getX() + 20, chosen.getY() + 20);
                        mouse(panel, java.awt.event.MouseEvent.MOUSE_RELEASED, chosen.getX() + 20, chosen.getY() + 20);
                        equal(i != 1, chosen.getPin(0).getLevel());
                    }
                    mouse(panel, java.awt.event.MouseEvent.MOUSE_PRESSED, 40, 320);
                    equal(true, momentary.getPin(0).getLevel());
                    mouse(panel, java.awt.event.MouseEvent.MOUSE_RELEASED, 40, 320);
                    equal(false, momentary.getPin(0).getLevel());
                });
                idle(); equal(0, capture.count.get()); equal(false, target.getPin(2).getLevel());
                equal(false, led.getPin(0).getLevel()); equal("1", chosen.getProperty("state"));
                equal(oldA, a.getPin(0).getLevel()); equal(oldB, b.getPin(0).getLevel());
                equal(false, SIM.isRunning()); equal(true, SIM.isOscillating());
                SwingUtilities.invokeAndWait(play::doClick); idle();
                SwingUtilities.invokeAndWait(() -> { equal(true, play.isSelected()); equal(1, notices.get()); });
                equal(true, SIM.isRunning()); equal(false, SIM.isOscillating());
                equal(true, target.getPin(2).getLevel());
                equal(set, a.getPin(0).getLevel()); equal(!set, b.getPin(0).getLevel());
                // Removing the recovery stimulus must retain the established state.
                SwingUtilities.invokeAndWait(() -> {
                    mouse(panel, java.awt.event.MouseEvent.MOUSE_PRESSED, chosen.getX() + 20, chosen.getY() + 20);
                    mouse(panel, java.awt.event.MouseEvent.MOUSE_RELEASED, chosen.getX() + 20, chosen.getY() + 20);
                });
                idle(); equal(set, a.getPin(0).getLevel()); equal(!set, b.getPin(0).getLevel());
                equal(true, SIM.isRunning()); equal(false, SIM.isOscillating());
            } finally { SIM.stop(); SIM.setInstabilityListener(() -> {}); panel.circuit.clear(); }
        }
    }
    private static void staleInstabilityNotice() throws Exception {
        NOR a = new NOR(), b = new NOR(); Circuit circuit = new Circuit();
        SIM.withoutEvents(() -> {
            circuit.addGate(a); circuit.addGate(b);
            connect(circuit, a.getPin(0), b.getPin(1)); connect(circuit, b.getPin(0), a.getPin(1)); return null;
        });
        javax.swing.JToggleButton play = new javax.swing.JToggleButton(); AtomicInteger notices = new AtomicInteger();
        LSFrame.bindInstabilityHandler(play, notices::incrementAndGet);
        try {
            // Keep the EDT busy until a new run has superseded the queued notice.
            SwingUtilities.invokeAndWait(() -> {
                SIM.start(); circuit.reset();
                try { idle(); } catch (Exception e) { throw new RuntimeException(e); }
                equal(true, SIM.isOscillating());
                SIM.start(); play.setSelected(true);
            });
            SwingUtilities.invokeAndWait(() -> { equal(true, play.isSelected()); equal(0, notices.get()); });
            equal(true, SIM.isRunning());
        } finally { SIM.stop(); SIM.setInstabilityListener(() -> {}); circuit.clear(); }
    }
    private static void moduleTemplate() throws Exception {
        MODIN in = (MODIN) at(new MODIN(), 10, 10);
        MODOUT out = (MODOUT) at(new MODOUT(), 400, 10);
        NOR a = (NOR) at(new NOR(), 100, 100), b = (NOR) at(new NOR(), 250, 100);
        LogicSimFile file = circuit(in, out, a, b);
        connect(file.circuit, a.getPin(0), b.getPin(1)); connect(file.circuit, b.getPin(0), a.getPin(1));
        connect(file.circuit, in.getOutputs().get(0), a.getPin(2));
        connect(file.circuit, a.getPin(0), out.getInputs().get(0));
        Path fixture = Paths.get("modules", "FeedbackTest.lsm"); file.fileName = fixture.toString(); XMLCreator.createXML(file);
        AtomicInteger notices = new AtomicInteger(); SIM.setInstabilityListener(notices::incrementAndGet);
        try {
            SIM.start(); App.reloadModules(); idle();
            check(App.getGate("feedbacktest") instanceof Module, "Template not loaded");
            equal(true, SIM.isRunning()); equal(false, SIM.isOscillating()); equal(0, notices.get());
            Module active = (Module) GateLoaderHelper.create(App.getGate("feedbacktest"));
            idle(); equal(true, SIM.isOscillating()); equal(1, notices.get()); active.dispose();
        } finally {
            SIM.stop(); SIM.setInstabilityListener(() -> {}); Files.deleteIfExists(fixture); App.reloadModules(); file.circuit.clear();
        }
    }

    private static final class CaptureWire extends Wire {
        final AtomicInteger count = new AtomicInteger(); final CountDownLatch first = new CountDownLatch(1);
        CaptureWire() { super(null, null); }
        @Override public void changedLevel(LSLevelEvent e) { count.incrementAndGet(); first.countDown(); }
    }
    private static void clock() throws Exception {
        CLK clock = new CLK(); Properties props = new Properties(); props.setProperty("hightime", "10"); props.setProperty("lowtime", "10"); clock.setProperties(props);
        CaptureWire capture = new CaptureWire(); clock.getPin(0).addLevelListener(capture);
        try {
            SIM.start(); clock.interact(); check(capture.first.await(2, TimeUnit.SECONDS), "Clock did not start");
            SIM.stop(); idle(); int paused = capture.count.get(); timers(); equal(paused, capture.count.get());
            SIM.start(); long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (capture.count.get() == paused && System.nanoTime() < deadline) Thread.sleep(5);
            check(capture.count.get() > paused, "Clock did not resume");
        } finally { SIM.stop(); clock.dispose(); }
    }
    private static void nullTarget() throws Exception {
        SIM.putEvent(new LSLevelEvent(new AND(), false)); idle();
        CaptureWire capture = new CaptureWire(); SIM.putEvent(new LSLevelEvent(new AND(), true, false, capture)); idle(); equal(1, capture.count.get());
    }
    private static void fanout() throws Exception {
        Gate gate = new AND(); CaptureWire capture = new CaptureWire();
        for (int i = 0; i < 2000; i++) SIM.putEvent(new LSLevelEvent(gate, true, false, capture));
        idle(); equal(2000, capture.count.get());
    }
    private static void delayProperties(Gate gate) { Properties props = new Properties(); props.setProperty("delay", "60"); gate.setProperties(props); }
    private static void cancelledDelay(Gate gate, boolean delayed) throws Exception {
        delayProperties(gate); input(gate, 0, !delayed); input(gate, 0, delayed); input(gate, 0, !delayed);
        timers(); equal(!delayed, gate.getPin(1).getLevel()); gate.dispose();
    }
    private static void sustainedDelay(Gate gate, boolean delayed) throws Exception {
        delayProperties(gate); if (!delayed) input(gate, 0, true);
        input(gate, 0, delayed); equal(!delayed, gate.getPin(1).getLevel()); timers(); equal(delayed, gate.getPin(1).getLevel()); gate.dispose();
    }
    private static void stoppedTimer() throws Exception {
        OnDelay gate = new OnDelay(); delayProperties(gate); SIM.start(); input(gate, 0, true); SIM.stop(); SIM.start(); timers();
        equal(false, gate.getPin(1).getLevel()); gate.dispose();
    }
    private static void resetTimer() throws Exception {
        OnDelay gate = new OnDelay(); delayProperties(gate); input(gate, 0, true); gate.reset(); timers(); equal(false, gate.getPin(1).getLevel()); gate.dispose();
    }
    private static void monoflop() throws Exception {
        MonoFlop gate = new MonoFlop(); Properties props = new Properties(); props.setProperty("hightime", "60"); gate.setProperties(props);
        input(gate, 0, true); equal(true, gate.getPin(1).getLevel()); timers(); equal(false, gate.getPin(1).getLevel());
        input(gate, 0, false); input(gate, 0, true); gate.reset(); timers(); equal(false, gate.getPin(1).getLevel()); gate.dispose();
    }
    private static void memoryAddress() {
        Memory128 memory = new Memory128(); Properties props = new Properties(); props.setProperty("state0", "1"); props.setProperty("state1", "2"); memory.setProperties(props);
        input(memory, 13, true); input(memory, 13, false); equal(1, word(memory, 0, 8)); input(memory, 8, true); equal(2, word(memory, 0, 8));
        input(memory, 8, false); equal(1, word(memory, 0, 8));
    }
    private static void memoryWrite() {
        Memory128 memory = new Memory128(); input(memory, 13, true); input(memory, 12, true);
        input(memory, 0, true); input(memory, 7, true); tick(memory, 14);
        input(memory, 12, false); input(memory, 13, false); equal(129, word(memory, 0, 8));
        Memory128 loaded = (Memory128) roundtrip(circuit(at(memory, 100, 100)), "memory").getGates().get(0);
        input(loaded, 13, true); input(loaded, 13, false); equal(129, word(loaded, 0, 8));
    }
    private static ProgramCounter4 counter() {
        ProgramCounter4 counter = new ProgramCounter4(); input(counter, 5, true); input(counter, 4, true); input(counter, 7, true); input(counter, 8, true); return counter;
    }
    private static void programCounter() {
        ProgramCounter4 counter = counter(); tick(counter, 6); equal(1, word(counter, 9, 4));
        ProgramCounter4 loaded = (ProgramCounter4) roundtrip(circuit(at(counter, 100, 100)), "counter").getGates().get(0);
        equal(1, word(loaded, 9, 4)); for (int i = 0; i < 15; i++) tick(counter, 6); equal(0, word(counter, 9, 4));
    }
    private static void counterJump() {
        ProgramCounter4 counter = counter(); input(counter, 5, false); input(counter, 1, true); tick(counter, 6); equal(2, word(counter, 9, 4));
    }
    private static void unfinishedUndo() {
        LSPanel panel = new LSPanel(); Gate gate = at(new AND(), 100, 100); panel.circuit.addGate(gate);
        Wire wire = connect(panel.circuit, gate.getPin(0), null); wire.addPoint(200, 100);
        panel.recordUndoState(); gate.moveBy(10, 0); panel.undo();
        equal(1, panel.circuit.getWires().size()); Wire restored = panel.circuit.getWires().get(0);
        equal(null, restored.getTo()); equal(1, restored.getPoints().size()); equal(200, restored.getPoints().get(0).getX());
    }
    private static void continueWire() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            LSPanel panel = new LSPanel(); Gate gate = at(new AND(), 100, 100); panel.circuit.addGate(gate);
            Wire wire = connect(panel.circuit, gate.getPin(0), null); wire.addPoint(200, 100);
            panel.recordUndoState(); wire.addPoint(220, 100); panel.undo();
            java.awt.event.MouseEvent click = new java.awt.event.MouseEvent(panel, java.awt.event.MouseEvent.MOUSE_PRESSED,
                System.currentTimeMillis(), 0, 100, 110, 1, false, java.awt.event.MouseEvent.BUTTON1);
            for (java.awt.event.MouseListener listener : panel.getMouseListeners()) listener.mousePressed(click);
            equal(panel.circuit.getGates().get(0).getPin(1), panel.circuit.getWires().get(0).getTo());
        });
    }
    private static LogicSimFile branches() {
        LogicSimFile file = circuit(at(new AND(), 100, 100), at(new AND(), 300, 100), at(new AND(), 300, 300), at(new AND(), 400, 300));
        Wire trunk = connect(file.circuit, file.getGates().get(0).getPin(0), file.getGates().get(1).getPin(1)); trunk.addPoint(200, 100);
        connect(file.circuit, trunk.getPoints().get(0), file.getGates().get(2).getPin(1));
        connect(file.circuit, trunk.getPoints().get(0), file.getGates().get(3).getPin(1)); return file;
    }
    private static void branchedUndo() {
        LSPanel panel = new LSPanel(); panel.circuit = branches().circuit; panel.recordUndoState(); panel.circuit.remove(new CircuitPart[]{panel.circuit.getWires().get(0)}); panel.undo();
        equal(3, panel.circuit.getWires().size());
        equal(panel.circuit.getWires().get(0).getPoints().get(0), panel.circuit.getWires().get(1).getFrom());
    }
    private static void deleteTrunk() {
        LogicSimFile file = branches(); file.circuit.remove(new CircuitPart[]{file.getWires().get(0)});
        LogicSimFile loaded = roundtrip(file, "deleted-trunk"); equal(2, loaded.getWires().size());
        equal(loaded.getWires().get(0).getFrom(), loaded.getWires().get(1).getFrom());
    }
    private static void forwardBranch() {
        LogicSimFile file = branches(); Wire trunk = file.getWires().get(0); file.circuit.parts.remove(trunk); file.circuit.parts.add(trunk);
        equal(3, roundtrip(file, "forward-branch").getWires().size());
    }
    private static void unfinishedSave() {
        LogicSimFile file = circuit(at(new AND(), 100, 100)); Wire wire = connect(file.circuit, file.getGates().get(0).getPin(0), null); wire.addPoint(200, 100);
        LogicSimFile loaded = roundtrip(file, "unfinished"); equal(1, loaded.getWires().size()); equal(null, loaded.getWires().get(0).getTo());
    }
    private static void mirror() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            LSPanel panel = new LSPanel(); AtomicInteger changes = new AtomicInteger();
            panel.setChangeListener(new CircuitChangedListener() {
                public void changedCircuit() { changes.incrementAndGet(); }
                public void changedStatusText(String text) {} public void changedZoomPos(double zoom, Point point) {}
                public void setAction(int action) {} public void needsRepaint(CircuitPart part) {}
            });
            Gate gate = at(new AND(), 100, 100); panel.circuit.addGate(gate); gate.select(); panel.mirrorSelected();
            equal(1, changes.get()); equal(Gate.XAXIS, gate.mirror); panel.undo(); equal(Gate.NORMAL, panel.circuit.getGates().get(0).mirror);
        });
    }
    private static void textHitArea() {
        TextLabel label = (TextLabel) at(new TextLabel(), 200, 200);
        label.setProperty(CircuitPart.TEXT, "/Long text for a legacy document");
        for (int rotation = 0; rotation < 4; rotation++) {
            label.deselect();
            BufferedImage image = new BufferedImage(600, 600, BufferedImage.TYPE_INT_ARGB);
            Graphics2D graphics = image.createGraphics();
            try { label.draw(graphics); } finally { graphics.dispose(); }
            int pixels = 0;
            for (int y = 0; y < 600; y++) for (int x = 0; x < 600; x++) {
                if ((image.getRGB(x, y) >>> 24) != 0) {
                    pixels++; check(label.insideFrame(x, y), "Text pixel outside hit area at " + x + "," + y);
                }
            }
            check(pixels > 0, "No text rendered");
            java.awt.Rectangle before = label.getBoundingBox();
            label.select(); graphics = image.createGraphics();
            try { label.draw(graphics); } finally { graphics.dispose(); }
            equal(before, label.getBoundingBox());
            Gate loaded = roundtrip(circuit(label), "text-rotation-" + rotation).getGates().get(0);
            equal(before, loaded.getBoundingBox());
            if (rotation == 0) { equal(230, (int) before.getCenterX()); check(before.contains(230, 210), "Historic text centre moved"); }
            label.rotate();
        }
    }

    private static void mouse(LSPanel panel, int id, int x, int y) {
        java.awt.event.MouseEvent e = new java.awt.event.MouseEvent(panel, id,
                System.currentTimeMillis(), 0, x, y, 1, false, java.awt.event.MouseEvent.BUTTON1);
        if (id == java.awt.event.MouseEvent.MOUSE_DRAGGED)
            for (java.awt.event.MouseMotionListener l : panel.getMouseMotionListeners()) l.mouseDragged(e);
        else for (java.awt.event.MouseListener l : panel.getMouseListeners()) {
            if (id == java.awt.event.MouseEvent.MOUSE_PRESSED) l.mousePressed(e);
            else l.mouseReleased(e);
        }
    }
    private static void autoWireDrop() throws Exception {
        SwingUtilities.invokeAndWait(() -> {
            LSProperties.getInstance().setProperty(LSProperties.AUTOWIRE, "true");
            LSPanel panel = new LSPanel(); NOT source = new NOT(); LED led = new LED();
            source.moveTo(100, 100); led.moveTo(300, 100);
            panel.circuit.addGate(source); panel.circuit.addGate(led); panel.circuit.deselectAll(); led.select();
            mouse(panel, java.awt.event.MouseEvent.MOUSE_PRESSED, 320, 120);
            mouse(panel, java.awt.event.MouseEvent.MOUSE_DRAGGED, 320, 120);
            int dx = source.getPin(1).getX() - led.getPin(0).getX();
            int dy = source.getPin(1).getY() - led.getPin(0).getY();
            mouse(panel, java.awt.event.MouseEvent.MOUSE_DRAGGED, 320 + dx, 120 + dy);
            equal(0, panel.circuit.getWires().size());
            mouse(panel, java.awt.event.MouseEvent.MOUSE_DRAGGED, 420, 120);
            mouse(panel, java.awt.event.MouseEvent.MOUSE_RELEASED, 420, 120);
            equal(0, panel.circuit.getWires().size());
            mouse(panel, java.awt.event.MouseEvent.MOUSE_PRESSED, led.getX() + 20, led.getY() + 20);
            mouse(panel, java.awt.event.MouseEvent.MOUSE_DRAGGED, led.getX() + 20, led.getY() + 20);
            dx = source.getPin(1).getX() - led.getPin(0).getX();
            dy = source.getPin(1).getY() - led.getPin(0).getY();
            int x = led.getX() + 20 + dx, y = led.getY() + 20 + dy;
            mouse(panel, java.awt.event.MouseEvent.MOUSE_DRAGGED, x, y);
            equal(0, panel.circuit.getWires().size());
            mouse(panel, java.awt.event.MouseEvent.MOUSE_RELEASED, x, y);
            equal(1, panel.circuit.getWires().size());
        });
        idle();
    }

    private static void normalBranches() throws Exception {
        final LSPanel[] saved = new LSPanel[1];
        SwingUtilities.invokeAndWait(() -> {
            LSProperties.getInstance().setProperty(LSProperties.MODE, LSProperties.MODE_NORMAL);
            LSPanel panel = new LSPanel(); saved[0] = panel;
            NOT source = (NOT) at(new NOT(), 100, 100);
            LED a = (LED) at(new LED(), 400, 110), b = (LED) at(new LED(), 400, 200), c = (LED) at(new LED(), 400, 300);
            panel.circuit.addGate(source); panel.circuit.addGate(a); panel.circuit.addGate(b); panel.circuit.addGate(c);
            Wire trunk = connect(panel.circuit, source.getPin(1), a.getPin(0)); trunk.initializeLevel();
            panel.circuit.deselectAll();
            for (LED target : new LED[]{b, c}) {
                mouse(panel, java.awt.event.MouseEvent.MOUSE_PRESSED, target.getPin(0).getX(), target.getPin(0).getY());
                int x = 280, y = source.getPin(1).getY();
                mouse(panel, java.awt.event.MouseEvent.MOUSE_PRESSED, x, y);
            }
            equal(3, panel.circuit.getWires().size());
            equal(null, panel.circuit.getUnfinishedWire());
            equal(panel.circuit.getWires().get(1).getTo(), panel.circuit.getWires().get(2).getTo());
        });
        idle();
        for (Gate gate : saved[0].circuit.getGates())
            if (gate instanceof LED) equal(true, gate.getPin(0).getLevel());
        roundtrip(newFile(saved[0].circuit), "normal-branches");
    }
    private static LogicSimFile newFile(Circuit circuit) {
        LogicSimFile file = new LogicSimFile(null); file.circuit = circuit; return file;
    }

    private static void dialogLanguage() {
        Properties previous = I18N.prop; String language = I18N.lang;
        java.util.Locale locale = java.util.Locale.getDefault();
        try {
            java.util.Locale.setDefault(java.util.Locale.US);
            I18N.prop = I18N.load("de"); I18N.lang = "de"; I18N.configureDialogTexts();
            equal("Abbrechen", javax.swing.UIManager.getString("OptionPane.cancelButtonText"));
            equal(I18N.tr(Lang.OK), javax.swing.UIManager.getString("OptionPane.okButtonText"));
        } finally { I18N.prop = previous; I18N.lang = language; java.util.Locale.setDefault(locale); I18N.configureDialogTexts(); }
    }
    private static void hiddenTestGate() throws Exception {
        equal("hidden", new TEST().category);
        Path legacy = xml("legacy-test-gate", "<logicsim version='3'><gates><gate type='test' x='100' y='100'/></gates></logicsim>");
        check(XMLLoader.loadXmlFile(legacy.toString()).getGates().get(0) instanceof TEST, "Old test gate cannot load");
    }
    private static void exampleModules() throws Exception {
        check(Files.exists(Paths.get("modules/beispiele/HA.lsm")), "Missing example HA");
        check(!Files.exists(Paths.get("modules/HA.lsm")), "HA still occupies student namespace");
        check(App.getGate("beispiele/ha") instanceof Module, "Example not registered");
        Path legacy = xml("legacy-adder", "<logicsim version='3'><gates><gate module='true' type='HA' x='100' y='100'/><gate type='led' x='300' y='100'/></gates><wires><wire><from id='HA@100:100' number='16'/><to id='led@300:100' number='0'/></wire></wires></logicsim>");
        LogicSimFile file = XMLLoader.loadXmlFile(legacy.toString());
        equal(null, file.getErrorString()); equal(1, file.getWires().size());
        // User modules have priority over compatibility aliases.
        Path student = Paths.get("modules/HA.lsm");
        Files.copy(Paths.get("modules/beispiele/HA.lsm"), student);
        try { App.reloadModules(); equal("HA", App.getGate("ha").type); }
        finally { Files.deleteIfExists(student); App.reloadModules(); }
        adder("beispiele/HA", 2); adder("beispiele/FA", 3);
    }

    private static void emptyText() {
        TextLabel label = new TextLabel(); label.setProperty(CircuitPart.TEXT, "");
        Graphics2D graphics = new BufferedImage(200, 200, BufferedImage.TYPE_INT_ARGB).createGraphics();
        try { label.draw(graphics); WidgetHelper.drawString(graphics, "", 0, 0, WidgetHelper.ALIGN_LEFT); }
        finally { graphics.dispose(); }
        equal("", roundtrip(circuit(at(label, 100, 100)), "empty-text").getGates().get(0).text);
    }
    private static void example(Path file) throws Exception {
        LogicSimFile original = XMLLoader.loadXmlFile(file.toString()); equal(null, original.getErrorString());
        LogicSimFile loaded = roundtrip(original, "example-" + file.getFileName());
        equal(original.getGates().size(), loaded.getGates().size()); equal(original.getWires().size(), loaded.getWires().size());
        for (int i = 0; i < original.getGates().size(); i++) {
            Gate before = original.getGates().get(i), after = loaded.getGates().get(i);
            equal(before.type, after.type); equal(before.getX(), after.getX()); equal(before.getY(), after.getY());
            equal(before.rotate90, after.rotate90); equal(before.mirror, after.mirror);
			for (Pin pin : before.getPins()) {
				Pin restored = after.getPin(pin.number);
				equal(pin.getX(), restored.getX()); equal(pin.getY(), restored.getY());
				equal(pin.levelType, restored.levelType); equal(pin.getProperties(), restored.getProperties());
			}
        }
    }
    private static void adder(String type, int inputs) throws Exception {
        Module module = new Module(type); idle();
        for (int bits = 0; bits < (1 << inputs); bits++) {
            int expected = Integer.bitCount(bits);
            for (int i = 0; i < inputs; i++) { input(module, i, (bits & (1 << i)) != 0); idle(); }
            equal(expected, word(module, 16, 2));
        }
        module.dispose();
    }
}
