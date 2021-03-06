/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.github.jdye64.nifi.shell;

import java.io.BufferedReader;
import java.io.InputStreamReader;

import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.HelpFormatter;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.ParseException;
import org.apache.commons.lang3.StringUtils;
import org.apache.nifi.web.api.entity.TemplateEntity;
import org.apache.nifi.web.api.entity.TemplatesEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.github.jdye64.nifi.shell.configuration.Environment;
import com.github.jdye64.nifi.shell.configuration.NiFiCLIConfiguration;
import com.github.jdye64.nifi.shell.domain.ServiceCache;
import com.github.jdye64.nifi.shell.domain.ShellContext;
import com.github.jdye64.nifi.shell.operations.DeploymentOperation;
import com.github.jdye64.nifi.shell.operations.HistoryOperation;

public class NiFiShell {

    public static Logger logger = LoggerFactory.getLogger(NiFiShell.class);
    public static Logger historyLogger = LoggerFactory.getLogger("history");

    public static void main(String[] args){

        NiFiShell m = new NiFiShell();
        try {
            if (m.run(args) != 0) {
                System.out.println("Error: Problem occured while exiting NiFi-CLI");
            } else {
                System.out.println("Good-byte ;)");
            }
        } catch (Exception ex) {
            System.out.println(ex.getMessage());
        }
    }


    //Hardcoded options values .... eyeroll
    private static final String QUIT = "q";
    private static final String HELP = "help";
    private static final String HISTORY = "history";
    private static final String LIST_ENVS = "lenvs";
    private static final String LIST_TEMPLATES = "ltemplates";
    private static final String DEPLOYMENT = "deployment";
    private static final String SET_ENV = "senv";
    private static final String STATUS = "status";

    //NiFiShell method to be run on startup
    public int run(String[] args) throws Exception {
        ShellContext shellContext = ShellContext.getInstance();
        logger.info("History file located at {}", System.getProperty(ShellContext.NIFI_SHELL_HOME_KEY));
        shellContext.setNifiShellHome(System.getProperty(ShellContext.NIFI_SHELL_HOME_KEY));
        shellContext.setNiFiCLIConfiguration(NiFiCLIConfiguration.loadNiFiCLIConfiguration(
                System.getProperty(ShellContext.NIFI_SHELL_HOME_KEY) + "/conf.json"));

        // create the parser
        CommandLineParser parser = new DefaultParser();
        try {
            //Build the NiFi-CLI options
            Options options = new Options();
            options.addOption(QUIT, false, "Quit NiFi-CLI");
            options.addOption(HELP, false, "Print Help");
            options.addOption(HISTORY, false, "Print history");
            options.addOption("le", LIST_ENVS, false, "List NiFi cluster environments");
            options.addOption("lt", LIST_TEMPLATES, false, "Lists Templates available for a particular NiFi environment");
            options.addOption("dt", DEPLOYMENT, false, "Promotes a template from a current environment to a promotion environment");
            options.addOption("se", SET_ENV, true, "Sets the current environment that should be used for all operations");
            options.addOption(STATUS, false, "Gets the status of the current NiFi environment");

            // automatically generate the help statement
            HelpFormatter formatter = new HelpFormatter();

            while (true) {
                System.out.print(shellContext.getCliDisplay());
                if (shellContext.getCurrentEnvironment() != null) {
                    System.out.print(" (" + shellContext.getCurrentEnvironment().getEnvironmentName() + ")");
                }
                System.out.print("> ");

                InputStreamReader in = new InputStreamReader(System.in);
                BufferedReader br = new BufferedReader(in);
                String input = br.readLine();
                historyLogger.info(input);

                //Parse the command.
                CommandLine cmd = parse(parser, options, StringUtils.split(input));
                if (cmd.hasOption(QUIT)) {
                    break;
                } else {
                    execute(formatter, options, cmd);
                }
            }
        }
        catch( ParseException exp ) {
            logger.error( "Parsing failed. Reason: {}" + exp.getMessage() );
        }

        return 0;
    }

    private CommandLine parse(CommandLineParser parser, Options options, String[] args) throws ParseException {
        CommandLine line = parser.parse( options, args );
        return line;
    }

    private int execute(HelpFormatter formatter, Options options, CommandLine cmd) throws Exception {

        ShellContext shellContext = ShellContext.getInstance();
        if (cmd != null) {

            if (cmd.hasOption(HELP)) {
                formatter.printHelp("nificli", options);
            } else if (cmd.hasOption(HISTORY)) {
                HistoryOperation historyOperation = new HistoryOperation();
                historyOperation.execute();
            } else if (cmd.hasOption(LIST_ENVS) || cmd.hasOption("le")) {
                for (Environment env :shellContext.getNiFiCLIConfiguration().getEnvironments()) {
                    System.out.println("\t'" + env.getEnvironmentName() + "'");
                }
            } else if (cmd.hasOption(SET_ENV) || cmd.hasOption("se")) {
                String setEnv = cmd.getOptionValue(SET_ENV);

                //Make sure the Env actually exists.
                if (shellContext.getNiFiCLIConfiguration().doesEnvironmentExist(setEnv)) {
                    logger.info("Current working environment set to: {}", setEnv);
                    shellContext.setCurrentEnvironment(shellContext.getNiFiCLIConfiguration().getEnvironmentByName(cmd.getOptionValue(SET_ENV)));
                } else {
                    logger.warn("NiFi environmont '{}' does not exist", setEnv);
                }
            } else if (cmd.hasOption(LIST_TEMPLATES)) {

                Environment currentEnv = shellContext.getCurrentEnvironment();
                if (currentEnv != null) {
                    ServiceCache sc = shellContext.getServiceCacheForEnvironmentName(currentEnv.getEnvironmentName());
                    TemplatesEntity templates = sc.getFlowService().getAllTemplates();
                    if (templates != null) {
                        for (TemplateEntity template : templates.getTemplates()) {
                            logger.info("{} - {}", template.getId(), template.getTemplate().getName());
                        }
                    } else {
                        logger.warn("No templates returned from HTTP request. Either environment is down or no templates exist.");
                    }

                } else {
                    logger.warn("Please set a current working environment before running this command");
                }

            } else if (cmd.hasOption(DEPLOYMENT)) {
                DeploymentOperation deploymentOperation = new DeploymentOperation();
                deploymentOperation.execute();
            } else if (cmd.hasOption(STATUS)) {
//                ControllerStatusEntity status = environmentService.getEnvironmentControllerStatus(CURRENT_ENV);
//                System.out.println("Status: " + controllerService.getControllerStatus(""));

                Environment currentEnv = shellContext.getCurrentEnvironment();
                if (currentEnv != null) {
                    ServiceCache sc = shellContext.getServiceCacheForEnvironmentName(currentEnv.getEnvironmentName());
                    //TemplatesEntity templates = sc.getControllerService().getControllerStatus();

                } else {
                    logger.warn("Please set a current working environment before running this command");
                }
            }

            return 0;
        } else {
            return -1;
        }
    }

}
