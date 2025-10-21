package com.fawry.utilities;

import java.util.logging.*;

import java.util.logging.*;

public class Log {

    private static final Logger LOGGER = Logger.getLogger(Log.class.getName());
    private static final String ANSI_RESET = "\u001B[0m";
    private static final String ANSI_LIGHT_GRAY = "\u001B[37m";
    private static final String ANSI_AMBER = "\u001B[33m";
    private static final String ANSI_BRIGHT_ORANGE = "\u001B[38;5;208m";


    private static void initializeLogger() {
        LOGGER.setUseParentHandlers(false);

        Handler handler = new ConsoleHandler();
        handler.setFormatter(new SimpleFormatter() {
            @Override
            public String format(LogRecord record) {
                String color = ANSI_RESET;
                if (record.getLevel() == Level.SEVERE) {
                    color = ANSI_BRIGHT_ORANGE;
                } else if (record.getLevel() == Level.WARNING) {
                    color = ANSI_AMBER;
                } else if (record.getLevel() == Level.INFO) {
                    color = ANSI_LIGHT_GRAY;
                }
                return color + super.formatMessage(record) + ANSI_RESET + "\n";
            }
        });
        handler.setLevel(Level.ALL);
        LOGGER.addHandler(handler);
    }

    static {
        initializeLogger();
    }

    public static void info(String message) {
        LOGGER.info("INFO: " + message);
    }

    public static void warning(String message) {
        LOGGER.warning("WARNING: " + message);
    }

    public static void error(String message) {
        LOGGER.severe("ERROR: " + message);
    }

    public static void error(String message, Exception exception) {
        LOGGER.severe("ERROR: " + message + " Due to: " + exception.toString());
    }


}
