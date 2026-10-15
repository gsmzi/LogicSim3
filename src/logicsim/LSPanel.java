package logicsim;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.Stroke;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseEvent;
import java.awt.event.MouseListener;
import java.awt.event.MouseMotionListener;
import java.awt.event.MouseWheelEvent;
import java.awt.event.MouseWheelListener;
import java.awt.geom.AffineTransform;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;
import java.awt.print.PageFormat;
import java.awt.print.Printable;
import java.awt.print.PrinterException;
import java.awt.print.PrinterJob;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.Vector;

import javax.swing.event.MouseInputAdapter;
import javax.swing.SwingUtilities;

public class LSPanel extends Viewer implements Printable, CircuitChangedListener, LSRepaintListener {
	public class LogicSimPainterGraphics implements Painter {
		@Override
		public void paint(Graphics2D g2, AffineTransform at, int w, int h) {
			// set anti-aliasing
			g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
			g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

			g2.transform(at);
			if (LSProperties.getInstance().getPropertyBoolean(LSProperties.PAINTGRID, true) && scaleX > 0.7f) {
				int startX = CircuitPart.round((int) Math.round(getTransformer().screenToWorldX(0)));
				int startY = CircuitPart.round((int) Math.round(getTransformer().screenToWorldY(0)));
				int endX = (int) getTransformer().screenToWorldX(w + 9);
				int endY = (int) getTransformer().screenToWorldY(h + 9);
				g2.setColor(gridColor);
				Path2D grid = new Path2D.Double();
				g2.setStroke(new BasicStroke(1));
				for (int x = startX; x < endX; x += 10) {
					for (int y = startY; y < endY; y += 10) {
						grid.moveTo(x, y);
						grid.lineTo(x, y);
					}
				}
				g2.draw(grid);
			}

			draw(g2);

			// redraw selected parts so that there are in the foreground
			for (CircuitPart part : circuit.getSelected()) {
				part.draw(g2);
			}

			if (currentAction == ACTION_SELECT) {
				g2.setStroke(dashed);
				g2.setColor(Color.blue);
				if (selectRect != null)
					g2.draw(selectRect);
			}
		}
	}

	/**
	 * A class summarizing the mouse interaction for this viewer.
	 */
	private class MouseControl extends MouseInputAdapter
			implements MouseListener, MouseMotionListener, MouseWheelListener {

		@Override
		public void mouseDragged(MouseEvent e) {
			e = convertToWorld(e);
			if (currentAction == ACTION_SELECT) {
				notifyZoomPos(scaleX, new Point(e.getX(), e.getY()));
				// previousPoint is the start point of the selection box
				Point currentMouse = new Point(e.getX(), e.getY());
				if (currentMouse.x < previousPoint.x || currentMouse.y < previousPoint.y)
					selectRect.setFrameFromDiagonal(currentMouse, previousPoint);
				else
					selectRect.setFrameFromDiagonal(previousPoint, currentMouse);
				repaint();
				return;
			}

			CircuitPart[] parts = circuit.getSelected();
			if (parts.length == 0) {
				// drag world
				int dx = e.getX() - previousPoint.x;
				int dy = e.getY() - previousPoint.y;
				translate(dx, dy);
				return;
			} else {
				// don't drag in simulation mode
				if (Simulation.getInstance().isRunning())
					return;

				// drag parts
				notifyZoomPos(scaleX, new Point(e.getX(), e.getY()));
				for (CircuitPart part : parts) {
					recordUndoForGesture();
					part.mouseDragged(e);

					if (LSProperties.getInstance().getPropertyBoolean(LSProperties.AUTOWIRE, true)) {
						// check if currentpart is a gate and if any output touches another part's input
						// pin
						if (part instanceof Gate) {
							Gate gate = (Gate) part;
							for (Pin pin : gate.pins) {
								// autowire unconnected pins only
								if (!pin.isConnected()) {
									int x = pin.getX();
									int y = pin.getY();
									for (Gate g : circuit.getGates()) {
										CircuitPart cp = g.findPartAt(x, y);
										if (cp instanceof Pin) {
											Pin p = (Pin) cp;
											if (canAutoWire(pin, p)) {
												// put new wire between pin and p
												Wire w = null;
												if (pin.isOutput())
													w = new Wire(pin, p);
												else
													w = new Wire(p, pin);
												w.deselect();
												if (circuit.addWire(w)) {
													p.connect(w);
													pin.connect(w);
												}
											}
										}
									}
								}
							}
						}
					}
				}
				fireCircuitChanged();
			}
		}

		private boolean canAutoWire(Pin sourcePin, Pin targetPin) {
			if (sourcePin.isInput() != targetPin.isOutput())
				return false;
			return !isModuleIoGate(sourcePin.parent) && !isModuleIoGate(targetPin.parent);
		}

		private boolean isModuleIoGate(CircuitPart gate) {
			return gate instanceof MODIN || gate instanceof MODOUT;
		}

		private void setPoint(int x, int y) {
			previousPoint.setLocation(x, y);
		}

		@Override
		public void mouseMoved(MouseEvent e) {
			e = convertToWorld(e);

			int rx = CircuitPart.round(e.getX());
			int ry = CircuitPart.round(e.getY());
			setPoint(rx, ry);
			notifyZoomPos(scaleX, new Point(rx, ry));

			CircuitPart[] parts = circuit.getSelected();
			if (parts.length == 1 && parts[0] instanceof Wire) {
				Wire wire = (Wire) parts[0];
				if (wire.isNotFinished()) {
					if (e.isShiftDown()) {
						// pressed SHIFT while moving and drawing wire
						WirePoint wp = wire.getLastPoint();
						int lastx = wp.getX();
						int lasty = wp.getY();
						if (Math.abs(rx - lastx) < Math.abs(ry - lasty))
							rx = lastx;
						else
							ry = lasty;
					}
					// the selected wire is unfinished - force draw
					wire.setTempPoint(new Point(rx, ry));
					repaint();
				}
			}
		}

		@Override
		public void mousePressed(MouseEvent e) {
			if (e.isPopupTrigger() || SwingUtilities.isRightMouseButton(e))
				return;

			undoRecordedForGesture = false;
			e = convertToWorld(e);
			setPoint(e.getX(), e.getY());
			int rx = CircuitPart.round(e.getX());
			int ry = CircuitPart.round(e.getY());

			boolean simRunning = Simulation.getInstance().isRunning();
			boolean expertMode = LSProperties.MODE_EXPERT
					.equals(LSProperties.getInstance().getProperty(LSProperties.MODE, LSProperties.MODE_NORMAL));

			if (simRunning) {
				currentAction = ACTION_NONE;
				//fireStatusText(NOTHING);
			}

			if (currentAction == ACTION_SELECT) {
				selectRect = new Rectangle2D.Double(e.getX(), e.getY(), 0, 0);
			}

			CircuitPart[] parts = circuit.getSelected();
			CircuitPart cp = circuit.findPartAt(e.getX(), e.getY());

			if (currentAction == ACTION_DELPOINT && cp instanceof WirePoint && cp.parent instanceof Wire) {
				recordUndoState();
				cp.parent.mousePressed(new LSMouseEvent(e, ACTION_DELPOINT, null));
			}
			// if (cp != null)
			// System.out.println(cp.toStringAll());
			if (!simRunning && cp instanceof Pin && !e.isAltDown() && currentAction == ACTION_NONE) {
				// we start a new wire if the pin we clicked is an output OR
				// if we are in expert mode
				if (((Pin) cp).isOutput() || expertMode)
					currentAction = ACTION_ADDWIRE;
			}

			if (currentAction == ACTION_ADDWIRE) {
				recordUndoState();
				WirePoint wp = null;
				Wire newWire = null;
				if (cp == null) {
					// empty space
					wp = new WirePoint(rx, ry);
				} else if (cp instanceof Wire) {
					// put a wirepoint at this position
					Wire clickedWire = (Wire) cp;
					int pt = clickedWire.isAt(e.getX(), e.getY());
					clickedWire.insertPointAfter(pt, rx, ry);
					cp = clickedWire.findPartAt(rx, ry);
					wp = (WirePoint) cp;
				} else if (cp instanceof WirePoint) {
					wp = (WirePoint) cp;
				} else if (cp instanceof Pin) {
					Pin p = (Pin) cp;
					newWire = new Wire(p, null);
					if (circuit.addWire(newWire)) {
						p.connect(newWire);
					}
				}
				if (newWire == null) {
					newWire = new Wire(wp, null);
					if (circuit.addWire(newWire)) {
						wp.connect(newWire);
					}
				}
				fireStatusText(I18N.tr(Lang.WIREEDIT));
				circuit.deselectAll();
				newWire.select();
				fireCircuitChanged();
				currentAction = ACTION_EDITWIRE;
				return;
			}

			if (currentAction == ACTION_EDITWIRE) {
				Wire wire = circuit.getUnfinishedWire();
				if (cp == null) {
					// empty space clicked
					recordUndoState();
					wire.addPoint(rx, ry);
				} else if (cp instanceof Pin) {
					Pin pin = ((Pin) cp);
					if (!expertMode && pin.isOutput())
						return;
					recordUndoState();
					wire.setTo(pin);
					wire.getTo().connect(wire);
					wire.finish();
					currentAction = ACTION_NONE;
					fireStatusText(NOTHING);
					fireCircuitChanged();
				} else if (cp instanceof Wire) {
					Wire clickedWire = (Wire) cp;
					if (clickedWire.equals(wire))
						return;
					if (!expertMode)
						return;
					recordUndoState();
					int pt = clickedWire.isAt(e.getX(), e.getY());
					clickedWire.insertPointAfter(pt, rx, ry);
					cp = clickedWire.findPartAt(rx, ry);
					wire.setTo(cp);
					wire.getTo().connect(wire);
					wire.finish();
					currentAction = ACTION_NONE;
					fireStatusText(NOTHING);
					fireCircuitChanged();
				} else if (cp instanceof WirePoint) {
					WirePoint clickedWP = (WirePoint) cp;
					// check if the clicked point belongs to another wire
					if (clickedWP.parent.equals(wire)) {
						recordUndoState();
						// the clicked wirepoint belongs to the editing wire...
						// so check if we clicked the last point of the wire to finish it
						WirePoint lp = wire.getLastPoint();
						if (lp.getX() == rx && lp.getY() == ry) {
							// it is the same point as the last one
							wire.removeLastPoint();
							wire.setTo(new WirePoint(rx, ry));
							wire.getTo().connect(wire);
							wire.finish();
							currentAction = ACTION_NONE;
							fireStatusText(NOTHING);
							fireCircuitChanged();
						} else {
							// shorten the wire and delete circles
							wire.addPoint(rx, ry);
						}
					} else {
						// wirepoint belongs to another wire
						if (!expertMode)
							return;
						recordUndoState();
						wire.setTo(clickedWP);
						wire.getTo().connect(wire);
						wire.finish();
						currentAction = ACTION_NONE;
						fireStatusText(NOTHING);
						fireCircuitChanged();
					}
				}
				repaint();
				return;
			}

			if (cp == null) {
				// empty space has been clicked
				circuit.deselectAll();
				repaint();
				fireStatusText("");
				return;
			}
			// check if the part is a connector
			if (cp instanceof Pin && !e.isAltDown() && !simRunning) {
				Pin pin = ((Pin) cp);
				fireStatusText(I18N.tr(Lang.PIN) + " (" + cp.getId() + ")");
				// modify input (inverted or high or low or revert to normal type)
				if (pin.isInput()) {
					if (currentAction == Pin.HIGH || currentAction == Pin.LOW || currentAction == Pin.INVERTED
							|| currentAction == Pin.NORMAL) {
						// 1. if we clicked on an input modificator
						recordUndoState();
						pin.setLevelType(currentAction);
						pin.changedLevel(new LSLevelEvent(new Wire(null, null), pin.level, true));
						currentAction = ACTION_NONE;
						fireStatusText(NOTHING);
						fireCircuitChanged();
						return;
					}
				}
			}
			if (cp instanceof Gate) {
				String type = ((Gate) cp).type;
				if (cp instanceof Module)
					fireStatusText(I18N.tr(Lang.MODULE) + " (" + cp.getId() + ")");
				else
					fireStatusText(I18N.getString(type, I18N.DESCRIPTION) + " (" + cp.getId() + ")");

				if (parts.length > 0 && !simRunning) {
					// check if we clicked a new gate
					if (!cp.isSelected()) {
						cp.select();
						if (!e.isShiftDown()) {
							circuit.deselectAll();
						}
						parts = circuit.getSelected();
					}
				}
			} else if (cp instanceof Wire && !simRunning) {
				String s = cp.getProperty(CircuitPart.TEXT);
				String desc = I18N.tr(Lang.WIRE);
				if (s != null)
					desc += ": " + s;
				desc += " (" + cp.getId() + ")";
				fireStatusText(desc);
				circuit.deselectAll();
				cp.select();
			} else if (cp instanceof WirePoint) {
				fireStatusText(I18N.tr(Lang.WIREPOINT) + " (" + cp.getId() + ")");
				circuit.deselectAll();
				cp.select();
			}

			cp.mousePressed(new LSMouseEvent(e, currentAction, parts));
			currentAction = ACTION_NONE;
			fireStatusText(NOTHING);
			repaint();
		}

		@Override
		public void mouseReleased(MouseEvent e) {
			if (e.isPopupTrigger() || SwingUtilities.isRightMouseButton(e))
				return;

			e = convertToWorld(e);
			int x = e.getPoint().x;
			int y = e.getPoint().y;
			LSPanel.this.requestFocusInWindow();

			if (currentAction == ACTION_SELECT) {
				CircuitPart[] parts = circuit.findParts(selectRect);
				for (CircuitPart part : parts) {
					if (part instanceof Wire) {
						Wire w = (Wire) part;
						if (w.getTo() instanceof WirePoint)
							w.getTo().select();
						if (w.getFrom() instanceof WirePoint)
							w.getFrom().select();
					}
				}
				fireStatusText(String.format(I18N.tr(Lang.PARTSSELECTED), String.valueOf(parts.length)));
				currentAction = ACTION_NONE;
				fireStatusText(NOTHING);
				selectRect = null;
				repaint();
				return;
			}
			CircuitPart[] parts = circuit.getSelected();
			for (CircuitPart part : parts) {
				part.mouseReleased(x, y);
				if (part instanceof WirePoint && part.parent == null) {
					WirePoint wp = (WirePoint) part;
					circuit.checkWirePoint(wp);
				}
			}
			CircuitPart cp = circuit.findPartAt(e.getX(), e.getY());
			if (cp != null)
				cp.mouseReleased(x, y);
		}

		@Override
		public void mouseWheelMoved(MouseWheelEvent e) {
			zoomBy(e.getX(), e.getY(), -e.getWheelRotation() * zoomingSpeed);
			notifyZoomPos(scaleX, screenToWorldPoint(e.getX(), e.getY()));
		}
	}

	static final short ACTION_NONE = 0;

	static final short ACTION_ADDWIRE = 0x50;
	static final short ACTION_EDITWIRE = 0x51;

	static final short ACTION_ADDPOINT = 0x52;
	static final short ACTION_DELPOINT = 0x53;
	static final short ACTION_SELECT = 1;

	final static Stroke dashed = new BasicStroke(3, BasicStroke.CAP_BUTT, BasicStroke.JOIN_MITER, 10,
			new float[] { 10 }, 0);
	public static final Color gridColor = Color.black;
	private static final long serialVersionUID = -6414072156700139318L;

	public static final String MSG_ABORTED = "MSG_DESELECT_BUTTONS";

	public static final String NOTHING = "NOTHING";

	CircuitChangedListener changeListener;
	public Circuit circuit = new Circuit();
	private ClipboardData clipboard;
	private Vector<ClipboardData> undoStack = new Vector<ClipboardData>();
	private boolean undoRecordedForGesture = false;

	// current mode
	private int currentAction;

	private Dimension panelSize = new Dimension(1280, 1024);

	/**
	 * used for track selection, is one endpoint of a rectangle
	 */
	private Rectangle2D selectRect;

	private static class ClipboardData {
		Vector<GateClipboardItem> gates = new Vector<GateClipboardItem>();
		Vector<WireClipboardItem> wires = new Vector<WireClipboardItem>();
		int pasteCount = 0;
	}

	private static class GateClipboardItem {
		Gate source;
		int x;
		int y;
		int rotate90;
		int mirror;
		int numInputs;
		Properties properties;
		Vector<PinClipboardItem> pins = new Vector<PinClipboardItem>();
	}

	private static class PinClipboardItem {
		Pin source;
		int number;
		int ioType;
		int levelType;
		boolean level;
		Properties properties;
	}

	private static class WireClipboardItem {
		EndpointClipboardItem from;
		EndpointClipboardItem to;
		Vector<WirePointClipboardItem> points = new Vector<WirePointClipboardItem>();
		Properties properties;
	}

	private static class EndpointClipboardItem {
		CircuitPart source;
		int x;
		int y;
		boolean show;
	}

	private static class WirePointClipboardItem {
		WirePoint source;
		int x;
		int y;
		boolean show;
	}

	public LSPanel() {
		this.setSize(panelSize);
		this.setPreferredSize(panelSize);
		this.setCursor(new Cursor(Cursor.DEFAULT_CURSOR));
		circuit.setRepaintListener(this);

		// setZoomingSpeed(0.02);
		setPainter(new LogicSimPainterGraphics());

		MouseControl mouseControl = new MouseControl();
		addMouseListener(mouseControl);
		addMouseMotionListener(mouseControl);
		addMouseWheelListener(mouseControl);

		addKeyListener(new KeyAdapter() {
			@Override
			public void keyPressed(KeyEvent e) {
				myKeyPressed(e);
			}
		});
	}

	@Override
	public void changedCircuit() {
		if (changeListener != null)
			changeListener.changedCircuit();
		repaint();
	}

	@Override
	public void changedStatusText(String text) {
		// just transfer to parent
		changeListener.changedStatusText(text);
	}

	@Override
	public void changedZoomPos(double zoom, Point pos) {
	}

	public void clear() {
		circuit.deselectAll();
		currentAction = 0;
		undoStack.clear();
		undoRecordedForGesture = false;
		circuit.clear();
		repaint();
	}

	private MouseEvent convertToWorld(MouseEvent e) {
		int x = (int) (getTransformer().screenToWorldX(e.getX()));
		int y = (int) (getTransformer().screenToWorldY(e.getY()));
		// int x = (int) Math.round(getTransformer().screenToWorldX(e.getX()));
		// int y = (int) Math.round(getTransformer().screenToWorldY(e.getY()));

		MouseEvent ec = new MouseEvent((Component) e.getSource(), e.getID(), e.getWhen(), e.getModifiersEx(), x, y,
				e.getClickCount(), e.isPopupTrigger(), e.getButton());
		return ec;
	}

	public void doPrint() {
		PrinterJob printJob = PrinterJob.getPrinterJob();
		printJob.setPrintable(this);
		if (printJob.printDialog()) {
			try {
				printJob.print();
			} catch (Exception ex) {
				ex.printStackTrace();
			}
		}
	}

	public void draw(Graphics2D g2) {
		// draw panels first
		for (CircuitPart gate : circuit.getGates()) {
			gate.draw(g2);
		}
		// then wires
		for (CircuitPart wire : circuit.getWires()) {
			wire.draw(g2);
		}
	}

	public void copySelected() {
		CircuitPart[] selected = circuit.getSelected();
		if (selected.length == 0)
			return;

		Set<Gate> selectedGates = new HashSet<Gate>();
		Set<Wire> selectedWires = new HashSet<Wire>();
		Set<WirePoint> selectedWirePoints = new HashSet<WirePoint>();
		for (CircuitPart part : selected) {
			if (part instanceof Gate)
				selectedGates.add((Gate) part);
			else if (part instanceof Wire)
				selectedWires.add((Wire) part);
			else if (part instanceof WirePoint)
				selectedWirePoints.add((WirePoint) part);
		}

		ClipboardData data = new ClipboardData();
		for (Gate gate : selectedGates)
			data.gates.add(copyGate(gate));

		for (Wire wire : circuit.getWires()) {
			boolean selectedWire = selectedWires.contains(wire);
			if (canCopyWire(wire, selectedGates, selectedWirePoints, selectedWire))
				data.wires.add(copyWire(wire));
		}

		if (data.gates.size() == 0 && data.wires.size() == 0)
			return;
		clipboard = data;
		fireStatusText(I18N.tr(Lang.COPY));
	}

	public void pasteCopied() {
		if (clipboard == null)
			return;

		recordUndoState();
		int offset = 40 * (clipboard.pasteCount + 1);
		if (pasteClipboardData(clipboard, offset, true).size() == 0)
			return;
		clipboard.pasteCount++;
		currentAction = ACTION_NONE;
		fireStatusText(I18N.tr(Lang.PASTE));
		fireCircuitChanged();
	}

	public void undo() {
		if (undoStack.size() == 0)
			return;
		ClipboardData data = undoStack.remove(undoStack.size() - 1);
		circuit.clear();
		pasteClipboardData(data, 0, false);
		currentAction = ACTION_NONE;
		fireStatusText(I18N.tr(Lang.UNDO));
		fireCircuitChanged();
	}

	public void recordUndoState() {
		undoStack.add(createCircuitSnapshot());
		if (undoStack.size() > 50)
			undoStack.remove(0);
	}

	private void recordUndoForGesture() {
		if (undoRecordedForGesture)
			return;
		recordUndoState();
		undoRecordedForGesture = true;
	}

	private ClipboardData createCircuitSnapshot() {
		ClipboardData data = new ClipboardData();
		for (Gate gate : circuit.getGates())
			data.gates.add(copyGate(gate));
		for (Wire wire : circuit.getWires())
			data.wires.add(copyWire(wire));
		return data;
	}

	private Vector<CircuitPart> pasteClipboardData(ClipboardData data, int offset, boolean selectPasted) {
		Map<Pin, Pin> copiedPins = new HashMap<Pin, Pin>();
		Map<WirePoint, WirePoint> copiedWirePoints = new HashMap<WirePoint, WirePoint>();
		Vector<CircuitPart> pasted = new Vector<CircuitPart>();

		circuit.deselectAll();
		for (GateClipboardItem item : data.gates) {
			Gate gate = pasteGate(item, offset, copiedPins);
			if (gate == null)
				continue;
			circuit.addGate(gate);
			if (selectPasted)
				gate.select();
			pasted.add(gate);
		}

		for (WireClipboardItem item : data.wires) {
			CircuitPart from = pasteEndpoint(item.from, copiedPins, copiedWirePoints, offset);
			CircuitPart to = pasteEndpoint(item.to, copiedPins, copiedWirePoints, offset);
			if (from == null || to == null)
				continue;

			Wire wire = new Wire(from, to);
			wire.setProperties(copyProperties(item.properties));
			for (WirePointClipboardItem pointItem : item.points) {
				WirePoint point = pasteWirePoint(pointItem, copiedWirePoints, offset);
				wire.addPoint(point);
			}
			if (circuit.addWire(wire)) {
				from.connect(wire);
				to.connect(wire);
				if (selectPasted)
					wire.select();
				pasted.add(wire);
			}
		}
		return pasted;
	}

	private GateClipboardItem copyGate(Gate gate) {
		GateClipboardItem item = new GateClipboardItem();
		item.source = gate;
		item.x = gate.getX();
		item.y = gate.getY();
		item.rotate90 = gate.rotate90;
		item.mirror = gate.mirror;
		item.numInputs = gate.getNumInputs();
		item.properties = copyProperties(gate.getProperties());
		for (Pin pin : gate.getPins()) {
			PinClipboardItem pinItem = new PinClipboardItem();
			pinItem.source = pin;
			pinItem.number = pin.number;
			pinItem.ioType = pin.getIoType();
			pinItem.levelType = pin.levelType;
			pinItem.level = pin.getInternalLevel();
			pinItem.properties = copyProperties(pin.getProperties());
			item.pins.add(pinItem);
		}
		return item;
	}

	private WireClipboardItem copyWire(Wire wire) {
		WireClipboardItem item = new WireClipboardItem();
		item.from = copyEndpoint(wire.getFrom());
		item.to = copyEndpoint(wire.getTo());
		item.properties = copyProperties(wire.getProperties());
		for (WirePoint point : wire.getPoints()) {
			WirePointClipboardItem pointItem = new WirePointClipboardItem();
			pointItem.source = point;
			pointItem.x = point.getX();
			pointItem.y = point.getY();
			pointItem.show = point.show;
			item.points.add(pointItem);
		}
		return item;
	}

	private EndpointClipboardItem copyEndpoint(CircuitPart part) {
		if (part == null)
			return null;
		EndpointClipboardItem item = new EndpointClipboardItem();
		item.source = part;
		item.x = part.getX();
		item.y = part.getY();
		if (part instanceof WirePoint)
			item.show = ((WirePoint) part).show;
		return item;
	}

	private boolean canCopyWire(Wire wire, Set<Gate> selectedGates, Set<WirePoint> selectedWirePoints,
			boolean selectedWire) {
		return canCopyEndpoint(wire.getFrom(), selectedGates, selectedWirePoints, selectedWire)
				&& canCopyEndpoint(wire.getTo(), selectedGates, selectedWirePoints, selectedWire);
	}

	private boolean canCopyEndpoint(CircuitPart part, Set<Gate> selectedGates, Set<WirePoint> selectedWirePoints,
			boolean selectedWire) {
		if (part instanceof Pin)
			return selectedGates.contains(((Pin) part).parent);
		if (part instanceof WirePoint)
			return selectedWire || selectedWirePoints.contains(part);
		return false;
	}

	private Gate pasteGate(GateClipboardItem item, int offset, Map<Pin, Pin> copiedPins) {
		Gate gate = null;
		try {
			gate = GateLoaderHelper.create(item.source);
		} catch (RuntimeException e) {
			e.printStackTrace();
			return null;
		}

		if (gate.supportsVariableInputs() && gate.getNumInputs() != item.numInputs)
			gate.createDynamicInputs(item.numInputs);
		for (int i = 0; i < item.rotate90; i++)
			gate.rotate();
		for (int i = 0; i < item.mirror; i++)
			gate.mirror();
		gate.moveTo(item.x + offset, item.y + offset);
		gate.setProperties(copyProperties(item.properties));

		for (PinClipboardItem pinItem : item.pins) {
			Pin pin = gate.getPin(pinItem.number);
			if (pin == null)
				continue;
			pin.setIoType(pinItem.ioType);
			pin.setLevelType(pinItem.levelType);
			pin.setLevel(pinItem.level);
			pin.setProperties(copyProperties(pinItem.properties));
			copiedPins.put(pinItem.source, pin);
		}
		return gate;
	}

	private CircuitPart pasteEndpoint(EndpointClipboardItem item, Map<Pin, Pin> copiedPins,
			Map<WirePoint, WirePoint> copiedWirePoints, int offset) {
		if (item == null)
			return null;
		if (item.source instanceof Pin)
			return copiedPins.get(item.source);
		if (item.source instanceof WirePoint) {
			WirePoint source = (WirePoint) item.source;
			WirePoint point = copiedWirePoints.get(source);
			if (point == null) {
				point = new WirePoint(item.x + offset, item.y + offset, item.show);
				copiedWirePoints.put(source, point);
			}
			return point;
		}
		return null;
	}

	private WirePoint pasteWirePoint(WirePointClipboardItem item, Map<WirePoint, WirePoint> copiedWirePoints,
			int offset) {
		WirePoint point = copiedWirePoints.get(item.source);
		if (point == null) {
			point = new WirePoint(item.x + offset, item.y + offset, item.show);
			copiedWirePoints.put(item.source, point);
		}
		return point;
	}

	private static Properties copyProperties(Properties properties) {
		Properties copy = new Properties();
		for (Object keyObject : properties.keySet()) {
			String key = (String) keyObject;
			String value = properties.getProperty(key);
			if (value != null)
				copy.setProperty(key, value);
		}
		return copy;
	}

	/**
	 * mirror a part if selected
	 */
	public void mirrorSelected() {
		CircuitPart[] parts = circuit.getSelected();
		boolean changed = false;
		for (CircuitPart part : parts) {
			if (part instanceof Gate) {
				if (!changed) {
					recordUndoState();
					changed = true;
				}
				((Gate) part).mirror();
			}
		}
		repaint();
	}

	/**
	 * check for escape, delete and space key
	 * 
	 * @param e
	 */
	protected void myKeyPressed(KeyEvent e) {
		int keyCode = e.getKeyCode();

		CircuitPart[] parts = circuit.getSelected();
		if (parts.length == 0)
			return;

		if (keyCode == KeyEvent.VK_ESCAPE) {
			if (currentAction == ACTION_EDITWIRE) {
				Wire w = (Wire) parts[0];
				int pointsOfWire = w.removeLastPoint();
				if (pointsOfWire == 0) {
					currentAction = ACTION_NONE;
					// delete wire
					w.disconnect(null);
					circuit.remove(w);
					w = null;
					parts = null;
					circuit.deselectAll();
					fireStatusText(MSG_ABORTED);
				}
			} else if (currentAction == ACTION_ADDPOINT || currentAction == ACTION_DELPOINT
					|| currentAction == ACTION_SELECT) {
				currentAction = ACTION_NONE;
				fireStatusText(MSG_ABORTED);
			} else if (parts.length > 1) {
				circuit.deselectAll();
			}
			repaint();
			return;
		}
		if (keyCode == KeyEvent.VK_UP || keyCode == KeyEvent.VK_DOWN || keyCode == KeyEvent.VK_LEFT
				|| keyCode == KeyEvent.VK_RIGHT) {
			int dx = 0;
			int dy = 0;
			if (keyCode == KeyEvent.VK_UP)
				dy -= 10;
			else if (keyCode == KeyEvent.VK_DOWN)
				dy += 10;
			else if (keyCode == KeyEvent.VK_LEFT)
				dx -= 10;
			else
				dx += 10;
			recordUndoState();
			for (CircuitPart part : parts)
				part.moveBy(dx, dy);
			fireCircuitChanged();
			return;
		}

		if (keyCode == KeyEvent.VK_DELETE || keyCode == KeyEvent.VK_BACK_SPACE) {
			recordUndoState();
			if (circuit.remove(parts)) {
				currentAction = ACTION_NONE;
				fireStatusText(NOTHING);
				fireStatusText(I18N.tr(Lang.PARTSDELETED, String.valueOf(parts.length)));
				fireCircuitChanged();
				repaint();
				return;
			}
			return;
		}

		if (keyCode == KeyEvent.VK_SPACE) {
			CircuitPart[] selected = circuit.getSelected();
			if (selected.length != 1)
				return;
			if (selected[0] instanceof Gate) {
				Gate g = (Gate) selected[0];
				g.interact();
			}
			repaint();
		}

	}

	@Override
	public void needsRepaint(CircuitPart circuitPart) {
		repaint();
	}

	private void fireCircuitChanged() {
		if (changeListener != null) {
			changeListener.changedCircuit();
		}
		repaint();
	}

	private void fireStatusText(String msg) {
		if (changeListener != null) {
			changeListener.changedStatusText(msg);
		}
	}

	private void notifyZoomPos(double zoom, Point mousePos) {
		if (changeListener != null)
			changeListener.changedZoomPos(zoom, mousePos);
	}

	@Override
	public int print(Graphics g, PageFormat pf, int pi) throws PrinterException {
		if (pi >= 1) {
			return Printable.NO_SUCH_PAGE;
		}
		draw((Graphics2D) g);
		return Printable.PAGE_EXISTS;
	}

	/**
	 * rotate a gate if selected
	 */
	public void rotateSelected() {
		CircuitPart[] parts = circuit.getSelected();
		boolean changed = false;
		for (CircuitPart part : parts) {
			if (part instanceof Gate) {
				if (!changed) {
					recordUndoState();
					changed = true;
				}
				((Gate) part).rotate();
			}
		}
		fireCircuitChanged();
	}

	public void setAction(CircuitPart g) {
		if (g != null) {
			recordUndoState();
			circuit.deselectAll();
			// place new gate
			int posX = (int) -offsetX / 10 * 10 + 20;
			int posY = (int) -offsetY / 10 * 10 + 20;
			while (circuit.isPartAtCoordinates(posX, posY)) {
				posX += 40;
				posY += 40;
			}
			g.moveTo(posX, posY);
			circuit.addGate((Gate) g);
			g.select();

			fireStatusText("MSG_ADD_NEW_GATE");
			fireCircuitChanged();
			repaint();
		}
	}

	public void setAction(int actionNumber) {
		switch (actionNumber) {
		case ACTION_ADDPOINT:
			fireStatusText(I18N.tr(Lang.ADDPOINT));
			break;
		case ACTION_DELPOINT:
			fireStatusText(I18N.tr(Lang.REMOVEPOINT));
			break;
		case Pin.HIGH:
			fireStatusText(I18N.tr(Lang.INPUTHIGH));
			break;
		case Pin.LOW:
			fireStatusText(I18N.tr(Lang.INPUTLOW));
			break;
		case Pin.NORMAL:
			fireStatusText(I18N.tr(Lang.INPUTNORM));
			break;
		case Pin.INVERTED:
			fireStatusText(I18N.tr(Lang.INPUTINV));
			break;
		}
		currentAction = actionNumber;
	}

	public void setChangeListener(CircuitChangedListener changeListener) {
		this.changeListener = changeListener;
	}

	/**
	 * less zoom
	 */
	public void zoomOut() {
		int x = getWidth() / 2;
		int y = getHeight() / 2;
		zoomBy(x, y, -0.5f);
		notifyZoomPos(scaleX, screenToWorldPoint(x, y));
	}

	/**
	 * more zoom
	 */
	public void zoomIn() {
		int x = getWidth() / 2;
		int y = getHeight() / 2;
		zoomBy(x, y, 0.5f);
		notifyZoomPos(scaleX, screenToWorldPoint(x, y));
	}

	/**
	 * zoom to all
	 */
	public void zoomAll() {
		Rectangle r = circuit.getBoundingBox();
		if (r.width == 0 || r.height == 0)
			return;
		double zx = (double) this.getWidth() / (double) r.width;
		double zy = (double) this.getHeight() / (double) r.height;
		double zf = zx < zy ? zx : zy;
		zf = Math.max(Viewer.minZoom, Math.min(Viewer.maxZoom, zf));

		double cx = r.getCenterX();
		double cy = r.getCenterY();
		scaleX = zf;
		scaleY = zf;
		offsetX = getWidth() / 2.0 / scaleX - cx;
		offsetY = getHeight() / 2.0 / scaleY - cy;
		repaint();
		notifyZoomPos(scaleX, screenToWorldPoint(getWidth() / 2, getHeight() / 2));
	}

	private Point screenToWorldPoint(int x, int y) {
		return new Point((int) getTransformer().screenToWorldX(x), (int) getTransformer().screenToWorldY(y));
	}

	public void gateSettings() {
		CircuitPart[] parts = circuit.getSelected();
		if (parts.length == 1 && parts[0] instanceof Gate) {
			Gate g = (Gate) parts[0];
			recordUndoState();
			g.showPropertiesUI(this);
			fireCircuitChanged();
		}
	}

}
