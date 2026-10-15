package logicsim;

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.Point;
import java.awt.Rectangle;
import java.awt.Stroke;
import java.awt.geom.Rectangle2D;

public class WidgetHelper {

	private static Stroke oldStroke;
	private static Font oldFont;
	private static Color oldColor;

	public static final int ALIGN_CENTER = 1;
	public static final int ALIGN_LEFT = 2;
	public static final int ALIGN_RIGHT = 3;

	public static void drawSwitchHorizontal(Graphics2D g2, Rectangle rect, boolean on, Color onColor, Color offColor) {
		backup(g2);

		Color active = soften(onColor, 0.92f);
		Color inactive = offColor == null ? new Color(0xE5, 0xE7, 0xEB) : offColor;
		int arc = Math.min(rect.width, rect.height);
		g2.setStroke(new BasicStroke(1.4f));
		g2.setPaint(on ? active : inactive);
		g2.fillRoundRect(rect.x, rect.y, rect.width, rect.height, arc, arc);
		g2.setPaint(new Color(0x64, 0x74, 0x8B));
		g2.drawRoundRect(rect.x, rect.y, rect.width, rect.height, arc, arc);

		int diameter = Math.max(4, rect.height - 4);
		int knobX = on ? rect.x + rect.width - diameter - 2 : rect.x + 2;
		int knobY = rect.y + 2;
		g2.setPaint(Color.WHITE);
		g2.fillOval(knobX, knobY, diameter, diameter);
		g2.setPaint(new Color(0x94, 0xA3, 0xB8));
		g2.drawOval(knobX, knobY, diameter, diameter);
		revert(g2);
	}

	public static void drawPoint(Graphics2D g2, Point pt) {
		g2.drawOval(pt.x - 1, pt.y - 1, 3, 3);
	}

	public static void drawSwitchVertical(Graphics2D g2, Rectangle rect, boolean on, Color onColor, Color offColor) {
		backup(g2);

		Color active = soften(onColor, 0.92f);
		Color inactive = offColor == null ? new Color(0xE5, 0xE7, 0xEB) : offColor;
		int arc = Math.min(rect.width, rect.height);
		g2.setStroke(new BasicStroke(1.4f));
		g2.setPaint(on ? active : inactive);
		g2.fillRoundRect(rect.x, rect.y, rect.width, rect.height, arc, arc);
		g2.setPaint(new Color(0x64, 0x74, 0x8B));
		g2.drawRoundRect(rect.x, rect.y, rect.width, rect.height, arc, arc);

		int diameter = Math.max(4, rect.width - 4);
		int knobX = rect.x + 2;
		int knobY = on ? rect.y + rect.height - diameter - 2 : rect.y + 2;
		g2.setPaint(Color.WHITE);
		g2.fillOval(knobX, knobY, diameter, diameter);
		g2.setPaint(new Color(0x94, 0xA3, 0xB8));
		g2.drawOval(knobX, knobY, diameter, diameter);

		revert(g2);
	}

	public static void drawPushSwitch(Graphics2D g2, int x, int y, int diameter, Color fill, String label) {
		backup(g2);
		drawIndicator(g2, x, y, diameter, fill, fill);
		if (label != null) {
			int textHeight = diameter * 7 / 10;
			g2.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, textHeight));
			drawStringCentered(g2, label, x + diameter / 2, y + textHeight / 2);
		}
		revert(g2);
	}

	public static void drawIndicator(Graphics2D g2, int x, int y, int diameter, Color activeColor, boolean on) {
		drawIndicator(g2, x, y, diameter, activeColor, on ? activeColor : new Color(0xE5, 0xE7, 0xEB));
	}

	private static void drawIndicator(Graphics2D g2, int x, int y, int diameter, Color activeColor, Color fillColor) {
		backup(g2);
		Color outline = onColor(fillColor) ? activeColor.darker() : new Color(0x94, 0xA3, 0xB8);
		g2.setStroke(new BasicStroke(1.4f));
		g2.setPaint(fillColor);
		g2.fillOval(x, y, diameter, diameter);
		g2.setPaint(outline);
		g2.drawOval(x, y, diameter, diameter);
		if (onColor(fillColor)) {
			int highlight = Math.max(3, diameter / 3);
			g2.setPaint(new Color(255, 255, 255, 170));
			g2.fillOval(x + diameter / 5, y + diameter / 5, highlight, highlight);
		}
		revert(g2);
	}

	private static boolean onColor(Color color) {
		return color != null && !(color.getRed() == 0xE5 && color.getGreen() == 0xE7 && color.getBlue() == 0xEB);
	}

	private static Color soften(Color color, float factor) {
		if (color == null)
			return new Color(0x22, 0xC5, 0x5E);
		int r = Math.min(255, Math.round(color.getRed() * factor + 255 * (1 - factor)));
		int g = Math.min(255, Math.round(color.getGreen() * factor + 255 * (1 - factor)));
		int b = Math.min(255, Math.round(color.getBlue() * factor + 255 * (1 - factor)));
		return new Color(r, g, b);
	}

	private static void revert(Graphics2D g2) {
		g2.setStroke(oldStroke);
		g2.setFont(oldFont);
		g2.setColor(oldColor);
	}

	private static void backup(Graphics2D g2) {
		oldStroke = g2.getStroke();
		oldFont = g2.getFont();
		oldColor = g2.getColor();
	}

	public static void drawStringCentered(Graphics2D g2, String text, int cX, int cY) {
		FontMetrics fm = g2.getFontMetrics();
		Rectangle2D rect = fm.getStringBounds(text, g2);
		g2.drawString(text, cX - (int) (rect.getWidth() / 2), (int) (cY + rect.getHeight() / 2 + 1));
	}

	public static Rectangle rotateRectangle(Rectangle r, Point ctr) {
		Point p1 = rotatePoint90(r.x, r.y, ctr);
		Point p2 = rotatePoint90(r.x + r.width, r.y + r.height, ctr);
		r = new Rectangle(p2.x, p1.y, p1.x - p2.x, p2.y - p1.y);
		return r;
	}

	public static Point rotatePoint90(int x, int y, Point center) {
		int degrees = -90;
		double sin = Math.sin(Math.toRadians(degrees));
		double cos = Math.cos(Math.toRadians(degrees));
		// translate to origin
		x -= center.x;
		y -= center.y;
		// transform coordinate system (y grows south)
		y = -y;
		// rotate point
		double rx = x * cos - y * sin;
		double ry = x * sin + y * cos;
		x = (int) Math.round(rx);
		y = (int) Math.round(ry);
		// transform coordinate system
		y = -y;
		// translate back
		x += center.x;
		y += center.y;
		return new Point(x, y);
	}

	public static Rectangle textDimensions(Graphics2D g2, String text) {
		FontMetrics fm = g2.getFontMetrics();
		boolean overLine = false;
		if (text.charAt(0) == '/') {
			overLine = true;
			text = text.substring(1);
		}
		int stringWidth = fm.stringWidth(text);
		int stringHeight = fm.getHeight() + (overLine ? 2 : 0);
		return new Rectangle(0, 0, stringWidth, stringHeight);
	}

	public static void drawString(Graphics2D g2, String text, int x, int y, int mode) {
		int nx = x;
		int ny = y;
		if (text == null)
			return;
		Rectangle r = textDimensions(g2, text);
		boolean overLine = false;
		if (text.charAt(0) == '/') {
			overLine = true;
			text = text.substring(1);
		}
		if (mode == ALIGN_CENTER) {
			nx = x - r.width / 2;
			ny = ny + r.height / 3;
			if (overLine) {
				g2.drawLine(nx, y - r.height + 4, nx + r.width, y - r.height + 5);
			}
			g2.drawString(text, nx, ny);
		} else if (mode == ALIGN_LEFT) {
			if (overLine) {
				g2.drawLine(nx, y - r.height + 5, nx + r.width, y - r.height + 5);
			}
			g2.drawString(text, nx, ny);
		} else if (mode == ALIGN_RIGHT) {
			if (overLine) {
				g2.drawLine(nx - r.width, y - r.height + 5, nx, y - r.height + 5);
			}
			g2.drawString(text, nx - r.width, ny);
		}
	}

	public static void drawStringRotated(Graphics2D g2, String text, int x, int y, int mode, int angle) {
		g2.translate((float) x, (float) y);
		g2.rotate(Math.toRadians(angle));
		drawString(g2, text, 0, 0, mode);
		g2.rotate(-Math.toRadians(angle));
		g2.translate(-(float) x, -(float) y);
	}

}
