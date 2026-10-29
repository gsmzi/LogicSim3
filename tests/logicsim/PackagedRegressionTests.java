package logicsim;

import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.lang.reflect.Method;
import javax.swing.JToggleButton;
import javax.swing.SwingUtilities;

/** Runs in a child JVM with only the application JAR and test harness on its classpath. */
public final class PackagedRegressionTests {
    private static final Simulation SIM = Simulation.getInstance();

    public static void main(String[] args) throws Exception {
        try {
            ClassLoader application = Gate.class.getClassLoader();
            for (String name : new String[] { "gates.Switch", "gates.NOR" }) {
                try {
                    Class.forName(name, false, application);
                    throw new AssertionError("Gate leaked onto application classpath: " + name);
                } catch (ClassNotFoundException expected) { }
            }
            check("jar".equals(Gate.class.getResource("Gate.class").getProtocol()), "Application is not loaded from JAR");
            new I18N(); App.Running_From_Jar = true;
            Method initialize = App.class.getDeclaredMethod("initializeGateCategories");
            initialize.setAccessible(true); initialize.invoke(null); idle();
            check(App.getGate("switch").getClass().getClassLoader() != application, "Gate loader is not isolated");
            for (boolean set : new boolean[] { false, true }) recover(set);
            System.out.println("PASS: isolated packaged recovery");
        } finally { SIM.stop(); }
    }

    private static void recover(boolean set) throws Exception {
        SIM.stop(); LSPanel panel = new LSPanel();
        Gate a = gate("nor", 200, 100), b = gate("nor", 350, 100);
        Gate r = gate("switch", 20, 100), s = gate("switch", 20, 200);
        SIM.withoutEvents(() -> {
            for (Gate gate : new Gate[] { a, b, r, s }) panel.circuit.addGate(gate);
            connect(panel.circuit, a.getPin(0), b.getPin(1)); connect(panel.circuit, b.getPin(0), a.getPin(1));
            connect(panel.circuit, r.getPin(0), a.getPin(2)); connect(panel.circuit, s.getPin(0), b.getPin(2));
            return null;
        });
        JToggleButton play = new JToggleButton();
        LSFrame.bindInstabilityHandler(play, () -> {});
        play.addActionListener(e -> {
            if (play.isSelected()) { panel.circuit.deselectAll(); panel.startSimulation(); }
            else SIM.stop();
        });
        try {
            SwingUtilities.invokeAndWait(play::doClick); idle();
            SwingUtilities.invokeAndWait(() -> check(!play.isSelected(), "Play button remains selected after stop"));
            check(!SIM.isRunning() && SIM.isOscillating(), "NOR feedback did not stop on instability");
            Gate chosen = set ? s : r, target = set ? b : a;
            SwingUtilities.invokeAndWait(() -> click(panel, chosen)); idle();
            check(chosen.getPin(0).getLevel(), "Stopped switch did not toggle");
            check(!target.getPin(2).getLevel(), "Stopped switch propagated before restart");
            SwingUtilities.invokeAndWait(play::doClick); idle();
            SwingUtilities.invokeAndWait(() -> check(play.isSelected(), "One click did not restart"));
            check(SIM.isRunning() && !SIM.isOscillating(), "Recovered circuit did not remain running");
            check(a.getPin(0).getLevel() == set && b.getPin(0).getLevel() != set, "Incorrect stable state");
            SwingUtilities.invokeAndWait(() -> click(panel, chosen)); idle();
            check(a.getPin(0).getLevel() == set && b.getPin(0).getLevel() != set, "Latch did not hold after input cleared");
            check(SIM.isRunning() && !SIM.isOscillating(), "Latch oscillates after input cleared");
        } finally { SIM.stop(); SIM.setInstabilityListener(() -> {}); panel.circuit.clear(); }
    }

    private static Gate gate(String type, int x, int y) {
        Gate gate = GateLoaderHelper.create(App.getGate(type)); gate.moveTo(x, y); return gate;
    }
    private static void connect(Circuit circuit, Pin from, Pin to) {
        Wire wire = new Wire(from, to); check(circuit.addWire(wire), "Wire rejected"); from.connect(wire); to.connect(wire);
    }
    private static void click(LSPanel panel, Gate gate) {
        for (int id : new int[] { MouseEvent.MOUSE_PRESSED, MouseEvent.MOUSE_RELEASED }) {
            MouseEvent event = new MouseEvent(panel, id, System.currentTimeMillis(), 0,
                    gate.getX() + 20, gate.getY() + 20, 1, false, MouseEvent.BUTTON1);
            for (MouseListener listener : panel.getMouseListeners()) {
                if (id == MouseEvent.MOUSE_PRESSED) listener.mousePressed(event);
                else listener.mouseReleased(event);
            }
        }
    }
    private static void idle() throws InterruptedException { check(SIM.awaitIdle(2000), "Simulation did not settle"); }
    private static void check(boolean value, String message) { if (!value) throw new AssertionError(message); }
}
