import javax.sound.sampled.*;
import javax.swing.*;
import javax.swing.event.ChangeEvent;
import javax.swing.event.ChangeListener;
import java.awt.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.LinkedList;

public class Main {
    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            JFrame frame = new JFrame("Phonocardiogram");
            frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            frame.setSize(800, 600);

            WaveformPanel waveformPanel = new WaveformPanel();
            frame.add(waveformPanel);

            frame.setVisible(true);

            new Thread(() -> {
                try {
                    AudioFormat format = new AudioFormat(44100, 16, 1, true, false);
                    DataLine.Info info = new DataLine.Info(TargetDataLine.class, format);
                    TargetDataLine line = (TargetDataLine) AudioSystem.getLine(info);
                    line.open(format, 2752);
                    line.start();

                    byte[] buffer = new byte[2752]; // 44100/16 no idea why
                    while (true) {
                        int bytesRead = line.read(buffer, 0, buffer.length);
                        if (bytesRead > 0) {
                            waveformPanel.updateWaveform(buffer);
                        }
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }).start();
        });
    }

    static class WaveformPanel extends JPanel {
        private final LinkedList<Short> waveform = new LinkedList<>();
        private LinkedList<Long> peakTimes = new LinkedList<>();
        private int peakCount = 0;
        private int PEAK_THRESHOLD = 10000; // Threshold for counting peaks. Max value is 32768.
        private final int PEAK_COOLDOWN = 3000; // Cooldown period in samples. Works great as is.
        private int cooldownCounter = 0;
        private boolean isPeak = false;
        private int heartRate = 0;
        private double totalSamples = 0;

        public WaveformPanel() {
            // Initialize the spinner with the current value of PEAK_THRESHOLD
            JSpinner thresholdSpinner = new JSpinner(new SpinnerNumberModel(PEAK_THRESHOLD, 0, 40000, 500));
            thresholdSpinner.addChangeListener(new ChangeListener() {
                @Override
                public void stateChanged(ChangeEvent e) {
                    PEAK_THRESHOLD = (int) ((JSpinner) e.getSource()).getValue(); // Update PEAK_THRESHOLD when the user changes the value
                }
            });

            // Add the spinner to the panel
            this.add(thresholdSpinner);
        }

        void updateWaveform(byte[] bytes) {
            short[] newWaveform = new short[bytes.length / 2];
            ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(newWaveform);

            long currentTime = System.currentTimeMillis();

            synchronized (waveform) {
                short previousSample = 0;
                for (short sample : newWaveform) {
                    waveform.add(sample);
                    totalSamples++;
                    if (Math.abs(sample) >= PEAK_THRESHOLD && Math.abs(previousSample) < PEAK_THRESHOLD && cooldownCounter == 0) {
                        isPeak = true;
                        peakCount += 1;
                        cooldownCounter = PEAK_COOLDOWN;

                        // Calculate heart rate
                        if (peakCount % 2 != 0) { // Only count the first peak of each heartbeat. Time between n and n + 2.
                            peakTimes.add(currentTime);
                            if (peakTimes.size() > 10) { // Keep the last 10 peak times
                                peakTimes.removeFirst();
                            }
                            if (peakTimes.size() >= 2) { // We need at least two peaks to calculate the heart rate
                                double beatsPerMinute = getBeatsPerMinute(); // Actual calculation done here
                                heartRate = (int) Math.round(beatsPerMinute);
                            }
                        }
                    } else if (Math.abs(sample) < PEAK_THRESHOLD) {
                        isPeak = false;
                    }
                    if (cooldownCounter > 0) {
                        cooldownCounter--;
                    }
                    previousSample = sample;
                    if (waveform.size() > 220500) { // 5 seconds of audio with a sample rate of 44100 Hz
                        waveform.removeFirst();
                    }
                }
            }
            repaint();
        }

        private double getBeatsPerMinute() {
            long totalDifference = 0;
            for (int i = 1; i < peakTimes.size(); i++) {
                totalDifference += peakTimes.get(i) - peakTimes.get(i - 1);
            }
            double averageTimeBetweenPeaks = totalDifference / (double) (peakTimes.size() - 1); // Average time between peaks in milliseconds
            double beatsPerMillisecond = 1.0 / averageTimeBetweenPeaks;
            return beatsPerMillisecond * 60 * 1000;
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);

            Short[] waveformArray;
            synchronized (waveform) {
                waveformArray = waveform.toArray(new Short[0]);
            }

            if (waveformArray.length > 0) {
                int width = getWidth();
                int height = getHeight();
                for (int i = 1; i < waveformArray.length; i++) {
                    int x1 = (i - 1) * width / (waveformArray.length - 1);
                    int x2 = i * width / (waveformArray.length - 1);
                    int y1 = height / 2 + waveformArray[i - 1] * height / 2 / Short.MAX_VALUE;
                    int y2 = height / 2 + waveformArray[i] * height / 2 / Short.MAX_VALUE;
                    g.drawLine(x1, y1, x2, y2);
                }
            }
            // Display the peak count in the top left corner
            g.setColor(Color.RED);
            g.setFont(new Font("Arial", Font.BOLD, 24));
            g.drawString("Peak Counter: " + peakCount, (getWidth() - getWidth() / 3) - 5, 20);
            g.drawString("Heart Rate Counter: " + (heartRate + 1), (getWidth() - getWidth() / 3) - 5, 50);

            // Add labels for the x and y axes
            g.setColor(Color.BLACK);
            g.setFont(new Font("Arial", Font.BOLD, 15));
            g.drawString("Time (s)", getWidth() / 2, getHeight() - 20);
            g.drawString("Amplitude", 40,  20);

            double sampleRate = 44100.0; // Sample rate in Hz
            double durationInSeconds = totalSamples / sampleRate; // Calculate the total duration based on the total number of samples
            double startTime = Math.max(0, durationInSeconds - 5); // Start time for the x-axis
            int numTimeLabels = 10; // Number of time labels to display on the x-axis
            for (int i = 1; i <= numTimeLabels; i++) {
                double time = startTime + i * 5.0 / numTimeLabels; // Time for each label
                int x = (int) (i * getWidth() / (double) numTimeLabels);
                g.drawString(String.format("%.2f", time), x, getHeight() - 5);
            }

            int numAmplitudeLabels = 10; // Number of amplitude labels to display on the y-axis
            int padding = 20; // Padding from the edges of the screen
            for (int i = 0; i <= numAmplitudeLabels; i++) {
                double amplitude = -1 + i * 2.0 / numAmplitudeLabels; // Amplitude for each label
                int y = padding + (int) ((getHeight() - 2 * padding) - i * (getHeight() - 2 * padding) / (double) numAmplitudeLabels);
                g.drawString(String.format("%.2f", amplitude), 5, y);
            }
        }
//        void clearWaveform() {
//            synchronized (waveform) {
//                waveform.clear();
//                peakCount = 0;
//                cooldownCounter = 0;
//            }
//            repaint();
//        }
    }
}