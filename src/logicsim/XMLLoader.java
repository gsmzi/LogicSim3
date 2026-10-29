package logicsim;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Properties;
import java.util.Vector;

/**
 * XML Loader for circuit and module files
 * 
 * ideas taken from https://argonrain.wordpress.com/2009/10/27/000/
 * 
 * @author Peter Gabriel
 * @version 1.0
 */
public class XMLLoader {

	private static final String ROOT_STRING = "logicsim";
	public static String formatVersion = "3";

	public static LogicSimFile loadXmlFile(String fileName) throws RuntimeException {
		LogicSimFile ls = new LogicSimFile(fileName);
		String rootString = ROOT_STRING;
		Xml doc = new Xml(fileName, rootString);
		String version = doc.string("version");
		if (!formatVersion.equals(version))
			throw new RuntimeException(I18N.tr(Lang.READERROR) + ": version does not match");
		Xml node = doc.optChild("info");
		if (node != null) {
			for (Xml n : node.children(null)) {
				String key = "item".equals(n.name()) ? n.string("key") : n.name();
				ls.info.put(key, n.content());
			}
		}

		// gates part
		node = doc.optChild("gates");
		if (node != null) {
			for (Xml gnode : node.children(XMLCreator.TYPE_GATE)) {
				Gate gate = loadGate(gnode);
				gate.selected = false;
				ls.circuit.addGate(gate);
			}
		}

		node = doc.optChild("wires");
		if (node != null) {
			Map<Xml, Wire> pending = new LinkedHashMap<Xml, Wire>();
			Map<String, WirePoint> junctions = new HashMap<String, WirePoint>();
			for (Xml wnode : node.children(XMLCreator.TYPE_WIRE)) {
				Wire wire = new Wire(null, null);
				for (Xml pnode : wnode.children("point")) {
					wire.addPoint(new WirePoint(pnode.integer("x"), pnode.integer("y"),
							Boolean.parseBoolean(pnode.string("node"))));
				}
				wire.setProperties(getProperties(wnode));
				wire.selected = false;
				pending.put(wnode, wire);
			}
			// Resolve parents first, independent of XML ordering. Legacy files identify
			// junctions through their parent wire; new free junctions have explicit IDs.
			boolean progress = true;
			while (progress && !pending.isEmpty()) {
				progress = false;
				for (Iterator<Map.Entry<Xml, Wire>> it = pending.entrySet().iterator(); it.hasNext();) {
					Map.Entry<Xml, Wire> entry = it.next();
					Xml wnode = entry.getKey();
					CircuitPart from = loadEndpoint(wnode.child("from"), ls.circuit, junctions);
					Xml toNode = wnode.optChild("to");
					CircuitPart to = toNode == null ? null : loadEndpoint(toNode, ls.circuit, junctions);
					if (from == null || (toNode != null && to == null))
						continue;
					Wire wire = entry.getValue();
					wire.setFrom(from);
					wire.setTo(to);
					if (ls.circuit.addWire(wire)) {
						from.connect(wire);
						if (to != null) to.connect(wire);
					}
					it.remove();
					progress = true;
				}
			}
			for (Xml unresolved : pending.keySet())
				ls.addError("wire cannot be connected: " + unresolved.child("from"));
		}
		return ls;
	}

	private static CircuitPart loadEndpoint(Xml node, Circuit circuit, Map<String, WirePoint> junctions) {
		String type = node.optString("type");
		if (type == null || XMLCreator.TYPE_GATE.equals(type)) {
			Gate gate = findGateById(circuit.getGates(), node.string("id"));
			if (gate == null || gate.getPin(node.integer("number")) == null)
				throw new RuntimeException("Cannot find pin: " + node.string("id"));
			return gate.getPin(node.integer("number"));
		}
		if (XMLCreator.TYPE_WIREPOINT.equals(type)) {
			String id = node.optString("id");
			if (id != null && junctions.containsKey(id)) return junctions.get(id);
			WirePoint point = new WirePoint(node.integer("x"), node.integer("y"));
			if (id != null) junctions.put(id, point);
			return point;
		}
		if (XMLCreator.TYPE_WIRE.equals(type)) {
			for (Wire wire : circuit.getWires())
				for (WirePoint point : wire.getPoints())
					if (node.string("id").equals(point.getId())) return point;
		}
		return null;
	}

	private static Gate loadGate(Xml gnode) {
		// acquire basic information
		String type = gnode.string("type").toLowerCase();
		int x = Integer.parseInt(gnode.string("x"));
		int y = Integer.parseInt(gnode.string("y"));
		String optRotate = gnode.optString("rotate");
		String optMirror = gnode.optString("mirror");
		String optInputs = gnode.optString("inputs");

		Gate gate = null;
		try {
			Gate g = App.getGate(type);
			if (g == null)
				throw new RuntimeException("gate type '" + type + "' not there");
			gate = GateLoaderHelper.create(g);
		} catch (Exception e) {
			throw new RuntimeException(e.getMessage());
		}

		if (optInputs != null)
			gate.createDynamicInputs(Integer.parseInt(optInputs));
		if (optRotate != null) {
			int rot = Integer.parseInt(optRotate) / 90;
			for (int i = 0; i < rot; i++)
				gate.rotate();
		}
		if (optMirror != null) {
			if ("x".equals(optMirror))
				gate.mirror();
			else if ("y".equals(optMirror)) {
				gate.mirror();
				gate.mirror();
			} else if ("xy".equals(optMirror)) {
				gate.mirror();
				gate.mirror();
				gate.mirror();
			}
		}
		gate.moveTo(x, y);

		// settings
		Properties ps = getProperties(gnode);
		for (Object k : ps.keySet()) {
			String key = (String) k;
			gate.setProperty(key, ps.getProperty(key));
		}
		gate.loadProperties();

		// old
		if (gnode.children("io").size() > 0)
			loadPinsIO(gate, gnode);
		else
			loadPins(gate, gnode);
		return gate;
	}

	private static Properties getProperties(Xml gnode) {
		Properties ps = new Properties();
		Xml snode = gnode.optChild("properties");
		if (snode != null) {
			for (Xml n : snode.children("property")) {
				String key = n.string("key");
				ps.setProperty(key, n.content());
			}
		}
		return ps;
	}

	@Deprecated
	private static void loadPinsIO(Gate gate, Xml gnode) {
		// pins: in/output nodes (labels, numbers)
		for (Xml inode : gnode.children("io")) {
			String ioType = inode.string("iotype");

			int number = Integer.parseInt(inode.string("number"));

			String label = inode.optString("label");
			if (label != null)
				gate.getPin(number).setProperty(CircuitPart.TEXT, label);
			gate.getPin(number).loadProperties();

			if ("input".equals(ioType)) {
				String inpType = inode.optString("type");
				if (inpType != null) {
					int inputType = 0;
					if ("high".equals(inpType)) {
						inputType = Pin.HIGH;
					} else if ("low".equals(inpType)) {
						inputType = Pin.LOW;
					} else if ("inv".equals(inpType)) {
						inputType = Pin.INVERTED;
					}
					gate.getPin(number).levelType = inputType;
				}
			}
		}
	}

	private static void loadPins(Gate gate, Xml gnode) {
		for (Xml inode : gnode.children("pin")) {
			int number = inode.integer("number");
			Pin pin = gate.getPin(number);
			if (pin == null)
				throw new RuntimeException("Cannot find pin " + number + " in " + gate.type);
			Properties properties = getProperties(inode);
			for (String key : properties.stringPropertyNames())
				pin.setProperty(key, properties.getProperty(key));
			pin.loadProperties();
			String type = inode.optString("type");
			if (type != null) {
				if ("high".equals(type)) pin.levelType = Pin.HIGH;
				else if ("low".equals(type)) pin.levelType = Pin.LOW;
				else if ("inv".equals(type)) pin.levelType = Pin.INVERTED;
				else throw new RuntimeException("Unknown pin type: " + type);
			}
		}
	}

	private static Gate findGateById(Vector<Gate> gates, String id) {
		for (Gate gate : gates) {
			if (id.equals(gate.getId()))
				return gate;
		}
		return null;
	}

	public static ArrayList<String> getModuleListFromFile(String fileName) {
		ArrayList<String> moduleList = new ArrayList<String>();
		Xml doc = new Xml(fileName, ROOT_STRING);
		String version = doc.string("version");
		if (!formatVersion.equals(version))
			throw new RuntimeException(I18N.tr(Lang.READERROR) + ": version does not match");

		Xml node = doc.optChild("gates");
		if (node != null) {
			for (Xml gnode : node.children("gate")) {
				String type = gnode.string("type").toLowerCase();
				String module = gnode.optString("module");
				if ("true".equals(module) && !moduleList.contains(type.toLowerCase())) {
					moduleList.add(type.toLowerCase());
				}
			}
		}
		return moduleList;
	}
}
