package com.avento.service;

import java.awt.Dimension;
import java.awt.Robot;
import java.awt.Toolkit;
import java.awt.event.InputEvent;
import java.awt.event.KeyEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class SpatialControlService {

    private static final Logger logger = LoggerFactory.getLogger(SpatialControlService.class);
    private Robot robot;

    public SpatialControlService() {
        initRobot();
    }

    private synchronized boolean initRobot() {
        if (this.robot != null) return true;
        try {
            System.setProperty("java.awt.headless", "false");
            this.robot = new Robot();
            this.robot.setAutoDelay(10);
            logger.info("Java AWT Robot inicializado com sucesso para controle de clique espacial no macOS/Desktop.");
            warnIfAccessibilityIsMissing();
            return true;
        } catch (Exception e) {
            logger.error("Falha ao inicializar AWT Robot para controle espacial: {}", e.getMessage());
            return false;
        }
    }

    /**
     * No macOS, sem permissão de Acessibilidade o {@code mouseMove} NÃO lança exceção — ele
     * simplesmente não move nada. O log dizia sucesso, o cursor ficava parado, e o sintoma virava
     * "não funciona e não sei por quê". Aqui move 1px e relê a posição: se não mudou, avisa com o
     * caminho exato do ajuste.
     */
    private void warnIfAccessibilityIsMissing() {
        try {
            java.awt.Point before = java.awt.MouseInfo.getPointerInfo().getLocation();
            int probeX = before.x + (before.x > 0 ? -1 : 1);
            robot.mouseMove(probeX, before.y);
            java.awt.Point after = java.awt.MouseInfo.getPointerInfo().getLocation();
            robot.mouseMove(before.x, before.y);

            if (after.x == before.x && after.y == before.y) {
                logger.warn("O cursor nao respondeu ao Robot: falta permissao de Acessibilidade. Conceda em"
                        + " Ajustes do Sistema > Privacidade e Seguranca > Acessibilidade para o processo"
                        + " Java/Terminal e reinicie o backend. Ate la, o controle por gesto nao move nada.");
            }
        } catch (Exception e) {
            logger.debug("Nao foi possivel verificar a permissao de Acessibilidade", e);
        }
    }

    /**
     * Retângulo de TODOS os monitores, não só o principal. {@code Toolkit.getScreenSize()} devolve
     * apenas o display primário, então com um monitor externo ligado metade da área era inalcançável
     * e o mapeamento saía deslocado.
     */
    private java.awt.Rectangle virtualDesktopBounds() {
        java.awt.Rectangle bounds = new java.awt.Rectangle();
        for (java.awt.GraphicsDevice device :
                java.awt.GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices()) {
            bounds = bounds.union(device.getDefaultConfiguration().getBounds());
        }
        if (bounds.isEmpty()) {
            Dimension primary = Toolkit.getDefaultToolkit().getScreenSize();
            return new java.awt.Rectangle(0, 0, primary.width, primary.height);
        }
        return bounds;
    }

    /** Converte proporção 0..1 em ponto absoluto do desktop virtual, preso dentro dos limites. */
    private java.awt.Point toScreenPoint(double xRatio, double yRatio) {
        java.awt.Rectangle bounds = virtualDesktopBounds();
        int x = bounds.x + (int) Math.round(xRatio * bounds.width);
        int y = bounds.y + (int) Math.round(yRatio * bounds.height);
        return new java.awt.Point(
                Math.max(bounds.x, Math.min(x, bounds.x + bounds.width - 1)),
                Math.max(bounds.y, Math.min(y, bounds.y + bounds.height - 1)));
    }

    private boolean isMousePressedState = false;

    public boolean executeSpatialClick(double xRatio, double yRatio, boolean isDouble, boolean isRight) {
        if (robot == null && !initRobot()) {
            logger.warn("Robot indisponível para clique espacial");
            return false;
        }

        try {
            java.awt.Point target = toScreenPoint(xRatio, yRatio);
            int targetX = target.x;
            int targetY = target.y;

            int buttonMask = isRight ? InputEvent.BUTTON3_DOWN_MASK : InputEvent.BUTTON1_DOWN_MASK;

            robot.mouseMove(targetX, targetY);
            robot.mousePress(buttonMask);
            robot.mouseRelease(buttonMask);

            if (isDouble) {
                robot.mousePress(buttonMask);
                robot.mouseRelease(buttonMask);
            }

            logger.info("Spatial {} click executed at: ({}, {})", isRight ? "RIGHT" : "LEFT", targetX, targetY);
            return true;
        } catch (Exception e) {
            logger.error("Error executing spatial click: {}", e.getMessage(), e);
            return false;
        }
    }

    public boolean executeSpatialDrag(double xRatio, double yRatio, boolean isDown) {
        if (robot == null && !initRobot()) {
            return false;
        }

        try {
            java.awt.Point target = toScreenPoint(xRatio, yRatio);
            int targetX = target.x;
            int targetY = target.y;

            robot.mouseMove(targetX, targetY);

            if (isDown && !isMousePressedState) {
                robot.mousePress(InputEvent.BUTTON1_DOWN_MASK);
                isMousePressedState = true;
                logger.info("Spatial drag PRESS at ({}, {})", targetX, targetY);
            } else if (!isDown && isMousePressedState) {
                robot.mouseRelease(InputEvent.BUTTON1_DOWN_MASK);
                isMousePressedState = false;
                logger.info("Spatial drag RELEASE at ({}, {})", targetX, targetY);
            }

            return true;
        } catch (Exception e) {
            logger.error("Error executing spatial drag: {}", e.getMessage());
            return false;
        }
    }

    public boolean executeSpatialMove(double xRatio, double yRatio) {
        if (robot == null && !initRobot()) {
            return false;
        }

        try {
            java.awt.Point target = toScreenPoint(xRatio, yRatio);
            int targetX = target.x;
            int targetY = target.y;

            robot.mouseMove(targetX, targetY);
            return true;
        } catch (Exception e) {
            logger.error("Error executing spatial mouse move: {}", e.getMessage());
            return false;
        }
    }

    public boolean executeSpatialSwipe(String direction) {
        if (robot == null) {
            return false;
        }

        try {
            int keyCode = "right".equalsIgnoreCase(direction) ? KeyEvent.VK_RIGHT : KeyEvent.VK_LEFT;
            robot.keyPress(keyCode);
            robot.keyRelease(keyCode);
            logger.info("Spatial swipe key executed: {}", direction);
            return true;
        } catch (Exception e) {
            logger.error("Error executing spatial swipe: {}", e.getMessage(), e);
            return false;
        }
    }
}
