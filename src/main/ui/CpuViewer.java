package ui;

import model.Bus;

import javax.swing.*;
import java.awt.*;
import java.util.LinkedList;
import java.util.PriorityQueue;
import java.util.Queue;
import java.io.File;
import java.io.IOException;
import java.util.stream.Stream;

public class CpuViewer extends Window implements CpuOutput {
    private static final String FONT_FILE    = "./data/resource/font/CONSOLA.ttf";
    private static final float  FONT_SIZE    = 12.0f;
    private static final int    MAX_LOG_SIZE = 100;
    private static final int    FPS          = 60;

    private static Font font;

    static {
        try {
            font = Font.createFont(Font.TRUETYPE_FONT, new File(FONT_FILE));
            font = font.deriveFont(FONT_SIZE);
        } catch (FontFormatException | IOException e) {
            e.printStackTrace();
            System.exit(1);
        }
    }

    private JTextArea    textArea;
    private JScrollPane  scrollPane;

    private PreservationQueue<String> log;

    public CpuViewer(Bus bus) {
        super(bus, 1, 1, 400, 400, "CPU Viewer");

        textArea = new JTextArea(0, 0);
        textArea.setFont(font);
        scrollPane = new JScrollPane(textArea);
        add(scrollPane);

        //nes.setOutput(this);
        log = new PreservationQueue<>(MAX_LOG_SIZE);

        pack();
        setVisible(true);
        postContructor(FPS);
    }

    @Override
    public void repaint() {
        StringBuilder text = new StringBuilder();
        log.preserve();
        Queue<String> queue = log.getQueue();

        for (String cpuLog : queue) {
            text.append(cpuLog);
        }

        textArea.setText(text.toString());
        textArea.setSelectionEnd(0);
        super.repaint();
    }

    @Override
    public void log(String cpuLog) {
        log.add(cpuLog + "\n");
        if (log.size() > MAX_LOG_SIZE) {
            log.remove();
        }
    }
}
