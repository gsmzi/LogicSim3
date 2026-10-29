package gates;

import java.awt.Graphics2D;

import logicsim.Gate;
import logicsim.I18N;
import logicsim.LSLevelEvent;
import logicsim.Pin;

/**
 * SR-Latch (NOR-Latch) for LogicSim
 *
 * Behaviour like two cross-coupled NOR gates, but simulated directly so that
 * the start state is defined (Q = 0) and no oscillation can occur:
 * S=1,R=0 -> Q=1; S=0,R=1 -> Q=0; S=R=0 -> hold; S=R=1 -> Q = /Q = 0.
 *
 * @author Andreas Tetzl
 * @author Peter Gabriel
 * @version 3.0
 */
public class SRLatch extends Gate {
	static final long serialVersionUID = 1049162074522360589L;

	private boolean q = false;

	public SRLatch() {
		super("flipflops");
		type = "srl";
		createInputs(2);
		createOutputs(2);

		getPin(0).setProperty(TEXT, "S");
		getPin(1).setProperty(TEXT, "R");

		getPin(2).setProperty(TEXT, "Q");
		getPin(3).setProperty(TEXT, "/Q");

		getPin(0).moveBy(0, 10);
		getPin(1).moveBy(0, -10);
		getPin(2).moveBy(0, 10);
		getPin(3).moveBy(0, -10);
        update(false);
	}

	@Override
	public void changedLevel(LSLevelEvent e) {
		if (e.source instanceof Pin && ((Pin) e.source).isInput())
			update(false);
	}

	private void update(boolean force) {
		boolean s = getPin(0).getLevel();
		boolean r = getPin(1).getLevel();
		boolean outQ, outNQ;
		if (s && r) {
			outQ = false;
			outNQ = false;
		} else {
			if (s)
				q = true;
			else if (r)
				q = false;
			outQ = q;
			outNQ = !q;
		}
		getPin(2).changedLevel(new LSLevelEvent(this, outQ, force));
		getPin(3).changedLevel(new LSLevelEvent(this, outNQ, force));
	}

	@Override
	public void reset() {
		super.reset();
		q = false;
		update(true);
	}

	@Override
	public void draw(Graphics2D g2) {
		super.draw(g2);
		drawLabel(g2, "SRL", Pin.smallFont);
	}

	@Override
	public void loadLanguage() {
		I18N.addGate(I18N.ALL, type, I18N.TITLE, "SR Latch");
		I18N.addGate(I18N.ALL, type, I18N.DESCRIPTION, "SR Latch");
		I18N.addGate("de", type, I18N.TITLE, "RS-Flipflop (SR-Latch)");
		I18N.addGate("de", type, I18N.DESCRIPTION, "RS-Flipflop aus zwei NOR-Gattern: S setzt Q auf 1, R setzt Q auf 0");
	}
}
