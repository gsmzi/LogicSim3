package gates;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.Rectangle;

import logicsim.Gate;
import logicsim.I18N;
import logicsim.WidgetHelper;

/**
 * Text Label component for LogicSim
 * 
 * @author Andreas Tetzl
 * @author Peter Gabriel
 * @version 2.0
 */
public class TextLabel extends Gate {
	static final long serialVersionUID = 6576677427368074734L;

	static final String TEXTLABEL_DEFAULT = "Text";

	public TextLabel() {
		super("output");
		type = "label";
		width = 60;
		height = 20;
		loadProperties();
	}

	@Override
	protected void loadProperties() {
		text = getPropertyWithDefault(TEXT, TEXTLABEL_DEFAULT);
	}

	@Override
	public boolean insideFrame(int mx, int my) {
		return getBoundingBox().contains(mx, my);
	}

    @Override
    public Rectangle getBoundingBox() {
        // Keep the historic 60x20 anchor and rotation centre. Resizing the gate
        // while painting moved its hit area but not the text in existing files.
        java.awt.image.BufferedImage image = new java.awt.image.BufferedImage(1, 1,
                java.awt.image.BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        Rectangle size;
        try { graphics.setFont(bigFont); size = WidgetHelper.textDimensions(graphics, text); }
        finally { graphics.dispose(); }
        int cx = xc == -1 ? getX() + 30 : xc;
        int cy = yc == -1 ? getY() + 10 : yc;
        int top = cy - size.height / 2 - 4;
        if (text != null && text.startsWith("/")) top = Math.min(top, cy - size.height + 3);
        Rectangle bounds = new Rectangle(cx - size.width / 2 - 4,
                top, size.width + 8, cy + size.height / 2 + 4 - top);
        return java.awt.geom.AffineTransform.getRotateInstance(
                Math.toRadians(rotate90 * 90), cx, cy).createTransformedShape(bounds).getBounds();
    }

	@Override
	protected void drawFrame(Graphics2D g2) {
	}

	@Override
	public void drawRotated(Graphics2D g2) {
		g2.setFont(bigFont);
		g2.setColor(Color.black);
		if (text != null) {
			WidgetHelper.drawString(g2, text, xc, yc, WidgetHelper.ALIGN_CENTER);
		}
	}

	@Override
	public void loadLanguage() {
		I18N.addGate(I18N.ALL, type, I18N.TITLE, "Textfield");
		I18N.addGate(I18N.ALL, type, I18N.DESCRIPTION,
				"Textfield just for displaying text on the drawing surface - for documentation purposes");
		I18N.addGate(I18N.ALL, type, TEXT, "input Text");

		I18N.addGate("de", type, I18N.TITLE, "Textfeld");
		I18N.addGate("de", type, I18N.DESCRIPTION, "Textfeld für Dokumentationszwecke oder Hilfetexte");
		I18N.addGate("de", type, TEXT, "Text eingeben");

		I18N.addGate("es", type, I18N.TITLE, "Etiqueta de texto");

		I18N.addGate("fr", type, I18N.TITLE, "Étiquette");
		I18N.addGate("fr", type, TEXT, "Entrer le texte");

	}
}