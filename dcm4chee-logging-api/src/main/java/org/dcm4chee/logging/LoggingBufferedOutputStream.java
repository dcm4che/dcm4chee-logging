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

import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.Charset;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


public class LoggingBufferedOutputStream extends FilterOutputStream {

    private byte buf[];
    private int count;

    private boolean enableLog;
    private boolean enableActivityLog = false;
    private String msgHeader;
    
    private Charset charset = Charset.forName("ISO-8859-1");
    private int maxLogLen = Integer.MAX_VALUE;
    
    private Logger log;
    private ByteArrayTransformer transformer;
    
    public LoggingBufferedOutputStream(OutputStream out, Logger logger, String msgHeader) {
        this(out, logger, msgHeader, null, 8192);
    }

    public LoggingBufferedOutputStream(OutputStream out, Logger logger, String msgHeader, int bufSize) {
        this(out, logger, msgHeader, null, bufSize);
        log = logger;
    }
    public LoggingBufferedOutputStream(OutputStream out, Logger logger, String msgHeader, ByteArrayTransformer transformer) {
        this(out, logger, msgHeader, transformer, 8192);
    }

    public LoggingBufferedOutputStream(OutputStream out, Logger logger, String msgHeader, ByteArrayTransformer transformer, int bufSize) {
        super(out);
        if (bufSize <= 0) {
            throw new IllegalArgumentException("Buffer size <= 0");
        }
        buf = new byte[bufSize];
        this.msgHeader = msgHeader;
        log = logger;
        this.transformer = transformer == null ? new ByteArrayStringTransformer() : transformer;
    }

    public LoggingBufferedOutputStream setEnableLog(boolean enableLog) throws IOException {
        if (enableActivityLog)
            log.debug("##### setEnableLog:"+enableLog);
        flushBuffer();
        this.enableLog = enableLog;
        return this;
    }

    public LoggingBufferedOutputStream setEnableActivityLog(boolean enableActivityLog) {
        if (enableActivityLog)
            log.debug("##### setEnableActivityLog:"+enableActivityLog);
        this.enableActivityLog = enableActivityLog;
        return this;
    }

    public LoggingBufferedOutputStream setCharset(String charsetName) {
        this.charset = Charset.forName(charsetName);
        return this;
    }

    public LoggingBufferedOutputStream setMaxLogLen(int maxLogLen) {
        this.maxLogLen = maxLogLen;
        return this;
    }

    private void flushBuffer() throws IOException {
        if (enableActivityLog)
            log.debug("##### flushBuffer! buffer count:"+count);
        if (count > 0) {
            out.write(buf, 0, count);
            doLog(buf, count);
            count = 0;
        }
    }

    @Override
    public synchronized void write(int b) throws IOException {
        if (enableActivityLog)
            log.debug("##### write b:"+Integer.toHexString(b));
        if (count >= buf.length) {
            flushBuffer();
        }
        buf[count++] = (byte)b;
    }

    @Override
    public synchronized void write(byte b[], int off, int len) throws IOException {
        if (enableActivityLog)
            log.debug("##### write byte array! off:"+off+" len:"+len);
        if (len >= buf.length) {
            flushBuffer();
            out.write(b, off, len);
            doLog(b, len);
            return;
        }
        if (len > buf.length - count) {
            flushBuffer();
        }
        System.arraycopy(b, off, buf, count, len);
        count += len;
    }

    @Override
    public synchronized void flush() throws IOException {
        if (enableActivityLog)
            log.debug("##### flush called");
        flushBuffer();
        out.flush();
    }
    
    @Override
    public synchronized void close() throws IOException {
        if (enableActivityLog)
            log.debug("##### close called");
    }

    
    private void doLog(byte[] data, int len) {
        if (enableLog && len > 0) {
            if (maxLogLen > 0) {
                try {
                    if (len > maxLogLen) {
                        transformer.log(msgHeader, data, maxLogLen, "...(truncated)");
                    } else {
                        transformer.log(msgHeader, data, len, null);
                    }
                } catch (Exception x) {
                    log.warn("Logging failed!", x);
                }
                maxLogLen -= len;
            } else if (enableActivityLog) {
                log.debug("##### log ignored! maxLogLen reached!");
            }
        }
    }

    public interface ByteArrayTransformer {
        void log(String msgHeader, byte[] b, int len, String msgPostfix);
    }
    
    public class ByteArrayStringTransformer implements ByteArrayTransformer {

        @Override
        public void log(String msgHeader, byte[] b, int len, String msgPostfix) {
            StringBuilder sb = new StringBuilder(len+100);
            if (msgHeader != null)
                sb.append(msgHeader);
            sb.append(new String(b, 0, len, charset));
            if (msgPostfix != null)
                sb.append(msgPostfix);
            log.info(sb.toString());
        }
    }
    
    public class ByteArrayHexTransformer implements ByteArrayTransformer {

        private int blockSize = 256;

        private final char[] LINE_CHARS = "00000000 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00 00   ................".toCharArray();
        private static final int HEX_START_POS = 9;
        private static final int TEXT_START_POS = 59;
        
        private int logIdx = 0;
        
        @Override
        public void log(String msgHeader, byte[] b, int len, String msgPostfix) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0 ; i < len ; i++, logIdx++) {
                if ((logIdx & 0x0f) == 0) {
                    if (i > 0)
                    Integer.toHexString(i).toCharArray();
                }
            }
        }
    }
}
