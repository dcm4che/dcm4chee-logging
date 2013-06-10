/***** BEGIN LICENSE BLOCK *****
   - Version: MPL 1.1/GPL 2.0/LGPL 2.1
   -
   - The contents of this file are subject to the Mozilla Public License Version
   - 1.1 (the "License"); you may not use this file except in compliance with
   - the License. You may obtain a copy of the License at
   - http://www.mozilla.org/MPL/
   -
   - Software distributed under the License is distributed on an "AS IS" basis,
   - WITHOUT WARRANTY OF ANY KIND, either express or implied. See the License
   - for the specific language governing rights and limitations under the
   - License.
   -
   - The Original Code is part of dcm4che, an implementation of DICOM(TM) in
   - Java(TM), hosted at https://github.com/gunterze/dcm4che.
   -
   - The Initial Developer of the Original Code is
   - Agfa Healthcare.
   - Portions created by the Initial Developer are Copyright (C) 2011
   - the Initial Developer. All Rights Reserved.
   -
   - Contributor(s):
   - Franz Willer <franz.willer@gmail.com>
   -
   - Alternatively, the contents of this file may be used under the terms of
   - either the GNU General Public License Version 2 or later (the "GPL"), or
   - the GNU Lesser General Public License Version 2.1 or later (the "LGPL"),
   - in which case the provisions of the GPL or the LGPL are applicable instead
   - of those above. If you wish to allow use of your version of this file only
   - under the terms of either the GPL or the LGPL, and not to allow others to
   - use your version of this file under the terms of the MPL, indicate your
   - decision by deleting the provisions above and replace them with the notice
   - and other provisions required by the GPL or the LGPL. If you do not delete
   - the provisions above, a recipient may use your version of this file under
   - the terms of any one of the MPL, the GPL or the LGPL.
   -
   - ***** END LICENSE BLOCK *****/
package org.dcm4chee.logging;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.util.HashMap;
import java.util.logging.ErrorManager;
import java.util.logging.Handler;
import java.util.logging.LogRecord;

import org.slf4j.MDC;

/**
 * Logging handler to split log messages to thread/transaction relevant pieces.
 * Therefore org.slf4j.MDC properties are used to build the log filename:
 * mdcPropertyLogDir:       Property name which contains the logging directory 
 *                          (default: jboss.server.log.dir)
 * mdcPropertyLogFilename:  Name of MDC property which holds the log filename. 
 *                          Required to enable this logger. Should be unique for each transaction.
 * mdcPropertyCloseLogFile: Name of MDC property which must be set to 'true' to close OutputStream 
 *                          of this transaction log.
 *                          If this property is not used, each OutputStream will be open until the server is stopped!
 *                          Note: the level of root logger must be INFO (or lower) to ensure that the last info log message
 *                                will trigger the close.
 * 
 * e.g.: Log each webservice request in its own log file:
 * ...
 * String transactionID = [get transactionID (e.g. hashCode of SOAP msgID)]
 * MDC.put("ws_logging_dir", "/var/log/mywebservice");                         
 * MDC.put("ws_transactionID", transactionID);
 * ...
 * [process webservice request with some logging] 
 * ...
 * MDC.put("ws_close_log", "true");
 * log.info("Close log file after request finished!");//last log message of this request
 * MDC.remove("ws_logging_dir");
 * MDC.remove("ws_transactionID");
 * MDC.remove("ws_close_log");
 *      
 * Add following to logging subsystem configuration:<pre>
 *             <custom-handler name="MY_WS_LOG" class="org.dcm4chee.logging.MdcSplittingAppender" module="org.dcm4chee.logging">
 *                <level name="WARN"/>
 *                <formatter>
 *                  <pattern-formatter pattern="%d{yyyy-MM-dd HH:mm:ss,SSS} %-5p [%c] (%t) %s%E%n"/>
 *                </formatter>
 *                <properties>
 *                  <property name="mdcPropertyLogDir" value="ws_logging_dir"/>
 *                  <property name="mdcPropertyLogFilename" value="ws_transactionID"/>
 *                  <property name="mdcPropertyCloseLogFile" value="ws_close_log"/>
 *                </properties>
 *             </custom-handler>
 *</pre>
 *
 * Enable/disable splitting:
 * Open CLI; jboss-cli.bat -c
 * [standalone@localhost:9999 /] /subsystem=logging/root-logger=ROOT:root-logger-assign-handler(name=MY_WS_LOG)
 * or
 * [standalone@localhost:9999 /] /subsystem=logging/root-logger=ROOT:root-logger-unassign-handler(name=MY_WS_LOG)
 * 
 * show current level:
 * /subsystem=logging/custom-handler=MY_WS_LOG:read-attribute(name=level)
 * 
 * change level:
 * /subsystem=logging/custom-handler=MY_WS_LOG:write-attribute(name=level,value=DEBUG)
 * 
 *   of root logger:
 * /subsystem=logging/root-logger=ROOT:write-attribute(name=level,value=TRACE)
 * 
 *   of default file appender:
 * /subsystem=logging/periodic-rotating-file-handler=FILE:write-attribute(name=level,value=INFO)
 * @author franz.willer@gmail.com
 *
 */
public class MdcSplittingAppender extends Handler {

    private String mdcPropertyLogDir;
    private String mdcPropertyLogFilename;
    private String mdcPropertyCloseLogFile;
    
    private HashMap<String, OutputStream> mapLogStreams = new HashMap<String, OutputStream>();
    
    public String getMdcPropertyLogDir() {
        return mdcPropertyLogDir;
    }

    public void setMdcPropertyLogDir(String mdcPropertyLogDir) {
        this.mdcPropertyLogDir = mdcPropertyLogDir;
    }

    public String getMdcPropertyLogFilename() {
        return mdcPropertyLogFilename;
    }

    public void setMdcPropertyLogFilename(String mdcPropertyLogFilename) {
        this.mdcPropertyLogFilename = mdcPropertyLogFilename;
    }

    public String getMdcPropertyCloseLogFile() {
        return mdcPropertyCloseLogFile;
    }

    public void setMdcPropertyCloseLogFile(String mdcPropertyCloseLogFile) {
        this.mdcPropertyCloseLogFile = mdcPropertyCloseLogFile;
    }

    @Override
    public void publish(LogRecord record) {
        synchronized (mapLogStreams) {
            if (isLoggable(record)) {
                OutputStream out = getOrCreateLogStream();
                if (out != null) {
                    try {
                        out.write(getFormatter().format(record).getBytes());
                        out.flush();
                    } catch (IOException e) {
                        reportError(e.getMessage(), e, ErrorManager.WRITE_FAILURE);
                    }
                    if (mdcPropertyCloseLogFile != null && Boolean.valueOf(MDC.get(mdcPropertyCloseLogFile))) {
                        close();
                    }
                }
            }
        }
    }

    @Override
    public void flush() {
        try {
            OutputStream out = getOrCreateLogStream();
            if (out != null) {
                out.flush();
            }
        } catch (IOException e) {
            reportError(e.getMessage(), e, ErrorManager.FLUSH_FAILURE);
        }
    }

    @Override
    public void close() throws SecurityException {
        String filename = mdcPropertyLogFilename == null ? null : MDC.get(mdcPropertyLogFilename);
        if (filename != null) {
            try {
                OutputStream out = mapLogStreams.get(filename);
                if (out != null) {
                    out.close();
                    mapLogStreams.remove(filename);
                }
            } catch (IOException e) {
                reportError(e.getMessage(), e, ErrorManager.CLOSE_FAILURE);
            }
        }
    }
    
    @Override
    public boolean isLoggable(LogRecord record) {
        if (mdcPropertyLogFilename == null || MDC.get(mdcPropertyLogFilename) == null) {
            return false;
        } else if (mdcPropertyCloseLogFile != null && Boolean.valueOf(MDC.get(mdcPropertyCloseLogFile))) {
            return true;
        } else {
            return super.isLoggable(record);
        }
    }
   
    private OutputStream getOrCreateLogStream() {
        OutputStream out = null;
        String filename = MDC.get(mdcPropertyLogFilename);
        try {
            out = mapLogStreams.get(filename);
            if (out == null) {
                String dir = MDC.get(mdcPropertyLogDir);
                if (dir == null)
                    dir = System.getProperty("jboss.server.log.dir", "log");
                File logDir = new File(dir);
                logDir.mkdirs();
                File logFile = new File(logDir, filename.endsWith(".log") ? filename : filename + ".log");
                logFile.createNewFile();
                out = new FileOutputStream(logFile, true);
                mapLogStreams.put(filename, out);
            }
        } catch (IOException e) {
            reportError(e.getMessage(), e, ErrorManager.OPEN_FAILURE);
        }
        return out;
    }

}
