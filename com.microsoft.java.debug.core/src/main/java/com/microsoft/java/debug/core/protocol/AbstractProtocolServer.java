/*******************************************************************************
* Copyright (c) 2017 Microsoft Corporation and others.
* All rights reserved. This program and the accompanying materials
* are made available under the terms of the Eclipse Public License v1.0
* which accompanies this distribution, and is available at
* http://www.eclipse.org/legal/epl-v10.html
*
* Contributors:
*     Microsoft Corporation - initial API and implementation
*******************************************************************************/

package com.microsoft.java.debug.core.protocol;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public abstract class AbstractProtocolServer {
    private static final Logger logger = Logger.getLogger("java-debug");
    private static final int BUFFER_SIZE = 4096;
    private static final String TWO_CRLF = "\r\n\r\n";
    private static final Pattern CONTENT_LENGTH_MATCHER = Pattern.compile("Content-Length: (\\d+)");
    private static final Charset PROTOCOL_ENCODING = StandardCharsets.UTF_8; // vscode protocol uses UTF-8 as encoding format.

    protected boolean terminateSession = false;

    private Reader reader;
    private Writer writer;

    private ByteBuffer rawData;
    private int contentLength = -1;
    private AtomicInteger sequenceNumber = new AtomicInteger(1);

    private boolean isDispatchingData;
    private ConcurrentLinkedQueue<Messages.Event> eventQueue;

    /**
     * Constructs a protocol server instance based on the given input stream and output stream.
     * @param input
     *              the input stream
     * @param output
     *              the output stream
     */
    public AbstractProtocolServer(InputStream input, OutputStream output) {
        reader = new BufferedReader(new InputStreamReader(input, PROTOCOL_ENCODING));
        writer = new PrintWriter(new BufferedWriter(new OutputStreamWriter(output, PROTOCOL_ENCODING)));
        contentLength = -1;
        rawData = new ByteBuffer();
        eventQueue = new ConcurrentLinkedQueue<>();
    }

    /**
     * A while-loop to parse input data and send output data constantly.
     */
    public void start() {
        char[] buffer = new char[BUFFER_SIZE];
        try {
            while (!terminateSession) {
                int read = reader.read(buffer, 0, BUFFER_SIZE);
                if (read == -1) {
                    break;
                }

                rawData.append(new String(buffer, 0, read).getBytes(PROTOCOL_ENCODING));
                this.processData();
            }
        } catch (IOException e) {
            logger.log(Level.SEVERE, String.format("Read data from io exception: %s", e.toString()), e);
        }
    }

    /**
     * Sets terminateSession flag to true. And the dispatcher loop will be terminated after current dispatching operation finishes.
     */
    public void stop() {
        terminateSession = true;
    }

    /**
     * Send event to DA immediately.
     * @param eventType
     *                 event type
     * @param body
     *                 event body
     */
    protected void sendEvent(String eventType, Object body) {
        sendMessage(new Messages.Event(eventType, body));
    }

    /**
     * If the the dispatcher is idle, then send the event immediately.
     * Else add the new event to an eventQueue first and send them when dispatcher becomes idle again.
     * @param eventType
     *              event type
     * @param body
     *              event content
     */
    protected void sendEventLater(String eventType, Object body) {
        synchronized (this) {
            if (isDispatchingData) {
                eventQueue.offer(new Messages.Event(eventType, body));
            } else {
                sendMessage(new Messages.Event(eventType, body));
            }
        }
    }

    protected void sendMessage(Messages.ProtocolMessage message) {
        message.seq = sequenceNumber.getAndIncrement();

        String jsonMessage = JsonUtils.toJson(message);
        byte[] jsonBytes = jsonMessage.getBytes(PROTOCOL_ENCODING);

        String header = String.format("Content-Length: %d%s", jsonBytes.length, TWO_CRLF);
        byte[] headerBytes = header.getBytes(PROTOCOL_ENCODING);

        ByteBuffer data = new ByteBuffer();
        data.append(headerBytes);
        data.append(jsonBytes);

        String utf8Data = data.getString(PROTOCOL_ENCODING);

        try {
            logger.fine("\n[[RESPONSE]]\n" + utf8Data);
            writer.write(utf8Data);
            writer.flush();
        } catch (IOException e) {
            logger.log(Level.SEVERE, String.format("Write data to io exception: %s", e.toString()), e);
        }
    }

    private void processData() {
        while (true) {
            /**
             * In vscode debug protocol, the content length represents the message's byte length with utf8 format.
             */
            if (contentLength >= 0) {
                if (rawData.length() >= contentLength) {
                    byte[] buf = rawData.removeFirst(contentLength);
                    contentLength = -1;
                    String messageData = new String(buf, PROTOCOL_ENCODING);
                    Messages.ProtocolMessage message = JsonUtils.fromJson(messageData, Messages.ProtocolMessage.class);

                    logger.fine(String.format("\n[%s]\n%s", message.type, messageData));

                    if (message.type.equals("request")) {
                        try {
                            synchronized (this) {
                                isDispatchingData = true;
                            }
                            this.dispatchRequest(JsonUtils.fromJson(messageData, Messages.Request.class));
                        } catch (Exception e) {
                            e.printStackTrace();
                            logger.log(Level.SEVERE, String.format("Dispatch debug protocol error: %s", e.toString()), e);
                        } finally {
                            synchronized (this) {
                                isDispatchingData = false;
                            }

                            while (eventQueue.peek() != null) {
                                sendMessage(eventQueue.poll());
                            }
                        }
                    } else if (message.type.equals("response")) {
                        // handle response.
                    }
                    continue;
                }
            } else {
                String rawMessage = rawData.getString(PROTOCOL_ENCODING);
                int idx = rawMessage.indexOf(TWO_CRLF);
                if (idx != -1) {
                    Matcher matcher = CONTENT_LENGTH_MATCHER.matcher(rawMessage);
                    if (matcher.find()) {
                        contentLength = Integer.parseInt(matcher.group(1));
                        int headerByteLength = rawMessage.substring(0, idx + TWO_CRLF.length()).getBytes(PROTOCOL_ENCODING).length;
                        rawData.removeFirst(headerByteLength); // Remove the header from the raw message.
                        continue;
                    }
                }
            }
            break;
        }
    }

    protected abstract void dispatchRequest(Messages.Request request);

    class ByteBuffer {
        private byte[] buffer;

        public ByteBuffer() {
            buffer = new byte[0];
        }

        public int length() {
            return buffer.length;
        }

        public String getString(Charset cs) {
            return new String(buffer, cs);
        }

        public void append(byte[] b) {
            append(b, b.length);
        }

        public void append(byte[] b, int length) {
            byte[] newBuffer = new byte[buffer.length + length];
            System.arraycopy(buffer, 0, newBuffer, 0, buffer.length);
            System.arraycopy(b, 0, newBuffer, buffer.length, length);
            buffer = newBuffer;
        }

        public byte[] removeFirst(int n) {
            byte[] b = new byte[n];
            System.arraycopy(buffer, 0, b, 0, n);
            byte[] newBuffer = new byte[buffer.length - n];
            System.arraycopy(buffer, n, newBuffer, 0, buffer.length - n);
            buffer = newBuffer;
            return b;
        }
    }
}
