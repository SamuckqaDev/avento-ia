package com.avento.service;

import java.awt.Dimension;
import java.awt.GraphicsEnvironment;
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
            return true;
        } catch (Exception e) {
            logger.error("Falha ao inicializar AWT Robot para controle espacial: {}", e.getMessage());
            return false;
        }
    }

    private boolean isMousePressedState = false;

    public boolean executeSpatialClick(double xRatio, double yRatio, boolean isDouble, boolean isRight) {
        if (robot == null && !initRobot()) {
            logger.warn("Robot indisponível para clique espacial");
            return false;
        }

        try {
            Dimension screenSize = Toolkit.getDefaultToolkit().getScreenSize();
            int targetX = (int) Math.round(xRatio * screenSize.width);
            int targetY = (int) Math.round(yRatio * screenSize.height);

            targetX = Math.max(0, Math.min(targetX, screenSize.width - 1));
            targetY = Math.max(0, Math.min(targetY, screenSize.height - 1));

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
            Dimension screenSize = Toolkit.getDefaultToolkit().getScreenSize();
            int targetX = (int) Math.round(xRatio * screenSize.width);
            int targetY = (int) Math.round(yRatio * screenSize.height);

            targetX = Math.max(0, Math.min(targetX, screenSize.width - 1));
            targetY = Math.max(0, Math.min(targetY, screenSize.height - 1));

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
            Dimension screenSize = Toolkit.getDefaultToolkit().getScreenSize();
            int targetX = (int) Math.round(xRatio * screenSize.width);
            int targetY = (int) Math.round(yRatio * screenSize.height);

            targetX = Math.max(0, Math.min(targetX, screenSize.width - 1));
            targetY = Math.max(0, Math.min(targetY, screenSize.height - 1));

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
