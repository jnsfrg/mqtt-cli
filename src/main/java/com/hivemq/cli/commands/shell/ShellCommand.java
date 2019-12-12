/*
 * Copyright 2019 HiveMQ and the HiveMQ Community
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package com.hivemq.cli.commands.shell;

import com.google.common.base.Throwables;
import com.hivemq.cli.DefaultCLIProperties;
import com.hivemq.cli.MqttCLIMain;
import com.hivemq.cli.utils.MqttUtils;
import org.jetbrains.annotations.NotNull;
import org.jline.reader.*;
import org.jline.reader.impl.DefaultParser;
import org.jline.reader.impl.LineReaderImpl;
import org.jline.terminal.Terminal;
import org.jline.terminal.TerminalBuilder;
import org.jline.utils.AttributedStringBuilder;
import org.jline.utils.AttributedStyle;
import org.tinylog.Level;
import org.tinylog.Logger;
import org.tinylog.configuration.Configuration;
import picocli.CommandLine;
import picocli.shell.jline3.PicocliJLineCompleter;
import sun.security.krb5.Config;

import javax.inject.Inject;
import java.io.File;
import java.io.PrintWriter;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;

@CommandLine.Command(name = "shell", aliases = "sh",
        versionProvider = MqttCLIMain.CLIVersionProvider.class,
        description = "Starts MqttCLI in shell mode, to enable interactive mode with further sub commands.",
        footer = {"", "@|bold Press Ctl-C to exit.|@"},
        synopsisHeading = "%n@|bold Usage|@:  ",
        descriptionHeading = "%n",
        optionListHeading = "%n@|bold Options|@:%n",
        commandListHeading = "%n@|bold Commands|@:%n",
        separator = " ")

public class ShellCommand implements Runnable {

    private static final String DEFAULT_PROMPT = "mqtt> ";
    private static String prompt = DEFAULT_PROMPT;

    public static boolean DEBUG;
    public static boolean VERBOSE;
    private String logfilePath;

    public static PrintWriter TERMINAL_WRITER;

    private static LineReaderImpl currentReader;
    private static LineReaderImpl shellReader;
    private static LineReaderImpl contextReader;

    private static CommandLine currentCommandLine;
    private static CommandLine shellCommandLine;
    private static CommandLine contextCommandLine;

    private static boolean exitShell = false;

    private final DefaultCLIProperties defaultCLIProperties;

    @SuppressWarnings("NullableProblems")
    @CommandLine.Spec
    private @NotNull CommandLine.Model.CommandSpec spec;

    @Inject
    ShellCommand(final @NotNull DefaultCLIProperties defaultCLIProperties) {
        this.defaultCLIProperties = defaultCLIProperties;
    }

    @CommandLine.Option(names = {"--version", "-V"}, versionHelp = true, description = "display version info")
    boolean versionInfoRequested;

    @CommandLine.Option(names = {"--help", "-h"}, usageHelp = true, description = "display this help message")
    boolean usageHelpRequested;

    @Override
    public void run() {

        final String dir = defaultCLIProperties.getLogfilePath();

        final Level debugLevel = defaultCLIProperties.getShellDebugLevel();
        switch (debugLevel) {
            case TRACE:
                VERBOSE = true;
                DEBUG = false;
                break;
            case DEBUG:
                VERBOSE = false;
                DEBUG = true;
                break;
            case INFO:
                VERBOSE = false;
                DEBUG = false;
                break;
        }

        final File dirFile = new File(dir);
        dirFile.mkdirs();

        DateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd");
        Date date = new Date();

        logfilePath = dir + "mqtt_cli_" + dateFormat.format(date) + ".log";

        String logfileFormatPattern = "{date: yyyy-MM-dd HH:mm:ss} | {{tag}|min-size=10} | {{level}|min-size=7} | {message}";
        if (isVerbose() || isDebug()) {
            logfileFormatPattern = "{date: yyyy-MM-dd HH:mm:ss} | {{level}|min-size=7} | {{class-name}.{method}:{line}|min-size=20} | {{tag}|min-size=10} | {message}";
        }


        // TinyLog configuration
        Map<String, String> configurationMap = new HashMap<>();
        configurationMap.put("writer1", "file");
        configurationMap.put("writer1.format", logfileFormatPattern);
        configurationMap.put("writer1.file", logfilePath);
        configurationMap.put("writer1.append", "true");
        configurationMap.put("writer1.level", "off");
        if (isDebug()) { configurationMap.put("writer1.level", "debug"); }
        if (isVerbose()) { configurationMap.put("writer1.level", "trace"); }

        Configuration.replace(configurationMap);

        logfilePath = Configuration.get("writer1.file");

        interact();
    }


    private void interact() {
        shellCommandLine = MqttCLIMain.MQTTCLI.shell();
        contextCommandLine = MqttCLIMain.MQTTCLI.shellContext();

        try {
            final Terminal terminal = TerminalBuilder.builder()
                    .name("MQTT Terminal")
                    .system(true)
                    .build();

            shellReader = (LineReaderImpl) LineReaderBuilder.builder()
                    .terminal(terminal)
                    .completer(new PicocliJLineCompleter(shellCommandLine.getCommandSpec()))
                    .parser(new DefaultParser())
                    .build();

            contextReader = (LineReaderImpl) LineReaderBuilder.builder()
                    .terminal(terminal)
                    .completer(new PicocliJLineCompleter(contextCommandLine.getCommandSpec()))
                    .parser(new DefaultParser())
                    .build();

            readFromShell();


            TERMINAL_WRITER = terminal.writer();
            TERMINAL_WRITER.println(shellCommandLine.getUsageMessage());
            TERMINAL_WRITER.flush();

            TERMINAL_WRITER.printf("Using default values from properties file %s:\n", defaultCLIProperties.getFile().getPath());
            TERMINAL_WRITER.printf("Host: %s, Port: %d, Mqtt-Version %s, Shell-Debug-Level: %s\n",
                    defaultCLIProperties.getHost(),
                    defaultCLIProperties.getPort(),
                    defaultCLIProperties.getMqttVersion(),
                    defaultCLIProperties.getShellDebugLevel());
            TERMINAL_WRITER.printf("Writing Logfile to %s\n", logfilePath);

            String line;
            while (!exitShell) {
                try {
                    line = currentReader.readLine(prompt, null, (MaskingCallback) null, null);
                    final ParsedLine pl = currentReader.getParser().parse(line, prompt.length());
                    final String[] arguments = pl.words().toArray(new String[0]);
                    if (arguments.length != 0) {
                        currentCommandLine.execute(arguments);
                    }
                } catch (final UserInterruptException e) {
                    if (VERBOSE) {
                        Logger.trace("User interrupted shell: {}", e);
                    }
                    return;
                } catch (final Exception ex) {
                    Logger.error(ex);
                    System.err.println(Throwables.getRootCause(ex).getMessage());
                }
            }
        } catch (final Exception ex) {
            Logger.error(ex);
            System.err.println(Throwables.getRootCause(ex).getMessage());
        }
    }

    static void exitShell() {
        exitShell = true;
    }

    static void readFromContext() {
        currentReader = contextReader;
        currentCommandLine = contextCommandLine;
        prompt = new AttributedStringBuilder()
                .style(AttributedStyle.BOLD.foreground(AttributedStyle.YELLOW))
                .append(ShellContextCommand.contextClient.getConfig().getClientIdentifier().get().toString())
                .style(AttributedStyle.DEFAULT)
                .append("@")
                .style(AttributedStyle.BOLD.foreground(AttributedStyle.YELLOW))
                .append(ShellContextCommand.contextClient.getConfig().getServerHost())
                .style(AttributedStyle.DEFAULT)
                .append("> ")
                .toAnsi();
    }

    static void readFromShell() {

        currentReader = shellReader;
        currentCommandLine = shellCommandLine;
        prompt = new AttributedStringBuilder()
                .style(AttributedStyle.DEFAULT)
                .append(DEFAULT_PROMPT)
                .toAnsi();
    }

    private static void logOnRightLevels(final @NotNull Exception ex) {
        if (VERBOSE) {
            Logger.trace(ex);
        }
        else if (DEBUG) {
            Logger.debug(ex.getMessage());
        }
        Logger.error(MqttUtils.getRootCause(ex).getMessage());
    }

    static void usage(Object command) {
        currentCommandLine.usage(command, System.out);
    }

    static String getUsageMessage() {
        return currentCommandLine.getUsageMessage();
    }

    static void clearScreen() {
        currentReader.clearScreen();
    }

    static boolean isVerbose() {
        return VERBOSE;
    }

    static boolean isDebug() {
        return DEBUG;
    }

    @Override
    public String toString() {
        return  getClass().getSimpleName() + "{" +
                "logfilePath=" + logfilePath +
                ", debug=" + DEBUG +
                ", verbose=" + VERBOSE +
                "}";
    }


}
