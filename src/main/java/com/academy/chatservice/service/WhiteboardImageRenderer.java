package com.academy.chatservice.service;

import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.Polygon;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.List;
import java.util.Map;

@Component
public class WhiteboardImageRenderer {

    private static final int PADDING = 48;
    private static final int MIN_WIDTH = 960;
    private static final int MIN_HEIGHT = 540;

    public byte[] renderPng(List<Map<String, Object>> elements) {
        Bounds bounds = bounds(elements);
        int width = Math.max(MIN_WIDTH, (int) Math.ceil(bounds.maxX + PADDING));
        int height = Math.max(MIN_HEIGHT, (int) Math.ceil(bounds.maxY + PADDING));
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();
        try {
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setColor(Color.WHITE);
            g.fillRect(0, 0, width, height);
            g.setColor(new Color(15, 23, 42));
            g.setStroke(new BasicStroke(4f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));
            g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 24));
            for (Map<String, Object> element : elements) {
                drawElement(g, element);
            }
        } finally {
            g.dispose();
        }

        try {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(image, "png", out);
            return out.toByteArray();
        } catch (Exception e) {
            throw new IllegalStateException("No se pudo renderizar la pizarra", e);
        }
    }

    @SuppressWarnings("unchecked")
    private void drawElement(Graphics2D g, Map<String, Object> element) {
        String type = String.valueOf(element.getOrDefault("type", ""));
        double x = number(element.get("x"), 0);
        double y = number(element.get("y"), 0);
        double w = number(element.get("width"), 140);
        double h = number(element.get("height"), 72);
        String text = String.valueOf(element.getOrDefault("text", "")).trim();

        switch (type) {
            case "path" -> {
                Object rawPoints = element.get("points");
                if (!(rawPoints instanceof List<?> points) || points.isEmpty()) return;
                Path2D path = new Path2D.Double();
                boolean started = false;
                for (Object raw : points) {
                    if (!(raw instanceof Map<?, ?> point)) continue;
                    double px = number(((Map<String, Object>) point).get("x"), 0);
                    double py = number(((Map<String, Object>) point).get("y"), 0);
                    if (!started) {
                        path.moveTo(px, py);
                        started = true;
                    } else {
                        path.lineTo(px, py);
                    }
                }
                g.draw(path);
            }
            case "arrow" -> drawArrow(g, x, y, x + w, y + number(element.get("height"), 0));
            case "circle" -> {
                g.draw(new Ellipse2D.Double(x, y, w, h));
                drawCenteredText(g, text, x, y, w, h);
            }
            case "diamond" -> {
                Polygon polygon = new Polygon(
                        new int[]{(int) (x + w / 2), (int) (x + w), (int) (x + w / 2), (int) x},
                        new int[]{(int) y, (int) (y + h / 2), (int) (y + h), (int) (y + h / 2)},
                        4
                );
                g.drawPolygon(polygon);
                drawCenteredText(g, text, x, y, w, h);
            }
            case "rect" -> {
                g.drawRoundRect((int) x, (int) y, (int) w, (int) h, 12, 12);
                drawCenteredText(g, text, x, y, w, h);
            }
            case "text", "equation" -> {
                if (!text.isBlank()) g.drawString(text, (int) x, (int) y);
            }
            default -> {
            }
        }
    }

    private void drawArrow(Graphics2D g, double x1, double y1, double x2, double y2) {
        g.draw(new Line2D.Double(x1, y1, x2, y2));
        double angle = Math.atan2(y2 - y1, x2 - x1);
        int size = 14;
        Path2D head = new Path2D.Double();
        head.moveTo(x2, y2);
        head.lineTo(x2 - size * Math.cos(angle - Math.PI / 6), y2 - size * Math.sin(angle - Math.PI / 6));
        head.lineTo(x2 - size * Math.cos(angle + Math.PI / 6), y2 - size * Math.sin(angle + Math.PI / 6));
        head.closePath();
        g.fill(head);
    }

    private void drawCenteredText(Graphics2D g, String text, double x, double y, double w, double h) {
        if (text.isBlank()) return;
        var metrics = g.getFontMetrics();
        int tx = (int) (x + (w - metrics.stringWidth(text)) / 2);
        int ty = (int) (y + (h - metrics.getHeight()) / 2 + metrics.getAscent());
        g.drawString(text, tx, ty);
    }

    @SuppressWarnings("unchecked")
    private Bounds bounds(List<Map<String, Object>> elements) {
        Bounds bounds = new Bounds();
        for (Map<String, Object> element : elements) {
            String type = String.valueOf(element.getOrDefault("type", ""));
            if ("path".equals(type) && element.get("points") instanceof List<?> points) {
                for (Object raw : points) {
                    if (!(raw instanceof Map<?, ?> point)) continue;
                    bounds.include(number(((Map<String, Object>) point).get("x"), 0), number(((Map<String, Object>) point).get("y"), 0));
                }
            } else {
                double x = number(element.get("x"), 0);
                double y = number(element.get("y"), 0);
                bounds.include(x + number(element.get("width"), 160), y + number(element.get("height"), 80));
            }
        }
        return bounds;
    }

    private double number(Object value, double fallback) {
        if (value instanceof Number n) return n.doubleValue();
        if (value == null) return fallback;
        try {
            return Double.parseDouble(String.valueOf(value));
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static class Bounds {
        double maxX = MIN_WIDTH - PADDING;
        double maxY = MIN_HEIGHT - PADDING;

        void include(double x, double y) {
            maxX = Math.max(maxX, x);
            maxY = Math.max(maxY, y);
        }
    }
}
