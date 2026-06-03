package com.academy.chatservice.model;

/**
 * Resultado del OCR matemático especializado.
 *
 * @param latex       Expresión LaTeX detectada (vacío si falló)
 * @param confidence  Confianza del OCR (0.0 a 1.0)
 * @param text        Texto plano detectado (fallback si LaTeX no está disponible)
 * @param method      Método usado: "pix2tex", "tesseract" o "failed"
 */
public record MathOcrResult(
        String latex,
        double confidence,
        String text,
        String method
) {

    /**
     * @return true si el OCR fue exitoso con al menos confianza mínima.
     */
    public boolean isUsable() {
        return confidence >= 0.3
                && latex != null
                && !latex.isBlank()
                && !"failed".equals(method);
    }

    /**
     * @return true si el OCR usó pix2tex (el mejor método).
     */
    public boolean isFromPix2tex() {
        return "pix2tex".equals(method);
    }
}
