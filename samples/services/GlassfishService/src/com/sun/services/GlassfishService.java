/* The contents of this file are subject to the terms
 * of the Common Development and Distribution License
 * (the License). You may not use this file except in
 * compliance with the License.
 *
 * You can obtain a copy of the License at
 * http://www.sun.com/cddl/cddl.html or
 * install_dir/legal/LICENSE
 * See the License for the specific language governing
 * permission and limitations under the License.
 *
 * When distributing Covered Code, include this CDDL
 * Header Notice in each file and include the License file
 * at install_dir/legal/LICENSE.
 * If applicable, add the following below the CDDL Header,
 * with the fields enclosed by brackets [] replaced by
 * your own identifying information:
 * "Portions Copyrighted [year] [name of copyright owner]"
 *
 * Copyright 2007 Sun Microsystems Inc. All Rights Reserved
 */
package com.sun.services;

import static com.sun.faban.harness.RunContext.*;

import com.sun.faban.common.Command;
import com.sun.faban.common.CommandHandle;
import com.sun.faban.harness.Context;
import com.sun.faban.harness.RemoteCallable;

import com.sun.faban.harness.RunContext;
import com.sun.faban.harness.services.ClearLogs;
import com.sun.faban.harness.Configure;
import com.sun.faban.harness.services.GetLogs;
import com.sun.faban.harness.services.ServiceContext;
import com.sun.faban.harness.Start;
import com.sun.faban.harness.Stop;
import java.io.File;
import java.io.IOException;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.GregorianCalendar;
import java.util.Calendar;
import java.text.SimpleDateFormat;

/**
 *
 * This class implements the service to start/stop GlassFish instances.
 * It also provides functionality to transfer the portion of the glassfish
 * error_log for a run to the run output directory.
 * It can be used by any benchmark to GlassFish servers and
 * perform these operations remotely using this Service.
 *
 * @author Akara Sucharitakul modified by Sheetal Patil
 */
public class GlassfishService {

    @Context public ServiceContext ctx;
    Logger logger = Logger.getLogger(GlassfishService.class.getName());
    private String[] myServers;
    private static String asadminCmd,  errlogFile,  acclogFile;


    /**
     * The setup method is called to set up a benchmark run.
     * It is assumed that all servers have the same installation directory
     *
     */
     @Configure public void configure() {
        myServers = new String[ctx.getUniqueHosts().length];
        myServers = ctx.getUniqueHosts();
        String logsDir = ctx.getProperty("logsDir");
        if (!logsDir.endsWith(File.separator))
            logsDir = logsDir + File.separator;

        asadminCmd = ctx.getProperty("cmdPath");
        if (!asadminCmd.endsWith(File.separator))
            asadminCmd = asadminCmd + File.separator;


        asadminCmd = asadminCmd + "asadmin";
        errlogFile = logsDir + "server.log";
        acclogFile = logsDir + "access";
        logger.info("GlassfishService Configure completed.");
    }

    /**
     * Start all glassfish servers on configured hosts.
     */
    @Start public void startup() {

        Command startCmd = new Command(asadminCmd, "start-domain");       

        for (int i = 0; i < myServers.length; i++) {
            String server = myServers[i];
            try {
                // Run the command in the foreground and wait for the start
                ctx.exec(server, startCmd);
                /*
                 * Read the log file to make sure the server has started.
                 * We do this by running the code block on the server via
                 * RemoteCallable
                 */
                if (checkServerStarted(server, ctx)) {
                    logger.fine("Completed GlassFish startup successfully on " + server);
                } else {
                    logger.severe("Failed to start GlassFish on " + server);
                }

            } catch (Exception e) {
                logger.warning("Failed to start GlassFish server with " +
                                                                e.toString());
                logger.log(Level.FINE, "Exception", e);
            }
        }
        logger.info("Completed GlassFish server(s) startup");
    }

    /*
	 * Check if Glassfish server is started.
	 */
    private static boolean checkServerStarted(String hostName, ServiceContext ctx) throws Exception {
        Command checkCmd = new Command(asadminCmd, "list-domains");     
        CommandHandle handle = ctx.exec(hostName, checkCmd);
        byte[] output = handle.fetchOutput(Command.STDOUT);
        if (output != null) {
            String outStr = new String(output);
        if (outStr.indexOf("domain1 running") != -1)
            return true;
        else
            return false;
        }
        return false;
    }

    /**
     * Stop Servers.
     */
    @Stop public void shutdown() throws IOException, InterruptedException {
        for (int i = 0; i < myServers.length; i++) {
            Integer retVal = 0;
            try {
                Command stopCmd = new Command(asadminCmd, "stop-domain");
               
                // Run the command in the foreground
                CommandHandle ch = ctx.exec(myServers[i], stopCmd);

                // Check if the server was even running before stop was issued
                // If not running, asadmin will print that on stdout
                byte[] output = ch.fetchOutput(Command.STDOUT);

                if (output != null) {
                    String outStr = new String(output);
                    if (outStr.indexOf("stopped.") != -1 ||
                            outStr.indexOf("isn't running.") != -1) {
                       continue;
                    }
                }
                retVal = checkServerStopped(myServers[i], ctx);
                if (retVal == 0) {
                    logger.warning("GlassFish on " + myServers[i] +
                                        " is apparently still runnning");
                    continue;
                }
            } catch (Exception ie) {
                logger.log(Level.WARNING, "Failed to stop GlassFish on " +
                        myServers[i] + "with " + ie, ie);
            }
        }
    }

    /*
	 * Check if glassfish server is stopped.
	 */
    private static Integer checkServerStopped(String hostName, ServiceContext ctx) throws Exception {
        Command checkCmd = new Command(asadminCmd, "list-domains");
        CommandHandle handle = ctx.exec(hostName, checkCmd);
        byte[] output = handle.fetchOutput(Command.STDOUT);
        if (output != null) {
            String outStr = new String(output);
            if (outStr.indexOf("domain1 not running") != -1)
                return 1;
            else
                return 0;
        }
        return 0;
    }

    /**
     * clear glassfish logs and session files
	 * It assumes that session files are in /tmp/sess*
     * @return true if operation succeeded, else fail
     */
    @ClearLogs public boolean clearLogs() {

        for (int i = 0; i < myServers.length; i++) {
            if (isFile(myServers[i], errlogFile)) {
                if (!deleteFile(myServers[i], errlogFile)) {
                    logger.log(Level.WARNING, "Delete of " + errlogFile +
                            " failed on " + myServers[i]);
                    return (false);
                }
            }
            if (isFile(myServers[i], acclogFile)) {
                if (!deleteFile(myServers[i], acclogFile)) {
                    logger.log(Level.WARNING, "Delete of " + acclogFile +
                            " failed on " + myServers[i]);
                    return (false);
                }
            }

            logger.fine("Logs cleared for " + myServers[i]);
        }
        return (true);
    }

    /**
     * Transfer log files
     */
    @GetLogs public void xferLogs() {
        String duration = ctx.getRunDuration();
        int totalRunTime = Integer.parseInt(duration);
        for (int i = 0; i < myServers.length; i++) {
            String outFile = getOutDir() + "server_log." +
                    getHostName(myServers[i]);

            // copy the error_log to the master
            if (!getFile(myServers[i], errlogFile, outFile)) {
                logger.warning("Could not copy " + errlogFile + " to " + outFile);
                return;
            }


            try {
                // Now get the start and end times of the run
                GregorianCalendar calendar = getGregorianCalendar(myServers[i]);

                //format the end date
                SimpleDateFormat df = new SimpleDateFormat("MMM,dd,HH:mm:ss");
                String endDate = df.format(calendar.getTime());

                calendar.add(Calendar.SECOND, (totalRunTime * -1));

                String beginDate = df.format(calendar.getTime());

                Command parseCommand = new Command("truncate_errorlog.sh",
                        beginDate, endDate, outFile);
                exec(parseCommand);

            } catch (Exception e) {

                logger.log(Level.WARNING, "Failed to tranfer log of " +
                        myServers[i] + '.', e);
            }

            logger.fine("XferLog Completed for " + myServers[i]);

            logger.fine("XferLog Completed for " + myServers[i]);
        }
    }

    public static GregorianCalendar getGregorianCalendar(
            String hostName)
            throws Exception {
        return exec(hostName, new RemoteCallable<GregorianCalendar>() {

            public GregorianCalendar call() {
                return new GregorianCalendar();
            }
        });
    }


}
