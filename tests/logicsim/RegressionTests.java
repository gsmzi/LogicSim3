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
        test("logic gate truth tables", RegressionTests::truthTables);
        test("XOR does not clock a counter spuriously", RegressionTests::xorCounter);
        test("stopping a feedback loop remains responsive", () -> { for (int i = 0; i < 25; i++) feedback(); });
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
        Category category = new Category("test");
        App.cats.add(category);
        category.addGate(new MODIN()); category.addGate(new MODOUT());
        Path sources = Paths.get(System.getProperty("project.dir"), "src", "gates");
        try (DirectoryStream<Path> files = Files.newDirectoryStream(sources, "*.java")) {
            for (Path file : files) {
                String name = file.getFileName().toString().replace(".java", "");
                category.addGate((Gate) Class.forName("gates." + name).getDeclaredConstructor().newInstance());
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
